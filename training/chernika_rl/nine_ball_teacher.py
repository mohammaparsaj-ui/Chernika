"""Multi-ball 9-ball teacher: legal first contact + physics-verified candidates."""
from __future__ import annotations

from dataclasses import dataclass
import math
import numpy as np

from .physics import (
    Ball, POCKETS, BALL_RADIUS_PX, TABLE_LEFT, TABLE_TOP, TABLE_RIGHT,
    TABLE_BOTTOM, TABLE_WIDTH_PX, TABLE_HEIGHT_PX, TABLE_WIDTH_METERS, TABLE_LENGTH_METERS,
    ROLLING_RESISTANCE, GRAVITY, MAX_CUE_SPEED_MPS, MAX_UI_POWER_PERCENT,
    BREAK_CUE_SPEED_MULTIPLIER, SimulationResult, simulate, wrap_angle,
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
    shot_type: str = "direct"


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



def _rail_coordinate(rail: str) -> float:
    if rail == "left":
        return TABLE_LEFT + BALL_RADIUS_PX
    if rail == "right":
        return TABLE_RIGHT - BALL_RADIUS_PX
    if rail == "top":
        return TABLE_TOP + BALL_RADIUS_PX
    if rail == "bottom":
        return TABLE_BOTTOM - BALL_RADIUS_PX
    raise ValueError(f"unknown rail: {rail}")


def _reflect_point(x: float, y: float, rail: str) -> tuple[float, float]:
    """Reflect a point across the ball-centre cushion line."""
    coordinate = _rail_coordinate(rail)
    if rail in ("left", "right"):
        return 2.0 * coordinate - x, y
    return x, 2.0 * coordinate - y


def _rail_intersection(
    start: tuple[float, float],
    end: tuple[float, float],
    rail: str,
) -> tuple[float, float] | None:
    """Return a valid non-pocket cushion crossing for an unfolded route."""
    coordinate = _rail_coordinate(rail)
    sx, sy = start
    ex, ey = end
    if rail in ("left", "right"):
        denominator = ex - sx
        if abs(denominator) < 1e-8:
            return None
        t = (coordinate - sx) / denominator
        along = sy + t * (ey - sy)
        low, high = TABLE_TOP + BALL_RADIUS_PX, TABLE_BOTTOM - BALL_RADIUS_PX
    else:
        denominator = ey - sy
        if abs(denominator) < 1e-8:
            return None
        t = (coordinate - sy) / denominator
        along = sx + t * (ex - sx)
        low, high = TABLE_LEFT + BALL_RADIUS_PX, TABLE_RIGHT - BALL_RADIUS_PX

    if not 1e-5 < t < 1.0 - 1e-5 or not low <= along <= high:
        return None

    # A route through a pocket mouth is not a cushion bank/kick.
    for pocket in POCKETS:
        if rail == "left":
            adjacent = abs(pocket.x - TABLE_LEFT) <= BALL_RADIUS_PX * 2.0
            pocket_along = pocket.y
            half_opening = pocket.opening_height * 0.5
        elif rail == "right":
            adjacent = abs(pocket.x - TABLE_RIGHT) <= BALL_RADIUS_PX * 2.0
            pocket_along = pocket.y
            half_opening = pocket.opening_height * 0.5
        elif rail == "top":
            adjacent = abs(pocket.y - TABLE_TOP) <= BALL_RADIUS_PX * 2.0
            pocket_along = pocket.x
            half_opening = pocket.opening_width * 0.5
        else:
            adjacent = abs(pocket.y - TABLE_BOTTOM) <= BALL_RADIUS_PX * 2.0
            pocket_along = pocket.x
            half_opening = pocket.opening_width * 0.5
        usable = max(1.0, half_opening - BALL_RADIUS_PX * 0.72)
        if adjacent and abs(along - pocket_along) <= usable:
            return None
    return (coordinate, along) if rail in ("left", "right") else (along, coordinate)


def _evaluate_pot_route(
    cue: Ball,
    balls: list[Ball],
    target: Ball,
    legal_ball: int,
    pocket_index: int,
    virtual_pocket: tuple[float, float],
    *,
    shot_type: str,
    rail: str | None = None,
) -> NineBallPlan | None:
    """Generate and physics-check one direct, object-bank or cue-kick route."""
    dx = virtual_pocket[0] - target.x
    dy = virtual_pocket[1] - target.y
    object_distance = math.hypot(dx, dy)
    if object_distance < 1e-6:
        return None
    ux, uy = dx / object_distance, dy / object_distance
    ghost_x = target.x - ux * (2.0 * BALL_RADIUS_PX + 1.0)
    ghost_y = target.y - uy * (2.0 * BALL_RADIUS_PX + 1.0)
    if not (
        TABLE_LEFT + BALL_RADIUS_PX <= ghost_x <= TABLE_RIGHT - BALL_RADIUS_PX
        and TABLE_TOP + BALL_RADIUS_PX <= ghost_y <= TABLE_BOTTOM - BALL_RADIUS_PX
    ):
        return None

    if shot_type == "kick":
        if rail is None:
            raise ValueError("kick routes require a cushion")
        mirrored_cue = _reflect_point(cue.x, cue.y, rail)
        if _rail_intersection(
            mirrored_cue, (ghost_x, ghost_y), rail
        ) is None:
            return None
        contact_angle = math.atan2(
            ghost_y - mirrored_cue[1], ghost_x - mirrored_cue[0]
        )
        vx, vy = math.cos(contact_angle), math.sin(contact_angle)
        if rail in ("left", "right"):
            vx = -vx
        else:
            vy = -vy
        shot_angle = math.atan2(vy, vx)
        cue_distance = math.hypot(
            ghost_x - mirrored_cue[0], ghost_y - mirrored_cue[1]
        )
    else:
        if shot_type == "bank":
            if rail is None or _rail_intersection(
                (target.x, target.y), virtual_pocket, rail
            ) is None:
                return None
        cue_dx, cue_dy = ghost_x - cue.x, ghost_y - cue.y
        cue_distance = math.hypot(cue_dx, cue_dy)
        if cue_distance < 2.0 * BALL_RADIUS_PX:
            return None
        shot_angle = math.atan2(cue_dy, cue_dx)
        contact_angle = shot_angle

    direct = math.atan2(target.y - cue.y, target.x - cue.x)
    angle_offset = wrap_angle(shot_angle - direct)
    object_angle = math.atan2(uy, ux)
    if shot_type == "kick":
        contact_cos = math.cos(wrap_angle(contact_angle - object_angle))
    else:
        contact_cos = math.cos(wrap_angle(shot_angle - object_angle))
    if contact_cos <= 0.18:
        return None

    pixels_per_meter = _pixels_per_meter()
    deceleration = ROLLING_RESISTANCE * GRAVITY * pixels_per_meter
    maximum_speed = MAX_CUE_SPEED_MPS * pixels_per_meter
    required_speed = max(
        math.sqrt(max(0.0, 2.0 * deceleration * cue_distance)),
        math.sqrt(max(0.0, 2.0 * deceleration * object_distance))
        / contact_cos,
    )
    base_power = min(
        MAX_UI_POWER_PERCENT,
        max(1.0, required_speed / maximum_speed * MAX_UI_POWER_PERCENT * 1.12),
    )

    route_penalty = {"direct": 0.0, "bank": 2.0, "kick": 3.0}[shot_type]
    best: NineBallPlan | None = None
    power_multipliers = (
        (0.75, 0.90, 1.00, 1.10, 1.25, 1.40)
        if shot_type == "direct" else (0.85, 1.00, 1.20)
    )
    for multiplier in power_multipliers:
        power = min(
            MAX_UI_POWER_PERCENT,
            max(1.0, base_power * multiplier),
        )
        result = simulate([cue, *balls], shot_angle, power)
        assessment = assess_nine_ball_shot(result, legal_ball, False)
        # Potting routes must actually pocket a ball. Legal misses are handled
        # by the explicit safety search rather than mislabeled as pot attempts.
        if not assessment.legal_shot or not assessment.pocketed_numbers:
            continue
        score = _score_legal_result(
            result, assessment, legal_ball, None, False
        ) - route_penalty
        candidate = NineBallPlan(
            _power_action(angle_offset, power),
            legal_ball,
            pocket_index,
            score,
            result,
            assessment,
            False,
            shot_type,
        )
        if best is None or candidate.score > best.score:
            best = candidate
    return best


def _safety_score(
    result: SimulationResult,
    legal_ball: int,
    source_balls: list[Ball],
) -> float | None:
    if result.first_contact_number != legal_ball or result.cue_scratch or result.any_ball_off_table:
        return None
    if any(outcome.pocketed and not outcome.cue for outcome in result.outcomes):
        return None

    cue_outcome = next((o for o in result.outcomes if o.cue), None)
    target_outcome = next(
        (o for o in result.outcomes if not o.cue and o.number == legal_ball), None
    )
    if (
        cue_outcome is None or cue_outcome.pocketed or cue_outcome.off_table
        or target_outcome is None or target_outcome.pocketed or target_outcome.off_table
    ):
        return None

    separation = math.hypot(
        cue_outcome.x - target_outcome.x,
        cue_outcome.y - target_outcome.y,
    )
    diagonal = math.hypot(TABLE_WIDTH_PX, TABLE_HEIGHT_PX)
    score = 25.0 + 12.0 * min(1.0, separation / diagonal)

    # Reward hiding the cue ball behind another object ball from the next
    # player's straight line to the lowest-numbered ball.
    vx = target_outcome.x - cue_outcome.x
    vy = target_outcome.y - cue_outcome.y
    length_squared = max(1e-8, vx * vx + vy * vy)
    blockers = 0
    for outcome in result.outcomes:
        if outcome.cue or outcome.number == legal_ball or outcome.pocketed or outcome.off_table:
            continue
        projection = (
            (outcome.x - cue_outcome.x) * vx
            + (outcome.y - cue_outcome.y) * vy
        ) / length_squared
        if not 0.08 < projection < 0.92:
            continue
        closest_x = cue_outcome.x + projection * vx
        closest_y = cue_outcome.y + projection * vy
        if math.hypot(outcome.x - closest_x, outcome.y - closest_y) < 2.2 * BALL_RADIUS_PX:
            blockers += 1
    score += min(2, blockers) * 18.0

    nearest_target_pocket = _nearest_pocket_distance(
        target_outcome.x, target_outcome.y
    )
    score += 8.0 * min(1.0, nearest_target_pocket / diagonal)

    rail_clearance = min(
        cue_outcome.x - (TABLE_LEFT + BALL_RADIUS_PX),
        (TABLE_RIGHT - BALL_RADIUS_PX) - cue_outcome.x,
        cue_outcome.y - (TABLE_TOP + BALL_RADIUS_PX),
        (TABLE_BOTTOM - BALL_RADIUS_PX) - cue_outcome.y,
    )
    if rail_clearance < 2.0 * BALL_RADIUS_PX:
        score += 6.0 * (1.0 - max(0.0, rail_clearance) / (2.0 * BALL_RADIUS_PX))
    return score


def _plan_safety_shot(
    cue: Ball,
    balls: list[Ball],
    legal_ball: int,
) -> NineBallPlan | None:
    """Find a legal no-pot shot that increases distance or hides the next target."""
    by_number = {ball.number: ball for ball in balls}
    target = by_number[legal_ball]
    direct = math.atan2(target.y - cue.y, target.x - cue.x)
    distance = math.hypot(target.x - cue.x, target.y - cue.y)
    if distance < 1e-6:
        return None
    maximum_offset = math.asin(min(
        0.95, (cue.radius + target.radius - 0.25) / distance
    ))
    fractions = (-1.0, -0.5, 0.0, 0.5, 1.0)
    powers = (35.0, 55.0, 75.0, MAX_UI_POWER_PERCENT)
    best: NineBallPlan | None = None

    for fraction in fractions:
        angle_offset = fraction * maximum_offset
        shot_angle = direct + angle_offset
        for power in powers:
            result = simulate([cue, *balls], shot_angle, power)
            assessment = assess_nine_ball_shot(result, legal_ball, False)
            if not assessment.legal_shot:
                continue
            score = _safety_score(result, legal_ball, balls)
            if score is None:
                continue
            candidate = NineBallPlan(
                _power_action(angle_offset, power),
                legal_ball,
                -1,
                score,
                result,
                assessment,
                False,
                "safety",
            )
            if best is None or candidate.score > best.score:
                best = candidate
    return best

def plan_nine_ball_shot(
    cue: Ball,
    object_balls: list[Ball] | tuple[Ball, ...],
    break_shot: bool = False,
    pocket_indices: list[int] | tuple[int, ...] | None = None,
    allow_safety: bool = True,
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
        if pocket_indices is not None:
            raise ValueError("pocket_indices cannot be used for a break shot")
        if legal_ball != 1:
            return None
        # Direct-to-apex fallbacks plus explicit second-row target lines.
        # The latter are the recorded Plato break strategy: clip #1 first,
        # then transfer energy into one of the two adjacent rack balls.
        angle_candidates: list[tuple[float, bool]] = [
            (direct + offset * math.pi, False)
            for offset in (-0.02, 0.0, 0.02)
        ]
        second_row = []
        for ball in balls:
            if ball.number == legal_ball:
                continue
            dx = ball.x - target.x
            dy = ball.y - target.y
            distance = math.hypot(dx, dy)
            diameter = target.radius + ball.radius
            if (
                dy < -0.5 * target.radius
                and 0.85 * diameter <= distance <= 1.15 * diameter
                and abs(dx) >= 0.25 * target.radius
            ):
                second_row.append(ball)

        for second_ball in sorted(second_row, key=lambda item: item.x):
            aim_angle = math.atan2(
                second_ball.y - cue.y, second_ball.x - cue.x
            )
            for delta in (-0.02, 0.0, 0.02):
                angle_candidates.append((aim_angle + delta, True))

        powers = (MAX_UI_POWER_PERCENT,)
        seen_offsets: set[float] = set()
        for candidate_angle, second_ball_line in angle_candidates:
            action_angle = wrap_angle(candidate_angle - direct)
            key = round(action_angle, 6)
            if key in seen_offsets:
                continue
            seen_offsets.add(key)
            for power in powers:
                action = _power_action(action_angle, power)
                result = simulate(
                    [cue, *balls],
                    direct + action_angle,
                    power,
                    cue_speed_multiplier=BREAK_CUE_SPEED_MULTIPLIER,
                )
                assessment = assess_nine_ball_shot(result, legal_ball, True)
                score = _score_legal_result(
                    result, assessment, legal_ball, before_distance, True
                )
                if score < 0.0:
                    continue
                if second_ball_line:
                    score += 0.5
                candidates.append(NineBallPlan(
                    action, legal_ball, -1, score, result, assessment, True, "break"
                ))
    else:
        selected_pockets = (
            list(range(len(POCKETS)))
            if pocket_indices is None else list(pocket_indices)
        )
        if not selected_pockets or any(
            index < 0 or index >= len(POCKETS) for index in selected_pockets
        ):
            raise ValueError("pocket_indices must contain pocket indices in [0, 5]")
        if len(set(selected_pockets)) != len(selected_pockets):
            raise ValueError("pocket_indices must be unique")

        # Prefer a direct pot when one is physically available.
        for pocket_index in selected_pockets:
            pocket = POCKETS[pocket_index]
            candidate = _evaluate_pot_route(
                cue, balls, target, legal_ball, pocket_index,
                (pocket.x, pocket.y), shot_type="direct",
            )
            if candidate is not None:
                candidates.append(candidate)
        if candidates:
            return max(candidates, key=lambda item: item.score)

        # One-cushion object-ball banks: unfold each pocket across each rail.
        for pocket_index in selected_pockets:
            pocket = POCKETS[pocket_index]
            for rail in ("left", "right", "top", "bottom"):
                virtual_pocket = _reflect_point(pocket.x, pocket.y, rail)
                candidate = _evaluate_pot_route(
                    cue, balls, target, legal_ball, pocket_index,
                    virtual_pocket, shot_type="bank", rail=rail,
                )
                if candidate is not None:
                    candidates.append(candidate)
        if candidates:
            return max(candidates, key=lambda item: item.score)

        # One-cushion cue-ball kicks to a direct object-ball pot.
        for pocket_index in selected_pockets:
            pocket = POCKETS[pocket_index]
            for rail in ("left", "right", "top", "bottom"):
                candidate = _evaluate_pot_route(
                    cue, balls, target, legal_ball, pocket_index,
                    (pocket.x, pocket.y), shot_type="kick", rail=rail,
                )
                if candidate is not None:
                    candidates.append(candidate)
        if candidates:
            return max(candidates, key=lambda item: item.score)

        if allow_safety:
            return _plan_safety_shot(cue, balls, legal_ball)
        return None

    if not candidates:
        return None
    return max(candidates, key=lambda candidate: candidate.score)
