# Chernika — match-level PPO and snapshot self-play

## What this phase adds

\`chernika_rl.match_env.ChernikaMatchEnv\` wraps the existing Plato-calibrated
multi-ball simulator in a Gymnasium environment where one agent controls an
entire 9-ball rack. It is no longer a one-shot classification task or a
teacher-generated trajectory benchmark.

- Observation: the existing 52-value state (cue, numbered balls, legal-ball
  one-hot, six pockets, break flag and shot progress).
- Action: the existing two-value continuous control (normalized angle offset
  and normalized power). The mapping is deliberately unchanged for Android
  compatibility.
- Opponent: physics-teacher greedy or two-ply lookahead, or an optional frozen
  PPO checkpoint.
- Turn logic: a legal pot retains the shooter's turn; a legal miss passes it.
- Fouls: ordinary ball-in-hand anywhere; Plato's illegal no-pot break rerack;
  break ball-in-hand behind the provisional head string; and 9-ball respotting
  are delegated to the existing Plato-specific environment and placement
  helper.
- Termination: legal 9-ball win, opponent win, shot limit, or a bounded
  opponent-turn guard.

The first curriculum version has the agent break every rack. Break-side
alternation and an Elo-managed opponent league are subsequent milestones.

## Train against the physics teacher

From the \`training/\` directory:

\`\`\`bash
python -m chernika_rl.train_match_ppo \\
  --total-timesteps 200000 \\
  --n-envs 2 \\
  --n-steps 256 \\
  --batch-size 256 \\
  --opponent-strategy teacher_greedy \\
  --output artifacts/nine_ball_match_ppo
\`\`\`

For a stronger but slower baseline, use \`--opponent-strategy teacher_lookahead\`.

## Snapshot self-play

After training a first policy, train a new version against a frozen copy of
that policy while continuing from its weights:

\`\`\`bash
python -m chernika_rl.train_match_ppo \\
  --resume-model artifacts/nine_ball_match_ppo.zip \\
  --opponent-model artifacts/nine_ball_match_ppo.zip \\
  --total-timesteps 100000 \\
  --n-envs 2 \\
  --n-steps 256 \\
  --batch-size 256 \\
  --output artifacts/nine_ball_match_ppo_round_2
\`\`\`

Repeat with the latest checkpoint as both \`--resume-model\` and the frozen
\`--opponent-model\` to create a sequence of policy snapshots. A supplied
opponent checkpoint is held fixed during that training run; it is not updated
inside the environment.

## Evaluate complete racks

\`\`\`bash
python -m chernika_rl.evaluate_match_ppo \\
  --model artifacts/nine_ball_match_ppo.zip \\
  --episodes 100 \\
  --seed 96000 \\
  --opponent-strategy teacher_greedy \\
  --output artifacts/nine_ball_match_ppo_evaluation.json
\`\`\`

To compare a newer policy against an earlier snapshot, pass
\`--opponent-model path/to/older_policy.zip\`. The report includes agent/opponent
win rates, a Wilson 95% interval for the agent win rate, rack completion,
shot counts, reward and per-rack outcomes.

## Reward design

The initial reward is intentionally simple and auditable:

- small positive reward for a legal shot and correct first contact;
- positive reward per legally pocketed object ball;
- large positive reward for legally pocketing the 9;
- penalty for a foul, with extra penalty for scratch/off-table outcomes;
- penalty when the opponent pockets balls and a large penalty if the opponent
  wins;
- a small positive signal when the opponent fouls.

This is a starting reward, not a claim of optimal reward shaping. Training
reports must be compared against the physics teacher on identical held-out
seeds before a policy is promoted.

## Important limitations and promotion gates

1. The Python physics mirror still omits spin, throw, masse and cloth
   anisotropy. Do not expose spin actions to PPO until the simulator models it.
2. The head-string coordinate is provisional and must be measured from the
   actual Plato white line before claiming pixel-exact break-foul placement.
3. The first PPO curriculum always gives the agent the break. Alternating
   break order and a diverse Elo-rated snapshot league are not yet included.
4. A frozen PPO opponent is supported, but automatic league scheduling,
   checkpoint rotation and Elo tracking remain future work.
5. Gameplay video OCR announcements and victory messages are weak labels.
   Exact ball coordinates, cue angle, power, spin and shot ownership must be
   frame-reviewed before those frames are added as supervised ground truth.
6. Simulator win rates are not live Plato win rates. Before Android promotion,
   require Python/Java golden-shot agreement, replay trajectory agreement,
   held-out match evaluation and manual device validation.

The PPO policy is a research artifact until every promotion gate passes. It
must not automatically fire live shots merely because training completed.

## User-recording intake

Use \`chernika_rl.recording_identity.identify_user_recording(ocr_texts,
known_usernames)\` to tag recordings using a private, user-supplied username
list. The list is intentionally not stored in this public repository. A match
identifies the recording collection, not the player who took each individual
shot. Keep the raw video local and frame-review every proposed training label;
OCR target announcements and win banners are weak labels only. See
\`training/recordings/README.md\` for the annotation rules.
