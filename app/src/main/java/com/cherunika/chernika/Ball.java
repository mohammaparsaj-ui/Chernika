package com.cherunika.chernika;

final class Ball {
    final Vec2 center;
    final double radius;
    int number;
    final boolean cue;

    Ball(Vec2 center, double radius, int number, boolean cue) {
        this.center = center;
        this.radius = radius;
        this.number = number;
        this.cue = cue;
    }
}
