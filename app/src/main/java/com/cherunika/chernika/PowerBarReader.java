package com.cherunika.chernika;

import android.graphics.Bitmap;
import android.graphics.Color;

final class PowerBarReader {
    PowerBar detect(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int startY = (int) (height * 0.72);
        int endY = (int) (height * 0.985);
        int startX = (int) (width * 0.06);
        int endX = (int) (width * 0.94);
        int bestY = -1, bestStart = -1, bestEnd = -1, bestLength = 0;
        float[] hsv = new float[3];

        for (int y = startY; y < endY; y += 3) {
            int runStart = -1, longestStart = -1, longestEnd = -1, longest = 0;
            for (int x = startX; x < endX; x += 3) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv);
                boolean active = hsv[1] > 0.38f && hsv[2] > 0.38f;
                if (active) {
                    if (runStart < 0) runStart = x;
                } else if (runStart >= 0) {
                    int length = x - runStart;
                    if (length > longest) { longest = length; longestStart = runStart; longestEnd = x; }
                    runStart = -1;
                }
            }
            if (runStart >= 0 && endX - runStart > longest) {
                longest = endX - runStart;
                longestStart = runStart;
                longestEnd = endX;
            }
            if (longest > bestLength && longest > width * 0.13) {
                bestLength = longest;
                bestY = y;
                bestStart = longestStart;
                bestEnd = longestEnd;
            }
        }
        if (bestY < 0 || bestEnd - bestStart < width * 0.16 || bestY < height * 0.74) return null;

        int colored = 0, total = 0;
        for (int x = bestStart; x < bestEnd; x += 3) {
            Color.colorToHSV(bitmap.getPixel(x, bestY), hsv);
            if (hsv[1] > 0.38f && hsv[2] > 0.38f) colored++;
            total++;
        }
        float fraction = total == 0 ? 0.5f : Math.max(0f, Math.min(1f, colored / (float) total));
        return new PowerBar(bestStart, bestEnd, bestY, fraction);
    }
}
