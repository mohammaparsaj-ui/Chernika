"""Shared 9-ball legality assessment for environment and physics teacher."""
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


def assess_nine_ball_shot(
    simulation: SimulationResult,
    legal_ball: int,
    break_shot: bool = False,
) -> NineBallAssessment:
    """Apply first-contact, scratch, rail and break rules to one simulation."""
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
    elif simulation.first_contact_number != legal_ball:
        foul_reason = (
            "no_object_ball_contact"
            if simulation.first_contact_number is None
            else "wrong_first_contact"
        )
    elif break_shot:
        if not pocketed and len(simulation.post_contact_cushion_ball_numbers) < 4:
            foul_reason = "break_rail_requirement"
    elif not pocketed and simulation.post_contact_cushion_contacts == 0:
        foul_reason = "no_rail_after_contact"

    return NineBallAssessment(
        legal_ball=legal_ball,
        first_contact_number=simulation.first_contact_number,
        pocketed_numbers=pocketed,
        foul_reason=foul_reason,
    )
