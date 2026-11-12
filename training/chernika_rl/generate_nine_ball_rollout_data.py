"""Generate imitation demonstrations from complete, rule-aware nine-ball matches."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .nine_ball_rollout import run_rack


def generate(
    racks: int,
    seed: int,
    max_shots: int = 24,
    depth: int = 2,
) -> dict[str, np.ndarray | dict]:
    """Generate pot-selection labels from teacher-vs-teacher complete racks.

    Both players use the same physics/lookahead teacher. The shared match
    controller handles legal turn handoffs, Plato break reracks, behind-string
    break-foul placement, ordinary ball-in-hand and #1-apex 9-ball respotting.
    Only non-safety pocket attempts
    become six-class imitation labels; safety actions are executed in the
    simulated match but deliberately excluded from pocket classification.
    """
    if racks < 1 or max_shots < 2:
        raise ValueError("racks must be positive and max_shots must be at least 2")
    if depth not in (1, 2):
        raise ValueError("depth must be 1 or 2")

    observations: list[list[float]] = []
    actions: list[list[float]] = []
    pockets: list[int] = []
    groups: list[int] = []
    scores: list[float] = []
    successes: list[bool] = []
    first_contacts: list[bool] = []

    legal_breaks = break_fouls = break_wins = 0
    safety_actions = total_post_break_actions = legal_turn_handoffs = 0
    rack_wins = rack_fouls = no_plan_states = 0
    ball_in_hand_placements = nine_respots = reracks = 0
    racks_with_demonstrations = 0
    states_per_rack: list[int] = []
    status_counts: dict[str, int] = {}

    for index in range(racks):
        rack = run_rack(
            seed + index,
            strategy="teacher_lookahead",
            opponent_strategy="teacher_lookahead",
            max_shots=max_shots,
            depth=depth,
        )
        status = str(rack["status"])
        status_counts[status] = status_counts.get(status, 0) + 1
        rack_wins += int(status == "win")
        reracks += int(rack.get("reracks", 0))
        rack_fouls += int(rack.get("total_fouls", 0) > 0)
        no_plan_states += int(status == "no_legal_plan")
        ball_in_hand_placements += int(rack.get("ball_in_hand_placements", 0))
        nine_respots += int(rack.get("nine_respots", 0))
        trajectory = rack.get("trajectory", [])
        break_records = [shot for shot in trajectory if shot.get("break")]
        legal_breaks += int(any(bool(shot.get("legal", False)) for shot in break_records))
        break_fouls += sum(not bool(shot.get("legal", False)) for shot in break_records)
        break_wins += int(
            status == "win"
            and any(9 in shot.get("pocketed_numbers", []) for shot in break_records)
        )

        rack_demo_count = 0
        for shot in trajectory:
            if shot.get("break"):
                continue

            total_post_break_actions += 1
            if shot.get("legal") and not shot.get("pocketed_numbers"):
                legal_turn_handoffs += 1

            shot_type = str(shot.get("shot_type", ""))
            if shot_type.startswith("safety"):
                safety_actions += 1
                continue
            if not shot.get("legal", False):
                continue

            pocket_index = int(shot.get("pocket_index", -1))
            state = shot.get("observation")
            action = shot.get("action")
            if pocket_index < 0 or pocket_index >= 6 or state is None or action is None:
                continue
            if len(state) != 52 or len(action) != 2:
                raise ValueError(
                    f"invalid teacher record dimensions in rack {seed + index}: "
                    f"state={len(state) if state is not None else None}, "
                    f"action={len(action) if action is not None else None}"
                )

            observations.append([float(value) for value in state])
            actions.append([float(value) for value in action])
            pockets.append(pocket_index)
            groups.append(index)
            scores.append(float(shot.get("lookahead_score") or shot.get("immediate_score", 0.0)))
            successes.append(bool(shot.get("pocketed_numbers")))
            first_contacts.append(
                shot.get("first_contact") == shot.get("legal_ball")
            )
            rack_demo_count += 1

        states_per_rack.append(rack_demo_count)
        racks_with_demonstrations += int(rack_demo_count > 0)

    if not observations:
        raise RuntimeError(
            "No post-break pot demonstrations were generated from complete racks; "
            "inspect break legality, teacher shot coverage and match termination."
        )

    metadata = {
        "requested_racks": racks,
        "usable_demonstrations": len(observations),
        "racks_with_demonstrations": racks_with_demonstrations,
        "legal_breaks": legal_breaks,
        "break_fouls": break_fouls,
        "break_wins": break_wins,
        "rack_9ball_wins": rack_wins,
        "reracks": reracks,
        "racks_with_any_foul": rack_fouls,
        "states_without_any_legal_plan": no_plan_states,
        "safety_actions_executed_but_excluded": safety_actions,
        "total_post_break_actions": total_post_break_actions,
        "legal_no_pot_turn_handoffs": legal_turn_handoffs,
        "ball_in_hand_placements": ball_in_hand_placements,
        "nine_ball_respots": nine_respots,
        "status_counts": status_counts,
        "mean_pot_demonstrations_per_rack": float(np.mean(states_per_rack)),
        "teacher_pot_demonstration_rate": (
            len(observations) / max(1, total_post_break_actions)
        ),
        "pot_success_rate_on_labeled_samples": float(np.mean(successes)),
        "teacher_first_contact_rate": float(np.mean(first_contacts)),
        "observation_size": 52,
        "seed_start": seed,
        "max_shots": max_shots,
        "teacher_lookahead_depth": depth,
        "state_distribution": (
            "complete seeded 1-2-3-2-1 diamond racks generated by the shared "
            "match controller with teacher-vs-teacher play; legal misses pass "
            "the turn, ordinary fouls trigger anywhere ball-in-hand, break "
            "fouls use Plato's rerack/head-string branches, and a fouled 9 is "
            "respotted at the original #1 apex"
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
    print(f"Saved complete-rack demonstrations to {output}")


if __name__ == "__main__":
    main()
