package com.cherunika.chernika;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShotPlannerTest {
    private TableGeometry table() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                100, 100, 611, 1100, 1200, 1600);
    }

    @Test public void plansClearDirectShotToOneOfSixPockets() {
        FrameState frame = new FrameState(1200, 1600);
        frame.table = table();
        frame.cueBall = new Ball(new Vec2(220, 600), 12, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(450, 600), 12, 1, false));
        ShotPlan plan = new ShotPlanner().plan(frame, 1);
        assertNotNull(plan);
        assertEquals(1, plan.target.number);
        assertTrue(plan.powerPercent >= 20 && plan.powerPercent <= 100);
        assertNotNull(plan.pocket.id);
    }

    @Test public void directShotGhostBallUsesBeyondCushionPocketEntryDirection() {
        FrameState frame = new FrameState(1200, 1600);
        frame.table = table();
        frame.cueBall = new Ball(new Vec2(220, 600), 12, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(450, 600), 12, 1, false));

        ShotPlan plan = new ShotPlanner().plan(frame, 1);
        assertNotNull(plan);
        assertFalse(plan.bankShot);
        Vec2 intendedDirection = plan.pocket.aimPoint
                .subtract(plan.target.center).normalized();
        Vec2 ghostBallDirection = plan.target.center
                .subtract(plan.aimPoint).normalized();
        assertEquals(1.0, intendedDirection.dot(ghostBallDirection), 1e-6);
    }

    private FrameState scaledFrame(double scale) {
        int screenWidth = (int) Math.round(1200 * scale);
        int screenHeight = (int) Math.round(1600 * scale);
        FrameState frame = new FrameState(screenWidth, screenHeight);
        frame.table = PlatoTableCalibration.fromDetectedFeltBounds(
                100 * scale, 100 * scale, 611 * scale, 1100 * scale,
                screenWidth, screenHeight);
        frame.cueBall = new Ball(new Vec2(220 * scale, 600 * scale), 12 * scale, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(450 * scale, 600 * scale), 12 * scale, 1, false));
        return frame;
    }

    @Test public void shotScoreAndPowerAreResolutionIndependent() {
        ShotPlanner planner = new ShotPlanner();
        ShotPlan reference = planner.plan(scaledFrame(1.0), 1);
        ShotPlan doubled = planner.plan(scaledFrame(2.0), 1);

        assertNotNull(reference);
        assertNotNull(doubled);
        assertEquals(reference.pocket.id, doubled.pocket.id);
        assertEquals(reference.score, doubled.score, 1e-9);
        assertEquals(reference.powerPercent, doubled.powerPercent, 1e-9);
    }

    @Test public void breakAlwaysTargetsTheOneBallNotTheBallNearestToCue() {
        FrameState frame = new FrameState(611, 1024);
        frame.table = PlatoTableCalibration.fromDetectedFeltBounds(
                112, 159, 495, 904, 611, 1024);
        // This deliberately makes the purple 4-ball closer to the cue than #1.
        frame.cueBall = new Ball(new Vec2(140, 420), 11.5, 0, true);
        frame.balls.add(frame.cueBall);
        int[] numbers = {8, 6, 7, 4, 9, 5, 2, 3, 1};
        double[][] centers = {
                {306.5, 274.5}, {294.5, 294.5}, {318.5, 294.5},
                {283.5, 314.5}, {306.5, 314.5}, {329.5, 314.5},
                {294.5, 334.5}, {318.5, 334.5}, {306.5, 354.5}
        };
        for (int i = 0; i < numbers.length; i++) {
            frame.balls.add(new Ball(new Vec2(centers[i][0], centers[i][1]),
                    11.5, numbers[i], false, numbers[i], 0.80));
        }

        ShotPlan plan = new ShotPlanner().planBreak(frame);
        assertNotNull(plan);
        assertTrue(plan.breakShot);
        assertEquals(1, plan.target.number);
        assertEquals(306.5, plan.target.center.x, 1e-6);
        assertEquals(354.5, plan.target.center.y, 1e-6);
    }

    @Test public void highConfidenceRackTemplateIdentityIsNotOverwrittenByTinyBallOcr() {
        Ball one = new Ball(new Vec2(306.5, 354.5), 11.5, 1, false, 1, 0.55);
        one.applyOcrNumber(9);
        assertEquals(1, one.number);
    }

    @Test public void returnsNoShotWhenTargetNumberIsUnknown() {
        FrameState frame = new FrameState(1200, 1600);
        frame.table = table();
        frame.cueBall = new Ball(new Vec2(220, 600), 12, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(450, 600), 12, 0, false));
        assertNull(new ShotPlanner().plan(frame, 1));
    }
}
