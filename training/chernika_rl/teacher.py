"""Deterministic geometry + physics teacher for single-ball shot selection."""
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


@dataclass(frozen=True)
class TeacherPlan:
    action: np.ndarray
    pocket_index: int
    score: float
    result: SimulationResult


def plan_teacher_shot(cue: Ball, target: Ball) -> TeacherPlan | None:
    """Search ghost-ball candidates and power multipliers, verifying each in physics.

    This deliberately mirrors ChernikaSingleBallEnv.heuristic_action so that
    generated demonstrations remain comparable to the existing baseline.
    """
    direct = math.atan2(target.y - cue.y, target.x - cue.x)
    pixels_per_meter = (TABLE_WIDTH_PX / TABLE_WIDTH_METERS
                        + TABLE_HEIGHT_PX / TABLE_LENGTH_METERS) * 0.5
    decel = ROLLING_RESISTANCE * GRAVITY * pixels_per_meter
    max_speed = MAX_CUE_SPEED_MPS * pixels_per_meter
    candidates: list[TeacherPlan] = []

    for pocket_index, pocket in enumerate(POCKETS):
        dx, dy = pocket.x - target.x, pocket.y - target.y
        object_distance = math.hypot(dx, dy)
        if object_distance < 1e-6:
            continue
        ux, uy = dx / object_distance, dy / object_distance
        ghost_x = target.x - ux * (2.0 * BALL_RADIUS_PX + 1.0)
        ghost_y = target.y - uy * (2.0 * BALL_RADIUS_PX + 1.0)
        if not (TABLE_LEFT + BALL_RADIUS_PX <= ghost_x <= TABLE_RIGHT - BALL_RADIUS_PX
                and TABLE_TOP + BALL_RADIUS_PX <= ghost_y <= TABLE_BOTTOM - BALL_RADIUS_PX):
            continue

        cue_dx, cue_dy = ghost_x - cue.x, ghost_y - cue.y
        cue_distance = math.hypot(cue_dx, cue_dy)
        if cue_distance < BALL_RADIUS_PX * 2.0:
            continue

        shot_angle = math.atan2(cue_dy, cue_dx)
        delta = wrap_angle(shot_angle - direct)
        contact_cos = math.cos(wrap_angle(shot_angle - math.atan2(uy, ux)))
        if contact_cos <= 0.18:
            continue

        required_speed = max(
            math.sqrt(max(0.0, 2.0 * decel * cue_distance)),
            math.sqrt(max(0.0, 2.0 * decel * object_distance)) / contact_cos,
        )
        base_power = min(
            MAX_UI_POWER_PERCENT,
            max(1.0, required_speed / max_speed * MAX_UI_POWER_PERCENT * 1.12),
        )

        for multiplier in (0.75, 0.90, 1.00, 1.10, 1.25, 1.40):
            power = min(MAX_UI_POWER_PERCENT, max(1.0, base_power * multiplier))
            action = np.array(
                [delta / math.pi, 2.0 * power / MAX_UI_POWER_PERCENT - 1.0],
                dtype=np.float32,
            )
            result = simulate([cue, target], direct + delta, power)

            score = 100.0 if result.pocketed(target.number) else 0.0
            if result.first_contact_number == target.number:
                score += 2.0
            if result.cue_scratch:
                score -= 20.0
            if result.any_ball_off_table:
                score -= 10.0
            if score == 0.0:
                position = result.final_position(target.number)
                if position is not None:
                    after = min(
                        math.hypot(position[0] - p.x, position[1] - p.y)
                        for p in POCKETS
                    )
                    score += max(-1.0, min(1.0,
                        (object_distance - after) / TABLE_WIDTH_PX))

            candidates.append(TeacherPlan(action, pocket_index, score, result))

    if not candidates:
        return None
    return max(candidates, key=lambda candidate: candidate.score)
