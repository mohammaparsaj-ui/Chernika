"""Complete-rack nine-ball rollouts for teacher and hybrid policies."""
from __future__ import annotations

from typing import Any
import math
import numpy as np

from .nine_ball_break import BreakPolicy
from .nine_ball_env import ChernikaNineBallEnv
from .physics import (
    BREAK_CUE_SPEED_MULTIPLIER, BALL_RADIUS_PX, POCKETS,
    TABLE_LEFT, TABLE_TOP, TABLE_RIGHT, TABLE_BOTTOM,
)
from .nine_ball_planner import plan_nine_ball_shot_lookahead
from .nine_ball_teacher import NineBallPlan, plan_nine_ball_shot
from .imitation import ImitationPolicy, predict_top_pockets


STRATEGIES = ("teacher_greedy", "teacher_lookahead", "hybrid_top3_lookahead")


def _choose_plan(
    strategy: str,
    env: ChernikaNineBallEnv,
    observation: np.ndarray,
    policy: ImitationPolicy | None,
    depth: int,
    top_k: int,
) -> tuple[NineBallPlan | None, dict[str, Any]]:
    cue = env.cue
    if cue is None:
        raise RuntimeError("environment cue ball is not initialized")
    balls = list(env.object_balls.values())

    if strategy == "teacher_greedy":
        return plan_nine_ball_shot(cue, balls), {
            "lookahead_score": None,
            "planned_pockets": [],
            "nodes_evaluated": 1,
            "neural_fallback": False,
        }

    if strategy == "teacher_lookahead":
        result = plan_nine_ball_shot_lookahead(
            cue, balls, depth=depth
        )
        if result is None:
            return None, {}
        return result.plan, {
            "lookahead_score": result.total_score,
            "planned_pockets": list(result.planned_pockets),
            "nodes_evaluated": (
                result.candidate_count + result.future_candidate_count
            ),
            "neural_fallback": False,
        }

    if strategy == "hybrid_top3_lookahead":
        if policy is None:
            raise ValueError("hybrid strategy requires a trained 52-input policy")
        _, ranked_pockets = predict_top_pockets(
            policy, observation, top_k=top_k
        )
        result = plan_nine_ball_shot_lookahead(
            cue, balls, depth=depth, pocket_indices=ranked_pockets,
            allow_safety=False,
        )
        if result is not None:
            return result.plan, {
                "lookahead_score": result.total_score,
                "planned_pockets": list(result.planned_pockets),
                "nodes_evaluated": (
                    result.candidate_count + result.future_candidate_count
                ),
                "neural_fallback": False,
                "neural_pockets": ranked_pockets,
            }

        fallback = plan_nine_ball_shot_lookahead(
            cue, balls, depth=depth, allow_safety=True
        )
        if fallback is None:
            return None, {
                "neural_fallback": True,
                "neural_pockets": ranked_pockets,
            }
        return fallback.plan, {
            "lookahead_score": fallback.total_score,
            "planned_pockets": list(fallback.planned_pockets),
            "nodes_evaluated": (
                fallback.candidate_count + fallback.future_candidate_count
            ),
            "neural_fallback": True,
            "neural_pockets": ranked_pockets,
        }

    raise ValueError(f"unknown rollout strategy: {strategy}")



def _record_foul(consecutive_fouls: list[int], player: int) -> bool:
    """Increment one player's consecutive-foul count and report a rack loss."""
    consecutive_fouls[player] += 1
    return consecutive_fouls[player] >= 3


def _record_legal_shot(consecutive_fouls: list[int], player: int) -> None:
    """A legal shot clears only the shooter's own consecutive-foul streak."""
    consecutive_fouls[player] = 0


