package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TableGeometry {
    final double left, top, right, bottom;
    final double width, height;

    TableGeometry(double left, double top, double right, double bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.width = Math.max(1, right - left);
        this.height = Math.max(1, bottom - top);
    }

    boolean contains(Vec2 p, double margin) {
        return p.x >= left + margin && p.x <= right - margin
                && p.y >= top + margin && p.y <= bottom - margin;
    }

    List<Pocket> pockets() {
        double cornerRadius = width * 0.036;
        double sideRadius = width * 0.029;
        double inset = width * 0.004;
        List<Pocket> out = new ArrayList<>(6);
        out.add(new Pocket(new Vec2(left + inset, top + inset), cornerRadius, true));
        out.add(new Pocket(new Vec2((left + right) / 2.0, top + inset), sideRadius, false));
        out.add(new Pocket(new Vec2(right - inset, top + inset), cornerRadius, true));
        out.add(new Pocket(new Vec2(left + inset, bottom - inset), cornerRadius, true));
        out.add(new Pocket(new Vec2((left + right) / 2.0, bottom - inset), sideRadius, false));
        out.add(new Pocket(new Vec2(right - inset, bottom - inset), cornerRadius, true));
        return Collections.unmodifiableList(out);
    }
}
