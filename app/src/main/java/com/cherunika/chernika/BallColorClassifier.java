package com.cherunika.chernika;

import java.util.Arrays;

/**
 * Classifies the outer shell of a Plato 9-ball crop.
 * OCR remains the strongest source when readable. Color is a fallback.
 *
 * The supplied Plato screenshots show that the 5-ball (bright orange) and
 * 7-ball (dark maroon) overlap in hue. Their value/brightness separates them:
 * the 7 is dark; the 5 is bright. Do not classify the 7 as an orange 5.
 */
final class BallColorClassifier {
    static final class Result {
        final int number;
        final double confidence;
        final String family;
        final double stripeScore;

        Result(int number, double confidence, String family, double stripeScore) {
            this.number = number;
            this.confidence = clamp(confidence, 0.0, 1.0);
            this.family = family;
            this.stripeScore = stripeScore;
        }

        static Result unknown() {
            return new Result(0, 0.0, "unknown", 0.0);
        }

        boolean isConfident() {
            return number >= 1 && number <= 9 && confidence >= 0.42;
        }
    }

    Result classify(int[] pixels, int width, int height,
                    double centerX, double centerY, double radius) {
        if (pixels == null || width <= 0 || height <= 0
                || pixels.length < width * height || radius < 3) {
            return Result.unknown();
        }

        int left = Math.max(0, (int) Math.floor(centerX - radius));
        int right = Math.min(width - 1, (int) Math.ceil(centerX + radius));
        int top = Math.max(0, (int) Math.floor(centerY - radius));
        int bottom = Math.min(height - 1, (int) Math.ceil(centerY + radius));
        int capacity = Math.max(1, (right - left + 1) * (bottom - top + 1));

        double[] hues = new double[capacity];
        double[] saturations = new double[capacity];
        double[] values = new double[capacity];
        int[] hueHistogram = new int[36];
        int hueCount = 0;
        int sampleCount = 0;

        int[] bandWhite = new int[4];
        int[] bandTotal = new int[4];
        int[] sideWhite = new int[4];
        int[] sideTotal = new int[4];
        final double[] angles = {
                0.0, Math.PI / 4.0, Math.PI / 2.0, 3.0 * Math.PI / 4.0
        };
        final double inner = radius * 0.47;
        final double outer = radius * 0.94;

        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                double dx = x + 0.5 - centerX;
                double dy = y + 0.5 - centerY;
                double distance = Math.hypot(dx, dy);
                if (distance < inner || distance > outer) continue;

                double[] hsv = rgbToHsv(pixels[y * width + x]);
                double hue = hsv[0];
                double saturation = hsv[1];
                double value = hsv[2];
                saturations[sampleCount] = saturation;
                values[sampleCount] = value;
                sampleCount++;

                if (saturation > 0.20 && value > 0.08) {
                    hues[hueCount] = hue;
                    int bin = Math.min(35, (int) (hue / 10.0));
                    hueHistogram[bin]++;
                    hueCount++;
                }

                boolean white = saturation < 0.30 && value > 0.58;
                for (int i = 0; i < angles.length; i++) {
                    double projection = -Math.sin(angles[i]) * dx
                            + Math.cos(angles[i]) * dy;
                    if (Math.abs(projection) <= radius * 0.19) {
                        bandTotal[i]++;
                        if (white) bandWhite[i]++;
                    } else if (Math.abs(projection) >= radius * 0.34) {
                        sideTotal[i]++;
                        if (white) sideWhite[i]++;
                    }
                }
            }
        }

        if (sampleCount < 12) return Result.unknown();
        double medianSaturation = median(saturations, sampleCount);
        double medianValue = median(values, sampleCount);

        // The 8-ball is nearly black. A dark maroon 7-ball has a higher
        // median value and is classified later in the warm-color branch.
        if (medianValue < 0.28) {
            double darkConfidence = clamp((0.36 - medianValue) / 0.16, 0.0, 1.0);
            double confidence = 0.68 * darkConfidence
                    + 0.32 * clamp((0.72 - medianSaturation) / 0.45, 0.0, 1.0);
            return confidence >= 0.42
                    ? new Result(8, confidence, "black", 0.0)
                    : Result.unknown();
        }
        if (hueCount < 8) return Result.unknown();

        int dominantBin = 0;
        for (int i = 1; i < hueHistogram.length; i++) {
            if (hueHistogram[i] > hueHistogram[dominantBin]) dominantBin = i;
        }
        double purity = hueHistogram[dominantBin] / (double) hueCount;

        // The reference screenshots put the red 3-ball near hue 0° and
        // orange 5 / maroon 7 around 17°–28°. Keep the split narrow.
        double redSupport = support(hues, hueCount, 0.0, 15.0)
                + support(hues, hueCount, 345.0, 360.0);
        double yellowSupport = support(hues, hueCount, 36.0, 68.0);
        double orangeSupport = support(hues, hueCount, 15.0, 40.0);
        double blueSupport = support(hues, hueCount, 190.0, 250.0);
        double purpleSupport = support(hues, hueCount, 250.0, 305.0);
        double greenSupport = support(hues, hueCount, 75.0, 165.0);

        double stripeScore = 0.0;
        double stripeBandRatio = 0.0;
        for (int i = 0; i < angles.length; i++) {
            if (bandTotal[i] == 0 || sideTotal[i] == 0) continue;
            double bandRatio = bandWhite[i] / (double) bandTotal[i];
            double sideRatio = sideWhite[i] / (double) sideTotal[i];
            double delta = bandRatio - sideRatio;
            if (delta > stripeScore) {
                stripeScore = delta;
                stripeBandRatio = bandRatio;
            }
        }

        if (yellowSupport >= 0.34) {
            int number;
            double stripeConfidence;
            if (stripeScore >= 0.22 && stripeBandRatio >= 0.43) {
                number = 9;
                stripeConfidence = clamp(stripeScore / 0.38, 0.0, 1.0);
            } else if (stripeScore <= 0.23) {
                number = 1;
                stripeConfidence = clamp(1.0 - stripeScore / 0.32, 0.0, 1.0);
            } else {
                // Keep the yellow family so the verified rack template can
                // resolve its known 1/9 slot without guessing from glare.
                return new Result(0, 0.0, "yellow", stripeScore);
            }
            double confidence = 0.55 * yellowSupport + 0.25 * purity
                    + 0.20 * stripeConfidence;
            return confidence >= 0.42
                    ? new Result(number, confidence, "yellow", stripeScore)
                    : Result.unknown();
        }

        // Plato's 7-ball is a dark maroon/brown-red. It shares some hue range
        // with the bright orange 5-ball, so classify dark warm shells first.
        if (orangeSupport >= 0.28 && medianValue < 0.52) {
            double darkWarmConfidence = clamp((0.58 - medianValue) / 0.30, 0.0, 1.0);
            double confidence = 0.55 * orangeSupport + 0.20 * purity
                    + 0.10 * medianSaturation + 0.15 * darkWarmConfidence;
            return confidence >= 0.42
                    ? new Result(7, confidence, "maroon", stripeScore)
                    : Result.unknown();
        }

        if (redSupport >= 0.28) {
            if (medianValue < 0.45) {
                double darkWarmConfidence = clamp((0.58 - medianValue) / 0.30, 0.0, 1.0);
                double confidence = 0.55 * redSupport + 0.20 * purity
                        + 0.10 * medianSaturation + 0.15 * darkWarmConfidence;
                return confidence >= 0.42
                        ? new Result(7, confidence, "maroon", stripeScore)
                        : Result.unknown();
            }
            double brightConfidence = clamp((medianValue - 0.42) / 0.42, 0.0, 1.0);
            double confidence = 0.55 * redSupport + 0.20 * purity
                    + 0.10 * medianSaturation + 0.15 * brightConfidence;
            return confidence >= 0.42
                    ? new Result(3, confidence, "red", stripeScore)
                    : Result.unknown();
        }

        if (orangeSupport >= 0.34 && medianValue >= 0.52) {
            double brightConfidence = clamp((medianValue - 0.42) / 0.42, 0.0, 1.0);
            double confidence = 0.60 * orangeSupport + 0.20 * purity
                    + 0.10 * medianSaturation + 0.10 * brightConfidence;
            return confidence >= 0.42
                    ? new Result(5, confidence, "orange", stripeScore)
                    : Result.unknown();
        }

        int number = 0;
        String family = "unknown";
        double familySupport = 0.0;
        if (blueSupport >= 0.34) {
            number = 2; family = "blue"; familySupport = blueSupport;
        } else if (purpleSupport >= 0.34) {
            number = 4; family = "purple"; familySupport = purpleSupport;
        } else if (greenSupport >= 0.34) {
            number = 6; family = "green"; familySupport = greenSupport;
        }
        if (number == 0) return Result.unknown();

        double confidence = 0.60 * familySupport + 0.20 * purity
                + 0.20 * medianSaturation;
        return confidence >= 0.42
                ? new Result(number, confidence, family, stripeScore)
                : Result.unknown();
    }

    private double support(double[] hues, int count, double min, double max) {
        int matched = 0;
        for (int i = 0; i < count; i++) {
            if (hues[i] >= min && hues[i] <= max) matched++;
        }
        return matched / (double) count;
    }

    private double median(double[] values, int count) {
        double[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        int middle = count / 2;
        return count % 2 == 0
                ? (copy[middle - 1] + copy[middle]) * 0.5
                : copy[middle];
    }

    private double[] rgbToHsv(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8) & 0xFF) / 255.0;
        double b = (argb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        double hue;
        if (delta < 1e-8) {
            hue = 0.0;
        } else if (max == r) {
            hue = 60.0 * (((g - b) / delta) % 6.0);
        } else if (max == g) {
            hue = 60.0 * (((b - r) / delta) + 2.0);
        } else {
            hue = 60.0 * (((r - g) / delta) + 4.0);
        }
        if (hue < 0.0) hue += 360.0;
        double saturation = max <= 1e-8 ? 0.0 : delta / max;
        return new double[]{hue, saturation, max};
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
