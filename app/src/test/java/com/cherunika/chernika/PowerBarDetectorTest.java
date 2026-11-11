package com.cherunika.chernika;

import org.junit.Test;
import static org.junit.Assert.*;

public class PowerBarDetectorTest {
    @Test public void detectsRecordedStyleVerticalSliderAtLeftEdge() {
        int width = 360;
        int height = 608;
        int[] pixels = new int[width * height];
        fill(pixels, width, height, 0xFF24212A);

        // Simulated Plato power strip at x=18..24 and y=294..508.
        for (int y = 294; y <= 508; y++) {
            double f = (y - 294) / 214.0;
            int color = f < 0.45 ? 0xFFE6E51C : (f < 0.82 ? 0xFFFF8A0A : 0xFFFF3A08);
            for (int x = 18; x <= 24; x++) pixels[y * width + x] = color;
        }

        // A short unrelated orange UI mark must not win over the tall slider.
        for (int x = 2; x < 30; x++) pixels[270 * width + x] = 0xFFFF9900;

        PowerBar bar = PowerBarDetector.detect(pixels, width, height);
        assertNotNull(bar);
        assertEquals(21.0, bar.centerX, 1.5);
        assertEquals(294.0, bar.top, 3.0);
        assertEquals(508.0, bar.bottom, 3.0);
        assertTrue(bar.confidence >= 0.80);
        assertEquals(bar.top + (bar.bottom - bar.top) * 0.295f,
                bar.yForPower(29.5f), 0.01f);
    }

    @Test public void rejectsScreenWithoutVerticalPowerSlider() {
        int width = 360;
        int height = 608;
        int[] pixels = new int[width * height];
        fill(pixels, width, height, 0xFF24212A);
        for (int x = 2; x < 35; x++) pixels[270 * width + x] = 0xFFFF9900;
        assertNull(PowerBarDetector.detect(pixels, width, height));
    }

    private void fill(int[] pixels, int width, int height, int color) {
        for (int i = 0; i < width * height; i++) pixels[i] = color;
    }
}
