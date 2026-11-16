package com.cherunika.chernika;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects the legal lowest-numbered ball in 9-ball.
 * A HUD announcement wins when present. Without one, every visible object
 * ball must have a confident, unique number; otherwise the selector waits.
 */
final class LowestBallSelector {
    static final class Selection {
        final int number;
        final Ball ball;
        final double confidence;
        final String reason;

        Selection(int number, Ball ball, double confidence, String reason) {
            this.number = number;
            this.ball = ball;
            this.confidence = confidence;
            this.reason = reason;
        }

        boolean isConfident() {
            return number >= 1 && number <= 9 && ball != null && confidence >= 0.44;
        }

        static Selection unavailable(String reason) {
            return new Selection(0, null, 0.0, reason);
        }
    }

    Selection select(List<Ball> balls, int hudLegalBall) {
        if (balls == null || balls.isEmpty()) {
            return Selection.unavailable("No ball candidates");
        }

        if (hudLegalBall >= 1 && hudLegalBall <= 9) {
            Ball match = null;
            for (Ball ball : balls) {
                if (ball.cue || ball.number != hudLegalBall) continue;
                if (match != null) {
                    return Selection.unavailable("HUD target has duplicate visual candidates");
                }
                match = ball;
            }
            return match == null
                    ? Selection.unavailable("HUD target is not confidently visible")
                    : new Selection(hudLegalBall, match,
                            Math.max(0.75, match.numberConfidence), "HUD announcement");
        }

        Ball lowest = null;
        int lowestNumber = 10;
        Set<Integer> seen = new HashSet<>();
        int objectCount = 0;
        for (Ball ball : balls) {
            if (ball.cue) continue;
            objectCount++;
            if (ball.number < 1 || ball.number > 9 || ball.numberConfidence < 0.44) {
                return Selection.unavailable("At least one object ball has an uncertain color/number");
            }
            if (!seen.add(ball.number)) {
                return Selection.unavailable("Duplicate ball numbers detected");
            }
            if (ball.number < lowestNumber) {
                lowestNumber = ball.number;
                lowest = ball;
            }
        }

        if (objectCount == 0 || lowest == null) {
            return Selection.unavailable("No numbered object balls");
        }
        return new Selection(lowestNumber, lowest, lowest.numberConfidence,
                "Lowest visible numbered ball");
    }
}
