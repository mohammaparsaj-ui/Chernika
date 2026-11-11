# Recording review and 7.6 engine fixes

Date: 2026-11-11

## Visually reviewed recordings

The following three uploaded recordings were available as local MP4 files and were reviewed frame-by-frame with OCR on the game HUD.

| File | Duration | Visible evidence |
|---|---:|---|
| `1000323257.mp4` | 75.94 s | Plato launch, live 9-ball table, HUD progression through balls 3, 6, 8 and 9, pocket announcements, then return to profile/lobby. |
| `1000323259.mp4` | 130.61 s | Lobby-to-match transition; at about 26 s the screen says "It's your turn to break" and the HUD says "Hit the 1-ball"; six-pocket table, left vertical power slider, right-side spin selector and fine-aim arrows are visible. At about 40.8 s the game reports "You pocketed 2 balls" while the next HUD target remains the 1-ball. Later HUD targets progress and the final objective becomes "Pocket the 9-ball". |
| `1000323339.mp4` | 40.66 s | A complete short 9-ball match: the break scatters the rack, the HUD advances from ball 1 through later targets, two balls are pocketed during the opening sequence, and the result screen reports a win. |

All three MP4 files contain a mono AAC audio stream at 44.1 kHz. The original narrated 8-minute-15-second walkthrough transcript is preserved in `recovered-gameplay-instructions.md`; the transcript has not yet been independently matched to these exact three MP4 files. Direct audio transcription of the Kiwi share URL was not possible because it resolves to an HTML download page rather than a direct media URL. The visual findings above are independent of that transcript.

## Root cause found in the previous engine

The previous `BallStateTracker` refused to establish a baseline unless all nine balls (1–9) and the cue ball were individually detected and OCR-labelled in two consecutive stable frames before the break.

That assumption fails in the supplied recordings:
- balls overlap in the diamond rack and may not be individually readable;
- the break can pocket balls immediately (the 40-second recording shows two balls pocketed during the opening sequence);
- the engine therefore may never establish its required full-rack baseline;
- the planner also rejected every unread object ball, even when the HUD clearly identified the legal target and the unread ball was merely an obstacle.

This explains why a successful screen-capture permission could still result in no shot being played.

## 7.6 changes

1. Replace the full-rack baseline with a partial-scene temporal tracker. It can stabilize a post-break table without requiring all nine balls to be visible.
2. Retain every unread ball as a physical obstacle. Do not discard it simply because its number OCR failed.
3. Use the live HUD as the primary legal first-contact instruction. Readable lower-numbered balls still trigger a safety conflict; the 9-ball objective still selects the lowest known legal contact ball.
4. Require only two stable-frame intervals rather than three before planning.
5. Inherit an unread ball's number only from a unique, nearby match in the immediately preceding frame. Never reuse stale positions from balls that have disappeared.
6. Explicitly block result screens such as "You won", "You lost", "Victory" and "Defeat".
7. Explicitly pause the opening break. The recording transcript's phrase "second ball" and the visible aim guide need to be reconciled before enabling a high-power break; a normal 26.5–62.4% shot must not be mistaken for a break.
8. Keep ball-in-hand placement, spin, banks, kicks, kisses, combos and multi-rail routes disabled.

## APK size reduction

The 7.5 APK was 52,305,451 bytes (about 52.3 MB). It packaged four ABI copies of the bundled ML Kit OCR pipeline.

The 7.6 build changes OCR to the unbundled Google Play services implementation and filters the device-specific APK to `arm64-v8a`. The first successful 7.6 CI build produced a 2,021,824-byte APK (about 2.02 MB), a reduction of approximately 96.1%.

The unbundled OCR model is downloaded and maintained by Google Play services. The device needs Google Play services and an internet connection for the initial model download; later recognition uses the installed model.

## Validation boundary

JVM tests and APK assembly are automated. They do not prove that MediaProjection consent, OCR model download, screen coordinates, aim gestures or power gestures work on the user's physical tablet. The first device test must confirm the status line progresses from screen capture ready, to Plato detected, to a stable HUD target, to a shot sent. The opening break remains intentionally manual until its target position and power are calibrated.
