import math

import chernika_rl.nine_ball_planner as planner
import chernika_rl.nine_ball_teacher as teacher
from chernika_rl.physics import (
    Ball, BallOutcome, POCKETS, SimulationResult,
)


def _pocketed_simulation(balls, angle, power):
    outcomes = []
    for ball in balls:
        outcomes.append(BallOutcome(
            number=ball.number,
            cue=ball.cue,
            x=ball.x,
            y=ball.y,
            pocketed=(not ball.cue and ball.number == 1),
            off_table=False,
        ))
    return SimulationResult(
        outcomes=tuple(outcomes),
        first_contact_number=1,
        ball_collisions=1,
        cushion_contacts=1,
        simulated_seconds=1.0,
        cue_scratch=False,
        any_ball_off_table=False,
    )


def _safety_simulation(balls, angle, power):
    outcomes = []
    for ball in balls:
        if ball.cue:
            x, y, pocketed = 310.0, 650.0, False
        elif ball.number == 1:
            x, y, pocketed = 300.0, 300.0, False
        elif ball.number == 2:
            x, y, pocketed = 300.0, 500.0, False
        else:
            x, y, pocketed = ball.x, ball.y, False
        outcomes.append(BallOutcome(
            number=ball.number, cue=ball.cue, x=x, y=y,
            pocketed=pocketed, off_table=False,
        ))
    return SimulationResult(
        outcomes=tuple(outcomes),
        first_contact_number=1,
        ball_collisions=1,
        cushion_contacts=1,
        simulated_seconds=1.0,
        cue_scratch=False,
        any_ball_off_table=False,
        post_contact_cushion_contacts=1,
        post_contact_cushion_ball_numbers=(2,),
    )


def test_one_cushion_object_bank_is_generated_and_verified(monkeypatch):
    monkeypatch.setattr(teacher, "simulate", _pocketed_simulation)
    cue = Ball(400.0, 250.0, 0, cue=True)
    target = Ball(300.0, 450.0, 1)
    balls = [target, Ball(250.0, 600.0, 2), Ball(400.0, 600.0, 9)]
    pocket = POCKETS[5]
    virtual_pocket = teacher._reflect_point(pocket.x, pocket.y, "left")

    candidate = teacher._evaluate_pot_route(
        cue, balls, target, 1, 5, virtual_pocket,
        shot_type="bank", rail="left",
    )

    assert candidate is not None
    assert candidate.shot_type == "bank"
    assert candidate.assessment.legal_shot
    assert candidate.assessment.pocketed_numbers == (1,)


def test_one_cushion_cue_kick_uses_post_rail_contact_direction(monkeypatch):
    monkeypatch.setattr(teacher, "simulate", _pocketed_simulation)
    cue = Ball(400.0, 450.0, 0, cue=True)
    target = Ball(300.0, 450.0, 1)
    balls = [target, Ball(250.0, 600.0, 2), Ball(400.0, 600.0, 9)]
    pocket = POCKETS[5]

    candidate = teacher._evaluate_pot_route(
        cue, balls, target, 1, 5, (pocket.x, pocket.y),
        shot_type="kick", rail="left",
    )

    assert candidate is not None
    assert candidate.shot_type == "kick"
    assert candidate.assessment.legal_shot
    assert candidate.assessment.pocketed_numbers == (1,)


def test_safety_fallback_returns_a_legal_defensive_shot(monkeypatch):
    monkeypatch.setattr(teacher, "simulate", _safety_simulation)
    monkeypatch.setattr(teacher, "_evaluate_pot_route", lambda *args, **kwargs: None)
    cue = Ball(300.0, 700.0, 0, cue=True)
    balls = [Ball(300.0, 300.0, 1), Ball(350.0, 500.0, 2), Ball(400.0, 400.0, 9)]

    candidate = teacher.plan_nine_ball_shot(cue, balls)

    assert candidate is not None
    assert candidate.shot_type == "safety"
    assert candidate.pocket_index == -1
    assert candidate.assessment.legal_shot
    assert candidate.assessment.pocketed_numbers == ()
    assert candidate.score > 40.0
    assert teacher.plan_nine_ball_shot(
        cue, balls, allow_safety=False
    ) is None


def test_two_ply_planner_can_fall_back_to_safety(monkeypatch):
    monkeypatch.setattr(teacher, "simulate", _safety_simulation)
    monkeypatch.setattr(teacher, "_evaluate_pot_route", lambda *args, **kwargs: None)
    monkeypatch.setattr(planner, "_candidate_plans", lambda *args, **kwargs: [])
    cue = Ball(300.0, 700.0, 0, cue=True)
    balls = [Ball(300.0, 300.0, 1), Ball(350.0, 500.0, 2), Ball(400.0, 400.0, 9)]

    result = planner.plan_nine_ball_shot_lookahead(cue, balls, depth=2)

    assert result is not None
    assert result.plan.shot_type == "safety"
    assert result.planned_pockets == ()
