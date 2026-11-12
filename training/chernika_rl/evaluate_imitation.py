"""Evaluate ranked-pocket hybrid policies on identical held-out states."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .env import ChernikaSingleBallEnv
from .imitation import load_policy, predict, predict_top_pockets
from .teacher import plan_teacher_shot, plan_teacher_shot_for_pocket


TOP_K_VALUES = (1, 2, 3, 4, 6)


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
    rows = {"random": [], "teacher": [], "imitation": []}
    rows.update({f"hybrid_top{k}": [] for k in TOP_K_VALUES})
    pocket_recall = {k: 0 for k in TOP_K_VALUES}
    action_errors: list[float] = []
    pocket_comparisons = 0
    fallback_counts = {k: 0 for k in TOP_K_VALUES}

    for index in range(args.episodes):
        state_seed = args.seed + index
        observation, _ = env.reset(seed=state_seed)
        assert env.cue is not None and env.target is not None
        cue, target = env.cue, env.target

        teacher_plan = plan_teacher_shot(cue, target)
        teacher_action = (
            teacher_plan.action if teacher_plan is not None
            else np.array([0.0, -0.2], dtype=np.float32)
        )
        imitation_action, _ = predict(policy, observation)
        _, ranked_pockets = predict_top_pockets(
            policy, observation, top_k=6
        )

        if teacher_plan is not None:
            pocket_comparisons += 1
            action_errors.append(
                float(np.abs(imitation_action - teacher_plan.action).mean())
            )
            for k in TOP_K_VALUES:
                pocket_recall[k] += int(
                    teacher_plan.pocket_index in ranked_pockets[:k]
                )

        # Run each pocket's deterministic low-level controller once. Top-k
        # policies then reuse prefixes of the same ranked candidate list.
        pocket_plans = [
            plan_teacher_shot_for_pocket(cue, target, pocket_index)
            for pocket_index in ranked_pockets
        ]

        actions = {
            "random": rng.uniform(-1.0, 1.0, size=2).astype(np.float32),
            "teacher": teacher_action,
            "imitation": imitation_action,
        }
        for k in TOP_K_VALUES:
            candidates = [
                candidate for candidate in pocket_plans[:k]
                if candidate is not None
            ]
            if candidates:
                best = max(candidates, key=lambda candidate: candidate.score)
                actions[f"hybrid_top{k}"] = best.action
            else:
                actions[f"hybrid_top{k}"] = imitation_action
                fallback_counts[k] += 1

        for name, action in actions.items():
            env.reset(seed=state_seed)
            _, reward, _, _, info = env.step(action)
            assert env.target is not None
            rows[name].append({
                "success": info["success"],
                "first_contact": info["first_contact"] == env.target.number,
                "scratch": info["cue_scratch"],
                "off_table": info["off_table"],
                "reward": reward,
                "power_percent": info["power_percent"],
            })

    report = {
        "benchmark": "Chernika ranked-pocket hybrid top-k sweep, held-out single-ball states",
        "seed_start": args.seed,
        "results": {name: _metrics(values) for name, values in rows.items()},
        "teacher_agreement": {
            f"top{k}_pocket_recall": pocket_recall[k] / max(1, pocket_comparisons)
            for k in TOP_K_VALUES
        } | {
            "action_mae": float(np.mean(action_errors)) if action_errors else None,
            "states_with_teacher_plan": pocket_comparisons,
        },
        "hybrid": {
            "controller": "ranked neural pocket candidates + ghost-ball geometry + physics power sweep",
            "fallback_counts": fallback_counts,
            "ranked_candidate_order": ranked_pockets if args.episodes == 1 else "per-state neural logits",
        },
        "model": str(args.model),
        "note": "All policies use identical seeded states. Top-6 is a diagnostic near-oracle candidate sweep, not a deployable learned-only policy. Offline single-target simulator only; no Android controls are connected.",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
