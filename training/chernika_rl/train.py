"""Train a SAC aiming/power policy on Chernika's calibrated single-ball task."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from stable_baselines3 import SAC
from stable_baselines3.common.callbacks import EvalCallback
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv

from .env import ChernikaSingleBallEnv
from .physics import (
    TABLE_LEFT, TABLE_TOP, TABLE_RIGHT, TABLE_BOTTOM, BALL_RADIUS_PX,
    MAX_UI_POWER_PERCENT, MAX_CUE_SPEED_MPS,
)


def make_env(seed: int):
    def _init():
        env = ChernikaSingleBallEnv()
        env.reset(seed=seed)
        return Monitor(env)
    return _init


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--timesteps", type=int, default=100_000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    parser.add_argument("--output", default="artifacts/sac_single_ball")
    parser.add_argument("--eval-freq", type=int, default=5_000)
    parser.add_argument("--eval-episodes", type=int, default=100)
    args = parser.parse_args()

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    train_env = DummyVecEnv([make_env(args.seed)])
    eval_env = DummyVecEnv([make_env(args.seed + 100_000)])
    callback = EvalCallback(
        eval_env,
        best_model_save_path=str(output / "best_model"),
        log_path=str(output / "eval"),
        eval_freq=max(args.eval_freq, 1),
        n_eval_episodes=args.eval_episodes,
        deterministic=True,
        render=False,
    )

    model = SAC(
        "MlpPolicy",
        train_env,
        learning_rate=3e-4,
        buffer_size=200_000,
        learning_starts=5_000,
        batch_size=256,
        tau=0.005,
        gamma=0.99,
        train_freq=1,
        gradient_steps=1,
        policy_kwargs={"net_arch": [128, 128]},
        tensorboard_log=str(output / "tensorboard"),
        seed=args.seed,
        device=args.device,
        verbose=1,
    )
    model.learn(total_timesteps=args.timesteps, callback=callback,
                progress_bar=False, log_interval=10)
    model.save(output / "final_model")
    metadata = {
        "algorithm": "SAC",
        "task": "single-target-ball-one-shot",
        "timesteps": args.timesteps,
        "seed": args.seed,
        "device": args.device,
        "table_bounds_px": [TABLE_LEFT, TABLE_TOP, TABLE_RIGHT, TABLE_BOTTOM],
        "ball_radius_px": BALL_RADIUS_PX,
        "max_ui_power_percent": MAX_UI_POWER_PERCENT,
        "max_cue_speed_mps": MAX_CUE_SPEED_MPS,
        "action": ["delta_angle_relative_to_cue_target", "power_percent"],
        "observation_size": 16,
        "simulator": "Python parity port of Chernika PoolPhysics.java; calibration is provisional",
    }
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2))
    train_env.close()
    eval_env.close()
    print(f"Saved final model to {output / 'final_model.zip'}")


if __name__ == "__main__":
    main()
