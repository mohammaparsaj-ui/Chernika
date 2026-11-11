# Chernika Engine — raw Android build

This is a fresh, engine-only Android project. It does not import code from the older `jozajozapari/Chernika` repository.

## Current raw-build scope — Chernika 7.3

- Reads Plato's top-right `Hit the X ball` / `Pocket the 9 ball` instruction with on-device ML Kit OCR.
- Detects the felt/table bounds, six pocket openings, cue ball and object-ball candidates.
- Supplements colour segmentation with a radial-edge detector to recover felt-coloured balls such as the green 6.
- Refines pocket anchors against recorded-frame fixtures and keeps confidence gates fail-closed.
- Establishes a remembered 1–9 rack only after two matching full-rack frames.
- Tracks numbered balls across screenshots, detects movement, and only confirms a pocket after a previously moving ball reaches a confident pocket zone and is absent for multiple frames.
- Inherits an unread ball identity only when its position uniquely matches a remembered track; otherwise the inventory is incomplete and the engine waits.
- Checks the HUD target against the lowest remaining ball before planning.
- Proposes direct pots or direct legal-contact-only routes. Banks, kicks, combos, spin and automatic cue-ball placement remain out of scope.
- Runs the Android screenshot/OCR adapter in **DRY-RUN ONLY** mode. It reports WAIT states or candidate aim points; it does not tap, drag, set power, or shoot.

The old geometry and physics classes remain in the repository as research/reference code; the active accessibility service does not invoke their legacy gesture path.

## Important prototype limits

This is a development dry-run build, not a claim of verified full autonomous play. The primary pocket and ball measurements come from the user's 611x1024 screenshot; the 720x1216 recording is a secondary rail-envelope cross-check. The source did not conclusively distinguish the felt edge from the cushion nose, so the playable boundary remains a measured first-pass approximation. Dark-component pocket refinement and median ball-radius estimation are guarded by conservative confidence checks and have synthetic unit tests, but still need validation on real screenshots across devices. Exact cushion-nose lines, perspective correction, and screen-to-game gesture alignment remain open. The 2-D simulator now starts from candidate measurements extracted from the existing 2026-10-30 recording: rolling-resistance coefficient 0.0079, ball restitution 0.946, cushion restitution 0.815, and cue-ball speed up to 2.667 m/s. It uses a 0.99 m × 1.98 m table-scale assumption and the user's correction that a 97.8% slider reading represents maximum power. These are provisional calibration values, not production-approved constants; the power-to-speed curve and friction estimate still need replay validation. The simulator is diagnostic only and does not override the live geometric planner. Spin, throw, masse and multi-rail cushion effects are not modeled. Plato's exact table colors, screen layout, aiming gesture, and power-bar behavior can vary by device and game version. The instruction-first service does not fire at all in this build. It waits when table, pocket, inventory, HUD, motion or legal-contact confidence is insufficient. Bank shots, combo-shot planning, and reliable scratch placement require live-device calibration and testing before they can be considered complete.

## Install and inspect

1. Install the debug APK on Android 11 or newer.
2. Open **Chernika Engine** and enable **Chernika Game Control** in Android Accessibility settings.
3. Return to Chernika Engine and tap **Start engine**.
4. Open Plato 9-ball in portrait orientation and keep the full table visible.
5. Inspect the displayed dry-run WAIT reason or candidate shot. The build does not perform any game gestures.
6. Tap **Stop engine** before leaving the game.

The app uses Android Accessibility screenshot APIs and on-device OCR. No remote OCR service is used. Accessibility is currently used for observation only; gesture dispatch is intentionally disabled.

## Build

GitHub Actions builds `app-debug.apk` and runs unit tests. The workflow retains the APK artifact for 7 days to limit storage use. The artifact is a debug-signed APK intended for direct device testing, not Play Store release.
