"""Generate complete-rack teacher demonstrations for match-policy pretraining."""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

import numpy as np

from .nine_ball_env import OBSERVATION_SIZE
from .nine_ball_rollout import run_rack

ACTION_SIZE = 2
BALL_START = 2
POCKET_START = 38


def mirror_horizontal(
    observation: np.ndarray, action: np.ndarray
) -> tuple[np.ndarray, np.ndarray]:
    """Mirror a state across the table's vertical centre line.

    The 52-value observation stores normalized x/y pairs for the cue ball,
    nine numbered balls, and six pockets. Missing-ball slots use a negative
    presence mask and must remain untouched. A horizontal reflection reverses
    the signed angle offset while preserving power.
    """
    obs = np.asarray(observation, dtype=np.float32).copy()
    act = np.asarray(action, dtype=np.float32).copy()
    if obs.shape != (OBSERVATION_SIZE,):
        raise ValueError(f"expected {OBSERVATION_SIZE}-value observation")
    if act.shape != (ACTION_SIZE,):
        raise ValueError(f"expected {ACTION_SIZE}-value action")
    if not np.all(np.isfinite(obs)) or not np.all(np.isfinite(act)):
        raise ValueError("observation and action must be finite")

    obs[0] = 1.0 - obs[0]
    for ball_index in range(9):
        x_index = BALL_START + 3 * ball_index
        presence_index = x_index + 2
        if obs[presence_index] > 0.5:
            obs[x_index] = 1.0 - obs[x_index]
    for pocket_index in range(6):
        x_index = POCKET_START + 2 * pocket_index
        obs[x_index] = 1.0 - obs[x_index]

    act[0] = -act[0]
    return obs, act


