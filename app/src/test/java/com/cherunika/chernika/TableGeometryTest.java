package com.cherunika.chernika;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class TableGeometryTest {
    private TableGeometry referenceGeometry() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                115, 160, 494, 903, 611, 1024);
    }

    @Test public void restoresCanonicalPlayableBoundsFromDetectedGreenFelt() {
        TableGeometry table = referenceGeometry();

        assertEquals(115.0, table.feltBounds.left, 1e-6);
        assertEquals(160.0, table.feltBounds.top, 1e-6);
        assertEquals(494.0, table.feltBounds.right, 1e-6);
        assertEquals(903.0, table.feltBounds.bottom, 1e-6);
        assertEquals(112.0, table.left, 1e-6);
        assertEquals(158.0, table.top, 1e-6);
        assertEquals(496.0, table.right, 1e-6);
        assertEquals(905.0, table.bottom, 1e-6);
        assertEquals(384.0, table.width, 1e-6);
        assertEquals(747.0, table.height, 1e-6);
        assertEquals("plato-9ball-user-screenshot-611x1024-v2", table.calibrationId);
    }

    @Test public void projectsAllSixCanonicalPocketCentersAndAimPoints() {
        TableGeometry table = referenceGeometry();

        assertPocket(table, Pocket.Id.TOP_LEFT, 118.1, 163.2, 103.1, 163.2, 47.86);
        assertPocket(table, Pocket.Id.TOP_RIGHT, 489.9, 163.2, 504.9, 163.2, 47.86);
        assertPocket(table, Pocket.Id.MIDDLE_LEFT, 108.2, 531.5, 103.1, 531.5, 44.52);
        assertPocket(table, Pocket.Id.MIDDLE_RIGHT, 499.8, 531.5, 504.9, 531.5, 44.52);
        assertPocket(table, Pocket.Id.BOTTOM_LEFT, 118.1, 899.8, 103.1, 899.8, 47.86);
        assertPocket(table, Pocket.Id.BOTTOM_RIGHT, 489.9, 899.8, 504.9, 899.8, 47.86);
    }

    private void assertPocket(TableGeometry table, Pocket.Id id,
                              double cx, double cy, double ax, double ay, double opening) {
        Pocket pocket = table.pocket(id);
        assertNotNull(pocket);
        assertEquals(cx, pocket.center.x, 1e-6);
        assertEquals(cy, pocket.center.y, 1e-6);
        assertEquals(ax, pocket.aimPoint.x, 1e-6);
        assertEquals(ay, pocket.aimPoint.y, 1e-6);
        assertEquals(opening, pocket.openingWidth, 1e-6);
        assertEquals(opening, pocket.openingHeight, 1e-6);
        assertEquals(opening / 2.0, pocket.captureRadius, 1e-6);
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

    @Test public void scalesCanonicalPocketGeometryToAnotherScreenResolution() {
        TableGeometry table = PlatoTableCalibration.fromDetectedFeltBounds(
                230, 320, 988, 1806, 1222, 2048);

        assertEquals(768.0, table.width, 1e-6);
        assertEquals(1494.0, table.height, 1e-6);
        assertEquals(22.26, table.referenceBallRadius, 0.02);
        assertEquals(2.0, table.referenceBallRadiusUncertainty, 1e-6);
        assertEquals(95.72, table.pocket(Pocket.Id.TOP_LEFT).openingWidth, 0.02);
        assertEquals(89.04, table.pocket(Pocket.Id.MIDDLE_LEFT).openingWidth, 0.02);
        assertEquals(236.2, table.pocket(Pocket.Id.TOP_LEFT).center.x, 1e-6);
        assertEquals(326.4, table.pocket(Pocket.Id.TOP_LEFT).center.y, 1e-6);
        assertEquals(103.1 * 2.0, table.pocket(Pocket.Id.TOP_LEFT).aimPoint.x, 1e-6);
    }

    @Test public void scalesOuterRailEnvelopeAndRejectsClippedScreenshots() {
        TableGeometry table = referenceGeometry();

        assertEquals(77.64, table.outerRailBounds.left, 0.1);
        assertEquals(530.36, table.outerRailBounds.right, 0.1);
        assertEquals(125.54, table.outerRailBounds.top, 0.1);
        assertEquals(939.99, table.outerRailBounds.bottom, 0.1);

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

        Vec2 topLeft = table.screenToTable(new Vec2(112, 158));
        Vec2 bottomRight = table.screenToTable(new Vec2(496, 905));
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
