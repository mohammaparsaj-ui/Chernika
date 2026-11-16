import struct

import numpy as np
import pytest
import torch

from chernika_rl.export_nine_ball_policy import (
    ACTION_SIZE,
    FORMAT_VERSION,
    MAGIC,
    POCKET_COUNT,
    export_policy,
)


def checkpoint(observation_size=52, hidden_size=2):
    return {
        "observation_size": observation_size,
        "hidden_size": hidden_size,
        "state_dict": {
            "backbone.0.weight": torch.full((hidden_size, observation_size), 0.1),
            "backbone.0.bias": torch.full((hidden_size,), 0.2),
            "backbone.2.weight": torch.full((hidden_size, hidden_size), 0.3),
            "backbone.2.bias": torch.full((hidden_size,), 0.4),
            "pocket_head.weight": torch.full((POCKET_COUNT, hidden_size), 0.5),
            "pocket_head.bias": torch.full((POCKET_COUNT,), 0.6),
            "action_head.weight": torch.full((ACTION_SIZE, hidden_size), 0.7),
            "action_head.bias": torch.full((ACTION_SIZE,), 0.8),
        },
    }


def test_exports_java_header_and_big_endian_float_tensors(tmp_path):
    model = tmp_path / "policy.pt"
    output = tmp_path / "policy.bin"
    torch.save(checkpoint(), model)

    report = export_policy(model, output)

    raw = output.read_bytes()
    header = struct.unpack(">6i", raw[:24])
    assert header == (MAGIC, FORMAT_VERSION, 52, 2, POCKET_COUNT, ACTION_SIZE)
    assert report["bytes"] == len(raw)
    assert report["format"] == "CHN1"

    # First tensor is backbone.0.weight, serialized row-major as big-endian f32.
    first_weight = struct.unpack(">f", raw[24:28])[0]
    assert first_weight == pytest.approx(0.1, abs=1e-6)

    # The exact parameter count must match the Java loader's expected layout.
    expected_floats = (2 * 52) + 2 + (2 * 2) + 2 + (6 * 2) + 6 + (2 * 2) + 2
    assert len(raw) == 24 + expected_floats * 4


def test_rejects_a_model_with_the_wrong_observation_size(tmp_path):
    model = tmp_path / "policy.pt"
    torch.save(checkpoint(observation_size=16), model)

    with pytest.raises(ValueError, match="52-input"):
        export_policy(model, tmp_path / "policy.bin")


def test_rejects_a_missing_tensor(tmp_path):
    model = tmp_path / "policy.pt"
    data = checkpoint()
    del data["state_dict"]["pocket_head.bias"]
    torch.save(data, model)

    with pytest.raises(ValueError, match="pocket_head.bias"):
        export_policy(model, tmp_path / "policy.bin")
