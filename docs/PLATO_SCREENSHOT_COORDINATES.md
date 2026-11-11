# Plato reference coordinates — 2026-11-11

All coordinates below are measured in screenshot pixels from the supplied
611 × 1024 screenshots. Origin is the top-left; +x points right and +y points
down. The three images have identical dimensions and the same table layout.

## Table layers

| Layer | Left | Top | Right | Bottom |
|---|---:|---:|---:|---:|
| Green-mask bounds (inclusive detector coordinates) | 112 | 159 | 495 | 904 |
| Playable / cushion-nose center limits | 120 | 169 | 490 | 895 |
| Outer rail envelope | 80 | 127 | 531 | 936 |

The green detector rectangle includes the green cushion tint. Ball centers
must remain inside the smaller playable rectangle. The outer rail envelope is
used for table identification, not for ball-center physics.

## Six pocket centers

Centers are the centroids of the dark pocket openings, measured consistently
across all three screenshots.

| Pocket | Center (x, y) | Dark opening bounds | Entry continuation point |
|---|---:|---:|---:|
| Top-left | (118.60, 163.39) | about 48 × 47 px | (103.60, 163.39) |
| Top-right | (490.53, 163.43) | about 48 × 47 px | (505.53, 163.43) |
| Middle-left | (108.34, 531.65) | about 35 × 42 px | (103.34, 531.65) |
| Middle-right | (500.68, 531.62) | about 35 × 42 px | (505.68, 531.62) |
| Bottom-left | (118.61, 899.90) | about 48 × 47 px | (103.61, 899.90) |
| Bottom-right | (490.48, 899.83) | about 48 × 47 px | (505.48, 899.83) |

The entry continuation points lie just beyond the cushion and define the
incoming direction. The actual pocket center remains the capture target.

## Packed 9-ball diamond

Measured from the first two screenshots. Ball radius is approximately
11.5 px. The rack has five rows in a 1-2-3-2-1 arrangement:

| Row | Ball | Color | Center (x, y) |
|---|---:|---|---:|
| 1 | 8 | Black | (306.5, 274.5) |
| 2 | 6 | Green | (294.5, 294.5) |
| 2 | 7 | Maroon | (318.5, 294.5) |
| 3 | 4 | Purple | (283.5, 314.5) |
| 3 | 1 | Yellow solid | (306.5, 314.5) |
| 3 | 5 | Orange | (329.5, 314.5) |
| 4 | 2 | Blue | (294.5, 334.5) |
| 4 | 3 | Red | (318.5, 334.5) |
| 5 | 9 | Yellow stripe | (306.5, 354.5) |

The rack's long axis is vertical. The 1-ball is the bottom apex nearest the
cue ball; the 9-ball is the center ball in row 3. This matches the HUD's
"Hit the 1-ball" instruction. Do not infer the legal ball from screen-y order
alone; use the HUD instruction and the standard 9-ball rack positions.

## Cue-ball examples

| Screenshot | Cue-ball center (x, y) | Notes |
|---|---:|---|
| Break setup A | about (303, 725) | Cue is near the center line |
| Break setup B | about (199, 725) | User moved the cue left while aiming |
| Post-break | about (155.5, 272.5) | Cue ball after the break |

## Post-break ball coordinates

Measured from the third screenshot. These are screen positions in that
specific frame, not fixed rack positions.

| Ball | Color | Center (x, y) |
|---:|---|---:|
| Cue | White | (155.5, 272.5) |
| 9 | Yellow stripe | (292.5, 305.5) |
| 2 | Blue | (286.5, 479.5) |
| 3 | Red | (466.5, 455.5) |
| 4 | Purple | (219.5, 261.5) |
| 5 | Orange | (402.5, 658.5) |
| 6 | Not visible in this frame | — |
| 7 | Maroon | (322.5, 277.5) |
| 8 | Black | (169.5, 643.0) |
| 1 | Yellow solid | (417.5, 823.5) |

## Color identity model

| Ball | Plato color | Useful visual distinction |
|---:|---|---|
| 1 | Yellow solid | Yellow shell, no broad white stripe |
| 2 | Blue solid | Blue shell |
| 3 | Red solid | Bright red shell |
| 4 | Purple solid | Purple shell |
| 5 | Orange solid | Bright orange shell |
| 6 | Green solid | Darker green than the felt |
| 7 | Maroon solid | Dark maroon/brown-red; hue overlaps orange, so use brightness |
| 8 | Black | Very low value / nearly black |
| 9 | Yellow stripe | Yellow shell with a broad white stripe |

Color classification is a fallback. Number OCR and the HUD's "Hit the X-ball"
announcement take priority. The color classifier must reject ambiguous
samples rather than invent a ball number.

## Important implementation note

The screenshots provide precise table and rack reference coordinates, but the
current connected-component ball detector can merge touching rack balls into
one blob. The rack geometry test validates coordinates after candidate balls
exist; it does not by itself solve candidate separation. Before autonomous
breaks are enabled, live vision must split all nine touching balls and pass
the measured rack template test.
