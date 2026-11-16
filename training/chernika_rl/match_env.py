"""Gymnasium match environment for Chernika's Plato-style 9-ball RL.

The learning agent controls every shot for player 0. Player 1 is a configurable
physics-teacher opponent. The existing 52-value observation and two-value
(angle-offset, power) action interface are preserved so trained shot models
remain compatible with the current Android observation encoder.
"""
from __future__ import annotations

from typing import Any

import gymnasium as gym
import numpy as np

from .nine_ball_env import ChernikaNineBallEnv
from .nine_ball_planner import plan_nine_ball_shot_lookahead
from .nine_ball_teacher import plan_nine_ball_shot
from .nine_ball_rollout import _choose_ball_in_hand_position


class ChernikaMatchEnv(gym.Env):
    """One complete 9-ball rack: PPO agent versus a physics-teacher opponent.

    The agent always breaks in this first curriculum stage. Legal pots retain
    the turn; legal misses pass it. Ordinary fouls award ball-in-hand anywhere.
    Plato's illegal no-pot break is reracked and passes the break. A break
    scratch with a pocketed ball uses behind-head-string ball-in-hand. A fouled
    9 is respotted by the existing Plato-specific environment implementation.

    This is deliberately a match-level environment, not a single-shot proxy.
    The opponent continues shooting after legal pots until the agent's turn
    returns or the rack ends.
    """

    metadata = {"render_modes": []}

    def __init__(
        self,
        max_shots: int = 48,
        *,
        opponent_strategy: str = "teacher_greedy",
        opponent_depth: int = 2,
        max_opponent_actions_per_turn: int = 18,
        reward_scale: float = 1.0,
        opponent_policy: Any | None = None,
    ) -> None:
        super().__init__()
        if max_shots < 1:
            raise ValueError("max_shots must be positive")
        if opponent_strategy not in ("teacher_greedy", "teacher_lookahead"):
            raise ValueError("opponent_strategy must be teacher_greedy or teacher_lookahead")
        if opponent_depth not in (1, 2):
            raise ValueError("opponent_depth must be 1 or 2")
        if max_opponent_actions_per_turn < 1:
            raise ValueError("max_opponent_actions_per_turn must be positive")
        if reward_scale <= 0:
            raise ValueError("reward_scale must be positive")

        self.max_shots = int(max_shots)
        self.opponent_strategy = opponent_strategy
        self.opponent_depth = int(opponent_depth)
        self.max_opponent_actions_per_turn = int(max_opponent_actions_per_turn)
        self.reward_scale = float(reward_scale)
        if opponent_policy is not None and not hasattr(opponent_policy, "predict"):
            raise TypeError("opponent_policy must expose predict(observation, deterministic=True)")
        self.opponent_policy = opponent_policy

        self.game = ChernikaNineBallEnv(
            max_shots=self.max_shots,
            continue_after_foul=True,
        )
        self.action_space = self.game.action_space
        self.observation_space = self.game.observation_space

        self._observation: np.ndarray | None = None
        self._done = False
        self._truncated = False
        self._winner: int | None = None
        self._status = "not_started"
        self._agent_shots = 0
        self._opponent_shots = 0
        self._current_player = 0

    def reset(
        self,
        *,
        seed: int | None = None,
        options: dict[str, Any] | None = None,
    ) -> tuple[np.ndarray, dict[str, Any]]:
        super().reset(seed=seed)
        self._observation, reset_info = self.game.reset(seed=seed, options=options)
        self._done = False
        self._truncated = False
        self._winner = None
        self._status = "in_progress"
        self._agent_shots = 0
        self._opponent_shots = 0
        self._current_player = 0
        return self._observation.copy(), {
            **reset_info,
            "agent_player": 0,
            "opponent_player": 1,
            "agent_breaks": True,
            "opponent_strategy": self.opponent_strategy,
            "opponent_policy": self.opponent_policy is not None,
            "observation_size": int(self.observation_space.shape[0]),
            "action_size": int(self.action_space.shape[0]),
        }

    def step(
        self, action: np.ndarray
    ) -> tuple[np.ndarray, float, bool, bool, dict[str, Any]]:
        if self._observation is None:
            raise RuntimeError("reset() must be called before step()")
        if self._done:
            raise RuntimeError("step() called after the rack ended; call reset()")
        values = np.asarray(action, dtype=np.float32).reshape(-1)
        if values.shape != (2,) or not np.all(np.isfinite(values)):
            raise ValueError("action must contain two finite values: angle offset and power")
        if not self.action_space.contains(values):
            raise ValueError("action values must be within [-1, 1]")

        observation, _, terminated, truncated, shot_info = self.game.step(values)
        self._observation = observation
        self._agent_shots += 1
        reward = self._agent_shot_reward(shot_info)
        trajectory: list[dict[str, Any]] = [self._trajectory_row("agent", shot_info, values)]

        if shot_info.get("win", False):
            self._finish(winner=0, status="agent_win")
        elif shot_info.get("foul", False):
            recovery = self._recover_foul(incoming_player=1, shot_info=shot_info)
            trajectory[-1]["recovery"] = recovery
            self._current_player = 1
            if not truncated:
                opponent_reward, opponent_rows = self._advance_opponent_until_agent()
                reward += opponent_reward
                trajectory.extend(opponent_rows)
        elif terminated:
            self._finish(winner=None, status="rack_ended_without_win")
        elif truncated:
            self._finish(winner=None, status="max_shots", truncated=True)
        elif shot_info.get("pocketed_numbers"):
            # A legal pot retains the agent's turn.
            self._current_player = 0
        else:
            self._current_player = 1
            opponent_reward, opponent_rows = self._advance_opponent_until_agent()
            reward += opponent_reward
            trajectory.extend(opponent_rows)

        if self.game.shot_count >= self.max_shots and not self._done:
            self._finish(winner=None, status="max_shots", truncated=True)

        info = {
            "status": self._status,
            "winner": self._winner,
            "agent_shots": self._agent_shots,
            "opponent_shots": self._opponent_shots,
            "total_shots": self.game.shot_count,
            "remaining_balls": sorted(self.game.object_balls),
            "lowest_ball": self.game.lowest_ball,
            "break_pending": self.game.break_pending,
            "current_player": self._current_player,
            "trajectory": trajectory,
            "agent_shot": shot_info,
        }
        return (
            self._observation.copy(),
            float(reward * self.reward_scale),
            self._done and not self._truncated,
            self._truncated,
            info,
        )

    def _agent_shot_reward(self, info: dict[str, Any]) -> float:
        if info.get("foul", False):
            penalty = -1.25
            if info.get("cue_scratch", False):
                penalty -= 0.25
            if info.get("off_table", False):
                penalty -= 0.25
            return penalty
        reward = 0.05
        if not info.get("break_shot", False) and info.get("first_contact") == info.get("legal_ball"):
            reward += 0.15
        reward += 0.45 * len(info.get("pocketed_numbers", []))
        if info.get("win", False):
            reward += 10.0
        return reward

    def _opponent_shot_reward(self, info: dict[str, Any]) -> float:
        if info.get("win", False):
            return -10.0
        if info.get("foul", False):
            return 0.25
        return -0.20 * len(info.get("pocketed_numbers", []))

    def _recover_foul(
        self,
        *,
        incoming_player: int,
        shot_info: dict[str, Any],
    ) -> dict[str, Any]:
        """Apply Plato's break/ordinary foul branches and place ball-in-hand."""
        break_shot = bool(shot_info.get("break_shot", False))
        pocketed = list(shot_info.get("pocketed_numbers", []))
        break_requirement_met = bool(shot_info.get("break_requirement_met", False))

        self.game.prepare_ball_in_hand()
        respot = self.game.respot_nine_ball()

        if break_shot and not pocketed and not break_requirement_met:
            self._observation = self.game.rerack_for_break()
            self._current_player = incoming_player
            return {
                "kind": "illegal_no_pot_break_rerack",
                "incoming_player": incoming_player,
                "nine_respot_position": respot,
                "next_breaker": incoming_player,
            }

        behind_head_string = break_shot
        self._observation, position, score = _choose_ball_in_hand_position(
            self.game,
            "teacher_greedy",
            None,
            3,
            behind_head_string=behind_head_string,
        )
        self._current_player = incoming_player
        return {
            "kind": "break_ball_in_hand_behind_head_string" if break_shot else "ordinary_ball_in_hand",
            "incoming_player": incoming_player,
            "cue_position": position,
            "placement_teacher_score": score,
            "nine_respot_position": respot,
        }

    def _opponent_action(self) -> tuple[np.ndarray | None, Any | None]:
        if self.opponent_policy is not None:
            action, _ = self.opponent_policy.predict(self._observation, deterministic=True)
            values = np.asarray(action, dtype=np.float32).reshape(-1)
            if values.shape != (2,) or not np.all(np.isfinite(values)):
                raise ValueError("opponent policy returned an invalid two-value action")
            return np.clip(values, self.action_space.low, self.action_space.high), None

        cue = self.game.cue
        if cue is None:
            raise RuntimeError("opponent has no cue ball; foul recovery was not completed")
        balls = list(self.game.object_balls.values())
        if self.game.break_pending:
            plan = plan_nine_ball_shot(cue, balls, break_shot=True)
            return (None, None) if plan is None else (plan.action, plan)
        if self.opponent_strategy == "teacher_lookahead":
            result = plan_nine_ball_shot_lookahead(
                cue, balls, depth=self.opponent_depth, allow_safety=True
            )
            return (None, None) if result is None else (result.plan.action, result.plan)
        plan = plan_nine_ball_shot(cue, balls, break_shot=False, allow_safety=True)
        return (None, None) if plan is None else (plan.action, plan)

    def _advance_opponent_until_agent(self) -> tuple[float, list[dict[str, Any]]]:
        """Simulate opponent actions until the agent gets the table back."""
        reward = 0.0
        rows: list[dict[str, Any]] = []
        for _ in range(self.max_opponent_actions_per_turn):
            action, _plan = self._opponent_action()
            if action is None:
                self._finish(winner=None, status="opponent_no_legal_plan", truncated=True)
                break

            observation, _, terminated, truncated, info = self.game.step(action)
            self._observation = observation
            self._opponent_shots += 1
            reward += self._opponent_shot_reward(info)
            rows.append(self._trajectory_row("opponent", info, action))

            if info.get("win", False):
                self._finish(winner=1, status="opponent_win")
                break
            if info.get("foul", False):
                recovery = self._recover_foul(incoming_player=0, shot_info=info)
                rows[-1]["recovery"] = recovery
                self._current_player = 0
                break
            if terminated:
                self._finish(winner=None, status="rack_ended_without_win")
                break
            if truncated or self.game.shot_count >= self.max_shots:
                self._finish(winner=None, status="max_shots", truncated=True)
                break
            if not info.get("pocketed_numbers"):
                self._current_player = 0
                break
            # A legal pot retains the opponent's turn; continue the rack.
        else:
            self._finish(winner=None, status="opponent_turn_guard", truncated=True)
        return reward, rows

    @staticmethod
    def _trajectory_row(
        player: str,
        info: dict[str, Any],
        action: np.ndarray,
    ) -> dict[str, Any]:
        return {
            "player": player,
            "break": bool(info.get("break_shot", False)),
            "legal_ball": info.get("legal_ball"),
            "first_contact": info.get("first_contact"),
            "legal": bool(info.get("legal_shot", False)),
            "foul_reason": info.get("foul_reason"),
            "pocketed_numbers": list(info.get("pocketed_numbers", [])),
            "remaining_balls": list(info.get("remaining_balls", [])),
            "action": np.asarray(action, dtype=np.float32).tolist(),
            "power_percent": info.get("power_percent"),
            "angle_radians": info.get("angle_radians"),
            "cue_scratch": bool(info.get("cue_scratch", False)),
            "off_table": bool(info.get("off_table", False)),
        }

    def _finish(
        self,
        *,
        winner: int | None,
        status: str,
        truncated: bool = False,
    ) -> None:
        self._done = True
        self._truncated = bool(truncated)
        self._winner = winner
        self._status = status

    def render(self):
        return None

    def close(self) -> None:
        self.game.close() if hasattr(self.game, "close") else None
