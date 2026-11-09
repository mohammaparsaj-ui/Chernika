package com.cherunika.chernika;

final class ShotPlan {
    final Ball target;
    final Vec2 aimPoint;
    final Pocket pocket;
    final double powerPercent;
    final double score;
    final boolean breakShot;
    final boolean bankShot;
    final boolean comboShot;

    ShotPlan(Ball target, Vec2 aimPoint, Pocket pocket, double powerPercent, double score,
             boolean breakShot, boolean bankShot, boolean comboShot) {
        this.target = target;
        this.aimPoint = aimPoint;
        this.pocket = pocket;
        this.powerPercent = powerPercent;
        this.score = score;
        this.breakShot = breakShot;
        this.bankShot = bankShot;
        this.comboShot = comboShot;
    }
}
