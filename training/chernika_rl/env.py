"""Gymnasium environment for learning single-ball aim and power control."""
from __future__ import annotations

import math
import numpy as np
import gymnasium as gym
from gymnasium import spaces

from .physics import (
    Ball, POCKETS, BALL_RADIUS_PX, TABLE_LEFT, TABLE_TOP, TABLE_RIGHT,
    TABLE_BOTTOM, TABLE_WIDTH_PX, TABLE_HEIGHT_PX, TABLE_WIDTH_METERS,
    TABLE_LENGTH_METERS, ROLLING_RESISTANCE, GRAVITY, MAX_CUE_SPEED_MPS,
    MAX_UI_POWER_PERCENT, simulate, wrap_angle,
)

OBSERVATION_SIZE = 16  # cue xy + target xy + six pocket xy pairs


def _norm_xy(x: float, y: float) -> tuple[float, float]:
    return ((x - TABLE_LEFT) / TABLE_WIDTH_PX,
            (y - TABLE_TOP) / TABLE_HEIGHT_PX)


def _nearest_pocket_distance(x: float, y: float) -> float:
    return min(math.hypot(x - p.x, y - p.y) for p in POCKETS)


class ChernikaSingleBallEnv(gym.Env):
    """One-shot episodic task: pocket one target ball without scratching.

    Action[0] is an angle offset relative to the cue-to-target direction,
    scaled to [-pi, pi]. Action[1] maps linearly to 0..97.8% Plato power.
    Each episode contains one shot, matching the first stage of the Billiards-RL
    curriculum while using Chernika's measured table and simulator constants.
    """
    metadata = {"render_modes": []}

    def __init__(self):
        super().__init__()
        self.action_space = spaces.Box(
            low=np.array([-1.0, -1.0], dtype=np.float32),
            high=np.array([1.0, 1.0], dtype=np.float32), dtype=np.float32,
        )
        # Pocket anchors sit slightly outside the playable rectangle.
        self.observation_space = spaces.Box(
            low=-0.1, high=1.1, shape=(OBSERVATION_SIZE,), dtype=np.float32,
        )
        self.cue: Ball | None = None
        self.target: Ball | None = None
        self._last_obs: np.ndarray | None = None

    def _sample_position(self) -> tuple[float, float]:
        margin = BALL_RADIUS_PX * 2.5
        for _ in range(500):
            x = float(self.np_random.uniform(TABLE_LEFT + margin, TABLE_RIGHT - margin))
            y = float(self.np_random.uniform(TABLE_TOP + margin, TABLE_BOTTOM - margin))
            if _nearest_pocket_distance(x, y) > BALL_RADIUS_PX * 2.4:
                return x, y
        return (TABLE_LEFT + TABLE_WIDTH_PX * 0.5,
                TABLE_TOP + TABLE_HEIGHT_PX * 0.5)

    def _observation(self) -> np.ndarray:
        assert self.cue is not None and self.target is not None
        values = [*_norm_xy(self.cue.x, self.cue.y),
                  *_norm_xy(self.target.x, self.target.y)]
        for pocket in POCKETS:
            values.extend(_norm_xy(pocket.x, pocket.y))
        return np.asarray(values, dtype=np.float32)

    def reset(self, *, seed: int | None = None, options: dict | None = None):
        super().reset(seed=seed)
        for _ in range(500):
            tx, ty = self._sample_position()
            cx, cy = self._sample_position()
            separation = math.hypot(tx - cx, ty - cy)
            if BALL_RADIUS_PX * 3.0 <= separation <= TABLE_WIDTH_PX * 0.95:
                break
        self.cue = Ball(cx, cy, 0, cue=True)
        self.target = Ball(tx, ty, 1, cue=False)
        self._last_obs = self._observation()
        return self._last_obs.copy(), {"cue": (cx, cy), "target": (tx, ty)}

    def step(self, action):
        if self.cue is None or self.target is None:
            raise RuntimeError("reset() must be called before step()")
        a = np.asarray(action, dtype=np.float32).reshape(2)
        a = np.clip(a, self.action_space.low, self.action_space.high)
        direct_angle = math.atan2(self.target.y - self.cue.y,
                                  self.target.x - self.cue.x)
        angle = direct_angle + float(a[0]) * math.pi
        power = (float(a[1]) + 1.0) * 0.5 * MAX_UI_POWER_PERCENT
        power = max(0.5, min(MAX_UI_POWER_PERCENT, power))

        before = _nearest_pocket_distance(self.target.x, self.target.y)
        result = simulate([self.cue, self.target], angle, power)
        after_pos = result.final_position(self.target.number)
        after = before if after_pos is None else _nearest_pocket_distance(*after_pos)
        progress = float(np.clip((before - after) / (TABLE_WIDTH_PX * 0.5), -1.0, 1.0))
        success = result.pocketed(self.target.number)

        # Dense enough to teach contact and direction; potting dominates.
        reward = -0.20 + 0.60 * progress
        if result.first_contact_number == self.target.number:
            reward += 0.60
        else:
            reward -= 0.90
        if success:
            reward += 10.0
        if result.cue_scratch:
            reward -= 8.0
        if result.any_ball_off_table:
            reward -= 5.0
        reward -= 0.025 * (power / MAX_UI_POWER_PERCENT)

        info = {
            "success": success,
            "first_contact": result.first_contact_number,
            "cue_scratch": result.cue_scratch,
            "off_table": result.any_ball_off_table,
            "power_percent": power,
            "angle_radians": angle,
            "progress": progress,
            "ball_collisions": result.ball_collisions,
            "cushion_contacts": result.cushion_contacts,
            "simulated_seconds": result.simulated_seconds,
            "final_target_position": after_pos,
        }
        # This is deliberately a one-shot task; tactical multi-shot play comes later.
        return self._last_obs.copy(), float(reward), True, False, info

    def heuristic_action(self) -> np.ndarray:
        """Physics-scored ghost-ball baseline used for fair evaluation."""
        if self.cue is None or self.target is None:
            raise RuntimeError("reset() must be called before heuristic_action()")
        direct = math.atan2(self.target.y - self.cue.y,
                            self.target.x - self.cue.x)
        pixels_per_meter = (TABLE_WIDTH_PX / TABLE_WIDTH_METERS
                            + TABLE_HEIGHT_PX / TABLE_LENGTH_METERS) * 0.5
        decel = ROLLING_RESISTANCE * GRAVITY * pixels_per_meter
        max_speed = MAX_CUE_SPEED_MPS * pixels_per_meter
        candidates: list[tuple[float, np.ndarray]] = []

        for pocket in POCKETS:
            dx, dy = pocket.x - self.target.x, pocket.y - self.target.y
            obj_dist = math.hypot(dx, dy)
            if obj_dist < 1e-6:
                continue
            ux, uy = dx / obj_dist, dy / obj_dist
            ghost_x = self.target.x - ux * (2.0 * BALL_RADIUS_PX + 1.0)
            ghost_y = self.target.y - uy * (2.0 * BALL_RADIUS_PX + 1.0)
            if not (TABLE_LEFT + BALL_RADIUS_PX <= ghost_x <= TABLE_RIGHT - BALL_RADIUS_PX
                    and TABLE_TOP + BALL_RADIUS_PX <= ghost_y <= TABLE_BOTTOM - BALL_RADIUS_PX):
                continue
            cue_dx, cue_dy = ghost_x - self.cue.x, ghost_y - self.cue.y
            cue_dist = math.hypot(cue_dx, cue_dy)
            if cue_dist < BALL_RADIUS_PX * 2.0:
                continue
            shot_angle = math.atan2(cue_dy, cue_dx)
            delta = wrap_angle(shot_angle - direct)
            # The component of cue velocity along the contact normal transfers
            # to the object ball; compensate for glancing impacts.
            contact_cos = math.cos(wrap_angle(shot_angle - math.atan2(uy, ux)))
            if contact_cos <= 0.18:
                continue
            required_v = max(math.sqrt(max(0.0, 2.0 * decel * cue_dist)),
                             math.sqrt(max(0.0, 2.0 * decel * obj_dist)) / contact_cos)
            power = min(MAX_UI_POWER_PERCENT, max(1.0,
                    required_v / max_speed * MAX_UI_POWER_PERCENT * 1.12))
            action = np.array([delta / math.pi,
                               2.0 * power / MAX_UI_POWER_PERCENT - 1.0], dtype=np.float32)
            # Score the real simulated outcome rather than trusting geometry alone.
            result = simulate([self.cue, self.target], direct + delta, power)
            score = 100.0 if result.pocketed(self.target.number) else 0.0
            if result.first_contact_number == self.target.number:
                score += 2.0
            if result.cue_scratch:
                score -= 20.0
            if result.any_ball_off_table:
                score -= 10.0
            if score == 0.0:
                pos = result.final_position(self.target.number)
                if pos is not None:
                    score += max(-1.0, min(1.0,
                        (obj_dist - _nearest_pocket_distance(*pos)) / TABLE_WIDTH_PX))
            candidates.append((score, np.clip(action, -1.0, 1.0)))

        if not candidates:
            return np.array([0.0, -0.2], dtype=np.float32)
        return max(candidates, key=lambda item: item[0])[1]
