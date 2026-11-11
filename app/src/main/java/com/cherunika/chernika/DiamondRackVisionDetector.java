package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Recovers a touching Plato 9-ball rack using its measured 1-2-3-2-1
 * geometry and the nine known ball-color families.
 *
 * The detector deliberately runs only in the expected upper-center rack
 * region. It samples inside each ball's outer rim, where adjacent balls do
 * not contaminate one another, and requires all nine color slots to match.
 * This is a conservative rack-verification path, not a generic ball tracker.
 */
final class DiamondRackVisionDetector {
    static final class Result {
        final boolean detected;
        final Vec2 center;
        final double ballRadius;
        final double confidence;
        final int matchedSlots;
        final List<Ball> balls;

        Result(boolean detected, Vec2 center, double ballRadius,
               double confidence, int matchedSlots, List<Ball> balls) {
            this.detected = detected;
            this.center = center;
            this.ballRadius = ballRadius;
            this.confidence = confidence;
            this.matchedSlots = matchedSlots;
            this.balls = Collections.unmodifiableList(new ArrayList<>(balls));
        }

        static Result none() {
            return new Result(false, null, 0.0, 0.0, 0, Collections.emptyList());
        }
    }

    private static final int[] SLOT_NUMBERS = {8, 6, 7, 4, 9, 5, 2, 3, 1};
    private static final String[] SLOT_FAMILIES = {
            "black", "green", "maroon", "purple", "yellow",
            "orange", "blue", "red", "yellow"
    };
    private static final double SQRT_THREE = Math.sqrt(3.0);
    private static final double SAMPLE_RADIUS_FACTOR = 0.88;
    private static final double MIN_MEAN_CONFIDENCE = 0.50;
    private static final int REQUIRED_MATCHES = 9;

    private final BallColorClassifier colorClassifier;
    private final DiamondRackDetector geometryDetector;

    DiamondRackVisionDetector() {
        this(new BallColorClassifier(), new DiamondRackDetector());
    }

    DiamondRackVisionDetector(BallColorClassifier colorClassifier,
                              DiamondRackDetector geometryDetector) {
        this.colorClassifier = colorClassifier;
        this.geometryDetector = geometryDetector;
    }

    Result detect(int[] pixels, int width, int height,
                  TableGeometry table, double sourceScaleX, double sourceScaleY) {
        if (pixels == null || width <= 0 || height <= 0
                || pixels.length < width * height || table == null
                || sourceScaleX <= 0 || sourceScaleY <= 0) {
            return Result.none();
        }

        double analysisScale = (sourceScaleX + sourceScaleY) * 0.5;
        double radius = table.referenceBallRadius / analysisScale;
        if (!isFinite(radius) || radius < 4.0 || radius > 40.0) {
            return Result.none();
        }

        // Plato keeps the rack near the upper-center of this portrait table.
        // Search a modest normalized window to absorb small capture/crop shifts.
        double expectedX = (table.left + table.width * 0.50) / sourceScaleX;
        double expectedY = (table.top + table.height * 0.20) / sourceScaleY;
        double rangeX = table.width * 0.05 / sourceScaleX;
        double rangeY = table.height * 0.05 / sourceScaleY;
        double coarseStep = Math.max(2.0, Math.round(radius * 0.35));

        Candidate best = null;
        for (double centerX = expectedX - rangeX;
             centerX <= expectedX + rangeX + 1e-6; centerX += coarseStep) {
            for (double centerY = expectedY - rangeY;
                 centerY <= expectedY + rangeY + 1e-6; centerY += coarseStep) {
                Candidate candidate = evaluate(
                        pixels, width, height, centerX, centerY, radius);
                best = better(best, candidate, expectedX, expectedY);
            }
        }
        if (best == null) return Result.none();

        // Refine the coarse maximum to sub-pixel precision.
        Candidate refined = best;
        for (double centerX = best.centerX - coarseStep;
             centerX <= best.centerX + coarseStep + 1e-6; centerX += 0.5) {
            for (double centerY = best.centerY - coarseStep;
                 centerY <= best.centerY + coarseStep + 1e-6; centerY += 0.5) {
                Candidate candidate = evaluate(
                        pixels, width, height, centerX, centerY, radius);
                refined = better(refined, candidate, expectedX, expectedY);
            }
        }
        if (refined == null || refined.confidence < MIN_MEAN_CONFIDENCE) {
            return Result.none();
        }

        List<Ball> balls = new ArrayList<>(9);
        double sourceRadius = radius * analysisScale;
        for (int i = 0; i < SLOT_NUMBERS.length; i++) {
            double ballX = refined.centerX + slotX(i) * radius;
            double ballY = refined.centerY + slotY(i) * radius;
            double confidence = refined.slotConfidences[i];
            int number = SLOT_NUMBERS[i];
            balls.add(new Ball(
                    new Vec2(ballX * sourceScaleX, ballY * sourceScaleY),
                    sourceRadius, number, false, number, confidence));
        }

        // Never expose a rack to the shot planner unless its geometry also
        // passes the independent nine-ball diamond validator.
        DiamondRackDetector.Result geometry = geometryDetector.detect(balls, table);
        if (!geometry.detected || geometry.ballCount != 9) {
            return Result.none();
        }

        Vec2 center = new Vec2(
                refined.centerX * sourceScaleX,
                refined.centerY * sourceScaleY);
        return new Result(true, center, sourceRadius,
                refined.confidence, REQUIRED_MATCHES, balls);
    }

