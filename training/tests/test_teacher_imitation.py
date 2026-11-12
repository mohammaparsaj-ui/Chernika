import numpy as np
import torch

from chernika_rl.env import ChernikaSingleBallEnv
from chernika_rl.imitation import ImitationPolicy, predict
from chernika_rl.teacher import plan_teacher_shot, plan_teacher_shot_for_pocket


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



def test_pocket_conditioned_physics_planner_pots_selected_bottom_right():
    target = Ball(365.0, 760.0, 4)
    pocket = POCKETS[5]
    dx, dy = pocket.x - target.x, pocket.y - target.y
    length = np.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    ghost_x = target.x - ux * (2 * BALL_RADIUS_PX + 1.0)
    ghost_y = target.y - uy * (2 * BALL_RADIUS_PX + 1.0)
    cue = Ball(ghost_x - ux * 200.0, ghost_y - uy * 200.0, 0, cue=True)

    plan = plan_teacher_shot_for_pocket(cue, target, 5)
    assert plan is not None
    assert plan.pocket_index == 5
    assert plan.result.pocketed(4)


def test_pocket_conditioned_physics_planner_rejects_invalid_index():
    env = ChernikaSingleBallEnv()
    env.reset(seed=20261114)
    assert env.cue is not None and env.target is not None
    try:
        plan_teacher_shot_for_pocket(env.cue, env.target, 6)
    except ValueError:
        pass
    else:
        raise AssertionError("invalid pocket index must raise ValueError")
