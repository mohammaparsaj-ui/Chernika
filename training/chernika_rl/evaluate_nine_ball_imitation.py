"""Compare raw imitation and top-3 hybrid control on held-out 9-ball states."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .env import ChernikaNineBallEnv
from .imitation import load_policy, predict_top_pockets
from .nine_ball_data import sample_random_state
from .nine_ball_teacher import plan_nine_ball_shot

TOP_K = 3


def _metrics(rows: list[dict]) -> dict:
    n = max(1, len(rows))
    return {
        "episodes": len(rows),
        "legal_rate": sum(bool(row["legal"]) for row in rows) / n,
        "pocket_rate": sum(bool(row["pocketed"]) for row in rows) / n,
        "win_rate": sum(bool(row["win"]) for row in rows) / n,
        "first_contact_rate": sum(bool(row["first_contact"]) for row in rows) / n,
        "foul_rate": sum(bool(row["foul"]) for row in rows) / n,
        "scratch_rate": sum(bool(row["scratch"]) for row in rows) / n,
        "off_table_rate": sum(bool(row["off_table"]) for row in rows) / n,
        "mean_reward": float(np.mean([row["reward"] for row in rows])) if rows else 0.0,
        "mean_pocketed_balls": (
            float(np.mean([row["pocketed_count"] for row in rows])) if rows else 0.0
        ),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="artifacts/nine_ball_policy.pt")
    parser.add_argument("--episodes", type=int, default=150)
    parser.add_argument("--seed", type=int, default=80000)
    parser.add_argument(
        "--output", default="artifacts/nine_ball_evaluation.json"
    )
    args = parser.parse_args()

    policy = load_policy(args.model)
    if policy.observation_size != 52:
        raise ValueError(
            f"Expected a 52-value nine-ball policy, got {policy.observation_size}"
        )

    env = ChernikaNineBallEnv()
    rng = np.random.default_rng(args.seed + 991)
    rows = {
        "random": [],
        "teacher": [],
        "imitation": [],
        "hybrid_top3": [],
    }
    pocket_recall = 0
    comparisons = 0
    hybrid_fallbacks = 0
    skipped_states = 0

    for index in range(args.episodes):
        state_seed = args.seed + index
        cue, balls = sample_random_state(state_seed)
        options = {"cue": cue, "balls": balls, "break_pending": False}
        observation, _ = env.reset(seed=state_seed, options=options)
        teacher_plan = plan_nine_ball_shot(cue, balls, break_shot=False)
        if teacher_plan is None:
            skipped_states += 1
            continue

        imitation_action, ranked_pockets = predict_top_pockets(
            policy, observation, top_k=TOP_K
        )
        comparisons += 1
        pocket_recall += int(teacher_plan.pocket_index in ranked_pockets)

        candidate_plans = [
            plan_nine_ball_shot(
                cue, balls, break_shot=False, pocket_indices=[pocket_index]
            )
            for pocket_index in ranked_pockets
        ]
        candidate_plans = [
            candidate for candidate in candidate_plans if candidate is not None
        ]
        if candidate_plans:
            hybrid_action = max(
                candidate_plans, key=lambda candidate: candidate.score
            ).action
        else:
            hybrid_action = imitation_action
            hybrid_fallbacks += 1

        actions = {
            "random": rng.uniform(-1.0, 1.0, size=2).astype(np.float32),
            "teacher": teacher_plan.action,
            "imitation": imitation_action,
            "hybrid_top3": hybrid_action,
        }

        for name, action in actions.items():
            env.reset(seed=state_seed, options=options)
            _, reward, _, _, info = env.step(action)
            rows[name].append({
                "legal": info["legal_shot"],
                "pocketed": bool(info["pocketed_numbers"]),
                "pocketed_count": len(info["pocketed_numbers"]),
                "win": info["win"],
                "first_contact": info["first_contact"] == info["legal_ball"],
                "foul": info["foul"],
                "scratch": info["cue_scratch"],
                "off_table": info["off_table"],
                "reward": reward,
            })

    if not rows["teacher"]:
        raise RuntimeError("No held-out states had a legal teacher plan")

    report = {
        "benchmark": (
            "Chernika nine-ball top-3 hybrid, held-out randomized multi-ball states"
        ),
        "seed_start": args.seed,
        "requested_episodes": args.episodes,
        "evaluated_episodes": len(rows["teacher"]),
        "skipped_states_without_teacher_plan": skipped_states,
        "results": {name: _metrics(values) for name, values in rows.items()},
        "teacher_agreement": {
            "top3_pocket_recall": pocket_recall / max(1, comparisons),
            "states_with_teacher_plan": comparisons,
        },
        "hybrid": {
            "controller": (
                "neural top-3 pocket ranking + multi-ball ghost-ball geometry "
                "+ full-table physics verification"
            ),
            "fallback_count": hybrid_fallbacks,
        },
        "model": str(args.model),
        "note": (
            "Stationary randomly dispersed 3-9 ball states, not break-derived or "
            "replay-derived. Single-agent practice simulator only; no Android controls."
        ),
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
