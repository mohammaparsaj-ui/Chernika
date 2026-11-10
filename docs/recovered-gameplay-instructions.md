# Recovered Plato 9-ball walkthrough — structured requirements

**Source:** user's spoken walkthrough recovered from the recording.  
**Usable narration:** approximately 8 minutes 15 seconds.  
**Purpose:** requirements traceability for the Chernika engine built from scratch.  
**Caution:** speech recognition is imperfect; ambiguous phrases are explicitly marked rather than converted into rules.

## Gameplay and controls

- **00:00–00:30 — Accounts and match start.** The user distinguishes their personal account from a second account dedicated to Chernika. The designated Chernika account is the account to use for bot testing. The narration begins a normal 9-ball match with a second device/player joining.
- **00:30–00:43 — Rack.** The opening rack is diamond-shaped.
- **00:43–01:19 — Table and cue-ball placement.** The table has six pockets: two upper, two middle/side, and two lower. The cue ball can be dragged within the rectangular placement area behind the white string at the foot of the table; it must not cross that line.
- **01:19–02:18 — HUD and legal target.** The top-right announcement says 'Hit the 1 ball', then 'Hit the 2 ball', and so on through 8. For the 9 it says 'Pocket the 9 ball'. The lowest-numbered remaining object ball is the legal first-contact ball. A 9-ball combination/trick shot can win instantly if executed legally; contacting the wrong ball first is a foul.
- **02:18–02:42 — Spin.** Spin presets exist: up, down, left, right and center. The user explicitly says not to use spin in the initial version; use ordinary center/no-spin shots.
- **02:42–03:37 — Break and first milestone.** The user says not to drag the cue ball before the opening break; leave it at its default position. The user says to aim at the 'second ball' in the rack. The intended meaning appears to be the second rack position, not necessarily the ball numbered 2; verify this against a live rack image before implementing break aiming. The user also says the aiming guide can help confirm the shot.
- **03:37–04:06 — Initial success criterion.** The first milestone is reliable ball detection and legal first contact. Pocketing a ball is not required at this stage. Avoid fouls; legally hitting the required ball is already a successful first milestone.
- **04:06–04:42 — Progression and colors.** After the 2 is pocketed, the HUD announces 'Hit the 3 ball'. Use the remaining-ball inventory and color cues as secondary evidence: 1 yellow, 2 blue, 3 red, 4 purple.
- **04:42–05:09 — Known visual traps.** Do not confuse the 1 and 9 balls. The 6 ball is green like the felt and can blend into the table; it must not be ignored.
- **05:09–05:43 — Direct shots only.** The user lists trick-shot types such as kiss, kick, bank, blind and combo shots, but explicitly says not to implement them at the beginning. Play direct shots only.
- **05:43–06:31 — Legal contact over potting.** If a direct pot is unavailable, prioritize a direct legal first contact. A legal contact-only shot is preferable to an illegal attempt at the 9. The user demonstrates cue-ball placement within the white-string rectangle and emphasizes hitting the required fourth ball rather than trying to pocket the 9 through a combination.
- **06:31–07:17 — Continue by lowest remaining ball.** Use the colors and remaining-ball inventory to identify the next legal target. The example has only the purple 4 among the earlier solid-color cues, with the 9 still visible; hit the 4 first.
- **07:17–07:59 — Replay versus live state.** The displayed Best Shot is from a previous game recorded before this walkthrough. It shows combo/kiss/bank play and must not be mistaken for the current live table.
- **07:30–08:15 — Account and project intent.** The user reiterates that the dedicated Chernika account is the account for play and asks for a new engine from scratch based on the walkthrough. The recording ends with a note that it has been running for almost sixteen minutes.

## Requirements derived from the narration

1. Six-pocket table detection and diamond-rack recognition.
2. Read the top-right HUD as a primary source of legal target.
3. Cross-check the HUD with all visible numbered balls; lowest remaining ball is first contact.
4. Distinguish 1 from 9; specifically improve green 6 detection against green felt.
5. No spin in the initial engine.
6. Direct routes only; no banks, kicks, kisses, blind shots or combos in v1.
7. A legal first contact is the first milestone even if no ball is pocketed.
8. Keep the cue ball at its default position for the opening break; investigate aiming at the second rack-position ball before implementing break execution.
9. For ball-in-hand, remain inside the rectangle bounded by the white string at the foot of the table.
10. Treat Best Shot replay as historical footage, not a live state.
11. Use the designated Chernika account, not the user's personal account.

## Ambiguities deliberately not promoted to rules

- 'Second ball' at the break is interpreted as the second ball's position in the rack only as a hypothesis; confirm visually before coding the break planner.
- The phrase around 'treat it like the spin ball' is unclear, so no behavior is inferred from it.
- The spoken game counter around 07:17 is not a gameplay rule.
