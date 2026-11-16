import math
import numpy as np

from chernika_rl.nine_ball_planner import plan_nine_ball_shot_lookahead
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


def test_depth_one_is_the_existing_greedy_teacher():
    cue, balls = _open_bottom_right_state()
    greedy = plan_nine_ball_shot(cue, balls)
    planned = plan_nine_ball_shot_lookahead(cue, balls, depth=1)

    assert greedy is not None
    assert planned is not None
    assert np.allclose(planned.plan.action, greedy.action)
    assert planned.total_score == greedy.score
    assert planned.planned_pockets == (greedy.pocket_index,)


def test_two_ply_planner_returns_a_legal_first_action_and_path():
    cue, balls = _open_bottom_right_state()
    result = plan_nine_ball_shot_lookahead(cue, balls, depth=2)

    assert result is not None
    assert result.plan.assessment.legal_shot is True
    assert result.plan.result.first_contact_number == 1
    assert result.planned_pockets[0] == result.plan.pocket_index
    assert result.candidate_count >= 1
    assert result.total_score >= result.plan.score - 25.0


def test_planner_rejects_invalid_depth_and_pocket_candidates():
    cue, balls = _open_bottom_right_state()
    try:
        plan_nine_ball_shot_lookahead(cue, balls, depth=3)
    except ValueError:
        pass
    else:
        raise AssertionError("depth above two must be rejected")

    try:
        plan_nine_ball_shot_lookahead(cue, balls, pocket_indices=[6])
    except ValueError:
        pass
    else:
        raise AssertionError("invalid pocket index must be rejected")
