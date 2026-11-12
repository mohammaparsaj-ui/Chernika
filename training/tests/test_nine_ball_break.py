import numpy as np

from chernika_rl.nine_ball_break import BreakPolicy, predict_break_action


def test_break_policy_outputs_two_bounded_controls():
    policy = BreakPolicy()
    action = predict_break_action(policy, np.zeros(52, dtype=np.float32))

    assert action.shape == (2,)
    assert np.all(np.isfinite(action))
    assert np.all(action >= -1.0)
    assert np.all(action <= 1.0)
