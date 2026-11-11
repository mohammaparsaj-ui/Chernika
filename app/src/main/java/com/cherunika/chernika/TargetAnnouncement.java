package com.cherunika.chernika;

final class TargetAnnouncement {
    final int legalBall;
    final int objectiveBall;
    final boolean scratch;
    final boolean breakTurn;
    final String rawText;

    TargetAnnouncement(int legalBall, int objectiveBall, boolean scratch, String rawText) {
        this(legalBall, objectiveBall, scratch, false, rawText);
    }

    TargetAnnouncement(int legalBall, int objectiveBall, boolean scratch,
                       boolean breakTurn, String rawText) {
        this.legalBall = legalBall;
        this.objectiveBall = objectiveBall;
        this.scratch = scratch;
        this.breakTurn = breakTurn;
        this.rawText = rawText == null ? "" : rawText;
    }

    boolean hasTarget() { return legalBall >= 1 && legalBall <= 9; }
}
