"""Complete-rack nine-ball rollouts for teacher and hybrid policies."""
from __future__ import annotations

from typing import Any
import numpy as np

from .nine_ball_break import BreakPolicy
from .nine_ball_env import ChernikaNineBallEnv
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
            cue, balls, depth=depth, pocket_indices=ranked_pockets
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

        fallback = plan_nine_ball_shot_lookahead(cue, balls, depth=depth)
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


def run_rack(
    seed: int,
    *,
    strategy: str = "teacher_greedy",
    policy: ImitationPolicy | None = None,
    max_shots: int = 24,
    depth: int = 2,
    top_k: int = 3,
) -> dict[str, Any]:
    """Play one synthetic diamond rack until win, foul, no-plan or shot limit.

    The break is physics-teacher controlled for every strategy. The selected
    strategy controls all post-break shots. A foul ends this single-agent
    practice rollout; opponent turns and ball-in-hand are not simulated.
    """
    if strategy not in STRATEGIES:
        raise ValueError(f"strategy must be one of {STRATEGIES}")
    if max_shots < 1:
        raise ValueError("max_shots must be positive")
    if depth not in (1, 2):
        raise ValueError("depth must be 1 or 2")
    if not 1 <= top_k <= 6:
        raise ValueError("top_k must be in [1, 6]")

    env = ChernikaNineBallEnv(max_shots=max_shots)
    observation, reset_info = env.reset(seed=seed)
    if strategy == "hybrid_top3_lookahead":
        if policy is None or policy.observation_size != 52:
            raise ValueError("hybrid strategy requires a 52-input imitation policy")

    initial_balls = sorted(reset_info["remaining_balls"])
    records: list[dict[str, Any]] = []
    break_legal = False
    break_spread = 0
    break_pocketed = 0
    fallback_count = 0
    status = "no_break_plan"
    win = False
    foul = False
    total_pocketed = 0

    break_plan = plan_nine_ball_shot(
        env.cue, list(env.object_balls.values()), break_shot=True
    )
    if break_plan is not None:
        cue_before = (env.cue.x, env.cue.y) if env.cue is not None else None
        observation, reward, terminated, truncated, info = env.step(
            break_plan.action
        )
        break_legal = bool(info["legal_shot"])
        break_spread = len(info["post_contact_cushion_ball_numbers"])
        break_pocketed = len(info["pocketed_numbers"])
        total_pocketed += break_pocketed
        win = bool(info["win"])
        foul = bool(info["foul"])
        records.append({
            "shot": 1,
            "break": True,
            "legal_ball": info["legal_ball"],
            "first_contact": info["first_contact"],
            "legal": info["legal_shot"],
            "foul_reason": info["foul_reason"],
            "pocketed_numbers": info["pocketed_numbers"],
            "remaining_balls": info["remaining_balls"],
            "cue_before": cue_before,
            "cue_after": (env.cue.x, env.cue.y) if env.cue is not None else None,
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
        if win:
            status = "win"
        elif foul:
            status = "break_foul"
        elif truncated:
            status = "max_shots"
        elif terminated:
            status = "rack_ended_without_win"
        else:
            status = "in_progress"

    while status == "in_progress" and env.shot_count < max_shots:
        cue_before = (env.cue.x, env.cue.y) if env.cue is not None else None
        plan, planning = _choose_plan(
            strategy, env, observation, policy, depth, top_k
        )
        if plan is None:
            status = "no_legal_plan"
            break

        observation, reward, terminated, truncated, info = env.step(plan.action)
        fallback_count += int(planning.get("neural_fallback", False))
        total_pocketed += len(info["pocketed_numbers"])
        records.append({
            "shot": info["shot_count"],
            "break": False,
            "legal_ball": info["legal_ball"],
            "first_contact": info["first_contact"],
            "legal": info["legal_shot"],
            "foul_reason": info["foul_reason"],
            "pocketed_numbers": info["pocketed_numbers"],
            "remaining_balls": info["remaining_balls"],
            "cue_before": cue_before,
            "cue_after": (env.cue.x, env.cue.y) if env.cue is not None else None,
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
            win = True
            status = "win"
        elif info["foul"]:
            foul = True
            status = "foul"
        elif terminated:
            status = "rack_ended_without_win"
        elif truncated:
            status = "max_shots"

    if status == "in_progress":
        status = "max_shots" if env.shot_count >= max_shots else "stopped"

    return {
        "seed": seed,
        "strategy": strategy,
        "status": status,
        "win": win,
        "foul": foul,
        "break_legal": break_legal,
        "break_spread": break_spread,
        "break_pocketed": break_pocketed,
        "shots": env.shot_count,
        "total_pocketed_balls": total_pocketed,
        "initial_balls": initial_balls,
        "remaining_balls": sorted(env.object_balls),
        "neural_fallback_count": fallback_count,
        "trajectory": records,
    }


def summarize_racks(rows: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(rows)
    if count == 0:
        return {"racks": 0}
    winners = [row for row in rows if row["win"]]
    return {
        "racks": count,
        "rack_win_rate": sum(row["win"] for row in rows) / count,
        "legal_break_rate": sum(row["break_legal"] for row in rows) / count,
        "break_foul_rate": sum(row["status"] == "break_foul" for row in rows) / count,
        "mid_rack_foul_rate": sum(
            row["foul"] and row["status"] == "foul" for row in rows
        ) / count,
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
            "The break remains physics-teacher controlled. A foul ends the "
            "single-agent practice rollout; opponent turns, ball-in-hand, "
            "9-ball respotting and live Plato replay validation are not modeled."
        ),
    }
