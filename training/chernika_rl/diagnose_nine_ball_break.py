"""Diagnose why candidate nine-ball breaks fail the four-ball rail rule."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import numpy as np

from .nine_ball_env import ChernikaNineBallEnv
from .nine_ball_rules import assess_nine_ball_shot
from .physics import simulate


def diagnose(
    racks: int = 20,
    seed: int = 91000,
    angle_offsets: tuple[float, ...] | None = None,
    powers: tuple[float, ...] | None = None,
) -> dict:
    if racks < 1:
        raise ValueError("racks must be positive")
    offsets = angle_offsets or tuple(float(x) for x in np.linspace(-0.20, 0.20, 9))
    power_values = powers or (60.0, 70.0, 80.0, 90.0, 97.8)
    if not offsets or not power_values:
        raise ValueError("angle_offsets and powers must not be empty")

    env = ChernikaNineBallEnv()
    per_rack = []
    for index in range(racks):
        rack_seed = seed + index
        _, _ = env.reset(seed=rack_seed)
        cue = env.cue
        balls = list(env.object_balls.values())
        if cue is None or 1 not in env.object_balls:
            raise RuntimeError("seeded rack is missing cue ball or ball 1")
        target = env.object_balls[1]
        direct = float(np.arctan2(target.y - cue.y, target.x - cue.x))
        candidates = []

        for offset in offsets:
            for power in power_values:
                result = simulate([cue, *balls], direct + float(offset) * np.pi, power)
                assessment = assess_nine_ball_shot(result, 1, break_shot=True)
                correct_contact = result.first_contact_number == 1
                safe = not result.cue_scratch and not result.any_ball_off_table
                candidates.append({
                    "angle_offset": float(offset),
                    "power_percent": float(power),
                    "first_contact": result.first_contact_number,
                    "correct_first_contact": correct_contact,
                    "safe": safe,
                    "legal": assessment.legal_shot,
                    "foul_reason": assessment.foul_reason,
                    "pocketed_numbers": list(assessment.pocketed_numbers),
                    "spread": len(result.post_contact_cushion_ball_numbers),
                    "rail_ball_numbers": list(result.post_contact_cushion_ball_numbers),
                    "ball_collisions": result.ball_collisions,
                    "cushion_contacts": result.cushion_contacts,
                    "simulated_seconds": result.simulated_seconds,
                    "scratch": result.cue_scratch,
                    "off_table": result.any_ball_off_table,
                })

        valid = [
            row for row in candidates
            if row["correct_first_contact"] and row["safe"]
        ]
        legal = [row for row in candidates if row["legal"]]
        best_valid = max(
            valid,
            key=lambda row: (
                bool(row["pocketed_numbers"]),
                row["spread"],
                -abs(row["angle_offset"]),
                row["power_percent"],
            ),
            default=None,
        )
        best_legal = max(
            legal,
            key=lambda row: (
                bool(row["pocketed_numbers"]),
                row["spread"],
                -abs(row["angle_offset"]),
                row["power_percent"],
            ),
            default=None,
        )
        per_rack.append({
            "seed": rack_seed,
            "candidate_count": len(candidates),
            "correct_first_contact_candidates": len(valid),
            "legal_candidates": len(legal),
            "best_valid_contact_candidate": best_valid,
            "best_legal_candidate": best_legal,
        })

    best_valid_rows = [
        row["best_valid_contact_candidate"]
        for row in per_rack
        if row["best_valid_contact_candidate"] is not None
    ]
    best_legal_rows = [
        row["best_legal_candidate"]
        for row in per_rack
        if row["best_legal_candidate"] is not None
    ]
    spread_histogram = Counter(
        str(row["spread"]) for row in best_valid_rows
    )
    return {
        "benchmark": "nine-ball break candidate diagnostic",
        "seed_start": seed,
        "racks": racks,
        "angle_offsets": list(offsets),
        "powers": list(power_values),
        "racks_with_correct_first_contact": len(best_valid_rows),
        "racks_with_legal_break_candidate": len(best_legal_rows),
        "legal_break_candidate_rate": len(best_legal_rows) / racks,
        "mean_best_valid_contact_spread": (
            float(np.mean([row["spread"] for row in best_valid_rows]))
            if best_valid_rows else 0.0
        ),
        "max_best_valid_contact_spread": (
            max(row["spread"] for row in best_valid_rows)
            if best_valid_rows else 0
        ),
        "mean_best_valid_contact_collisions": (
            float(np.mean([row["ball_collisions"] for row in best_valid_rows]))
            if best_valid_rows else 0.0
        ),
        "max_best_valid_contact_collisions": (
            max(row["ball_collisions"] for row in best_valid_rows)
            if best_valid_rows else 0
        ),
        "best_valid_contact_spread_histogram": dict(sorted(spread_histogram.items())),
        "best_valid_contact_pocket_rate": (
            sum(bool(row["pocketed_numbers"]) for row in best_valid_rows)
            / max(1, len(best_valid_rows))
        ),
        "racks_without_legal_candidate": [
            row["seed"] for row in per_rack if row["legal_candidates"] == 0
        ],
        "per_rack": per_rack,
        "note": (
            "Diagnostic grid only. It does not change the physics model or "
            "declare an illegal break to be legal."
        ),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--racks", type=int, default=20)
    parser.add_argument("--seed", type=int, default=91000)
    parser.add_argument("--output", default=None)
    args = parser.parse_args()
    report = diagnose(args.racks, args.seed)
    rendered = json.dumps(report, indent=2)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered)
    print(rendered)


if __name__ == "__main__":
    main()