    private Candidate evaluate(int[] pixels, int width, int height,
                               double centerX, double centerY, double radius) {
        double[] confidences = new double[9];
        double confidenceSum = 0.0;

        for (int i = 0; i < SLOT_NUMBERS.length; i++) {
            double ballX = centerX + slotX(i) * radius;
            double ballY = centerY + slotY(i) * radius;
            BallColorClassifier.Result color = colorClassifier.classify(
                    pixels, width, height, ballX, ballY,
                    radius * SAMPLE_RADIUS_FACTOR);

            String expectedFamily = SLOT_FAMILIES[i];
            if (!expectedFamily.equals(color.family)) return null;

            double confidence = color.confidence;
            if ("yellow".equals(expectedFamily)) {
                // Plato's solid 1 and striped 9 share the yellow family.
                // Their known rack slots are authoritative when glare makes
                // the stripe classifier uncertain or reverses its guess.
                if (confidence < 0.50) confidence = 0.52;
                if (SLOT_NUMBERS[i] == 9 && color.stripeScore >= 0.12) {
                    confidence += 0.10;
                } else if (SLOT_NUMBERS[i] == 1 && color.stripeScore <= 0.48) {
                    confidence += 0.05;
                }
            } else if (color.number != SLOT_NUMBERS[i]) {
                return null;
            }

            confidence = clamp(confidence, 0.0, 1.0);
            confidences[i] = confidence;
            confidenceSum += confidence;
        }

        double mean = confidenceSum / SLOT_NUMBERS.length;
        if (mean < MIN_MEAN_CONFIDENCE) return null;
        return new Candidate(centerX, centerY, radius, mean, confidences);
    }

    private Candidate better(Candidate current, Candidate candidate,
                             double expectedX, double expectedY) {
        if (candidate == null) return current;
        if (current == null || candidate.confidence > current.confidence + 1e-6) {
            return candidate;
        }
        if (Math.abs(candidate.confidence - current.confidence) <= 1e-6) {
            double candidateOffset = Math.hypot(
                    candidate.centerX - expectedX, candidate.centerY - expectedY);
            double currentOffset = Math.hypot(
                    current.centerX - expectedX, current.centerY - expectedY);
            if (candidateOffset < currentOffset) return candidate;
        }
        return current;
    }

    private double slotX(int index) {
        switch (index) {
            case 1: return -1.0;
            case 2: return 1.0;
            case 3: return -2.0;
            case 5: return 2.0;
            case 6: return -1.0;
            case 7: return 1.0;
            default: return 0.0;
        }
    }

    private double slotY(int index) {
        switch (index) {
            case 0: return -2.0 * SQRT_THREE;
            case 1:
            case 2: return -SQRT_THREE;
            case 6:
            case 7: return SQRT_THREE;
            case 8: return 2.0 * SQRT_THREE;
            default: return 0.0;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static final class Candidate {
        final double centerX;
        final double centerY;
        final double radius;
        final double confidence;
        final double[] slotConfidences;

        Candidate(double centerX, double centerY, double radius,
                  double confidence, double[] slotConfidences) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
            this.confidence = confidence;
            this.slotConfidences = slotConfidences;
        }
    }
}