def _ball_in_hand_candidates(env: ChernikaNineBallEnv) -> list[tuple[float, float]]:
    """Small deterministic placement set: cloth grid plus six potting ghost points."""
    radius = BALL_RADIUS_PX
    center_x = (TABLE_LEFT + TABLE_RIGHT) * 0.5
    center_y = (TABLE_TOP + TABLE_BOTTOM) * 0.5
    xs = (TABLE_LEFT + radius + 4.0, center_x, TABLE_RIGHT - radius - 4.0)
    ys = (TABLE_TOP + radius + 4.0, center_y, TABLE_BOTTOM - radius - 4.0)
    positions: dict[tuple[float, float], None] = {
        (round(x, 2), round(y, 2)): None for x in xs for y in ys
    }

    target = env.object_balls.get(env.lowest_ball)
    if target is not None:
        for pocket in POCKETS:
            dx, dy = pocket.x - target.x, pocket.y - target.y
            distance = math.hypot(dx, dy)
            if distance < 1e-6:
                continue
            ux, uy = dx / distance, dy / distance
            ghost = (
                target.x - ux * (2.0 * radius + 1.0),
                target.y - uy * (2.0 * radius + 1.0),
            )
            positions[(round(ghost[0], 2), round(ghost[1], 2))] = None
    return list(positions)


def _choose_ball_in_hand_position(
    env: ChernikaNineBallEnv,
    strategy: str,
    policy: ImitationPolicy | None,
    top_k: int,
) -> tuple[np.ndarray, tuple[float, float], float | None]:
    """Choose a legal cue placement by scoring the best immediate legal shot."""
    best_position: tuple[float, float] | None = None
    best_score = float("-inf")
    first_legal: tuple[float, float] | None = None

    for x, y in _ball_in_hand_candidates(env):
        if not env.is_legal_cue_position(x, y):
            continue
        observation = env.place_cue_ball(x, y)
        if first_legal is None:
            first_legal = (x, y)
        plan, planning = _choose_plan(
            strategy, env, observation, policy, depth=1, top_k=top_k
        )
        if plan is None:
            continue
        score = planning.get("lookahead_score")
        if score is None:
            score = plan.score
        score = float(score)
        if score > best_score:
            best_score = score
            best_position = (x, y)

    chosen = best_position or first_legal
    if chosen is None:
        raise RuntimeError("no legal ball-in-hand cue placement could be found")
    observation = env.place_cue_ball(*chosen)
    return observation, chosen, (best_score if best_position is not None else None)


def _next_player_after_shot(
    current_player: int,
    legal_shot: bool,
    pocketed_numbers: list[int] | tuple[int, ...],
) -> int:
    """Keep the turn after a legal pot; otherwise pass it to the opponent."""
    if not legal_shot or pocketed_numbers:
        return current_player
    return 1 - current_player


