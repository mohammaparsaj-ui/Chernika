package com.cherunika.chernika;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Small pure-Java inference runtime for the exported 52-input PyTorch MLP.
 *
 * Binary format is documented in training/chernika_rl/export_policy_binary.py.
 * This class only ranks pocket candidates; ShotPlanner/PoolPhysics remain
 * responsible for legality, collision checks, aim geometry and power choice.
 */
final class NineBallImitationPolicy {
    static final int MAGIC = 0x43484E31; // "CHN1"
    static final int FORMAT_VERSION = 1;
    static final int INPUT_SIZE = 52;
    static final int POCKET_COUNT = 6;
    static final int ACTION_SIZE = 2;

    static final class Prediction {
        final float[] pocketLogits;
        final int[] rankedPocketIndices;
        final float angleOffset;
        final float power;

        Prediction(float[] pocketLogits, int[] rankedPocketIndices,
                   float angleOffset, float power) {
            this.pocketLogits = pocketLogits;
            this.rankedPocketIndices = rankedPocketIndices;
            this.angleOffset = angleOffset;
            this.power = power;
        }
    }

    private final int hiddenSize;
    private final float[] inputWeights;
    private final float[] inputBias;
    private final float[] hiddenWeights;
    private final float[] hiddenBias;
    private final float[] pocketWeights;
    private final float[] pocketBias;
    private final float[] actionWeights;
    private final float[] actionBias;

    private NineBallImitationPolicy(
            int hiddenSize,
            float[] inputWeights, float[] inputBias,
            float[] hiddenWeights, float[] hiddenBias,
            float[] pocketWeights, float[] pocketBias,
            float[] actionWeights, float[] actionBias) {
        this.hiddenSize = hiddenSize;
        this.inputWeights = inputWeights;
        this.inputBias = inputBias;
        this.hiddenWeights = hiddenWeights;
        this.hiddenBias = hiddenBias;
        this.pocketWeights = pocketWeights;
        this.pocketBias = pocketBias;
        this.actionWeights = actionWeights;
        this.actionBias = actionBias;
    }

    static NineBallImitationPolicy load(InputStream source) throws IOException {
        if (source == null) throw new IllegalArgumentException("Model input stream is required");
        DataInputStream input = new DataInputStream(source);
        int magic = input.readInt();
        int version = input.readInt();
        int inputSize = input.readInt();
        int hiddenSize = input.readInt();
        int pocketCount = input.readInt();
        int actionSize = input.readInt();
        if (magic != MAGIC || version != FORMAT_VERSION) {
            throw new IOException("Unsupported Chernika policy binary format");
        }
        if (inputSize != INPUT_SIZE || hiddenSize < 1
                || pocketCount != POCKET_COUNT || actionSize != ACTION_SIZE) {
            throw new IOException("Chernika policy dimensions do not match this Android engine");
        }

        float[] inputWeights = readFloats(input, hiddenSize * inputSize);
        float[] inputBias = readFloats(input, hiddenSize);
        float[] hiddenWeights = readFloats(input, hiddenSize * hiddenSize);
        float[] hiddenBias = readFloats(input, hiddenSize);
        float[] pocketWeights = readFloats(input, pocketCount * hiddenSize);
        float[] pocketBias = readFloats(input, pocketCount);
        float[] actionWeights = readFloats(input, actionSize * hiddenSize);
        float[] actionBias = readFloats(input, actionSize);

        if (input.read() != -1) {
            throw new IOException("Unexpected trailing data in Chernika policy binary");
        }
        return new NineBallImitationPolicy(
                hiddenSize, inputWeights, inputBias, hiddenWeights, hiddenBias,
                pocketWeights, pocketBias, actionWeights, actionBias);
    }

    Prediction predict(float[] observation, int topK) {
        if (observation == null || observation.length != INPUT_SIZE) {
            throw new IllegalArgumentException("Expected a 52-value nine-ball observation");
        }
        if (topK < 1 || topK > POCKET_COUNT) {
            throw new IllegalArgumentException("topK must be in [1, 6]");
        }

        float[] hidden = new float[hiddenSize];
        for (int row = 0; row < hiddenSize; row++) {
            float sum = inputBias[row];
            int offset = row * INPUT_SIZE;
            for (int col = 0; col < INPUT_SIZE; col++) {
                sum += inputWeights[offset + col] * observation[col];
            }
            hidden[row] = Math.max(0.0f, sum);
        }

        float[] features = new float[hiddenSize];
        for (int row = 0; row < hiddenSize; row++) {
            float sum = hiddenBias[row];
            int offset = row * hiddenSize;
            for (int col = 0; col < hiddenSize; col++) {
                sum += hiddenWeights[offset + col] * hidden[col];
            }
            features[row] = Math.max(0.0f, sum);
        }

        float[] logits = new float[POCKET_COUNT];
        for (int row = 0; row < POCKET_COUNT; row++) {
            float sum = pocketBias[row];
            int offset = row * hiddenSize;
            for (int col = 0; col < hiddenSize; col++) {
                sum += pocketWeights[offset + col] * features[col];
            }
            logits[row] = sum;
        }

        float[] action = new float[ACTION_SIZE];
        for (int row = 0; row < ACTION_SIZE; row++) {
            float sum = actionBias[row];
            int offset = row * hiddenSize;
            for (int col = 0; col < hiddenSize; col++) {
                sum += actionWeights[offset + col] * features[col];
            }
            action[row] = (float) Math.tanh(sum);
        }

        int[] ranked = rank(logits, topK);
        return new Prediction(logits, ranked, action[0], action[1]);
    }

    private static int[] rank(float[] values, int count) {
        Integer[] indices = new Integer[values.length];
        for (int i = 0; i < values.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Float.compare(values[b], values[a]));
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = indices[i];
        return result;
    }

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
