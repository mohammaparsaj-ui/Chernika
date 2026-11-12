"""Multi-ball 9-ball teacher: legal first contact + physics-verified candidates."""
from __future__ import annotations

from dataclasses import dataclass
import math
import numpy as np

from .physics import (
    Ball, POCKETS, BALL_RADIUS_PX, TABLE_LEFT, TABLE_TOP, TABLE_RIGHT,
    TABLE_BOTTOM, TABLE_WIDTH_PX, TABLE_WIDTH_METERS, TABLE_LENGTH_METERS,
    ROLLING_RESISTANCE, GRAVITY, MAX_CUE_SPEED_MPS, MAX_UI_POWER_PERCENT,
    SimulationResult, simulate, wrap_angle,
)
from .nine_ball_rules import NineBallAssessment, assess_nine_ball_shot


@dataclass(frozen=True)
class NineBallPlan:
    action: np.ndarray
    legal_ball: int
    pocket_index: int
    score: float
    result: SimulationResult
    assessment: NineBallAssessment
    break_shot: bool


def _pixels_per_meter() -> float:
    return (
        TABLE_WIDTH_PX / TABLE_WIDTH_METERS
        + (TABLE_BOTTOM - TABLE_TOP) / TABLE_LENGTH_METERS
    ) * 0.5


def _power_action(angle_offset: float, power: float) -> np.ndarray:
    return np.asarray(
        [
            angle_offset / math.pi,
            2.0 * power / MAX_UI_POWER_PERCENT - 1.0,
        ],
        dtype=np.float32,
    )


def _nearest_pocket_distance(x: float, y: float) -> float:
    return min(math.hypot(x - pocket.x, y - pocket.y) for pocket in POCKETS)


def _score_legal_result(
    result: SimulationResult,
    assessment: NineBallAssessment,
    legal_ball: int,
    before_distance: float | None,
    break_shot: bool,
) -> float:
    if not assessment.legal_shot:
        return -1000.0
    if assessment.won:
        return 1000.0
    if assessment.pocketed_numbers:
        score = 100.0 + 5.0 * len(assessment.pocketed_numbers)
        if legal_ball in assessment.pocketed_numbers:
            score += 20.0
        return score
    if break_shot:
        return 10.0 + 2.0 * len(result.post_contact_cushion_ball_numbers)

    final_position = result.final_position(legal_ball)
    if final_position is None or before_distance is None:
        return 10.0
    after_distance = _nearest_pocket_distance(*final_position)
    progress = (before_distance - after_distance) / TABLE_WIDTH_PX
    return 10.0 + max(-1.0, min(1.0, progress))


def plan_nine_ball_shot(
    cue: Ball,
    object_balls: list[Ball] | tuple[Ball, ...],
    break_shot: bool = False,
) -> NineBallPlan | None:
    """Find a legal, physics-verified direct shot for the current 9-ball state.

    On the break, test centered and small-offset hits on the #1 apex ball.
    In normal play, enumerate ghost-ball paths from the lowest ball to all six
    measured pockets. Other object balls stay in the simulation, so a blocked
    path or wrong first contact is rejected by the shared rule assessor.
    """
    if cue is None or not cue.cue:
        raise ValueError("cue must be a cue Ball")
    balls = list(object_balls)
    if not balls:
        return None
    if any(ball.cue or not 1 <= ball.number <= 9 for ball in balls):
        raise ValueError("object_balls must contain numbered object balls only")
    if len({ball.number for ball in balls}) != len(balls):
        raise ValueError("object-ball numbers must be unique")

    balls.sort(key=lambda ball: ball.number)
    by_number = {ball.number: ball for ball in balls}
    legal_ball = min(by_number)
    target = by_number[legal_ball]
    direct = math.atan2(target.y - cue.y, target.x - cue.x)
    before_distance = _nearest_pocket_distance(target.x, target.y)
    candidates: list[NineBallPlan] = []

    if break_shot:
        if legal_ball != 1:
            return None
        angle_offsets = (-0.04, -0.02, 0.0, 0.02, 0.04)
        powers = (70.0, 80.0, 90.0, MAX_UI_POWER_PERCENT)
        for offset in angle_offsets:
            action_angle = offset * math.pi
            for power in powers:
                action = _power_action(action_angle, power)
                result = simulate([cue, *balls], direct + action_angle, power)
                assessment = assess_nine_ball_shot(result, legal_ball, True)
                score = _score_legal_result(
                    result, assessment, legal_ball, before_distance, True
                )
                if score < 0.0:
                    continue
                candidates.append(NineBallPlan(
                    action, legal_ball, -1, score, result, assessment, True
                ))
    else:
        pixels_per_meter = _pixels_per_meter()
        deceleration = ROLLING_RESISTANCE * GRAVITY * pixels_per_meter
        maximum_speed = MAX_CUE_SPEED_MPS * pixels_per_meter

        for pocket_index, pocket in enumerate(POCKETS):
            dx = pocket.x - target.x
            dy = pocket.y - target.y
            object_distance = math.hypot(dx, dy)
            if object_distance < 1e-6:
                continue
            ux, uy = dx / object_distance, dy / object_distance
            ghost_x = target.x - ux * (2.0 * BALL_RADIUS_PX + 1.0)
            ghost_y = target.y - uy * (2.0 * BALL_RADIUS_PX + 1.0)
            if not (
                TABLE_LEFT + BALL_RADIUS_PX <= ghost_x <= TABLE_RIGHT - BALL_RADIUS_PX
                and TABLE_TOP + BALL_RADIUS_PX <= ghost_y <= TABLE_BOTTOM - BALL_RADIUS_PX
            ):
                continue

            cue_dx, cue_dy = ghost_x - cue.x, ghost_y - cue.y
            cue_distance = math.hypot(cue_dx, cue_dy)
            if cue_distance < 2.0 * BALL_RADIUS_PX:
                continue

            shot_angle = math.atan2(cue_dy, cue_dx)
            angle_offset = wrap_angle(shot_angle - direct)
            contact_cos = math.cos(
                wrap_angle(shot_angle - math.atan2(uy, ux))
            )
            if contact_cos <= 0.18:
                continue

            required_speed = max(
                math.sqrt(max(0.0, 2.0 * deceleration * cue_distance)),
                math.sqrt(max(0.0, 2.0 * deceleration * object_distance))
                / contact_cos,
            )
            base_power = min(
                MAX_UI_POWER_PERCENT,
                max(
                    1.0,
                    required_speed / maximum_speed
                    * MAX_UI_POWER_PERCENT * 1.12,
                ),
            )

            for multiplier in (0.75, 0.90, 1.00, 1.10, 1.25, 1.40):
                power = min(
                    MAX_UI_POWER_PERCENT,
                    max(1.0, base_power * multiplier),
                )
                action = _power_action(angle_offset, power)
                result = simulate([cue, *balls], shot_angle, power)
                assessment = assess_nine_ball_shot(result, legal_ball, False)
                score = _score_legal_result(
                    result, assessment, legal_ball, before_distance, False
                )
                if score < 0.0:
                    continue
                candidates.append(NineBallPlan(
                    action, legal_ball, pocket_index, score,
                    result, assessment, False
                ))

    if not candidates:
        return None
    return max(candidates, key=lambda candidate: candidate.score)
