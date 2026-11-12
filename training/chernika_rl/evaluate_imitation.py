"""Evaluate raw imitation and hybrid pocket+physics policies on held-out states."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .env import ChernikaSingleBallEnv
from .imitation import load_policy, predict
from .teacher import plan_teacher_shot, plan_teacher_shot_for_pocket


def _metrics(rows: list[dict]) -> dict:
    n = max(1, len(rows))
    return {
        "episodes": len(rows),
        "pocket_rate": sum(bool(row["success"]) for row in rows) / n,
        "first_contact_rate": sum(bool(row["first_contact"]) for row in rows) / n,
        "scratch_rate": sum(bool(row["scratch"]) for row in rows) / n,
        "off_table_rate": sum(bool(row["off_table"]) for row in rows) / n,
        "mean_reward": sum(float(row["reward"]) for row in rows) / n,
        "mean_power_percent": sum(float(row["power_percent"]) for row in rows) / n,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="artifacts/imitation_policy.pt")
    parser.add_argument("--episodes", type=int, default=300)
    parser.add_argument("--seed", type=int, default=60000)
    parser.add_argument("--output", default="artifacts/imitation_evaluation.json")
    args = parser.parse_args()

    policy = load_policy(args.model)
    env = ChernikaSingleBallEnv()
    rng = np.random.default_rng(args.seed + 991)
    rows = {"random": [], "teacher": [], "imitation": [], "hybrid": []}
    pocket_matches, action_errors, pocket_comparisons = 0, [], 0
    hybrid_fallbacks = 0

    for index in range(args.episodes):
        state_seed = args.seed + index
        observation, _ = env.reset(seed=state_seed)
        assert env.cue is not None and env.target is not None
        plan = plan_teacher_shot(env.cue, env.target)
        teacher_action = (plan.action if plan is not None
                          else np.array([0.0, -0.2], dtype=np.float32))
        imitation_action, predicted_pocket = predict(policy, observation)

        if plan is not None:
            pocket_comparisons += 1
            pocket_matches += int(predicted_pocket == plan.pocket_index)
            action_errors.append(float(np.abs(imitation_action - plan.action).mean()))

        # Hybrid control: the network supplies strategic pocket selection;
        # deterministic physics supplies exact ghost-ball angle and power.
        hybrid_plan = plan_teacher_shot_for_pocket(
            env.cue, env.target, predicted_pocket
        )
        if hybrid_plan is None:
            hybrid_action = imitation_action
            hybrid_fallbacks += 1
        else:
            hybrid_action = hybrid_plan.action

        actions = {
            "random": rng.uniform(-1.0, 1.0, size=2).astype(np.float32),
            "teacher": teacher_action,
            "imitation": imitation_action,
            "hybrid": hybrid_action,
        }
        for name, action in actions.items():
            env.reset(seed=state_seed)
            _, reward, _, _, info = env.step(action)
            rows[name].append({
                "success": info["success"],
                "first_contact": info["first_contact"] == env.target.number,
                "scratch": info["cue_scratch"],
                "off_table": info["off_table"],
                "reward": reward,
                "power_percent": info["power_percent"],
            })

    report = {
        "benchmark": "Chernika teacher-guided imitation and hybrid control, held-out single-ball states",
        "seed_start": args.seed,
        "results": {name: _metrics(values) for name, values in rows.items()},
        "teacher_agreement": {
            "pocket_accuracy": pocket_matches / max(1, pocket_comparisons),
            "action_mae": float(np.mean(action_errors)) if action_errors else None,
            "states_with_teacher_plan": pocket_comparisons,
        },
        "hybrid": {
            "controller": "neural pocket classification + ghost-ball geometry + physics power sweep",
            "fallback_count": hybrid_fallbacks,
        },
        "model": str(args.model),
        "note": "Same seeded states for all policies. This remains an offline simulator benchmark; no Android controls are connected.",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
