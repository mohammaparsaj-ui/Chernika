# Chernika — match-level PPO, behavior cloning and snapshot self-play

## What this phase adds

chernika_rl.match_env.ChernikaMatchEnv wraps the existing Plato-calibrated
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

## Why behavior-cloning initialization comes before PPO

A random continuous PPO actor has to discover both legal first contact and
useful power/angle settings from sparse match rewards. The earlier smoke run
only established that the training and evaluation scripts execute; it did not
establish learning. The match demonstration stage now generates legal actions
from complete teacher-vs-teacher racks and pretrains an actor before PPO.

The dataset includes:
- all legal actions in both player seats, including legal safety shots and
  break actions;
- the pre-shot 52-value state and normalized angle/power action;
- rack-level group IDs so validation never splits shots from the same rack;
- sample metadata for break, pocket outcome, first contact and shot type;
- horizontal reflection augmentation, which mirrors x coordinates and
  reverses signed angle offset while leaving power unchanged.

Illegal teacher actions are excluded from the behavior-cloning target set.
Human video OCR events are not converted into angle/power targets: the current
recording annotations do not contain fully reviewed pre-shot coordinates and
aim/power labels for every shot.

## Generate and pretrain demonstrations

From the training/ directory:

~~~bash
python -m chernika_rl.generate_match_demonstrations \
  --racks 1000 \
  --seed 98000 \
  --max-shots 48 \
  --depth 2 \
  --output artifacts/nine_ball_match_demonstrations.npz

python -m chernika_rl.train_match_behavior_cloning \
  --data artifacts/nine_ball_match_demonstrations.npz \
  --epochs 80 \
  --batch-size 128 \
  --output artifacts/nine_ball_match_bc.pt
~~~

The generator uses teacher lookahead on both sides. The default output includes
a horizontal mirror of each legal action. The train/validation split is grouped
by rack, not by individual shot. Review the generated JSON metadata and
validation action MAE before using the checkpoint.

## Train PPO from the pretrained actor

~~~bash
python -m chernika_rl.train_match_ppo \
  --bc-checkpoint artifacts/nine_ball_match_bc.pt \
  --total-timesteps 200000 \
  --n-envs 2 \
  --n-steps 256 \
  --batch-size 256 \
  --opponent-strategy teacher_greedy \
  --output artifacts/nine_ball_match_ppo
~~~

The BC actor initializes the PPO policy network and action head. The critic is
initialized independently. The initial continuous-action log standard
deviation is set conservatively so PPO begins near the demonstrated actions
while retaining exploration.

For a stronger but slower baseline, use --opponent-strategy teacher_lookahead.

## Snapshot self-play

After training a first policy, train a new version against a frozen copy of
that policy while continuing from its weights:

~~~bash
python -m chernika_rl.train_match_ppo \
  --resume-model artifacts/nine_ball_match_ppo.zip \
  --opponent-model artifacts/nine_ball_match_ppo.zip \
  --total-timesteps 100000 \
  --n-envs 2 \
  --n-steps 256 \
  --batch-size 256 \
  --output artifacts/nine_ball_match_ppo_round_2
~~~

Repeat with the latest checkpoint as both --resume-model and the frozen
--opponent-model to create a sequence of policy snapshots. A supplied
opponent checkpoint is held fixed during that training run; it is not updated
inside the environment.

## Evaluate complete racks

~~~bash
python -m chernika_rl.evaluate_match_ppo \
  --model artifacts/nine_ball_match_ppo.zip \
  --episodes 100 \
  --seed 96000 \
  --opponent-strategy teacher_greedy \
  --output artifacts/nine_ball_match_ppo_evaluation.json
~~~

To compare a newer policy against an earlier snapshot, pass
--opponent-model path/to/older_policy.zip. The report includes agent/opponent
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

Use chernika_rl.recording_identity.identify_user_recording(ocr_texts,
known_usernames) to tag recordings using a private, user-supplied username
list. The list is intentionally not stored in this public repository. A match
identifies the recording collection, not the player who took each individual
shot. Keep the raw video local and frame-review every proposed training label;
OCR target announcements and win banners are weak labels only. See
training/recordings/README.md for the annotation rules.


## Real-game replay diagnostic

The anonymized October 30 user recording is retained as one held-out rack
review candidate under \`training/recordings/reviewed/\`. It shows a complete
break-and-run: two balls on the break, then the 7, 1, 2, 3, 6, 8 and 9.

Run the diagnostic from the training directory:

~~~bash
python -m chernika_rl.validate_recording_review \\
  --manifest recordings/reviewed/20261030_break_and_run_candidate.json \\
  --output artifacts/recording_replay_diagnostic.json
~~~

The report compares the reviewed angle/power estimates with the current
physics mirror and searches nearby power values for the observed pocket set.
It is a calibration diagnostic only. The single rack is not included in
behavior-cloning or PPO training, and no simulator mismatch is hidden or
automatically relabeled.
