package com.cherunika.chernika;

final class TargetAnnouncement {
    final int legalBall;
    final int objectiveBall;
    final boolean scratch;
    final String rawText;

    TargetAnnouncement(int legalBall, int objectiveBall, boolean scratch, String rawText) {
        this.legalBall = legalBall;
        this.objectiveBall = objectiveBall;
        this.scratch = scratch;
        this.rawText = rawText == null ? "" : rawText;
    }

    boolean hasTarget() { return legalBall >= 1 && legalBall <= 9; }
}
