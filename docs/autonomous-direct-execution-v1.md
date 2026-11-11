# Chernika direct-execution integration

## Screen-capture permission flow

The earlier build relied on AccessibilityService.takeScreenshot. This revision switches frame acquisition to Android MediaProjection so the operating system explicitly asks the user for screen-capture consent.

Activation is now:
1. Enable Chernika Game Control in Android Accessibility settings.
2. Tap ACTIVATE.
3. Approve Android's screen-capture dialog and choose Entire screen.
4. The app starts a foreground service declared with foregroundServiceType="mediaProjection".
5. Switch to Plato. The accessibility service detects the foreground package and reads frames supplied by the MediaProjection service.
6. Tap STOP to disable game gestures and stop screen capture.

A new Android consent token is requested for each capture session. The token is used once to create the virtual display. Android revocation stops the service and clears the engine-enabled flag.

## Runtime gates

A shot can be dispatched only when all of the following are true:

1. The user has activated Chernika and granted the current MediaProjection session.
2. The Accessibility service is enabled.
3. The active accessibility package contains 'plato'.
4. OCR identifies the user's turn ('Your Turn' or 'Chernika's turn').
5. The screen is not a replay/results screen and the HUD contains a recognized 9-ball instruction.
6. The table is stable for at least three frames.
7. All remaining object balls and the cue ball have a reconciled identity/inventory.
8. All six pockets pass the confidence threshold.
9. The vertical left-edge power slider is detected.
10. The same direct-shot plan is present in two consecutive observations.
11. No previous shot is awaiting its result and the gesture cooldown has elapsed.

## Gesture mapping

- Aiming uses one drag from the cue-ball center to the planner's ghost-ball aim point.
- Power uses one drag on the vertical left-edge slider.
- Recorded calibration indicates slider top=0%, bottom=100%.
- No fine-aim arrows or spin controls are touched.
- Power is clamped to the legacy observed interval 26.5–62.4%. A route-distance heuristic interpolates within that range. This is a conservative first integration, not a calibrated physical power model.

## Deliberate exclusions

- Opening-break execution remains disabled until the intended second rack-position target is confirmed.
- Ball-in-hand cue placement remains manual.
- Banks, kicks, kisses, blind shots, combinations, multi-rail routes and spin remain excluded.
- Live-device gameplay and gesture coordinates have not yet been verified.
