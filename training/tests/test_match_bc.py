import numpy as np
import pytest
import torch

from chernika_rl.generate_match_demonstrations import mirror_horizontal
from chernika_rl.train_match_behavior_cloning import (
    BehaviorCloningActor,
    split_by_group,
    train_actor,
)
from chernika_rl.train_match_ppo import initialize_actor_from_behavior_cloning


def _sample_observation():
    obs = np.zeros(52, dtype=np.float32)
    obs[0:2] = [0.2, 0.7]
    for ball in range(9):
        x = 2 + 3 * ball
        obs[x:x + 3] = [0.1 + ball * 0.05, 0.2 + ball * 0.03, 1.0]
    # Mark ball 4 as absent; its sentinel x/y must not be reflected.
    obs[2 + 3 * 3:2 + 3 * 3 + 3] = [-0.05, -0.05, 0.0]
    obs[29:38] = [1.0, 0.0, 0.0] * 3
    for pocket in range(6):
        x = 38 + 2 * pocket
        obs[x:x + 2] = [0.05 + pocket * 0.17, 0.0 if pocket < 3 else 1.0]
    obs[50:52] = [0.0, 0.25]
    return obs


def test_horizontal_mirror_is_an_involution_and_reverses_angle_only():
    obs = _sample_observation()
    action = np.asarray([0.37, 0.82], dtype=np.float32)
    mirrored_obs, mirrored_action = mirror_horizontal(obs, action)
    restored_obs, restored_action = mirror_horizontal(mirrored_obs, mirrored_action)

    np.testing.assert_allclose(restored_obs, obs, atol=1e-6)
    np.testing.assert_allclose(restored_action, action, atol=1e-6)
    assert mirrored_action[0] == pytest.approx(-0.37)
    assert mirrored_action[1] == pytest.approx(0.82)
    assert mirrored_obs[11] == pytest.approx(-0.05)
    assert mirrored_obs[12] == pytest.approx(-0.05)


def test_group_split_keeps_every_rack_entirely_in_one_partition():
    groups = np.repeat(np.arange(20), 4)
    train_ids, validation_ids = split_by_group(groups, seed=11)
    train_groups = set(groups[train_ids])
    validation_groups = set(groups[validation_ids])
    assert train_groups
    assert validation_groups
    assert train_groups.isdisjoint(validation_groups)


def test_behavior_cloning_trains_and_returns_grouped_validation_metrics():
    rng = np.random.default_rng(42)
    observations = rng.uniform(0.0, 1.0, size=(80, 52)).astype(np.float32)
    actions = np.tanh(observations[:, :2] * 0.5).astype(np.float32)
    groups = np.repeat(np.arange(10), 8)
    weights = np.ones(80, dtype=np.float32)

    model, report = train_actor(
        observations,
        actions,
        groups,
        weights,
        epochs=2,
        batch_size=8,
        seed=42,
    )
    assert isinstance(model, BehaviorCloningActor)
    assert report["metadata"]["split_strategy"] == "grouped_by_complete_rack"
    assert report["metadata"]["training_racks"] + report["metadata"]["validation_racks"] == 10
    assert np.isfinite(report["metadata"]["best_validation_mse"])


def test_behavior_cloning_split_rejects_single_rack_leakage():
    with pytest.raises(ValueError, match="two distinct rack groups"):
        split_by_group(np.zeros(10, dtype=np.int64), seed=1)


def test_bc_actor_weights_initialize_matching_ppo_actor():
    source = BehaviorCloningActor()
    target_policy = type("Policy", (), {})()
    target_policy.mlp_extractor = type("Extractor", (), {})()
    target_policy.mlp_extractor.policy_net = torch.nn.Sequential(
        torch.nn.Linear(52, 256),
        torch.nn.Tanh(),
        torch.nn.Linear(256, 256),
        torch.nn.Tanh(),
    )
    target_policy.action_net = torch.nn.Linear(256, 2)
    target_policy.log_std = torch.nn.Parameter(torch.zeros(2))

    checkpoint = {
        "state_dict": source.state_dict(),
        "observation_size": 52,
        "action_size": 2,
        "hidden_size": 256,
    }
    initialize_actor_from_behavior_cloning(target_policy, checkpoint, log_std=-1.5)

    torch.testing.assert_close(
        target_policy.mlp_extractor.policy_net[0].weight,
        source.net[0].weight,
    )
    torch.testing.assert_close(
        target_policy.mlp_extractor.policy_net[2].weight,
        source.net[2].weight,
    )
    torch.testing.assert_close(target_policy.action_net.weight, source.net[4].weight)
    assert torch.all(target_policy.log_std == -1.5)
