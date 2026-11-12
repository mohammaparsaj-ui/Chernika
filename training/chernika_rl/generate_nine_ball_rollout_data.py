"""Generate post-break demonstrations from teacher-driven complete-rack rollouts."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .nine_ball_env import ChernikaNineBallEnv
from .nine_ball_planner import plan_nine_ball_shot_lookahead
from .nine_ball_teacher import plan_nine_ball_shot


def generate(
    racks: int,
    seed: int,
    max_shots: int = 24,
    depth: int = 2,
) -> dict[str, np.ndarray | dict]:
    if racks < 1 or max_shots < 2:
        raise ValueError("racks must be positive and max_shots must be at least 2")
    if depth not in (1, 2):
        raise ValueError("depth must be 1 or 2")

    env = ChernikaNineBallEnv(max_shots=max_shots)
    observations, actions, pockets, groups = [], [], [], []
    scores, successes, first_contacts = [], [], []
    legal_breaks = break_fouls = break_wins = no_plan_states = 0
    safety_actions = total_post_break_actions = turn_handoffs = 0
    rack_wins = rack_fouls = 0
    states_per_rack = []

    for index in range(racks):
        rack_seed = seed + index
        observation, _ = env.reset(seed=rack_seed)
        break_plan = plan_nine_ball_shot(
            env.cue, list(env.object_balls.values()), break_shot=True
        )
        if break_plan is None:
            states_per_rack.append(0)
            continue

        observation, _, terminated, truncated, break_info = env.step(
            break_plan.action
        )
        if break_info["foul"]:
            break_fouls += 1
            rack_fouls += 1
            states_per_rack.append(0)
            continue
        legal_breaks += 1
        if not break_info["pocketed_numbers"]:
            turn_handoffs += 1
        if break_info["win"]:
            break_wins += 1
            rack_wins += 1
            states_per_rack.append(0)
            continue
        if terminated or truncated:
            states_per_rack.append(0)
            continue

        rack_states = 0
        while not terminated and not truncated and env.shot_count < max_shots:
            plan_result = plan_nine_ball_shot_lookahead(
                env.cue, list(env.object_balls.values()), depth=depth
            )
            if plan_result is None:
                no_plan_states += 1
                break

            plan = plan_result.plan
            total_post_break_actions += 1
            if plan.shot_type.startswith("safety"):
                safety_actions += 1
            else:
                observations.append(observation.copy())
                actions.append(plan.action)
                pockets.append(plan.pocket_index)
                groups.append(index)
                scores.append(plan_result.total_score)
                successes.append(bool(plan.assessment.pocketed_numbers))
                first_contacts.append(
                    plan.result.first_contact_number == plan.legal_ball
                )
                rack_states += 1

            observation, _, terminated, truncated, info = env.step(plan.action)
            if info["legal_shot"] and not info["pocketed_numbers"]:
                turn_handoffs += 1
            if info["foul"]:
                rack_fouls += 1
                break
            if info["win"]:
                rack_wins += 1
                break
        states_per_rack.append(rack_states)

    if not observations:
        raise RuntimeError(
            "No post-break teacher demonstrations were generated; "
            "inspect legal break and direct-shot availability"
        )

    metadata = {
        "requested_racks": racks,
        "usable_demonstrations": len(observations),
        "racks_with_demonstrations": int(len(np.unique(groups))),
        "legal_breaks": legal_breaks,
        "break_fouls": break_fouls,
        "break_wins": break_wins,
        "rack_wins": rack_wins,
        "rack_fouls": rack_fouls,
        "states_without_any_legal_plan": no_plan_states,
        "safety_actions_excluded_from_pocket_imitation": safety_actions,
        "total_post_break_actions": total_post_break_actions,
        "legal_turn_handoffs": turn_handoffs,
        "mean_pot_demonstrations_per_rack": float(np.mean(states_per_rack)),
        "teacher_pot_demonstration_rate": (
            len(observations) / max(1, total_post_break_actions)
        ),
        "seed_start": seed,
        "max_shots": max_shots,
        "teacher_lookahead_depth": depth,
        "teacher_pot_rate": len(observations) / max(1, total_post_break_actions),
        "pot_success_rate_on_labeled_samples": float(np.mean(successes)),
        "teacher_first_contact_rate": float(np.mean(first_contacts)),
        "observation_size": 52,
        "state_distribution": (
            "post-break states reached by legal physics-teacher breaks and "
            "teacher self-play with legal no-pot turn handoffs on seeded "
            "1-2-3-2-1 diamond racks; safety actions are executed but excluded "
            "from the six-pocket imitation labels"
        ),
    }
    return {
        "observations": np.asarray(observations, dtype=np.float32),
        "actions": np.asarray(actions, dtype=np.float32),
        "pockets": np.asarray(pockets, dtype=np.int64),
        "groups": np.asarray(groups, dtype=np.int64),
        "scores": np.asarray(scores, dtype=np.float32),
        "successes": np.asarray(successes, dtype=np.bool_),
        "first_contacts": np.asarray(first_contacts, dtype=np.bool_),
        "metadata": metadata,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--racks", type=int, default=100)
    parser.add_argument("--seed", type=int, default=93000)
    parser.add_argument("--max-shots", type=int, default=24)
    parser.add_argument("--depth", type=int, default=2)
    parser.add_argument("--output", default="artifacts/nine_ball_rollout_dataset.npz")
    args = parser.parse_args()

    dataset = generate(
        args.racks, args.seed, max_shots=args.max_shots, depth=args.depth
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata = dataset.pop("metadata")
    np.savez_compressed(output, **dataset)
    output.with_suffix(".json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata, indent=2))
    print(f"Saved post-break rollout demonstrations to {output}")


if __name__ == "__main__":
    main()
