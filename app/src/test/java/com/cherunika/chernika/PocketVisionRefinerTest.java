package com.cherunika.chernika;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.InflaterInputStream;

import static org.junit.Assert.*;

public class PocketVisionRefinerTest {
    private static final int WIDTH = 611;
    private static final int HEIGHT = 1024;
    private static final int FELT = 0xff167a47;
    private static final int POCKET = 0xff080808;

    private TableGeometry table() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                115, 160, 494, 903, WIDTH, HEIGHT);
    }

    @Test public void refinesAllSixPocketOpeningsFromDarkImageComponents() {
        TableGeometry table = table();
        int[] pixels = greenFrame();
        drawOpenings(pixels, table, 3, -2, 1.0);

        PocketVisionRefiner.Result result = new PocketVisionRefiner().refine(
                pixels, WIDTH, HEIGHT, table, 1.0, 1.0);

        assertEquals(6, result.refinedCount);
        assertEquals(6, result.pockets.size());
        for (Pocket pocket : result.pockets) {
            assertTrue(pocket.visionRefined);
            assertTrue(pocket.visionConfidence >= 0.58);
            assertEquals(1.05, pocket.center.x - table.pocket(pocket.id).center.x, 0.6);
            assertEquals(-0.70, pocket.center.y - table.pocket(pocket.id).center.y, 0.6);
            assertEquals(pocket.center.x - table.pocket(pocket.id).center.x,
                    pocket.aimPoint.x - table.pocket(pocket.id).aimPoint.x, 1e-6);
        }
    }

    @Test public void retainsReferenceWhenNoDarkOpeningIsVisible() {
        TableGeometry table = table();
        PocketVisionRefiner.Result result = new PocketVisionRefiner().refine(
                greenFrame(), WIDTH, HEIGHT, table, 1.0, 1.0);

        assertEquals(0, result.refinedCount);
        for (Pocket pocket : result.pockets) {
            assertFalse(pocket.visionRefined);
            assertEquals(table.pocket(pocket.id).center.x, pocket.center.x, 1e-9);
            assertEquals(table.pocket(pocket.id).center.y, pocket.center.y, 1e-9);
        }
    }

    @Test public void doesNotMistakeABallSizedDarkCircleForAPocket() {
        TableGeometry table = table();
        int[] pixels = greenFrame();
        Pocket pocket = table.pocket(Pocket.Id.MIDDLE_LEFT);
        drawEllipse(pixels, pocket.center.x, pocket.center.y, 10.0, 10.0, POCKET);

        PocketVisionRefiner.Result result = new PocketVisionRefiner().refine(
                pixels, WIDTH, HEIGHT, table, 1.0, 1.0);

        assertEquals(0, result.refinedCount);
        assertFalse(result.pockets.get(2).visionRefined);
    }


    @Test public void refinesAllSixPocketsFromRecordedPlatoFrameCrops() throws Exception {
        InputStream fixture = getClass().getResourceAsStream("/six_real_pockets_small.grayz.b64");
        assertNotNull("Recorded pocket fixture must be packaged", fixture);
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

        assertTrue(raw.length >= 8);
        int width = readInt(raw, 0);
        int height = readInt(raw, 4);
        assertEquals(320, width);
        assertEquals(540, height);
        assertEquals(8 + width * height, raw.length);

        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int gray = raw[8 + i] & 0xff;
            pixels[i] = 0xff000000 | gray << 16 | gray << 8 | gray;
        }

        double scaleX = width / 720.0;
        double scaleY = height / 1216.0;
        TableGeometry recordedTable = PlatoTableCalibration.fromDetectedFeltBounds(
                136 * scaleX, 194 * scaleY, 583 * scaleX, 1069 * scaleY,
                width, height);
        PocketVisionRefiner.Result result = new PocketVisionRefiner().refine(
                pixels, width, height, recordedTable, 1.0, 1.0);

        assertEquals("All six recorded pocket openings should refine", 6, result.refinedCount);
        for (Pocket pocket : result.pockets) {
            Pocket reference = recordedTable.pocket(pocket.id);
            assertTrue("Confidence must exceed the planner gate for " + pocket.id,
                    pocket.visionConfidence >= 0.72);
            assertTrue("Refinement must stay close to the calibrated anchor for " + pocket.id,
                    pocket.center.distance(reference.center) <= 2.0);
            assertTrue(pocket.visionRefined);
        }
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xff) << 24
                | (data[offset + 1] & 0xff) << 16
                | (data[offset + 2] & 0xff) << 8
                | (data[offset + 3] & 0xff);
    }

    private int[] greenFrame() {
        int[] pixels = new int[WIDTH * HEIGHT];
        java.util.Arrays.fill(pixels, FELT);
        return pixels;
    }

    private void drawOpenings(int[] pixels, TableGeometry table,
                              double offsetX, double offsetY, double scale) {
        for (Pocket pocket : table.pockets()) {
            drawEllipse(pixels,
                    pocket.center.x + offsetX,
                    pocket.center.y + offsetY,
                    pocket.openingWidth * 0.5 * scale,
                    pocket.openingHeight * 0.5 * scale,
                    POCKET);
        }
    }

    private void drawEllipse(int[] pixels, double centerX, double centerY,
                             double radiusX, double radiusY, int color) {
        int left = Math.max(0, (int) Math.floor(centerX - radiusX));
        int right = Math.min(WIDTH - 1, (int) Math.ceil(centerX + radiusX));
        int top = Math.max(0, (int) Math.floor(centerY - radiusY));
        int bottom = Math.min(HEIGHT - 1, (int) Math.ceil(centerY + radiusY));
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                double dx = (x - centerX) / radiusX;
                double dy = (y - centerY) / radiusY;
                if (dx * dx + dy * dy <= 1.0) pixels[y * WIDTH + x] = color;
            }
        }
    }
}
