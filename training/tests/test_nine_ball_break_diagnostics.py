from chernika_rl.diagnose_nine_ball_break import diagnose


def test_break_diagnostic_reports_legal_and_spread_metrics():
    report = diagnose(
        racks=1,
        seed=20261121,
        angle_offsets=(0.0,),
        powers=(97.8,),
    )

    assert report["racks"] == 1
    assert report["racks_with_correct_first_contact"] in (0, 1)
    assert report["racks_with_legal_break_candidate"] in (0, 1)
    assert 0.0 <= report["legal_break_candidate_rate"] <= 1.0
    assert len(report["per_rack"]) == 1
