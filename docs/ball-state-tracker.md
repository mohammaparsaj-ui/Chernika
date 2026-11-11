# Temporal ball-state tracking — Chernika 7.3

## Purpose

A single screenshot cannot prove that the ball detector found every object ball, cannot distinguish a ball that is still moving from one that has stopped, and cannot prove that a missing ball was pocketed. The Android adapter now feeds observations through a stateful, pure-Java tracker before the instruction-first planner sees them.

## Baseline acquisition

- The tracker requires the cue ball and all nine numbered object balls (1–9).
- Every object ball must have a unique number with the provisional OCR identity confidence at or above 0.80.
- The full rack must appear in two consecutive frames with matching identities and centers within a radius-scaled tolerance.
- Until then, inventory is incomplete and the planner returns WAIT.

## Frame-to-frame tracking

- Recognized identities are retained as the authoritative track keys.
- An unread object ball may inherit a remembered number only when it is spatially close to exactly one unused track. The inherited identity confidence is deliberately capped at 0.78.
- Unmatched or ambiguous candidates keep inventory incomplete.
- Duplicate numbers, unexpected numbers, a cue-ball ambiguity, or a previously pocketed number reappearing invalidate the current state.
- Motion is detected by comparing ball centers and the visible identity set. Three consecutive stable frames are required by the planner.

## Pocket confirmation

A missing ball is **not** automatically removed from inventory. The tracker confirms a pocket only when:

1. the ball was observed moving relative to its previous position;
2. its last observed center was inside a high-confidence pocket capture zone; and
3. it is absent in at least two consecutive frames.

If any condition is missing, the inventory remains incomplete and planning is blocked. The tracker also blocks the state if a confirmed pocketed ball appears again; a new rack baseline must be acquired.

## Regression coverage

`BallStateTrackerTest` covers baseline acquisition, incomplete racks, motion/stability reset, spatial identity inheritance, unresolved unknown balls, missing balls away from pockets, false pocket prevention, positive pocket confirmation, reappearing pocketed balls, duplicate OCR identities and reset behavior.

This is a conservative temporal state model, not a trained multi-object tracker. It still needs validation against more labelled Plato recordings, occlusion, simultaneous collisions, fast movement, slow residual motion, and real-device screenshots. OCR confidence remains provisional. The Android service remains **DRY-RUN ONLY**.
