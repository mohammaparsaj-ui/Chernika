# Chernika Engine — engine-only Android APK

Chernika is a minimal Android controller for Plato 9-ball. Its launcher has one ACTIVATE/STOP control and a text status. It does not show character art, a dashboard, overlays, or game visuals.

## Activation and screen permission

Chernika uses two separate Android grants:

1. **Accessibility:** enable Chernika Game Control in Android Accessibility settings. This is used for foreground-app detection and touch gestures.
2. **Screen capture:** after Accessibility is enabled, tap ACTIVATE and approve Android's system screen-capture dialog. Choose **Entire screen** so the capture continues when switching from Chernika to Plato.

Screen capture is provided by Android MediaProjection in a visible foreground service. Android requires user consent for each new capture session; Chernika asks again after each fresh activation. A persistent notification indicates that screen capture is active. STOP ends the capture service and disables further game gestures.

## Current implementation

- Uses Android MediaProjection with explicit user consent for screenshots.
- Uses an Android foreground service with the required mediaProjection service type and permissions.
- Uses Accessibility only for foreground-app detection and touch gestures.
- Runs game logic only while the foreground package name contains 'plato'.
- Requires OCR-confirmed 'Your Turn' / 'Chernika's turn' state; opponent turns are blocked.
- Reads 'Hit the X ball' and 'Pocket the 9 ball' HUD instructions.
- Tracks the full 1–9 inventory over time, including the green 6 recovery path and six-pocket refinement.
- Requires a stable table, complete ball inventory, six confident pockets and a detected vertical power slider before producing an executable plan.
- Plans direct pots or direct legal-first-contact shots only. It does not attempt banks, kicks, kisses, combos or spin.
- Adds drag-based aiming and a top-to-bottom vertical power-slider gesture, bounded to the legacy observed 26.5–62.4% interval.
- Waits for shot-result resolution and prevents repeated firing from the same observation.
- Does not reposition the cue ball automatically. Opening break remains gated pending confirmation of the intended second rack-position target.

## Recorded-data constraints

The current slider power estimate is a bounded heuristic, not a trained policy. It stays inside the legacy observed interval (26.5–62.4%) instead of extrapolating to maximum power. The user-confirmed anchor is 97.8% UI = maximum effective power; this does not mean every shot should use maximum. The recordings include bank/combo/right-spin demonstrations, but those routes are intentionally excluded from this first execution increment because the spoken requirements specify direct-only, center-spin play initially.

## Safety and current limitations

This is an experimental, supervised gameplay build, not a verified unattended bot. Live-device gameplay and gesture coordinates still require testing on the target device. If a required observation is uncertain, Chernika waits rather than firing.

Not implemented:
- Automatic opening break.
- Automatic ball-in-hand repositioning.
- Bank, kick, combo, kiss, blind, multi-rail or spin shots.
- Robust recovery from prolonged occlusion or missed ball-number OCR.

The app requires Android 11 or newer (API 30+). OCR runs on-device; no remote OCR service is used. The debug APK is intended for direct supervised testing, not Play Store release.
