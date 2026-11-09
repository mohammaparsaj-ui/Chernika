package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.List;

final class FrameState {
    final int screenWidth, screenHeight;
    TableGeometry table;
    final List<Ball> balls = new ArrayList<>();
    Ball cueBall;
    PowerBar powerBar;
    int refinedPocketCount;
    boolean ballRadiusRefined;

    FrameState(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    boolean hasUsableTable() { return table != null && table.width > screenWidth * 0.35; }
}
