package com.cherunika.chernika;

/** Vertical Plato power slider geometry in screenshot pixels. */
final class PowerBar {
    final float centerX;
    final float top;
    final float bottom;
    final float currentFraction;
    final double confidence;

    PowerBar(float centerX, float top, float bottom,
             float currentFraction, double confidence) {
        this.centerX = centerX;
        this.top = top;
        this.bottom = bottom;
        this.currentFraction = currentFraction;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    float yForPower(float percent) {
        float fraction = Math.max(0f, Math.min(1f, percent / 100f));
        // Plato's recorded control is 0% at the top and 100% at the bottom.
        return top + (bottom - top) * fraction;
    }
}
