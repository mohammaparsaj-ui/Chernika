package com.cherunika.chernika;

final class Vec2 {
    final double x;
    final double y;

    Vec2(double x, double y) { this.x = x; this.y = y; }
    Vec2 add(Vec2 o) { return new Vec2(x + o.x, y + o.y); }
    Vec2 subtract(Vec2 o) { return new Vec2(x - o.x, y - o.y); }
    Vec2 scale(double k) { return new Vec2(x * k, y * k); }
    double dot(Vec2 o) { return x * o.x + y * o.y; }
    double length() { return Math.hypot(x, y); }
    double distance(Vec2 o) { return subtract(o).length(); }
    Vec2 normalized() {
        double len = length();
        return len < 1e-8 ? new Vec2(0, 0) : new Vec2(x / len, y / len);
    }
}
