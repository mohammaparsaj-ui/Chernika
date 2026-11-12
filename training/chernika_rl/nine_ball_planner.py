"""Short-horizon, physics-verified planning for sequential nine-ball shots."""
from __future__ import annotations

from dataclasses import dataclass
import math

from .physics import Ball, POCKETS
from .nine_ball_teacher import NineBallPlan, plan_nine_ball_shot


@dataclass(frozen=True)
class LookaheadPlan:
    """Best first shot plus the value of its one-shot continuation."""

    plan: NineBallPlan
    total_score: float
    next_score: float | None
    planned_pockets: tuple[int, ...]
    candidate_count: int
    future_candidate_count: int


def _validate_pockets(pocket_indices: list[int] | tuple[int, ...] | None) -> list[int]:
    selected = list(range(len(POCKETS))) if pocket_indices is None else list(pocket_indices)
    if not selected or any(index < 0 or index >= len(POCKETS) for index in selected):
        raise ValueError("pocket_indices must contain pocket indices in [0, 5]")
    if len(set(selected)) != len(selected):
        raise ValueError("pocket_indices must be unique")
    return selected


def _candidate_plans(
    cue: Ball,
    balls: list[Ball],
    pocket_indices: list[int],
) -> list[NineBallPlan]:
    candidates = []
    for pocket_index in pocket_indices:
        candidate = plan_nine_ball_shot(
            cue, balls, break_shot=False, pocket_indices=[pocket_index],
            allow_safety=False,
        )
        if candidate is not None and candidate.assessment.legal_shot:
            candidates.append(candidate)
    return candidates


def _next_state(
    plan: NineBallPlan,
    cue: Ball,
    balls: list[Ball],
) -> tuple[Ball, list[Ball]] | None:
    if not plan.assessment.legal_shot or plan.assessment.won:
        return None
    source_by_number = {ball.number: ball for ball in balls}
    cue_outcome = next(
        (outcome for outcome in plan.result.outcomes if outcome.cue), None
    )
    if cue_outcome is None or cue_outcome.pocketed or cue_outcome.off_table:
        return None

    next_cue = Ball(
        cue_outcome.x, cue_outcome.y, 0, cue=True, radius=cue.radius
    )
    next_balls = []
    for outcome in plan.result.outcomes:
        if outcome.cue or outcome.pocketed or outcome.off_table:
            continue
        source = source_by_number[outcome.number]
        next_balls.append(Ball(
            outcome.x, outcome.y, outcome.number,
            cue=False, radius=source.radius,
        ))
    return next_cue, sorted(next_balls, key=lambda ball: ball.number)


def plan_nine_ball_shot_lookahead(
    cue: Ball,
    object_balls: list[Ball] | tuple[Ball, ...],
    *,
    depth: int = 2,
    discount: float = 0.65,
    beam_width: int = 3,
    pocket_indices: list[int] | tuple[int, ...] | None = None,
    allow_safety: bool = True,
) -> LookaheadPlan | None:
    """Choose a first shot using immediate physics score plus one continuation.

    Depth 1 is the existing greedy teacher. Depth 2 evaluates the best legal
    next shot after each of the strongest first-shot candidates. If
    pocket_indices is supplied, it restricts only the first shot; the
    continuation always checks all six pockets. This is a two-ply tactical
    planner, not a full game-tree search.
    """
    if depth not in (1, 2):
        raise ValueError("depth must be 1 or 2")
    if not 0.0 <= discount <= 1.0:
        raise ValueError("discount must be in [0, 1]")
    if beam_width < 1:
        raise ValueError("beam_width must be positive")
    balls = list(object_balls)
    if not balls:
        return None

    selected = _validate_pockets(pocket_indices)
    if depth == 1:
        plan = plan_nine_ball_shot(
            cue, balls, break_shot=False,
            pocket_indices=None if pocket_indices is None else selected,
            allow_safety=allow_safety,
        )
        if plan is None:
            return None
        return LookaheadPlan(
            plan=plan,
            total_score=plan.score,
            next_score=None,
            planned_pockets=(
                () if plan.shot_type.startswith("safety") else (plan.pocket_index,)
            ),
            candidate_count=1,
            future_candidate_count=0,
        )

    roots = _candidate_plans(cue, balls, selected)
    if not roots:
        if not allow_safety:
            return None
        safety = plan_nine_ball_shot(
            cue, balls, break_shot=False, allow_safety=True
        )
        if safety is None:
            return None
        return LookaheadPlan(
            plan=safety,
            total_score=safety.score,
            next_score=None,
            planned_pockets=(),
            candidate_count=0,
            future_candidate_count=0,
        )

    ranked_roots = sorted(roots, key=lambda candidate: candidate.score, reverse=True)
    beam_ids = {id(candidate) for candidate in ranked_roots[:beam_width]}
    scored = []
    total_future_candidates = 0

    for candidate in roots:
        next_score = None
        future_pocket = None
        total_score = candidate.score
        if id(candidate) in beam_ids and not candidate.assessment.won:
            next_state = _next_state(candidate, cue, balls)
            if next_state is None:
                next_score = -25.0
            else:
                next_cue, next_balls = next_state
                future = _candidate_plans(
                    next_cue, next_balls, list(range(len(POCKETS)))
                )
                total_future_candidates += len(future)
                if future:
                    best_future = max(future, key=lambda item: item.score)
                    next_score = best_future.score
                    future_pocket = best_future.pocket_index
                else:
                    safety_followup = plan_nine_ball_shot(
                        next_cue, next_balls, allow_safety=True
                    )
                    next_score = (
                        safety_followup.score if safety_followup is not None else -25.0
                    )
            total_score += discount * next_score

        path = (candidate.pocket_index,)
        if future_pocket is not None:
            path += (future_pocket,)
        scored.append(LookaheadPlan(
            plan=candidate,
            total_score=total_score,
            next_score=next_score,
            planned_pockets=path,
            candidate_count=len(roots),
            future_candidate_count=total_future_candidates,
        ))

    return max(scored, key=lambda item: item.total_score)
