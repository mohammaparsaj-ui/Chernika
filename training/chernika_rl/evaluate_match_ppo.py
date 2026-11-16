"""Evaluate a trained Chernika PPO policy on complete held-out 9-ball racks."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
from stable_baselines3 import PPO

from .match_env import ChernikaMatchEnv


def wilson_interval(successes: int, trials: int, z: float = 1.96) -> tuple[float, float]:
    if trials <= 0:
        return 0.0, 0.0
    p = successes / trials
    denominator = 1.0 + z * z / trials
    center = (p + z * z / (2.0 * trials)) / denominator
    margin = z * math.sqrt(p * (1.0 - p) / trials + z * z / (4.0 * trials * trials)) / denominator
    return max(0.0, center - margin), min(1.0, center + margin)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--episodes", type=int, default=100)
    parser.add_argument("--seed", type=int, default=96000)
    parser.add_argument("--max-shots", type=int, default=48)
    parser.add_argument(
        "--opponent-strategy",
        choices=("teacher_greedy", "teacher_lookahead"),
        default="teacher_greedy",
    )
    parser.add_argument("--opponent-depth", type=int, choices=(1, 2), default=2)
    parser.add_argument("--output", default="artifacts/nine_ball_match_ppo_evaluation.json")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--opponent-model", default=None, help="optional frozen PPO snapshot opponent")
    args = parser.parse_args()

    if args.episodes < 1:
        raise ValueError("episodes must be positive")
    model = PPO.load(args.model, device=args.device)
    opponent_snapshot = (
        PPO.load(args.opponent_model, device=args.device)
        if args.opponent_model else None
    )
    env = ChernikaMatchEnv(
        max_shots=args.max_shots,
        opponent_strategy=args.opponent_strategy,
        opponent_depth=args.opponent_depth,
        opponent_policy=opponent_snapshot,
    )

    rows = []
    for index in range(args.episodes):
        observation, _ = env.reset(seed=args.seed + index)
        total_reward = 0.0
        terminated = truncated = False
        final_info = {}
        while not (terminated or truncated):
            action, _ = model.predict(observation, deterministic=True)
            observation, reward, terminated, truncated, final_info = env.step(action)
            total_reward += float(reward)
        rows.append({
            "seed": args.seed + index,
            "winner": final_info.get("winner"),
            "status": final_info.get("status"),
            "agent_win": final_info.get("winner") == 0,
            "opponent_win": final_info.get("winner") == 1,
            "shots": final_info.get("total_shots", 0),
            "agent_shots": final_info.get("agent_shots", 0),
            "opponent_shots": final_info.get("opponent_shots", 0),
            "reward": total_reward,
        })

    agent_wins = sum(row["agent_win"] for row in rows)
    opponent_wins = sum(row["opponent_win"] for row in rows)
    completed = agent_wins + opponent_wins
    low, high = wilson_interval(agent_wins, len(rows))
    report = {
        "benchmark": "Chernika PPO complete-rack match evaluation",
        "model": args.model,
        "episodes": len(rows),
        "seed_start": args.seed,
        "opponent_strategy": args.opponent_strategy,
        "opponent_depth": args.opponent_depth,
        "opponent_model": args.opponent_model,
        "opponent_type": "snapshot_policy" if args.opponent_model else args.opponent_strategy,
        "max_shots": args.max_shots,
        "metrics": {
            "agent_win_rate": agent_wins / len(rows),
            "agent_win_rate_wilson_95_ci": [low, high],
            "opponent_win_rate": opponent_wins / len(rows),
            "completed_rack_rate": completed / len(rows),
            "max_shot_or_guard_rate": sum(row["status"] in ("max_shots", "opponent_turn_guard") for row in rows) / len(rows),
            "mean_total_shots": float(np.mean([row["shots"] for row in rows])),
            "mean_agent_shots": float(np.mean([row["agent_shots"] for row in rows])),
            "mean_opponent_shots": float(np.mean([row["opponent_shots"] for row in rows])),
            "mean_reward": float(np.mean([row["reward"] for row in rows])),
        },
        "limitations": [
            "The agent controls the break in every evaluation rack; alternating break order is not yet implemented.",
            "A supplied opponent checkpoint is a frozen snapshot; league rotation and Elo tracking are not yet implemented.",
            "The Python physics model does not yet model spin, throw, masse or cloth anisotropy.",
            "These are simulator results, not live Plato win-rate claims.",
        ],
        "episodes_detail": rows,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    env.close()


if __name__ == "__main__":
    main()
