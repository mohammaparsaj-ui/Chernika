package com.cherunika.chernika;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

/** Hough-circle ball detector restricted to the calibrated playable table ROI. */
final class OpenCvBallDetector {
    private static final boolean AVAILABLE = loadOpenCv();
    private final BallColorClassifier colorClassifier = new BallColorClassifier();

    static boolean isAvailable() { return AVAILABLE; }

    private static boolean loadOpenCv() {
        try { return OpenCVLoader.initLocal(); }
        catch (Throwable ignored) { return false; }
    }

    boolean detect(Bitmap bitmap, FrameState frame,
                   double sourceScaleX, double sourceScaleY,
                   int[] pixels, int width, int height) {
        if (!AVAILABLE || bitmap == null || frame == null || frame.table == null
                || pixels == null || pixels.length < width * height
                || sourceScaleX <= 0 || sourceScaleY <= 0) return false;

        double analysisScale = (sourceScaleX + sourceScaleY) * 0.5;
        double expectedRadius = frame.table.referenceBallRadius / analysisScale;
        int minRadius = Math.max(6, (int) Math.round(expectedRadius * 0.70));
        int maxRadius = Math.max(minRadius + 2, (int) Math.round(expectedRadius * 1.25));

        int left = Math.max(0, (int) Math.floor(frame.table.left / sourceScaleX));
        int top = Math.max(0, (int) Math.floor(frame.table.top / sourceScaleY));
        int right = Math.min(width, (int) Math.ceil(frame.table.right / sourceScaleX));
        int bottom = Math.min(height, (int) Math.ceil(frame.table.bottom / sourceScaleY));
        if (right - left < expectedRadius * 8 || bottom - top < expectedRadius * 8) return false;

        Mat rgba = new Mat(), gray = new Mat(), blurred = new Mat(), circles = new Mat();
        Mat tableGray = null;
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            tableGray = gray.submat(new Rect(left, top, right - left, bottom - top));
            Imgproc.medianBlur(tableGray, blurred, 5);
            Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT,
                    1.2, Math.max(18.0, expectedRadius * 1.60),
                    80.0, 18.0, minRadius, maxRadius);

            int count = (int) circles.total();
            for (int i = 0; i < count; i++) {
                double[] circle = circles.get(0, i);
                if (circle == null || circle.length < 3) continue;
                double x = circle[0] + left, y = circle[1] + top, radius = circle[2];
                Vec2 sourceCenter = new Vec2(x * sourceScaleX, y * sourceScaleY);
                double sourceRadius = radius * analysisScale;
                if (!frame.table.contains(sourceCenter, sourceRadius * 1.05)) continue;

                boolean nearPocket = false;
                for (Pocket pocket : frame.table.pockets()) {
                    if (sourceCenter.distance(pocket.center)
                            < pocket.captureRadius + sourceRadius * 0.35) {
                        nearPocket = true;
                        break;
                    }
                }
                if (nearPocket) continue;

                BallColorClassifier.Result color =
                        colorClassifier.classify(pixels, width, height, x, y, radius * 0.92);
                double[] center = centerStats(pixels, width, height, x, y, radius * 0.40);
                boolean cue = !color.isConfident() && center[0] < 0.27 && center[1] > 0.70;
                if (!cue && !color.isConfident()) continue;
                // The 6-ball is green, but the felt is green too. Its shell is
                // substantially darker than the calibrated cloth; reject bright
                // green aim rings and felt highlights that Hough also sees.
                if (!cue && color.number == 6 && center[1] > 0.55) continue;

                Ball candidate = new Ball(sourceCenter, sourceRadius,
                        cue ? 0 : color.number, cue,
                        cue ? 0 : color.number, cue ? 0.0 : color.confidence);
                boolean duplicate = false;
                for (int j = 0; j < frame.balls.size(); j++) {
                    Ball existing = frame.balls.get(j);
                    if (existing.center.distance(candidate.center)
                            < Math.max(existing.radius, candidate.radius) * 1.25) {
                        duplicate = true;
                        if (!existing.cue && !candidate.cue
                                && candidate.colorConfidence > existing.colorConfidence) {
                            frame.balls.set(j, candidate);
                        } else if (existing.cue && candidate.cue
                                && candidate.radius > existing.radius) {
                            frame.balls.set(j, candidate);
                            frame.cueBall = candidate;
                        }
                        break;
                    }
                }
                if (duplicate) continue;
                frame.balls.add(candidate);
                if (cue && frame.cueBall == null) frame.cueBall = candidate;
                if (frame.balls.size() >= 12) break;
            }
            return !frame.balls.isEmpty();
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (tableGray != null) tableGray.release();
            rgba.release(); gray.release(); blurred.release(); circles.release();
        }
    }

    private double[] centerStats(int[] pixels, int width, int height,
                                 double cx, double cy, double radius) {
        float[] hsv = new float[3];
        double saturation = 0.0, value = 0.0;
        int count = 0, extent = Math.max(1, (int) Math.floor(radius));
        for (int dy = -extent; dy <= extent; dy++) {
            for (int dx = -extent; dx <= extent; dx++) {
                if (dx * dx + dy * dy > radius * radius) continue;
                int x = (int) Math.round(cx + dx), y = (int) Math.round(cy + dy);
                if (x < 0 || x >= width || y < 0 || y >= height) continue;
                Color.colorToHSV(pixels[y * width + x], hsv);
                saturation += hsv[1]; value += hsv[2]; count++;
            }
        }
        return count == 0 ? new double[]{1.0, 0.0}
                : new double[]{saturation / count, value / count};
    }
}
