package com.cherunika.chernika.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single, already-normalized observation assembled by the Android vision adapter.
 * This class contains no Android dependencies and is safe to unit-test on the JVM.
 */
public final class GameObservation {
    public final Rect2 playableBounds;
    public final double tableConfidence;
    public final List<PocketObservation> pockets;
    public final List<BallObservation> balls;
    public final HudInstruction instruction;
    public final boolean inventoryComplete;
    public final boolean liveTable;
    public final boolean replayScreen;
    public final boolean ballsMoving;
    public final int consecutiveStableFrames;
    public final double powerControlConfidence;
    public final boolean yourTurn;

    /** Backwards-compatible constructor for existing pure-Java tests. */
    public GameObservation(Rect2 playableBounds, double tableConfidence,
                            List<PocketObservation> pockets, List<BallObservation> balls,
                            HudInstruction instruction, boolean inventoryComplete,
                            boolean liveTable, boolean replayScreen, boolean ballsMoving,
                            int consecutiveStableFrames, double powerControlConfidence) {
        this(playableBounds, tableConfidence, pockets, balls, instruction, inventoryComplete,
                liveTable, replayScreen, ballsMoving, consecutiveStableFrames,
                powerControlConfidence, true);
    }

    public GameObservation(Rect2 playableBounds, double tableConfidence,
                            List<PocketObservation> pockets, List<BallObservation> balls,
                            HudInstruction instruction, boolean inventoryComplete,
                            boolean liveTable, boolean replayScreen, boolean ballsMoving,
                            int consecutiveStableFrames, double powerControlConfidence,
                            boolean yourTurn) {
        if (!Double.isFinite(tableConfidence) || tableConfidence < 0 || tableConfidence > 1
                || !Double.isFinite(powerControlConfidence)
                || powerControlConfidence < 0 || powerControlConfidence > 1
                || consecutiveStableFrames < 0) {
            throw new IllegalArgumentException("Invalid observation confidence or stability");
        }
        this.playableBounds = playableBounds;
        this.tableConfidence = tableConfidence;
        this.pockets = immutableCopy(pockets);
        this.balls = immutableCopy(balls);
        this.instruction = instruction;
        this.inventoryComplete = inventoryComplete;
        this.liveTable = liveTable;
        this.replayScreen = replayScreen;
        this.ballsMoving = ballsMoving;
        this.consecutiveStableFrames = consecutiveStableFrames;
        this.powerControlConfidence = powerControlConfidence;
        this.yourTurn = yourTurn;
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        if (source == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
