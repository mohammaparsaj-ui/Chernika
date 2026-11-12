import math

import numpy as np

from chernika_rl.nine_ball_env import (
    ChernikaNineBallEnv, OBSERVATION_SIZE, HEAD_STRING_Y,
)
from chernika_rl.physics import Ball, BALL_RADIUS_PX, POCKETS
from chernika_rl.teacher import plan_teacher_shot_for_pocket


def test_reset_builds_verified_nine_ball_diamond_state():
    env = ChernikaNineBallEnv()
    observation, info = env.reset(seed=20261120)

    assert observation.shape == (OBSERVATION_SIZE,)
    assert OBSERVATION_SIZE == 52
    assert env.observation_space.contains(observation)
    assert sorted(env.object_balls) == list(range(1, 10))
    assert info["lowest_ball"] == 1
    assert info["break_pending"] is True
    assert env.object_balls[1].y > env.object_balls[9].y
    assert math.isclose(env.object_balls[9].x, 306.5, abs_tol=1e-6)
    assert math.isclose(env.object_balls[9].y, 314.5, abs_tol=1e-6)
    assert math.isclose(env.object_balls[1].x, 306.5, abs_tol=1e-6)
    # The frame-derived apex is 354.5 px; ideal hex packing differs by <0.2 px.
    assert math.isclose(env.object_balls[1].y, 354.5, abs_tol=0.2)


def test_wrong_first_contact_is_a_foul():
    env = ChernikaNineBallEnv()
    cue = Ball(300.0, 700.0, 0, cue=True)
    one = Ball(400.0, 400.0, 1)
    two = Ball(350.0, 550.0, 2)
    env.reset(
        seed=1,
        options={"cue": cue, "balls": [one, two], "break_pending": False},
    )

    _, _, terminated, truncated, info = env.step(
        np.array([0.0, 0.0], dtype=np.float32)
    )

    assert terminated is True
    assert truncated is False
    assert info["legal_ball"] == 1
    assert info["first_contact"] == 2
    assert info["foul"] is True
    assert info["foul_reason"] == "wrong_first_contact"


def test_no_rail_after_contact_is_a_foul_when_no_ball_is_pocketed():
    env = ChernikaNineBallEnv()
    cue = Ball(300.0, 700.0, 0, cue=True)
    one = Ball(300.0, 650.0, 1)
    nine = Ball(450.0, 300.0, 9)
    env.reset(
        seed=2,
        options={"cue": cue, "balls": [one, nine], "break_pending": False},
    )

    _, _, terminated, _, info = env.step(
        np.array([0.0, -0.5], dtype=np.float32)
    )

    assert info["first_contact"] == 1
    assert info["pocketed_numbers"] == []
    assert info["post_contact_cushion_contacts"] == 0
    assert info["foul"] is True
    assert info["foul_reason"] == "no_rail_after_contact"
    assert terminated is True


def test_legal_pot_removes_ball_and_advances_lowest_target():
    env = ChernikaNineBallEnv()
    target = Ball(365.0, 760.0, 1)
    pocket = POCKETS[5]
    dx, dy = pocket.x - target.x, pocket.y - target.y
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    ghost_x = target.x - ux * (2.0 * BALL_RADIUS_PX + 1.0)
    ghost_y = target.y - uy * (2.0 * BALL_RADIUS_PX + 1.0)
    cue = Ball(ghost_x - ux * 200.0, ghost_y - uy * 200.0, 0, cue=True)
    two = Ball(200.0, 300.0, 2)
    nine = Ball(400.0, 300.0, 9)
    env.reset(
        seed=3,
        options={
            "cue": cue,
            "balls": [target, two, nine],
            "break_pending": False,
        },
    )
    plan = plan_teacher_shot_for_pocket(cue, target, 5)
    assert plan is not None and plan.result.pocketed(1)

    _, reward, terminated, truncated, info = env.step(plan.action)

    assert info["legal_shot"] is True
    assert info["first_contact"] == 1
    assert info["pocketed_numbers"] == [1]
    assert info["remaining_balls"] == [2, 9]
    assert env.lowest_ball == 2
    assert reward > 0.0
    assert terminated is False
    assert truncated is False


def test_max_shots_truncates_a_legal_nonterminal_episode():
    env = ChernikaNineBallEnv(max_shots=1)
    cue = Ball(300.0, 300.0, 0, cue=True)
    one = Ball(300.0, 220.0, 1)
    nine = Ball(450.0, 500.0, 9)
    env.reset(
        seed=4,
        options={"cue": cue, "balls": [one, nine], "break_pending": False},
    )

    _, _, terminated, truncated, info = env.step(
        np.array([0.0, 0.0], dtype=np.float32)
    )

    assert info["first_contact"] == 1
    assert info["legal_shot"] is True
    assert info["pocketed_numbers"] == []
    assert terminated is False
    assert truncated is True



def test_match_mode_keeps_foul_recoverable_and_moves_cue_into_hand():
    env = ChernikaNineBallEnv(continue_after_foul=True)
    cue = Ball(300.0, 700.0, 0, cue=True)
    one = Ball(400.0, 400.0, 1)
    two = Ball(350.0, 550.0, 2)
    env.reset(
        seed=5,
        options={"cue": cue, "balls": [one, two], "break_pending": False},
    )

    _, _, terminated, truncated, info = env.step(
        np.array([0.0, 0.0], dtype=np.float32)
    )

    assert info["foul"] is True
    assert terminated is False
    assert truncated is False
    assert env.cue is None


