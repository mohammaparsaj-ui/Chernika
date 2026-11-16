"""Plato-observed 9-ball legality assessment for the environment and teacher."""
from __future__ import annotations

from dataclasses import dataclass

from .physics import SimulationResult


@dataclass(frozen=True)
class NineBallAssessment:
    legal_ball: int
    first_contact_number: int | None
    pocketed_numbers: tuple[int, ...]
    foul_reason: str | None

    @property
    def legal_shot(self) -> bool:
        return self.foul_reason is None

    @property
    def won(self) -> bool:
        return self.legal_shot and 9 in self.pocketed_numbers


def break_requirement_met(simulation: SimulationResult) -> bool:
    """Plato 9-ball: any object-ball pot, or 3 distinct balls / 4 rail hits."""
    if simulation is None:
        raise ValueError("simulation is required")
    if any(not outcome.cue and outcome.pocketed for outcome in simulation.outcomes):
        return True
    return (
        len(set(simulation.post_contact_cushion_ball_numbers)) >= 3
        and simulation.post_contact_object_ball_cushion_contacts >= 4
    )


def assess_nine_ball_shot(
    simulation: SimulationResult,
    legal_ball: int,
    break_shot: bool = False,
) -> NineBallAssessment:
    """Apply Plato 9-ball first-contact, scratch, rail and break rules."""
    if simulation is None:
        raise ValueError("simulation is required")
    if not 1 <= legal_ball <= 9:
        raise ValueError("legal_ball must be in [1, 9]")

    pocketed = tuple(sorted(
        outcome.number for outcome in simulation.outcomes
        if not outcome.cue and outcome.pocketed
    ))
    foul_reason = None

    if simulation.cue_scratch:
        foul_reason = "cue_scratch"
    elif simulation.any_ball_off_table:
        foul_reason = "ball_off_table"
    elif break_shot:
        # Plato's 9-ball break is exempt from lowest-ball-first contact, but
        # the cue ball must still contact at least one object ball.
        if simulation.first_contact_number is None:
            foul_reason = "no_object_ball_contact"
        elif not break_requirement_met(simulation):
            foul_reason = "break_rail_requirement"
    elif simulation.first_contact_number != legal_ball:
        foul_reason = (
            "no_object_ball_contact"
            if simulation.first_contact_number is None
            else "wrong_first_contact"
        )
    elif (
        not pocketed
        and simulation.post_contact_cushion_contacts == 0
        and simulation.cue_pre_contact_cushion_contacts == 0
    ):
        # In ordinary Plato shots, a cue-ball rail hit before first contact
        # also satisfies the no-pot cushion requirement.
        foul_reason = "no_rail_after_contact"

    return NineBallAssessment(
        legal_ball=legal_ball,
        first_contact_number=simulation.first_contact_number,
        pocketed_numbers=pocketed,
        foul_reason=foul_reason,
    )
