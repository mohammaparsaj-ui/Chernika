# Six-pocket vision regression — Chernika 7.2

## Recorded source frame

The pocket checks use a static frame at approximately 270 seconds from the user's Plato gameplay recording. The source frame is 720 × 1216 px. The table bounds used for the probe are the already measured green-felt bounds (136, 194)–(583, 1069), projected through `PlatoTableCalibration`.

## Full-frame offline probe

The existing `PocketVisionRefiner` was run on the complete original color frame with the calibrated table geometry. All six pockets refined:

| Pocket | Refined | Observed confidence |
| --- | --- | ---: |
| Top-left | Yes | 0.921 |
| Top-right | Yes | 0.926 |
| Middle-left | Yes | 0.913 |
| Middle-right | Yes | 0.913 |
| Bottom-left | Yes | 0.919 |
| Bottom-right | Yes | 0.924 |

Every pocket exceeds the instruction-first engine's 0.72 confidence gate. The measured anchor shifts were small and remained inside the calibration's conservative blend limit.

## Automated regression fixture

To keep the JVM test asset compact and deterministic, the automated test contains six actual 40 × 40 pixel crops around the six pocket openings. Those crops are placed at the corresponding calibrated positions in a 320 × 540 grayscale frame; the rest of the image is a neutral background. The calibration bounds are scaled from the same 720 × 1216 source profile.

The test asserts:

1. Exactly six pocket anchors are refined.
2. Every pocket confidence is at least 0.72.
3. Each blended center remains within 2 px of its original calibrated anchor.

The existing negative tests still verify that uniform felt does not create pocket refinements and that a ball-sized dark circle is not mistaken for a pocket.

## Limits

This is a static-frame validation with manually established table bounds. It does not prove end-to-end automatic table-bound detection, performance across different matches or device resolutions, robustness to motion/occlusion, or correct pocket detection during a ball entering a pocket. Pocket refinement must continue to fail closed if any anchor is uncertain.

The Android app remains **DRY-RUN ONLY**; no shot, drag, cue placement, power or spin gestures are enabled.
