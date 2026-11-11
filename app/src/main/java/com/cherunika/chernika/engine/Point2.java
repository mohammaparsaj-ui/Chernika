package com.cherunika.chernika.engine;

/** Immutable screen/table coordinate in pixels. */
public final class Point2 {
    public final double x;
    public final double y;

    public Point2(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Point coordinates must be finite");
        }
        this.x = x;
        this.y = y;
    }

    public Point2 add(Point2 other) { return new Point2(x + other.x, y + other.y); }
    public Point2 subtract(Point2 other) { return new Point2(x - other.x, y - other.y); }
    public Point2 scale(double factor) { return new Point2(x * factor, y * factor); }
    public double dot(Point2 other) { return x * other.x + y * other.y; }
    public double length() { return Math.hypot(x, y); }
    public double distance(Point2 other) { return subtract(other).length(); }

    public Point2 normalized() {
        double length = length();
        if (length < 1.0e-9) return new Point2(0.0, 0.0);
        return new Point2(x / length, y / length);
    }
}
