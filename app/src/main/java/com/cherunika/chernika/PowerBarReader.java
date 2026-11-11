package com.cherunika.chernika;

import android.graphics.Bitmap;

final class PowerBarReader {
    PowerBar detect(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return PowerBarDetector.detect(pixels, width, height);
    }
}
