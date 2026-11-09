package com.cherunika.chernika;

final class PowerBar {
    final float left, right, y;
    final float currentFraction;

    PowerBar(float left, float right, float y, float currentFraction) {
        this.left = left;
        this.right = right;
        this.y = y;
        this.currentFraction = currentFraction;
    }
}
