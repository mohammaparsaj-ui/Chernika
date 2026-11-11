# Chernika Engine — engine-only Android APK

Chernika is a minimal Android controller for Plato 9-ball. Its launcher has one ACTIVATE/STOP control and a text status. It does not show character art, a dashboard, overlays, or game visuals.

## Current implementation

- Uses Android Accessibility screenshots and on-device ML Kit OCR.
- Runs only while the foreground package name contains 'plato'.
- Requires an OCR-confirmed 'Your Turn' / 'Chernika's turn' state; opponent turns are blocked.
- Reads 'Hit the X ball' and 'Pocket the 9 ball' HUD instructions.
- Tracks the full 1–9 inventory over time, including the green 6 recovery path and six-pocket refinement.
- Requires a stable table, complete ball inventory, six confident pockets and a detected vertical power slider before producing an executable plan.
- Plans direct pots or direct legal-first-contact shots only. It does not attempt banks, kicks, kisses, combos or spin.
- Uses drag-based aiming and a top-to-bottom vertical power-slider gesture. The recorded slider direction is top=0%, bottom=100%.
- Waits for ball motion to settle and the next legal state before another shot. It has a gesture cooldown and a no-motion recovery timeout.
- The app never changes cue-ball placement automatically. Break execution remains gated pending a final confirmation of the intended second rack-position target.

## Recorded-data constraints

The current slider power estimate is a bounded heuristic, not a trained policy. It stays inside the legacy observed interval (26.5–62.4%) instead of extrapolating to maximum power. The user-confirmed anchor is 97.8% UI = maximum effective power; this does not mean every shot should use maximum. The 1000323339 recording includes bank/combo/right-spin demonstrations, but those routes are intentionally excluded from this first execution increment because the spoken requirements specify direct-only, center-spin play initially.

The raw recordings remain local; the repository stores derived metadata and compact regression fixtures. The implementation references the available recordings: 1000323257.mp4, 1000323259.mp4, 1000323339.mp4, the prior XRecorder_20261030_01.mp4 calibration analysis, and the XRecorder_20261107_02.mp4 power sweep. XRecorder_20261110_02.mp4 was only a short home-screen capture and is not used as gameplay evidence.

## Safety gates and current limitations

This is an experimental, supervised gameplay build, not a verified unattended bot. It must remain supervised on the first device test. The detector/OCR and gesture coordinates have not been validated on the user's live device after this integration. If any required observation is uncertain, Chernika waits rather than firing.

Not implemented in this increment:
- Automatic opening break (the second rack-position instruction needs final visual confirmation).
- Automatic ball-in-hand repositioning.
- Bank, kick, combo, kiss, blind, multi-rail or spin shots.
- Robust recovery from prolonged occlusion or missed ball-number OCR.

The Android service needs Accessibility permission and Android 11 or newer (API 30+) for screenshots. OCR runs on-device; no remote OCR service is used.

## Build and install

GitHub Actions runs JVM tests and assembles the debug APK. The artifact is debug-signed for direct device testing and is not a Play Store release.
