"""Replay a manually reviewed real-game sample against the Python physics mirror.

The report is diagnostic only. A mismatch never silently changes a label or
promotes a human recording into the teacher-training set.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

import numpy as np

from .physics import Ball, MAX_UI_POWER_PERCENT, simulate
from .nine_ball_rules import assess_nine_ball_shot
from .recording_review import derive_reviewed_action, screen_to_physics


def evaluate_manifest(manifest: dict[str, Any], *, power_step: float = 2.5) -> dict[str, Any]:
    if manifest.get("review", {}).get("training_eligible") is not False:
        raise ValueError("diagnostic manifest must remain training-ineligible")
    if power_step <= 0.0:
        raise ValueError("power_step must be positive")

    rows = []
    for shot in manifest.get("shots", []):
        if shot.get("kind") != "post_break":
            continue
        cue_screen = shot["cue_screen_px"]
        balls_screen = {
            int(number): point
            for number, point in shot["object_balls_screen_px"].items()
        }
        legal_ball = min(balls_screen)
        if shot.get("target_announcement") != legal_ball:
            raise ValueError(
                f"shot {shot['shot_index']} target announcement does not match lowest ball"
            )
        cue_xy = screen_to_physics(cue_screen)
        cue = Ball(cue_xy[0], cue_xy[1], 0, cue=True)
        balls = []
        for number, point in sorted(balls_screen.items()):
            x, y = screen_to_physics(point)
            balls.append(Ball(x, y, number))

        action = derive_reviewed_action(
            cue_screen,
            balls_screen[legal_ball],
            shot["aim_marker_screen_px"],
            shot["power_marker_y_px"],
        )
        direct_angle = math.atan2(
            balls_screen[legal_ball][1] - cue_screen[1],
            balls_screen[legal_ball][0] - cue_screen[0],
        )
        # Use the calibrated physics-space angle returned by the converter.
        physics_direct = action["direct_angle_radians"]
        aim_angle = physics_direct + action["angle_offset_normalized"] * math.pi
        estimated_power = float(action["power_percent"])

        def run(power: float) -> dict[str, Any]:
            simulation = simulate([cue, *balls], aim_angle, power)
            assessment = assess_nine_ball_shot(simulation, legal_ball, False)
            return {
                "first_contact": simulation.first_contact_number,
                "pocketed_numbers": list(assessment.pocketed_numbers),
                "legal": assessment.legal_shot,
                "foul_reason": assessment.foul_reason,
                "cue_scratch": simulation.cue_scratch,
                "off_table": simulation.any_ball_off_table,
            }

        estimated = run(estimated_power)
        expected = sorted(int(n) for n in shot["pocketed_numbers"])
        matching_powers = []
        grid = np.arange(max(0.5, power_step), MAX_UI_POWER_PERCENT + 1e-6, power_step)
        for candidate_power in grid:
            trial = run(float(candidate_power))
            if trial["legal"] and trial["pocketed_numbers"] == expected:
                matching_powers.append(round(float(candidate_power), 2))

        rows.append({
            "shot_index": shot["shot_index"],
            "target_ball": legal_ball,
            "expected_pocketed_numbers": expected,
            "estimated_action": action["action"],
            "estimated_power_percent": round(estimated_power, 2),
            "estimated_power_outcome": estimated,
            "estimated_power_matches_observed": (
                estimated["legal"] and estimated["pocketed_numbers"] == expected
            ),
            "estimated_first_contact_matches_target": (
                estimated["first_contact"] == legal_ball
            ),
            "matching_power_candidates": matching_powers,
            "matching_power_range": (
                [min(matching_powers), max(matching_powers)]
                if matching_powers else None
            ),
        })

    matches = sum(row["estimated_power_matches_observed"] for row in rows)
    contact_matches = sum(row["estimated_first_contact_matches_target"] for row in rows)
    return {
        "schema": "chernika.recording_replay_diagnostic.v1",
        "recording_id": manifest.get("recording_id"),
        "diagnostic_only": True,
        "training_eligible": False,
        "post_break_shots": len(rows),
        "estimated_power_exact_outcome_matches": matches,
        "estimated_power_outcome_match_rate": matches / len(rows) if rows else 0.0,
        "first_contact_matches": contact_matches,
        "first_contact_match_rate": contact_matches / len(rows) if rows else 0.0,
        "rows": rows,
        "limitations": [
            "This is a single user-provided rack, not a statistically meaningful benchmark.",
            "The screen-to-physics transform and power-slider mapping remain provisional.",
            "A matching pocket set does not prove exact trajectory or spin parity.",
            "The raw recording is not included in the repository.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", default="artifacts/recording_replay_diagnostic.json")
    parser.add_argument("--power-step", type=float, default=2.5)
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text())
    report = evaluate_manifest(manifest, power_step=args.power_step)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
