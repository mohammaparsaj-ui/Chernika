"""Train a PPO policy on complete Plato-style 9-ball matches."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
from stable_baselines3 import PPO
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv

from .match_env import ChernikaMatchEnv


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--total-timesteps", type=int, default=200_000)
    parser.add_argument("--n-envs", type=int, default=2)
    parser.add_argument("--n-steps", type=int, default=256)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--max-shots", type=int, default=48)
    parser.add_argument(
        "--opponent-strategy",
        choices=("teacher_greedy", "teacher_lookahead"),
        default="teacher_greedy",
    )
    parser.add_argument("--opponent-depth", type=int, choices=(1, 2), default=2)
    parser.add_argument("--seed", type=int, default=20261116)
    parser.add_argument("--learning-rate", type=float, default=3e-4)
    parser.add_argument("--output", default="artifacts/nine_ball_match_ppo")
    parser.add_argument("--tensorboard-log", default="artifacts/tensorboard")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--opponent-model", default=None, help="optional frozen PPO snapshot for self-play training")
    parser.add_argument("--resume-model", default=None, help="optional PPO checkpoint to continue training from")
    args = parser.parse_args()

    if args.total_timesteps < 1 or args.n_envs < 1 or args.n_steps < 2:
        raise ValueError("timesteps, n-envs and n-steps must be positive")
    if args.batch_size < 2 or (args.n_envs * args.n_steps) % args.batch_size:
        raise ValueError("n-envs * n-steps must be divisible by batch-size")

    torch.set_num_threads(2)
    np.random.seed(args.seed)
    opponent_snapshot = (
        PPO.load(args.opponent_model, device=args.device)
        if args.opponent_model else None
    )

    def make_env(rank: int):
        def _init():
            env = ChernikaMatchEnv(
                max_shots=args.max_shots,
                opponent_strategy=args.opponent_strategy,
                opponent_depth=args.opponent_depth,
                opponent_policy=opponent_snapshot,
            )
            return Monitor(env)
        return _init

    vec_env = DummyVecEnv([make_env(index) for index in range(args.n_envs)])
    if args.resume_model:
        model = PPO.load(args.resume_model, env=vec_env, device=args.device)
    else:
        model = PPO(
            "MlpPolicy",
            vec_env,
            learning_rate=args.learning_rate,
            n_steps=args.n_steps,
            batch_size=args.batch_size,
            n_epochs=10,
            gamma=0.995,
            gae_lambda=0.95,
            clip_range=0.20,
            ent_coef=0.01,
            vf_coef=0.5,
            max_grad_norm=0.5,
            policy_kwargs={"net_arch": {"pi": [256, 256], "vf": [256, 256]}},
            tensorboard_log=args.tensorboard_log,
            seed=args.seed,
            device=args.device,
            verbose=1,
        )
    model.learn(
        total_timesteps=args.total_timesteps,
        progress_bar=False,
        reset_num_timesteps=not bool(args.resume_model),
    )

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    model.save(str(output))
    metadata = {
        "algorithm": "PPO",
        "task": "complete Plato-style nine-ball match",
        "total_timesteps": args.total_timesteps,
        "seed": args.seed,
        "observation_size": 52,
        "action_size": 2,
        "action_semantics": ["angle_offset_normalized", "power_normalized"],
        "max_shots": args.max_shots,
        "opponent_strategy": args.opponent_strategy,
        "opponent_depth": args.opponent_depth,
        "opponent_model": args.opponent_model,
        "resume_model": args.resume_model,
        "training_mode": "snapshot_self_play" if args.opponent_model else "teacher_opponent",
        "agent_breaks": True,
        "physics_spin_modelled": False,
        "checkpoint": str(output.with_suffix(".zip")),
    }
    output.with_suffix(".metadata.json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata, indent=2))
    vec_env.close()


if __name__ == "__main__":
    main()
