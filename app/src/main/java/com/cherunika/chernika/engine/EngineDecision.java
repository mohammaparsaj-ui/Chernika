package com.cherunika.chernika.engine;

/** A validated direct-shot proposal for the Android gesture adapter. */
public final class EngineDecision {
    public enum Status { WAIT, PLAN_READY }
    public enum ShotKind { DIRECT_POT, LEGAL_CONTACT_ONLY }

    public final Status status;
    public final String reason;
    public final int legalBall;
    public final int objectiveBall;
    public final ShotKind shotKind;
    public final Point2 aimPoint;
    public final int pocketIndex;
    public final double powerPercent;
    public final boolean centerSpinOnly;
    public final boolean cueBallMustRemainAtCurrentPosition;
    public final boolean gestureExecutionEnabled;

    private EngineDecision(Status status, String reason, int legalBall, int objectiveBall,
                           ShotKind shotKind, Point2 aimPoint, int pocketIndex,
                           double powerPercent) {
        this.status = status;
        this.reason = reason == null ? "" : reason;
        this.legalBall = legalBall;
        this.objectiveBall = objectiveBall;
        this.shotKind = shotKind;
        this.aimPoint = aimPoint;
        this.pocketIndex = pocketIndex;
        this.powerPercent = powerPercent;
        this.centerSpinOnly = true;
        this.cueBallMustRemainAtCurrentPosition = true;
        // A PLAN_READY result is gesture-eligible only after all observation gates pass.
        // The Android adapter still checks app foreground, power geometry and duplicate-shot state.
        this.gestureExecutionEnabled = status == Status.PLAN_READY;
    }

    public static EngineDecision waitFor(String reason) {
        return new EngineDecision(Status.WAIT, reason, 0, 0, null, null, -1, 0.0);
    }

    public static EngineDecision plan(int legalBall, int objectiveBall, ShotKind kind,
                                      Point2 aimPoint, int pocketIndex, String reason) {
        return plan(legalBall, objectiveBall, kind, aimPoint, pocketIndex, 35.0, reason);
    }

    public static EngineDecision plan(int legalBall, int objectiveBall, ShotKind kind,
                                      Point2 aimPoint, int pocketIndex,
                                      double powerPercent, String reason) {
        if (legalBall < 1 || legalBall > 9 || kind == null || aimPoint == null
                || !Double.isFinite(powerPercent) || powerPercent < 0 || powerPercent > 100
                || (kind == ShotKind.DIRECT_POT && pocketIndex < 0)
                || (kind == ShotKind.LEGAL_CONTACT_ONLY && pocketIndex != -1)) {
            throw new IllegalArgumentException("Invalid shot proposal");
        }
        return new EngineDecision(Status.PLAN_READY, reason, legalBall, objectiveBall,
                kind, aimPoint, pocketIndex, powerPercent);
    }

    public boolean isPlanReady() { return status == Status.PLAN_READY; }
}
