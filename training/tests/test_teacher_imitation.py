import numpy as np
import torch

from chernika_rl.env import ChernikaSingleBallEnv
from chernika_rl.imitation import ImitationPolicy, predict
from chernika_rl.teacher import plan_teacher_shot


def test_teacher_action_matches_existing_geometric_baseline():
    env = ChernikaSingleBallEnv()
    env.reset(seed=20261112)
    assert env.cue is not None and env.target is not None
    plan = plan_teacher_shot(env.cue, env.target)
    assert plan is not None
    assert plan.action.shape == (2,)
    assert env.action_space.contains(plan.action)
    assert np.allclose(plan.action, env.heuristic_action(), atol=1e-6)


def test_teacher_plan_records_a_valid_pocket_and_physics_result():
    env = ChernikaSingleBallEnv()
    env.reset(seed=20261113)
    assert env.cue is not None and env.target is not None
    plan = plan_teacher_shot(env.cue, env.target)
    assert plan is not None
    assert 0 <= plan.pocket_index < 6
    assert np.isfinite(plan.score)
    assert plan.result.outcomes


def test_imitation_policy_outputs_bounded_angle_and_power():
    policy = ImitationPolicy()
    output = policy(torch.zeros((4, 16), dtype=torch.float32))
    assert output.shape == (4, 8)
    action, pocket = predict(policy, np.zeros(16, dtype=np.float32))
    assert action.shape == (2,)
    assert np.all(action >= -1.0) and np.all(action <= 1.0)
    assert 0 <= pocket < 6
