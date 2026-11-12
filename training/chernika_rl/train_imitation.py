"""Train a pocket-classification + angle/power behavioral-cloning policy."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, TensorDataset

from .imitation import ImitationPolicy, POCKET_COUNT
from .physics import (
    POCKETS, TABLE_LEFT, TABLE_TOP, TABLE_WIDTH_PX, TABLE_HEIGHT_PX,
)


def _split_indices(
    sample_count: int,
    seed: int,
    groups: np.ndarray | None = None,
) -> tuple[np.ndarray, np.ndarray, str]:
    """Split by rack when group labels exist, preventing adjacent-state leakage."""
    rng = np.random.default_rng(seed)
    if groups is not None:
        groups = np.asarray(groups)
        if groups.shape != (sample_count,):
            raise ValueError("groups must contain one rack ID per demonstration")
        unique_groups = np.unique(groups)
        if len(unique_groups) >= 2:
            rng.shuffle(unique_groups)
            validation_group_count = max(1, int(round(len(unique_groups) * 0.10)))
            validation_group_count = min(validation_group_count, len(unique_groups) - 1)
            validation_groups = unique_groups[:validation_group_count]
            validation_mask = np.isin(groups, validation_groups)
            train_ids = np.flatnonzero(~validation_mask)
            validation_ids = np.flatnonzero(validation_mask)
            if len(train_ids) and len(validation_ids):
                return train_ids, validation_ids, "rack_grouped"

    order = rng.permutation(sample_count)
    split = max(1, int(sample_count * 0.90))
    train_ids, validation_ids = order[:split], order[split:]
    if len(validation_ids) == 0:
        validation_ids = train_ids[-1:]
        train_ids = train_ids[:-1]
    return train_ids, validation_ids, "random_sample"



def augment_nine_ball_symmetries(
    observations: np.ndarray,
    actions: np.ndarray,
    pockets: np.ndarray,
    successes: np.ndarray,
    first_contacts: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Apply horizontal, vertical and 180-degree table symmetries to 52-value states.

    Pocket coordinates are reset to the canonical measured anchors after each
    transform. Missing-ball sentinels remain unchanged, and mirrored angle
    offsets change sign while power remains unchanged.
    """
    observations = np.asarray(observations, dtype=np.float32)
    actions = np.asarray(actions, dtype=np.float32)
    pockets = np.asarray(pockets, dtype=np.int64)
    successes = np.asarray(successes, dtype=np.float32)
    first_contacts = np.asarray(first_contacts, dtype=np.float32)
    count = len(observations)
    if observations.ndim != 2 or observations.shape[1] != 52:
        raise ValueError("nine-ball symmetry augmentation requires 52-value observations")
    if actions.shape != (count, 2):
        raise ValueError("actions must have shape (N, 2)")
    for values, name in (
        (pockets, "pockets"),
        (successes, "successes"),
        (first_contacts, "first_contacts"),
    ):
        if values.shape != (count,):
            raise ValueError(f"{name} must have one value per observation")
    if np.any(pockets < 0) or np.any(pockets >= POCKET_COUNT):
        raise ValueError("Pocket labels must be integers in [0, 5]")

    horizontal_map = np.asarray((1, 0, 3, 2, 5, 4), dtype=np.int64)
    vertical_map = np.asarray((4, 5, 2, 3, 0, 1), dtype=np.int64)
    rotate_map = np.asarray((5, 4, 3, 2, 1, 0), dtype=np.int64)
    pocket_x = np.asarray(
        [(p.x - TABLE_LEFT) / TABLE_WIDTH_PX for p in POCKETS],
        dtype=np.float32,
    )
    pocket_y = np.asarray(
        [(p.y - TABLE_TOP) / TABLE_HEIGHT_PX for p in POCKETS],
        dtype=np.float32,
    )

    base_observations = observations.copy()
    for pocket_index in range(6):
        base_observations[:, 38 + 2 * pocket_index] = pocket_x[pocket_index]
        base_observations[:, 39 + 2 * pocket_index] = pocket_y[pocket_index]

    transformed_observations = [base_observations]
    transformed_actions = [actions.copy()]
    transformed_pockets = [pockets.copy()]

    for flip_x, flip_y, pocket_map in (
        (True, False, horizontal_map),
        (False, True, vertical_map),
        (True, True, rotate_map),
    ):
        state = base_observations.copy()
        if flip_x:
            state[:, 0] = 1.0 - state[:, 0]
        if flip_y:
            state[:, 1] = 1.0 - state[:, 1]

        for ball_index in range(9):
            base = 2 + 3 * ball_index
            present = state[:, base + 2] > 0.5
            if flip_x:
                state[present, base] = 1.0 - state[present, base]
            if flip_y:
                state[present, base + 1] = 1.0 - state[present, base + 1]

        for pocket_index in range(6):
            state[:, 38 + 2 * pocket_index] = pocket_x[pocket_index]
            state[:, 39 + 2 * pocket_index] = pocket_y[pocket_index]

        action = actions.copy()
        if flip_x != flip_y:
            action[:, 0] *= -1.0

        transformed_observations.append(state)
        transformed_actions.append(action)
        transformed_pockets.append(pocket_map[pockets])

    return (
        np.concatenate(transformed_observations, axis=0),
        np.concatenate(transformed_actions, axis=0),
        np.concatenate(transformed_pockets, axis=0),
        np.tile(successes, 4),
        np.tile(first_contacts, 4),
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="artifacts/teacher_dataset.npz")
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--seed", type=int, default=123)
    parser.add_argument("--output", default="artifacts/imitation_policy.pt")
    parser.add_argument("--min-demonstrations", type=int, default=100)
    parser.add_argument(
        "--symmetry-augmentation",
        action="store_true",
        help="augment 52-value nine-ball states with horizontal/vertical table symmetries",
    )
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    torch.set_num_threads(2)

    data = np.load(args.data)
    observations = data["observations"].astype(np.float32)
    actions = data["actions"].astype(np.float32)
    pockets = data["pockets"].astype(np.int64)
    successes = data["successes"].astype(np.float32)
    first_contacts = data["first_contacts"].astype(np.float32)
    groups = data["groups"].astype(np.int64) if "groups" in data.files else None

    if len(observations) < args.min_demonstrations:
        raise ValueError(
            f"Need at least {args.min_demonstrations} demonstrations, got {len(observations)}"
        )
    if (
        observations.ndim != 2
        or observations.shape[1] not in (16, 52)
        or actions.ndim != 2
        or actions.shape[1] != 2
    ):
        raise ValueError("Expected 16- or 52-value observations and 2-value actions")
    if np.any(pockets < 0) or np.any(pockets >= POCKET_COUNT):
        raise ValueError("Pocket labels must be integers in [0, 5]")

    train_ids, validation_ids, split_strategy = _split_indices(
        len(observations), args.seed, groups
    )
    train_observations = observations[train_ids]
    train_actions = actions[train_ids]
    train_pockets = pockets[train_ids]
    train_successes = successes[train_ids]
    train_first_contacts = first_contacts[train_ids]
    if args.symmetry_augmentation:
        (
            train_observations,
            train_actions,
            train_pockets,
            train_successes,
            train_first_contacts,
        ) = augment_nine_ball_symmetries(
            train_observations,
            train_actions,
            train_pockets,
            train_successes,
            train_first_contacts,
        )
    x_train = torch.from_numpy(train_observations)
    y_action_train = torch.from_numpy(train_actions)
    y_pocket_train = torch.from_numpy(train_pockets)
    weights_train = torch.from_numpy(
        1.0 + 2.0 * train_successes + 0.5 * train_first_contacts
    )
    x_validation = torch.from_numpy(observations[validation_ids])
    y_action_validation = torch.from_numpy(actions[validation_ids])
    y_pocket_validation = torch.from_numpy(pockets[validation_ids])

    train_ds = TensorDataset(
        x_train, y_action_train, y_pocket_train, weights_train
    )
    loader = DataLoader(train_ds, batch_size=args.batch_size, shuffle=True)

    model = ImitationPolicy(
        hidden_size=128,
        observation_size=int(observations.shape[1]),
    )
    optimizer = torch.optim.AdamW(model.parameters(), lr=8e-4, weight_decay=1e-4)
    classification_loss = nn.CrossEntropyLoss()
    best_validation = float("inf")
    best_state = None
    history = []

    for epoch in range(1, args.epochs + 1):
        model.train()
        train_total = 0.0
        train_count = 0
        for batch_x, batch_action, batch_pocket, batch_weight in loader:
            output = model(batch_x)
            logits = output[:, :POCKET_COUNT]
            predicted_action = output[:, POCKET_COUNT:]
            pocket_loss = classification_loss(logits, batch_pocket)
            per_sample_action_loss = ((predicted_action - batch_action) ** 2).mean(dim=1)
            action_loss = (per_sample_action_loss * batch_weight).mean()
            loss = pocket_loss + 2.0 * action_loss

            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
            optimizer.step()
            train_total += float(loss.item()) * len(batch_x)
            train_count += len(batch_x)

        model.eval()
        with torch.no_grad():
            validation_output = model(x_validation)
            val_pocket_loss = classification_loss(
                validation_output[:, :POCKET_COUNT], y_pocket_validation
            )
            val_action_loss = (
                (validation_output[:, POCKET_COUNT:] - y_action_validation) ** 2
            ).mean()
            val_loss = float((val_pocket_loss + 2.0 * val_action_loss).item())
            pocket_accuracy = float(
                (validation_output[:, :POCKET_COUNT].argmax(dim=1)
                 == y_pocket_validation).float().mean().item()
            )
            action_mae = float(
                (validation_output[:, POCKET_COUNT:]
                 - y_action_validation).abs().mean().item()
            )

        history.append({
            "epoch": epoch,
            "train_loss": train_total / max(1, train_count),
            "validation_loss": val_loss,
            "validation_pocket_accuracy": pocket_accuracy,
            "validation_action_mae": action_mae,
        })
        if val_loss < best_validation:
            best_validation = val_loss
            best_state = {
                key: value.detach().cpu().clone()
                for key, value in model.state_dict().items()
            }

    if best_state is None:
        raise RuntimeError("Training failed to produce a checkpoint")
    model.load_state_dict(best_state)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.save({
        "state_dict": model.state_dict(),
        "hidden_size": 128,
        "observation_size": int(observations.shape[1]),
        "metadata": {
            "algorithm": "behavioral-cloning",
            "demonstrations": int(len(observations)),
            "training_examples_after_augmentation": int(len(train_observations)),
            "symmetry_augmentation": bool(args.symmetry_augmentation),
            "epochs": args.epochs,
            "seed": args.seed,
            "split_strategy": split_strategy,
            "training_racks": (
                int(len(np.unique(groups[train_ids]))) if groups is not None else None
            ),
            "validation_racks": (
                int(len(np.unique(groups[validation_ids]))) if groups is not None else None
            ),
            "best_validation_loss": best_validation,
            "best_validation_pocket_accuracy": max(
                row["validation_pocket_accuracy"] for row in history
            ),
            "best_validation_action_mae": min(
                row["validation_action_mae"] for row in history
            ),
        },
    }, output_path)
    output_path.with_suffix(".metrics.json").write_text(
        json.dumps({"history": history}, indent=2)
    )
    print(json.dumps({
        "demonstrations": len(observations),
        "training_examples_after_augmentation": len(train_observations),
        "symmetry_augmentation": bool(args.symmetry_augmentation),
        "split_strategy": split_strategy,
        "best_validation_loss": best_validation,
        "best_validation_pocket_accuracy": max(
            row["validation_pocket_accuracy"] for row in history
        ),
        "best_validation_action_mae": min(
            row["validation_action_mae"] for row in history
        ),
        "checkpoint": str(output_path),
    }, indent=2))


if __name__ == "__main__":
    main()
