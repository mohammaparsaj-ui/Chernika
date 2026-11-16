"""Compare the learned nine-ball break with the physics teacher on held-out racks."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .nine_ball_break import load_break_policy, predict_break_action
from .nine_ball_env import ChernikaNineBallEnv
from .physics import BREAK_CUE_SPEED_MULTIPLIER
from .nine_ball_teacher import plan_nine_ball_shot


def _summarize(rows: list[dict]) -> dict:
    n = max(1, len(rows))
    return {
        "racks": len(rows),
        "legal_break_rate": sum(row["legal"] for row in rows) / n,
        "foul_rate": sum(row["foul"] for row in rows) / n,
        "scratch_rate": sum(row["scratch"] for row in rows) / n,
        "off_table_rate": sum(row["off_table"] for row in rows) / n,
        "any_ball_pocket_rate": sum(row["pocketed_count"] > 0 for row in rows) / n,
        "nine_ball_win_rate": sum(row["win"] for row in rows) / n,
        "mean_pocketed_balls": float(np.mean([row["pocketed_count"] for row in rows])) if rows else 0.0,
        "mean_distinct_object_balls_to_rail": float(np.mean([row["spread"] for row in rows])) if rows else 0.0,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="artifacts/nine_ball_break_policy.pt")
    parser.add_argument("--episodes", type=int, default=150)
    parser.add_argument("--seed", type=int, default=92000)
    parser.add_argument("--output", default="artifacts/nine_ball_break_evaluation.json")
    args = parser.parse_args()
    if args.episodes < 1:
        raise ValueError("episodes must be positive")

    policy = load_break_policy(args.model)
    env = ChernikaNineBallEnv()
    rows = {"physics_teacher": [], "raw_neural": []}
    teacher_unavailable = 0

    for index in range(args.episodes):
        seed = args.seed + index
        observation, _ = env.reset(seed=seed)
        teacher_plan = plan_nine_ball_shot(
            env.cue, list(env.object_balls.values()), break_shot=True
        )
        if teacher_plan is None:
            teacher_unavailable += 1
            continue

        actions = {
            "physics_teacher": teacher_plan.action,
            "raw_neural": predict_break_action(policy, observation),
        }
        options_seed = seed
        for name, action in actions.items():
            env.reset(seed=options_seed)
            _, reward, terminated, truncated, info = env.step(action)
            rows[name].append({
                "legal": bool(info["legal_shot"]),
                "foul": bool(info["foul"]),
                "scratch": bool(info["cue_scratch"]),
                "off_table": bool(info["off_table"]),
                "win": bool(info["win"]),
                "pocketed_count": len(info["pocketed_numbers"]),
                "spread": len(info["post_contact_cushion_ball_numbers"]),
                "reward": float(reward),
                "terminated": bool(terminated),
                "truncated": bool(truncated),
            })

    if not rows["physics_teacher"]:
        raise RuntimeError("No held-out racks had a legal teacher break")
    report = {
        "benchmark": "Chernika nine-ball break imitation, held-out diamond racks",
        "seed_start": args.seed,
        "break_cue_speed_multiplier": BREAK_CUE_SPEED_MULTIPLIER,
        "break_aim_strategy": "second-row aim lines; Plato permits any first object-ball contact",
        "requested_episodes": args.episodes,
        "evaluated_episodes": len(rows["physics_teacher"]),
        "racks_without_teacher_break": teacher_unavailable,
        "results": {name: _summarize(values) for name, values in rows.items()},
        "model": str(args.model),
        "note": (
            "The neural policy is evaluated without physics correction. "
            "These are synthetic simulator racks, not live Plato break replays."
        ),
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
