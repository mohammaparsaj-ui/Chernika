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

## Hybrid control result

The first hybrid benchmark used the neural network's top-ranked pocket, then deterministic ghost-ball geometry and a six-value physics power sweep.

| Policy | Pocket rate |
|---|---:|
| Physics teacher | 28.0% |
| Raw neural student | 2.33% |
| Hybrid top-1 | 25.0% |

The hybrid achieved a 99.33% first-contact rate and 0.67% scratch rate on the same 300 held-out states. It is only three percentage points behind the teacher, and far better than raw action regression.

## Top-2 pocket search

The next experiment asks the classifier for its two highest-ranked pockets, runs the physics shot controller for both, and selects the better simulated result. This tests whether a small increase in candidate search can recover cases where the top-1 pocket label is wrong.

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
