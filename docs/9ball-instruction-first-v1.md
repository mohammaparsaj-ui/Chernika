# Chernika 9-Ball Engine v1 — Instruction-First Baseline

**Branch:** `feature/9ball-instruction-first-v1`  
**Status:** specification and pure-Java core are being built in isolation; this is not a claim of verified autonomous play.

## Source of requirements

The user narrated the Plato 9-ball recording. The recovered, usable voice track is approximately **8 minutes 15 seconds**. The narration ends with the speaker saying the recording has been running for almost 16 minutes, so this transcript may represent only the spoken portions recovered from the first part of the video. Unclear words are not treated as requirements.

## Product goal

Build a new 9-ball decision engine from scratch. Keep Android screen capture and accessibility gestures as adapters; keep game rules, observation validation, shot selection and safety decisions in a pure-Java core that can be unit-tested without Android.

The first milestone is **reliable recognition and legal first contact**, not winning quickly. A shot that legally contacts the required ball is acceptable even when it does not pot a ball.

## Confirmed gameplay facts

- The table has **six pockets**: two top, two middle/side, and two bottom.
- The rack is diamond-shaped.
- The top-right announcement is a primary source of turn intent:
  - `Hit the 1 ball` through `Hit the 8 ball` identifies the required first-contact ball.
  - `Pocket the 9 ball` identifies the 9 as the current required ball/objective; for the 9-ball endgame, first contact is the 9.
- The lowest-numbered remaining object ball must be contacted first. The HUD announcement and visual ball inventory must agree before a shot is authorized.
- Ball colors are useful secondary cues:
  - 1 yellow
  - 2 blue
  - 3 red
  - 4 purple
  - 6 green, which can blend into the table felt
- Do not confuse the 1 ball with the 9 ball. Number/stripe recognition is more authoritative than color alone.
- The cue-ball placement area is bounded by the white string/line at the foot of the table. A cue ball in hand may be dragged only within the permitted placement region.
- At the opening break, leave the cue ball at its default position. Do not add an unnecessary cue-ball drag before the break.
- Spin presets exist (up, down, left, right, center), but v1 uses **center/no spin only**.
- The visible aiming guide can help confirm a shot, but it does not replace legal-contact and obstruction checks.
- The Best Shot replay shown in the recording includes a previous game's combo/kiss/bank shot. It is replay footage, not the current live table state.

## v1 scope and exclusions

### Included

1. Detect the table, six pockets, cue ball and object balls.
2. Read the top-right HUD instruction and validate it against the detected remaining-ball set.
3. Identify the lowest remaining ball and require it to be the first object-ball contact.
4. Generate **direct-only** legal shot candidates:
   - direct pot when the cue path and object-ball-to-pocket path are clear;
   - otherwise, a direct legal-contact-only shot is acceptable.
5. Keep the cue ball stationary for the opening break.
6. Constrain any later ball-in-hand placement to the permitted rectangle behind the white string.
7. Verify the result after the balls stop and the HUD/ball inventory updates.

### Explicitly excluded from v1

- Spin, masse, throw compensation or spin-selector gestures.
- Bank, kick, kiss, combo or other trick-shot planning.
- Any route that requires hitting a different object ball first.
- Guessing a ball number from color when number/stripe evidence is uncertain.
- Shooting while the table, cue ball, required target, replay/live mode, or power control is uncertain.

These can be added only after the direct legal-contact baseline is reliable.

## Fail-closed safety rules

The engine must return **WAIT** rather than shoot when any of these conditions applies:

- The table or one of the six pockets is not confidently localized.
- The cue ball or required target ball is missing, duplicated, or ambiguous.
- HUD target and lowest-numbered remaining ball disagree.
- A ball that could block the cue path is unidentified.
- The direct path to the required first-contact ball is obstructed.
- The screen is showing a Best Shot replay or another non-live state.
- A previous shot is still moving or the outcome has not been verified.
- A cue-ball placement is required but no legal position inside the placement region is validated.

## Engine pipeline

1. **Capture adapter** — acquire a fresh portrait gameplay frame.
2. **Vision adapter** — produce table, pocket, ball, cue-ball, power-control and confidence observations.
3. **HUD parser** — parse `Hit the X ball` / `Pocket the 9 ball`.
4. **State validator** — compare the HUD instruction with the complete detected ball inventory and lowest remaining number.
5. **Rule engine** — establish the legal first-contact target.
6. **Direct planner** — test direct ghost-ball contact and direct pot routes only.
7. **Safety gate** — require confidence, clear paths, stable frame and live-game state.
8. **Gesture adapter** — aim, use center/no spin and shoot only after authorization.
9. **Outcome verifier** — wait for motion to stop, then confirm HUD progression, ball removal, foul/scratch or turn handoff.

## Acceptance tests

- `Hit the 4 ball` with 1–3 already removed and 4 present selects 4.
- `Pocket the 9 ball` selects 9 as the required ball.
- HUD/ball-inventory mismatch returns WAIT.
- An ambiguous 1-versus-9 observation returns WAIT.
- An unidentified green 6 ball returns WAIT rather than being ignored.
- A direct pot is selected only when both required paths are clear.
- If no direct pot exists but direct legal contact is clear, the engine may select contact-only.
- A bank/kick/combo-only route is rejected in v1.
- Opening break preserves the default cue-ball position.
- Ball-in-hand placement never leaves the legal rectangle.
- Spin remains centered; no spin gesture is emitted.
- Best Shot replay frames never trigger a live shot.
- No second shot is sent until the previous outcome is verified.

## Calibration note

The repository's existing 611x1024 table calibration and six pocket anchors are useful priors, not unquestioned truth. Revalidate the playable boundary, pocket openings, ball radius and gesture transform against the newly inspected 720x1216 recording and the actual device before enabling live firing.

## Account note

The narration distinguishes the user's own account from Chernika's designated account. Use the Chernika account shown for bot testing; do not treat the user's personal account as the automation target.
