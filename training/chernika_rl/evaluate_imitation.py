"""Evaluate raw imitation and ranked-pocket hybrid policies on held-out states."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .env import ChernikaSingleBallEnv
from .imitation import load_policy, predict, predict_top_pockets
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
    rows = {
        "random": [],
        "teacher": [],
        "imitation": [],
        "hybrid_top1": [],
        "hybrid_top2": [],
    }
    pocket_matches, top2_matches = 0, 0
    action_errors, pocket_comparisons = [], 0
    hybrid_top1_fallbacks, hybrid_top2_fallbacks = 0, 0

    for index in range(args.episodes):
        state_seed = args.seed + index
        observation, _ = env.reset(seed=state_seed)
        assert env.cue is not None and env.target is not None
        plan = plan_teacher_shot(env.cue, env.target)
        teacher_action = (plan.action if plan is not None
                          else np.array([0.0, -0.2], dtype=np.float32))
        imitation_action, predicted_pocket = predict(policy, observation)
        _, top_pockets = predict_top_pockets(policy, observation, top_k=2)

        if plan is not None:
            pocket_comparisons += 1
            pocket_matches += int(predicted_pocket == plan.pocket_index)
            top2_matches += int(plan.pocket_index in top_pockets)
            action_errors.append(float(np.abs(imitation_action - plan.action).mean()))

        # Rank-1 hybrid: neural pocket choice + physics shot controller.
        top1_plan = plan_teacher_shot_for_pocket(
            env.cue, env.target, top_pockets[0]
        )
        if top1_plan is None:
            hybrid_top1_action = imitation_action
            hybrid_top1_fallbacks += 1
        else:
            hybrid_top1_action = top1_plan.action

        # Rank-2 hybrid: search both leading pocket candidates, then keep the
        # best simulated outcome. This reduces the cost of a wrong top-1 label.
        candidate_plans = [top1_plan] if top1_plan is not None else []
        second_plan = plan_teacher_shot_for_pocket(
            env.cue, env.target, top_pockets[1]
        )
        if second_plan is not None:
            candidate_plans.append(second_plan)
        if candidate_plans:
            best_top2_plan = max(candidate_plans, key=lambda candidate: candidate.score)
            hybrid_top2_action = best_top2_plan.action
        else:
            hybrid_top2_action = imitation_action
            hybrid_top2_fallbacks += 1

        actions = {
            "random": rng.uniform(-1.0, 1.0, size=2).astype(np.float32),
            "teacher": teacher_action,
            "imitation": imitation_action,
            "hybrid_top1": hybrid_top1_action,
            "hybrid_top2": hybrid_top2_action,
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
        "benchmark": "Chernika ranked-pocket imitation and hybrid control, held-out single-ball states",
        "seed_start": args.seed,
        "results": {name: _metrics(values) for name, values in rows.items()},
        "teacher_agreement": {
            "top1_pocket_accuracy": pocket_matches / max(1, pocket_comparisons),
            "top2_pocket_recall": top2_matches / max(1, pocket_comparisons),
            "action_mae": float(np.mean(action_errors)) if action_errors else None,
            "states_with_teacher_plan": pocket_comparisons,
        },
        "hybrid": {
            "controller": "ranked neural pocket candidates + ghost-ball geometry + physics power sweep",
            "top1_fallback_count": hybrid_top1_fallbacks,
            "top2_fallback_count": hybrid_top2_fallbacks,
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
