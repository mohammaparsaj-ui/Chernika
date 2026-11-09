package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical Plato 9-ball calibration from the user's 611x1024 portrait
 * screenshot, cross-checked against the 720x1216 gameplay recording profile.
 *
 * The live green-felt detector's reference bounds are (115,160)-(494,903).
 * The measured playable-table boundary estimate is (112,158)-(496,905).
 * Pocket centers, beyond-cushion entry aim points, opening widths and the
 * 11.13 px ball-radius estimate are screen-space measurements normalized to
 * the detected felt rectangle. All values scale with live detected bounds.
 *
 * The source did not conclusively distinguish felt edge from cushion nose.
 * Therefore cushionNoseBounds remains an explicit first-pass playable-boundary
 * estimate; do not treat the derived rail envelope as universal physics.
 */
final class PlatoTableCalibration {
    static final String ID = "plato-9ball-user-screenshot-611x1024-v2";

    static final double REFERENCE_FELT_LEFT = 115.0;
    static final double REFERENCE_FELT_TOP = 160.0;
    static final double REFERENCE_FELT_RIGHT = 494.0;
    static final double REFERENCE_FELT_BOTTOM = 903.0;
    static final double REFERENCE_FELT_WIDTH = REFERENCE_FELT_RIGHT - REFERENCE_FELT_LEFT;
    static final double REFERENCE_FELT_HEIGHT = REFERENCE_FELT_BOTTOM - REFERENCE_FELT_TOP;
    static final double EXPECTED_ASPECT_RATIO = REFERENCE_FELT_WIDTH / REFERENCE_FELT_HEIGHT;

    // Measured table/playable boundary estimate from the same screenshot.
    private static final double TABLE_LEFT_MARGIN_U = 3.0 / REFERENCE_FELT_WIDTH;
    private static final double TABLE_RIGHT_MARGIN_U = 2.0 / REFERENCE_FELT_WIDTH;
    private static final double TABLE_TOP_MARGIN_V = 2.0 / REFERENCE_FELT_HEIGHT;
    private static final double TABLE_BOTTOM_MARGIN_V = 2.0 / REFERENCE_FELT_HEIGHT;

    // Outer rail offsets are cross-checked against the 720x1216 recording:
    // felt (136,194)-(583,1069), outer rail envelope (96,156)-(623,1110).
    private static final double OUTER_LEFT_U = 40.0 / 447.0;
    private static final double OUTER_RIGHT_U = 40.0 / 447.0;
    private static final double OUTER_TOP_V = 38.0 / 875.0;
    private static final double OUTER_BOTTOM_V = 41.0 / 875.0;

    // User screenshot measurement. The 1 px uncertainty is a conservative
    // detector tolerance, not a statistical confidence interval from the source.
    private static final double BALL_RADIUS_U = 11.13 / REFERENCE_FELT_WIDTH;
    private static final double BALL_RADIUS_UNCERTAINTY_U = 1.0 / REFERENCE_FELT_WIDTH;

    private static final Anchor[] POCKET_ANCHORS = {
            new Anchor(Pocket.Id.TOP_LEFT,
                    118.1, 163.2, 103.1, 163.2, 47.86, true),
            new Anchor(Pocket.Id.TOP_RIGHT,
                    489.9, 163.2, 504.9, 163.2, 47.86, true),
            new Anchor(Pocket.Id.MIDDLE_LEFT,
                    108.2, 531.5, 103.1, 531.5, 44.52, false),
            new Anchor(Pocket.Id.MIDDLE_RIGHT,
                    499.8, 531.5, 504.9, 531.5, 44.52, false),
            new Anchor(Pocket.Id.BOTTOM_LEFT,
                    118.1, 899.8, 103.1, 899.8, 47.86, true),
            new Anchor(Pocket.Id.BOTTOM_RIGHT,
                    489.9, 899.8, 504.9, 899.8, 47.86, true)
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
            throw new IllegalArgumentException("Detected green region does not match portrait Plato table aspect ratio");
        }

        // Restore the small measured margin between the green mask boundary
        // and the table/playable boundary. This preserves the user's original
        // screen-space pocket and aim-point calibration.
        TableGeometry.Bounds playable = new TableGeometry.Bounds(
                left - TABLE_LEFT_MARGIN_U * felt.width,
                top - TABLE_TOP_MARGIN_V * felt.height,
                right + TABLE_RIGHT_MARGIN_U * felt.width,
                bottom + TABLE_BOTTOM_MARGIN_V * felt.height);

        TableGeometry.Bounds outer = new TableGeometry.Bounds(
                playable.left - OUTER_LEFT_U * playable.width,
                playable.top - OUTER_TOP_V * playable.height,
                playable.right + OUTER_RIGHT_U * playable.width,
                playable.bottom + OUTER_BOTTOM_V * playable.height);
        if (outer.left < 0 || outer.top < 0
                || outer.right > screenWidth || outer.bottom > screenHeight) {
            throw new IllegalArgumentException("The complete Plato rail envelope is not visible");
        }

        List<Pocket> pockets = new ArrayList<>(6);
        for (Anchor anchor : POCKET_ANCHORS) {
            Vec2 center = project(anchor.centerX, anchor.centerY, left, top, felt.width, felt.height);
            Vec2 aimPoint = project(anchor.aimX, anchor.aimY, left, top, felt.width, felt.height);
            double openingWidth = anchor.openingWidth * felt.width / REFERENCE_FELT_WIDTH;
            double openingHeight = openingWidth;
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
        final boolean corner;

        Anchor(Pocket.Id id, double centerX, double centerY,
               double aimX, double aimY, double openingWidth, boolean corner) {
            this.id = id;
            this.centerX = centerX;
            this.centerY = centerY;
            this.aimX = aimX;
            this.aimY = aimY;
            this.openingWidth = openingWidth;
            this.corner = corner;
        }
    }
}
