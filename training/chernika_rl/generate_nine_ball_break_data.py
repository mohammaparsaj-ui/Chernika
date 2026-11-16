"""Generate teacher-labelled opening-break actions from randomized diamond racks."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .nine_ball_env import ChernikaNineBallEnv
from .physics import BREAK_CUE_SPEED_MULTIPLIER
from .nine_ball_teacher import plan_nine_ball_shot


def generate(racks: int, seed: int) -> dict[str, np.ndarray | dict]:
    if racks < 1:
        raise ValueError("racks must be positive")
    env = ChernikaNineBallEnv()
    observations, actions = [], []
    spreads, pocket_counts, nine_on_break = [], [], []
    skipped = 0
    legal_breaks = 0

    for index in range(racks):
        rack_seed = seed + index
        observation, _ = env.reset(seed=rack_seed)
        plan = plan_nine_ball_shot(
            env.cue, list(env.object_balls.values()), break_shot=True
        )
        if plan is None:
            skipped += 1
            continue
        if not plan.assessment.legal_shot:
            skipped += 1
            continue
        observations.append(observation)
        actions.append(plan.action)
        legal_breaks += 1
        spreads.append(len(plan.result.post_contact_cushion_ball_numbers))
        pocket_counts.append(len(plan.assessment.pocketed_numbers))
        nine_on_break.append(plan.assessment.won)

    if not observations:
        raise RuntimeError("Break teacher produced no legal demonstrations")

    metadata = {
        "requested_racks": racks,
        "usable_demonstrations": len(observations),
        "skipped_racks": skipped,
        "seed_start": seed,
        "break_cue_speed_multiplier": BREAK_CUE_SPEED_MULTIPLIER,
        "break_aim_strategy": "second-row aim lines; Plato permits any first object-ball contact",
        "legal_teacher_break_rate": legal_breaks / racks,
        "mean_distinct_object_balls_to_rail": float(np.mean(spreads)),
        "break_pocket_rate": float(np.mean(np.asarray(pocket_counts) > 0)),
        "mean_break_pocketed_balls": float(np.mean(pocket_counts)),
        "nine_ball_won_on_break_rate": float(np.mean(nine_on_break)),
        "observation_size": 52,
        "state_distribution": (
            "seeded 1-2-3-2-1 diamond rack with shuffled balls 2-8 and "
            "randomized cue-ball x-position; synthetic calibrated table"
        ),
    }
    return {
        "observations": np.asarray(observations, dtype=np.float32),
        "actions": np.asarray(actions, dtype=np.float32),
        "metadata": metadata,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--racks", type=int, default=500)
    parser.add_argument("--seed", type=int, default=91000)
    parser.add_argument("--output", default="artifacts/nine_ball_break_dataset.npz")
    args = parser.parse_args()

    dataset = generate(args.racks, args.seed)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata = dataset.pop("metadata")
    np.savez_compressed(output, **dataset)
    output.with_suffix(".json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata, indent=2))
    print(f"Saved break demonstrations to {output}")


if __name__ == "__main__":
    main()
