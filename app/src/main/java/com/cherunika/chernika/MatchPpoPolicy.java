package com.cherunika.chernika;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Pure-Java inference for the 52 -> 256 -> 256 -> 2 match-level PPO actor.
 *
 * The actor is deliberately separate from NineBallImitationPolicy: the older
 * imitation model ranks pockets, while this PPO model outputs normalized
 * angle offset and power. Loading this class does not enable live shot control.
 */
final class MatchPpoPolicy {
    static final int MAGIC = 0x43484D31; // "CHM1"
    static final int FORMAT_VERSION = 1;
    static final int INPUT_SIZE = 52;
    static final int ACTION_SIZE = 2;
    static final float ACTION_MIN = -1.0f;
    static final float ACTION_MAX = 1.0f;
    static final float MAX_UI_POWER_PERCENT = 97.8f;

    static final class Prediction {
        final float angleOffsetNormalized;
        final float powerNormalized;

        Prediction(float angleOffsetNormalized, float powerNormalized) {
            this.angleOffsetNormalized = angleOffsetNormalized;
            this.powerNormalized = powerNormalized;
        }

        float powerPercent() {
            return (powerNormalized + 1.0f) * 0.5f * MAX_UI_POWER_PERCENT;
        }
    }

    private final int hiddenSize;
    private final float[] inputWeights;
    private final float[] inputBias;
    private final float[] hiddenWeights;
    private final float[] hiddenBias;
    private final float[] actionWeights;
    private final float[] actionBias;

    private MatchPpoPolicy(
            int hiddenSize,
            float[] inputWeights,
            float[] inputBias,
            float[] hiddenWeights,
            float[] hiddenBias,
            float[] actionWeights,
            float[] actionBias) {
        this.hiddenSize = hiddenSize;
        this.inputWeights = inputWeights;
        this.inputBias = inputBias;
        this.hiddenWeights = hiddenWeights;
        this.hiddenBias = hiddenBias;
        this.actionWeights = actionWeights;
        this.actionBias = actionBias;
    }

    static MatchPpoPolicy load(InputStream source) throws IOException {
        if (source == null) throw new IllegalArgumentException("Policy input stream is required");
        DataInputStream input = new DataInputStream(source);
        int magic = input.readInt();
        int version = input.readInt();
        int inputSize = input.readInt();
        int hiddenSize = input.readInt();
        int actionSize = input.readInt();

        if (magic != MAGIC || version != FORMAT_VERSION) {
            throw new IOException("Unsupported Chernika match PPO binary format");
        }
        if (inputSize != INPUT_SIZE || hiddenSize < 1 || hiddenSize > 4096
                || actionSize != ACTION_SIZE) {
            throw new IOException("Chernika match PPO dimensions do not match this runtime");
        }

        float[] inputWeights = readFloats(input, hiddenSize * inputSize);
        float[] inputBias = readFloats(input, hiddenSize);
        float[] hiddenWeights = readFloats(input, hiddenSize * hiddenSize);
        float[] hiddenBias = readFloats(input, hiddenSize);
        float[] actionWeights = readFloats(input, actionSize * hiddenSize);
        float[] actionBias = readFloats(input, actionSize);

        if (input.read() != -1) {
            throw new IOException("Unexpected trailing data in Chernika match PPO binary");
        }
        return new MatchPpoPolicy(
                hiddenSize, inputWeights, inputBias, hiddenWeights, hiddenBias,
                actionWeights, actionBias);
    }

    Prediction predict(float[] observation) {
        if (observation == null || observation.length != INPUT_SIZE) {
            throw new IllegalArgumentException("Expected a 52-value match observation");
        }
        for (float value : observation) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("Observation contains a non-finite value");
            }
        }

        float[] hidden = new float[hiddenSize];
        for (int row = 0; row < hiddenSize; row++) {
            float sum = inputBias[row];
            int offset = row * INPUT_SIZE;
            for (int col = 0; col < INPUT_SIZE; col++) {
                sum += inputWeights[offset + col] * observation[col];
            }
            hidden[row] = (float) Math.tanh(sum);
        }

        float[] features = new float[hiddenSize];
        for (int row = 0; row < hiddenSize; row++) {
            float sum = hiddenBias[row];
            int offset = row * hiddenSize;
            for (int col = 0; col < hiddenSize; col++) {
                sum += hiddenWeights[offset + col] * hidden[col];
            }
            features[row] = (float) Math.tanh(sum);
        }

        float[] action = new float[ACTION_SIZE];
        for (int row = 0; row < ACTION_SIZE; row++) {
            float sum = actionBias[row];
            int offset = row * hiddenSize;
            for (int col = 0; col < hiddenSize; col++) {
                sum += actionWeights[offset + col] * features[col];
            }
            action[row] = Math.max(ACTION_MIN, Math.min(ACTION_MAX, sum));
        }
        return new Prediction(action[0], action[1]);
    }

    int hiddenSize() { return hiddenSize; }

    private static float[] readFloats(DataInputStream input, int count) throws IOException {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = input.readFloat();
            if (Float.isNaN(values[i]) || Float.isInfinite(values[i])) {
                throw new IOException("Policy contains a non-finite weight");
            }
        }
        return values;
    }
}
