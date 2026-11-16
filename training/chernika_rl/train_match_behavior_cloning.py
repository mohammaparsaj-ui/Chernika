"""Train a supervised actor from legal complete-rack teacher demonstrations."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, TensorDataset

OBSERVATION_SIZE = 52
ACTION_SIZE = 2


class BehaviorCloningActor(nn.Module):
    """Architecture intentionally matches the PPO actor's two 256-unit layers."""

    def __init__(self, hidden_size: int = 256) -> None:
        super().__init__()
        self.observation_size = OBSERVATION_SIZE
        self.hidden_size = int(hidden_size)
        self.net = nn.Sequential(
            nn.Linear(OBSERVATION_SIZE, hidden_size),
            nn.Tanh(),
            nn.Linear(hidden_size, hidden_size),
            nn.Tanh(),
            nn.Linear(hidden_size, ACTION_SIZE),
        )

    def forward(self, observation: torch.Tensor) -> torch.Tensor:
        return self.net(observation)


def split_by_group(
    groups: np.ndarray, seed: int, validation_fraction: float = 0.10
) -> tuple[np.ndarray, np.ndarray]:
    """Split by complete rack, never by adjacent frames or individual shots."""
    values = np.asarray(groups)
    if values.ndim != 1 or len(values) < 2:
        raise ValueError("groups must be a one-dimensional array with at least two samples")
    unique = np.unique(values)
    if len(unique) < 2:
        raise ValueError("at least two distinct rack groups are required")
    if not 0.0 < validation_fraction < 0.5:
        raise ValueError("validation_fraction must be between 0 and 0.5")

    rng = np.random.default_rng(seed)
    shuffled = unique.copy()
    rng.shuffle(shuffled)
    validation_count = max(1, int(round(len(shuffled) * validation_fraction)))
    validation_count = min(validation_count, len(shuffled) - 1)
    validation_groups = shuffled[:validation_count]
    validation_mask = np.isin(values, validation_groups)
    train_ids = np.flatnonzero(~validation_mask)
    validation_ids = np.flatnonzero(validation_mask)
    if not len(train_ids) or not len(validation_ids):
        raise ValueError("grouped split produced an empty train or validation set")
    return train_ids, validation_ids


