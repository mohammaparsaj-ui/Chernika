"""Generate supervised multi-ball 9-ball shots from the physics teacher."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .nine_ball_env import ChernikaNineBallEnv
from .nine_ball_data import sample_random_state
from .nine_ball_teacher import plan_nine_ball_shot


def generate(states: int, seed: int) -> dict[str, np.ndarray | dict]:
    env = ChernikaNineBallEnv()
    observations, actions, pockets = [], [], []
    scores, successes, first_contacts = [], [], []
    skipped = 0
    safety_actions = 0
    legal_plans = 0

    for index in range(states):
        state_seed = seed + index
        cue, balls = sample_random_state(state_seed)
        observation, _ = env.reset(
            seed=state_seed,
            options={"cue": cue, "balls": balls, "break_pending": False},
        )
        plan = plan_nine_ball_shot(cue, balls, break_shot=False)
        if plan is None:
            skipped += 1
            continue
        legal_plans += 1
        if plan.shot_type.startswith("safety"):
            safety_actions += 1
            skipped += 1
            continue

        observations.append(observation)
        actions.append(plan.action)
        pockets.append(plan.pocket_index)
        scores.append(plan.score)
        successes.append(bool(plan.assessment.pocketed_numbers))
        first_contacts.append(plan.result.first_contact_number == plan.legal_ball)

    if not observations:
        raise RuntimeError("Multi-ball teacher produced no usable demonstrations")

    return {
        "observations": np.asarray(observations, dtype=np.float32),
        "actions": np.asarray(actions, dtype=np.float32),
        "pockets": np.asarray(pockets, dtype=np.int64),
        "scores": np.asarray(scores, dtype=np.float32),
        "successes": np.asarray(successes, dtype=np.bool_),
        "first_contacts": np.asarray(first_contacts, dtype=np.bool_),
        "metadata": {
            "requested_states": states,
            "usable_demonstrations": len(observations),
            "skipped_states": skipped,
            "seed_start": seed,
            "teacher_legal_plan_rate": legal_plans / max(1, states),
            "teacher_pot_demonstration_rate": len(observations) / max(1, states),
            "teacher_pot_rate": len(observations) / max(1, legal_plans),
            "pot_success_rate_on_labeled_samples": float(np.mean(successes)),
            "safety_actions_excluded_from_pocket_imitation": safety_actions,
            "teacher_first_contact_rate": float(np.mean(first_contacts)),
            "observation_size": 52,
            "state_distribution": (
                "randomly dispersed stationary 3-9 object balls; ball 9 remains present; "
                "not break-derived and not extracted from gameplay recordings"
            ),
        },
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--states", type=int, default=700)
    parser.add_argument("--seed", type=int, default=70000)
    parser.add_argument(
        "--output", default="artifacts/nine_ball_teacher_dataset.npz"
    )
    args = parser.parse_args()

    dataset = generate(args.states, args.seed)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata = dataset.pop("metadata")
    np.savez_compressed(output, **dataset)
    output.with_suffix(".json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata, indent=2))
    print(f"Saved multi-ball demonstrations to {output}")


if __name__ == "__main__":
    main()
