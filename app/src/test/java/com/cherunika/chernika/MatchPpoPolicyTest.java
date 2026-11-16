package com.cherunika.chernika;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.Test;

public class MatchPpoPolicyTest {
    private byte[] fixture(int magic, int version) throws IOException {
        int hidden = 4;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(magic);
        out.writeInt(version);
        out.writeInt(MatchPpoPolicy.INPUT_SIZE);
        out.writeInt(hidden);
        out.writeInt(MatchPpoPolicy.ACTION_SIZE);

        float[] inputWeights = new float[hidden * MatchPpoPolicy.INPUT_SIZE];
        float[] inputBias = new float[hidden];
        inputBias[0] = 1.0f;
        for (float value : inputWeights) out.writeFloat(value);
        for (float value : inputBias) out.writeFloat(value);

        float[] hiddenWeights = new float[hidden * hidden];
        float[] hiddenBias = new float[hidden];
        hiddenBias[0] = 1.0f;
        for (float value : hiddenWeights) out.writeFloat(value);
        for (float value : hiddenBias) out.writeFloat(value);

        float[] actionWeights = new float[MatchPpoPolicy.ACTION_SIZE * hidden];
        actionWeights[0] = 0.5f;
        actionWeights[hidden] = 2.0f;
        float[] actionBias = new float[MatchPpoPolicy.ACTION_SIZE];
        for (float value : actionWeights) out.writeFloat(value);
        for (float value : actionBias) out.writeFloat(value);
        out.flush();
        return bytes.toByteArray();
    }

    @Test
    public void predictsTanhActorAndClipsGaussianMeanLikeSb3() throws Exception {
        MatchPpoPolicy policy = MatchPpoPolicy.load(new ByteArrayInputStream(
                fixture(MatchPpoPolicy.MAGIC, MatchPpoPolicy.FORMAT_VERSION)));
        MatchPpoPolicy.Prediction prediction = policy.predict(new float[52]);

        double hidden = Math.tanh(1.0);
        assertEquals(0.5 * hidden, prediction.angleOffsetNormalized, 1e-6);
        assertEquals(1.0, prediction.powerNormalized, 1e-6);
        assertEquals(97.8, prediction.powerPercent(), 1e-4);
        assertEquals(4, policy.hiddenSize());
    }

    @Test
    public void rejectsWrongMagicAndVersion() throws Exception {
        assertThrows(IOException.class, () -> MatchPpoPolicy.load(
                new ByteArrayInputStream(fixture(0, MatchPpoPolicy.FORMAT_VERSION))));
        assertThrows(IOException.class, () -> MatchPpoPolicy.load(
                new ByteArrayInputStream(fixture(MatchPpoPolicy.MAGIC, 99))));
    }

    @Test
    public void rejectsWrongObservationShapeAndNonFiniteInput() throws Exception {
        MatchPpoPolicy policy = MatchPpoPolicy.load(new ByteArrayInputStream(
                fixture(MatchPpoPolicy.MAGIC, MatchPpoPolicy.FORMAT_VERSION)));
        assertThrows(IllegalArgumentException.class, () -> policy.predict(new float[51]));
        float[] invalid = new float[52];
        invalid[3] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> policy.predict(invalid));
    }

    @Test
    public void rejectsTrailingBytes() throws Exception {
        byte[] valid = fixture(MatchPpoPolicy.MAGIC, MatchPpoPolicy.FORMAT_VERSION);
        byte[] extra = new byte[valid.length + 1];
        System.arraycopy(valid, 0, extra, 0, valid.length);
        assertThrows(IOException.class, () -> MatchPpoPolicy.load(
                new ByteArrayInputStream(extra)));
    }
}
