"""Evaluate complete nine-ball racks with greedy, lookahead and hybrid policies."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .imitation import load_policy
from .nine_ball_rollout import evaluate_racks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="artifacts/nine_ball_rollout_policy.pt")
    parser.add_argument("--episodes", type=int, default=30)
    parser.add_argument("--seed", type=int, default=94000)
    parser.add_argument("--max-shots", type=int, default=24)
    parser.add_argument("--depth", type=int, default=2)
    parser.add_argument("--top-k", type=int, default=3)
    parser.add_argument("--output", default="artifacts/nine_ball_rollout_evaluation.json")
    args = parser.parse_args()

    policy = load_policy(args.model)
    if policy.observation_size != 52:
        raise ValueError(
            f"Expected a 52-input nine-ball rollout model, got {policy.observation_size}"
        )
    report = evaluate_racks(
        args.episodes,
        args.seed,
        policy=policy,
        max_shots=args.max_shots,
        depth=args.depth,
        top_k=args.top_k,
    )
    report["model"] = str(args.model)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
