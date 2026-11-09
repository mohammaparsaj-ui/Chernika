package com.cherunika.chernika;

import org.junit.Test;

import static org.junit.Assert.*;

public class PhysicsShotEvaluatorTest {
    @Test public void evaluatorReportsLegalFirstContactAndPocketedTarget() {
        FrameState frame = new FrameState(1200, 1600);
        frame.table = PlatoTableCalibration.fromDetectedFeltBounds(
                100, 100, 611, 1100, 1200, 1600);
        Pocket pocket = frame.table.pocket(Pocket.Id.MIDDLE_RIGHT);
        frame.cueBall = new Ball(new Vec2(
                frame.table.right - 145, pocket.center.y),
                frame.table.referenceBallRadius, 0, true);
        Ball target = new Ball(new Vec2(
                frame.table.right - 95, pocket.center.y),
                frame.table.referenceBallRadius, 1, false);
        frame.balls.add(frame.cueBall);
        frame.balls.add(target);

        Vec2 ghostBall = new Vec2(
                target.center.x - target.radius * 2.0, target.center.y);
        ShotPlan plan = new ShotPlan(target, ghostBall, pocket,
                40.0, 0.0, false, false, false);

        PhysicsShotEvaluator.Evaluation evaluation =
                new PhysicsShotEvaluator().evaluate(frame, plan, 1, 0);

        assertNotNull(evaluation);
        assertTrue(evaluation.legalFirstContact);
        assertTrue(evaluation.targetPocketed);
        assertTrue(evaluation.quality > 0);
        assertFalse(evaluation.simulation.cueScratch);
    }

    @Test public void evaluatorRejectsWrongFirstContact() {
        FrameState frame = new FrameState(1200, 1600);
        frame.table = PlatoTableCalibration.fromDetectedFeltBounds(
                100, 100, 611, 1100, 1200, 1600);
        double y = frame.table.top + 400;
        frame.cueBall = new Ball(new Vec2(frame.table.left + 150, y),
                frame.table.referenceBallRadius, 0, true);
        Ball blocker = new Ball(new Vec2(frame.table.left + 200, y),
                frame.table.referenceBallRadius, 2, false);
        Ball target = new Ball(new Vec2(frame.table.left + 260, y),
                frame.table.referenceBallRadius, 1, false);
        frame.balls.add(frame.cueBall);
        frame.balls.add(blocker);
        frame.balls.add(target);

        ShotPlan plan = new ShotPlan(target,
                new Vec2(target.center.x - target.radius * 2.0, y),
                frame.table.pocket(Pocket.Id.MIDDLE_RIGHT),
                35.0, 0.0, false, false, false);

        PhysicsShotEvaluator.Evaluation evaluation =
                new PhysicsShotEvaluator().evaluate(frame, plan, 1, 0);

        assertNotNull(evaluation);
        assertFalse(evaluation.legalFirstContact);
        assertSame(blocker, evaluation.simulation.firstContactBall);
    }
}
