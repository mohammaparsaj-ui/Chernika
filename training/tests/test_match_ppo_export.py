from types import SimpleNamespace

import numpy as np
import pytest
import torch
from torch import nn

from chernika_rl.export_match_ppo_policy import (
    ACTION_SIZE,
    OBSERVATION_SIZE,
    export_policy,
    predict_from_binary,
    read_policy_binary,
)


def _policy(hidden=16, activation=nn.Tanh):
    return SimpleNamespace(
        mlp_extractor=SimpleNamespace(
            policy_net=nn.Sequential(
                nn.Linear(OBSERVATION_SIZE, hidden),
                activation(),
                nn.Linear(hidden, hidden),
                activation(),
            )
        ),
        action_net=nn.Linear(hidden, ACTION_SIZE),
    )


def test_exported_binary_matches_pytorch_deterministic_actor(tmp_path):
    torch.manual_seed(71)
    policy = _policy(hidden=16)
    output = tmp_path / "match_policy.bin"
    metadata = export_policy(policy, output, source_checkpoint="test-checkpoint.zip")
    binary = read_policy_binary(output)

    rng = np.random.default_rng(71)
    observations = rng.uniform(-0.05, 1.0, size=(12, OBSERVATION_SIZE)).astype(np.float32)
    with torch.no_grad():
        expected = policy.action_net(
            policy.mlp_extractor.policy_net(torch.from_numpy(observations))
        ).numpy()
    expected = np.clip(expected, -1.0, 1.0)

    actual = np.stack([predict_from_binary(binary, row) for row in observations])
    np.testing.assert_allclose(actual, expected, rtol=1e-5, atol=1e-6)
    assert metadata["magic_ascii"] == "CHM1"
    assert metadata["action_semantics"] == [
        "angle_offset_normalized", "power_normalized"
    ]
    assert output.with_suffix(".json").exists()


def test_export_rejects_non_tanh_hidden_activation(tmp_path):
    with pytest.raises(ValueError, match="Linear/Tanh"):
        export_policy(_policy(activation=nn.ReLU), tmp_path / "bad.bin")


def test_binary_reader_rejects_trailing_or_truncated_bytes(tmp_path):
    policy = _policy(hidden=8)
    output = tmp_path / "match_policy.bin"
    export_policy(policy, output)
    original = output.read_bytes()

    output.write_bytes(original[:-4])
    with pytest.raises(ValueError, match="length mismatch"):
        read_policy_binary(output)

    output.write_bytes(original + b"junk")
    with pytest.raises(ValueError, match="length mismatch"):
        read_policy_binary(output)
