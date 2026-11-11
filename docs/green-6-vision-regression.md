# Green 6 vision regression — Chernika 7.1

## Problem

The previous ball segmentation used a global RGB distance threshold against the average felt colour. A green object ball can have an interior colour close to the green cloth, so its connected component may disappear even while the other eight object balls are detected.

## Detector added

`BallEdgeDetector` is a pure-Java, colour-agnostic candidate detector. It scans the detected felt region at the calibrated ball-size prior and evaluates five radius hypotheses (0.88×, 0.94×, 1.00×, 1.06× and 1.12×). At each hypothesis it samples 24 radial directions and measures the contrast across the candidate's outer boundary.

Current conservative gates:

- Mean radial contrast: at least 14 intensity levels.
- Angular edge support: at least 50% of sampled directions.
- Heuristic candidate score: at least 0.78.
- Non-maximum suppression: centers closer than 1.65× the larger candidate radius are treated as one candidate.
- Integration merge: edge candidates within 1.20× the larger radius of an existing colour candidate are not duplicated.

The heuristic score is not a probability and has not been statistically calibrated. The detector supplements the colour-based candidates; it does not replace the rest of the vision pipeline.

## Recorded-frame regression

The fixture is a 130 × 160 grayscale crop from the user's Plato recording. It contains a complete nine-ball diamond rack and retains the dark ball rims and printed ball numbers. The green 6 is the second-row, left-hand ball, with an approximate center at (47, 57) in the crop.

The JVM regression test confirms:

1. The edge detector returns nine non-overlapping candidates from this rack crop.
2. The green 6 is present and its candidate center is within 3.5 px of the manually checked center.
3. A synthetic green ball whose interior differs from the felt by less than the old RGB threshold is still detected from its boundary.
4. Uniform felt does not produce circular candidates.
5. Two separate balls remain two candidates.

## What this proves — and what it does not

It proves that the new edge detector can recover the green 6 in this specific recorded rack frame and that the pure-Java detector passes its regression tests.

It does **not** establish general ball-detection precision/recall, full-table inventory recall, OCR accuracy, six-pocket localization, moving-ball detection, or reliable aim coordinates. The detector still needs evaluation on more recorded frames with balls near cushions, partial occlusion, reflections, different display scales and motion.

The Android app remains **DRY-RUN ONLY**. No taps, drags, cue placement, power pulls or spin inputs are enabled by this change.
