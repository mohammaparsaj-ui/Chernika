# Chernika gameplay replay calibration — 720 × 1216

Source: user-supplied Plato 9-ball recording, 75.94 seconds, 720 × 1216 portrait.
Coordinates use the top-left screenshot origin; x increases right and y down.

## Static table template

The green-felt mask is stable across the recording:

| Layer | Left | Top | Right | Bottom |
|---|---:|---:|---:|---:|
| Green-felt detector bounds | 134 | 194 | 585 | 1069 |
| Playable/cushion-nose projection | 143.4 | 205.7 | 579.1 | 1058.4 |
| Approximate outer rail envelope | 96.3 | 156.4 | 627.4 | 1106.6 |

Projected pocket centers:

| Pocket | Center |
|---|---:|
| Top-left | (141.8, 199.2) |
| Top-right | (579.7, 199.2) |
| Middle-left | (129.7, 631.7) |
| Middle-right | (591.7, 631.6) |
| Bottom-left | (141.8, 1064.2) |
| Bottom-right | (579.7, 1064.1) |

The table is a static six-pocket coordinate model. Balls, cue, aim line, HUD
and controls are separate dynamic layers and must not change table geometry.

## Gameplay HUD and power control

- Local turn badge ("Your Turn"): approximately x=423..551, y=84..123.
- Target instruction: approximately x=638..710, y=183..255.
- Vertical power meter: approximately x=20..69, y=590..1020.
- At 16.0 s, the narrow white power thumb is around (48,742), about 65% of
  bottom-to-top travel. The thumb—not the meter's bottom edge—is the drag start.

## Ball scale and replay states

Observed ball radii are approximately 11–13 px in this recording.
The first stable post-break state (about 8 s) contains:

| Ball | Center |
|---|---:|
| Cue | (359, 631) |
| 3 | (423, 627) |
| 6 | (407, 474) |
| 8 | (280, 580) |
| 9 | (365, 245) |

The recording provides a compact gameplay regression sequence:

1. Hit #3; "You pocketed the 3-ball" appears around 24 s.
2. Hit #6; "You pocketed the 6-ball" appears around 40 s.
3. Hit #8; "You pocketed the 8-ball" appears around 52 s.
4. The HUD changes to "Pocket the 9-ball"; the player pockets the 9 and wins around 60 s.

Approximate cue positions after settled shots: after #3 (397,771), after #6
(167,365), and after #8 (247,573).

The cue ball starts near (307,590) at 17.2 s and reaches about (377,617) by
17.6 s before object-ball contact. This is a useful initial-speed and power
slider reference; more shots are needed to finish calibrating friction and
cushion restitution.

## Engine acceptance criteria

- Detect the calibrated table before relying on OCR.
- Detect ball circles only inside the table ROI; reject aim rings and UI icons.
- Identify shell colors first; use OCR only for low-confidence corrections.
- Require an explicit local "Your Turn" badge or local break instruction.
- Wait for all detected ball centers to remain stable across multiple frames.
- Start power gestures from the detected white thumb.
- Track motion until the table settles before planning the next shot.
