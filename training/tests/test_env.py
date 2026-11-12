import numpy as np

from chernika_rl.env import ChernikaSingleBallEnv


def test_reset_returns_valid_16_value_observation():
    env = ChernikaSingleBallEnv()
    obs, info = env.reset(seed=123)
    assert obs.shape == (16,)
    assert env.observation_space.contains(obs)
    assert "cue" in info and "target" in info


def test_step_is_one_shot_and_reports_execution_metrics():
    env = ChernikaSingleBallEnv()
    obs, _ = env.reset(seed=456)
    next_obs, reward, terminated, truncated, info = env.step(np.array([0.0, 0.0]))
    assert next_obs.shape == obs.shape
    assert np.isfinite(reward)
    assert terminated is True
    assert truncated is False
    assert "success" in info and "power_percent" in info


def test_heuristic_action_is_inside_continuous_action_space():
    env = ChernikaSingleBallEnv()
    env.reset(seed=789)
    action = env.heuristic_action()
    assert action.shape == (2,)
    assert env.action_space.contains(action)
