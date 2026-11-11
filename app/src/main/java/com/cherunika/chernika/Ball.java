package com.cherunika.chernika;

final class Ball {
    final Vec2 center;
    final double radius;
    int number;
    final boolean cue;
    int colorNumber;
    double colorConfidence;
    double numberConfidence;

    Ball(Vec2 center, double radius, int number, boolean cue) {
        this(center, radius, number, cue, 0, 0.0);
    }

    Ball(Vec2 center, double radius, int number, boolean cue,
         int colorNumber, double colorConfidence) {
        this.center = center;
        this.radius = radius;
        this.cue = cue;
        this.colorNumber = validNumber(colorNumber) ? colorNumber : 0;
        this.colorConfidence = clamp(colorConfidence, 0.0, 1.0);
        if (validNumber(number)) {
            this.number = number;
            this.numberConfidence = 0.98;
        } else if (validNumber(this.colorNumber) && this.colorConfidence >= 0.42) {
            this.number = this.colorNumber;
            this.numberConfidence = this.colorConfidence;
        } else {
            this.number = 0;
            this.numberConfidence = 0.0;
        }
    }

    void applyColorClassification(int classifiedNumber, double confidence) {
        if (cue || !validNumber(classifiedNumber)) return;
        colorNumber = classifiedNumber;
        colorConfidence = clamp(confidence, 0.0, 1.0);
        if (number == 0 && colorConfidence >= 0.42) {
            number = classifiedNumber;
            numberConfidence = colorConfidence;
        }
    }

    void applyOcrNumber(int recognizedNumber) {
        if (cue || !validNumber(recognizedNumber)) return;
        // A rack-template slot is a high-confidence geometric identity.
        // OCR may be noisy on tiny glossy balls, so it must not overwrite it.
        if (numberConfidence >= 0.95 && number != recognizedNumber) return;
        number = recognizedNumber;
        numberConfidence = 0.98;
    }

    private static boolean validNumber(int value) {
        return value >= 1 && value <= 9;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
