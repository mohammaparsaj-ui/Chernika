"""Diagnose why candidate nine-ball breaks fail the four-ball rail rule."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import numpy as np

from .nine_ball_env import ChernikaNineBallEnv
from .nine_ball_rules import assess_nine_ball_shot
from .physics import (
    Ball, TABLE_BOTTOM, TABLE_LEFT, TABLE_RIGHT, simulate,
)


def diagnose_canonical_reference() -> dict:
    """Test the exact recorded rack mapping with center and second-ball break lines."""
    env = ChernikaNineBallEnv()
    rack = [
        Ball(306.5, 274.5, 8),
        Ball(294.5, 294.5, 6),
        Ball(318.5, 294.5, 7),
        Ball(283.5, 314.5, 4),
        Ball(306.5, 314.5, 9),
        Ball(329.5, 314.5, 5),
        Ball(294.5, 334.5, 2),
        Ball(318.5, 334.5, 3),
        Ball(306.5, 354.5, 1),
    ]
    cue_y = TABLE_BOTTOM - 100.0
    cue_x_values = (
        (TABLE_LEFT + TABLE_RIGHT) * 0.5,
        306.5,
    )
    delta_angles = (-0.02, -0.01, 0.0, 0.01, 0.02)
    powers = (97.8,)
    speed_multipliers = (1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0)
    observed_positions = {
        1: (417.5, 823.5),
        2: (286.5, 479.5),
        3: (466.5, 455.5),
        4: (219.5, 261.5),
        5: (402.5, 658.5),
        7: (322.5, 277.5),
        8: (169.5, 643.0),
        9: (292.5, 305.5),
    }
    candidates = []

    for cue_x in cue_x_values:
        cue = Ball(cue_x, cue_y, 0, cue=True)
        env.reset(seed=1, options={"balls": rack, "cue": cue})
        target_one = env.object_balls[1]
        direct = float(np.arctan2(target_one.y - cue.y, target_one.x - cue.x))
        target_modes = (
            ("apex_center", target_one),
            ("second_row_left_ball_2", env.object_balls[2]),
            ("second_row_right_ball_3", env.object_balls[3]),
        )
        for mode, aim_target in target_modes:
            aim_angle = float(np.arctan2(
                aim_target.y - cue.y, aim_target.x - cue.x
            ))
            for delta in delta_angles:
                angle = aim_angle + delta
                action_offset = float((angle - direct + np.pi) % (2.0 * np.pi) - np.pi)
                for power in powers:
                    for speed_multiplier in speed_multipliers:
                        result = simulate(
                            [cue, *sorted(rack, key=lambda ball: ball.number)],
                            angle, power,
                            cue_speed_multiplier=speed_multiplier,
                        )
                        assessment = assess_nine_ball_shot(
                            result, 1, break_shot=True
                        )
                        outcomes_by_number = {
                            outcome.number: outcome
                            for outcome in result.outcomes
                            if not outcome.cue
                        }
                        cue_outcome = next(
                            (outcome for outcome in result.outcomes if outcome.cue),
                            None,
                        )
                        position_errors = []
                        missing_visible = []
                        for number, observed in observed_positions.items():
                            outcome = outcomes_by_number.get(number)
                            if outcome is None or outcome.pocketed or outcome.off_table:
                                missing_visible.append(number)
                            else:
                                position_errors.append(
                                    (outcome.x - observed[0]) ** 2
                                    + (outcome.y - observed[1]) ** 2
                                )
                        cue_missing = (
                            cue_outcome is None
                            or cue_outcome.pocketed
                            or cue_outcome.off_table
                        )
                        if not cue_missing:
                            position_errors.append(
                                (cue_outcome.x - 155.5) ** 2
                                + (cue_outcome.y - 272.5) ** 2
                            )
                        position_rmse = (
                            float(np.sqrt(np.mean(position_errors)))
                            if position_errors else 999.0
                        )
                        fit_score = (
                            position_rmse
                            + 250.0 * len(missing_visible)
                            + 250.0 * int(cue_missing)
                            + 100.0 * abs(len(assessment.pocketed_numbers) - 2)
                            + 200.0 * int(not assessment.legal_shot)
                            + 500.0 * int(result.first_contact_number != 1)
                        )
                        candidates.append({
                            "cue": [cue_x, cue_y],
                            "aim_mode": mode,
                            "aim_target_number": aim_target.number,
                            "delta_angle_radians": delta,
                            "action_angle_offset": action_offset / np.pi,
                            "power_percent": power,
                            "cue_speed_multiplier": speed_multiplier,
                            "first_contact": result.first_contact_number,
                            "correct_first_contact": result.first_contact_number == 1,
                            "legal": assessment.legal_shot,
                            "foul_reason": assessment.foul_reason,
                            "pocketed_numbers": list(assessment.pocketed_numbers),
                            "spread": len(result.post_contact_cushion_ball_numbers),
                            "rail_ball_numbers": list(result.post_contact_cushion_ball_numbers),
                            "ball_collisions": result.ball_collisions,
                            "cushion_contacts": result.cushion_contacts,
                            "simulated_seconds": result.simulated_seconds,
                            "position_rmse": position_rmse,
                            "missing_visible_balls": missing_visible,
                            "cue_missing": cue_missing,
                            "fit_score": fit_score,
                            "scratch": result.cue_scratch,
                            "off_table": result.any_ball_off_table,
                        })

    legal = [row for row in candidates if row["legal"]]
    valid = [
        row for row in candidates
        if row["correct_first_contact"] and not row["scratch"] and not row["off_table"]
    ]
    best = max(
        valid,
        key=lambda row: (
            bool(row["pocketed_numbers"]),
            row["spread"],
            row["ball_collisions"],
        ),
        default=None,
    )
    best_fit = sorted(candidates, key=lambda row: row["fit_score"])[:10]
    return {
        "rack_source": "frame-derived 1000323339.mp4 initial rack mapping",
        "initial_ball_positions": {
            str(ball.number): [ball.x, ball.y] for ball in rack
        },
        "cue_position_source": "centered current simulator default; exact video cue start was not retained",
        "cue_y": cue_y,
        "candidate_count": len(candidates),
        "legal_candidate_count": len(legal),
        "legal_candidate_rate": len(legal) / max(1, len(candidates)),
        "correct_first_contact_candidate_count": len(valid),
        "best_valid_candidate": best,
        "best_fit_candidates": best_fit,
        "speed_multipliers_tested": list(speed_multipliers),
        "candidates": candidates,
        "observed_reference": {
            "break_pocketed_ball_count": 2,
            "post_break_cue": [155.5, 272.5],
            "post_break_visible_balls": {
                "1": [417.5, 823.5],
                "2": [286.5, 479.5],
                "3": [466.5, 455.5],
                "4": [219.5, 261.5],
                "5": [402.5, 658.5],
                "7": [322.5, 277.5],
                "8": [169.5, 643.0],
                "9": [292.5, 305.5],
            },
            "note": "Ball 6 was not visible; exact cue start, shot angle and pocketed-ball identities were not retained.",
        },
    }


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
        balls = sorted(env.object_balls.values(), key=lambda ball: ball.number)
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
        "canonical_reference": diagnose_canonical_reference(),
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
