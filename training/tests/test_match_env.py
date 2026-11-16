import math

import numpy as np

import chernika_rl.match_env as match_env
from chernika_rl.match_env import ChernikaMatchEnv
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


def _fast_ball_in_hand(monkeypatch):
    def place(env, strategy, policy, top_k, *, behind_head_string=False):
        x, y = 200.0, 200.0
        observation = env.place_cue_ball(
            x, y, behind_head_string=behind_head_string
        )
        return observation, (x, y), 0.0

    monkeypatch.setattr(match_env, "_choose_ball_in_hand_position", place)


def test_reset_returns_existing_52_value_observation_and_agent_break():
    env = ChernikaMatchEnv(max_shots=12)
    observation, info = env.reset(seed=20261116)

    assert observation.shape == (52,)
    assert env.observation_space.contains(observation)
    assert info["agent_breaks"] is True
    assert info["agent_player"] == 0
    assert env.game.break_pending is True
    assert sorted(env.game.object_balls) == list(range(1, 10))


def test_step_rejects_invalid_action_values():
    env = ChernikaMatchEnv(max_shots=12)
    env.reset(seed=20261116)

    try:
        env.step(np.asarray([np.nan, 0.0], dtype=np.float32))
    except ValueError:
        pass
    else:
        raise AssertionError("non-finite actions must be rejected")


def test_single_agent_action_returns_gymnasium_transition():
    env = ChernikaMatchEnv(max_shots=1)
    observation, _ = env.reset(seed=20261117)
    next_observation, reward, terminated, truncated, info = env.step(
        np.asarray([0.0, 1.0], dtype=np.float32)
    )

    assert next_observation.shape == (52,)
    assert env.observation_space.contains(next_observation)
    assert np.isfinite(reward)
    assert isinstance(terminated, bool)
    assert isinstance(truncated, bool)
    assert info["total_shots"] == 1
    assert terminated != truncated
    assert terminated or truncated


def test_legal_pot_retains_agent_turn_and_advances_lowest_ball():
    cue, balls = _open_bottom_right_state()
    env = ChernikaMatchEnv(max_shots=12)
    env.reset(
        seed=20261118,
        options={"cue": cue, "balls": balls, "break_pending": False},
    )
    plan = plan_nine_ball_shot(cue, balls, break_shot=False)
    assert plan is not None
    assert plan.assessment.legal_shot
    assert 1 in plan.assessment.pocketed_numbers

    observation, reward, terminated, truncated, info = env.step(plan.action)

    assert observation.shape == (52,)
    assert reward > 0.0
    assert terminated is False
    assert truncated is False
    assert info["current_player"] == 0
    assert env.game.lowest_ball == 2


def test_illegal_no_pot_break_reracks_and_passes_break():
    env = ChernikaMatchEnv(max_shots=20)
    env.reset(seed=20261119)

    recovery = env._recover_foul(
        incoming_player=1,
        shot_info={
            "break_shot": True,
            "foul": True,
            "pocketed_numbers": [],
            "break_requirement_met": False,
        },
    )

    assert recovery["kind"] == "illegal_no_pot_break_rerack"
    assert recovery["next_breaker"] == 1
    assert env._current_player == 1
    assert env.game.break_pending is True
    assert sorted(env.game.object_balls) == list(range(1, 10))
    assert env.game.cue is not None


def test_ordinary_foul_awards_ball_in_hand_to_incoming_player(monkeypatch):
    _fast_ball_in_hand(monkeypatch)
    env = ChernikaMatchEnv(max_shots=20)
    env.reset(seed=20261120, options={"break_pending": False})
    env.game.prepare_ball_in_hand()

    recovery = env._recover_foul(
        incoming_player=1,
        shot_info={
            "break_shot": False,
            "foul": True,
            "pocketed_numbers": [],
            "break_requirement_met": None,
        },
    )

    assert recovery["kind"] == "ordinary_ball_in_hand"
    assert recovery["incoming_player"] == 1
    assert env._current_player == 1
    assert env.game.cue is not None
    assert env.game.is_legal_cue_position(env.game.cue.x, env.game.cue.y)


def test_fouled_nine_is_respotted_before_ball_in_hand(monkeypatch):
    _fast_ball_in_hand(monkeypatch)
    cue = Ball(300.0, 700.0, 0, cue=True)
    balls = [Ball(200.0, 250.0, 1), Ball(220.0, 250.0, 2)]
    env = ChernikaMatchEnv(max_shots=20)
    env.reset(
        seed=20261121,
        options={"cue": cue, "balls": balls, "break_pending": False},
    )
    env.game.prepare_ball_in_hand()

    recovery = env._recover_foul(
        incoming_player=1,
        shot_info={
            "break_shot": False,
            "foul": True,
            "pocketed_numbers": [9],
            "break_requirement_met": None,
        },
    )

    assert recovery["nine_respot_position"] == (306.5, 354.5)
    assert 9 in env.game.object_balls
    assert env.game.cue is not None


def test_legal_miss_runs_opponent_until_agent_turn_returns(monkeypatch):
    env = ChernikaMatchEnv(max_shots=6)
    env.reset(seed=20261122)
    calls = []

    def fake_step(action):
        calls.append(np.asarray(action, dtype=np.float32).copy())
        env.game.shot_count += 1
        info = {
            "win": False,
            "foul": False,
            "legal_shot": True,
            "pocketed_numbers": [],
            "break_shot": False,
            "legal_ball": 1,
            "first_contact": 1,
            "foul_reason": None,
            "remaining_balls": sorted(env.game.object_balls),
            "power_percent": 50.0,
            "angle_radians": 0.0,
            "cue_scratch": False,
            "off_table": False,
        }
        return env._observation.copy(), 0.0, False, False, info

    monkeypatch.setattr(env.game, "step", fake_step)
    monkeypatch.setattr(
        env,
        "_opponent_action",
        lambda: (np.zeros(2, dtype=np.float32), None),
    )

    observation, reward, terminated, truncated, info = env.step(
        np.zeros(2, dtype=np.float32)
    )

    assert len(calls) == 2
    assert observation.shape == (52,)
    assert reward > 0.0
    assert terminated is False
    assert truncated is False
    assert env._opponent_shots == 1
    assert info["current_player"] == 0
    assert info["trajectory"][1]["player"] == "opponent"
