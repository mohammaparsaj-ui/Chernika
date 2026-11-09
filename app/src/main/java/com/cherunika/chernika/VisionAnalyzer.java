package com.cherunika.chernika;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class VisionAnalyzer {
    private static final int MAX_ANALYSIS_WIDTH = 900;
    private final PowerBarReader powerReader = new PowerBarReader();

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
        for (int i = 1; i < hueHistogram.length; i++) if (hueHistogram[i] > hueHistogram[dominantBin]) dominantBin = i;
        float dominantHue = dominantBin * 10f + 5f;

        int[] rows = new int[h];
        int[] cols = new int[w];
        long red = 0, green = 0, blue = 0;
        int feltSamples = 0;
        for (int y = 0; y < h; y += 1) {
            for (int x = 0; x < w; x += 1) {
                int c = pixels[y * w + x];
                Color.colorToHSV(c, hsv);
                if (isFelt(hsv, dominantHue)) {
                    rows[y]++;
                    cols[x]++;
                    if (x > w * 0.2 && x < w * 0.8 && y > h * 0.2 && y < h * 0.8) {
                        red += Color.red(c); green += Color.green(c); blue += Color.blue(c); feltSamples++;
                    }
                }
            }
        }

        int maxRow = max(rows);
        int maxCol = max(cols);
        if (maxRow < w * 0.18 || maxCol < h * 0.18) {
            scaled.recycle();
            return frame;
        }
        int top = firstAbove(rows, Math.max(8, (int) (maxRow * 0.30)));
        int bottom = lastAbove(rows, Math.max(8, (int) (maxRow * 0.30)));
        int left = firstAbove(cols, Math.max(8, (int) (maxCol * 0.30)));
        int right = lastAbove(cols, Math.max(8, (int) (maxCol * 0.30)));
        if (left < 0 || top < 0 || right <= left || bottom <= top
                || right - left < w * 0.42 || bottom - top < h * 0.25) {
            scaled.recycle();
            return frame;
        }

        double sx = source.getWidth() / (double) w;
        double sy = source.getHeight() / (double) h;
        try {
            frame.table = PlatoTableCalibration.fromDetectedFeltBounds(
                    left * sx, top * sy, right * sx, bottom * sy,
                    source.getWidth(), source.getHeight());
        } catch (IllegalArgumentException invalidGeometry) {
            // Never let a weak green-region match become a shot-planning surface.
            scaled.recycle();
            return frame;
        }

        if (feltSamples > 0) {
            int fr = (int) (red / feltSamples);
            int fg = (int) (green / feltSamples);
            int fb = (int) (blue / feltSamples);
            detectBalls(frame, pixels, w, h, left, top, right, bottom, sx, sy, fr, fg, fb);
        }
        scaled.recycle();
        return frame;
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
        double minDim = Math.max(4.0, (right - left) * 0.008);
        double maxDim = Math.max(minDim + 2, (right - left) * 0.065);
        for (int y = top + 2; y < bottom - 2; y++) {
            for (int x = left + 2; x < right - 2; x++) {
                int c = pixels[y * w + x];
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                double dist = Math.sqrt((r - fr) * (r - fr) + (g - fg) * (g - fg) + (b - fb) * (b - fb));
                float value = Math.max(r, Math.max(g, b)) / 255f;
                if (dist > 78 && value > 0.15f) mask[y * w + x] = true;
            }
        }

        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        ArrayDeque<Integer> queue = new ArrayDeque<>();
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
                    sumR += Color.red(color); sumG += Color.green(color); sumB += Color.blue(color);
                    count++;
                    minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                    for (int k = 0; k < 4; k++) {
                        int nx = px + dx[k], ny = py + dy[k];
                        if (nx <= left || nx >= right || ny <= top || ny >= bottom) continue;
                        int ni = ny * w + nx;
                        if (mask[ni] && !visited[ni]) { visited[ni] = true; queue.addLast(ni); }
                    }
                }
                int bw = maxX - minX + 1;
                int bh = maxY - minY + 1;
                double aspect = bw / (double) Math.max(1, bh);
                double diameter = (bw + bh) / 2.0;
                if (count < 5 || count > 900 || diameter < minDim || diameter > maxDim
                        || aspect < 0.58 || aspect > 1.72) continue;
                double cx = (minX + maxX) / 2.0 * sx;
                double cy = (minY + maxY) / 2.0 * sy;
                double radius = diameter * (sx + sy) / 4.0;
                float[] avgHsv = new float[3];
                int avg = Color.rgb((int) (sumR / count), (int) (sumG / count), (int) (sumB / count));
                Color.colorToHSV(avg, avgHsv);
                boolean cue = avgHsv[1] < 0.28f && avgHsv[2] > 0.60f;
                Ball ball = new Ball(new Vec2(cx, cy), radius, 0, cue);
                frame.balls.add(ball);
                if (cue && (frame.cueBall == null || avgHsv[2] > 0.65f)) frame.cueBall = ball;
                if (frame.balls.size() >= 20) return;
            }
        }
    }
}
