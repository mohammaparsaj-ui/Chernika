package com.cherunika.chernika;

import android.graphics.Bitmap;
import android.graphics.Color;

final class PowerBarReader {
    PowerBar detect(Bitmap bitmap) {
        if (bitmap == null) return null;
        PowerBar vertical = detectVertical(bitmap);
        return vertical != null ? vertical : detectHorizontal(bitmap);
    }

    private PowerBar detectVertical(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int xStart = Math.max(0, (int) (width * 0.015));
        int xEnd = Math.min(width, (int) (width * 0.18));
        int yStart = Math.max(0, (int) (height * 0.42));
        int yEnd = Math.min(height, (int) (height * 0.91));
        if (xEnd <= xStart || yEnd <= yStart) return null;

        int[] activePerColumn = new int[width];
        float[] hsv = new float[3];
        for (int x = xStart; x < xEnd; x++) {
            int active = 0;
            for (int y = yStart; y < yEnd; y += 2) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv);
                if (hsv[1] > 0.30f && hsv[2] > 0.20f) active++;
            }
            activePerColumn[x] = active;
        }

        int minimumActive = Math.max(18, (int) ((yEnd - yStart) * 0.20 / 2.0));
        int bestStart = -1;
        int bestEnd = -1;
        int bestScore = 0;
        int x = xStart;
        while (x < xEnd) {
            while (x < xEnd && activePerColumn[x] < minimumActive) x++;
            if (x >= xEnd) break;
            int start = x;
            int score = 0;
            while (x < xEnd && activePerColumn[x] >= minimumActive) {
                score += activePerColumn[x];
                x++;
            }
            int end = x;
            if (end - start >= Math.max(6, (int) (width * 0.018))
                    && end - start <= width * 0.11 && score > bestScore) {
                bestScore = score;
                bestStart = start;
                bestEnd = end;
            }
        }
        if (bestStart < 0) return null;

        int top = yEnd;
        int bottom = yStart;
        int rowThreshold = Math.max(2, (int) ((bestEnd - bestStart) * 0.20));
        for (int y = yStart; y < yEnd; y += 2) {
            int active = 0;
            for (int px = bestStart; px < bestEnd; px++) {
                Color.colorToHSV(bitmap.getPixel(px, y), hsv);
                if (hsv[1] > 0.30f && hsv[2] > 0.20f) active++;
            }
            if (active >= rowThreshold) {
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        if (bottom - top < height * 0.25) return null;

        return new PowerBar(bestStart, bestEnd, top, bottom,
                0.5f, PowerBar.Orientation.VERTICAL);
    }

    private PowerBar detectHorizontal(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int startY = (int) (height * 0.72);
        int endY = (int) (height * 0.985);
        int startX = (int) (width * 0.06);
        int endX = (int) (width * 0.94);
        int bestY = -1;
        int bestStart = -1;
        int bestEnd = -1;
        int bestLength = 0;
        float[] hsv = new float[3];

        for (int y = startY; y < endY; y += 3) {
            int runStart = -1;
            for (int x = startX; x < endX; x += 3) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv);
                boolean active = hsv[1] > 0.38f && hsv[2] > 0.38f;
                if (active) {
                    if (runStart < 0) runStart = x;
                } else if (runStart >= 0) {
                    int length = x - runStart;
                    if (length > bestLength) {
                        bestLength = length;
                        bestY = y;
                        bestStart = runStart;
                        bestEnd = x;
                    }
                    runStart = -1;
                }
            }
            if (runStart >= 0 && endX - runStart > bestLength) {
                bestLength = endX - runStart;
                bestY = y;
                bestStart = runStart;
                bestEnd = endX;
            }
        }
        if (bestY < 0 || bestEnd - bestStart < width * 0.16) return null;
        return new PowerBar(bestStart, bestEnd, bestY - 3, bestY + 3,
                0.5f, PowerBar.Orientation.HORIZONTAL);
    }
}
