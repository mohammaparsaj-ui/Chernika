# Chernika 7.1 development status — instruction-first dry run

**Branch:** `feature/9ball-instruction-first-v1`  
**Pull request:** #5 (draft)  
**Build version:** `7.1-dev-dry-run`  
**Package:** `com.cherunika.chernika`

## Implemented

- Pure-Java decision core in `com.cherunika.chernika.engine`.
- Explicit HUD parsing for `Hit the X ball` and `Pocket the 9 ball`.
- Fail-closed validation for live/replay state, stable frames, six pocket anchors, ball-number confidence, duplicate numbers, inventory completeness and lowest-ball legality.
- Direct-only pot candidates and direct legal-contact-only candidates.
- v1 excludes banks, kicks, combos, spin and automatic cue-ball placement.
- Android screenshot/OCR service feeds the instruction-first core in **DRY-RUN ONLY** mode.
- Legacy gesture execution has been removed from the accessibility service. A ready plan is a proposal, not a tap/drag command.
- Main screen copy clearly states that this build does not perform touch gestures.
- A colour-agnostic radial-edge detector supplements the RGB component detector. It is designed to recover balls whose paint resembles the felt, especially the green 6.
- The edge detector is integrated into the Android vision pipeline and merges candidates near already-detected colour components.

## Green 6 regression result

A cropped frame from the user's Plato recording is now included as a compact test fixture.

- Fixture: 130 × 160 px crop containing the complete nine-ball diamond rack.
- The detector returns **9 non-overlapping ball candidates** for that rack.
- The green 6 candidate is localized within **3.5 px** of the manually checked center.
- Regression coverage includes: green ball with felt-like interior colour, uniform felt negative control, two distinct nearby balls, and the actual Plato rack crop.
- The score is a heuristic edge/shape score, not a calibrated probability.

See `docs/green-6-vision-regression.md` for the detector parameters, fixture description and limitations.

## Verification

Verified using JDK 17.0.21, Gradle 9.6.0, Android SDK Platform 35 and Android Build Tools 36.0.0:

- `gradle --no-daemon testDebugUnitTest assembleDebug` — **BUILD SUCCESSFUL**.
- **51 JVM tests passed; 0 failures; 0 errors; 0 skipped.**
  - Existing project tests: 29.
  - Instruction-first parser and engine tests: 18.
  - Green-6 edge detector regression tests: 4.
- APK package inspection confirms:
  - `com.cherunika.chernika`
  - versionCode `71`
  - versionName `7.1-dev-dry-run`
  - minSdk `30`
  - targetSdk `35`
- Debug APK SHA-256: `bf19aa9e1dceaa0b107648b823e66e73932e7679fb841a2313a4ffca289e97c7`.

No Android device or emulator was attached to the build environment, so this verification is a JVM/build check, not a live Plato gameplay test.

## Important limitations — do not enable live firing

This is not an autonomous-play release.

1. The pocket detector must refine all six pocket anchors to the engine's confidence threshold. Otherwise it correctly returns WAIT.
2. Ball-number OCR currently accepts only a single unambiguous digit and assigns a conservative provisional confidence. This confidence has not yet been calibrated on a labelled set of Plato ball crops.
3. The adapter's inventory-complete flag checks that detected candidates have unique readable numbers. It does **not** prove detector recall. The green 6 is recovered in one rack regression frame, but general recall across lighting, motion, occlusion and screen sizes remains unmeasured.
4. Stable-frame tracking compares detected ball centers across screenshots. It has not yet been validated against slow residual ball motion.
5. The planner is geometric and direct-only. It has no measured collision/throw model and does not calculate a calibrated power-bar pull.
6. Opening-break execution and automatic ball-in-hand placement are not enabled.
7. No live Plato match or device/emulator gameplay test has been completed.

The engine must remain dry-run until ball inventory recall, pocket calibration, live/replay classification, aim coordinates and power calibration have been verified against a broader labelled recording set and supervised device tests.

## Next engineering steps

1. Expand the labelled frame set beyond the single rack crop, including green 6 in different positions, lighting, overlap and motion.
2. Measure ball and pocket detection precision/recall and calibrate OCR confidence.
3. Validate six-pocket refinement on complete table screenshots.
4. Add motion-settled verification using consecutive frames and post-shot state changes.
5. Validate direct contact and direct pot candidates against recorded aiming guides.
6. Calibrate the power bar separately; do not infer power from distance alone.
7. Add supervised, explicitly armed gesture execution only after the above gates pass.
