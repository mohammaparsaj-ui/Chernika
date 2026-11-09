package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, screen-space representation of the Plato table.
 *
 * The felt/cushion-nose rectangle is the coordinate frame. Pocket anchors,
 * outer rails and the reference ball radius are projected from normalized
 * Plato calibration coordinates, so geometry scales with the detected table
 * rather than relying on one device's screen pixels.
 */
final class TableGeometry {
    static final class Bounds {
        final double left;
        final double top;
        final double right;
        final double bottom;
        final double width;
        final double height;

        Bounds(double left, double top, double right, double bottom) {
            if (!isFinite(left) || !isFinite(top) || !isFinite(right) || !isFinite(bottom)
                    || right <= left || bottom <= top) {
                throw new IllegalArgumentException("Bounds must be finite and have positive area");
            }
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.width = right - left;
            this.height = bottom - top;
        }

        boolean contains(Vec2 point) {
            return point != null && point.x >= left && point.x <= right
                    && point.y >= top && point.y <= bottom;
        }
    }

    /** Approximate outer timber/metal rail envelope in screenshot pixels. */
    final Bounds outerRailBounds;
    /** Green-cloth mask bounds detected from the screenshot. */
    final Bounds feltBounds;
    /** First-pass cushion-nose rectangle; currently seeded from felt bounds. */
    final Bounds cushionNoseBounds;

    // Compatibility aliases used by the shot planner. These are cushion-nose
    // coordinates, not outer-rail coordinates.
    final double left;
    final double top;
    final double right;
    final double bottom;
    final double width;
    final double height;

    /** Reference estimate; VisionAnalyzer may replace this with measured ball radii. */
    final double referenceBallRadius;
    final double referenceBallRadiusUncertainty;
    /** Fit of the detected felt aspect ratio to the Plato reference (0..1). */
    final double aspectRatioFit;
    final String calibrationId;

    private final List<Pocket> pockets;

    TableGeometry(Bounds outerRailBounds, Bounds feltBounds, Bounds cushionNoseBounds,
                  double referenceBallRadius, double referenceBallRadiusUncertainty,
                  double aspectRatioFit, String calibrationId, List<Pocket> pockets) {
        if (outerRailBounds == null || feltBounds == null || cushionNoseBounds == null
                || pockets == null || pockets.size() != 6
                || !isFinite(referenceBallRadius) || referenceBallRadius <= 0
                || !isFinite(referenceBallRadiusUncertainty) || referenceBallRadiusUncertainty < 0
                || !isFinite(aspectRatioFit) || aspectRatioFit < 0 || aspectRatioFit > 1
                || calibrationId == null || calibrationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Incomplete Plato table geometry");
        }
        this.outerRailBounds = outerRailBounds;
        this.feltBounds = feltBounds;
        this.cushionNoseBounds = cushionNoseBounds;
        this.left = cushionNoseBounds.left;
        this.top = cushionNoseBounds.top;
        this.right = cushionNoseBounds.right;
        this.bottom = cushionNoseBounds.bottom;
        this.width = cushionNoseBounds.width;
        this.height = cushionNoseBounds.height;
        this.referenceBallRadius = referenceBallRadius;
        this.referenceBallRadiusUncertainty = referenceBallRadiusUncertainty;
        this.aspectRatioFit = aspectRatioFit;
        this.calibrationId = calibrationId;
        this.pockets = Collections.unmodifiableList(new ArrayList<>(pockets));
    }

    /**
     * Tests whether a point lies inside the cushion-nose rectangle after
     * applying a pixel margin. Positive margins keep a ball/ghost center away
     * from the cushion; negative margins are permitted for diagnostics only.
     */
    boolean contains(Vec2 point, double margin) {
        return point != null
                && point.x >= left + margin && point.x <= right - margin
                && point.y >= top + margin && point.y <= bottom - margin;
    }

    /** Convert screenshot pixels to table-relative coordinates; corners are (0,0) and (1,1). */
    Vec2 screenToTable(Vec2 screenPoint) {
        if (screenPoint == null) throw new IllegalArgumentException("screenPoint is required");
        return new Vec2((screenPoint.x - left) / width, (screenPoint.y - top) / height);
    }

    /** Convert table-relative coordinates back to screenshot pixels for Android gestures. */
    Vec2 tableToScreen(Vec2 tablePoint) {
        if (tablePoint == null) throw new IllegalArgumentException("tablePoint is required");
        return new Vec2(left + tablePoint.x * width, top + tablePoint.y * height);
    }

    List<Pocket> pockets() {
        return pockets;
    }

    Pocket pocket(Pocket.Id id) {
        if (id == null) return null;
        for (Pocket pocket : pockets) if (pocket.id == id) return pocket;
        return null;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
