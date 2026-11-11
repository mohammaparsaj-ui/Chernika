package com.cherunika.chernika.engine;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class InstructionFirstEngineTest {
    private final InstructionFirstEngine engine = new InstructionFirstEngine();
    private final Rect2 bounds = new Rect2(0, 0, 1000, 500);

    private GameObservation observation(HudInstruction instruction, boolean inventoryComplete,
                                        boolean live, boolean replay, boolean moving,
                                        int stableFrames, BallObservation... balls) {
        return new GameObservation(bounds, 0.97, pockets(), Arrays.asList(balls),
                instruction, inventoryComplete, live, replay, moving, stableFrames, 0.95, true);
    }

    private java.util.List<PocketObservation> pockets() {
        return Arrays.asList(
                new PocketObservation(new Point2(0, 0), 34, 34, 0.95),
                new PocketObservation(new Point2(500, 0), 34, 34, 0.95),
                new PocketObservation(new Point2(1000, 0), 34, 34, 0.95),
                new PocketObservation(new Point2(0, 500), 34, 34, 0.95),
                new PocketObservation(new Point2(500, 500), 34, 34, 0.95),
                new PocketObservation(new Point2(1000, 500), 34, 34, 0.95));
    }

    private BallObservation cue(double x, double y) {
        return new BallObservation(new Point2(x, y), 10, 0, true, 0.98);
    }

    private BallObservation ball(int number, double x, double y) {
        return new BallObservation(new Point2(x, y), 10, number, false, 0.96);
    }

    @Test public void hitFourSelectsFourWhenItIsLowestRemaining() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, true, false, false, 3,
                cue(240, 250), ball(4, 500, 250), ball(5, 700, 350), ball(9, 800, 150));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.PLAN_READY, decision.status);
        assertEquals(4, decision.legalBall);
        assertTrue(decision.aimPoint != null);
        assertTrue(decision.gestureExecutionEnabled);
        assertTrue(decision.powerPercent >= 26.5 && decision.powerPercent <= 62.4);
    }

    @Test public void openingBreakIsPausedUntilDedicatedBreakPlannerExists() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.HIT_BALL, 1,
                        "It's your turn to break. Hit the 1 ball"),
                true, true, false, false, 3,
                cue(240, 250), ball(1, 500, 250));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.WAIT, decision.status);
        assertTrue(decision.reason.contains("Opening break detected"));
    }

    @Test public void opponentTurnNeverProducesPlan() {
        GameObservation state = new GameObservation(bounds, 0.97, pockets(),
                Arrays.asList(cue(240, 250), ball(4, 500, 250)),
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, true, false, false, 4, 0.95, false);
        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.WAIT, decision.status);
        assertTrue(decision.reason.contains("own turn"));
    }

    @Test public void missingPowerControlBlocksExecution() {
        GameObservation state = new GameObservation(bounds, 0.97, pockets(),
                Arrays.asList(cue(240, 250), ball(4, 500, 250)),
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, true, false, false, 4, 0.20, true);
        assertEquals(EngineDecision.Status.WAIT, engine.evaluate(state).status);
    }

    @Test public void hudMismatchFailsClosed() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, true, false, false, 3,
                cue(240, 250), ball(2, 400, 250), ball(4, 500, 250));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.WAIT, decision.status);
        assertTrue(decision.reason.contains("lower-numbered ball 2"));
    }

    @Test public void pocketNineWithLowerBallRequiresLegalContactOnLowestBall() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.POCKET_BALL, 9, "Pocket the 9 ball"),
                true, true, false, false, 3,
                cue(240, 250), ball(4, 500, 250), ball(9, 800, 150));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.PLAN_READY, decision.status);
        assertEquals(4, decision.legalBall);
        assertEquals(9, decision.objectiveBall);
        assertEquals(EngineDecision.ShotKind.LEGAL_CONTACT_ONLY, decision.shotKind);
        assertEquals(-1, decision.pocketIndex);
    }

    @Test public void pocketNineCanBePottedWhenItIsTheLowestRemainingBall() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.POCKET_BALL, 9, "Pocket the 9 ball"),
                true, true, false, false, 3,
                cue(250, 250), ball(9, 520, 250));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.PLAN_READY, decision.status);
        assertEquals(9, decision.legalBall);
        assertEquals(EngineDecision.ShotKind.DIRECT_POT, decision.shotKind);
    }

    @Test public void unreadBallAwayFromRouteRemainsAnObstacleWithoutBlockingHudTarget() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                false, true, false, false, 3,
                cue(240, 250), ball(4, 500, 250),
                new BallObservation(new Point2(650, 300), 10, 0, false, 0.92));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.PLAN_READY, decision.status);
        assertEquals(4, decision.legalBall);
    }

    @Test public void replayScreenNeverProducesPlan() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, false, true, false, 5,
                cue(240, 250), ball(4, 500, 250));

        assertEquals(EngineDecision.Status.WAIT, engine.evaluate(state).status);
    }

    @Test public void movingBallsNeverProducePlan() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, true, false, true, 0,
                cue(240, 250), ball(4, 500, 250));

        assertEquals(EngineDecision.Status.WAIT, engine.evaluate(state).status);
    }

    @Test public void fewerThanSixPocketsNeverProducePlan() {
        GameObservation state = new GameObservation(bounds, 0.97,
                Collections.singletonList(new PocketObservation(new Point2(0, 0), 34, 34, 0.95)),
                Arrays.asList(cue(240, 250), ball(4, 500, 250)),
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, true, false, false, 3, 0.95, true);

        assertEquals(EngineDecision.Status.WAIT, engine.evaluate(state).status);
    }

    @Test public void ballInHandDoesNotTriggerAutomaticPlacement() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.BALL_IN_HAND, 0, "Cue ball in hand"),
                true, true, false, false, 3,
                cue(240, 250), ball(4, 500, 250));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.WAIT, decision.status);
        assertTrue(decision.reason.contains("does not reposition"));
    }

    @Test public void duplicateBallNumberFailsClosed() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, true, false, false, 3,
                cue(240, 250), ball(4, 500, 250), ball(4, 700, 350));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.WAIT, decision.status);
        assertTrue(decision.reason.contains("Duplicate"));
    }

    @Test public void oneUnrefinedPocketBlocksPlanning() {
        java.util.List<PocketObservation> uncertain = pockets();
        uncertain.set(2, new PocketObservation(new Point2(1000, 0), 34, 34, 0.40));
        GameObservation state = new GameObservation(bounds, 0.97, uncertain,
                Arrays.asList(cue(240, 250), ball(4, 500, 250)),
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                true, true, false, false, 3, 0.95, true);

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.WAIT, decision.status);
        assertTrue(decision.reason.contains("pocket"));
    }

    @Test public void partialInventoryDoesNotBlockAConfidentHudTarget() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.HIT_BALL, 4, "Hit the 4 ball"),
                false, true, false, false, 3,
                cue(240, 250), ball(4, 500, 250));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.PLAN_READY, decision.status);
        assertEquals(4, decision.legalBall);
    }

    @Test public void unreadObjectBallBlocksFinalNineWhenNoLegalFirstContactIsKnown() {
        GameObservation state = observation(
                new HudInstruction(HudInstruction.Type.POCKET_BALL, 9, "Pocket the 9 ball"),
                false, true, false, false, 3,
                cue(240, 250), ball(9, 520, 250),
                new BallObservation(new Point2(700, 300), 10, 0, false, 0.92));

        EngineDecision decision = engine.evaluate(state);
        assertEquals(EngineDecision.Status.WAIT, decision.status);
        assertTrue(decision.reason.contains("unread object ball"));
    }
}
