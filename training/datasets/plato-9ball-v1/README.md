# Chernika Plato 9-ball dataset v1

Derived annotations from six unique local recordings: five manually played gameplay recordings and one power-bar calibration recording. These are human demonstrations, not autonomous Chernika results.

Raw MP4 files are deliberately excluded from this public repository. The manifest contains SHA-256 checksums and recording metadata so local videos can be matched.

Files:
- recordings.json — recording metadata, provenance, split and quality.
- shots.jsonl — 11 shot/partial-shot samples with task-specific eligibility flags.
- events.jsonl — HUD, pocket, foul, scratch, turn and win events.
- power-calibration.json — user-confirmed maximum-power mapping and legacy interval.
- geometry-reference.json — prior table bounds and six pocket centers.
- validate_dataset.py — structural and leakage checks.

Splits are grouped by recording:
- training_gold: 1000323339.mp4 (four reviewed examples).
- training_outcome_only: 1000323257.mp4 (four outcomes; first-contact unverified).
- training_calibration: XRecorder_20261030_01.mp4.
- validation_state_transitions: 1000323251.mp4.
- validation_long_match: 1000323259.mp4.
- calibration_only: XRecorder_20261107_02.mp4.

The duplicate XRecorder_20261030_01(1).mp4 is excluded.

Do not infer first contact from a pocket banner alone. Do not fit power from values outside the legacy interval until recalibrated. Keep unknown coordinates, routes, spin and timestamps null. This dataset is not yet sufficient to claim robust reinforcement learning; the next step is reproducible ball-track/collision extraction and held-out offline benchmarking.
