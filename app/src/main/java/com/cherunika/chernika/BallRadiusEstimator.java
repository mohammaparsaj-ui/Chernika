package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Robust live ball-radius estimator using the median and median absolute deviation. */
final class BallRadiusEstimator {
    static final class Estimate {
        final double radius;
        final double uncertainty;
        final int sampleCount;

        Estimate(double radius, double uncertainty, int sampleCount) {
            this.radius = radius;
            this.uncertainty = uncertainty;
            this.sampleCount = sampleCount;
        }
    }

    private BallRadiusEstimator() { }

    static Estimate estimate(List<Double> observedRadii, double priorRadius,
                             double priorUncertainty) {
        if (observedRadii == null || observedRadii.size() < 3
                || !isFinite(priorRadius) || priorRadius <= 0
                || !isFinite(priorUncertainty) || priorUncertainty < 0) {
            return null;
        }

        List<Double> valid = new ArrayList<>();
        for (Double value : observedRadii) {
            if (value == null || !isFinite(value) || value <= 0) continue;
            if (value < priorRadius * 0.65 || value > priorRadius * 1.45) continue;
            valid.add(value);
        }
        if (valid.size() < 3) return null;

        double median = median(valid);
        List<Double> deviations = new ArrayList<>(valid.size());
        for (double value : valid) deviations.add(Math.abs(value - median));
        double mad = median(deviations);

        // Reject a frame when the candidate components disagree too much to
        // represent one physical ball size.
        if (median < priorRadius * 0.75 || median > priorRadius * 1.30
                || mad > median * 0.12) {
            return null;
        }

        double robustSigma = 1.4826 * mad;
        double uncertainty = Math.max(0.5, robustSigma);
        uncertainty = Math.min(uncertainty, Math.max(priorUncertainty, 0.5));
        return new Estimate(median, uncertainty, valid.size());
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) * 0.5;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
