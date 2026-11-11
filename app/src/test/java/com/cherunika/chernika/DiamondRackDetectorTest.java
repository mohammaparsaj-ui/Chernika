package com.cherunika.chernika;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiamondRackDetectorTest {
    private final DiamondRackDetector detector = new DiamondRackDetector();

    @Test public void detectsAStandardFiveRowDiamondRack() {
        TableGeometry table = table();
        List<Ball> balls = diamond(305, 360, 11.13);
        DiamondRackDetector.Result result = detector.detect(balls, table);
        assertTrue(result.detected);
        assertTrue(result.confidence > 0.8);
    }

    @Test public void rejectsRackWhenOneBallIsFarFromItsSlot() {
        TableGeometry table = table();
        List<Ball> balls = diamond(305, 360, 11.13);
        Ball old = balls.get(4);
        balls.set(4, new Ball(new Vec2(old.center.x + 20, old.center.y + 12),
                old.radius, old.number, false));
        assertFalse(detector.detect(balls, table).detected);
    }

    @Test public void rejectsIncompleteRack() {
        List<Ball> balls = diamond(305, 360, 11.13);
        balls.remove(8);
        assertFalse(detector.detect(balls, table()).detected);
    }

    private TableGeometry table() {
        return PlatoTableCalibration.fromDetectedFeltBounds(
                115, 160, 494, 903, 611, 1024);
    }

    private List<Ball> diamond(double centerX, double centerY, double radius) {
        double row = Math.sqrt(3.0) * radius;
        double[][] slots = {
                {0, -2 * row},
                {-radius, -row}, {radius, -row},
                {-2 * radius, 0}, {0, 0}, {2 * radius, 0},
                {-radius, row}, {radius, row},
                {0, 2 * row}
        };
        List<Ball> balls = new ArrayList<>();
        for (double[] slot : slots) {
            balls.add(new Ball(new Vec2(centerX + slot[0], centerY + slot[1]),
                    radius, 0, false));
        }
        return balls;
    }
}
