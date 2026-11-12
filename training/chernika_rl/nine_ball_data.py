"""Deterministic random stationary 9-ball states for offline teacher data."""
from __future__ import annotations

import math
import numpy as np

from .physics import (
    Ball, POCKETS, BALL_RADIUS_PX, TABLE_LEFT, TABLE_TOP,
    TABLE_RIGHT, TABLE_BOTTOM,
)


def sample_random_state(
    seed: int,
    min_balls: int = 3,
    max_balls: int = 9,
) -> tuple[Ball, list[Ball]]:
    """Sample a non-overlapping state with 3–9 object balls and the 9 present.

    These are randomized stationary training states, not break outcomes and
    not positions extracted from gameplay recordings.
    """
    if not 2 <= min_balls <= max_balls <= 9:
        raise ValueError("ball-count bounds must satisfy 2 <= min <= max <= 9")

    rng = np.random.default_rng(seed)
    count = int(rng.integers(min_balls, max_balls + 1))
    other_numbers = rng.choice(
        np.arange(1, 9), size=count - 1, replace=False
    ).tolist()
    numbers = sorted([9, *[int(number) for number in other_numbers]])

    placed: list[Ball] = []

    def sample_center() -> tuple[float, float]:
        margin = BALL_RADIUS_PX * 2.5
        for _ in range(3000):
            x = float(rng.uniform(TABLE_LEFT + margin, TABLE_RIGHT - margin))
            y = float(rng.uniform(TABLE_TOP + margin, TABLE_BOTTOM - margin))
            if any(
                math.hypot(x - pocket.x, y - pocket.y) < BALL_RADIUS_PX * 2.5
                for pocket in POCKETS
            ):
                continue
            if any(
                math.hypot(x - ball.x, y - ball.y) < BALL_RADIUS_PX * 2.1
                for ball in placed
            ):
                continue
            return x, y
        raise RuntimeError("Could not sample a collision-free ball position")

    for number in numbers:
        x, y = sample_center()
        placed.append(Ball(x, y, number, cue=False))

    cue_x, cue_y = sample_center()
    return Ball(cue_x, cue_y, 0, cue=True), sorted(
        placed, key=lambda ball: ball.number
    )
