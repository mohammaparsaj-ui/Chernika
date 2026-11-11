package com.cherunika.chernika;

final class PowerBar {
    enum Orientation { VERTICAL, HORIZONTAL }

    final float left;
    final float right;
    final float top;
    final float bottom;
    final float currentFraction;
    final Orientation orientation;
    final float handleX;
    final float handleY;

    PowerBar(float left, float right, float top, float bottom,
             float currentFraction, Orientation orientation) {
        this(left, right, top, bottom, currentFraction, orientation, Float.NaN, Float.NaN);
    }

    PowerBar(float left, float right, float top, float bottom,
             float currentFraction, Orientation orientation,
             float handleX, float handleY) {
        this.left = left;
        this.right = right;
        this.top = top;
        this.bottom = bottom;
        this.currentFraction = Math.max(0.0f, Math.min(1.0f, currentFraction));
        this.orientation = orientation == null ? Orientation.VERTICAL : orientation;
        this.handleX = handleX;
        this.handleY = handleY;
    }

    Vec2 startPoint() {
        if (!Float.isNaN(handleX) && !Float.isNaN(handleY)) {
            return new Vec2(handleX, handleY);
        }
        if (orientation == Orientation.VERTICAL) {
            return new Vec2((left + right) * 0.5,
                    bottom - 2.0 - currentFraction * Math.max(1.0, bottom - top - 4.0));
        }
        return new Vec2(left + 2.0 + currentFraction * Math.max(1.0, right - left - 4.0),
                (top + bottom) * 0.5);
    }

    /** powerPercent is normalized from 0 (minimum) to 100 (maximum). */
    Vec2 pointForPower(double powerPercent) {
        double fraction = Math.max(0.0, Math.min(1.0, powerPercent / 100.0));
        if (orientation == Orientation.VERTICAL) {
            double trackX = Float.isNaN(handleX) ? (left + right) * 0.5 : handleX;
            return new Vec2(trackX,
                    bottom - 2.0 - fraction * Math.max(1.0, bottom - top - 4.0));
        }
        return new Vec2(left + 2.0 + fraction * Math.max(1.0, right - left - 4.0),
                (top + bottom) * 0.5);
    }
}
