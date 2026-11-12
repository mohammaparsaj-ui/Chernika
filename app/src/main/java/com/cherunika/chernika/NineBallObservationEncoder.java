package com.cherunika.chernika;

import java.util.List;

/**
 * Builds the exact 52-value observation used by the Python nine-ball policy.
 *
 * Feature order is fixed:
 *   cue x/y (2),
 *   nine numbered-ball slots x/y/present (27),
 *   legal-ball one-hot (9),
 *   six pocket centers x/y (12),
 *   break-pending and shot-progress (2).
 *
 * Coordinates are normalized against the playable cushion-nose rectangle,
 * matching training/chernika_rl/nine_ball_env.py.
 */
final class NineBallObservationEncoder {
    static final int OBSERVATION_SIZE = 52;
    static final int DEFAULT_MAX_SHOTS = 24;
    private static final float MISSING_COORDINATE = -0.05f;

    private NineBallObservationEncoder() { }

    static float[] encode(FrameState frame, int legalBall,
                          boolean breakPending, int shotCount) {
        return encode(frame, legalBall, breakPending, shotCount, DEFAULT_MAX_SHOTS);
    }

    static float[] encode(FrameState frame, int legalBall,
                          boolean breakPending, int shotCount, int maxShots) {
        if (frame == null || frame.table == null || frame.cueBall == null) {
            throw new IllegalArgumentException("A calibrated table and cue ball are required");
        }
        if (legalBall < 1 || legalBall > 9) {
            throw new IllegalArgumentException("legalBall must be in [1, 9]");
        }
        if (shotCount < 0 || maxShots < 1) {
            throw new IllegalArgumentException("shotCount must be non-negative and maxShots positive");
        }

        float[] values = new float[OBSERVATION_SIZE];
        int cursor = 0;
        values[cursor++] = normalizeX(frame.cueBall.center.x, frame.table);
        values[cursor++] = normalizeY(frame.cueBall.center.y, frame.table);

        Ball[] numbered = new Ball[10];
        for (Ball ball : frame.balls) {
            if (ball == null || ball.cue) continue;
            if (ball.number < 1 || ball.number > 9) {
                throw new IllegalArgumentException("Every object ball needs a verified number in [1, 9]");
            }
            if (numbered[ball.number] != null) {
                throw new IllegalArgumentException("Duplicate object-ball number: " + ball.number);
            }
            numbered[ball.number] = ball;
        }
        if (numbered[legalBall] == null) {
            throw new IllegalArgumentException("The announced legal ball is not visible");
        }

        for (int number = 1; number <= 9; number++) {
            Ball ball = numbered[number];
            if (ball == null) {
                values[cursor++] = MISSING_COORDINATE;
                values[cursor++] = MISSING_COORDINATE;
                values[cursor++] = 0.0f;
            } else {
                values[cursor++] = normalizeX(ball.center.x, frame.table);
                values[cursor++] = normalizeY(ball.center.y, frame.table);
                values[cursor++] = 1.0f;
            }
        }

        for (int number = 1; number <= 9; number++) {
            values[cursor++] = number == legalBall ? 1.0f : 0.0f;
        }

        List<Pocket> pockets = frame.table.pockets();
        if (pockets.size() != 6) {
            throw new IllegalArgumentException("The calibrated table must contain six pockets");
        }
        for (Pocket pocket : pockets) {
            values[cursor++] = normalizeX(pocket.center.x, frame.table);
            values[cursor++] = normalizeY(pocket.center.y, frame.table);
        }

        values[cursor++] = breakPending ? 1.0f : 0.0f;
        values[cursor] = Math.min(1.0f, (float) shotCount / (float) maxShots);

        if (cursor + 1 != OBSERVATION_SIZE) {
            throw new IllegalStateException("Nine-ball observation feature count changed");
        }
        return values;
    }

    private static float normalizeX(double x, TableGeometry table) {
        return (float) ((x - table.left) / table.width);
    }

    private static float normalizeY(double y, TableGeometry table) {
        return (float) ((y - table.top) / table.height);
    }
}
