"""Evaluate SAC, geometry+physics heuristic, and random shooting on identical states."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from stable_baselines3 import SAC
from .env import ChernikaSingleBallEnv


def run_one(env: ChernikaSingleBallEnv, seed: int, policy, rng) -> dict:
    obs, _ = env.reset(seed=seed)
    if policy == "heuristic":
        action = env.heuristic_action()
    elif policy == "random":
        action = rng.uniform(-1.0, 1.0, size=2).astype(np.float32)
    else:
        action, _ = policy.predict(obs, deterministic=True)
    _, reward, _, _, info = env.step(action)
    return {"reward": reward, **info}


def summarize(rows: list[dict]) -> dict:
    n = max(1, len(rows))
    return {
        "episodes": len(rows),
        "pocket_rate": sum(bool(r["success"]) for r in rows) / n,
        "first_contact_rate": sum(r["first_contact"] == 1 for r in rows) / n,
        "scratch_rate": sum(bool(r["cue_scratch"]) for r in rows) / n,
        "off_table_rate": sum(bool(r["off_table"]) for r in rows) / n,
        "mean_reward": sum(float(r["reward"]) for r in rows) / n,
        "mean_power_percent": sum(float(r["power_percent"]) for r in rows) / n,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="artifacts/sac_single_ball/best_model/best_model.zip")
    parser.add_argument("--episodes", type=int, default=200)
    parser.add_argument("--seed", type=int, default=20_000)
    parser.add_argument("--output", default="artifacts/evaluation.json")
    args = parser.parse_args()

    model_path = Path(args.model)
    model = SAC.load(model_path) if model_path.exists() else None
    env = ChernikaSingleBallEnv()
    rng = np.random.default_rng(args.seed + 77)
    results = {}
    policies = ["random", "heuristic"] + (["sac"] if model is not None else [])
    for name in policies:
        rows = []
        for i in range(args.episodes):
            policy = "heuristic" if name == "heuristic" else "random" if name == "random" else model
            rows.append(run_one(env, args.seed + i, policy, rng))
        results[name] = summarize(rows)
    payload = {
        "benchmark": "Chernika single-ball aim-and-power",
        "seed_start": args.seed,
        "model": str(model_path) if model is not None else None,
        "results": results,
        "note": "Same seeded table states are used for each policy. Physics parameters remain provisional.",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2))
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()
