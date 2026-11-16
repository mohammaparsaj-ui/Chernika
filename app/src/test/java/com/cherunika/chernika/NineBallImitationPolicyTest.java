package com.cherunika.chernika;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

public class NineBallImitationPolicyTest {
    private byte[] modelBytes(int inputSize, boolean trailingByte) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int hidden = 1;
        out.writeInt(NineBallImitationPolicy.MAGIC);
        out.writeInt(NineBallImitationPolicy.FORMAT_VERSION);
        out.writeInt(inputSize);
        out.writeInt(hidden);
        out.writeInt(NineBallImitationPolicy.POCKET_COUNT);
        out.writeInt(NineBallImitationPolicy.ACTION_SIZE);

        // input layer: [1, 52] weights + [1] bias
        for (int i = 0; i < hidden * inputSize + hidden; i++) out.writeFloat(0.0f);
        // hidden layer: [1, 1] weights + [1] bias
        out.writeFloat(0.0f);
        out.writeFloat(0.0f);
        // pocket head: [6, 1] weights + [6] biases
        for (int i = 0; i < 6; i++) out.writeFloat(0.0f);
        for (int i = 0; i < 6; i++) out.writeFloat((float) i);
        // action head: [2, 1] weights + [2] biases
        out.writeFloat(0.0f);
        out.writeFloat(0.0f);
        out.writeFloat(0.25f);
        out.writeFloat(-0.50f);
        if (trailingByte) out.writeByte(1);
        out.flush();
        return bytes.toByteArray();
    }

    @Test public void ranksPocketsAndReturnsTheTwoContinuousOutputs() throws Exception {
        NineBallImitationPolicy policy = NineBallImitationPolicy.load(
                new java.io.ByteArrayInputStream(modelBytes(52, false)));

        NineBallImitationPolicy.Prediction prediction = policy.predict(
                new float[52], 3);

        assertArrayEquals(new int[]{5, 4, 3}, prediction.rankedPocketIndices);
        assertEquals(5.0f, prediction.pocketLogits[5], 1e-6f);
        assertEquals((float) Math.tanh(0.25), prediction.angleOffset, 1e-6f);
        assertEquals((float) Math.tanh(-0.50), prediction.power, 1e-6f);
    }

    @Test public void rejectsWrongObservationDimensionsInModelHeader() throws Exception {
        try {
            NineBallImitationPolicy.load(
                    new java.io.ByteArrayInputStream(modelBytes(16, false)));
            fail("Expected the 16-input model to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("dimensions"));
        }
    }

    @Test public void rejectsTrailingBytesToCatchCorruptOrMismatchedAssets() throws Exception {
        try {
            NineBallImitationPolicy.load(
                    new java.io.ByteArrayInputStream(modelBytes(52, true)));
            fail("Expected trailing bytes to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("trailing"));
        }
    }

    @Test public void rejectsInvalidTopK() throws Exception {
        NineBallImitationPolicy policy = NineBallImitationPolicy.load(
                new java.io.ByteArrayInputStream(modelBytes(52, false)));
        try {
            policy.predict(new float[52], 0);
            fail("Expected invalid topK to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("topK"));
        }
    }
}
