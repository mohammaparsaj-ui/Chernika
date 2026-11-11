package com.cherunika.chernika;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiamondRackVisionDetectorTest {
    private static final int WIDTH = 611;
    private static final int HEIGHT = 1024;
    private static final double RADIUS = 11.5;
    private static final double CENTER_X = 306.5;
    private static final double CENTER_Y = 314.5;

    private final DiamondRackVisionDetector detector = new DiamondRackVisionDetector();

    @Test public void separatesNineTouchingBallsUsingTheMeasuredPlatoRackTemplate() {
        DiamondRackVisionDetector.Result result = detector.detect(
                rackPixels(false), WIDTH, HEIGHT, table(), 1.0, 1.0);

        assertTrue(result.detected);
        assertEquals(9, result.matchedSlots);
        assertEquals(9, result.balls.size());
        assertTrue(result.confidence > 0.55);
        assertEquals(1, ball(result, 1).number);
        assertEquals(9, ball(result, 9).number);
        assertEquals("detected rack center x=" + result.center.x,
                306.5, ball(result, 1).center.x, 3.0);
        assertEquals("detected #1 y=" + ball(result, 1).center.y,
                354.5, ball(result, 1).center.y, 2.0);
        assertEquals("detected #9 x=" + ball(result, 9).center.x,
                306.5, ball(result, 9).center.x, 3.0);
        assertEquals("detected #9 y=" + ball(result, 9).center.y,
                314.5, ball(result, 9).center.y, 2.0);
    }

    @Test public void refusesToInventANinthBallWhenTheRackIsIncomplete() {
        DiamondRackVisionDetector.Result result = detector.detect(
                rackPixels(true), WIDTH, HEIGHT, table(), 1.0, 1.0);
        assertFalse(result.detected);
        assertEquals(0, result.balls.size());
    }

    @Test public void doesNotTreatScatteredPostBreakBallsAsARack() {
        int[] pixels = feltPixels();
        int[] numbers = {8, 6, 7, 4, 9, 5, 2, 3, 1};
        int[] colors = {
                0xFF101010, 0xFF435327, 0xFF624C39,
                0xFF4E4274, 0xFFFFC400, 0xFFBE6948,
                0xFF414B7B, 0xFFE51B23, 0xFFFFC400
        };
        double[][] scattered = {
                {155, 272}, {294, 643}, {322, 277},
                {220, 262}, {292, 305}, {402, 658},
                {286, 479}, {466, 455}, {417, 823}
        };
        for (int i = 0; i < numbers.length; i++) {
            drawBall(pixels, scattered[i][0], scattered[i][1],
                    RADIUS, colors[i], false);
        }

        DiamondRackVisionDetector.Result result = detector.detect(
                pixels, WIDTH, HEIGHT, table(), 1.0, 1.0);
        assertFalse(result.detected);
    }

    private TableGeometry table() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                112, 159, 495, 904, WIDTH, HEIGHT);
    }

    private Ball ball(DiamondRackVisionDetector.Result result, int number) {
        for (Ball ball : result.balls) if (ball.number == number) return ball;
        throw new AssertionError("Missing ball #" + number);
    }

    private int[] rackPixels(boolean omitOneBall) {
        int[] pixels = feltPixels();
        int[] numbers = {8, 6, 7, 4, 9, 5, 2, 3, 1};
        int[] colors = {
                0xFF101010, 0xFF435327, 0xFF624C39,
                0xFF4E4274, 0xFFFFC400, 0xFFBE6948,
                0xFF414B7B, 0xFFE51B23, 0xFFFFC400
        };
        double[][] centers = {
                {CENTER_X, CENTER_Y - 2 * Math.sqrt(3) * RADIUS},
                {CENTER_X - RADIUS, CENTER_Y - Math.sqrt(3) * RADIUS},
                {CENTER_X + RADIUS, CENTER_Y - Math.sqrt(3) * RADIUS},
                {CENTER_X - 2 * RADIUS, CENTER_Y},
                {CENTER_X, CENTER_Y},
                {CENTER_X + 2 * RADIUS, CENTER_Y},
                {CENTER_X - RADIUS, CENTER_Y + Math.sqrt(3) * RADIUS},
                {CENTER_X + RADIUS, CENTER_Y + Math.sqrt(3) * RADIUS},
                {CENTER_X, CENTER_Y + 2 * Math.sqrt(3) * RADIUS}
        };

        for (int i = 0; i < numbers.length; i++) {
            if (omitOneBall && numbers[i] == 1) continue;
            drawBall(pixels, centers[i][0], centers[i][1],
                    RADIUS, colors[i], numbers[i] == 9);
        }
        return pixels;
    }

    private int[] feltPixels() {
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFF23843C;
        return pixels;
    }

    private void drawBall(int[] pixels, double centerX, double centerY,
                          double radius, int shellColor, boolean striped) {
        int left = Math.max(0, (int) Math.floor(centerX - radius));
        int right = Math.min(WIDTH - 1, (int) Math.ceil(centerX + radius));
        int top = Math.max(0, (int) Math.floor(centerY - radius));
        int bottom = Math.min(HEIGHT - 1, (int) Math.ceil(centerY + radius));
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                double dx = x + 0.5 - centerX;
                double dy = y + 0.5 - centerY;
                if (Math.hypot(dx, dy) > radius) continue;
                int color = shellColor;
                if (striped && Math.abs(dy) <= radius * 0.20) color = 0xFFFFFFFF;
                pixels[y * WIDTH + x] = color;
            }
        }
    }
}
