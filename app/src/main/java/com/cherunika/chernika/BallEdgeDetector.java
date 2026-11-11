package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finds ball-sized circular boundaries independently of ball/felt colour.
 *
 * This detector is deliberately colour-agnostic: the green 6 ball can have
 * nearly the same interior RGB values as the cloth, but its outer rim and
 * shading still produce a coherent radial edge. The returned score is a
 * heuristic shape score, not a calibrated probability.
 *
 * The implementation is pure Java so it can be regression-tested against
 * synthetic images and a cropped Plato rack frame without Android dependencies.
 */
final class BallEdgeDetector {
    private static final double[] RADIUS_FACTORS = {0.88, 0.94, 1.00, 1.06, 1.12};
    private static final int ANGLE_COUNT = 24;
    private static final double SAMPLE_OFFSET = 1.6;
    private static final double MIN_MEAN_CONTRAST = 14.0;
    private static final double MIN_EDGE_SUPPORT = 0.50;
    private static final double MIN_SCORE = 0.78;
    private static final double MIN_INTERIOR_BACKGROUND_CONTRAST = 4.0;
    private static final double[] COS = new double[ANGLE_COUNT];
    private static final double[] SIN = new double[ANGLE_COUNT];

    static {
        for (int i = 0; i < ANGLE_COUNT; i++) {
            double angle = 2.0 * Math.PI * i / ANGLE_COUNT;
            COS[i] = Math.cos(angle);
            SIN[i] = Math.sin(angle);
        }
    }

    static final class Candidate {
        final double x;
        final double y;
        final double radius;
        final double score;
        final int meanRed;
        final int meanGreen;
        final int meanBlue;