def run_rack(
    seed: int,
    *,
    strategy: str = "teacher_greedy",
    policy: ImitationPolicy | None = None,
    max_shots: int = 24,
    depth: int = 2,
    top_k: int = 3,
    opponent_strategy: str = "teacher_greedy",
) -> dict[str, Any]:
    """Play one seeded nine-ball rack with WPA-style foul recovery.

    Player 0 uses strategy and player 1 uses opponent_strategy. Legal pots
    retain the turn; legal misses pass it. Fouls pass the turn, give the
    incoming player ball-in-hand, and respot the 9-ball when required. Three
    consecutive fouls by one player lose the rack. Push-outs and stalemate
    handling are not yet modeled.
    """
    if strategy not in STRATEGIES:
        raise ValueError(f"strategy must be one of {STRATEGIES}")
    if opponent_strategy not in ("teacher_greedy", "teacher_lookahead"):
        raise ValueError("opponent_strategy must be teacher_greedy or teacher_lookahead")
    if max_shots < 1:
        raise ValueError("max_shots must be positive")
    if depth not in (1, 2):
        raise ValueError("depth must be 1 or 2")
    if not 1 <= top_k <= 6:
        raise ValueError("top_k must be in [1, 6]")

    env = ChernikaNineBallEnv(
        max_shots=max_shots, continue_after_foul=True
    )
    observation, reset_info = env.reset(seed=seed)
    if strategy == "hybrid_top3_lookahead":
        if policy is None or policy.observation_size != 52:
            raise ValueError("hybrid strategy requires a 52-input imitation policy")

    initial_balls = sorted(reset_info["remaining_balls"])
    records: list[dict[str, Any]] = []
    break_legal = False
    break_foul = False
    break_spread = 0
    break_pocketed = 0
    fallback_count = 0
    status = "no_break_plan"
    winner: int | None = None
    current_player = 0
    turn_switches = 0
    total_pocketed = 0
    total_fouls = 0
    mid_rack_fouls = 0
    ball_in_hand_placements = 0
    nine_respots = 0
    consecutive_fouls = [0, 0]
    foul_counts = [0, 0]
    safety_shots = 0

    def recover_foul(fouling_player: int) -> tuple[np.ndarray, tuple[float, float] | None, tuple[float, float] | None]:
        nonlocal current_player, turn_switches, ball_in_hand_placements, nine_respots
        foul_counts[fouling_player] += 1
        if _record_foul(consecutive_fouls, fouling_player):
            current_player = 1 - fouling_player
            return observation, None, None

        current_player = 1 - fouling_player
        turn_switches += 1
        env.prepare_ball_in_hand()
        respot = env.respot_nine_ball()
        if respot is not None:
            nine_respots += 1
        active_strategy = strategy if current_player == 0 else opponent_strategy
        active_policy = (
            policy
            if current_player == 0 and active_strategy == "hybrid_top3_lookahead"
            else None
        )
        next_observation, placement, _ = _choose_ball_in_hand_position(
            env, active_strategy, active_policy, top_k
        )
        ball_in_hand_placements += 1
        return next_observation, placement, respot

    break_plan = plan_nine_ball_shot(
        env.cue, list(env.object_balls.values()), break_shot=True
    )
    if break_plan is not None:
        cue_before = (env.cue.x, env.cue.y) if env.cue is not None else None
        observation_before = observation.copy()
        observation, reward, terminated, truncated, info = env.step(
            break_plan.action
        )
        break_legal = bool(info["legal_shot"])
        break_foul = bool(info["foul"])
        break_spread = len(info["post_contact_cushion_ball_numbers"])
        break_pocketed = len(info["pocketed_numbers"])
        total_pocketed += break_pocketed
        safety_shots += int(break_plan.shot_type.startswith("safety"))
        records.append({
            "shot": 1,
            "player": 0,
            "break": True,
            "shot_type": break_plan.shot_type,
            "legal_ball": info["legal_ball"],
            "first_contact": info["first_contact"],
            "legal": info["legal_shot"],
            "foul_reason": info["foul_reason"],
            "pocketed_numbers": info["pocketed_numbers"],
            "remaining_balls": info["remaining_balls"],
            "cue_before": cue_before,
            "cue_after": (
                (env.cue.x, env.cue.y) if env.cue is not None else None
            ),
            "observation": observation_before.tolist(),
            "action": break_plan.action.tolist(),
            "power_percent": info["power_percent"],
            "pocket_index": -1,
            "immediate_score": break_plan.score,
            "lookahead_score": None,
            "planned_pockets": [],
            "nodes_evaluated": 1,
            "neural_fallback": False,
            "reward": reward,
        })
        if info["win"]:
            winner = 0
            status = "win"
        elif info["foul"]:
            total_fouls += 1
            observation, placement, respot = recover_foul(0)
            records[-1]["ball_in_hand_for_player"] = current_player
            records[-1]["ball_in_hand_position"] = placement
            records[-1]["nine_respot_position"] = respot
            status = (
                "three_foul_loss"
                if consecutive_fouls[0] >= 3 else "in_progress"
            )
            if status == "three_foul_loss":
                winner = 1
        elif truncated:
            status = "max_shots"
        elif terminated:
            status = "rack_ended_without_win"
        else:
            _record_legal_shot(consecutive_fouls, 0)
            next_player = _next_player_after_shot(
                0, info["legal_shot"], info["pocketed_numbers"]
            )
            if next_player != 0:
                turn_switches += 1
            current_player = next_player
            status = "in_progress"

    while status == "in_progress" and env.shot_count < max_shots:
        cue_before = (env.cue.x, env.cue.y) if env.cue is not None else None
        active_strategy = strategy if current_player == 0 else opponent_strategy
        active_policy = (
            policy
            if current_player == 0 and active_strategy == "hybrid_top3_lookahead"
            else None
        )
        plan, planning = _choose_plan(
            active_strategy, env, observation, active_policy, depth, top_k
        )
        if plan is None:
            status = "no_legal_plan"
            break

        observation_before = observation.copy()
        observation, reward, terminated, truncated, info = env.step(plan.action)
        fallback_count += int(planning.get("neural_fallback", False))
        total_pocketed += len(info["pocketed_numbers"])
        safety_shots += int(plan.shot_type.startswith("safety"))
        records.append({
            "shot": info["shot_count"],
            "player": current_player,
            "break": False,
            "shot_type": plan.shot_type,
            "active_strategy": active_strategy,
            "legal_ball": info["legal_ball"],
            "first_contact": info["first_contact"],
            "legal": info["legal_shot"],
            "foul_reason": info["foul_reason"],
            "pocketed_numbers": info["pocketed_numbers"],
            "remaining_balls": info["remaining_balls"],
            "cue_before": cue_before,
            "cue_after": (
                (env.cue.x, env.cue.y) if env.cue is not None else None
            ),
            "observation": observation_before.tolist(),
            "action": plan.action.tolist(),
            "power_percent": info["power_percent"],
            "pocket_index": plan.pocket_index,
            "immediate_score": plan.score,
            "lookahead_score": planning.get("lookahead_score"),
            "planned_pockets": planning.get("planned_pockets", []),
            "neural_pockets": planning.get("neural_pockets", []),
            "nodes_evaluated": planning.get("nodes_evaluated", 1),
            "neural_fallback": planning.get("neural_fallback", False),
            "reward": reward,
        })

        if info["win"]:
            winner = current_player
            status = "win"
        elif info["foul"]:
            foul_player = current_player
            total_fouls += 1
            mid_rack_fouls += 1
            observation, placement, respot = recover_foul(foul_player)
            records[-1]["ball_in_hand_for_player"] = current_player
            records[-1]["ball_in_hand_position"] = placement
            records[-1]["nine_respot_position"] = respot
            if consecutive_fouls[foul_player] >= 3:
                winner = 1 - foul_player
                status = "three_foul_loss"
        elif terminated:
            status = "rack_ended_without_win"
        elif truncated:
            status = "max_shots"
        else:
            _record_legal_shot(consecutive_fouls, current_player)
            next_player = _next_player_after_shot(
                current_player, info["legal_shot"], info["pocketed_numbers"]
            )
            if next_player != current_player:
                turn_switches += 1
            current_player = next_player

    if status == "in_progress":
        status = "max_shots" if env.shot_count >= max_shots else "stopped"

    return {
        "seed": seed,
        "strategy": strategy,
        "opponent_strategy": opponent_strategy,
        "status": status,
        "win": winner == 0,
        "winner": winner,
        "foul": total_fouls > 0,
        "total_fouls": total_fouls,
        "mid_rack_fouls": mid_rack_fouls,
        "foul_counts_by_player": foul_counts,
        "consecutive_fouls_by_player": consecutive_fouls,
        "three_foul_loss": status == "three_foul_loss",
        "break_legal": break_legal,
        "break_foul": break_foul,
        "break_spread": break_spread,
        "break_cue_speed_multiplier": BREAK_CUE_SPEED_MULTIPLIER,
        "break_pocketed": break_pocketed,
        "shots": env.shot_count,
        "total_pocketed_balls": total_pocketed,
        "initial_balls": initial_balls,
        "remaining_balls": sorted(env.object_balls),
        "neural_fallback_count": fallback_count,
        "turn_switches": turn_switches,
        "ball_in_hand_placements": ball_in_hand_placements,
        "nine_respots": nine_respots,
        "safety_shots": safety_shots,
        "trajectory": records,
    }


