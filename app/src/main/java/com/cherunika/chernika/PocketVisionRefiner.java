package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.List;

/**
 * Refines manually calibrated pocket anchors from dark opening pixels.
 *
 * This is deliberately conservative: it only accepts a compact, approximately
 * opening-sized dark component near the reference center. A single frame can
 * move an anchor only a fraction of its opening radius; otherwise the
 * reference calibration is retained unchanged.
 */
final class PocketVisionRefiner {
    private static final int MAX_DARK_LUMA = 58;
    private static final int MAX_DARK_CHROMA = 92;
    private static final double MIN_CONFIDENCE = 0.58;

    static final class Result {
        final List<Pocket> pockets;
        final int refinedCount;

        Result(List<Pocket> pockets, int refinedCount) {
            this.pockets = pockets;
            this.refinedCount = refinedCount;
        }
    }

    Result refine(int[] pixels, int width, int height, TableGeometry table,
                  double scaleX, double scaleY) {
        if (pixels == null || table == null || width <= 0 || height <= 0
                || pixels.length < width * height || scaleX <= 0 || scaleY <= 0) {
            return new Result(table == null ? new ArrayList<>() : table.pockets(), 0);
        }

        List<Pocket> refined = new ArrayList<>(6);
        int refinedCount = 0;
        for (Pocket pocket : table.pockets()) {
            Pocket next = refineOne(pixels, width, height, pocket, scaleX, scaleY);
            refined.add(next);
            if (next.visionRefined) refinedCount++;
        }
        return new Result(refined, refinedCount);
    }

    private Pocket refineOne(int[] pixels, int width, int height, Pocket pocket,
                             double scaleX, double scaleY) {
        double expectedWidthPx = pocket.openingWidth / scaleX;
        double expectedHeightPx = pocket.openingHeight / scaleY;
        int centerX = (int) Math.round(pocket.center.x / scaleX);
        int centerY = (int) Math.round(pocket.center.y / scaleY);
        int radiusX = Math.max(5, (int) Math.ceil(expectedWidthPx * 0.68));
        int radiusY = Math.max(5, (int) Math.ceil(expectedHeightPx * 0.68));

        int left = Math.max(0, centerX - radiusX);
        int right = Math.min(width - 1, centerX + radiusX);
        int top = Math.max(0, centerY - radiusY);
        int bottom = Math.min(height - 1, centerY + radiusY);
        int roiWidth = right - left + 1;
        int roiHeight = bottom - top + 1;
        if (roiWidth < 5 || roiHeight < 5) return pocket;

        boolean[] dark = new boolean[roiWidth * roiHeight];
        boolean[] visited = new boolean[dark.length];
        for (int y = 0; y < roiHeight; y++) {
            for (int x = 0; x < roiWidth; x++) {
                int color = pixels[(top + y) * width + left + x];
                int red = (color >>> 16) & 0xff;
                int green = (color >>> 8) & 0xff;
                int blue = color & 0xff;
                int luma = (299 * red + 587 * green + 114 * blue) / 1000;
                int chroma = Math.max(red, Math.max(green, blue))
                        - Math.min(red, Math.min(green, blue));
                dark[y * roiWidth + x] = luma <= MAX_DARK_LUMA
                        && chroma <= MAX_DARK_CHROMA;
            }
        }

        double expectedArea = Math.PI * expectedWidthPx * expectedHeightPx * 0.25;
        Candidate best = null;
        int[] queue = new int[dark.length];
        for (int start = 0; start < dark.length; start++) {
            if (!dark[start] || visited[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            int minX = start % roiWidth, maxX = minX;
            int minY = start / roiWidth, maxY = minY;
            int count = 0;
            long sumX = 0, sumY = 0;

            while (head < tail) {
                int at = queue[head++];
                int x = at % roiWidth;
                int y = at / roiWidth;
                count++;
                sumX += x;
                sumY += y;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= roiWidth || ny < 0 || ny >= roiHeight) continue;
                        int next = ny * roiWidth + nx;
                        if (dark[next] && !visited[next]) {
                            visited[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }

            int boxWidth = maxX - minX + 1;
            int boxHeight = maxY - minY + 1;
            double fill = count / (double) (boxWidth * boxHeight);
            double ratioX = boxWidth / expectedWidthPx;
            double ratioY = boxHeight / expectedHeightPx;
            double areaRatio = count / expectedArea;
            double centroidX = left + sumX / (double) count;
            double centroidY = top + sumY / (double) count;
            double dx = centroidX - centerX;
            double dy = centroidY - centerY;
            double distance = Math.hypot(dx, dy);
            double maxDistance = Math.min(expectedWidthPx, expectedHeightPx) * 0.42;

            if (areaRatio < 0.24 || areaRatio > 1.65
                    || ratioX < 0.55 || ratioX > 1.55
                    || ratioY < 0.55 || ratioY > 1.55
                    || fill < 0.28 || fill > 0.99
                    || distance > maxDistance) {
                continue;
            }

            double areaPenalty = Math.abs(Math.log(Math.max(1e-6, areaRatio)));
            double distancePenalty = distance / Math.max(1.0, maxDistance);
            double shapePenalty = Math.abs(Math.log(Math.max(1e-6, ratioX / ratioY)));
            double fillPenalty = Math.abs(fill - 0.72);
            double score = 0.40 * areaPenalty + 0.35 * distancePenalty
                    + 0.15 * shapePenalty + 0.10 * fillPenalty;
            double confidence = clamp(1.0 - score / 1.8, 0.0, 1.0);

            Candidate candidate = new Candidate(
                    centroidX, centroidY, boxWidth, boxHeight, confidence);
            if (best == null || candidate.confidence > best.confidence) best = candidate;
        }

        if (best == null || best.confidence < MIN_CONFIDENCE) return pocket;

        Vec2 measuredCenter = new Vec2(best.centerX * scaleX, best.centerY * scaleY);
        double measuredWidth = best.width * scaleX;
        double measuredHeight = best.height * scaleY;
        return pocket.withVisionMeasurement(
                measuredCenter, measuredWidth, measuredHeight, best.confidence);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Candidate {
        final double centerX;
        final double centerY;
        final double width;
        final double height;
        final double confidence;

        Candidate(double centerX, double centerY, double width, double height, double confidence) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
        }
    }
}