def train_actor(
    observations: np.ndarray,
    actions: np.ndarray,
    groups: np.ndarray,
    weights: np.ndarray | None = None,
    *,
    epochs: int = 80,
    batch_size: int = 128,
    seed: int = 123,
    learning_rate: float = 8e-4,
    device: str = "cpu",
) -> tuple[BehaviorCloningActor, dict[str, Any]]:
    x_np = np.asarray(observations, dtype=np.float32)
    y_np = np.asarray(actions, dtype=np.float32)
    group_np = np.asarray(groups, dtype=np.int64)
    if x_np.ndim != 2 or x_np.shape[1] != OBSERVATION_SIZE:
        raise ValueError("observations must have shape (N, 52)")
    if y_np.shape != (len(x_np), ACTION_SIZE):
        raise ValueError("actions must have shape (N, 2)")
    if group_np.shape != (len(x_np),):
        raise ValueError("groups must contain one rack ID per sample")
    if len(x_np) < 2 or epochs < 1 or batch_size < 1:
        raise ValueError("need at least two samples, positive epochs and batch size")
    if not np.all(np.isfinite(x_np)) or not np.all(np.isfinite(y_np)):
        raise ValueError("training arrays must be finite")
    if np.any(np.abs(y_np) > 1.0001):
        raise ValueError("teacher actions must be normalized to [-1, 1]")

    if weights is None:
        w_np = np.ones(len(x_np), dtype=np.float32)
    else:
        w_np = np.asarray(weights, dtype=np.float32)
        if w_np.shape != (len(x_np),) or not np.all(np.isfinite(w_np)):
            raise ValueError("weights must be a finite vector with one value per sample")
        if np.any(w_np <= 0):
            raise ValueError("all sample weights must be positive")

    train_ids, validation_ids = split_by_group(group_np, seed)
    torch.manual_seed(seed)
    np.random.seed(seed)
    torch.set_num_threads(2)
    dev = torch.device(device)

    x = torch.from_numpy(x_np).to(dev)
    y = torch.from_numpy(y_np).to(dev)
    w = torch.from_numpy(w_np).to(dev)
    dataset = TensorDataset(x[train_ids], y[train_ids], w[train_ids])
    loader = DataLoader(
        dataset,
        batch_size=min(batch_size, len(train_ids)),
        shuffle=True,
        generator=torch.Generator().manual_seed(seed),
    )

    model = BehaviorCloningActor().to(dev)
    optimizer = torch.optim.AdamW(model.parameters(), lr=learning_rate, weight_decay=1e-4)
    best_validation = float("inf")
    best_state = None
    history: list[dict[str, float | int]] = []

    for epoch in range(1, epochs + 1):
        model.train()
        total_loss = 0.0
        sample_count = 0
        for batch_x, batch_y, batch_weight in loader:
            prediction = model(batch_x)
            per_sample = ((prediction - batch_y) ** 2).mean(dim=1)
            loss = (per_sample * batch_weight).mean()
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
            optimizer.step()
            total_loss += float(loss.detach().cpu()) * len(batch_x)
            sample_count += len(batch_x)

        model.eval()
        with torch.no_grad():
            prediction = model(x[validation_ids])
            per_sample = ((prediction - y[validation_ids]) ** 2).mean(dim=1)
            validation_mse = float(per_sample.mean().cpu())
            validation_mae = float(
                (prediction - y[validation_ids]).abs().mean().cpu()
            )
        history.append({
            "epoch": epoch,
            "train_mse": total_loss / max(1, sample_count),
            "validation_mse": validation_mse,
            "validation_action_mae": validation_mae,
        })
        if validation_mse < best_validation:
            best_validation = validation_mse
            best_state = {
                key: value.detach().cpu().clone()
                for key, value in model.state_dict().items()
            }

    if best_state is None:
        raise RuntimeError("behavior-cloning training did not produce a checkpoint")
    model.load_state_dict(best_state)
    model.to("cpu")
    model.eval()

    metadata = {
        "algorithm": "behavioral_cloning",
        "observation_size": OBSERVATION_SIZE,
        "action_size": ACTION_SIZE,
        "hidden_size": 256,
        "samples": int(len(x_np)),
        "unique_racks": int(len(np.unique(group_np))),
        "training_samples": int(len(train_ids)),
        "validation_samples": int(len(validation_ids)),
        "training_racks": int(len(np.unique(group_np[train_ids]))),
        "validation_racks": int(len(np.unique(group_np[validation_ids]))),
        "epochs": epochs,
        "seed": seed,
        "best_validation_mse": best_validation,
        "best_validation_action_mae": min(
            float(row["validation_action_mae"]) for row in history
        ),
        "split_strategy": "grouped_by_complete_rack",
    }
    return model, {"metadata": metadata, "history": history}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="artifacts/nine_ball_match_demonstrations.npz")
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--seed", type=int, default=123)
    parser.add_argument("--learning-rate", type=float, default=8e-4)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--min-demonstrations", type=int, default=100)
    parser.add_argument("--output", default="artifacts/nine_ball_match_bc.pt")
    args = parser.parse_args()

    data = np.load(args.data, allow_pickle=False)
    observations = data["observations"]
    actions = data["actions"]
    groups = data["groups"]
    weights = data["weights"] if "weights" in data.files else None
    if len(observations) < args.min_demonstrations:
        raise ValueError(
            f"need at least {args.min_demonstrations} demonstrations, got {len(observations)}"
        )

    model, report = train_actor(
        observations,
        actions,
        groups,
        weights,
        epochs=args.epochs,
        batch_size=args.batch_size,
        seed=args.seed,
        learning_rate=args.learning_rate,
        device=args.device,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    torch.save({
        "state_dict": model.state_dict(),
        "observation_size": OBSERVATION_SIZE,
        "action_size": ACTION_SIZE,
        "hidden_size": 256,
        "metadata": report["metadata"],
    }, output)
    output.with_suffix(".metrics.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report["metadata"], indent=2))
    print(f"Saved behavior-cloning actor to {output}")


if __name__ == "__main__":
    main()
