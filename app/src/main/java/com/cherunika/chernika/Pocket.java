package com.cherunika.chernika;

/**
 * Calibrated six-pocket model. The pocket center is the opening center;
 * aimPoint is the measured continuation point just beyond the cushion and is
 * used only to establish the incoming pocket-entry direction.
 */
final class Pocket {
    enum Id {
        TOP_LEFT,
        TOP_RIGHT,
        MIDDLE_LEFT,
        MIDDLE_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    final Id id;
    final Vec2 center;
    final Vec2 aimPoint;
    final double openingWidth;
    final double openingHeight;
    /** Half of the smaller measured opening dimension, in screenshot pixels. */
    final double captureRadius;
    final boolean corner;
    final double visionConfidence;
    final boolean visionRefined;

    Pocket(Id id, Vec2 center, Vec2 aimPoint, double openingWidth,
           double openingHeight, boolean corner) {
        this(id, center, aimPoint, openingWidth, openingHeight, corner, 0.0, false);
    }

    private Pocket(Id id, Vec2 center, Vec2 aimPoint, double openingWidth,
                   double openingHeight, boolean corner, double visionConfidence,
                   boolean visionRefined) {
        if (id == null || center == null || aimPoint == null
                || !isFinite(openingWidth) || !isFinite(openingHeight)
                || openingWidth <= 0 || openingHeight <= 0
                || !isFinite(visionConfidence) || visionConfidence < 0 || visionConfidence > 1) {
            throw new IllegalArgumentException("Invalid Plato pocket geometry");
        }
        this.id = id;
        this.center = center;
        this.aimPoint = aimPoint;
        this.openingWidth = openingWidth;
        this.openingHeight = openingHeight;
        this.captureRadius = Math.min(openingWidth, openingHeight) * 0.5;
        this.corner = corner;
        this.visionConfidence = visionConfidence;
        this.visionRefined = visionRefined;
    }

    /**
     * Apply a conservative image-derived correction. The manually calibrated
     * anchor remains authoritative unless the detected dark opening is both
     * plausible and close to it.
     */
    Pocket withVisionMeasurement(Vec2 measuredCenter, double measuredWidth,
                                  double measuredHeight, double confidence) {
        if (measuredCenter == null || !isFinite(measuredWidth) || !isFinite(measuredHeight)
                || !isFinite(confidence) || confidence < 0.58 || confidence > 1.0) {
            return this;
        }
        Vec2 delta = measuredCenter.subtract(center);
        if (delta.length() > captureRadius * 0.45) return this;

        // Blend instead of replacing the known reference with one noisy frame.
        Vec2 adjustedCenter = center.add(delta.scale(0.35));
        Vec2 adjustedAim = aimPoint.add(adjustedCenter.subtract(center));
        double width = clamp(measuredWidth, openingWidth * 0.78, openingWidth * 1.22);
        double height = clamp(measuredHeight, openingHeight * 0.78, openingHeight * 1.22);
        return new Pocket(id, adjustedCenter, adjustedAim, width, height,
                corner, confidence, true);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
