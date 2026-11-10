package com.cherunika.chernika.engine;

import java.util.List;

/** Direct-only 9-ball planner. No banks, kicks, spin, combos or rail routes. */
final class DirectShotPlanner {
    static final class Result {
        final EngineDecision.ShotKind kind;
        final Point2 aimPoint;
        final int pocketIndex;
        final double score;

        Result(EngineDecision.ShotKind kind, Point2 aimPoint, int pocketIndex, double score) {
            this.kind = kind;
            this.aimPoint = aimPoint;
            this.pocketIndex = pocketIndex;
            this.score = score;
        }
    }

    Result plan(GameObservation observation, BallObservation cue, BallObservation target,
                int objectiveBall) {
        // When the HUD says "Pocket the 9 ball", do not pretend a direct shot on
        // the 9 is legal while a lower-numbered ball remains. v1 safely contacts
        // the legal ball first and leaves 9-ball combinations for a later version.
        boolean mayPotTarget = objectiveBall == 0 || objectiveBall == target.number;
        if (mayPotTarget) {
            Result best = bestDirectPot(observation, cue, target);
            if (best != null) return best;
        }
        Point2 contact = directContactPoint(observation, cue, target);
        if (contact == null) return null;
        return new Result(EngineDecision.ShotKind.LEGAL_CONTACT_ONLY, contact, -1,
                cue.center.distance(contact));
    }

    private Result bestDirectPot(GameObservation observation, BallObservation cue,
                                 BallObservation target) {
        Result best = null;
        for (int i = 0; i < observation.pockets.size(); i++) {
            PocketObservation pocket = observation.pockets.get(i);
            Point2 targetToPocket = pocket.center.subtract(target.center).normalized();
            if (targetToPocket.length() < 0.9) continue;

            double clearance = Math.max(1.0, cue.radius * 0.16);
            Point2 ghost = target.center.subtract(targetToPocket.scale(
                    target.radius + cue.radius + clearance));
            if (!observation.playableBounds.contains(ghost, cue.radius * 0.85)) continue;

            if (!clearPath(cue.center, ghost, observation.balls, cue, target, cue.radius)) continue;
            if (!clearPath(target.center, pocket.center, observation.balls,
                    target, cue, target.radius)) continue;

            Point2 cueDirection = ghost.subtract(cue.center).normalized();
            Point2 objectDirection = target.center.subtract(ghost).normalized();
            double alignmentPenalty = (1.0 - Math.max(-1.0,
                    Math.min(1.0, cueDirection.dot(objectDirection)))) * cue.radius * 3.0;
            double score = cue.center.distance(ghost)
                    + target.center.distance(pocket.center) * 0.82 + alignmentPenalty;
            Result candidate = new Result(EngineDecision.ShotKind.DIRECT_POT,
                    ghost, i, score);
            if (best == null || candidate.score < best.score) best = candidate;
        }
        return best;
    }

    private Point2 directContactPoint(GameObservation observation, BallObservation cue,
                                      BallObservation target) {
        Point2 direction = target.center.subtract(cue.center).normalized();
        if (direction.length() < 0.9) return null;
        double clearance = Math.max(1.0, cue.radius * 0.16);
        Point2 ghost = target.center.subtract(direction.scale(
                target.radius + cue.radius + clearance));
        if (!observation.playableBounds.contains(ghost, cue.radius * 0.85)) return null;
        if (!clearPath(cue.center, ghost, observation.balls, cue, target, cue.radius)) return null;
        return ghost;
    }

    private boolean clearPath(Point2 start, Point2 end, List<BallObservation> balls,
                              BallObservation excludedA, BallObservation excludedB,
                              double movingRadius) {
        for (BallObservation obstacle : balls) {
            if (obstacle == excludedA || obstacle == excludedB) continue;
            double distance = distanceToSegment(obstacle.center, start, end);
            double requiredClearance = movingRadius + obstacle.radius
                    + Math.max(1.0, movingRadius * 0.08);
            if (distance < requiredClearance) return false;
        }
        return true;
    }

    private double distanceToSegment(Point2 point, Point2 start, Point2 end) {
        Point2 segment = end.subtract(start);
        double lengthSquared = segment.dot(segment);
        if (lengthSquared < 1.0e-9) return point.distance(start);
        double t = point.subtract(start).dot(segment) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        Point2 projection = start.add(segment.scale(t));
        return point.distance(projection);
    }
}
