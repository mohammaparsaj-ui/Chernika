package com.cherunika.chernika.engine;

import org.junit.Test;

import static org.junit.Assert.*;

public class BallColorClassifierTest {
    private static final int FELT = rgb(74, 155, 75);
    private static final int GREEN_SIX = rgb(36, 82, 16);

    @Test public void recognizesDarkSaturatedGreenSixAgainstBrighterFelt() {
        int[] pixels = scene(64, 64, FELT);
        drawDisk(pixels, 64, 64, 32, 32, 14, GREEN_SIX);
        // A small white numeral should not change the median saturated pixels.
        drawDisk(pixels, 64, 64, 32, 32, 2, rgb(245, 245, 245));

        assertTrue(BallColorClassifier.isGreenSix(
                pixels, 64, 64, 32, 32, 14));
    }

    @Test public void uniformFeltIsNotMisclassifiedAsGreenSix() {
        int[] pixels = scene(64, 64, FELT);
        assertFalse(BallColorClassifier.isGreenSix(
                pixels, 64, 64, 32, 32, 14));
    }

    @Test public void yellowAndBlueBallsAreNotMisclassifiedAsGreenSix() {
        int[] yellow = scene(64, 64, rgb(240, 190, 35));
        int[] blue = scene(64, 64, rgb(30, 90, 205));
        assertFalse(BallColorClassifier.isGreenSix(yellow, 64, 64, 32, 32, 14));
        assertFalse(BallColorClassifier.isGreenSix(blue, 64, 64, 32, 32, 14));
    }

    private static int[] scene(int width, int height, int color) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }

    private static void drawDisk(int[] pixels, int width, int height,
                                 int cx, int cy, int radius, int color) {
        for (int y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++) {
                int dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= radius * radius) {
                    pixels[y * width + x] = color;
                }
            }
        }
    }

    private static int rgb(int red, int green, int blue) {
        return 0xff000000 | red << 16 | green << 8 | blue;
    }
}
