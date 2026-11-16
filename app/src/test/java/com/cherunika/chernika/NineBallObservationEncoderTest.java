package com.cherunika.chernika;

import org.junit.Test;

import static org.junit.Assert.*;

public class NineBallObservationEncoderTest {
    private FrameState frame() {
        FrameState frame = new FrameState(611, 1024);
        frame.table = PlatoTableCalibration.fromDetectedFeltBounds(
                112, 159, 495, 904, 611, 1024);
        frame.cueBall = new Ball(new Vec2(frame.table.left, frame.table.top),
                frame.table.referenceBallRadius, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(
                frame.table.left + frame.table.width * 0.25,
                frame.table.top + frame.table.height * 0.50),
                frame.table.referenceBallRadius, 1, false));
        frame.balls.add(new Ball(new Vec2(
                frame.table.left + frame.table.width * 0.50,
                frame.table.top + frame.table.height * 0.25),
                frame.table.referenceBallRadius, 3, false));
        frame.balls.add(new Ball(new Vec2(frame.table.right, frame.table.bottom),
                frame.table.referenceBallRadius, 9, false));
        return frame;
    }

    @Test public void encodesTheExact52FeatureOrderAndNormalization() {
        float[] values = NineBallObservationEncoder.encode(
                frame(), 3, false, 12, 24);

        assertEquals(52, values.length);
        assertEquals(0.0f, values[0], 1e-6f);
        assertEquals(0.0f, values[1], 1e-6f);

        // Ball #1 occupies features 2..4.
        assertEquals(0.25f, values[2], 1e-6f);
        assertEquals(0.50f, values[3], 1e-6f);
        assertEquals(1.0f, values[4], 1e-6f);

        // Missing #2 uses the exact Python sentinel.
        assertEquals(-0.05f, values[5], 1e-6f);
        assertEquals(-0.05f, values[6], 1e-6f);
        assertEquals(0.0f, values[7], 1e-6f);

        // Ball #3 is present and is the legal ball.
        assertEquals(0.50f, values[8], 1e-6f);
        assertEquals(0.25f, values[9], 1e-6f);
        assertEquals(1.0f, values[10], 1e-6f);
        assertEquals(1.0f, values[31], 1e-6f);
        assertEquals(0.0f, values[29], 1e-6f);

        // Pocket anchors follow Python's top-left, top-right, middle-left,
        // middle-right, bottom-left, bottom-right order.
        assertEquals((118.60f - 120.0f) / 370.0f, values[38], 1e-6f);
        assertEquals((163.39f - 169.0f) / 726.0f, values[39], 1e-6f);
        assertEquals((490.53f - 120.0f) / 370.0f, values[40], 1e-6f);

        assertEquals(0.0f, values[50], 1e-6f);
        assertEquals(0.5f, values[51], 1e-6f);
    }

    @Test public void breakFlagAndShotProgressAreEncodedAtTheFinalTwoPositions() {
        float[] values = NineBallObservationEncoder.encode(
                frame(), 1, true, 30, 24);
        assertEquals(1.0f, values[50], 1e-6f);
        assertEquals(1.0f, values[51], 1e-6f);
    }

    @Test public void rejectsAnAnnouncedBallThatIsNotVisible() {
        try {
            NineBallObservationEncoder.encode(frame(), 2, false, 0);
            fail("Expected an invisible legal ball to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not visible"));
        }
    }

    @Test public void rejectsDuplicateObjectBallNumbers() {
        FrameState frame = frame();
        frame.balls.add(new Ball(new Vec2(frame.table.left + 20, frame.table.top + 20),
                frame.table.referenceBallRadius, 3, false));
        try {
            NineBallObservationEncoder.encode(frame, 3, false, 0);
            fail("Expected duplicate object-ball number to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate"));
        }
    }
}
