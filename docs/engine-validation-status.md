# Chernika 7.0 development status — instruction-first dry run

**Branch:** `feature/9ball-instruction-first-v1`  
**Pull request:** #5 (draft)  
**Build version:** `7.0-dev-dry-run`  
**Package:** `com.cherunika.chernika`

## Implemented

- Pure-Java decision core in `com.cherunika.chernika.engine`.
- Explicit HUD parsing for `Hit the X ball` and `Pocket the 9 ball`.
- Fail-closed validation for live/replay state, stable frames, six pocket anchors, ball-number confidence, duplicate numbers, inventory completeness and lowest-ball legality.
- Direct-only pot candidates and direct legal-contact-only candidates.
- v1 excludes banks, kicks, combos, spin and automatic cue-ball placement.
- Android screenshot/OCR service now feeds the instruction-first core in **DRY-RUN ONLY** mode.
- Legacy gesture execution has been removed from the accessibility service. A ready plan is a proposal, not a tap/drag command.
- Main screen copy clearly states that this build does not perform touch gestures.

## Verification

Last verified using JDK 17.0.21, Gradle 9.6.0, Android SDK Platform 35 and Android Build Tools 36.0.0:

- `gradle --no-daemon clean testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL**.
- **47 JVM tests passed; 0 failures; 0 errors.**
  - Existing project tests: 29.
  - New instruction-first parser and engine tests: 18.
- APK package inspection confirms:
  - `com.cherunika.chernika`
  - versionCode `70`
  - versionName `7.0-dev-dry-run`
  - minSdk `30`
  - targetSdk `35`
- Debug APK SHA-256: `948c81648c209809cbfe1b1dd323467e6a681d58f48b9f1a90b3091f4b11ea0f`.

## Important limitations — do not enable live firing

The build is not yet an autonomous-play release.

1. The pocket detector must refine all six pocket anchors to the engine's confidence threshold. Otherwise it correctly returns WAIT.
2. Ball-number OCR currently accepts only a single unambiguous digit and assigns a conservative provisional confidence. This confidence has not yet been calibrated on a labelled set of Plato ball crops.
3. The Android adapter's inventory-complete flag currently means all detected candidates have unique readable numbers. It does **not** prove detector recall; the green 6 ball is a known risk because it can blend into the felt.
4. Stable-frame tracking compares detected ball centers across screenshots. It has not yet been validated against slow residual ball motion.
5. The planner is geometric and direct-only. It has no measured collision/throw model and does not calculate a calibrated power-bar pull.
6. Opening-break execution and automatic ball-in-hand placement are not enabled.
7. No live Plato match or device/emulator gameplay test has been completed.

The engine must remain dry-run until ball inventory recall, pocket calibration, live/replay classification, aim coordinates and power calibration have been verified against labelled recording frames and supervised device tests.

## Next engineering steps

1. Build a labelled frame set from the recovered gameplay recording, including the green 6 and overlapping/partially occluded balls.
2. Measure ball and pocket detection precision/recall and calibrate OCR confidence.
3. Add motion-settled verification using consecutive frames and post-shot state changes.
4. Validate direct contact and direct pot candidates against recorded aiming guides.
5. Calibrate the power bar separately; do not infer power from distance alone.
6. Add supervised, explicitly armed gesture execution only after the above gates pass.
