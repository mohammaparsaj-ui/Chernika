package com.cherunika.chernika;

/**
 * Pure-Java detector for Plato's narrow vertical power slider at the left edge.
 * The old horizontal/bottom-screen detector did not match the recorded UI.
 */
final class PowerBarDetector {
    private PowerBarDetector() { }

    static PowerBar detect(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0
                || pixels.length < width * height) return null;

        int searchRight = Math.max(1, Math.min(width, (int) Math.round(width * 0.11)));
        int searchTop = Math.max(0, (int) Math.round(height * 0.44));
        int searchBottom = Math.min(height, (int) Math.round(height * 0.90));
        int rowThreshold = Math.max(4, (int) Math.round(width * 0.009));
        int[] rowCounts = new int[height];

        for (int y = searchTop; y < searchBottom; y++) {
            int count = 0;
            for (int x = 0; x < searchRight; x++) {
                if (isPowerColor(pixels[y * width + x])) count++;
            }
            rowCounts[y] = count;
        }

        int bestStart = -1;
        int bestEnd = -1;
        int bestHeight = 0;
        int runStart = -1;
        int lastActive = -1;

        for (int y = searchTop; y < searchBottom; y++) {
            if (rowCounts[y] >= rowThreshold) {
                if (runStart < 0) runStart = y;
                lastActive = y;
            } else if (runStart >= 0 && y - lastActive > 2) {
                int runHeight = lastActive - runStart + 1;
                if (runHeight > bestHeight) {
                    bestHeight = runHeight;
                    bestStart = runStart;
                    bestEnd = lastActive;
                }
                runStart = -1;
                lastActive = -1;
            }
        }
        if (runStart >= 0) {
            int runHeight = lastActive - runStart + 1;
            if (runHeight > bestHeight) {
                bestHeight = runHeight;
                bestStart = runStart;
                bestEnd = lastActive;
            }
        }

        if (bestStart < 0 || bestHeight < height * 0.20
                || bestHeight > height * 0.50) return null;

        int minX = searchRight;
        int maxX = -1;
        int activeRows = 0;
        for (int y = bestStart; y <= bestEnd; y++) {
            if (rowCounts[y] >= rowThreshold) activeRows++;
            for (int x = 0; x < searchRight; x++) {
                if (isPowerColor(pixels[y * width + x])) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }

        int barWidth = maxX - minX + 1;
        double widthRatio = barWidth / (double) width;
        double heightRatio = bestHeight / (double) height;
        if (maxX < minX || widthRatio < 0.010 || widthRatio > 0.080
                || activeRows < bestHeight * 0.75) return null;

        double heightFit = 1.0 - Math.min(1.0, Math.abs(heightRatio - 0.35) / 0.20);
        double widthFit = 1.0 - Math.min(1.0, Math.abs(widthRatio - 0.020) / 0.025);
        double confidence = Math.max(0.0, Math.min(0.97,
                0.82 + 0.08 * heightFit + 0.06 * widthFit));

        return new PowerBar((minX + maxX) * 0.5f,
                bestStart, bestEnd, 0f, confidence);
    }

    private static boolean isPowerColor(int argb) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 28) return false;
        double saturation = max == 0 ? 0.0 : (max - min) / (double) max;
        if (saturation < 0.25) return false;

        double rf = r / 255.0;
        double gf = g / 255.0;
        double bf = b / 255.0;
        double delta = (max - min) / 255.0;
        double hue;
        if (delta < 1.0e-6) {
            hue = 0.0;
        } else if (max == r) {
            hue = 60.0 * (((gf - bf) / delta) % 6.0);
        } else if (max == g) {
            hue = 60.0 * (((bf - rf) / delta) + 2.0);
        } else {
            hue = 60.0 * (((rf - gf) / delta) + 4.0);
        }
        if (hue < 0.0) hue += 360.0;
        return hue <= 78.0 || hue >= 345.0;
    }
}
