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
        assert len(result["trajectory"][0]["observation"]) == 52
        assert len(result["trajectory"][0]["action"]) == 2


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
    assert _next_player_after_shot(0, False, []) == 1



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








def test_rack_summary_reports_plato_break_rerack_metrics():
    rows = [
        {
            "winner": 0,
            "break_legal": True,
            "break_foul": True,
            "reracks": 1,
            "total_fouls": 1,
            "mid_rack_fouls": 0,
            "ball_in_hand_placements": 0,
            "nine_respots": 0,
            "status": "win",
            "foul": True,
            "shots": 4,
            "total_pocketed_balls": 9,
            "break_spread": 5,
            "break_attempts": 2,
            "neural_fallback_count": 0,
        },
    ]
    metrics = summarize_racks(rows)

    assert metrics["rerack_rate"] == 1.0
    assert metrics["mean_break_attempts"] == 2.0
    assert "three_foul_loss_rate" not in metrics



def test_illegal_no_pot_break_reracks_and_passes_break_to_opponent(monkeypatch):
    from types import SimpleNamespace
    import numpy as np
    import chernika_rl.nine_ball_rollout as rollout

    plan = SimpleNamespace(
        action=np.asarray([0.0, 0.0], dtype=np.float32),
        shot_type="break",
        score=1.0,
    )
    monkeypatch.setattr(rollout, "plan_nine_ball_shot", lambda *args, **kwargs: plan)
    monkeypatch.setattr(rollout, "_choose_plan", lambda *args, **kwargs: (None, {}))

    def fake_step(env, action):
        env.shot_count += 1
        env.break_pending = False
        if env.shot_count == 1:
            env.cue = None
            legal = False
            foul = True
            first_contact = None
            break_met = False
            foul_reason = "no_object_ball_contact"
        else:
            legal = True
            foul = False
            first_contact = 2
            break_met = True
            foul_reason = None
        info = {
            "shot_count": env.shot_count,
            "legal_ball": 1,
            "first_contact": first_contact,
            "pocketed_numbers": [],
            "remaining_balls": sorted(env.object_balls),
            "legal_shot": legal,
            "foul": foul,
            "foul_reason": foul_reason,
            "break_requirement_met": break_met,
            "post_contact_cushion_ball_numbers": [1, 2, 3] if break_met else [],
            "post_contact_object_ball_cushion_contacts": 4 if break_met else 0,
            "power_percent": 60.0,
            "win": False,
        }
        return env._last_obs.copy(), -1.0, False, False, info

    monkeypatch.setattr(rollout.ChernikaNineBallEnv, "step", fake_step)

    result = rollout.run_rack(
        20261114,
        strategy="teacher_greedy",
        max_shots=3,
    )

    assert result["break_attempts"] == 2
    assert result["reracks"] == 1
    assert result["break_foul"] is True
    assert result["break_legal"] is True
    assert [shot["player"] for shot in result["trajectory"]] == [0, 1]
    assert result["trajectory"][0]["reracked"] is True
    assert result["trajectory"][0]["next_breaker"] == 1
