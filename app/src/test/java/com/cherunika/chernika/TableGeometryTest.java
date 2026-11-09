package com.cherunika.chernika;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class TableGeometryTest {
    private TableGeometry referenceGeometry() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                136, 194, 583, 1069, 720, 1216);
    }

    @Test public void projectsReferencePocketCentersFrom720x1216Frame() {
        TableGeometry table = referenceGeometry();

        assertEquals(134.0, table.pocket(Pocket.Id.TOP_LEFT).center.x, 1e-6);
        assertEquals(191.0, table.pocket(Pocket.Id.TOP_LEFT).center.y, 1e-6);
        assertEquals(583.0, table.pocket(Pocket.Id.TOP_RIGHT).center.x, 1e-6);
        assertEquals(194.0, table.pocket(Pocket.Id.TOP_RIGHT).center.y, 1e-6);
        assertEquals(128.0, table.pocket(Pocket.Id.MIDDLE_LEFT).center.x, 1e-6);
        assertEquals(634.0, table.pocket(Pocket.Id.MIDDLE_LEFT).center.y, 1e-6);
        assertEquals(589.0, table.pocket(Pocket.Id.MIDDLE_RIGHT).center.x, 1e-6);
        assertEquals(629.0, table.pocket(Pocket.Id.MIDDLE_RIGHT).center.y, 1e-6);
        assertEquals(134.0, table.pocket(Pocket.Id.BOTTOM_LEFT).center.x, 1e-6);
        assertEquals(1070.0, table.pocket(Pocket.Id.BOTTOM_LEFT).center.y, 1e-6);
        assertEquals(585.0, table.pocket(Pocket.Id.BOTTOM_RIGHT).center.x, 1e-6);
        assertEquals(1071.0, table.pocket(Pocket.Id.BOTTOM_RIGHT).center.y, 1e-6);
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

    @Test public void scalesPocketAndRailGeometryToTheLiveScreenshot() {
        TableGeometry table = PlatoTableCalibration.fromDetectedFeltBounds(
                115, 160, 494, 903, 611, 1024);

        assertEquals(379.0, table.width, 1e-6);
        assertEquals(743.0, table.height, 1e-6);
        assertEquals(11.0, table.referenceBallRadius, 0.05);
        assertEquals(1.7, table.referenceBallRadiusUncertainty, 0.05);
        assertEquals(81.1, table.outerRailBounds.left, 0.2);
        assertEquals(528.0, table.outerRailBounds.right, 0.2);
        assertEquals(127.7, table.outerRailBounds.top, 0.2);
        assertEquals(937.8, table.outerRailBounds.bottom, 0.2);
        assertEquals(108.2, table.pocket(Pocket.Id.MIDDLE_LEFT).center.x, 0.2);
        assertEquals(533.7, table.pocket(Pocket.Id.MIDDLE_LEFT).center.y, 0.2);
    }

    @Test public void tableCoordinateTransformRoundTripsAndUsesFeltCornersAsZeroAndOne() {
        TableGeometry table = referenceGeometry();

        Vec2 topLeft = table.screenToTable(new Vec2(136, 194));
        Vec2 bottomRight = table.screenToTable(new Vec2(583, 1069));
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

    @Test public void rejectsScreenshotsWhereTheOuterRailEnvelopeIsCutOff() {
        try {
            PlatoTableCalibration.fromDetectedFeltBounds(
                    20, 100, 467, 975, 500, 1100);
            fail("A clipped outer rail must not be used for shot planning");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rail envelope"));
        }
    }

    @Test public void ballCenterMustRespectTheCushionNoseMargin() {
        TableGeometry table = referenceGeometry();

        assertTrue(table.contains(new Vec2(150, 220), 13));
        assertFalse(table.contains(new Vec2(140, 220), 13));
    }
}
