package com.cherunika.chernika;

import org.junit.Test;

import static org.junit.Assert.*;

public class PoolPhysicsTest {
    private TableGeometry table() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                112, 159, 495, 904, 611, 1024);
    }

    private FrameState frameWithCue(Vec2 cuePosition) {
        FrameState frame = new FrameState(611, 1024);
        frame.table = table();
        frame.cueBall = new Ball(cuePosition, frame.table.referenceBallRadius, 0, true);
        frame.balls.add(frame.cueBall);
        return frame;
    }

    @Test public void rollingBallSlowsToRestUnderClothFriction() {
        FrameState frame = frameWithCue(new Vec2(
                frameTableLeft() + 150, frameTableTop() + 250));

        PoolPhysics.Result result = new PoolPhysics().simulate(
                frame, new Vec2(1, 0), 5.0);

        Vec2 end = result.finalPosition(frame.cueBall);
        assertNotNull(end);
        assertTrue(end.x > frame.cueBall.center.x);
        assertTrue(end.x < frame.cueBall.center.x + 250);
        assertEquals(0.0, velocityOf(result, frame.cueBall).length(), 0.1);
        assertTrue(result.simulatedSeconds > 0.0);
        assertTrue(result.simulatedSeconds < 8.0);
    }

    @Test public void headOnEqualMassCollisionTransfersMotionToObjectBall() {
        FrameState frame = frameWithCue(new Vec2(
                frameTableLeft() + 170, frameTableTop() + 400));
        Ball target = new Ball(new Vec2(
                frame.cueBall.center.x + 50, frame.cueBall.center.y),
                frame.cueBall.radius, 1, false);
        frame.balls.add(target);

        PoolPhysics.Result result = new PoolPhysics().simulate(
                frame, new Vec2(1, 0), 10.0);

        assertSame(target, result.firstContactBall);
        assertTrue(result.ballCollisions >= 1);
        assertTrue(result.finalPosition(target).x > target.center.x + 50);
        assertTrue(result.finalPosition(frame.cueBall).x < result.finalPosition(target).x);
    }

    @Test public void ballEnteringSidePocketIsCaptured() {
        TableGeometry table = table();
        Pocket pocket = table.pocket(Pocket.Id.MIDDLE_RIGHT);
        FrameState frame = frameWithCue(new Vec2(
                table.right - 145, pocket.center.y));
        Ball target = new Ball(new Vec2(
                table.right - 95, pocket.center.y),
                frame.cueBall.radius, 1, false);
        frame.balls.add(target);

        PoolPhysics.Result result = new PoolPhysics().simulate(
                frame, new Vec2(1, 0), 40.0);

        assertSame(target, result.firstContactBall);
        assertTrue(result.pocketed(target));
        assertTrue(result.pocketedNumber(1));
        assertFalse(result.cueScratch);
    }

    @Test public void ballEnteringCornerPocketIsCaptured() {
        TableGeometry table = table();
        Pocket pocket = table.pocket(Pocket.Id.TOP_LEFT);
        // Approach through the left-rail opening at the corner. This
        // trajectory reaches the left gate before the top cushion gate.
        Vec2 targetPosition = new Vec2(
                pocket.center.x + 22, pocket.center.y + 30);
        FrameState frame = frameWithCue(new Vec2(
                targetPosition.x + 22, targetPosition.y + 30));
        Ball target = new Ball(targetPosition, frame.cueBall.radius, 1, false);
        frame.balls.add(target);

        PoolPhysics.Result result = new PoolPhysics().simulate(
                frame, new Vec2(-22, -30), 40.0);

        assertSame(target, result.firstContactBall);
        assertTrue(result.pocketed(target));
        assertTrue(result.pocketedNumber(1));
    }

    @Test public void cueBallPocketingIsReportedAsScratch() {
        TableGeometry table = table();
        Pocket pocket = table.pocket(Pocket.Id.MIDDLE_RIGHT);
        FrameState frame = frameWithCue(new Vec2(
                table.right - 145, pocket.center.y));

        PoolPhysics.Result result = new PoolPhysics().simulate(
                frame, new Vec2(1, 0), 60.0);

        assertTrue(result.pocketed(frame.cueBall));
        assertTrue(result.cueScratch);
    }

    @Test public void cushionCollisionReflectsVelocityAndCountsRailContact() {
        TableGeometry table = table();
        FrameState frame = frameWithCue(new Vec2(
                table.right - 120, table.top + 250));

        PoolPhysics.Result result = new PoolPhysics().simulate(
                frame, new Vec2(1, 0), 30.0);

        assertTrue(result.cushionContacts >= 1);
        assertTrue(result.finalPosition(frame.cueBall).x < table.right - frame.cueBall.radius);
        assertFalse(result.cueScratch);
    }

    private double frameTableLeft() { return table().left; }
    private double frameTableTop() { return table().top; }

    private Vec2 velocityOf(PoolPhysics.Result result, Ball ball) {
        for (PoolPhysics.BallResult outcome : result.balls) {
            if (outcome.source == ball) return outcome.velocity;
        }
        return new Vec2(0, 0);
    }
}
