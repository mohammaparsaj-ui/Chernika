package com.cherunika.chernika.engine;

import java.util.Arrays;

/**
 * Conservative color fallback for the green 6-ball, which can blend into
 * Plato's felt and is sometimes unreadable by OCR.
 *
 * This is not a general ball classifier. It only recognizes a dark, saturated
 * green interior that is measurably different from the brighter table cloth.
 */
public final class BallColorClassifier {
    private static final double MIN_HUE_DEGREES = 85.0;
    private static final double MAX_HUE_DEGREES = 135.0;
    private static final double MIN_SATURATION = 0.60;
    private static final double MAX_VALUE = 0.52;

    private BallColorClassifier() { }

    public static boolean isGreenSix(int[] argb, int width, int height,
                                     int centerX, int centerY, double ballRadius) {
        if (argb == null || width <= 0 || height <= 0
                || argb.length < width * height
                || !Double.isFinite(ballRadius) || ballRadius <= 0) return false;

        int sampleRadius = Math.max(2, (int) Math.round(ballRadius * 0.55));
        int left = Math.max(0, centerX - sampleRadius);
        int right = Math.min(width - 1, centerX + sampleRadius);
        int top = Math.max(0, centerY - sampleRadius);
        int bottom = Math.min(height - 1, centerY + sampleRadius);
        int capacity = (right - left + 1) * (bottom - top + 1);
        double[] hues = new double[capacity];
        double[] saturations = new double[capacity];
        double[] values = new double[capacity];
        int count = 0;
        int radiusSquared = sampleRadius * sampleRadius;

        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int dx = x - centerX;
                int dy = y - centerY;
                if (dx * dx + dy * dy > radiusSquared) continue;
                double[] hsv = toHsv(argb[y * width + x]);
                // Ignore the white printed numeral and specular highlights.
                if (hsv[1] < 0.25) continue;
                hues[count] = hsv[0];
                saturations[count] = hsv[1];
                values[count] = hsv[2];
                count++;
            }
        }

        if (count < 12) return false;
        Arrays.sort(hues, 0, count);
        Arrays.sort(saturations, 0, count);
        Arrays.sort(values, 0, count);
        double medianHue = median(hues, count);
        double medianSaturation = median(saturations, count);
        double medianValue = median(values, count);

        return medianHue >= MIN_HUE_DEGREES && medianHue <= MAX_HUE_DEGREES
                && medianSaturation >= MIN_SATURATION
                && medianValue <= MAX_VALUE;
    }

    private static double median(double[] values, int count) {
        int middle = count / 2;
        if ((count & 1) == 1) return values[middle];
        return (values[middle - 1] + values[middle]) * 0.5;
    }

    /** Returns hue in degrees, saturation and value in [0,1]. */
    private static double[] toHsv(int argb) {
        double r = ((argb >>> 16) & 0xff) / 255.0;
        double g = ((argb >>> 8) & 0xff) / 255.0;
        double b = (argb & 0xff) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double delta = max - min;

        double hue;
        if (delta < 1.0e-9) {
            hue = 0.0;
        } else if (max == r) {
            hue = 60.0 * (((g - b) / delta) % 6.0);
        } else if (max == g) {
            hue = 60.0 * (((b - r) / delta) + 2.0);
        } else {
            hue = 60.0 * (((r - g) / delta) + 4.0);
        }
        if (hue < 0.0) hue += 360.0;
        double saturation = max <= 0.0 ? 0.0 : delta / max;
        return new double[]{hue, saturation, max};
    }
}
