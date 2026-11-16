package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.List;

final class ShotPlanner {
    static final double MAX_UI_POWER_PERCENT = 97.8;
    private final DiamondRackDetector rackDetector = new DiamondRackDetector();
    private final PhysicsShotEvaluator physicsEvaluator = new PhysicsShotEvaluator();
    private final NineBallImitationPolicy imitationPolicy;

    ShotPlanner() { this(null); }

    ShotPlanner(NineBallImitationPolicy imitationPolicy) {
        this.imitationPolicy = imitationPolicy;
    }

    ShotPlan plan(FrameState frame, int legalBall) {
        return plan(frame, legalBall, 0, 0);
    }

    ShotPlan plan(FrameState frame, int legalBall, int objectiveBall) {
        return plan(frame, legalBall, objectiveBall, 0);
    }

    ShotPlan plan(FrameState frame, int legalBall, int objectiveBall, int shotCount) {
        if (frame == null || frame.table == null || frame.cueBall == null
                || legalBall < 1 || legalBall > 9) return null;
        Ball target = null;
        for (Ball ball : frame.balls) {
            if (!ball.cue && ball.number == legalBall) { target = ball; break; }
        }
        if (target == null) return null;

        NineBallImitationPolicy.Prediction neuralPrediction = policyPrediction(
                frame, legalBall, objectiveBall, shotCount);
        int[] preferredPocketIndices = neuralPrediction == null
                ? null : neuralPrediction.rankedPocketIndices;

        if (objectiveBall == 9 && legalBall != 9) {
            Ball nine = null;
            for (Ball ball : frame.balls) {
                if (!ball.cue && ball.number == 9) { nine = ball; break; }
            }
            ShotPlan combo = nine == null ? null : evaluateCombo(frame, target, nine);
            if (combo != null) {
                List<ShotPlan> comboOnly = new ArrayList<>();
                comboOnly.add(combo);
                return rankWithPhysics(frame, comboOnly, legalBall, objectiveBall);
            }
        }

        List<ShotPlan> candidates = new ArrayList<>();
        for (Pocket pocket : frame.table.pockets()) {
            ShotPlan direct = evaluate(frame, target, pocket, pocket.aimPoint, null, false);
            if (direct != null) candidates.add(direct);

            for (int rail = 0; rail < 4; rail++) {
                Vec2 virtualPocket = reflect(pocket.aimPoint, frame.table, rail);
                Vec2 bounce = railIntersection(target.center, virtualPocket, frame.table, rail);
                if (bounce == null) continue;
                ShotPlan bank = evaluate(frame, target, pocket, virtualPocket, bounce, true);
                if (bank != null) candidates.add(bank);
            }
        }
        if (preferredPocketIndices != null && preferredPocketIndices.length > 0) {
            List<ShotPlan> neuralCandidates = new ArrayList<>();
            for (ShotPlan candidate : candidates) {
                int index = pocketIndex(frame.table, candidate.pocket);
                for (int preferred : preferredPocketIndices) {
                    if (index == preferred) {
                        neuralCandidates.add(candidate);
                        break;
                    }
                }
            }
            // Keep the physics-only route search as a safety fallback if the
            // neural top-3 has no geometrically feasible candidate.
            if (!neuralCandidates.isEmpty()) candidates = neuralCandidates;
        }
        return rankWithPhysics(frame, candidates, legalBall, objectiveBall, neuralPrediction);
    }

    NineBallImitationPolicy.Prediction policyPrediction(
            FrameState frame, int legalBall, int objectiveBall, int shotCount) {
        if (imitationPolicy == null || frame == null || frame.table == null
                || (objectiveBall == 9 && legalBall != 9)) {
            return null;
        }
        try {
            float[] observation = NineBallObservationEncoder.encode(
                    frame, legalBall, false, shotCount);
            return imitationPolicy.predict(observation, 3);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Vision may leave an object-ball identity unresolved for this
            // frame. Never let uncertain OCR disable the geometric fallback.
            return null;
        }
    }