def generate(
    racks: int,
    seed: int,
    *,
    max_shots: int = 48,
    depth: int = 2,
    mirror: bool = True,
) -> dict[str, Any]:
    """Collect every legal action from teacher-vs-teacher complete racks."""
    if racks < 2:
        raise ValueError("at least two racks are required for grouped validation")
    if max_shots < 2:
        raise ValueError("max_shots must be at least 2")
    if depth not in (1, 2):
        raise ValueError("depth must be 1 or 2")

    observations: list[np.ndarray] = []
    actions: list[np.ndarray] = []
    groups: list[int] = []
    players: list[int] = []
    break_flags: list[bool] = []
    successes: list[bool] = []
    first_contacts: list[bool] = []
    sample_weights: list[float] = []
    shot_types: list[str] = []
    rack_statuses: Counter[str] = Counter()
    rack_wins = [0, 0]
    excluded_illegal = 0
    total_rows = 0

    for rack_index in range(racks):
        result = run_rack(
            seed + rack_index,
            strategy="teacher_lookahead",
            opponent_strategy="teacher_lookahead",
            max_shots=max_shots,
            depth=depth,
            top_k=3,
        )
        rack_statuses[str(result.get("status", "unknown"))] += 1
        if result.get("winner") in (0, 1):
            rack_wins[int(result["winner"])] += 1

        for shot in result.get("trajectory", []):
            total_rows += 1
            if not bool(shot.get("legal", False)):
                excluded_illegal += 1
                continue
            try:
                obs = np.asarray(shot["observation"], dtype=np.float32)
                act = np.asarray(shot["action"], dtype=np.float32)
            except (KeyError, TypeError, ValueError):
                continue
            if obs.shape != (OBSERVATION_SIZE,) or act.shape != (ACTION_SIZE,):
                continue
            if not np.all(np.isfinite(obs)) or not np.all(np.isfinite(act)):
                continue
            if np.any(np.abs(act) > 1.0001):
                continue

            pocketed = bool(shot.get("pocketed_numbers"))
            first_contact = (
                shot.get("first_contact") is not None
                and shot.get("first_contact") == shot.get("legal_ball")
            )
            is_break = bool(shot.get("break", False))
            shot_type = str(shot.get("shot_type") or ("break" if is_break else "unknown"))
            weight = 1.0 + 0.75 * float(pocketed) + 0.25 * float(first_contact)

            observations.append(obs)
            actions.append(np.clip(act, -1.0, 1.0))
            groups.append(rack_index)
            players.append(int(shot.get("player", 0)))
            break_flags.append(is_break)
            successes.append(pocketed)
            first_contacts.append(first_contact)
            sample_weights.append(weight)
            shot_types.append(shot_type)

            if mirror:
                mirrored_obs, mirrored_act = mirror_horizontal(obs, act)
                observations.append(mirrored_obs)
                actions.append(np.clip(mirrored_act, -1.0, 1.0))
                groups.append(rack_index)
                players.append(int(shot.get("player", 0)))
                break_flags.append(is_break)
                successes.append(pocketed)
                first_contacts.append(first_contact)
                sample_weights.append(weight)
                shot_types.append(f"{shot_type}_horizontal_mirror")

    if not observations:
        raise RuntimeError(
            "No legal teacher actions were collected; inspect rack simulation and break legality"
        )
    if len(set(groups)) < 2:
        raise RuntimeError(
            "Demonstrations came from fewer than two racks; grouped validation would leak"
        )

    metadata = {
        "schema": "chernika.nineball.match_demonstrations.v1",
        "requested_racks": racks,
        "seed_start": seed,
        "max_shots": max_shots,
        "teacher_strategy": "teacher_lookahead_vs_teacher_lookahead",
        "lookahead_depth": depth,
        "mirror_horizontal": mirror,
        "raw_trajectory_rows": total_rows,
        "excluded_illegal_or_invalid_rows": excluded_illegal,
        "usable_samples_including_augmentation": len(observations),
        "unique_racks_with_samples": len(set(groups)),
        "unique_players": sorted(set(players)),
        "legal_pot_sample_rate": float(np.mean(successes)),
        "first_contact_sample_rate": float(np.mean(first_contacts)),
        "break_sample_count": int(sum(break_flags)),
        "rack_status_counts": dict(rack_statuses),
        "rack_wins_by_player": rack_wins,
        "label_source": "physics-teacher complete-rack rollouts",
        "human_recordings_used_as_action_labels": False,
        "note": (
            "Human video event/OCR labels are not converted into angle/power targets. "
            "Only legal, simulator-verified teacher actions are used here; reviewed "
            "human action labels can be added later when exact pre-shot state and "
            "aim/power are available."
        ),
    }
    return {
        "observations": np.asarray(observations, dtype=np.float32),
        "actions": np.asarray(actions, dtype=np.float32),
        "groups": np.asarray(groups, dtype=np.int64),
        "players": np.asarray(players, dtype=np.int8),
        "break_flags": np.asarray(break_flags, dtype=np.bool_),
        "successes": np.asarray(successes, dtype=np.bool_),
        "first_contacts": np.asarray(first_contacts, dtype=np.bool_),
        "weights": np.asarray(sample_weights, dtype=np.float32),
        "shot_types": np.asarray(shot_types, dtype=str),
        "metadata": metadata,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--racks", type=int, default=200)
    parser.add_argument("--seed", type=int, default=98000)
    parser.add_argument("--max-shots", type=int, default=48)
    parser.add_argument("--depth", type=int, choices=(1, 2), default=2)
    parser.add_argument("--no-mirror", action="store_true")
    parser.add_argument(
        "--output", default="artifacts/nine_ball_match_demonstrations.npz"
    )
    args = parser.parse_args()

    dataset = generate(
        args.racks,
        args.seed,
        max_shots=args.max_shots,
        depth=args.depth,
        mirror=not args.no_mirror,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata = dataset.pop("metadata")
    np.savez_compressed(output, **dataset)
    output.with_suffix(".json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata, indent=2))
    print(f"Saved match demonstrations to {output}")


if __name__ == "__main__":
    main()
