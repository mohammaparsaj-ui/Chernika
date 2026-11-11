package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinate calibration measured from three 611x1024 Plato screenshots
 * supplied on 2026-11-11. Coordinates use the screenshot origin (0,0) at
 * the upper-left; x increases right and y increases down.
 *
 * The detected green-mask rectangle, the cushion-nose/playable rectangle,
 * and the outer rail envelope are intentionally separate coordinate layers.
 */
final class PlatoTableCalibration {
    static final String ID = "plato-9ball-611x1024-three-screenshot-v3";

    // Inclusive bounds returned by VisionAnalyzer's green-felt scan.
    static final double REFERENCE_FELT_LEFT = 112.0;
    static final double REFERENCE_FELT_TOP = 159.0;
    static final double REFERENCE_FELT_RIGHT = 495.0;
    static final double REFERENCE_FELT_BOTTOM = 904.0;
    static final double REFERENCE_FELT_WIDTH =
            REFERENCE_FELT_RIGHT - REFERENCE_FELT_LEFT;
    static final double REFERENCE_FELT_HEIGHT =
            REFERENCE_FELT_BOTTOM - REFERENCE_FELT_TOP;
    static final double EXPECTED_ASPECT_RATIO =
            REFERENCE_FELT_WIDTH / REFERENCE_FELT_HEIGHT;

    // Cushion-nose / playable center limits measured from the same screenshots:
    // (120,169) to (490,895) at 611x1024.
    private static final double PLAYABLE_LEFT_INSET_U = 8.0 / REFERENCE_FELT_WIDTH;
    private static final double PLAYABLE_TOP_INSET_V = 10.0 / REFERENCE_FELT_HEIGHT;
    private static final double PLAYABLE_RIGHT_INSET_U = 5.0 / REFERENCE_FELT_WIDTH;
    private static final double PLAYABLE_BOTTOM_INSET_V = 9.0 / REFERENCE_FELT_HEIGHT;

    // Outer metal/wood rail envelope: approximately (80,127) to (531,936).
    private static final double OUTER_LEFT_MARGIN_U = 40.0 / 370.0;
    private static final double OUTER_RIGHT_MARGIN_U = 41.0 / 370.0;
    private static final double OUTER_TOP_MARGIN_V = 42.0 / 726.0;
    private static final double OUTER_BOTTOM_MARGIN_V = 41.0 / 726.0;

    // Measured ball radius from the packed diamond and dispersed-ball frames.
    private static final double BALL_RADIUS_U = 11.5 / REFERENCE_FELT_WIDTH;
    private static final double BALL_RADIUS_UNCERTAINTY_U = 1.0 / REFERENCE_FELT_WIDTH;

    private static final Anchor[] POCKET_ANCHORS = {
            new Anchor(Pocket.Id.TOP_LEFT,
                    118.60, 163.39, 103.60, 163.39, 48.0, 47.0, true),
            new Anchor(Pocket.Id.TOP_RIGHT,
                    490.53, 163.43, 505.53, 163.43, 48.0, 47.0, true),
            new Anchor(Pocket.Id.MIDDLE_LEFT,
                    108.34, 531.65, 103.34, 531.65, 35.0, 42.0, false),
            new Anchor(Pocket.Id.MIDDLE_RIGHT,
                    500.68, 531.62, 505.68, 531.62, 35.0, 42.0, false),
            new Anchor(Pocket.Id.BOTTOM_LEFT,
                    118.61, 899.90, 103.61, 899.90, 48.0, 47.0, true),
            new Anchor(Pocket.Id.BOTTOM_RIGHT,
                    490.48, 899.83, 505.48, 899.83, 48.0, 47.0, true)
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
        if (aspect < 0.43 || aspect > 0.60) {
            throw new IllegalArgumentException(
                    "Detected green region does not match portrait Plato table aspect ratio");
        }

        // The green detector includes the inner cushion tint. The playable
        // ball-center rectangle is inset from those detected green bounds.
        TableGeometry.Bounds playable = new TableGeometry.Bounds(
                left + PLAYABLE_LEFT_INSET_U * felt.width,
                top + PLAYABLE_TOP_INSET_V * felt.height,
                right - PLAYABLE_RIGHT_INSET_U * felt.width,
                bottom - PLAYABLE_BOTTOM_INSET_V * felt.height);

        TableGeometry.Bounds outer = new TableGeometry.Bounds(
                playable.left - OUTER_LEFT_MARGIN_U * playable.width,
                playable.top - OUTER_TOP_MARGIN_V * playable.height,
                playable.right + OUTER_RIGHT_MARGIN_U * playable.width,
                playable.bottom + OUTER_BOTTOM_MARGIN_V * playable.height);
        if (outer.left < 0 || outer.top < 0
                || outer.right > screenWidth || outer.bottom > screenHeight) {
            throw new IllegalArgumentException(
                    "The complete Plato rail envelope is not visible");
        }

        List<Pocket> pockets = new ArrayList<>(6);
        for (Anchor anchor : POCKET_ANCHORS) {
            Vec2 center = project(anchor.centerX, anchor.centerY,
                    left, top, felt.width, felt.height);
            Vec2 aimPoint = project(anchor.aimX, anchor.aimY,
                    left, top, felt.width, felt.height);
            double openingWidth = anchor.openingWidth
                    * felt.width / REFERENCE_FELT_WIDTH;
            double openingHeight = anchor.openingHeight
                    * felt.height / REFERENCE_FELT_HEIGHT;
            pockets.add(new Pocket(anchor.id, center, aimPoint,
                    openingWidth, openingHeight, anchor.corner));
        }

        double aspectFit = Math.max(0.0,
                1.0 - Math.abs(aspect - EXPECTED_ASPECT_RATIO) / 0.09);
        double ballRadius = BALL_RADIUS_U * felt.width;
        double ballRadiusUncertainty = BALL_RADIUS_UNCERTAINTY_U * felt.width;

        return new TableGeometry(
                outer, felt, playable, ballRadius, ballRadiusUncertainty,
                aspectFit, ID, pockets);
    }

    private static Vec2 project(double referenceX, double referenceY,
                                double left, double top, double width, double height) {
        double u = (referenceX - REFERENCE_FELT_LEFT) / REFERENCE_FELT_WIDTH;
        double v = (referenceY - REFERENCE_FELT_TOP) / REFERENCE_FELT_HEIGHT;
        return new Vec2(left + u * width, top + v * height);
    }

    private static final class Anchor {
        final Pocket.Id id;
        final double centerX;
        final double centerY;
        final double aimX;
        final double aimY;
        final double openingWidth;
        final double openingHeight;
        final boolean corner;

        Anchor(Pocket.Id id, double centerX, double centerY,
               double aimX, double aimY, double openingWidth,
               double openingHeight, boolean corner) {
            this.id = id;
            this.centerX = centerX;
            this.centerY = centerY;
            this.aimX = aimX;
            this.aimY = aimY;
            this.openingWidth = openingWidth;
            this.openingHeight = openingHeight;
            this.corner = corner;
        }
    }
}
