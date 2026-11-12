from chernika_rl.nine_ball_env import ChernikaNineBallEnv
from chernika_rl.nine_ball_teacher import plan_nine_ball_shot
from chernika_rl.physics import Ball, BREAK_CUE_SPEED_MULTIPLIER, TABLE_BOTTOM


def _recorded_rack():
    return [
        Ball(306.5, 274.5, 8),
        Ball(294.5, 294.5, 6),
        Ball(318.5, 294.5, 7),
        Ball(283.5, 314.5, 4),
        Ball(306.5, 314.5, 9),
        Ball(329.5, 314.5, 5),
        Ball(294.5, 334.5, 2),
        Ball(318.5, 334.5, 3),
        Ball(306.5, 354.5, 1),
    ]


def test_second_ball_break_is_legal_and_environment_matches_teacher():
    assert BREAK_CUE_SPEED_MULTIPLIER == 4.0
    env = ChernikaNineBallEnv()
    cue = Ball(306.5, TABLE_BOTTOM - 100.0, 0, cue=True)
    env.reset(seed=1, options={"balls": _recorded_rack(), "cue": cue})

    plan = plan_nine_ball_shot(
        env.cue,
        list(env.object_balls.values()),
        break_shot=True,
    )

    assert plan is not None
    assert plan.assessment.legal_shot
    assert plan.result.first_contact_number == 1
    # A second-row line is a small, non-zero deviation from the apex centerline.
    assert 0.001 < abs(float(plan.action[0])) < 0.03

    _, _, _, _, info = env.step(plan.action)
    assert info["break_shot"] is True
    assert info["legal_shot"] is True
    assert info["foul"] is False
    assert info["first_contact"] == 1
    assert (
        len(info["post_contact_cushion_ball_numbers"]) >= 4
        or len(info["pocketed_numbers"]) >= 1
    )
