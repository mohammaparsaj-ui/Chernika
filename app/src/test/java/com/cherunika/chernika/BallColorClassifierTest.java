package com.cherunika.chernika;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BallColorClassifierTest {
    private final BallColorClassifier classifier = new BallColorClassifier();

    @Test public void readsSolidYellowOneBall() {
        assertNumber(1, 0xFFFFC400, false);
    }

    @Test public void readsStripedYellowNineBall() {
        assertNumber(9, 0xFFFFC400, true);
    }

    @Test public void separatesBrightRedThreeFromDarkMaroonSeven() {
        assertNumber(3, 0xFFE51B23, false);
        assertNumber(7, 0xFF624C39, false);
    }

    @Test public void separatesBrightOrangeFiveFromDarkMaroonSeven() {
        assertNumber(5, 0xFFBE6948, false);
        assertNumber(7, 0xFF624C39, false);
    }

    @Test public void readsGreenSixBall() {
        assertNumber(6, 0xFF435327, false);
    }

    @Test public void readsBlueTwoAndPurpleFour() {
        assertNumber(2, 0xFF414B7B, false);
        assertNumber(4, 0xFF4E4274, false);
    }

    @Test public void readsBlackEightBall() {
        assertNumber(8, 0xFF101010, false);
    }

    private void assertNumber(int expected, int shellColor, boolean stripe) {
        BallColorClassifier.Result result = classifier.classify(
                ballImage(shellColor, stripe), 40, 40, 20, 20, 12);
        assertEquals(expected, result.number);
        assertTrue(result.confidence >= 0.42);
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
