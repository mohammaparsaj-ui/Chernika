# Chernika — Teacher-Guided Shot Learning

The first SAC-only experiment learned legal first contact but pocketed only 2.5% of 200 held-out single-ball states, below the 32.5% geometric/physics baseline. Do not deploy that checkpoint.

## First teacher/student experiment

The pipeline generated 3,929 usable demonstrations from 4,000 seeded states (71 were skipped because no legal teacher candidate existed), trained for 80 epochs, and evaluated on 300 disjoint held-out states.

| Policy | Pocket rate | First-contact rate |
|---|---:|---:|
| Random | 0.0% | 6.7% |
| Physics teacher | 28.0% | 99.3% |
| Raw neural student | 2.33% | 98.3% |

The neural pocket classifier agreed with the teacher on 89.9% of states with a valid teacher plan. However, the raw angle/power regression did not reproduce successful shots. The raw student is not a playable policy and must not be deployed.

## Hybrid control experiment

The next experiment separates strategic selection from precise execution:

1. The neural network chooses one of the six measured pockets.
2. A deterministic geometry controller computes the ghost-ball line for that selected pocket.
3. The physics simulator sweeps candidate power values and selects the best outcome.
4. The held-out benchmark compares random, teacher, raw imitation, and hybrid policies on identical seeded states.

This tests whether learned pocket selection can help without asking a neural network to regress a highly sensitive angle and power directly.

## Run locally

From the training/ directory:

    python -m chernika_rl.generate_teacher_data --states 4000 --seed 40000
    python -m chernika_rl.train_imitation --data artifacts/teacher_dataset.npz --epochs 80
    python -m chernika_rl.evaluate_imitation --model artifacts/imitation_policy.pt --episodes 300 --seed 60000

The default GitHub Actions workflow runs the pipeline and uploads the dataset, model, metrics and held-out report.

## Deployment gate

The hybrid policy must first demonstrate a repeatable improvement on held-out states. Then validate the Python physics port against Java golden shots and real Plato recordings. No model in this phase is connected to Android touch controls or live matches.

## Important limitation

The simulator's friction, restitution, pocket capture and power mapping are provisional. A student can only learn what its teacher and simulator represent. This remains a single-cue-ball/one-target-ball research task; it does not yet model the full nine-ball game, spin, multi-ball occlusion, or Android gesture timing.
