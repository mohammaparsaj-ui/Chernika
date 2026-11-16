"""Train a PPO policy on complete Plato-style 9-ball matches."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import numpy as np
import torch
from stable_baselines3 import PPO
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv

from .match_env import ChernikaMatchEnv


def initialize_actor_from_behavior_cloning(
    policy: Any,
    checkpoint: dict[str, Any],
    *,
    log_std: float = -1.5,
) -> dict[str, Any]:
    """Copy a 52→256→256→2 BC actor into the matching Stable-Baselines3 PPO actor."""
    if int(checkpoint.get("observation_size", -1)) != 52:
        raise ValueError("BC checkpoint must use 52-value observations")
    if int(checkpoint.get("action_size", -1)) != 2:
        raise ValueError("BC checkpoint must use two-value angle/power actions")
    if int(checkpoint.get("hidden_size", -1)) != 256:
        raise ValueError("BC checkpoint must use hidden_size=256")
    if not np.isfinite(log_std):
        raise ValueError("log_std must be finite")

    source = checkpoint.get("state_dict")
    required = (
        "net.0.weight", "net.0.bias",
        "net.2.weight", "net.2.bias",
        "net.4.weight", "net.4.bias",
    )
    if not isinstance(source, dict) or any(key not in source for key in required):
        raise ValueError("BC checkpoint is missing actor weights")

    target_layers = [
        module for module in policy.mlp_extractor.policy_net
        if isinstance(module, torch.nn.Linear)
    ]
    if len(target_layers) != 2:
        raise ValueError("PPO policy must have exactly two actor hidden linear layers")
    if not isinstance(policy.action_net, torch.nn.Linear):
        raise ValueError("PPO policy action head must be a linear layer")
    if not hasattr(policy, "log_std"):
        raise ValueError("PPO policy does not expose a continuous-action log_std")

    pairs = (
        (target_layers[0], "net.0"),
        (target_layers[1], "net.2"),
        (policy.action_net, "net.4"),
    )
    with torch.no_grad():
        for target, prefix in pairs:
            weight = source[f"{prefix}.weight"]
            bias = source[f"{prefix}.bias"]
            if target.weight.shape != weight.shape or target.bias.shape != bias.shape:
                raise ValueError(f"PPO actor layer shape does not match BC layer {prefix}")
            target.weight.copy_(weight.to(device=target.weight.device, dtype=target.weight.dtype))
            target.bias.copy_(bias.to(device=target.bias.device, dtype=target.bias.dtype))
        policy.log_std.fill_(float(log_std))

    return {
        "initialized": True,
        "source": "behavior_cloning",
        "observation_size": 52,
        "action_size": 2,
        "hidden_size": 256,
        "initial_log_std": float(log_std),
        "critic_initialized_from_bc": False,
    }


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
    parser.add_argument("--bc-checkpoint", default=None, help="optional supervised actor checkpoint for PPO initialization")
    parser.add_argument("--bc-log-std", type=float, default=-1.5)
    args = parser.parse_args()

    if args.total_timesteps < 1 or args.n_envs < 1 or args.n_steps < 2:
        raise ValueError("timesteps, n-envs and n-steps must be positive")
    if args.batch_size < 2 or (args.n_envs * args.n_steps) % args.batch_size:
        raise ValueError("n-envs * n-steps must be divisible by batch-size")
    if args.resume_model and args.bc_checkpoint:
        raise ValueError("use either --resume-model or --bc-checkpoint, not both")

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
    bc_initialization = None
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
        if args.bc_checkpoint:
            checkpoint = torch.load(
                args.bc_checkpoint, map_location="cpu", weights_only=True
            )
            bc_initialization = initialize_actor_from_behavior_cloning(
                model.policy, checkpoint, log_std=args.bc_log_std
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
        "behavior_cloning_checkpoint": args.bc_checkpoint,
        "behavior_cloning_initialization": bc_initialization,
        "training_mode": (
            "snapshot_self_play" if args.opponent_model
            else "behavior_cloning_initialized_teacher_opponent"
            if args.bc_checkpoint else "teacher_opponent"
        ),
        "agent_breaks": True,
        "physics_spin_modelled": False,
        "checkpoint": str(output.with_suffix(".zip")),
    }
    output.with_suffix(".metadata.json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata, indent=2))
    vec_env.close()


if __name__ == "__main__":
    main()
