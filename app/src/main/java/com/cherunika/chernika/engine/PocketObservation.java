package com.cherunika.chernika.engine;

/** Detected pocket opening; center is the estimated pot destination. */
public final class PocketObservation {
    public final Point2 center;
    public final double openingWidth;
    public final double openingHeight;
    public final double confidence;

    public PocketObservation(Point2 center, double openingWidth, double openingHeight,
                             double confidence) {
        if (center == null || !Double.isFinite(openingWidth) || openingWidth <= 0
                || !Double.isFinite(openingHeight) || openingHeight <= 0
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Invalid pocket observation");
        }
        this.center = center;
        this.openingWidth = openingWidth;
        this.openingHeight = openingHeight;
        this.confidence = confidence;
    }
}
