# Chernika — Teacher-Guided Shot Learning

The first SAC-only experiment learned legal first contact but pocketed only 2.5% of 200 held-out single-ball states, below the 32.5% geometric/physics baseline. Do not deploy that checkpoint.

This phase adds **behavioral cloning** before another RL attempt:

1. Sample reproducible cue/target positions on the calibrated Plato table.
2. Run the ghost-ball + power-sweep teacher against the current 2-D physics simulator.
3. Save the teacher's selected pocket, angle offset, power, score and outcome.
4. Train a small neural network with a six-class pocket head and a two-value continuous action head.
5. Evaluate the student on a disjoint seed range against the teacher and random policy.

The teacher planner intentionally mirrors ChernikaSingleBallEnv.heuristic_action. A parity test prevents the two implementations from silently diverging.

## Run locally

From the training/ directory:

    python -m chernika_rl.generate_teacher_data --states 4000 --seed 40000
    python -m chernika_rl.train_imitation --data artifacts/teacher_dataset.npz --epochs 80
    python -m chernika_rl.evaluate_imitation --model artifacts/imitation_policy.pt --episodes 300 --seed 60000

The default GitHub Actions workflow runs the same pipeline and uploads the dataset, model, metrics and held-out report.

## Deployment gate

The imitation model is an offline research artifact. It must beat the geometric teacher on the held-out benchmark, then pass physics calibration and replay validation against actual Plato recordings. No model in this phase is connected to Android touch controls or live matches.

## Important limitation

The simulator's friction, restitution, pocket capture and power mapping are provisional. A student can only learn what its teacher and simulator represent. Validate the Python physics port against Java golden shots and real recorded outcomes before interpreting benchmark scores as real-world playing strength.