        Candidate(double x, double y, double radius, double score,
                  int meanRed, int meanGreen, int meanBlue) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.score = score;
            this.meanRed = meanRed;
            this.meanGreen = meanGreen;
            this.meanBlue = meanBlue;
        }
    }

    private BallEdgeDetector() { }

    /**
     * @param pixels ARGB pixels in row-major order
     * @param width image width
     * @param height image height
     * @param left/top/right/bottom detected felt bounds in analysis-image pixels
     * @param scaleX/scaleY source-screen pixels per analysis-image pixel
     * @param expectedRadiusScreen calibrated ball radius in source-screen pixels
     * @param maxCandidates maximum number of non-overlapping candidates to return
     */
    static List<Candidate> detect(int[] pixels, int width, int height,
                                  int left, int top, int right, int bottom,
                                  double scaleX, double scaleY,
                                  double expectedRadiusScreen, int maxCandidates) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height
                || left < 0 || top < 0 || right > width || bottom > height
                || right <= left || bottom <= top
                || !finitePositive(scaleX) || !finitePositive(scaleY)
                || !finitePositive(expectedRadiusScreen) || maxCandidates <= 0) {
            return Collections.emptyList();
        }

        double averageScale = (scaleX + scaleY) * 0.5;
        double expectedRadius = expectedRadiusScreen / averageScale;
        if (expectedRadius < 4.0 || !Double.isFinite(expectedRadius)) {
            return Collections.emptyList();
        }

        double maxRadius = expectedRadius * RADIUS_FACTORS[RADIUS_FACTORS.length - 1];
        int margin = (int) Math.ceil(maxRadius + SAMPLE_OFFSET + 1.0);
        int minX = Math.max(1, left + margin);
        int maxX = Math.min(width - 2, right - margin);
        int minY = Math.max(1, top + margin);
        int maxY = Math.min(height - 2, bottom - margin);
        if (maxX <= minX || maxY <= minY) return Collections.emptyList();

        int step = Math.max(2, (int) Math.round(expectedRadius * 0.16));
        List<Candidate> raw = new ArrayList<>();

        for (int y = minY; y <= maxY; y += step) {
            for (int x = minX; x <= maxX; x += step) {
                EdgeMeasure best = measureBestRadius(
                        pixels, width, height, x, y, expectedRadius);
                if (best == null || best.meanContrast < MIN_MEAN_CONTRAST
                        || best.support < MIN_EDGE_SUPPORT || best.score < MIN_SCORE) {
                    continue;
                }

                int[] meanColor = sampleMeanColor(
                        pixels, width, height, x, y, best.radius);
                int[] surroundingColor = sampleMedianColorOnRing(
                        pixels, width, height, x, y, best.radius * 2.0,
                        best.radius * 2.8);
                if (surroundingColor != null
                        && rgbDistance(meanColor, surroundingColor)
                        < MIN_INTERIOR_BACKGROUND_CONTRAST) {
                    // Plato's hollow aiming reticle can look like a ball-sized
                    // circle to the radial-edge detector. Its centre is just
                    // the felt, unlike a real filled ball (including green 6).
                    continue;
                }
                raw.add(new Candidate(
                        x * scaleX, y * scaleY, best.radius * averageScale,
                        best.score, meanColor[0], meanColor[1], meanColor[2]));
            }
        }

        raw.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed()
                .thenComparingDouble(c -> c.x)
                .thenComparingDouble(c -> c.y));

        List<Candidate> accepted = new ArrayList<>();
        for (Candidate candidate : raw) {
            boolean overlaps = false;
            for (Candidate existing : accepted) {
                double exclusion = Math.max(candidate.radius, existing.radius) * 1.65;
                if (Math.hypot(candidate.x - existing.x, candidate.y - existing.y) < exclusion) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                accepted.add(candidate);
                if (accepted.size() >= maxCandidates) break;
            }
        }
        return accepted;
    }

    private static EdgeMeasure measureBestRadius(int[] pixels, int width, int height,
                                                  int centerX, int centerY,
                                                  double expectedRadius) {
        EdgeMeasure best = null;
        for (double factor : RADIUS_FACTORS) {
            double radius = expectedRadius * factor;
            double totalContrast = 0.0;
            int supportedAngles = 0;
            int validAngles = 0;

            for (int i = 0; i < ANGLE_COUNT; i++) {
                double cos = COS[i];
                double sin = SIN[i];
                int innerX = (int) Math.round(centerX + cos * (radius - SAMPLE_OFFSET));
                int innerY = (int) Math.round(centerY + sin * (radius - SAMPLE_OFFSET));
                int outerX = (int) Math.round(centerX + cos * (radius + SAMPLE_OFFSET));
                int outerY = (int) Math.round(centerY + sin * (radius + SAMPLE_OFFSET));
                if (!inside(innerX, innerY, width, height)
                        || !inside(outerX, outerY, width, height)) {
                    continue;
                }

                int inner = pixels[innerY * width + innerX];
                int outer = pixels[outerY * width + outerX];
                double luminanceContrast = Math.abs(luminance(inner) - luminance(outer));
                double rgbContrast = (Math.abs(red(inner) - red(outer))
                        + Math.abs(green(inner) - green(outer))
                        + Math.abs(blue(inner) - blue(outer))) / 3.0;
                double contrast = 0.65 * luminanceContrast + 0.35 * rgbContrast;
                totalContrast += contrast;
                if (contrast >= 10.0) supportedAngles++;
                validAngles++;
            }

            if (validAngles < ANGLE_COUNT * 0.80) continue;
            double meanContrast = totalContrast / validAngles;
            double support = supportedAngles / (double) validAngles;
            double score = 0.55 + 0.35 * Math.min(1.0, meanContrast / 80.0)
                    + 0.10 * support;
            if (best == null || score > best.score) {
                best = new EdgeMeasure(radius, meanContrast, support, score);
            }
        }
        return best;
    }

    private static int[] sampleMeanColor(int[] pixels, int width, int height,
                                         int centerX, int centerY, double radius) {
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            double angle = i == 0 ? 0.0 : 2.0 * Math.PI * (i - 1) / 8.0;
            double radial = i == 0 ? 0.0 : radius * 0.34;
            int x = (int) Math.round(centerX + Math.cos(angle) * radial);
            int y = (int) Math.round(centerY + Math.sin(angle) * radial);
            if (!inside(x, y, width, height)) continue;
            int color = pixels[y * width + x];
            r += red(color);
            g += green(color);
            b += blue(color);
            count++;
        }
        if (count == 0) return new int[]{0, 0, 0};
        return new int[]{(int) (r / count), (int) (g / count), (int) (b / count)};
    }

    private static int[] sampleMedianColorOnRing(
            int[] pixels, int width, int height,
            int centerX, int centerY, double innerRadius, double outerRadius) {
        int[] redValues = new int[ANGLE_COUNT];
        int[] greenValues = new int[ANGLE_COUNT];
        int[] blueValues = new int[ANGLE_COUNT];
        int count = 0;
        double middleRadius = (innerRadius + outerRadius) * 0.5;
        for (int i = 0; i < ANGLE_COUNT; i++) {
            int x = (int) Math.round(centerX + COS[i] * middleRadius);
            int y = (int) Math.round(centerY + SIN[i] * middleRadius);
            if (!inside(x, y, width, height)) continue;
            int color = pixels[y * width + x];
            redValues[count] = red(color);
            greenValues[count] = green(color);
            blueValues[count] = blue(color);
            count++;
        }
        // Near a rail or pocket there may not be enough background samples;
        // in that case keep the candidate rather than rejecting it blindly.
        if (count < ANGLE_COUNT / 2) return null;
        Arrays.sort(redValues, 0, count);
        Arrays.sort(greenValues, 0, count);
        Arrays.sort(blueValues, 0, count);
        int middle = count / 2;
        if ((count & 1) == 1) {
            return new int[]{redValues[middle], greenValues[middle], blueValues[middle]};
        }
        return new int[]{
                (redValues[middle - 1] + redValues[middle]) / 2,
                (greenValues[middle - 1] + greenValues[middle]) / 2,
                (blueValues[middle - 1] + blueValues[middle]) / 2
        };
    }

    private static double rgbDistance(int[] first, int[] second) {
        double dr = first[0] - second[0];
        double dg = first[1] - second[1];
        double db = first[2] - second[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static boolean inside(int x, int y, int width, int height) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    private static double luminance(int color) {
        return 0.299 * red(color) + 0.587 * green(color) + 0.114 * blue(color);
    }

    private static int red(int color) { return (color >>> 16) & 0xff; }
    private static int green(int color) { return (color >>> 8) & 0xff; }
    private static int blue(int color) { return color & 0xff; }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static final class EdgeMeasure {
        final double radius;
        final double meanContrast;
        final double support;
        final double score;

        EdgeMeasure(double radius, double meanContrast, double support, double score) {
            this.radius = radius;
            this.meanContrast = meanContrast;
            this.support = support;
            this.score = score;
        }
    }
}
