"""Multi-ball 9-ball practice environment using Chernika's measured Plato physics."""
from __future__ import annotations

import math
import numpy as np
import gymnasium as gym
from gymnasium import spaces

from .physics import (
    Ball, POCKETS, BALL_RADIUS_PX, TABLE_LEFT, TABLE_TOP, TABLE_RIGHT,
    TABLE_BOTTOM, TABLE_WIDTH_PX, TABLE_HEIGHT_PX, MAX_UI_POWER_PERCENT,
    BREAK_CUE_SPEED_MULTIPLIER, simulate,
)
from .nine_ball_rules import assess_nine_ball_shot

OBSERVATION_SIZE = 52
MAX_SHOTS_DEFAULT = 24
SQRT_THREE = math.sqrt(3.0)

# Slot order and orientation match DiamondRackVisionDetector:
# 1 / 2 / 3 / 2 / 1, with the #1 apex closest to the cue ball.
RACK_SLOTS = (
    (0.0, -2.0 * SQRT_THREE),
    (-1.0, -SQRT_THREE),
    (1.0, -SQRT_THREE),
    (-2.0, 0.0),
    (0.0, 0.0),
    (2.0, 0.0),
    (-1.0, SQRT_THREE),
    (1.0, SQRT_THREE),
    (0.0, 2.0 * SQRT_THREE),
)


def _normalize_x(x: float) -> float:
    return (x - TABLE_LEFT) / TABLE_WIDTH_PX


def _normalize_y(y: float) -> float:
    return (y - TABLE_TOP) / TABLE_HEIGHT_PX


