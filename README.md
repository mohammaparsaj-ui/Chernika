# Chernika Engine — raw Android build

Chernika is an engine-only Android project for on-device Plato 9-ball vision and touch control. It does not import code from the older jozajozapari/Chernika repository.

## Table-first reference

The internal simulation is a table-only coordinate model based on the supplied 611 × 1024 portrait screenshot. It stores the felt/playable bounds and six calibrated pocket anchors without rendering balls, cue sticks, aiming guides, player HUD, chat, or controls. See docs/TABLE_ONLY_REFERENCE.md for the measured geometry and shot sequence.

## Engine capabilities

- Reads the game's top-right target announcement with on-device ML Kit OCR (Hit the X ball, Pocket the 9 ball).
- Detects the live green-felt bounds and maps them into a table-relative coordinate frame.
- Projects six calibrated pocket centers and beyond-cushion entry aim points across device resolutions.
- Refines pocket centers and opening dimensions from dark image components only when size, shape, distance and confidence checks pass.
- Detects candidate balls and the cue ball from the table image.
- Classifies ball-shell colors as a fallback and uses number OCR as the higher-priority correction.
- Verifies the opening rack against the 9-ball five-row diamond geometry before attempting a break.
- Selects the lowest visible numbered ball only when all object-ball identities are confident and unique.
- Plans direct, bank and combination routes with ghost-ball geometry.
- Uses accessibility gestures to align the cue line and set power on vertical or horizontal power bars.
- Keeps monitoring continuously and waits rather than shooting when a required detection is uncertain.

## Important prototype limits

This is a staged prototype, not a claim of verified full autonomous play. Pocket anchors are measured from the supplied screenshot; the exact felt-edge/cushion-nose semantics remain first-pass estimates. Color classification, rack detection, vertical power-bar interaction, gesture alignment and the power-to-speed curve require validation on fresh live screenshots and recordings from the target device. Touch input is sent only while the user has explicitly enabled the engine and Android Accessibility service. The engine must not fire when the table, cue ball, target ball, route, or power control is uncertain.

## Install and enable

1. Install the debug APK on Android 11 or newer.
2. Open Chernika Engine and enable Chernika Game Control in Android Accessibility settings.
3. Return to Chernika Engine and tap Start engine.
4. Open Plato 9-ball in portrait orientation with the full table visible.
5. Tap Stop engine before leaving the game.

The app uses Android Accessibility screenshot and gesture APIs. Android shows the system accessibility disclosure/confirmation. OCR runs on-device.

## Build

GitHub Actions runs the unit tests and builds app-debug.apk. The workflow retains the debug APK artifact for 7 days. It is intended for direct device testing, not Play Store release.
