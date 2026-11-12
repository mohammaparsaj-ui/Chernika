"""Generate supervised shot demonstrations from the physics-scored teacher."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import numpy as np

from .env import ChernikaSingleBallEnv
from .teacher import plan_teacher_shot


def generate(states: int, seed: int) -> dict[str, np.ndarray | dict]:
    env = ChernikaSingleBallEnv()
    observations, actions, pockets = [], [], []
    scores, successes, first_contacts = [], [], []
    skipped = 0

    for index in range(states):
        obs, _ = env.reset(seed=seed + index)
        assert env.cue is not None and env.target is not None
        plan = plan_teacher_shot(env.cue, env.target)
        if plan is None:
            skipped += 1
            continue
        observations.append(obs)
        actions.append(plan.action)
        pockets.append(plan.pocket_index)
        scores.append(plan.score)
        successes.append(plan.result.pocketed(env.target.number))
        first_contacts.append(plan.result.first_contact_number == env.target.number)

    if not observations:
        raise RuntimeError("Teacher produced no usable demonstrations")

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
            "teacher_pocket_rate": float(np.mean(successes)),
            "teacher_first_contact_rate": float(np.mean(first_contacts)),
            "action_definition": ["angle_offset_over_pi", "normalized_power_minus_one_to_one"],
        },
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--states", type=int, default=4000)
    parser.add_argument("--seed", type=int, default=40000)
    parser.add_argument("--output", default="artifacts/teacher_dataset.npz")
    args = parser.parse_args()

    dataset = generate(args.states, args.seed)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata = dataset.pop("metadata")
    np.savez_compressed(output, **dataset)
    output.with_suffix(".json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata, indent=2))
    print(f"Saved demonstrations to {output}")


if __name__ == "__main__":
    main()