def summarize_racks(rows: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(rows)
    if count == 0:
        return {"racks": 0}
    winners = [row for row in rows if row.get("winner") is not None]
    candidate_wins = sum(row.get("winner") == 0 for row in rows)
    opponent_wins = sum(row.get("winner") == 1 for row in rows)
    return {
        "racks": count,
        "rack_win_rate": candidate_wins / count,
        "candidate_win_rate": candidate_wins / count,
        "opponent_win_rate": opponent_wins / count,
        "completed_rack_rate": len(winners) / count,
        "legal_break_rate": sum(row["break_legal"] for row in rows) / count,
        "break_foul_rate": sum(row.get("break_foul", False) for row in rows) / count,
        "mid_rack_foul_rate": sum(row.get("mid_rack_fouls", 0) > 0 for row in rows) / count,
        "total_foul_rate": sum(row.get("total_fouls", 0) > 0 for row in rows) / count,
        "three_foul_loss_rate": sum(row.get("three_foul_loss", False) for row in rows) / count,
        "mean_total_fouls": float(np.mean([row.get("total_fouls", 0) for row in rows])),
        "mean_ball_in_hand_placements": float(np.mean([
            row.get("ball_in_hand_placements", 0) for row in rows
        ])),
        "mean_nine_respots": float(np.mean([row.get("nine_respots", 0) for row in rows])),
        "no_legal_plan_rate": sum(row["status"] == "no_legal_plan" for row in rows) / count,
        "max_shot_rate": sum(row["status"] == "max_shots" for row in rows) / count,
        "mean_shots": float(np.mean([row["shots"] for row in rows])),
        "mean_pocketed_balls": float(np.mean([
            row["total_pocketed_balls"] for row in rows
        ])),
        "mean_break_spread": float(np.mean([row["break_spread"] for row in rows])),
        "mean_shots_to_win": (
            float(np.mean([row["shots"] for row in winners])) if winners else None
        ),
        "mean_neural_fallbacks_per_rack": float(np.mean([
            row["neural_fallback_count"] for row in rows
        ])),
        "mean_safety_shots_per_rack": float(np.mean([
            row.get("safety_shots", 0) for row in rows
        ])),
        "mean_turn_switches": float(np.mean([
            row.get("turn_switches", 0) for row in rows
        ])),
    }


def evaluate_racks(
    episodes: int,
    seed: int,
    *,
    policy: ImitationPolicy,
    max_shots: int = 24,
    depth: int = 2,
    top_k: int = 3,
) -> dict[str, Any]:
    """Compare greedy teacher, two-ply teacher and neural top-k hybrid."""
    if episodes < 1:
        raise ValueError("episodes must be positive")
    if policy.observation_size != 52:
        raise ValueError("nine-ball rollout policy must have 52 inputs")

    results: dict[str, list[dict[str, Any]]] = {
        strategy: [] for strategy in STRATEGIES
    }
    for index in range(episodes):
        rack_seed = seed + index
        for strategy in STRATEGIES:
            results[strategy].append(run_rack(
                rack_seed,
                strategy=strategy,
                policy=policy if strategy == "hybrid_top3_lookahead" else None,
                max_shots=max_shots,
                depth=depth,
                top_k=top_k,
                opponent_strategy="teacher_greedy",
            ))

    return {
        "benchmark": "Chernika complete-rack nine-ball rollouts",
        "seed_start": seed,
        "requested_racks": episodes,
        "max_shots": max_shots,
        "lookahead_depth": depth,
        "neural_top_k": top_k,
        "results": {
            strategy: {
                "metrics": summarize_racks(rows),
                "racks": rows,
            }
            for strategy, rows in results.items()
        },
        "note": (
            "Synthetic seeded diamond racks on the current Python physics port. "
            "The break remains physics-teacher controlled. Player 0 uses the "
            "selected controller and player 1 uses the greedy physics teacher; "
            "legal no-pot shots pass the turn. Fouls pass the turn with "
            "ball-in-hand, the 9-ball is respotted when required, and three "
            "consecutive fouls lose the rack. Push-outs, stalemates and live "
            "Plato replay validation are not modeled."
        ),
    }
