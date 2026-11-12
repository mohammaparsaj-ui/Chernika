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
from .nine_ball_rules import assess_nine_ball_shot, break_requirement_met

OBSERVATION_SIZE = 52
MAX_SHOTS_DEFAULT = 24
# Frame-derived Plato rack coordinates from 1000323339.mp4 (611x1024).
RACK_CENTER_X = 306.5
RACK_CENTER_Y = 314.5
NINE_BALL_RESPOT_X = 306.5
NINE_BALL_RESPOT_Y = 354.5  # original #1 apex position, not the #9 centre

# Provisional head-string estimate: one quarter of the playable length from
# the head rail. The exact white-line pixel coordinate has not been measured.
HEAD_STRING_Y = TABLE_BOTTOM - TABLE_HEIGHT_PX * 0.25

# Slot order and orientation match DiamondRackVisionDetector:
# 1 / 2 / 3 / 2 / 1, with the #1 apex closest to the cue ball.
RACK_SLOTS = (
    (0.0, -40.0),
    (-12.0, -20.0),
    (12.0, -20.0),
    (-23.0, 0.0),
    (0.0, 0.0),
    (23.0, 0.0),
    (-12.0, 20.0),
    (12.0, 20.0),
    (0.0, 40.0),
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

    By default this is a single-agent practice environment and fouls terminate
    the episode. The complete-rack evaluator may opt into recoverable-foul mode;
    opponent turns and match-level decisions remain in the rollout controller.
    """

    metadata = {"render_modes": []}

    def __init__(
        self,
        max_shots: int = MAX_SHOTS_DEFAULT,
        *,
        continue_after_foul: bool = False,
    ):
        super().__init__()
        if max_shots < 1:
            raise ValueError("max_shots must be positive")
        self.max_shots = int(max_shots)
        # The default Gym task remains terminal on a foul. The complete-match
        # evaluator opts in and handles ball-in-hand at the match layer.
        self.continue_after_foul = bool(continue_after_foul)
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
        center_x = RACK_CENTER_X
        center_y = RACK_CENTER_Y
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


    def is_legal_cue_position(
        self,
        x: float,
        y: float,
        *,
        behind_head_string: bool = False,
    ) -> bool:
        """Check on-cloth placement; touching an object ball is allowed."""
        radius = BALL_RADIUS_PX
        if not (
            TABLE_LEFT + radius <= x <= TABLE_RIGHT - radius
            and TABLE_TOP + radius <= y <= TABLE_BOTTOM - radius
        ):
            return False
        if behind_head_string and y < HEAD_STRING_Y + 0.5:
            return False
        for ball in self.object_balls.values():
            # Plato permits the cue ball to touch an object ball; only overlap
            # is prohibited. The small tolerance absorbs pixel rounding.
            minimum = radius + ball.radius - 0.25
            if math.hypot(x - ball.x, y - ball.y) < minimum:
                return False
        return True

    def prepare_ball_in_hand(self) -> None:
        """Remove the cue ball from play while the incoming player places it."""
        self.cue = None
        self._last_obs = None

    def place_cue_ball(
        self,
        x: float,
        y: float,
        *,
        behind_head_string: bool = False,
    ) -> np.ndarray:
        """Place the cue ball legally and return the refreshed 52-value state."""
        if not self.is_legal_cue_position(
            float(x), float(y), behind_head_string=behind_head_string
        ):
            region = "behind the head string" if behind_head_string else "on the table"
            raise ValueError(f"cue-ball placement is not legal {region}")
        self.cue = Ball(float(x), float(y), 0, cue=True)
        self._last_obs = self._make_observation()
        return self._last_obs.copy()

    def rerack_for_break(self) -> np.ndarray:
        """Rebuild the diamond after an illegal no-pot break; keep shot count."""
        self.object_balls = self._rack_balls()
        center_x = (TABLE_LEFT + TABLE_RIGHT) * 0.5
        cue_x = center_x + float(self.np_random.uniform(-20.0, 20.0))
        cue_y = TABLE_BOTTOM - 100.0
        self.cue = Ball(cue_x, cue_y, 0, cue=True)
        self.break_pending = True
        self._last_obs = self._make_observation()
        return self._last_obs.copy()

    def respot_nine_ball(self) -> tuple[float, float] | None:
        """Respot a missing 9 at Plato's original #1 apex position."""
        if 9 in self.object_balls:
            return None

        spot_x, spot_y = NINE_BALL_RESPOT_X, NINE_BALL_RESPOT_Y
        radius = BALL_RADIUS_PX

        def clear(x: float, y: float) -> bool:
            if not (
                TABLE_LEFT + radius <= x <= TABLE_RIGHT - radius
                and TABLE_TOP + radius <= y <= TABLE_BOTTOM - radius
            ):
                return False
            for ball in self.object_balls.values():
                if math.hypot(x - ball.x, y - ball.y) < radius + ball.radius - 0.25:
                    return False
            return True

        chosen: tuple[float, float] | None = None
        if clear(spot_x, spot_y):
            chosen = (spot_x, spot_y)
        else:
            # Plato places the 9 immediately beside the blocking ball, as
            # close as possible to the original #1 spot. Search the whole
            # playable cloth and choose the nearest non-overlapping pixel;
            # do not force the respot onto the centre line.
            best_distance = float("inf")
            for yi in range(
                int(TABLE_TOP + radius),
                int(TABLE_BOTTOM - radius) + 1,
            ):
                for xi in range(
                    int(TABLE_LEFT + radius),
                    int(TABLE_RIGHT - radius) + 1,
                ):
                    distance = math.hypot(xi - spot_x, yi - spot_y)
                    if distance >= best_distance:
                        continue
                    if clear(float(xi), float(yi)):
                        best_distance = distance
                        chosen = (float(xi), float(yi))

        if chosen is None:
            raise RuntimeError("no legal position is available to respot the 9-ball")

        self.object_balls[9] = Ball(chosen[0], chosen[1], 9, cue=False)
        if self.cue is not None:
            self._last_obs = self._make_observation()
        return chosen

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

        previous_observation = (
            self._last_obs.copy() if self._last_obs is not None else None
        )
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
        if self.continue_after_foul and not assessment.legal_shot:
            # The cue ball is now in the incoming player's hand, even if it
            # did not scratch. The match controller will place it after any
            # required 9-ball respot.
            self.cue = None
        elif cue_outcome is not None:
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
        if (
            (break_before and simulation.first_contact_number is not None)
            or (
                not break_before
                and simulation.first_contact_number == legal_ball
            )
        ):
            reward += 0.50
        if legal_shot:
            reward += 1.0 * len(pocketed_numbers)
            if won:
                reward += 10.0
        else:
            reward -= 5.0

        if self.cue is None:
            # No policy consumes this interim observation: the match controller
            # must place the cue ball before the next decision.
            if previous_observation is None:
                raise RuntimeError("missing pre-shot observation during ball-in-hand")
            self._last_obs = previous_observation
        else:
            self._last_obs = self._make_observation()
        terminated = bool(
            won
            or (not legal_shot and not self.continue_after_foul)
            or (not self.object_balls and legal_shot)
        )
        truncated = bool(self.shot_count >= self.max_shots and not terminated)
        info = {
            "legal_ball": legal_ball,
            "first_contact": simulation.first_contact_number,
            "pocketed_numbers": pocketed_numbers,
            "remaining_balls": sorted(self.object_balls),
            "off_table_numbers": sorted(
                outcome.number for outcome in simulation.outcomes
                if not outcome.cue and outcome.off_table
            ),
            "legal_shot": legal_shot,
            "foul": not legal_shot,
            "foul_reason": foul_reason,
            "break_shot": break_before,
            "post_contact_cushion_contacts": simulation.post_contact_cushion_contacts,
            "post_contact_cushion_ball_numbers": list(
                simulation.post_contact_cushion_ball_numbers
            ),
            "post_contact_object_ball_cushion_contacts": (
                simulation.post_contact_object_ball_cushion_contacts
            ),
            "cue_pre_contact_cushion_contacts": (
                simulation.cue_pre_contact_cushion_contacts
            ),
            "break_requirement_met": (
                break_requirement_met(simulation) if break_before else None
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
