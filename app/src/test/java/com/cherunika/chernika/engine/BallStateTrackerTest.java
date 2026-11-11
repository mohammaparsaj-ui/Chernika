package com.cherunika.chernika.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BallStateTrackerTest {
    private List<PocketObservation> pockets() {
        return Arrays.asList(
                pocket(0, 0), pocket(500, 0), pocket(1000, 0),
                pocket(0, 500), pocket(500, 500), pocket(1000, 500));
    }

    private PocketObservation pocket(double x, double y) {
        return new PocketObservation(new Point2(x, y), 34, 34, 0.94);
    }

    private BallObservation cue(double x, double y) {
        return new BallObservation(new Point2(x, y), 10, 0, true, 0.98);
    }

    private BallObservation ball(int number, double x, double y) {
        return new BallObservation(new Point2(x, y), 10, number, false, 0.94, 0.96);
    }

    private BallObservation unread(double x, double y) {
        return new BallObservation(new Point2(x, y), 10, 0, false, 0.94, 0.0);
    }

    private List<BallObservation> partialScene() {
        return new ArrayList<>(Arrays.asList(
                cue(120, 250), ball(1, 300, 210), ball(3, 500, 260),
                ball(7, 700, 330), unread(600, 420)));
    }

    @Test public void partialPostBreakSceneCanStabilizeWithoutFullRack() {
        BallStateTracker state = new BallStateTracker();
        List<BallObservation> scene = partialScene();

        BallStateTracker.Result first = state.update(scene, pockets());
        assertFalse(first.inventoryComplete);
        assertFalse(first.baselineEstablished);
        assertEquals(0, first.consecutiveStableFrames);

        BallStateTracker.Result second = state.update(scene, pockets());
        assertTrue(second.baselineEstablished);
        assertEquals(1, second.consecutiveStableFrames);

        BallStateTracker.Result third = state.update(scene, pockets());
        assertEquals(2, third.consecutiveStableFrames);
        assertFalse(third.ballsMoving);
        assertEquals(5, third.balls.size());
    }

    @Test public void unreadBallsAreRetainedAsPhysicalObstacles() {
        BallStateTracker state = new BallStateTracker();
        BallStateTracker.Result result = state.update(partialScene(), pockets());

        assertEquals(5, result.balls.size());
        boolean foundUnread = false;
        for (BallObservation ball : result.balls) {
            if (!ball.cueBall && ball.number == 0) foundUnread = true;
        }
        assertTrue(foundUnread);
        assertFalse(result.inventoryComplete);
    }

    @Test public void unreadBallInSamePositionInheritsUniquePriorIdentity() {
        BallStateTracker state = new BallStateTracker();
        List<BallObservation> first = new ArrayList<>(Arrays.asList(
                cue(120, 250), ball(4, 200, 200), ball(8, 700, 300)));
        state.update(first, pockets());

        List<BallObservation> second = new ArrayList<>(Arrays.asList(
                cue(120, 250), unread(200.5, 200.5), ball(8, 700, 300)));
        BallStateTracker.Result result = state.update(second, pockets());

        BallObservation resolved = null;
        for (BallObservation item : result.balls) {
            if (!item.cueBall && item.center.distance(new Point2(200.5, 200.5)) < 1) {
                resolved = item;
            }
        }
        assertNotNull(resolved);
        assertEquals(4, resolved.number);
        assertEquals(0.82, resolved.identityConfidence, 0.0001);
    }

    @Test public void unknownBallFarFromKnownTracksRemainsNumberZero() {
        BallStateTracker state = new BallStateTracker();
        state.update(Arrays.asList(cue(120, 250), ball(4, 200, 200)), pockets());
        BallStateTracker.Result result = state.update(
                Arrays.asList(cue(120, 250), unread(600, 420)), pockets());

        assertEquals(2, result.balls.size());
        assertEquals(0, result.balls.get(1).number);
        assertFalse(result.inventoryComplete);
    }

    @Test public void pocketedBallIdentityIsNotInheritedFromAStalePosition() {
        BallStateTracker state = new BallStateTracker();
        state.update(Arrays.asList(cue(120, 250), ball(4, 200, 200)), pockets());
        state.update(Arrays.asList(cue(120, 250), ball(4, 200, 200)), pockets());

        // Ball 4 disappears; the immediately previous scene no longer contains it.
        state.update(Arrays.asList(cue(120, 250)), pockets());
        BallStateTracker.Result later = state.update(
                Arrays.asList(cue(120, 250), unread(200, 200)), pockets());

        assertEquals(0, later.balls.get(1).number);
    }

    @Test public void stableCounterResetsWhenAnyBallMoves() {
        BallStateTracker state = new BallStateTracker();
        List<BallObservation> scene = partialScene();
        state.update(scene, pockets());
        state.update(scene, pockets());
        BallStateTracker.Result stable = state.update(scene, pockets());
        assertEquals(2, stable.consecutiveStableFrames);

        List<BallObservation> moved = new ArrayList<>(scene);
        moved.set(2, ball(3, 530, 260));
        BallStateTracker.Result moving = state.update(moved, pockets());
        assertTrue(moving.ballsMoving);
        assertEquals(0, moving.consecutiveStableFrames);
    }

    @Test public void aPocketedBallDisappearingChangesSceneButDoesNotBlockLaterStability() {
        BallStateTracker state = new BallStateTracker();
        List<BallObservation> before = new ArrayList<>(Arrays.asList(
                cue(120, 250), ball(1, 300, 210), ball(2, 500, 260), ball(9, 700, 330)));
        state.update(before, pockets());
        state.update(before, pockets());

        List<BallObservation> after = new ArrayList<>(Arrays.asList(
                cue(120, 250), ball(2, 500, 260), ball(9, 700, 330)));
        BallStateTracker.Result changed = state.update(after, pockets());
        assertTrue(changed.ballsMoving);
        assertEquals(0, changed.consecutiveStableFrames);

        state.update(after, pockets());
        BallStateTracker.Result stable = state.update(after, pockets());
        assertFalse(stable.ballsMoving);
        assertEquals(2, stable.consecutiveStableFrames);
        assertEquals(3, stable.balls.size());
    }

    @Test public void duplicateNumbersAreReportedButBallsRemainInScene() {
        BallStateTracker state = new BallStateTracker();
        List<BallObservation> duplicate = Arrays.asList(
                cue(120, 250), ball(4, 200, 200), ball(4, 700, 300));
        BallStateTracker.Result result = state.update(duplicate, pockets());

        assertFalse(result.inventoryComplete);
        assertEquals(3, result.balls.size());
        assertTrue(result.reason.contains("Duplicate"));
    }

    @Test public void cueBallMissingResetsSceneStability() {
        BallStateTracker state = new BallStateTracker();
        state.update(partialScene(), pockets());
        state.update(partialScene(), pockets());

        BallStateTracker.Result result = state.update(
                Arrays.asList(ball(1, 300, 210), ball(3, 500, 260)), pockets());
        assertFalse(result.inventoryComplete);
        assertTrue(result.ballsMoving);
        assertEquals(0, result.consecutiveStableFrames);
        assertTrue(result.reason.contains("Cue ball"));
    }

    @Test public void resetClearsIdentityMemoryAndStableFrames() {
        BallStateTracker state = new BallStateTracker();
        List<BallObservation> scene = partialScene();
        state.update(scene, pockets());
        state.update(scene, pockets());
        state.reset();

        BallStateTracker.Result result = state.update(scene, pockets());
        assertFalse(result.baselineEstablished);
        assertEquals(0, result.consecutiveStableFrames);
        assertTrue(result.pocketedNumbers.isEmpty());
    }
}
