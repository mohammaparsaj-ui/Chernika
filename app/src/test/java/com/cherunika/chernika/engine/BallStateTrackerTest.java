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

    private List<BallObservation> rack() {
        List<BallObservation> balls = new ArrayList<>();
        balls.add(cue(120, 250));
        balls.add(ball(1, 650, 210));
        balls.add(ball(2, 670, 230));
        balls.add(ball(3, 690, 250));
        balls.add(ball(4, 200, 200));
        balls.add(ball(5, 710, 270));
        balls.add(ball(6, 730, 250));
        balls.add(ball(7, 750, 230));
        balls.add(ball(8, 770, 250));
        balls.add(ball(9, 790, 270));
        return balls;
    }

    private BallStateTracker freshLockedTracker() {
        BallStateTracker state = new BallStateTracker();
        BallStateTracker.Result first = state.update(rack(), pockets());
        assertFalse(first.inventoryComplete);
        BallStateTracker.Result second = state.update(rack(), pockets());
        assertTrue(second.inventoryComplete);
        assertTrue(second.baselineEstablished);
        assertEquals(2, second.consecutiveStableFrames);
        BallStateTracker.Result third = state.update(rack(), pockets());
        assertEquals(3, third.consecutiveStableFrames);
        return state;
    }

    @Test public void requiresTwoMatchingFullRackFramesBeforeLockingIdentity() {
        BallStateTracker state = new BallStateTracker();
        BallStateTracker.Result first = state.update(rack(), pockets());
        assertFalse(first.inventoryComplete);
        assertFalse(first.baselineEstablished);
        assertTrue(first.reason.contains("Full rack seen once"));

        BallStateTracker.Result second = state.update(rack(), pockets());
        assertTrue(second.inventoryComplete);
        assertTrue(second.baselineEstablished);
        assertEquals(9, second.remainingNumbers.size());
        assertEquals(2, second.consecutiveStableFrames);
    }

    @Test public void partialRackCannotBecomeAnInventoryBaseline() {
        BallStateTracker state = new BallStateTracker();
        List<BallObservation> partial = rack();
        partial.removeIf(ball -> !ball.cueBall && ball.number == 8);
        state.update(partial, pockets());
        BallStateTracker.Result result = state.update(partial, pockets());
        assertFalse(result.baselineEstablished);
        assertFalse(result.inventoryComplete);
        assertTrue(result.reason.contains("all numbered balls 1–9"));
    }

    @Test public void movingRackResetsBaselineConfirmation() {
        BallStateTracker state = new BallStateTracker();
        state.update(rack(), pockets());
        List<BallObservation> moved = rack();
        moved.set(4, ball(4, 220, 200));
        BallStateTracker.Result second = state.update(moved, pockets());
        assertFalse(second.baselineEstablished);
        assertTrue(second.reason.contains("Full rack seen once"));
        BallStateTracker.Result third = state.update(moved, pockets());
        assertTrue(third.baselineEstablished);
    }

    @Test public void stableFrameCounterResetsWhenAnyBallMoves() {
        BallStateTracker state = freshLockedTracker();
        List<BallObservation> moved = rack();
        moved.set(4, ball(4, 220, 200));
        BallStateTracker.Result moving = state.update(moved, pockets());
        assertTrue(moving.ballsMoving);
        assertEquals(0, moving.consecutiveStableFrames);

        BallStateTracker.Result stopped1 = state.update(moved, pockets());
        BallStateTracker.Result stopped2 = state.update(moved, pockets());
        BallStateTracker.Result stopped3 = state.update(moved, pockets());
        assertFalse(stopped1.ballsMoving);
        assertEquals(1, stopped1.consecutiveStableFrames);
        assertEquals(2, stopped2.consecutiveStableFrames);
        assertEquals(3, stopped3.consecutiveStableFrames);
    }

    @Test public void unreadBallInSamePositionInheritsItsRememberedIdentity() {
        BallStateTracker state = freshLockedTracker();
        List<BallObservation> frame = rack();
        frame.set(4, unread(200.5, 200.5));
        BallStateTracker.Result result = state.update(frame, pockets());
        assertTrue(result.inventoryComplete);
        BallObservation resolved = null;
        for (BallObservation ball : result.balls) {
            if (!ball.cueBall && ball.center.distance(new Point2(200.5, 200.5)) < 1) {
                resolved = ball;
            }
        }
        assertNotNull(resolved);
        assertEquals(4, resolved.number);
        assertEquals(0.78, resolved.identityConfidence, 0.0001);
    }

    @Test public void unknownBallFarFromEveryRememberedTrackBlocksInventory() {
        BallStateTracker state = freshLockedTracker();
        List<BallObservation> frame = rack();
        frame.set(4, unread(450, 420));
        BallStateTracker.Result result = state.update(frame, pockets());
        assertFalse(result.inventoryComplete);
        assertTrue(result.ballsMoving);
        assertTrue(result.reason.contains("unresolved identities"));
    }

    @Test public void missingBallAwayFromPocketIsNeverAssumedPocketed() {
        BallStateTracker state = freshLockedTracker();
        List<BallObservation> missing = rack();
        missing.removeIf(ball -> !ball.cueBall && ball.number == 4);
        BallStateTracker.Result first = state.update(missing, pockets());
        BallStateTracker.Result second = state.update(missing, pockets());
        assertFalse(first.inventoryComplete);
        assertFalse(second.inventoryComplete);
        assertFalse(second.pocketedNumbers.contains(4));
        assertEquals(9, second.remainingNumbers.size());
    }

    @Test public void ballNearPocketWithoutObservedMovementIsNotConfirmedPocketed() {
        BallStateTracker state = new BallStateTracker();
        List<BallObservation> nearPocketRack = rack();
        nearPocketRack.set(4, ball(4, 20, 20));
        state.update(nearPocketRack, pockets());
        state.update(nearPocketRack, pockets());
        List<BallObservation> missing = new ArrayList<>(nearPocketRack);
        missing.removeIf(ball -> !ball.cueBall && ball.number == 4);
        state.update(missing, pockets());
        BallStateTracker.Result result = state.update(missing, pockets());
        assertFalse(result.pocketedNumbers.contains(4));
        assertFalse(result.inventoryComplete);
    }

    @Test public void confirmsPocketOnlyAfterMovementIntoPocketAndTwoMissingFrames() {
        BallStateTracker state = freshLockedTracker();
        List<BallObservation> movedNearPocket = rack();
        movedNearPocket.set(4, ball(4, 20, 20));
        BallStateTracker.Result moving = state.update(movedNearPocket, pockets());
        assertTrue(moving.ballsMoving);
        assertTrue(moving.inventoryComplete);

        List<BallObservation> missing = new ArrayList<>(movedNearPocket);
        missing.removeIf(ball -> !ball.cueBall && ball.number == 4);
        BallStateTracker.Result firstMissing = state.update(missing, pockets());
        assertFalse(firstMissing.inventoryComplete);
        assertFalse(firstMissing.pocketedNumbers.contains(4));

        BallStateTracker.Result secondMissing = state.update(missing, pockets());
        assertTrue(secondMissing.inventoryComplete);
        assertTrue(secondMissing.pocketedNumbers.contains(4));
        assertFalse(secondMissing.remainingNumbers.contains(4));
        assertEquals(8, secondMissing.remainingNumbers.size());
    }

    @Test public void reappearingPocketedBallInvalidatesTheRememberedState() {
        BallStateTracker state = freshLockedTracker();
        List<BallObservation> movedNearPocket = rack();
        movedNearPocket.set(4, ball(4, 20, 20));
        state.update(movedNearPocket, pockets());
        List<BallObservation> missing = new ArrayList<>(movedNearPocket);
        missing.removeIf(ball -> !ball.cueBall && ball.number == 4);
        state.update(missing, pockets());
        state.update(missing, pockets());

        BallStateTracker.Result reappeared = state.update(movedNearPocket, pockets());
        assertFalse(reappeared.inventoryComplete);
        assertTrue(reappeared.reason.contains("previously pocketed"));
    }

    @Test public void duplicateOcrIdentityInvalidatesFrame() {
        BallStateTracker state = freshLockedTracker();
        List<BallObservation> frame = rack();
        frame.set(4, ball(3, 200, 200));
        BallStateTracker.Result result = state.update(frame, pockets());
        assertFalse(result.inventoryComplete);
        assertTrue(result.reason.contains("Duplicate OCR identity"));
    }

    @Test public void resetClearsBaselineAndPocketMemory() {
        BallStateTracker state = freshLockedTracker();
        state.reset();
        BallStateTracker.Result result = state.update(rack(), pockets());
        assertFalse(result.baselineEstablished);
        assertFalse(result.inventoryComplete);
        assertTrue(result.pocketedNumbers.isEmpty());
    }
}
