package com.cherunika.chernika;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class VisionAnalyzer {
    private static final int MAX_ANALYSIS_WIDTH = 900;
    private final PowerBarReader powerReader = new PowerBarReader();
    private final OpenCvBallDetector openCvBallDetector = new OpenCvBallDetector();
    private final PocketVisionRefiner pocketRefiner = new PocketVisionRefiner();
    private final BallColorClassifier colorClassifier = new BallColorClassifier();
    private final DiamondRackVisionDetector rackVisionDetector =
            new DiamondRackVisionDetector();
    private Vec2 cachedRackCenter;

    FrameState analyze(Bitmap source) {
        FrameState frame = new FrameState(source.getWidth(), source.getHeight());
        frame.powerBar = powerReader.detect(source);

        double ratio = Math.min(1.0, MAX_ANALYSIS_WIDTH / (double) source.getWidth());
        int w = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int h = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(source, w, h, true);
        int[] pixels = new int[w * h];
        scaled.getPixels(pixels, 0, w, 0, 0, w, h);

        float[] hsv = new float[3];
        int[] hueHistogram = new int[36];
        for (int y = 0; y < h; y += 3) {
            for (int x = 0; x < w; x += 3) {
                Color.colorToHSV(pixels[y * w + x], hsv);
                if (hsv[1] > 0.20f && hsv[2] > 0.16f && hsv[2] < 0.94f) {
                    int bin = Math.min(35, (int) (hsv[0] / 10f));
                    hueHistogram[bin]++;
                }
            }
        }
        int dominantBin = 0;
        for (int i = 1; i < hueHistogram.length; i++) {
            if (hueHistogram[i] > hueHistogram[dominantBin]) dominantBin = i;
        }
        float dominantHue = dominantBin * 10f + 5f;

        int[] rows = new int[h];
        int[] cols = new int[w];
        long red = 0, green = 0, blue = 0;
        int feltSamples = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = pixels[y * w + x];
                Color.colorToHSV(c, hsv);
                if (isFelt(hsv, dominantHue)) {
                    rows[y]++;
                    cols[x]++;
                    if (x > w * 0.2 && x < w * 0.8 && y > h * 0.2 && y < h * 0.8) {
                        red += Color.red(c);
                        green += Color.green(c);
                        blue += Color.blue(c);
                        feltSamples++;
                    }
                }
            }
        }

        int maxRow = max(rows);
        int maxCol = max(cols);
        if (maxRow < w * 0.18 || maxCol < h * 0.18) {
            recycleAnalysisBitmap(scaled, source);
            return frame;
        }
        int top = firstAbove(rows, Math.max(8, (int) (maxRow * 0.30)));
        int bottom = lastAbove(rows, Math.max(8, (int) (maxRow * 0.30)));
        int left = firstAbove(cols, Math.max(8, (int) (maxCol * 0.30)));
        int right = lastAbove(cols, Math.max(8, (int) (maxCol * 0.30)));
        if (left < 0 || top < 0 || right <= left || bottom <= top
                || right - left < w * 0.42 || bottom - top < h * 0.25) {
            recycleAnalysisBitmap(scaled, source);
            return frame;
        }

        double sx = source.getWidth() / (double) w;
        double sy = source.getHeight() / (double) h;
        try {
            frame.table = PlatoTableCalibration.fromDetectedFeltBounds(
                    left * sx, top * sy, right * sx, bottom * sy,
                    source.getWidth(), source.getHeight());
        } catch (IllegalArgumentException invalidGeometry) {
            recycleAnalysisBitmap(scaled, source);
            return frame;
        }

        PocketVisionRefiner.Result pocketResult =
                pocketRefiner.refine(pixels, w, h, frame.table, sx, sy);
        frame.refinedPocketCount = pocketResult.refinedCount;
        frame.table = frame.table.withVisionRefinements(
                pocketResult.pockets,
                frame.table.referenceBallRadius,
                frame.table.referenceBallRadiusUncertainty);

        if (feltSamples > 0) {
            int fr = (int) (red / feltSamples);
            int fg = (int) (green / feltSamples);
            int fb = (int) (blue / feltSamples);
            boolean circleVision = openCvBallDetector.detect(
                    scaled, frame, sx, sy, pixels, w, h);
            if (circleVision) {
                List<Double> observedRadii = new ArrayList<>();
                for (Ball ball : frame.balls) observedRadii.add(ball.radius);
                addMeasuredRackBalls(frame, pixels, w, h, sx, sy, observedRadii);
                BallRadiusEstimator.Estimate estimate = BallRadiusEstimator.estimate(
                        observedRadii, frame.table.referenceBallRadius,
                        frame.table.referenceBallRadiusUncertainty);
                if (estimate != null) {
                    frame.table = frame.table.withVisionRefinements(
                            frame.table.pockets(), estimate.radius, estimate.uncertainty);
                    frame.ballRadiusRefined = true;
                }
            } else {
                detectBalls(frame, pixels, w, h, left, top, right, bottom,
                        sx, sy, fr, fg, fb);
            }
        }
        recycleAnalysisBitmap(scaled, source);
        return frame;
    }


    /**
     * The connected-component pass intentionally rejects oversized blobs.
     * When those blobs are a touching 9-ball diamond, recover the nine centers
     * from the calibrated rack template and verify every color slot first.
     */
    private void addMeasuredRackBalls(FrameState frame, int[] pixels, int width, int height,
                                      double sx, double sy, List<Double> observedRadii) {
        if (!looksLikeRackCluster(frame)) {
            cachedRackCenter = null;
            return;
        }

        DiamondRackVisionDetector.Result rack = rackVisionDetector.detect(
                pixels, width, height, frame.table, sx, sy, cachedRackCenter);
        if (!rack.detected) {
            cachedRackCenter = null;
            return;
        }
        cachedRackCenter = rack.center;

        double centerX = rack.center.x;
        double centerY = rack.center.y;
        double radius = rack.ballRadius;
        frame.balls.removeIf(ball -> !ball.cue
                && Math.abs(ball.center.x - centerX) <= radius * 3.2
                && Math.abs(ball.center.y - centerY) <= radius * 4.8);
        frame.balls.addAll(rack.balls);

        // Rebuild the radius sample after replacing any merged rack blob.
        observedRadii.clear();
        for (Ball ball : frame.balls) observedRadii.add(ball.radius);
    }

    private boolean looksLikeRackCluster(FrameState frame) {
        if (frame == null || frame.table == null) return false;
        int count = 0;
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Ball ball : frame.balls) {
            if (ball.cue) continue;
            count++;
            minX = Math.min(minX, ball.center.x);
            maxX = Math.max(maxX, ball.center.x);
            minY = Math.min(minY, ball.center.y);
            maxY = Math.max(maxY, ball.center.y);
        }
        if (count == 0) return true;

        double expectedX = frame.table.left + frame.table.width * 0.50;
        double expectedY = frame.table.top + frame.table.height * 0.20;
        if (count == 1) {
            Ball only = null;
            for (Ball ball : frame.balls) if (!ball.cue) { only = ball; break; }
            return only != null
                    && Math.abs(only.center.x - expectedX) <= frame.table.width * 0.12
                    && Math.abs(only.center.y - expectedY) <= frame.table.height * 0.10;
        }

        double clusterWidth = maxX - minX;
        double clusterHeight = maxY - minY;
        double clusterCenterX = (minX + maxX) * 0.5;
        double clusterCenterY = (minY + maxY) * 0.5;
        return clusterWidth <= frame.table.width * 0.22
                && clusterHeight <= frame.table.height * 0.18
                && Math.abs(clusterCenterX - expectedX) <= frame.table.width * 0.10
                && Math.abs(clusterCenterY - expectedY) <= frame.table.height * 0.10;
    }

    private void recycleAnalysisBitmap(Bitmap scaled, Bitmap source) {
        if (scaled != source && !scaled.isRecycled()) scaled.recycle();
    }

    private boolean isFelt(float[] hsv, float dominantHue) {
        return hsv[1] > 0.16f && hsv[2] > 0.13f && hsv[2] < 0.96f
                && hueDistance(hsv[0], dominantHue) < 38f;
    }

    private float hueDistance(float a, float b) {
        float d = Math.abs(a - b);
        return Math.min(d, 360f - d);
    }

    private int max(int[] values) {
        int m = 0;
        for (int v : values) m = Math.max(m, v);
        return m;
    }

    private int firstAbove(int[] values, int threshold) {
        for (int i = 0; i < values.length; i++) if (values[i] >= threshold) return i;
        return -1;
    }

    private int lastAbove(int[] values, int threshold) {
        for (int i = values.length - 1; i >= 0; i--) if (values[i] >= threshold) return i;
        return -1;
    }

    private void detectBalls(FrameState frame, int[] pixels, int w, int h,
                             int left, int top, int right, int bottom,
                             double sx, double sy, int fr, int fg, int fb) {
        boolean[] mask = new boolean[w * h];
        boolean[] visited = new boolean[w * h];
        double analysisScale = (sx + sy) / 2.0;
        double expectedDiameter = 2.0 * frame.table.referenceBallRadius / analysisScale;
        double minDim = Math.max(4.0, expectedDiameter * 0.45);
        double maxDim = Math.max(minDim + 2.0, expectedDiameter * 1.35);
        List<Double> observedRadii = new ArrayList<>();
        for (int y = top + 2; y < bottom - 2; y++) {
            for (int x = left + 2; x < right - 2; x++) {
                int c = pixels[y * w + x];
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                double dist = Math.sqrt((r - fr) * (r - fr)
                        + (g - fg) * (g - fg) + (b - fb) * (b - fb));
                float value = Math.max(r, Math.max(g, b)) / 255f;
                if (dist > 78 && value > 0.15f) mask[y * w + x] = true;
            }
        }

        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        scan:
        for (int y = top + 2; y < bottom - 2; y++) {
            for (int x = left + 2; x < right - 2; x++) {
                int index = y * w + x;
                if (!mask[index] || visited[index]) continue;
                queue.clear();
                queue.add(index);
                visited[index] = true;
                int minX = x, maxX = x, minY = y, maxY = y, count = 0;
                long sumR = 0, sumG = 0, sumB = 0;
                while (!queue.isEmpty() && count < 3000) {
                    int at = queue.removeFirst();
                    int px = at % w, py = at / w;
                    int color = pixels[at];
                    sumR += Color.red(color);
                    sumG += Color.green(color);
                    sumB += Color.blue(color);
                    count++;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                    for (int k = 0; k < 4; k++) {
                        int nx = px + dx[k], ny = py + dy[k];
                        if (nx <= left || nx >= right || ny <= top || ny >= bottom) continue;
                        int ni = ny * w + nx;
                        if (mask[ni] && !visited[ni]) {
                            visited[ni] = true;
                            queue.addLast(ni);
                        }
                    }
                }
                int bw = maxX - minX + 1;
                int bh = maxY - minY + 1;
                double aspect = bw / (double) Math.max(1, bh);
                double diameter = (bw + bh) / 2.0;
                double fillRatio = count / (double) Math.max(1, bw * bh);
                if (count < 5 || count > 900 || diameter < minDim || diameter > maxDim
                        || aspect < 0.58 || aspect > 1.72
                        || fillRatio < 0.20 || fillRatio > 0.99) continue;

                double centerX = (minX + maxX) / 2.0;
                double centerY = (minY + maxY) / 2.0;
                double radiusInAnalysisPixels = diameter / 2.0;
                float[] avgHsv = new float[3];
                int avg = Color.rgb((int) (sumR / count),
                        (int) (sumG / count), (int) (sumB / count));
                Color.colorToHSV(avg, avgHsv);
                boolean cue = avgHsv[1] < 0.28f && avgHsv[2] > 0.60f;

                BallColorClassifier.Result color = cue
                        ? BallColorClassifier.Result.unknown()
                        : colorClassifier.classify(pixels, w, h,
                                centerX, centerY, radiusInAnalysisPixels);
                double sourceRadius = radiusInAnalysisPixels * analysisScale;
                Ball ball = new Ball(
                        new Vec2(centerX * sx, centerY * sy),
                        sourceRadius, 0, cue, color.number, color.confidence);
                frame.balls.add(ball);
                observedRadii.add(sourceRadius);
                if (cue && (frame.cueBall == null || avgHsv[2] > 0.65f)) {
                    frame.cueBall = ball;
                }
                if (frame.balls.size() >= 20) break scan;
            }
        }

        addMeasuredRackBalls(frame, pixels, w, h, sx, sy, observedRadii);

        BallRadiusEstimator.Estimate estimate = BallRadiusEstimator.estimate(
                observedRadii, frame.table.referenceBallRadius,
                frame.table.referenceBallRadiusUncertainty);
        if (estimate != null) {
            frame.table = frame.table.withVisionRefinements(
                    frame.table.pockets(), estimate.radius, estimate.uncertainty);
            frame.ballRadiusRefined = true;
        }
    }
}
