package com.cherunika.chernika;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class BallRadiusEstimatorTest {
    @Test public void estimatesMedianRadiusAndIgnoresOneLargeOutlier() {
        BallRadiusEstimator.Estimate estimate = BallRadiusEstimator.estimate(
                Arrays.asList(11.0, 11.1, 11.2, 11.3, 22.0), 11.13, 1.0);

        assertNotNull(estimate);
        assertEquals(11.15, estimate.radius, 1e-9);
        assertEquals(4, estimate.sampleCount);
        assertTrue(estimate.uncertainty >= 0.5);
        assertTrue(estimate.uncertainty <= 1.0);
    }

    @Test public void requiresAtLeastThreePlausibleBallCandidates() {
        assertNull(BallRadiusEstimator.estimate(
                Arrays.asList(11.0, 11.2), 11.13, 1.0));
        assertNull(BallRadiusEstimator.estimate(
                Arrays.asList(2.0, 11.0, 11.2), 11.13, 1.0));
    }

    @Test public void rejectsUnstableCandidateRadii() {
        assertNull(BallRadiusEstimator.estimate(
                Arrays.asList(8.0, 11.0, 14.0, 11.2), 11.13, 1.0));
    }

    @Test public void rejectsNonFiniteAndNonPositiveValues() {
        BallRadiusEstimator.Estimate estimate = BallRadiusEstimator.estimate(
                Arrays.asList(Double.NaN, -1.0, 11.0, 11.1, 11.2),
                11.13, 1.0);

        assertNotNull(estimate);
        assertEquals(3, estimate.sampleCount);
    }
}
