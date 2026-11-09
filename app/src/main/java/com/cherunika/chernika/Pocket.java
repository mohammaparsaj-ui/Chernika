package com.cherunika.chernika;

final class Pocket {
    final Vec2 center;
    final double captureRadius;
    final boolean corner;

    Pocket(Vec2 center, double captureRadius, boolean corner) {
        this.center = center;
        this.captureRadius = captureRadius;
        this.corner = corner;
    }
}
