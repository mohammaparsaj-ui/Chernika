package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.List;

/** Verifies the opening 9-ball rack as a five-row diamond: 1 / 2 / 3 / 2 / 1. */
final class DiamondRackDetector {
    static final class Result {
        final boolean detected;
        final Vec2 center;
        final Vec2 longAxis;
        final double confidence;
        final double rmsErrorInRadii;
        final int ballCount;

        Result(boolean detected, Vec2 center, Vec2 longAxis,
               double confidence, double rmsErrorInRadii, int ballCount) {
            this.detected = detected;
            this.center = center;
            this.longAxis = longAxis;
            this.confidence = confidence;
            this.rmsErrorInRadii = rmsErrorInRadii;
            this.ballCount = ballCount;
        }

        static Result no(int count) {
            return new Result(false, null, null, 0.0, Double.POSITIVE_INFINITY, count);
        }
    }

    Result detect(List<Ball> balls, TableGeometry table) {
        if (balls == null || table == null) return Result.no(0);
        List<Ball> objects = new ArrayList<>();
        for (Ball ball : balls) if (!ball.cue) objects.add(ball);
        if (objects.size() != 9) return Result.no(objects.size());

        double radius = 0.0;
        for (Ball ball : objects) radius += ball.radius;
        radius /= objects.size();
        if (radius <= 0.0) return Result.no(objects.size());
        for (Ball ball : objects) {
            if (ball.radius < radius * 0.78 || ball.radius > radius * 1.22) {
                return Result.no(objects.size());
            }
        }

        double centerX = 0.0;
        double centerY = 0.0;
        for (Ball ball : objects) {
            centerX += ball.center.x;
            centerY += ball.center.y;
        }
        centerX /= objects.size();
        centerY /= objects.size();

        double covarianceXX = 0.0;
        double covarianceYY = 0.0;
        double covarianceXY = 0.0;
        for (Ball ball : objects) {
            double dx = ball.center.x - centerX;
            double dy = ball.center.y - centerY;
            covarianceXX += dx * dx;
            covarianceYY += dy * dy;
            covarianceXY += dx * dy;
        }
        covarianceXX /= objects.size();
        covarianceYY /= objects.size();
        covarianceXY /= objects.size();

        double theta = 0.5 * Math.atan2(2.0 * covarianceXY,
                covarianceXX - covarianceYY);
        Vec2 axis = new Vec2(Math.cos(theta), Math.sin(theta)).normalized();
        if (Math.abs(axis.y) < 0.65) return Result.no(objects.size());
        Vec2 side = new Vec2(-axis.y, axis.x);

        double rowSpacing = Math.sqrt(3.0) * radius;
        double[] expectedU = {
                -2.0 * rowSpacing,
                -rowSpacing, -rowSpacing,
                0.0, 0.0, 0.0,
                rowSpacing, rowSpacing,
                2.0 * rowSpacing
        };
        double[] expectedV = {
                0.0,
                -radius, radius,
                -2.0 * radius, 0.0, 2.0 * radius,
                -radius, radius,
                0.0
        };

        double[][] distances = new double[9][9];
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < objects.size(); i++) {
            Ball ball = objects.get(i);
            double dx = ball.center.x - centerX;
            double dy = ball.center.y - centerY;
            double u = dx * axis.x + dy * axis.y;
            double v = dx * side.x + dy * side.y;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
            for (int j = 0; j < 9; j++) {
                distances[i][j] = Math.hypot(u - expectedU[j], v - expectedV[j]);
            }
        }

        if (maxU - minU > table.height * 0.17
                || maxV - minV > table.width * 0.18) {
            return Result.no(objects.size());
        }

        boolean[] usedBalls = new boolean[9];
        boolean[] usedSlots = new boolean[9];
        double squaredError = 0.0;
        double maximumError = 0.0;
        for (int assigned = 0; assigned < 9; assigned++) {
            double best = Double.POSITIVE_INFINITY;
            int bestBall = -1;
            int bestSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (usedBalls[i]) continue;
                for (int j = 0; j < 9; j++) {
                    if (usedSlots[j]) continue;
                    if (distances[i][j] < best) {
                        best = distances[i][j];
                        bestBall = i;
                        bestSlot = j;
                    }
                }
            }
            if (bestBall < 0 || bestSlot < 0) return Result.no(objects.size());
            usedBalls[bestBall] = true;
            usedSlots[bestSlot] = true;
            squaredError += best * best;
            maximumError = Math.max(maximumError, best);
        }

        double rms = Math.sqrt(squaredError / 9.0) / radius;
        double maxNormalized = maximumError / radius;
        if (rms > 0.48 || maxNormalized > 0.78) {
            return new Result(false, new Vec2(centerX, centerY), axis,
                    0.0, rms, objects.size());
        }

        double confidence = clamp(1.0 - rms / 0.65, 0.0, 1.0);
        return new Result(true, new Vec2(centerX, centerY), axis,
                confidence, rms, objects.size());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
