package com.cherunika.chernika.engine;

/** Axis-aligned playable surface in screen coordinates. */
public final class Rect2 {
    public final double left;
    public final double top;
    public final double right;
    public final double bottom;

    public Rect2(double left, double top, double right, double bottom) {
        if (!Double.isFinite(left) || !Double.isFinite(top)
                || !Double.isFinite(right) || !Double.isFinite(bottom)
                || right <= left || bottom <= top) {
            throw new IllegalArgumentException("Invalid rectangle");
        }
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public double width() { return right - left; }
    public double height() { return bottom - top; }

    public boolean contains(Point2 point, double inset) {
        if (point == null || inset < 0) return false;
        return point.x >= left + inset && point.x <= right - inset
                && point.y >= top + inset && point.y <= bottom - inset;
    }
}
