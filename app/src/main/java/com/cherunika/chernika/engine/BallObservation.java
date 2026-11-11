package com.cherunika.chernika.engine;

/** One detected ball. Number 0 means the detector could not read its number. */
public final class BallObservation {
    public final Point2 center;
    public final double radius;
    public final int number;
    public final boolean cueBall;
    /** Combined confidence consumed by the planner. */
    public final double confidence;
    /** Confidence that a ball was detected at this location, independent of OCR. */
    public final double detectionConfidence;
    /** Confidence in the numeric identity; cue balls do not need number OCR. */
    public final double identityConfidence;

    public BallObservation(Point2 center, double radius, int number,
                           boolean cueBall, double confidence) {
        this(center, radius, number, cueBall, confidence,
                cueBall ? 1.0 : (number == 0 ? 0.0 : confidence));
    }

    public BallObservation(Point2 center, double radius, int number, boolean cueBall,
                           double detectionConfidence, double identityConfidence) {
        if (center == null || !Double.isFinite(radius) || radius <= 0
                || number < 0 || number > 9
                || !Double.isFinite(detectionConfidence) || detectionConfidence < 0
                || detectionConfidence > 1
                || !Double.isFinite(identityConfidence) || identityConfidence < 0
                || identityConfidence > 1) {
            throw new IllegalArgumentException("Invalid ball observation");
        }
        if (cueBall && number != 0) {
            throw new IllegalArgumentException("Cue ball must not have an object-ball number");
        }
        this.center = center;
        this.radius = radius;
        this.number = number;
        this.cueBall = cueBall;
        this.detectionConfidence = detectionConfidence;
        this.identityConfidence = cueBall ? 1.0 : identityConfidence;
        this.confidence = cueBall ? detectionConfidence
                : Math.min(detectionConfidence, identityConfidence);
    }

    public boolean hasReliableIdentity(double minimumConfidence) {
        return cueBall || (number >= 1 && number <= 9
                && identityConfidence >= minimumConfidence);
    }
}
