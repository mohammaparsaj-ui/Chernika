# Chernika — table-only reference model

The supplied 611 × 1024 Plato screenshot is the canonical portrait reference.
The internal simulation deliberately contains only the table: no balls,
cue stick, aiming guide, player HUD, chat, or power control. Live balls and
controls are detected as separate layers over this stable table coordinate
frame.

## Reference geometry

- Detected green-felt bounds: (115, 160) to (494, 903).
- First-pass playable/cushion-nose bounds: (112, 158) to (496, 905).
- Approximate outer rail envelope: (77.6, 125.5) to (530.4, 940.0).
- Reference ball radius: 11.13 px.
- All values are normalized from the reference felt rectangle before being
  projected to the current screenshot resolution.

## Six pocket anchors

| Pocket | Center (px) | Entry-direction aim point (px) | Opening width |
|---|---:|---:|---:|
| Top-left | (118.1, 163.2) | (103.1, 163.2) | 47.86 px |
| Top-right | (489.9, 163.2) | (504.9, 163.2) | 47.86 px |
| Middle-left | (108.2, 531.5) | (103.1, 531.5) | 44.52 px |
| Middle-right | (499.8, 531.5) | (504.9, 531.5) | 44.52 px |
| Bottom-left | (118.1, 899.8) | (103.1, 899.8) | 47.86 px |
| Bottom-right | (489.9, 899.8) | (504.9, 899.8) | 47.86 px |

Pocket image refinement is allowed only when the observed dark opening is
plausible and close to its calibrated anchor. A weak frame never replaces the
reference geometry.

## Shot pipeline

1. Detect the green felt and validate the portrait table aspect ratio.
2. Project the six pocket anchors into the live screen coordinate frame.
3. Refine pocket centers only from high-confidence dark-opening components.
4. Detect candidate balls, classify shell colors, and use number OCR as a
   higher-priority correction.
5. Verify the opening rack against the five-row 1-2-3-2-1 diamond lattice.
6. If the rack is verified, aim at the apex ball for the break.
7. Otherwise, obey the HUD Hit the X ball announcement. If it is absent,
   select the lowest ball only when every visible object ball has a confident,
   unique number.
8. Compute the ghost-ball contact point so the object-ball path is aligned
   with a selected pocket.
9. Drag the cue aim from the cue ball to the ghost-ball point.
10. Detect the power-bar orientation and drag to the calibrated power point.

The engine must wait rather than shoot if the table, a pocket, the legal ball,
the cue ball, or the power bar is uncertain. Color classification is a
fallback, not a replacement for readable HUD instructions or number OCR.
The diamond-rack detector and power-bar gesture still require validation
against fresh live Plato recordings on the target device.
