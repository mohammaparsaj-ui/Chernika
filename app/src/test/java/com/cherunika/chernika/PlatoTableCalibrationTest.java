package com.cherunika.chernika;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlatoTableCalibrationTest {
    private static final double EPSILON = 0.15;

    @Test public void restoresExactTableLayersFrom611By1024Reference() {
        TableGeometry table = PlatoTableCalibration.fromDetectedFeltBounds(
                112, 159, 495, 904, 611, 1024);

        assertEquals(120.0, table.left, EPSILON);
        assertEquals(169.0, table.top, EPSILON);
        assertEquals(490.0, table.right, EPSILON);
        assertEquals(895.0, table.bottom, EPSILON);

        assertEquals(80.0, table.outerRailBounds.left, EPSILON);
        assertEquals(127.0, table.outerRailBounds.top, EPSILON);
        assertEquals(531.0, table.outerRailBounds.right, EPSILON);
        assertEquals(936.0, table.outerRailBounds.bottom, EPSILON);
    }

    @Test public void projectsAllSixPocketCentersToMeasuredScreenshotCoordinates() {
        TableGeometry table = PlatoTableCalibration.fromDetectedFeltBounds(
                112, 159, 495, 904, 611, 1024);

        assertPocket(table, Pocket.Id.TOP_LEFT, 118.60, 163.39);
        assertPocket(table, Pocket.Id.TOP_RIGHT, 490.53, 163.43);
        assertPocket(table, Pocket.Id.MIDDLE_LEFT, 108.34, 531.65);
        assertPocket(table, Pocket.Id.MIDDLE_RIGHT, 500.68, 531.62);
        assertPocket(table, Pocket.Id.BOTTOM_LEFT, 118.61, 899.90);
        assertPocket(table, Pocket.Id.BOTTOM_RIGHT, 490.48, 899.83);
    }

    private void assertPocket(TableGeometry table, Pocket.Id id, double x, double y) {
        Pocket pocket = table.pocket(id);
        assertEquals(x, pocket.center.x, EPSILON);
        assertEquals(y, pocket.center.y, EPSILON);
    }
    @Test public void projectsTheUploadedGameplayRecordingTo720By1216() {
        TableGeometry table = PlatoTableCalibration.fromDetectedFeltBounds(
                134, 194, 585, 1069, 720, 1216);
        assertEquals(143.4, table.left, 0.25);
        assertEquals(205.7, table.top, 0.25);
        assertEquals(579.1, table.right, 0.25);
        assertEquals(1058.4, table.bottom, 0.25);
        assertPocket(table, Pocket.Id.TOP_LEFT, 141.8, 199.2);
        assertPocket(table, Pocket.Id.TOP_RIGHT, 579.7, 199.2);
        assertPocket(table, Pocket.Id.MIDDLE_LEFT, 129.7, 631.7);
        assertPocket(table, Pocket.Id.MIDDLE_RIGHT, 591.7, 631.6);
        assertPocket(table, Pocket.Id.BOTTOM_LEFT, 141.8, 1064.2);
        assertPocket(table, Pocket.Id.BOTTOM_RIGHT, 579.7, 1064.1);
    }

}
