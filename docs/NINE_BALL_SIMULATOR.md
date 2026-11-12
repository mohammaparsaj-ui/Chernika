# Chernika — multi-ball 9-ball practice simulator

## Purpose

\`ChernikaNineBallEnv\` is the first multi-ball training environment. It moves beyond the Phase 1 cue-ball/one-target task and uses the same measured Plato table and Python physics port for the cue ball plus all visible object balls.

It is an offline single-agent practice environment. It is not a complete match engine and is not connected to Android touch controls.

## Initial diamond rack

The rack uses the same five-row 1-2-3-2-1 lattice as the Android \`DiamondRackVisionDetector\`:

- Ball 1 is fixed at the lower/front apex, closest to the cue ball.
- Ball 9 is fixed in the center slot.
- Balls 2–8 are shuffled between the remaining seven slots.
- The rack center is placed at 20% of the playable table height from the top, matching the current Plato rack-search region.
- Cue-ball position is sampled in the lower table area.

Ball centers use the calibrated reference radius of 11.5 px and the same normalized screen-space coordinates as \`physics.py\`.

## Observation and action

The observation is a fixed 52-value vector:

| Fields | Values | Meaning |
|---|---:|---|
| Cue position | 2 | Normalized cue-ball x/y |
| Object-ball slots | 27 | For balls 1–9: normalized x/y and active mask |
| Legal-ball identity | 9 | One-hot encoding of the lowest remaining ball |
| Pocket anchors | 12 | Six measured pocket x/y coordinates |
| Episode state | 2 | Break-pending flag and normalized shot count |
| **Total** | **52** | |

The action remains continuous and compatible with the Phase 1 controller:

1. Angle offset relative to the cue-to-lowest-ball direction, normalized to [-1, 1] and multiplied by pi.
2. Power, normalized to [-1, 1] and mapped to the Plato UI range up to 97.8%.

## Implemented 9-ball rules

- The lowest numbered remaining object ball is the legal first-contact target.
- A wrong first contact or no object-ball contact is a foul.
- Cue-ball scratch and any ball leaving the table are fouls.
- On an ordinary shot, if no object ball is pocketed, at least one cushion contact must occur after the first legal contact.
- On the break, if no object ball is pocketed, at least four distinct object balls must contact a cushion after the first legal contact.
- A legally pocketed 9-ball ends the episode with a win.
- Pocketed balls are removed from the state; remaining balls retain their simulated final positions. The legal lowest ball is recomputed after every shot.

\`SimulationResult\` now records post-contact cushion-contact count and the distinct object-ball numbers that reached a cushion. This avoids counting a rail bounce that happened before the first ball contact as satisfying the ordinary-shot or break requirement.

## Tests

The test suite covers:

- Rack contains each number 1–9 exactly once, with 1 at the front apex and 9 in the center.
- Wrong first contact is rejected.
- A shot with no pocket and no post-contact rail is rejected.
- A legal pot removes the ball and advances the legal target.
- A legal nonterminal sequence respects the maximum-shot truncation.
- Physics records distinct post-contact object-ball cushion events.

## Known limitations — not deployment-ready

- This is a single-agent practice task; it does not simulate opponent turns or full match scoring.
- Ball-in-hand placement and spotting the 9 after a foul are not yet modelled.
- Spin, throw, masse, cloth anisotropy and detailed jaw/rail effects remain outside the current physics model.
- The simulator can resolve multi-ball collisions, but there is not yet a dedicated multi-ball teacher or trained nine-ball policy.
- The current single-ball imitation policy is not compatible with the 52-value observation and must not be reused as a nine-ball policy.
- No Android APK, live screen capture, or gesture automation has been changed by this phase.

## Next gate

Build and validate a multi-ball teacher against legal first-contact, pocketing and foul rules; generate post-break states; train a separate 52-input policy; then compare it with a physics baseline on held-out full-table states. Only after Python/Java golden-shot agreement and real Plato replay validation should any learned controller be considered for Android integration.


## Multi-ball teacher training prototype

The 52-input policy is separate from the Phase 1 single-ball checkpoint. The current teacher samples direct ghost-ball paths from the lowest numbered ball to the six measured pockets, simulates the cue plus all active object balls, and rejects any candidate that violates the shared 9-ball rules. A pocket-restricted mode supports neural top-k ranking followed by full-table physics verification.

Generate randomized stationary training states (3–9 object balls, with the 9 present):

    python -m chernika_rl.generate_nine_ball_teacher_data --states 700 --seed 70000

Train a separate 52-input behavioral-cloning model:

    python -m chernika_rl.train_imitation --data artifacts/nine_ball_teacher_dataset.npz --epochs 80 --min-demonstrations 50 --output artifacts/nine_ball_policy.pt

Evaluate random, teacher, raw imitation and neural top-3 + physics hybrid policies on held-out randomized states:

    python -m chernika_rl.evaluate_nine_ball_imitation --model artifacts/nine_ball_policy.pt --episodes 150 --seed 80000

The random state distribution is a controlled first experiment, not a reconstruction of post-break gameplay. A later dataset must be generated from validated break simulations and real Plato replays before live integration.
