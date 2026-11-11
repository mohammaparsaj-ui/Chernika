package com.cherunika.chernika;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BallColorClassifierTest {
    private final BallColorClassifier classifier = new BallColorClassifier();

    @Test public void readsSolidYellowOneBall() {
        BallColorClassifier.Result result = classifier.classify(
                ballImage(0xFFFFC400, false), 40, 40, 20, 20, 12);
        assertEquals(1, result.number);
        assertTrue(result.confidence >= 0.42);
    }

    @Test public void readsStripedYellowNineBall() {
        BallColorClassifier.Result result = classifier.classify(
                ballImage(0xFFFFC400, true), 40, 40, 20, 20, 12);
        assertEquals(9, result.number);
        assertTrue(result.confidence >= 0.42);
    }

    @Test public void separatesBrightRedThreeFromDarkMaroonSeven() {
        BallColorClassifier.Result three = classifier.classify(
                ballImage(0xFFE51B23, false), 40, 40, 20, 20, 12);
        BallColorClassifier.Result seven = classifier.classify(
                ballImage(0xFF68121B, false), 40, 40, 20, 20, 12);
        assertEquals(3, three.number);
        assertEquals(7, seven.number);
    }

    @Test public void readsBlackEightBall() {
        BallColorClassifier.Result result = classifier.classify(
                ballImage(0xFF101010, false), 40, 40, 20, 20, 12);
        assertEquals(8, result.number);
    }

    private int[] ballImage(int shellColor, boolean stripe) {
        int width = 40;
        int height = 40;
        int[] pixels = new int[width * height];
        double cx = 20.0;
        double cy = 20.0;
        double radius = 12.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = x + 0.5 - cx;
                double dy = y + 0.5 - cy;
                double distance = Math.hypot(dx, dy);
                int color = 0x00000000;
                if (distance <= radius) {
                    color = shellColor;
                    if (stripe && Math.abs(dy) <= 2.4) color = 0xFFFFFFFF;
                    if (distance <= 3.5) color = 0xFFFFFFFF;
                }
                pixels[y * width + x] = color;
            }
        }
        return pixels;
    }
}
