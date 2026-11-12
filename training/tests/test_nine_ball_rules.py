from chernika_rl.nine_ball_rules import (
    assess_nine_ball_shot,
    break_requirement_met,
)
from chernika_rl.physics import BallOutcome, SimulationResult


def make_simulation(
    *,
    first_contact=1,
    pocketed=(),
    distinct_rail_balls=(),
    object_rail_contacts=0,
    post_contacts=0,
    cue_pre_contacts=0,
    scratch=False,
    off_table=False,
):
    outcomes = [
        BallOutcome(0, True, 300.0, 700.0, scratch, False),
    ]
    for number in range(1, 10):
        outcomes.append(BallOutcome(
            number,
            False,
            200.0 + number,
            300.0,
            number in pocketed,
            off_table and number == 8,
        ))
    return SimulationResult(
        outcomes=tuple(outcomes),
        first_contact_number=first_contact,
        ball_collisions=1,
        cushion_contacts=object_rail_contacts + post_contacts + cue_pre_contacts,
        simulated_seconds=1.0,
        cue_scratch=scratch,
        any_ball_off_table=off_table,
        post_contact_cushion_contacts=post_contacts,
        post_contact_cushion_ball_numbers=tuple(distinct_rail_balls),
        post_contact_object_ball_cushion_contacts=object_rail_contacts,
        cue_pre_contact_cushion_contacts=cue_pre_contacts,
    )


def test_plato_no_pot_break_needs_three_distinct_balls_and_four_object_rail_hits():
    valid = make_simulation(
        first_contact=2,
        distinct_rail_balls=(1, 2, 3),
        object_rail_contacts=4,
    )
    assert break_requirement_met(valid)
    assessment = assess_nine_ball_shot(valid, legal_ball=1, break_shot=True)
    assert assessment.legal_shot
    assert assessment.first_contact_number == 2


def test_four_rail_hits_by_only_two_object_balls_do_not_qualify_break():
    simulation = make_simulation(
        first_contact=1,
        distinct_rail_balls=(1, 2),
        object_rail_contacts=4,
    )
    assert not break_requirement_met(simulation)
    assessment = assess_nine_ball_shot(simulation, legal_ball=1, break_shot=True)
    assert assessment.foul_reason == "break_rail_requirement"


def test_three_distinct_object_balls_with_only_three_rail_hits_do_not_qualify():
    simulation = make_simulation(
        first_contact=1,
        distinct_rail_balls=(1, 2, 3),
        object_rail_contacts=3,
    )
    assert not break_requirement_met(simulation)


def test_any_object_ball_pocket_satisfies_break_requirement():
    simulation = make_simulation(
        first_contact=4,
        pocketed=(4,),
        distinct_rail_balls=(),
        object_rail_contacts=0,
    )
    assert break_requirement_met(simulation)
    assert assess_nine_ball_shot(
        simulation, legal_ball=1, break_shot=True
    ).legal_shot


def test_missing_every_object_ball_on_break_is_foul_even_if_no_rail_count_is_reported():
    simulation = make_simulation(
        first_contact=None,
        distinct_rail_balls=(),
        object_rail_contacts=0,
    )
    assessment = assess_nine_ball_shot(simulation, legal_ball=1, break_shot=True)
    assert assessment.foul_reason == "no_object_ball_contact"


def test_cue_rail_before_first_contact_satisfies_ordinary_no_pot_rule():
    simulation = make_simulation(
        first_contact=1,
        distinct_rail_balls=(),
        object_rail_contacts=0,
        post_contacts=0,
        cue_pre_contacts=1,
    )
    assessment = assess_nine_ball_shot(simulation, legal_ball=1, break_shot=False)
    assert assessment.legal_shot


def test_cue_rail_does_not_count_toward_break_requirement():
    simulation = make_simulation(
        first_contact=1,
        distinct_rail_balls=(),
        object_rail_contacts=0,
        cue_pre_contacts=4,
    )
    assert not break_requirement_met(simulation)
    assessment = assess_nine_ball_shot(simulation, legal_ball=1, break_shot=True)
    assert assessment.foul_reason == "break_rail_requirement"


def test_break_scratch_with_nine_pocketed_is_foul_not_a_win():
    simulation = make_simulation(
        first_contact=1,
        pocketed=(9,),
        scratch=True,
    )
    assessment = assess_nine_ball_shot(simulation, legal_ball=1, break_shot=True)
    assert assessment.foul_reason == "cue_scratch"
    assert not assessment.won
