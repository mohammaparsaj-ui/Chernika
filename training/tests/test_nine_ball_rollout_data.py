import numpy as np

from chernika_rl import generate_nine_ball_rollout_data as rollout_data


def test_generator_uses_complete_rack_trajectories_and_excludes_safeties(monkeypatch):
    calls = []

    def fake_run_rack(seed, **kwargs):
        calls.append((seed, kwargs))
        return {
            "status": "win",
            "winner": 0,
            "win": True,
            "shots": 3,
            "total_fouls": 1,
            "ball_in_hand_placements": 1,
            "nine_respots": 1,
            "three_foul_loss": False,
            "trajectory": [
                {
                    "break": True,
                    "legal": True,
                    "pocketed_numbers": [],
                },
                {
                    "break": False,
                    "legal": True,
                    "shot_type": "direct",
                    "pocket_index": 2,
                    "observation": [0.25] * 52,
                    "action": [0.1, 0.8],
                    "lookahead_score": 1.5,
                    "immediate_score": 1.2,
                    "pocketed_numbers": [1],
                    "first_contact": 1,
                    "legal_ball": 1,
                },
                {
                    "break": False,
                    "legal": True,
                    "shot_type": "safety_kick",
                    "pocket_index": -1,
                    "observation": [0.5] * 52,
                    "action": [-0.2, 0.4],
                    "lookahead_score": 0.2,
                    "immediate_score": 0.1,
                    "pocketed_numbers": [],
                    "first_contact": 1,
                    "legal_ball": 1,
                },
            ],
        }

    monkeypatch.setattr(rollout_data, "run_rack", fake_run_rack)

    dataset = rollout_data.generate(racks=2, seed=93000, max_shots=8, depth=2)

    assert [call[0] for call in calls] == [93000, 93001]
    assert all(call[1]["strategy"] == "teacher_lookahead" for call in calls)
    assert all(call[1]["opponent_strategy"] == "teacher_lookahead" for call in calls)
    assert dataset["observations"].shape == (2, 52)
    assert dataset["actions"].shape == (2, 2)
    assert dataset["pockets"].tolist() == [2, 2]
    assert dataset["groups"].tolist() == [0, 1]
    assert dataset["successes"].tolist() == [True, True]
    assert dataset["first_contacts"].tolist() == [True, True]
    assert dataset["metadata"]["ball_in_hand_placements"] == 2
    assert dataset["metadata"]["nine_ball_respots"] == 2
    assert dataset["metadata"]["safety_actions_executed_but_excluded"] == 2
    assert dataset["metadata"]["total_post_break_actions"] == 4
    assert dataset["metadata"]["rack_9ball_wins"] == 2
    assert np.allclose(dataset["scores"], [1.5, 1.5])
