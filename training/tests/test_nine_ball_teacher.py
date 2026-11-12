import math

from chernika_rl.nine_ball_env import ChernikaNineBallEnv
from chernika_rl.nine_ball_teacher import plan_nine_ball_shot
from chernika_rl.physics import Ball, BALL_RADIUS_PX, POCKETS


def _open_bottom_right_state():
    target = Ball(365.0, 760.0, 1)
    pocket = POCKETS[5]
    dx, dy = pocket.x - target.x, pocket.y - target.y
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    ghost_x = target.x - ux * (2.0 * BALL_RADIUS_PX + 1.0)
    ghost_y = target.y - uy * (2.0 * BALL_RADIUS_PX + 1.0)
    cue = Ball(ghost_x - ux * 200.0, ghost_y - uy * 200.0, 0, cue=True)
    return cue, [target, Ball(200.0, 300.0, 2), Ball(400.0, 300.0, 9)]


def test_multi_ball_teacher_finds_a_legal_lowest_ball_pot():
    cue, balls = _open_bottom_right_state()
    plan = plan_nine_ball_shot(cue, balls, break_shot=False)

    assert plan is not None
    assert plan.legal_ball == 1
    assert plan.break_shot is False
    assert plan.pocket_index == 5
    assert plan.assessment.legal_shot is True
    assert 1 in plan.assessment.pocketed_numbers
    assert plan.result.first_contact_number == 1


def test_break_teacher_only_targets_the_one_ball():
    env = ChernikaNineBallEnv()
    env.reset(seed=20261121)
    assert env.cue is not None
    plan = plan_nine_ball_shot(
        env.cue, list(env.object_balls.values()), break_shot=True
    )
    if plan is not None:
        assert plan.legal_ball == 1
        assert plan.break_shot is True
        assert plan.pocket_index == -1
        assert plan.assessment.legal_shot is True
        assert plan.result.first_contact_number == 1


def test_break_teacher_refuses_a_rack_without_ball_one():
    cue = Ball(300.0, 750.0, 0, cue=True)
    balls = [Ball(300.0, 400.0, 2), Ball(350.0, 400.0, 9)]
    assert plan_nine_ball_shot(cue, balls, break_shot=True) is None


def test_teacher_rejects_duplicate_object_ball_numbers():
    cue = Ball(300.0, 700.0, 0, cue=True)
    balls = [Ball(300.0, 500.0, 1), Ball(350.0, 500.0, 1)]
    try:
        plan_nine_ball_shot(cue, balls, break_shot=False)
    except ValueError:
        pass
    else:
        raise AssertionError("duplicate ball numbers must be rejected")