    int[] preferredPocketIndices(FrameState frame, int legalBall,
                                 int objectiveBall, int shotCount) {
        NineBallImitationPolicy.Prediction prediction = policyPrediction(
                frame, legalBall, objectiveBall, shotCount);
        return prediction == null ? null : prediction.rankedPocketIndices;
    }

    private int pocketIndex(TableGeometry table, Pocket candidate) {
        if (table == null || candidate == null) return -1;
        List<Pocket> pockets = table.pockets();
        for (int i = 0; i < pockets.size(); i++) {
            if (pockets.get(i).id == candidate.id) return i;
        }
        return -1;
    }

    /**
     * Geometry proposes routes; the table simulator checks first contact,
     * pocket outcome, scratches and candidate power before choosing among them.
     * A geometric fallback remains while physical constants are calibrated.
     */
    private ShotPlan rankWithPhysics(FrameState frame, List<ShotPlan> candidates,
                                     int legalBall, int objectiveBall) {
        return rankWithPhysics(frame, candidates, legalBall, objectiveBall, null);
    }

    private ShotPlan rankWithPhysics(
            FrameState frame, List<ShotPlan> candidates, int legalBall, int objectiveBall,
            NineBallImitationPolicy.Prediction neuralPrediction) {
        if (candidates == null || candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Double.compare(a.score, b.score));
        ShotPlan best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        // Evaluate the learned continuous action as an additional candidate,
        // but never let it bypass first-contact, scratch or off-table checks.
        if (neuralPrediction != null) {
            ShotPlan base = bestCandidateForPocket(
                    frame.table, candidates, neuralPrediction.rankedPocketIndices);
            if (base == null) base = candidates.get(0);
            ShotPlan neural = withNeuralAction(
                    base, frame.cueBall.center,
                    neuralPrediction.angleOffset, neuralPrediction.power);
            if (neural != null) {
                double geometricAngle = Math.atan2(
                        base.aimPoint.y - frame.cueBall.center.y,
                        base.aimPoint.x - frame.cueBall.center.x);
                double learnedAngle = Math.atan2(
                        neural.aimPoint.y - frame.cueBall.center.y,
                        neural.aimPoint.x - frame.cueBall.center.x);
                double angleDeviation = Math.abs(wrapAngle(learnedAngle - geometricAngle));
                double actionPenalty = angleDeviation * 0.5
                        + Math.abs(neural.powerPercent - base.powerPercent) * 0.003;
                neural = new ShotPlan(
                        neural.target, neural.aimPoint, neural.pocket,
                        neural.powerPercent, base.score + actionPenalty,
                        neural.breakShot, neural.bankShot, neural.comboShot);
                double[] neuralPowerOffsets = {-5.0, 0.0, 5.0};
                for (double offset : neuralPowerOffsets) {
                    double power = clamp(neural.powerPercent + offset,
                            20.0, MAX_UI_POWER_PERCENT);
                    ShotPlan trial = new ShotPlan(
                            neural.target, neural.aimPoint, neural.pocket, power,
                            neural.score, neural.breakShot, neural.bankShot, neural.comboShot);
                    PhysicsShotEvaluator.Evaluation evaluation =
                            physicsEvaluator.evaluate(frame, trial, legalBall, objectiveBall);
                    if (!isSafeLegalEvaluation(evaluation)) continue;
                    boolean objectiveMet = evaluation.targetPocketed
                            && (objectiveBall != 9 || legalBall == 9 || evaluation.ninePocketed);
                    double score = neural.score + Math.abs(offset) * 0.003
                            - (objectiveMet ? 1.0 : 0.0)
                            - evaluation.quality * 0.04;
                    if (score < bestScore) {
                        bestScore = score;
                        best = trial;
                    }
                }
            }
        }

        int limit = Math.min(4, candidates.size());
        double[] offsets = {-10.0, 0.0, 10.0};
        for (int i = 0; i < limit; i++) {
            ShotPlan geometric = candidates.get(i);
            for (double offset : offsets) {
                double power = clamp(geometric.powerPercent + offset,
                        20.0, MAX_UI_POWER_PERCENT);
                ShotPlan trial = new ShotPlan(
                        geometric.target, geometric.aimPoint, geometric.pocket,
                        power, geometric.score, geometric.breakShot,
                        geometric.bankShot, geometric.comboShot);
                PhysicsShotEvaluator.Evaluation evaluation =
                        physicsEvaluator.evaluate(frame, trial, legalBall, objectiveBall);
                if (!isSafeLegalEvaluation(evaluation)) continue;

                boolean objectiveMet = evaluation.targetPocketed
                        && (objectiveBall != 9 || legalBall == 9 || evaluation.ninePocketed);
                double score = geometric.score + Math.abs(offset) * 0.003
                        - (objectiveMet ? 1.0 : 0.0)
                        - evaluation.quality * 0.04;
                if (score < bestScore) {
                    bestScore = score;
                    best = trial;
                }
            }
        }
        return best != null ? best : candidates.get(0);
    }

