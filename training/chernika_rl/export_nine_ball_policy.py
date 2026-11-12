"""Export a trained 52-input pocket/action policy to Chernika's Java binary format."""
from __future__ import annotations

import argparse
from pathlib import Path
import struct

import numpy as np
import torch


MAGIC = 0x43484E31  # "CHN1"
FORMAT_VERSION = 1
POCKET_COUNT = 6
ACTION_SIZE = 2

TENSOR_ORDER = (
    "backbone.0.weight",
    "backbone.0.bias",
    "backbone.2.weight",
    "backbone.2.bias",
    "pocket_head.weight",
    "pocket_head.bias",
    "action_head.weight",
    "action_head.bias",
)


def export_policy(model_path: str | Path, output_path: str | Path) -> dict:
    checkpoint = torch.load(
        Path(model_path), map_location="cpu", weights_only=True
    )
    observation_size = int(checkpoint.get("observation_size", 0))
    hidden_size = int(checkpoint.get("hidden_size", 0))
    if observation_size != 52 or hidden_size < 1:
        raise ValueError(
            "Android export requires a 52-input imitation policy with a positive hidden size"
        )

    state = checkpoint.get("state_dict")
    if not isinstance(state, dict):
        raise ValueError("Checkpoint does not contain a state_dict")

    expected_shapes = {
        "backbone.0.weight": (hidden_size, observation_size),
        "backbone.0.bias": (hidden_size,),
        "backbone.2.weight": (hidden_size, hidden_size),
        "backbone.2.bias": (hidden_size,),
        "pocket_head.weight": (POCKET_COUNT, hidden_size),
        "pocket_head.bias": (POCKET_COUNT,),
        "action_head.weight": (ACTION_SIZE, hidden_size),
        "action_head.bias": (ACTION_SIZE,),
    }

    tensors: list[np.ndarray] = []
    for name in TENSOR_ORDER:
        if name not in state:
            raise ValueError(f"Checkpoint is missing tensor: {name}")
        array = state[name].detach().cpu().numpy()
        if tuple(array.shape) != expected_shapes[name]:
            raise ValueError(
                f"{name} has shape {tuple(array.shape)}, expected {expected_shapes[name]}"
            )
        array = np.asarray(array, dtype=">f4", order="C")
        if not np.isfinite(array).all():
            raise ValueError(f"{name} contains non-finite weights")
        tensors.append(array)

    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as stream:
        stream.write(struct.pack(
            ">6i", MAGIC, FORMAT_VERSION, observation_size,
            hidden_size, POCKET_COUNT, ACTION_SIZE,
        ))
        for array in tensors:
            stream.write(array.tobytes(order="C"))

    return {
        "format": "CHN1",
        "format_version": FORMAT_VERSION,
        "observation_size": observation_size,
        "hidden_size": hidden_size,
        "pocket_count": POCKET_COUNT,
        "action_size": ACTION_SIZE,
        "output": str(output),
        "bytes": output.stat().st_size,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    import json
    print(json.dumps(export_policy(args.model, args.output), indent=2))


if __name__ == "__main__":
    main()