class ChernikaNineBallEnv(gym.Env):
    """Sequential multi-ball 9-ball practice task.

    Actions are the same two continuous controls as the single-ball task:
    angle offset from the current lowest-numbered ball, and power. The
    observation contains the cue, all nine numbered ball slots, legal-ball
    one-hot identity, six measured pockets, break state and shot progress.

    This is a single-agent practice environment, not a complete match engine:
    it ends on a foul or a legal 9-ball win and does not simulate an opponent.
    """

    metadata = {"render_modes": []}

    def __init__(self, max_shots: int = MAX_SHOTS_DEFAULT):
        super().__init__()
        if max_shots < 1:
            raise ValueError("max_shots must be positive")
        self.max_shots = int(max_shots)
        self.action_space = spaces.Box(
            low=np.array([-1.0, -1.0], dtype=np.float32),
            high=np.array([1.0, 1.0], dtype=np.float32),
            dtype=np.float32,
        )
        self.observation_space = spaces.Box(
            low=-0.1, high=1.1, shape=(OBSERVATION_SIZE,), dtype=np.float32
        )
        self.cue: Ball | None = None
        self.object_balls: dict[int, Ball] = {}
        self.break_pending = True
        self.shot_count = 0
        self._last_obs: np.ndarray | None = None

    @property
    def lowest_ball(self) -> int:
        return min(self.object_balls) if self.object_balls else 0

    def _rack_balls(self) -> dict[int, Ball]:
        center_x = (TABLE_LEFT + TABLE_RIGHT) * 0.5
        center_y = TABLE_TOP + TABLE_HEIGHT_PX * 0.20
        shuffled = list(range(2, 9))
        self.np_random.shuffle(shuffled)
        numbers = [0] * 9
        numbers[4] = 9
        numbers[8] = 1
        remaining = iter(shuffled)
        for slot in range(9):
            if numbers[slot] == 0:
                numbers[slot] = next(remaining)

        balls: dict[int, Ball] = {}
        for slot, (offset_x, offset_y) in enumerate(RACK_SLOTS):
            number = numbers[slot]
            x = center_x + offset_x * BALL_RADIUS_PX
            y = center_y + offset_y * BALL_RADIUS_PX
            balls[number] = Ball(x, y, number, cue=False)
        return balls

    def _make_observation(self) -> np.ndarray:
        if self.cue is None:
            raise RuntimeError("cue ball is not initialized")
        values: list[float] = [
            _normalize_x(self.cue.x),
            _normalize_y(self.cue.y),
        ]
        for number in range(1, 10):
            ball = self.object_balls.get(number)
            if ball is None:
                values.extend((-0.05, -0.05, 0.0))
            else:
                values.extend((
                    _normalize_x(ball.x),
                    _normalize_y(ball.y),
                    1.0,
                ))
        legal = self.lowest_ball
        values.extend(1.0 if number == legal else 0.0 for number in range(1, 10))
        for pocket in POCKETS:
            values.extend((_normalize_x(pocket.x), _normalize_y(pocket.y)))
        values.extend((
            1.0 if self.break_pending else 0.0,
            min(1.0, self.shot_count / self.max_shots),
        ))
        observation = np.asarray(values, dtype=np.float32)
        if observation.shape != (OBSERVATION_SIZE,):
            raise AssertionError(f"Expected {OBSERVATION_SIZE} observation values")
        return observation

    def reset(self, *, seed: int | None = None, options: dict | None = None):
        super().reset(seed=seed)
        options = options or {}

        if "balls" in options:
            supplied = list(options["balls"])
            object_balls: dict[int, Ball] = {}
            for ball in supplied:
                if not isinstance(ball, Ball) or ball.cue or not 1 <= ball.number <= 9:
                    raise ValueError("custom balls must be numbered object Ball instances")
                if ball.number in object_balls:
                    raise ValueError("custom object-ball numbers must be unique")
                object_balls[ball.number] = ball
            if not object_balls:
                raise ValueError("at least one object ball is required")
            self.object_balls = object_balls
        else:
            self.object_balls = self._rack_balls()

        supplied_cue = options.get("cue")
        if supplied_cue is not None:
            if not isinstance(supplied_cue, Ball) or not supplied_cue.cue:
                raise ValueError("custom cue must be a cue Ball instance")
            self.cue = supplied_cue
        else:
            center_x = (TABLE_LEFT + TABLE_RIGHT) * 0.5
            cue_x = center_x + float(self.np_random.uniform(-20.0, 20.0))
            cue_y = TABLE_BOTTOM - 100.0
            self.cue = Ball(cue_x, cue_y, 0, cue=True)

        self.break_pending = bool(options.get("break_pending", True))
        self.shot_count = 0
        self._last_obs = self._make_observation()
        return self._last_obs.copy(), {
            "cue": (self.cue.x, self.cue.y),
            "remaining_balls": sorted(self.object_balls),
            "lowest_ball": self.lowest_ball,
            "break_pending": self.break_pending,
        }

    def step(self, action):
        if self.cue is None or not self.object_balls:
            raise RuntimeError("reset() must be called before a playable step()")

        values = np.asarray(action, dtype=np.float32).reshape(2)
        values = np.clip(values, self.action_space.low, self.action_space.high)
        legal_ball = self.lowest_ball
        target = self.object_balls[legal_ball]
        direct_angle = math.atan2(target.y - self.cue.y, target.x - self.cue.x)
        angle = direct_angle + float(values[0]) * math.pi
        power = max(
            0.5,
            min(MAX_UI_POWER_PERCENT,
                (float(values[1]) + 1.0) * 0.5 * MAX_UI_POWER_PERCENT),
        )
        break_before = self.break_pending
        original_cue = self.cue
        original_balls = dict(self.object_balls)
        simulation = simulate(
            [original_cue, *[original_balls[n] for n in sorted(original_balls)]],
            angle,
            power,
            cue_speed_multiplier=(
                BREAK_CUE_SPEED_MULTIPLIER if break_before else 1.0
            ),
        )

        assessment = assess_nine_ball_shot(simulation, legal_ball, break_before)
        pocketed_numbers = list(assessment.pocketed_numbers)
        remaining: dict[int, Ball] = {}
        for outcome in simulation.outcomes:
            if outcome.cue or outcome.pocketed or outcome.off_table:
                continue
            source = original_balls[outcome.number]
            remaining[outcome.number] = Ball(
                outcome.x, outcome.y, outcome.number, cue=False, radius=source.radius
            )
        self.object_balls = remaining

        cue_outcome = next((outcome for outcome in simulation.outcomes if outcome.cue), None)
        if cue_outcome is not None:
            self.cue = Ball(
                cue_outcome.x, cue_outcome.y, 0, cue=True,
                radius=original_cue.radius,
            )

        foul_reason = assessment.foul_reason
        legal_shot = assessment.legal_shot
        won = assessment.won
        self.break_pending = False
        self.shot_count += 1

        reward = -0.05
        if simulation.first_contact_number == legal_ball:
            reward += 0.50
        if legal_shot:
            reward += 1.0 * len(pocketed_numbers)
            if won:
                reward += 10.0
        else:
            reward -= 5.0

        self._last_obs = self._make_observation()
        terminated = bool(not legal_shot or won or not self.object_balls)
        truncated = bool(self.shot_count >= self.max_shots and not terminated)
        info = {
            "legal_ball": legal_ball,
            "first_contact": simulation.first_contact_number,
            "pocketed_numbers": pocketed_numbers,
            "remaining_balls": sorted(self.object_balls),
            "legal_shot": legal_shot,
            "foul": not legal_shot,
            "foul_reason": foul_reason,
            "break_shot": break_before,
            "post_contact_cushion_contacts": simulation.post_contact_cushion_contacts,
            "post_contact_cushion_ball_numbers": list(
                simulation.post_contact_cushion_ball_numbers
            ),
            "cue_scratch": simulation.cue_scratch,
            "off_table": simulation.any_ball_off_table,
            "success": won,
            "win": won,
            "power_percent": power,
            "angle_radians": angle,
            "shot_count": self.shot_count,
        }
        return self._last_obs.copy(), float(reward), terminated, truncated, info
