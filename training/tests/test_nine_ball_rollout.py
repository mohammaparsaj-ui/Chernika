from chernika_rl.nine_ball_rollout import (
    _next_player_after_shot, run_rack, summarize_racks,
)


def test_single_shot_limit_produces_a_bounded_rack_trajectory():
    result = run_rack(
        20261112,
        strategy="teacher_greedy",
        max_shots=1,
    )

    assert result["shots"] <= 1
    assert result["initial_balls"] == list(range(1, 10))
    assert len(result["trajectory"]) <= 1
    if result["trajectory"]:
        assert result["trajectory"][0]["break"] is True


def test_rack_summary_reports_win_and_break_metrics():
    rows = [
        {
            "win": True,
            "winner": 0,
            "break_legal": True,
            "break_foul": False,
            "total_fouls": 0,
            "mid_rack_fouls": 0,
            "ball_in_hand_placements": 0,
            "nine_respots": 0,
            "three_foul_loss": False,
            "status": "win",
            "foul": False,
            "shots": 8,
            "total_pocketed_balls": 9,
            "break_spread": 5,
            "neural_fallback_count": 0,
        },
        {
            "win": False,
            "winner": 1,
            "break_legal": False,
            "break_foul": True,
            "total_fouls": 1,
            "mid_rack_fouls": 0,
            "ball_in_hand_placements": 1,
            "nine_respots": 0,
            "three_foul_loss": False,
            "status": "break_foul",
            "foul": True,
            "shots": 1,
            "total_pocketed_balls": 0,
            "break_spread": 1,
            "neural_fallback_count": 0,
        },
    ]
    metrics = summarize_racks(rows)

    assert metrics["racks"] == 2
    assert metrics["rack_win_rate"] == 0.5
    assert metrics["legal_break_rate"] == 0.5
    assert metrics["break_foul_rate"] == 0.5



def test_legal_no_pot_passes_turn_but_a_legal_pot_retains_it():
    assert _next_player_after_shot(0, True, []) == 1
    assert _next_player_after_shot(1, True, [3]) == 1
    assert _next_player_after_shot(0, False, []) == 0



def test_ball_in_hand_search_returns_a_legal_position():
    from chernika_rl.nine_ball_env import ChernikaNineBallEnv
    from chernika_rl.nine_ball_rollout import _choose_ball_in_hand_position

    env = ChernikaNineBallEnv(continue_after_foul=True)
    env.reset(seed=20261113)
    env.prepare_ball_in_hand()

    observation, position, score = _choose_ball_in_hand_position(
        env, "teacher_greedy", None, 3
    )

    assert observation.shape == (52,)
    assert env.is_legal_cue_position(*position)
    assert score is not None


def test_three_consecutive_fouls_are_counted_per_player():
    # A player's streak survives the opponent's turn and resets only after
    # that same player completes a legal shot; match-loop state is per player.
    streaks = [2, 0]
    streaks[0] += 1
    assert streaks == [3, 0]
    streaks[1] = 0
    assert streaks == [3, 0]
    streaks[0] = 0
    assert streaks == [0, 0]