def test_ball_in_hand_placement_rejects_overlap_and_accepts_clear_cloth():
    env = ChernikaNineBallEnv()
    cue = Ball(300.0, 700.0, 0, cue=True)
    one = Ball(300.0, 400.0, 1)
    nine = Ball(420.0, 400.0, 9)
    env.reset(
        seed=6,
        options={"cue": cue, "balls": [one, nine], "break_pending": False},
    )
    env.prepare_ball_in_hand()

    assert env.is_legal_cue_position(200.0, 200.0)
    assert not env.is_legal_cue_position(300.0, 400.0)
    observation = env.place_cue_ball(200.0, 200.0)
    assert observation.shape == (OBSERVATION_SIZE,)
    assert env.cue is not None
    assert (env.cue.x, env.cue.y) == (200.0, 200.0)


def test_missing_nine_ball_is_respotted_at_original_one_ball_apex_when_clear():
    env = ChernikaNineBallEnv()
    cue = Ball(300.0, 700.0, 0, cue=True)
    one = Ball(200.0, 250.0, 1)
    two = Ball(220.0, 250.0, 2)
    env.reset(
        seed=7,
        options={"cue": cue, "balls": [one, two], "break_pending": False},
    )
    env.prepare_ball_in_hand()

    position = env.respot_nine_ball()

    assert position == (306.5, 354.5)
    assert 9 in env.object_balls
    assert env.object_balls[9].x == 306.5
    assert env.object_balls[9].y == 354.5
    assert env.cue is None


def test_blocked_nine_respot_moves_to_nearest_open_side_of_original_apex():
    env = ChernikaNineBallEnv()
    cue = Ball(300.0, 700.0, 0, cue=True)
    blocker = Ball(306.5, 354.5, 1)
    two = Ball(200.0, 250.0, 2)
    env.reset(
        seed=7,
        options={"cue": cue, "balls": [blocker, two], "break_pending": False},
    )
    env.prepare_ball_in_hand()

    position = env.respot_nine_ball()

    assert position is not None
    nine = env.object_balls[9]
    assert math.hypot(nine.x - 306.5, nine.y - 354.5) >= 2.0 * BALL_RADIUS_PX - 0.25
    assert math.hypot(nine.x - 306.5, nine.y - 354.5) <= 2.0 * BALL_RADIUS_PX + 2.0



def test_nine_ball_is_not_respotted_when_it_remains_on_the_table():
    env = ChernikaNineBallEnv()
    cue = Ball(300.0, 700.0, 0, cue=True)
    one = Ball(250.0, 250.0, 1)
    nine = Ball(400.0, 400.0, 9)
    env.reset(
        seed=8,
        options={"cue": cue, "balls": [one, nine], "break_pending": False},
    )
    env.prepare_ball_in_hand()

    assert env.respot_nine_ball() is None
    assert env.object_balls[9] == nine



def test_rerack_after_illegal_break_restores_diamond_and_break_state():
    env = ChernikaNineBallEnv()
    env.reset(seed=9)
    env.object_balls.pop(2)
    env.break_pending = False

    observation = env.rerack_for_break()

    assert observation.shape == (OBSERVATION_SIZE,)
    assert sorted(env.object_balls) == list(range(1, 10))
    assert env.break_pending is True
    assert env.cue is not None
    assert env.cue.y > HEAD_STRING_Y



def test_cue_ball_may_touch_an_object_ball_during_ball_in_hand():
    env = ChernikaNineBallEnv()
    cue = Ball(300.0, 700.0, 0, cue=True)
    one = Ball(300.0, 400.0, 1)
    nine = Ball(420.0, 400.0, 9)
    env.reset(
        seed=10,
        options={"cue": cue, "balls": [one, nine], "break_pending": False},
    )
    env.prepare_ball_in_hand()

    touching_y = one.y + 2.0 * BALL_RADIUS_PX
    assert env.is_legal_cue_position(one.x, touching_y)
    assert env.is_legal_cue_position(
        one.x, touching_y, behind_head_string=False
    )



def test_break_ball_placement_is_restricted_to_behind_head_string():
    env = ChernikaNineBallEnv()
    cue = Ball(300.0, 700.0, 0, cue=True)
    one = Ball(300.0, 350.0, 1)
    nine = Ball(420.0, 350.0, 9)
    env.reset(
        seed=11,
        options={"cue": cue, "balls": [one, nine], "break_pending": False},
    )
    env.prepare_ball_in_hand()

    assert env.is_legal_cue_position(
        300.0, HEAD_STRING_Y + 30.0, behind_head_string=True
    )
    assert not env.is_legal_cue_position(
        300.0, HEAD_STRING_Y - 30.0, behind_head_string=True
    )
    observation = env.place_cue_ball(
        300.0, HEAD_STRING_Y + 30.0, behind_head_string=True
    )
    assert observation.shape == (OBSERVATION_SIZE,)
