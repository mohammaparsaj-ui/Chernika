package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.List;

/**
 * First measured calibration for the portrait Plato 9-ball table.
 *
 * Source: manually reviewed 720x1216 Plato gameplay frame. Reference felt
 * bounds are (136,194)-(583,1069), i.e. 447x875 px. Pocket centers, outer
 * rail envelope and ball radius are stored as ratios relative to that felt
 * rectangle and are projected onto the live detected felt bounds.
 *
 * These are reference-seeded estimates, not universal physical measurements.
 * The six pocket mouths and cushion-nose lines must still be validated on
 * multiple Android devices before the planner may treat them as exact.
 */
final class PlatoTableCalibration {
    static final String ID = "plato-9ball-720x1216-reference-v1";

    static final double REFERENCE_FELT_WIDTH = 447.0;
    static final double REFERENCE_FELT_HEIGHT = 875.0;
    static final double EXPECTED_ASPECT_RATIO =
            REFERENCE_FELT_WIDTH / REFERENCE_FELT_HEIGHT;

    // A portrait table is expected. Reject other green rectangles rather than
    // silently building a dangerous geometry from an unrelated UI element.
    private static final double MIN_ASPECT_RATIO = 0.43;
    private static final double MAX_ASPECT_RATIO = 0.60;

    // Outer rail bounds measured in the same 720x1216 reference frame.
    private static final double OUTER_LEFT_U = (96.0 - 136.0) / REFERENCE_FELT_WIDTH;
    private static final double OUTER_TOP_V = (156.0 - 194.0) / REFERENCE_FELT_HEIGHT;
    private static final double OUTER_RIGHT_U = (623.0 - 136.0) / REFERENCE_FELT_WIDTH;
    private static final double OUTER_BOTTOM_V = (1110.0 - 194.0) / REFERENCE_FELT_HEIGHT;

    // Hough-circle median from the reference frame: 13 +/- 2 px.
    private static final double BALL_RADIUS_U = 13.0 / REFERENCE_FELT_WIDTH;

    private static final Anchor[] POCKET_ANCHORS = {
            new Anchor(Pocket.Id.TOP_LEFT,
                    (134.0 - 136.0) / REFERENCE_FELT_WIDTH,
                    (191.0 - 194.0) / REFERENCE_FELT_HEIGHT,
                    35.0 / REFERENCE_FELT_WIDTH, true),
            new Anchor(Pocket.Id.TOP_RIGHT,
                    (583.0 - 136.0) / REFERENCE_FELT_WIDTH,
                    (194.0 - 194.0) / REFERENCE_FELT_HEIGHT,
                    33.0 / REFERENCE_FELT_WIDTH, true),
            new Anchor(Pocket.Id.MIDDLE_LEFT,
                    (128.0 - 136.0) / REFERENCE_FELT_WIDTH,
                    (634.0 - 194.0) / REFERENCE_FELT_HEIGHT,
                    25.0 / REFERENCE_FELT_WIDTH, false),
            new Anchor(Pocket.Id.MIDDLE_RIGHT,
                    (589.0 - 136.0) / REFERENCE_FELT_WIDTH,
                    (629.0 - 194.0) / REFERENCE_FELT_HEIGHT,
                    25.0 / REFERENCE_FELT_WIDTH, false),
            new Anchor(Pocket.Id.BOTTOM_LEFT,
                    (134.0 - 136.0) / REFERENCE_FELT_WIDTH,
                    (1070.0 - 194.0) / REFERENCE_FELT_HEIGHT,
                    34.0 / REFERENCE_FELT_WIDTH, true),
            new Anchor(Pocket.Id.BOTTOM_RIGHT,
                    (585.0 - 136.0) / REFERENCE_FELT_WIDTH,
                    (1071.0 - 194.0) / REFERENCE_FELT_HEIGHT,
                    35.0 / REFERENCE_FELT_WIDTH, true)
    };

    private PlatoTableCalibration() { }

    static TableGeometry fromDetectedFeltBounds(
            double left, double top, double right, double bottom,
            int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("Invalid screenshot dimensions");
        }

        TableGeometry.Bounds felt = new TableGeometry.Bounds(left, top, right, bottom);
        if (left < 0 || top < 0 || right > screenWidth || bottom > screenHeight) {
            throw new IllegalArgumentException("Detected felt bounds exceed screenshot");
        }

        double aspect = felt.width / felt.height;
        if (aspect < MIN_ASPECT_RATIO || aspect > MAX_ASPECT_RATIO) {
            throw new IllegalArgumentException("Detected green region does not match portrait Plato table aspect ratio");
        }

        TableGeometry.Bounds outer = new TableGeometry.Bounds(
                left + OUTER_LEFT_U * felt.width,
                top + OUTER_TOP_V * felt.height,
                left + OUTER_RIGHT_U * felt.width,
                top + OUTER_BOTTOM_V * felt.height);
        // The entire rail envelope must be visible before it is used for
        // aiming, rail reflection or pocket geometry.
        if (outer.left < 0 || outer.top < 0
                || outer.right > screenWidth || outer.bottom > screenHeight) {
            throw new IllegalArgumentException("The complete Plato rail envelope is not visible");
        }

        List<Pocket> pockets = new ArrayList<>(6);
        for (Anchor anchor : POCKET_ANCHORS) {
            Vec2 center = new Vec2(
                    left + anchor.u * felt.width,
                    top + anchor.v * felt.height);
            double captureRadius = anchor.radiusU * felt.width;
            pockets.add(new Pocket(anchor.id, center, captureRadius, anchor.corner));
        }

        double aspectFit = Math.max(0.0,
                1.0 - Math.abs(aspect - EXPECTED_ASPECT_RATIO) / 0.09);
        double ballRadius = BALL_RADIUS_U * felt.width;

        // Until an image-based cushion-nose detector is added, the green-cloth
        // boundary is the explicit first-pass approximation for the nose line.
        TableGeometry.Bounds cushionNose = new TableGeometry.Bounds(
                felt.left, felt.top, felt.right, felt.bottom);

        return new TableGeometry(
                outer, felt, cushionNose, ballRadius, aspectFit, ID, pockets);
    }

    private static final class Anchor {
        final Pocket.Id id;
        final double u;
        final double v;
        final double radiusU;
        final boolean corner;

        Anchor(Pocket.Id id, double u, double v, double radiusU, boolean corner) {
            this.id = id;
            this.u = u;
            this.v = v;
            this.radiusU = radiusU;
            this.corner = corner;
        }
    }
}
