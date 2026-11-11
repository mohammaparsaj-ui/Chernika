package com.cherunika.chernika;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.InflaterInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;


import static org.junit.Assert.*;

public class BallEdgeDetectorTest {
    @Test public void detectsGreenBallWhoseInteriorNearlyMatchesFelt() {
        int width = 120;
        int height = 120;
        int felt = rgb(59, 141, 69);
        int ball = rgb(63, 145, 73);
        int rim = rgb(24, 76, 35);
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, felt);
        drawBall(pixels, width, height, 60, 60, 12.0, ball, rim);

        // The interior differs from the felt by less than the old 78-RGB
        // segmentation threshold, so only the circular boundary can recover it.
        assertTrue(rgbDistance(felt, ball) < 78.0);
        List<BallEdgeDetector.Candidate> candidates = detect(
                pixels, width, height, 12.0);

        BallEdgeDetector.Candidate best = nearest(candidates, 60, 60);
        assertNotNull("The green ball should be found from its radial edge", best);
        assertTrue(Math.hypot(best.x - 60, best.y - 60) <= 2.5);
        assertTrue(best.score >= 0.78);
    }

    @Test public void uniformFeltDoesNotCreateCircularCandidates() {
        int width = 120;
        int height = 120;
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, rgb(59, 141, 69));

        assertTrue(detect(pixels, width, height, 12.0).isEmpty());
    }

    @Test public void keepsTwoSeparateBallsAsTwoCandidates() {
        int width = 140;
        int height = 120;
        int felt = rgb(59, 141, 69);
        int ball = rgb(63, 145, 73);
        int rim = rgb(24, 76, 35);
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, felt);
        drawBall(pixels, width, height, 43, 60, 12.0, ball, rim);
        drawBall(pixels, width, height, 79, 60, 12.0, ball, rim);

        List<BallEdgeDetector.Candidate> candidates = detect(
                pixels, width, height, 12.0);
        assertEquals(2, candidates.size());
        assertNotNull(nearest(candidates, 43, 60));
        assertNotNull(nearest(candidates, 79, 60));
    }

    @Test public void recoversGreenSixFromActualPlatoDiamondRackFrame() throws Exception {
        InputStream fixture = getClass().getResourceAsStream("/green_6_rack_crop.jpg.b64");
        assertNotNull("Actual Plato rack fixture must be packaged", fixture);
        String encoded;
        try (InputStream input = fixture) {
            encoded = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
        byte[] compressed = Base64.getDecoder().decode(encoded);
        byte[] raw;
        try (InflaterInputStream inflater = new InflaterInputStream(
                new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            inflater.transferTo(output);
            raw = output.toByteArray();
        }
        assertTrue("Fixture must contain its 8-byte dimensions header", raw.length >= 8);
        int width = readInt(raw, 0);
        int height = readInt(raw, 4);
        assertEquals("Fixture dimensions must match the downsampled crop", 120, width);
        assertEquals("Fixture dimensions must match the downsampled crop", 144, height);
        assertEquals("Fixture must contain exactly one grayscale byte per pixel",
                8 + width * height, raw.length);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int gray = raw[8 + i] & 0xff;
            pixels[i] = 0xff000000 | gray << 16 | gray << 8 | gray;
        }
        List<BallEdgeDetector.Candidate> candidates = BallEdgeDetector.detect(
                pixels, width, height, 0, 0, width, height,
                1.0, 1.0, 12.8 * 120.0 / 250.0, 12);

        assertTrue("A nine-ball rack should yield at least nine circular candidates: "
                + candidates.size(), candidates.size() >= 9);
        assertTrue("The candidate count should remain bounded by the rack inventory: "
                + candidates.size(), candidates.size() <= 10);

        // In this downsampled 120x144 recording frame, the green 6 is the
        // second-row left ball, centered approximately at (56, 39).
        BallEdgeDetector.Candidate greenSix = nearest(candidates, 56, 39);
        assertNotNull("The green 6 must not disappear into the green cloth", greenSix);
        assertTrue("Green 6 center error should be within 3.5 px",
                Math.hypot(greenSix.x - 56, greenSix.y - 39) <= 3.5);
        assertTrue(greenSix.score >= 0.78);
    }

        private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xff) << 24
                | (data[offset + 1] & 0xff) << 16
                | (data[offset + 2] & 0xff) << 8
                | (data[offset + 3] & 0xff);
    }

    private static List<BallEdgeDetector.Candidate> detect(
            int[] pixels, int width, int height, double radius) {
        return BallEdgeDetector.detect(
                pixels, width, height, 0, 0, width, height,
                1.0, 1.0, radius, 12);
    }

    private static BallEdgeDetector.Candidate nearest(
            List<BallEdgeDetector.Candidate> candidates, double x, double y) {
        BallEdgeDetector.Candidate best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BallEdgeDetector.Candidate candidate : candidates) {
            double distance = Math.hypot(candidate.x - x, candidate.y - y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= 8.0 ? best : null;
    }

    private static void drawBall(int[] pixels, int width, int height,
                                 int centerX, int centerY, double radius,
                                 int fill, int rim) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double distance = Math.hypot(x - centerX, y - centerY);
                if (distance <= radius + 1.5) {
                    pixels[y * width + x] = distance >= radius - 1.2 ? rim : fill;
                }
            }
        }
    }

    private static int rgb(int red, int green, int blue) {
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    private static double rgbDistance(int first, int second) {
        double dr = ((first >>> 16) & 0xff) - ((second >>> 16) & 0xff);
        double dg = ((first >>> 8) & 0xff) - ((second >>> 8) & 0xff);
        double db = (first & 0xff) - (second & 0xff);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
