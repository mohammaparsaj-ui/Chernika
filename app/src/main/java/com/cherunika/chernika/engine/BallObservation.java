package com.cherunika.chernika.engine;

/** One detected ball. Number 0 means the detector could not read its number. */
public final class BallObservation {
    public final Point2 center;
    public final double radius;
    public final int number;
    public final boolean cueBall;
    public final double confidence;

    public BallObservation(Point2 center, double radius, int number,
                           boolean cueBall, double confidence) {
        if (center == null || !Double.isFinite(radius) || radius <= 0
                || number < 0 || number > 9
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Invalid ball observation");
        }
        if (cueBall && number != 0) {
            throw new IllegalArgumentException("Cue ball must not have an object-ball number");
        }
        this.center = center;
        this.radius = radius;
        this.number = number;
        this.cueBall = cueBall;
        this.confidence = confidence;
    }
}
