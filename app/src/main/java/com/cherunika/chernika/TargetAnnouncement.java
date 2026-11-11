package com.cherunika.chernika;

final class TargetAnnouncement {
    final int legalBall;
    final int objectiveBall;
    final boolean scratch;
    final boolean breakTurn;
    final boolean playerTurn;
    final String rawText;

    TargetAnnouncement(int legalBall, int objectiveBall, boolean scratch, String rawText) {
        this(legalBall, objectiveBall, scratch, false, false, rawText);
    }

    TargetAnnouncement(int legalBall, int objectiveBall, boolean scratch,
                       boolean breakTurn, String rawText) {
        this(legalBall, objectiveBall, scratch, breakTurn, breakTurn, rawText);
    }

    TargetAnnouncement(int legalBall, int objectiveBall, boolean scratch,
                       boolean breakTurn, boolean playerTurn, String rawText) {
        this.legalBall = legalBall;
        this.objectiveBall = objectiveBall;
        this.scratch = scratch;
        this.breakTurn = breakTurn;
        this.playerTurn = playerTurn || breakTurn;
        this.rawText = rawText == null ? "" : rawText;
    }

    boolean hasTarget() { return legalBall >= 1 && legalBall <= 9; }
}