    private ShotPlan bestCandidateForPocket(
            TableGeometry table, List<ShotPlan> candidates, int[] rankedPocketIndices) {
        if (rankedPocketIndices == null || rankedPocketIndices.length == 0) return null;
        for (int preferred : rankedPocketIndices) {
            ShotPlan best = null;
            for (ShotPlan candidate : candidates) {
                if (pocketIndex(table, candidate.pocket) == preferred
                        && (best == null || candidate.score < best.score)) {
                    best = candidate;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    static ShotPlan withNeuralAction(
            ShotPlan base, Vec2 cueCenter, float angleOffset, float normalizedPower) {
        if (base == null || base.target == null || base.target.center == null
                || base.aimPoint == null || cueCenter == null
                || !Float.isFinite(angleOffset) || !Float.isFinite(normalizedPower)) {
            return null;
        }
        double offset = clamp(angleOffset, -1.0, 1.0);
        double normalized = clamp(normalizedPower, -1.0, 1.0);
        double directAngle = Math.atan2(
                base.target.center.y - cueCenter.y,
                base.target.center.x - cueCenter.x);
        double learnedAngle = directAngle + offset * Math.PI;
        double aimDistance = cueCenter.distance(base.aimPoint);
        if (!Double.isFinite(aimDistance) || aimDistance < 1e-6) return null;

        Vec2 learnedAimPoint = cueCenter.add(new Vec2(
                Math.cos(learnedAngle), Math.sin(learnedAngle)).scale(aimDistance));
        double powerPercent = ((normalized + 1.0) * 0.5) * MAX_UI_POWER_PERCENT;
        powerPercent = clamp(powerPercent, 20.0, MAX_UI_POWER_PERCENT);
        return new ShotPlan(
                base.target, learnedAimPoint, base.pocket, powerPercent,
                base.score, base.breakShot, base.bankShot, base.comboShot);
    }

    private boolean isSafeLegalEvaluation(PhysicsShotEvaluator.Evaluation evaluation) {
        return evaluation != null && evaluation.legalFirstContact
                && !evaluation.simulation.cueScratch
                && !evaluation.simulation.anyBallOffTable;
    }

    private static double wrapAngle(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private ShotPlan evaluateCombo(FrameState frame, Ball first, Ball nine) {
        Vec2 firstToNine = nine.center.subtract(first.center).normalized();
        Vec2 ghost = first.center.subtract(firstToNine.scale(first.radius + nine.radius));
        if (!frame.table.contains(ghost, first.radius * 1.2)) return null;
        if (!clearPath(frame.cueBall.center, ghost,
                frame.balls, frame.cueBall, first, first.radius * 2.0)) return null;
        if (!clearPath(first.center, nine.center,
                frame.balls, first, nine, first.radius * 1.8)) return null;

        ShotPlan best = null;
        for (Pocket pocket : frame.table.pockets()) {
            if (!clearPath(nine.center, pocket.center,
                    frame.balls, nine, first, nine.radius * 2.0)) continue;
            double cueDistance = tableDistance(frame.table, frame.cueBall.center, ghost);
            double comboDistance = tableDistance(frame.table, first.center, nine.center);
            double nineDistance = tableDistance(frame.table, nine.center, pocket.center);
            double angle = Math.acos(Math.max(-1, Math.min(1,
                    ghost.subtract(frame.cueBall.center).normalized().dot(firstToNine))));
            double score = cueDistance + comboDistance * 0.9 + nineDistance * 0.78
                    + angle * 0.48 + 0.12;
            double power = Math.max(30, Math.min(92,
                    28 + (cueDistance + comboDistance + nineDistance * 0.65) * 62 + angle * 8));
            ShotPlan candidate = new ShotPlan(first, ghost, pocket, power,
                    score, false, false, true);
            if (best == null || candidate.score < best.score) best = candidate;
        }
        return best;
    }

    ShotPlan planBreak(FrameState frame) {
        if (frame == null || frame.table == null || frame.cueBall == null) return null;
        DiamondRackDetector.Result rack = rackDetector.detect(frame.balls, frame.table);
        if (!rack.detected) return null;

        List<Ball> objects = new ArrayList<>();
        for (Ball ball : frame.balls) if (!ball.cue) objects.add(ball);
        if (objects.size() != 9) return null;

        Ball apex = null;
        for (Ball ball : objects) {
            if (ball.number == 1) {
                apex = ball;
                break;
            }
        }
        if (apex == null) {
            // Fallback to the lower apex in screen coordinates. The 1-ball
            // is the front ball closest to the cue in this Plato orientation,
            // but cue placement must never change which rack ball is targeted.
            Vec2 downAxis = rack.longAxis;
            if (downAxis.y < 0) downAxis = downAxis.scale(-1.0);
            double greatestProjection = Double.NEGATIVE_INFINITY;
            for (Ball ball : objects) {
                double projection = ball.center.subtract(rack.center).dot(downAxis);
                if (projection > greatestProjection) {
                    greatestProjection = projection;
                    apex = ball;
                }
            }
        }
        if (apex == null) return null;
        return new ShotPlan(apex, apex.center, null, 100.0,
                0.0, true, false, false);
    }

    Vec2 bestCuePlacement(FrameState frame, int legalBall, int objectiveBall) {
        return bestCuePlacement(frame, legalBall, objectiveBall, 0);
    }

    Vec2 bestCuePlacement(FrameState frame, int legalBall,
                          int objectiveBall, int shotCount) {
        if (frame == null || frame.table == null || frame.cueBall == null) return null;
        Ball oldCue = frame.cueBall;
        if (plan(frame, legalBall, objectiveBall, shotCount) != null) return oldCue.center;

        double r = oldCue.radius;
        double[][] points = {
                {0.50, 0.50}, {0.36, 0.50}, {0.64, 0.50},
                {0.50, 0.38}, {0.50, 0.62}, {0.30, 0.35}, {0.70, 0.65}
        };
        Vec2 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double[] p : points) {
            Vec2 candidate = new Vec2(frame.table.left + frame.table.width * p[0],
                    frame.table.top + frame.table.height * p[1]);
            if (!frame.table.contains(candidate, r * 2.2)) continue;
            if (overlapsBall(candidate, r, frame.balls, oldCue)) continue;
            frame.cueBall = new Ball(candidate, r, 0, true);
            ShotPlan candidatePlan = plan(frame, legalBall, objectiveBall, shotCount);
            if (candidatePlan != null && candidatePlan.score < bestScore) {
                bestScore = candidatePlan.score;
                best = candidate;
            }
        }
        frame.cueBall = oldCue;
        return best;
    }

    private ShotPlan evaluate(FrameState frame, Ball target, Pocket pocket,
                              Vec2 virtualPocket, Vec2 bounce, boolean bank) {
        Vec2 targetVector = virtualPocket.subtract(target.center).normalized();
        Vec2 ghost = target.center.subtract(targetVector.scale(target.radius * 2.05));
        if (!frame.table.contains(ghost, target.radius * 1.2)) return null;
        if (!clearPath(frame.cueBall.center, ghost,
                frame.balls, frame.cueBall, target, target.radius * 2.1)) return null;

        double objectDistance;
        if (!bank) {
            if (!clearPath(target.center, pocket.center,
                    frame.balls, target, frame.cueBall, target.radius * 2.05)) return null;
            objectDistance = tableDistance(frame.table, target.center, pocket.center);
        } else {
            if (bounce == null
                    || !clearPath(target.center, bounce,
                        frame.balls, target, frame.cueBall, target.radius * 2.05)
                    || !clearPath(bounce, pocket.center,
                        frame.balls, target, frame.cueBall, target.radius * 2.05)) return null;
            objectDistance = tableDistance(frame.table, target.center, bounce)
                    + tableDistance(frame.table, bounce, pocket.center);
        }

        Vec2 cueVector = ghost.subtract(frame.cueBall.center).normalized();
        Vec2 objectVector = targetVector;
        double dot = Math.max(-1, Math.min(1, cueVector.dot(objectVector)));
        double cutAngle = Math.acos(dot);
        double cueDistance = tableDistance(frame.table, frame.cueBall.center, ghost);
        double score = cueDistance + objectDistance * 0.72 + cutAngle * 0.42
                + (bank ? 0.24 : 0);
        double power = Math.max(24, Math.min(94,
                24 + (cueDistance + objectDistance * 0.55) * 68
                        + cutAngle * 8 + (bank ? 6 : 0)));
        return new ShotPlan(target, ghost, pocket, power, score, false, bank, false);
    }

    private double tableDistance(TableGeometry table, Vec2 a, Vec2 b) {
        Vec2 na = table.screenToTable(a);
        Vec2 nb = table.screenToTable(b);
        double dx = na.x - nb.x;
        double dy = (na.y - nb.y) * (table.height / table.width);
        return Math.hypot(dx, dy);
    }

    private Vec2 reflect(Vec2 pocket, TableGeometry table, int rail) {
        switch (rail) {
            case 0: return new Vec2(2 * table.left - pocket.x, pocket.y);
            case 1: return new Vec2(2 * table.right - pocket.x, pocket.y);
            case 2: return new Vec2(pocket.x, 2 * table.top - pocket.y);
            default: return new Vec2(pocket.x, 2 * table.bottom - pocket.y);
        }
    }

    private Vec2 railIntersection(Vec2 start, Vec2 end, TableGeometry table, int rail) {
        double t;
        double x, y;
        if (rail == 0 || rail == 1) {
            double edge = rail == 0 ? table.left : table.right;
            if (Math.abs(end.x - start.x) < 1e-8) return null;
            t = (edge - start.x) / (end.x - start.x);
            y = start.y + t * (end.y - start.y);
            x = edge;
            if (t <= 0 || t >= 1 || y < table.top || y > table.bottom) return null;
        } else {
            double edge = rail == 2 ? table.top : table.bottom;
            if (Math.abs(end.y - start.y) < 1e-8) return null;
            t = (edge - start.y) / (end.y - start.y);
            x = start.x + t * (end.x - start.x);
            y = edge;
            if (t <= 0 || t >= 1 || x < table.left || x > table.right) return null;
        }
        return new Vec2(x, y);
    }

    private boolean clearPath(Vec2 a, Vec2 b,
                              List<Ball> balls, Ball skipA, Ball skipB, double clearance) {
        for (Ball ball : balls) {
            if (ball == skipA || ball == skipB || ball.cue) continue;
            if (distanceToSegment(ball.center, a, b) < ball.radius + clearance) return false;
        }
        return true;
    }

    private boolean overlapsBall(Vec2 p, double radius, List<Ball> balls, Ball skip) {
        for (Ball ball : balls) {
            if (ball == skip) continue;
            if (p.distance(ball.center) < radius + ball.radius * 2.1) return true;
        }
        return false;
    }

    static double distanceToSegment(Vec2 p, Vec2 a, Vec2 b) {
        Vec2 ab = b.subtract(a);
        double length2 = ab.dot(ab);
        if (length2 < 1e-8) return p.distance(a);
        double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / length2));
        return p.distance(a.add(ab.scale(t)));
    }
}
