package com.cherunika.chernika;

final class Ball {
    final Vec2 center;
    final double radius;
    final boolean cue;
    final double detectionConfidence;
    int number;
    double numberConfidence;

    Ball(Vec2 center, double radius, int number, boolean cue, double detectionConfidence) {
        this.center = center;
        this.radius = radius;
        this.number = number;
        this.cue = cue;
        this.detectionConfidence = Math.max(0.0, Math.min(1.0, detectionConfidence));
        this.numberConfidence = 0.0;
    }
}
