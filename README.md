# Chernika Engine — raw Android build

This is a fresh, engine-only Android project. It does not import code from the older `jozajozapari/Chernika` repository.

## Current raw-build scope

- Reads the game's top-right target announcement with on-device ML Kit OCR (`Hit the X ball`, `Pocket the 9 ball`).
- Detects the live green-felt bounds and maps them into a table-relative coordinate frame.
- Uses the user's canonical 611x1024 portrait screenshot calibration: playable bounds (112,158)-(496,905), six pocket centers, six beyond-cushion entry aim points, opening widths (47.86 px corners; 44.52 px side pockets), and an 11.13 px ball-radius prior.
- Keeps detected green-felt bounds separate from the first-pass playable/cushion boundary estimate; the outer rail envelope is cross-checked against the 720x1216 gameplay recording.
- Refines pocket centers and opening dimensions from dark image components only when size, shape, distance and confidence checks pass; otherwise retains the measured reference anchors.
- Refines ball radius from the median of at least three plausible ball components, rejecting unstable or outlier-dominated frames.
- Exposes screen-to-table and table-to-screen transforms so shot geometry is independent of device resolution.
- Scores direct, bank and combination routes in normalized table-width units; uses beyond-cushion aim points for pocket-entry direction and converts final aim points back to screenshot pixels only for Android gestures.
- Uses the calibrated ball-radius prior to filter candidate ball components.
- Rejects green regions with the wrong portrait-table aspect ratio or screenshots where the full rail envelope is clipped.
- Detects candidate balls and the cue ball from the table image; attempts to read ball numbers from enlarged crops.
- Plans unobstructed cue-to-object and object-to-pocket paths using ghost-ball geometry.
- Uses accessibility gestures to set the aim line and drag the detected power bar.
- Keeps monitoring continuously; it does not stop merely because a single shot has completed.
- Detects scratch/ball-in-hand announcements and avoids moving the cue ball when a clear shot is already available.

## Important prototype limits

This is a first raw engine build, not a claim of verified full autonomous play. The primary pocket and ball measurements come from the user's 611x1024 screenshot; the 720x1216 recording is a secondary rail-envelope cross-check. The source did not conclusively distinguish the felt edge from the cushion nose, so the playable boundary remains a measured first-pass approximation. Dark-component pocket refinement and median ball-radius estimation are guarded by conservative confidence checks and have synthetic unit tests, but still need validation on real screenshots across devices. Exact cushion-nose lines, perspective correction, pocket capture physics, and screen-to-game gesture alignment remain open. Plato's exact table colors, screen layout, aiming gesture, and power-bar behavior can vary by device and game version. The engine deliberately does not fire when it cannot confidently detect the table, cue ball, target ball, or power control. Bank shots, combo-shot planning, and reliable scratch placement require live-device calibration and testing before they can be considered complete.

## Install and enable

1. Install the debug APK on Android 11 or newer.
2. Open **Chernika Engine** and enable **Chernika Game Control** in Android Accessibility settings.
3. Return to Chernika Engine and tap **Start engine**.
4. Open Plato 9-ball and keep the full table visible in portrait orientation.
5. Tap **Stop engine** before leaving the game.

The app uses Accessibility screenshot and gesture APIs. Android will show the system accessibility disclosure/confirmation. The app does not use a remote server for OCR; text recognition runs on-device.

## Build

GitHub Actions builds `app-debug.apk` and runs unit tests. The workflow retains the APK artifact for 7 days to limit storage use. The artifact is a debug-signed APK intended for direct device testing, not Play Store release.
