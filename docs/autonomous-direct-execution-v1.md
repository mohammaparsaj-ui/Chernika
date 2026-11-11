# Chernika direct-execution integration

## Scope

This increment converts the instruction-first planner from dry-run reporting into a gated Android gesture adapter. It intentionally follows the spoken v1 gameplay requirements: direct routes only, center/no spin, legal first contact before potting ambition, and no automatic cue-ball relocation.

## Runtime gates

A shot can be dispatched only when all of the following are true:

1. The user has activated Chernika.
2. The active accessibility package contains 'plato'.
3. OCR identifies the user's turn ('Your Turn' or 'Chernika's turn').
4. The screen is not a replay/results screen and the HUD contains a recognized 9-ball instruction.
5. The table is stable for at least three frames.
6. All remaining object balls and the cue ball have a reconciled identity/inventory.
7. All six pockets pass the confidence threshold.
8. The vertical left-edge power slider is detected with at least 0.80 confidence.
9. The same direct-shot plan is present in two consecutive observations.
10. No previous shot is awaiting its result and the gesture cooldown has elapsed.

The app package guard and own-turn OCR gate prevent background activity in other apps and opponent-turn shots. STOP is checked before aim and again before power input.

## Gesture mapping

- Aiming uses one drag from the cue-ball center to the planner's ghost-ball aim point.
- Power uses one drag on the vertical left-edge slider.
- Recorded calibration indicates slider top=0%, bottom=100%; the drag begins at the top and moves downward.
- No fine-aim arrows or spin controls are touched.
- Power is clamped to the legacy observed interval 26.5–62.4%. A simple route-distance heuristic interpolates within that range. This is a conservative first integration, not a calibrated physical power model.

## Recorded evidence used

- 1000323257.mp4: 720×1216 portrait gameplay; target progression and four outcome/power observations.
- 1000323259.mp4: 720×1216 long match; turn handoffs, scratch events, target progression and ball movement.
- 1000323339.mp4: reviewed direct-shot examples and power observations, plus bank/combo examples deliberately excluded from v1.
- XRecorder_20261030_01.mp4: prior analysis of slider drag direction and short/long power references.
- XRecorder_20261107_02.mp4: user-confirmed maximum-to-minimum power sweep; 97.8% UI maps to maximum effective power.

The current local working set contains the three raw gameplay videos 1000323257.mp4, 1000323259.mp4 and 1000323339.mp4; the other recordings are represented by prior analysis and repository annotations. The 2.4-second XRecorder_20261110_02.mp4 home-screen clip is excluded.

## Explicitly not enabled

- Opening-break execution remains disabled until the intended second rack-position target is confirmed.
- Ball-in-hand cue placement remains manual; the white-string boundary is not yet calibrated as a gesture region.
- Banks, kicks, kisses, blind shots, combinations, multi-rail routes and spin remain excluded.
- No live-device gameplay test has yet validated the integrated gestures. Treat the APK as supervised experimental software.
