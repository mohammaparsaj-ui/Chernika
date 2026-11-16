"""Convert manually reviewed Plato screen recordings into Chernika samples.

This module never treats OCR or computer-vision output as ground truth by
itself. Coordinates and power-slider positions must be reviewed before a
sample can be promoted into a training dataset.
"""
from __future__ import annotations

import math
import re
from typing import Mapping, Sequence

import numpy as np

from .physics import (
    MAX_UI_POWER_PERCENT,
    POCKETS,
    TABLE_BOTTOM,
    TABLE_LEFT,
    TABLE_RIGHT,
    TABLE_TOP,
)

# Screen-pixel bounds measured from the 720x1216 recording layout.
# These map the playable cloth rectangle to the canonical physics table.
SCREEN_TABLE_LEFT = 143.0
SCREEN_TABLE_TOP = 195.0
SCREEN_TABLE_RIGHT = 580.0
SCREEN_TABLE_BOTTOM = 1075.0

# Calibrated vertical power-slider marker endpoints from the supplied Plato UI.
POWER_MARKER_MAX_Y = 603.0
POWER_MARKER_MIN_Y = 1010.0

OBSERVATION_SIZE = 52
ACTION_SIZE = 2


def screen_to_physics(point: Sequence[float]) -> tuple[float, float]:
    """Map a screen-pixel ball centre into canonical physics coordinates."""
    if len(point) != 2:
        raise ValueError("point must contain x and y")
    x, y = (float(point[0]), float(point[1]))
    if not math.isfinite(x) or not math.isfinite(y):
        raise ValueError("point coordinates must be finite")
    if not (
        SCREEN_TABLE_LEFT <= x <= SCREEN_TABLE_RIGHT
        and SCREEN_TABLE_TOP <= y <= SCREEN_TABLE_BOTTOM
    ):
        raise ValueError("point is outside the calibrated playable table")

    mapped_x = TABLE_LEFT + (
        (x - SCREEN_TABLE_LEFT)
        * (TABLE_RIGHT - TABLE_LEFT)
        / (SCREEN_TABLE_RIGHT - SCREEN_TABLE_LEFT)
    )
    mapped_y = TABLE_TOP + (
        (y - SCREEN_TABLE_TOP)
        * (TABLE_BOTTOM - TABLE_TOP)
        / (SCREEN_TABLE_BOTTOM - SCREEN_TABLE_TOP)
    )
    return mapped_x, mapped_y


def power_marker_to_percent(marker_y: float) -> float:
    """Convert a reviewed vertical slider marker into the calibrated UI power."""
    y = float(marker_y)
    if not math.isfinite(y):
        raise ValueError("power marker must be finite")
    if not POWER_MARKER_MAX_Y <= y <= POWER_MARKER_MIN_Y:
        raise ValueError("power marker is outside the calibrated slider")
    fraction = (
        (POWER_MARKER_MIN_Y - y)
        / (POWER_MARKER_MIN_Y - POWER_MARKER_MAX_Y)
    )
    return float(np.clip(fraction * MAX_UI_POWER_PERCENT, 0.0, MAX_UI_POWER_PERCENT))


def _normalize_x(x: float) -> float:
    return (x - TABLE_LEFT) / (TABLE_RIGHT - TABLE_LEFT)


def _normalize_y(y: float) -> float:
    return (y - TABLE_TOP) / (TABLE_BOTTOM - TABLE_TOP)


def encode_reviewed_observation(
    cue_screen: Sequence[float],
    object_balls_screen: Mapping[int, Sequence[float]],
    *,
    break_pending: bool = False,
    shot_count: int = 0,
    max_shots: int = 24,
) -> np.ndarray:
    """Encode a reviewed screen state using the existing 52-value RL schema."""
    if not object_balls_screen:
        raise ValueError("at least one object ball is required")
    if any(number not in range(1, 10) for number in object_balls_screen):
        raise ValueError("object-ball keys must be numbered 1 through 9")
    if len(object_balls_screen) != len(set(object_balls_screen)):
        raise ValueError("object-ball numbers must be unique")
    if max_shots < 1 or shot_count < 0:
        raise ValueError("shot_count and max_shots are invalid")

    cue_x, cue_y = screen_to_physics(cue_screen)
    values: list[float] = [_normalize_x(cue_x), _normalize_y(cue_y)]

    mapped_balls: dict[int, tuple[float, float]] = {}
    for number in range(1, 10):
        point = object_balls_screen.get(number)
        if point is None:
            values.extend((-0.05, -0.05, 0.0))
            continue
        x, y = screen_to_physics(point)
        mapped_balls[number] = (x, y)
        values.extend((_normalize_x(x), _normalize_y(y), 1.0))

    legal_ball = min(mapped_balls)
    values.extend(1.0 if number == legal_ball else 0.0 for number in range(1, 10))
    for pocket in POCKETS:
        values.extend((_normalize_x(pocket.x), _normalize_y(pocket.y)))
    values.extend((
        1.0 if break_pending else 0.0,
        min(1.0, shot_count / max_shots),
    ))

    observation = np.asarray(values, dtype=np.float32)
    if observation.shape != (OBSERVATION_SIZE,):
        raise AssertionError("reviewed observation does not match the 52-value schema")
    return observation


def derive_reviewed_action(
    cue_screen: Sequence[float],
    legal_ball_screen: Sequence[float],
    aim_marker_screen: Sequence[float],
    power_marker_y: float,
) -> dict[str, float | list[float]]:
    """Derive the existing [angle-offset, power] action from reviewed UI marks.

    The angle offset is relative to the direct cue-to-legal-ball line, matching
    ChernikaNineBallEnv.step(). The aim marker is the projected cue-ball
    contact point shown by Plato's aiming guide.
    """
    cue_x, cue_y = screen_to_physics(cue_screen)
    ball_x, ball_y = screen_to_physics(legal_ball_screen)
    aim_x, aim_y = screen_to_physics(aim_marker_screen)

    direct_angle = math.atan2(ball_y - cue_y, ball_x - cue_x)
    aim_angle = math.atan2(aim_y - cue_y, aim_x - cue_x)
    delta = math.atan2(
        math.sin(aim_angle - direct_angle),
        math.cos(aim_angle - direct_angle),
    )
    angle_offset = float(np.clip(delta / math.pi, -1.0, 1.0))

    power_percent = power_marker_to_percent(power_marker_y)
    power_normalized = float(
        np.clip(2.0 * power_percent / MAX_UI_POWER_PERCENT - 1.0, -1.0, 1.0)
    )
    return {
        "direct_angle_radians": direct_angle,
        "aim_angle_radians": aim_angle,
        "angle_offset_normalized": angle_offset,
        "power_percent": power_percent,
        "power_normalized": power_normalized,
        "action": [angle_offset, power_normalized],
    }


def parse_target_announcement(text: str) -> int | None:
    """Read a weak target-ball OCR label; return None when uncertain."""
    match = re.search(r"(?:hit\s+the|pocket\s+the)\s*([1-9])\s*[- ]?ball", text or "", re.I)
    return int(match.group(1)) if match else None


def parse_pocket_announcement(text: str) -> list[int] | None:
    """Read a weak pocket OCR label; break counts remain number-unknown."""
    value = (text or "").strip()
    match = re.search(r"you\s+pocketed\s+the\s*([1-9])\s*[- ]?ball", value, re.I)
    if match:
        return [int(match.group(1))]
    count = re.search(r"you\s+pocketed\s+([1-9])\s+balls?", value, re.I)
    if count:
        return []
    return None
