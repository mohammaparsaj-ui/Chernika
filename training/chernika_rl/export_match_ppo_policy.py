"""Export a Stable-Baselines3 Chernika match PPO actor for Android Java inference.

Binary format (big-endian):
  int32 magic 'CHM1', version, observation_size, hidden_size, action_size
  float32 input_weight[hidden, observation], input_bias[hidden]
  float32 hidden_weight[hidden, hidden], hidden_bias[hidden]
  float32 action_weight[action, hidden], action_bias[action]

Hidden activations are Tanh. The output is the deterministic Gaussian mean
clipped to the Gymnasium Box range [-1, 1], matching SB3 PPO.predict().
"""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path
from typing import Any

import numpy as np
import torch
from torch import nn

MAGIC = 0x43484D31  # "CHM1"
FORMAT_VERSION = 1
OBSERVATION_SIZE = 52
ACTION_SIZE = 2


def _linear_layers(policy: Any) -> tuple[nn.Linear, nn.Linear, nn.Linear]:
    network = list(policy.mlp_extractor.policy_net)
    if len(network) != 4:
        raise ValueError("expected actor layers [Linear, Tanh, Linear, Tanh]")
    if not (
        isinstance(network[0], nn.Linear)
        and isinstance(network[1], nn.Tanh)
        and isinstance(network[2], nn.Linear)
        and isinstance(network[3], nn.Tanh)
    ):
        raise ValueError("actor architecture must use two Linear/Tanh hidden blocks")
    if not isinstance(policy.action_net, nn.Linear):
        raise ValueError("PPO action head must be a Linear layer")

    first, second, action = network[0], network[2], policy.action_net
    hidden = first.out_features
    if first.in_features != OBSERVATION_SIZE:
        raise ValueError(f"expected {OBSERVATION_SIZE} observation inputs")
    if second.in_features != hidden or second.out_features != hidden:
        raise ValueError("both actor hidden layers must have the same width")
    if action.in_features != hidden or action.out_features != ACTION_SIZE:
        raise ValueError(f"action head must map hidden features to {ACTION_SIZE} outputs")
    return first, second, action


def _write_tensor(stream, tensor: torch.Tensor) -> None:
    array = tensor.detach().cpu().numpy().astype(">f4", copy=False)
    if not np.all(np.isfinite(array)):
        raise ValueError("actor contains non-finite weights")
    stream.write(np.ascontiguousarray(array).tobytes(order="C"))


def export_policy(policy: Any, output: str | Path, *, source_checkpoint: str = "") -> dict[str, Any]:
    first, second, action = _linear_layers(policy)
    hidden = first.out_features
    path = Path(output)
    path.parent.mkdir(parents=True, exist_ok=True)

    with path.open("wb") as stream:
        stream.write(struct.pack(
            ">5i", MAGIC, FORMAT_VERSION, OBSERVATION_SIZE, hidden, ACTION_SIZE
        ))
        for layer in (first, second, action):
            _write_tensor(stream, layer.weight)
            _write_tensor(stream, layer.bias)

    metadata = {
        "format": "Chernika Android match PPO actor",
        "magic_ascii": "CHM1",
        "format_version": FORMAT_VERSION,
        "observation_size": OBSERVATION_SIZE,
        "hidden_size": hidden,
        "action_size": ACTION_SIZE,
        "hidden_activation": "tanh",
        "output_transform": "deterministic_gaussian_mean_clipped_to[-1,1]",
        "action_semantics": ["angle_offset_normalized", "power_normalized"],
        "power_percent_mapping": "((power_normalized + 1) / 2) * 97.8",
        "source_checkpoint": source_checkpoint,
        "binary_file": path.name,
        "android_runtime_class": "com.cherunika.chernika.MatchPpoPolicy",
        "live_control_enabled": False,
    }
    path.with_suffix(".json").write_text(json.dumps(metadata, indent=2))
    return metadata


def read_policy_binary(path: str | Path) -> dict[str, Any]:
    raw = Path(path).read_bytes()
    if len(raw) < 20:
        raise ValueError("policy binary is shorter than its header")
    magic, version, obs_size, hidden, action_size = struct.unpack(">5i", raw[:20])
    if magic != MAGIC or version != FORMAT_VERSION:
        raise ValueError("unsupported Chernika match PPO binary format")
    if obs_size != OBSERVATION_SIZE or hidden < 1 or action_size != ACTION_SIZE:
        raise ValueError("policy dimensions do not match the Android runtime")

    expected_floats = hidden * obs_size + hidden + hidden * hidden + hidden + action_size * hidden + action_size
    expected_bytes = 20 + expected_floats * 4
    if len(raw) != expected_bytes:
        raise ValueError(f"binary length mismatch: expected {expected_bytes}, got {len(raw)}")

    values = np.frombuffer(raw, dtype=">f4", offset=20).astype(np.float32)
    if not np.all(np.isfinite(values)):
        raise ValueError("policy binary contains non-finite weights")
    cursor = 0

    def take(shape: tuple[int, ...]) -> np.ndarray:
        nonlocal cursor
        count = int(np.prod(shape))
        result = values[cursor:cursor + count].reshape(shape)
        cursor += count
        return result

    return {
        "observation_size": obs_size,
        "hidden_size": hidden,
        "action_size": action_size,
        "input_weight": take((hidden, obs_size)),
        "input_bias": take((hidden,)),
        "hidden_weight": take((hidden, hidden)),
        "hidden_bias": take((hidden,)),
        "action_weight": take((action_size, hidden)),
        "action_bias": take((action_size,)),
    }


def predict_from_binary(binary: dict[str, Any], observation: np.ndarray) -> np.ndarray:
    obs = np.asarray(observation, dtype=np.float32)
    if obs.shape != (OBSERVATION_SIZE,) or not np.all(np.isfinite(obs)):
        raise ValueError("observation must be a finite 52-value vector")
    hidden = np.tanh(binary["input_weight"] @ obs + binary["input_bias"])
    features = np.tanh(binary["hidden_weight"] @ hidden + binary["hidden_bias"])
    action = binary["action_weight"] @ features + binary["action_bias"]
    return np.clip(action, -1.0, 1.0).astype(np.float32)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="Stable-Baselines3 PPO .zip checkpoint")
    parser.add_argument("--output", default="artifacts/nine_ball_match_ppo.bin")
    args = parser.parse_args()

    from stable_baselines3 import PPO

    model = PPO.load(args.model, device="cpu")
    if tuple(model.observation_space.shape or ()) != (OBSERVATION_SIZE,):
        raise ValueError("PPO checkpoint observation space must have shape (52,)")
    if tuple(model.action_space.shape or ()) != (ACTION_SIZE,):
        raise ValueError("PPO checkpoint action space must have shape (2,)")
    metadata = export_policy(model.policy, args.output, source_checkpoint=args.model)

    binary = read_policy_binary(args.output)
    sample = np.zeros((1, OBSERVATION_SIZE), dtype=np.float32)
    with torch.no_grad():
        expected = model.policy._predict(
            torch.as_tensor(sample), deterministic=True
        ).cpu().numpy()[0]
    actual = predict_from_binary(binary, sample[0])
    max_error = float(np.max(np.abs(np.clip(expected, -1.0, 1.0) - actual)))
    if max_error > 1e-5:
        raise RuntimeError(f"Android export parity check failed: max error {max_error}")
    metadata["parity_check_max_abs_error"] = max_error
    Path(args.output).with_suffix(".json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
