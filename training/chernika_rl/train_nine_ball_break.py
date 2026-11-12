"""Train a separate continuous-action imitation model for nine-ball breaks."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, TensorDataset

from .nine_ball_break import BreakPolicy


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="artifacts/nine_ball_break_dataset.npz")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--seed", type=int, default=911)
    parser.add_argument("--output", default="artifacts/nine_ball_break_policy.pt")
    parser.add_argument("--min-demonstrations", type=int, default=100)
    args = parser.parse_args()

    if args.epochs < 1 or args.batch_size < 1:
        raise ValueError("epochs and batch-size must be positive")
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    torch.set_num_threads(2)

    data = np.load(args.data)
    observations = data["observations"].astype(np.float32)
    actions = data["actions"].astype(np.float32)
    if len(observations) < args.min_demonstrations:
        raise ValueError(
            f"Need at least {args.min_demonstrations} demonstrations, got {len(observations)}"
        )
    if observations.ndim != 2 or observations.shape[1] != 52:
        raise ValueError("Break observations must have shape [N, 52]")
    if actions.shape != (len(observations), 2):
        raise ValueError("Break actions must have shape [N, 2]")

    order = np.random.permutation(len(observations))
    split = max(1, int(len(order) * 0.90))
    train_ids, validation_ids = order[:split], order[split:]
    if len(validation_ids) == 0:
        validation_ids = train_ids[-1:]
        train_ids = train_ids[:-1]

    x = torch.from_numpy(observations)
    y = torch.from_numpy(actions)
    loader = DataLoader(
        TensorDataset(x[train_ids], y[train_ids]),
        batch_size=args.batch_size,
        shuffle=True,
    )
    model = BreakPolicy()
    optimizer = torch.optim.AdamW(model.parameters(), lr=8e-4, weight_decay=1e-4)
    loss_fn = nn.SmoothL1Loss()
    best_loss = float("inf")
    best_state = None
    history = []

    for epoch in range(1, args.epochs + 1):
        model.train()
        train_total = 0.0
        train_count = 0
        for batch_x, batch_y in loader:
            prediction = model(batch_x)
            loss = loss_fn(prediction, batch_y)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
            optimizer.step()
            train_total += float(loss.item()) * len(batch_x)
            train_count += len(batch_x)

        model.eval()
        with torch.no_grad():
            validation_prediction = model(x[validation_ids])
            validation_loss = float(
                loss_fn(validation_prediction, y[validation_ids]).item()
            )
            action_mae = float(
                (validation_prediction - y[validation_ids]).abs().mean().item()
            )
        history.append({
            "epoch": epoch,
            "train_loss": train_total / max(1, train_count),
            "validation_loss": validation_loss,
            "validation_action_mae": action_mae,
        })
        if validation_loss < best_loss:
            best_loss = validation_loss
            best_state = {
                key: value.detach().cpu().clone()
                for key, value in model.state_dict().items()
            }

    if best_state is None:
        raise RuntimeError("Break training did not produce a checkpoint")
    model.load_state_dict(best_state)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    torch.save({
        "state_dict": model.state_dict(),
        "hidden_size": 128,
        "observation_size": 52,
        "metadata": {
            "algorithm": "break behavioral cloning",
            "demonstrations": len(observations),
            "epochs": args.epochs,
            "seed": args.seed,
            "best_validation_loss": best_loss,
            "best_validation_action_mae": min(
                row["validation_action_mae"] for row in history
            ),
        },
    }, output)
    output.with_suffix(".metrics.json").write_text(
        json.dumps({"history": history}, indent=2)
    )
    print(json.dumps({
        "demonstrations": len(observations),
        "best_validation_loss": best_loss,
        "best_validation_action_mae": min(
            row["validation_action_mae"] for row in history
        ),
        "checkpoint": str(output),
    }, indent=2))


if __name__ == "__main__":
    main()
