package com.cherunika.chernika;

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
    /** Initial visual estimate of the pocket capture radius, in screen pixels. */
    final double captureRadius;
    final boolean corner;

    Pocket(Id id, Vec2 center, double captureRadius, boolean corner) {
        if (id == null || center == null || Double.isNaN(captureRadius)
                || Double.isInfinite(captureRadius) || captureRadius <= 0) {
            throw new IllegalArgumentException("Invalid pocket calibration");
        }
        this.id = id;
        this.center = center;
        this.captureRadius = captureRadius;
        this.corner = corner;
    }
}
