package com.cherunika.chernika;

/**
 * Runs a candidate ShotPlan through PoolPhysics and reports whether the
 * simulated first contact and pocket outcome satisfy the 9-ball objective.
 *
 * This is diagnostic in v1: live aiming remains on the calibrated geometric
 * planner until friction, power and cushion constants are tuned from device
 * recordings.
 */
final class PhysicsShotEvaluator {
    static final class Evaluation {
        final PoolPhysics.Result simulation;
        final boolean legalFirstContact;
        final boolean targetPocketed;
        final boolean ninePocketed;
        final double quality;

        Evaluation(PoolPhysics.Result simulation, boolean legalFirstContact,
                   boolean targetPocketed, boolean ninePocketed, double quality) {
            this.simulation = simulation;
            this.legalFirstContact = legalFirstContact;
            this.targetPocketed = targetPocketed;
            this.ninePocketed = ninePocketed;
            this.quality = quality;
        }
    }

    private final PoolPhysics physics;

    PhysicsShotEvaluator() {
        this(new PoolPhysics());
    }

    PhysicsShotEvaluator(PoolPhysics physics) {
        if (physics == null) throw new IllegalArgumentException("physics is required");
        this.physics = physics;
    }

    Evaluation evaluate(FrameState frame, ShotPlan plan, int legalBall, int objectiveBall) {
        if (frame == null || frame.cueBall == null || plan == null || plan.aimPoint == null) return null;
        Vec2 direction = plan.aimPoint.subtract(frame.cueBall.center).normalized();
        if (direction.length() < 1e-8) return null;

        PoolPhysics.Result simulation = plan.breakShot
                ? physics.simulateBreak(frame, direction, plan.powerPercent)
                : physics.simulate(frame, direction, plan.powerPercent);
        boolean legalFirstContact = simulation.firstContactBall != null
                && simulation.firstContactBall.number == legalBall;
        boolean targetPocketed = simulation.pocketed(plan.target);
        boolean ninePocketed = objectiveBall == 9 && simulation.pocketedNumber(9);

        double quality = 0.0;
        if (legalFirstContact) quality += 2.0;
        if (targetPocketed) quality += 5.0;
        if (ninePocketed) quality += 8.0;
        if (simulation.cueScratch) quality -= 10.0;
        if (simulation.anyBallOffTable) quality -= 8.0;

        return new Evaluation(simulation, legalFirstContact,
                targetPocketed, ninePocketed, quality);
    }
}
