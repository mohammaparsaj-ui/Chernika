from chernika_rl.diagnose_nine_ball_break import diagnose


def test_break_diagnostic_reports_the_calibrated_teacher_result():
    report = diagnose(racks=1, seed=90900)

    assert report["racks"] == 1
    assert report["break_cue_speed_multiplier"] == 4.0
    assert report["racks_with_teacher_plan"] == 1
    assert report["racks_with_object_ball_contact"] == 1
    assert report["racks_with_legal_break_candidate"] == 1
    assert report["legal_break_candidate_rate"] == 1.0
    assert report["mean_best_valid_contact_spread"] >= 4
    assert report["per_rack"][0]["shot_type"] == "break"
