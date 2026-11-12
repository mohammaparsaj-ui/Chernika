import math
import numpy as np

from chernika_rl.imitation import ImitationPolicy, predict_top_pockets
from chernika_rl.nine_ball_data import sample_random_state
from chernika_rl.nine_ball_env import ChernikaNineBallEnv
from chernika_rl.physics import BALL_RADIUS_PX, POCKETS


def test_random_state_sampler_returns_unique_non_overlapping_balls():
    cue, balls = sample_random_state(20261122)
    assert 3 <= len(balls) <= 9
    assert 9 in [ball.number for ball in balls]
    assert len({ball.number for ball in balls}) == len(balls)
    all_balls = [cue, *balls]
    for index, first in enumerate(all_balls):
        for second in all_balls[index + 1:]:
            assert math.hypot(first.x - second.x, first.y - second.y) >= (
                BALL_RADIUS_PX * 2.1 - 1e-6
            )
        for pocket in POCKETS:
            assert math.hypot(first.x - pocket.x, first.y - pocket.y) >= (
                BALL_RADIUS_PX * 2.5 - 1e-6
            )


def test_sampled_state_fits_nine_ball_environment_observation():
    cue, balls = sample_random_state(20261123)
    env = ChernikaNineBallEnv()
    observation, info = env.reset(
        seed=20261123,
        options={"cue": cue, "balls": balls, "break_pending": False},
    )
    assert observation.shape == (52,)
    assert env.observation_space.contains(observation)
    assert info["lowest_ball"] == min(ball.number for ball in balls)


def test_imitation_policy_supports_52_value_observations():
    policy = ImitationPolicy(observation_size=52)
    action, pockets = predict_top_pockets(
        policy, np.zeros(52, dtype=np.float32), top_k=3
    )
    assert action.shape == (2,)
    assert len(pockets) == 3
    assert len(set(pockets)) == 3
    assert all(0 <= pocket < 6 for pocket in pockets)
