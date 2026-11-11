package com.cherunika.chernika;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class TableGeometryTest {
    private TableGeometry referenceGeometry() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                112, 159, 495, 904, 611, 1024);
    }

    @Test public void restoresMeasuredPlayableBoundsFromDetectedGreenFelt() {
        TableGeometry table = referenceGeometry();

        assertEquals(112.0, table.feltBounds.left, 1e-6);
        assertEquals(159.0, table.feltBounds.top, 1e-6);
        assertEquals(495.0, table.feltBounds.right, 1e-6);
        assertEquals(904.0, table.feltBounds.bottom, 1e-6);
        assertEquals(120.0, table.left, 1e-6);
        assertEquals(169.0, table.top, 1e-6);
        assertEquals(490.0, table.right, 1e-6);
        assertEquals(895.0, table.bottom, 1e-6);
        assertEquals(370.0, table.width, 1e-6);
        assertEquals(726.0, table.height, 1e-6);
        assertEquals("plato-9ball-611x1024-three-screenshot-v3", table.calibrationId);
    }

    @Test public void projectsAllSixMeasuredPocketCentersAndAimPoints() {
        TableGeometry table = referenceGeometry();

        assertPocket(table, Pocket.Id.TOP_LEFT,
                118.60, 163.39, 103.60, 163.39, 48.0, 47.0);
        assertPocket(table, Pocket.Id.TOP_RIGHT,
                490.53, 163.43, 505.53, 163.43, 48.0, 47.0);
        assertPocket(table, Pocket.Id.MIDDLE_LEFT,
                108.34, 531.65, 103.34, 531.65, 35.0, 42.0);
        assertPocket(table, Pocket.Id.MIDDLE_RIGHT,
                500.68, 531.62, 505.68, 531.62, 35.0, 42.0);
        assertPocket(table, Pocket.Id.BOTTOM_LEFT,
                118.61, 899.90, 103.61, 899.90, 48.0, 47.0);
        assertPocket(table, Pocket.Id.BOTTOM_RIGHT,
                490.48, 899.83, 505.48, 899.83, 48.0, 47.0);
    }

    private void assertPocket(TableGeometry table, Pocket.Id id,
                              double cx, double cy, double ax, double ay,
                              double openingWidth, double openingHeight) {
        Pocket pocket = table.pocket(id);
        assertNotNull(pocket);
        assertEquals(cx, pocket.center.x, 1e-6);
        assertEquals(cy, pocket.center.y, 1e-6);
        assertEquals(ax, pocket.aimPoint.x, 1e-6);
        assertEquals(ay, pocket.aimPoint.y, 1e-6);
        assertEquals(openingWidth, pocket.openingWidth, 1e-6);
        assertEquals(openingHeight, pocket.openingHeight, 1e-6);
        assertEquals(Math.min(openingWidth, openingHeight) / 2.0,
                pocket.captureRadius, 1e-6);
    }

    @Test public void identifiesFourCornerAndTwoSidePockets() {
        TableGeometry table = referenceGeometry();
        Set<Pocket.Id> ids = new HashSet<>();
        int corners = 0;
        int sides = 0;

        for (Pocket pocket : table.pockets()) {
            ids.add(pocket.id);
            if (pocket.corner) corners++;
            else sides++;
        }

        assertEquals(6, table.pockets().size());
        assertEquals(6, ids.size());
        assertEquals(4, corners);
        assertEquals(2, sides);
    }

    @Test public void scalesPocketGeometryToAnotherScreenResolution() {
        TableGeometry table = PlatoTableCalibration.fromDetectedFeltBounds(
                224, 318, 990, 1808, 1222, 2048);

        assertEquals(740.0, table.width, 1e-6);
        assertEquals(1452.0, table.height, 1e-6);
        assertEquals(23.0, table.referenceBallRadius, 0.02);
        assertEquals(2.0, table.referenceBallRadiusUncertainty, 1e-6);
        assertEquals(96.0, table.pocket(Pocket.Id.TOP_LEFT).openingWidth, 0.02);
        assertEquals(94.0, table.pocket(Pocket.Id.TOP_LEFT).openingHeight, 0.02);
        assertEquals(70.0, table.pocket(Pocket.Id.MIDDLE_LEFT).openingWidth, 0.02);
        assertEquals(84.0, table.pocket(Pocket.Id.MIDDLE_LEFT).openingHeight, 0.02);
        assertEquals(237.2, table.pocket(Pocket.Id.TOP_LEFT).center.x, 0.02);
        assertEquals(326.78, table.pocket(Pocket.Id.TOP_LEFT).center.y, 0.02);
    }

    @Test public void scalesOuterRailEnvelopeAndRejectsClippedScreenshots() {
        TableGeometry table = referenceGeometry();

        assertEquals(80.0, table.outerRailBounds.left, 1e-6);
        assertEquals(531.0, table.outerRailBounds.right, 1e-6);
        assertEquals(127.0, table.outerRailBounds.top, 1e-6);
        assertEquals(936.0, table.outerRailBounds.bottom, 1e-6);

        try {
            PlatoTableCalibration.fromDetectedFeltBounds(
                    20, 100, 399, 843, 500, 1100);
            fail("A clipped outer rail must not be used for shot planning");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rail envelope"));
        }
    }

    @Test public void tableCoordinateTransformRoundTripsAndUsesPlayableBounds() {
        TableGeometry table = referenceGeometry();

        Vec2 topLeft = table.screenToTable(new Vec2(120, 169));
        Vec2 bottomRight = table.screenToTable(new Vec2(490, 895));
        assertEquals(0.0, topLeft.x, 1e-9);
        assertEquals(0.0, topLeft.y, 1e-9);
        assertEquals(1.0, bottomRight.x, 1e-9);
        assertEquals(1.0, bottomRight.y, 1e-9);

        Vec2 original = new Vec2(342.25, 701.5);
        Vec2 roundTrip = table.tableToScreen(table.screenToTable(original));
        assertEquals(original.x, roundTrip.x, 1e-9);
        assertEquals(original.y, roundTrip.y, 1e-9);
    }

    @Test public void rejectsGreenRegionsThatDoNotMatchThePortraitTable() {
        try {
            PlatoTableCalibration.fromDetectedFeltBounds(
                    100, 100, 900, 700, 1200, 1600);
            fail("A landscape green rectangle must not be accepted as the Plato table");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("aspect ratio"));
        }
    }

    @Test public void ballCenterMustRespectThePlayableBoundaryMargin() {
        TableGeometry table = referenceGeometry();

        assertTrue(table.contains(new Vec2(150, 220), 13));
        assertFalse(table.contains(new Vec2(120, 220), 13));
    }
}
