from chernika_rl.nine_ball_rollout import run_rack, summarize_racks


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
            "break_legal": True,
            "status": "win",
            "foul": False,
            "shots": 8,
            "total_pocketed_balls": 9,
            "break_spread": 5,
            "neural_fallback_count": 0,
        },
        {
            "win": False,
            "break_legal": False,
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
