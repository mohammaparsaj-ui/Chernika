package com.cherunika.chernika;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.Assert.*;

public class ShotPlannerNeuralRankingTest {
    private NineBallImitationPolicy constantPocketPolicy(int[] pocketLogits) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(NineBallImitationPolicy.MAGIC);
        out.writeInt(NineBallImitationPolicy.FORMAT_VERSION);
        out.writeInt(52);
        out.writeInt(1);
        out.writeInt(6);
        out.writeInt(2);

        // First and second backbone layers: all-zero weights and biases.
        for (int i = 0; i < 52 + 1 + 1 + 1; i++) out.writeFloat(0.0f);
        // Pocket head: zero weights followed by the requested constant logits.
        for (int i = 0; i < 6; i++) out.writeFloat(0.0f);
        for (int value : pocketLogits) out.writeFloat((float) value);
        // Continuous action head is neutral for this ranking-only test.
        for (int i = 0; i < 4; i++) out.writeFloat(0.0f);
        out.flush();
        return NineBallImitationPolicy.load(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private FrameState frame() {
        FrameState frame = new FrameState(611, 1024);
        frame.table = PlatoTableCalibration.fromDetectedFeltBounds(
                112, 159, 495, 904, 611, 1024);
        frame.cueBall = new Ball(new Vec2(180, 700), 11.5, 0, true);
        frame.balls.add(frame.cueBall);
        frame.balls.add(new Ball(new Vec2(270, 520), 11.5, 1, false));
        frame.balls.add(new Ball(new Vec2(340, 450), 11.5, 3, false));
        return frame;
    }

    @Test public void plannerUsesThePolicyTopThreeInMeasuredPocketOrder() throws Exception {
        NineBallImitationPolicy policy = constantPocketPolicy(
                new int[]{0, 1, 3, 2, 6, 5});
        ShotPlanner planner = new ShotPlanner(policy);

        int[] preferred = planner.preferredPocketIndices(frame(), 1, 0, 7);

        assertArrayEquals(new int[]{4, 5, 2}, preferred);
    }

    @Test public void comboObjectiveBypassesPolicyTrainedOnlyForDirectLowestBallPots()
            throws Exception {
        NineBallImitationPolicy policy = constantPocketPolicy(
                new int[]{0, 1, 3, 2, 6, 5});
        ShotPlanner planner = new ShotPlanner(policy);

        assertNull(planner.preferredPocketIndices(frame(), 1, 9, 7));
    }

    @Test public void unresolvedObjectBallIdentityFallsBackToPhysicsOnlyPlanning()
            throws Exception {
        NineBallImitationPolicy policy = constantPocketPolicy(
                new int[]{0, 1, 3, 2, 6, 5});
        ShotPlanner planner = new ShotPlanner(policy);
        FrameState frame = frame();
        frame.balls.add(new Ball(new Vec2(400, 500), 11.5, 0, false));

        assertNull(planner.preferredPocketIndices(frame, 1, 0, 7));
    }
}
