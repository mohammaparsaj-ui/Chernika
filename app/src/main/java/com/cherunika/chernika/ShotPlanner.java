package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.List;

final class ShotPlanner {
    private final DiamondRackDetector rackDetector = new DiamondRackDetector();
    private final PhysicsShotEvaluator physicsEvaluator = new PhysicsShotEvaluator();

    ShotPlan plan(FrameState frame, int legalBall) { return plan(frame, legalBall, 0); }

    ShotPlan plan(FrameState frame, int legalBall, int objectiveBall) {
        if (frame == null || frame.table == null || frame.cueBall == null
                || legalBall < 1 || legalBall > 9) return null;
        Ball target = null;
        for (Ball ball : frame.balls) {
            if (!ball.cue && ball.number == legalBall) { target = ball; break; }
        }
        if (target == null) return null;

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
        return rankWithPhysics(frame, candidates, legalBall, objectiveBall);
    }

    /**
     * Geometry proposes routes; the table simulator checks first contact,
     * pocket outcome, scratches and candidate power before choosing among them.
     * A geometric fallback remains while physical constants are calibrated.
     */
    private ShotPlan rankWithPhysics(FrameState frame, List<ShotPlan> candidates,
                                     int legalBall, int objectiveBall) {
        if (candidates == null || candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Double.compare(a.score, b.score));
        ShotPlan best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int limit = Math.min(4, candidates.size());
        double[] offsets = {-10.0, 0.0, 10.0};

        for (int i = 0; i < limit; i++) {
            ShotPlan geometric = candidates.get(i);
            for (double offset : offsets) {
                double power = Math.max(20.0, Math.min(98.0,
                        geometric.powerPercent + offset));
                ShotPlan trial = new ShotPlan(
                        geometric.target, geometric.aimPoint, geometric.pocket,
                        power, geometric.score, geometric.breakShot,
                        geometric.bankShot, geometric.comboShot);
                PhysicsShotEvaluator.Evaluation evaluation =
                        physicsEvaluator.evaluate(frame, trial, legalBall, objectiveBall);
                if (evaluation == null || !evaluation.legalFirstContact
                        || evaluation.simulation.cueScratch
                        || evaluation.simulation.anyBallOffTable) continue;

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
        if (frame == null || frame.table == null || frame.cueBall == null) return null;
        Ball oldCue = frame.cueBall;
        if (plan(frame, legalBall, objectiveBall) != null) return oldCue.center;

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
            ShotPlan candidatePlan = plan(frame, legalBall, objectiveBall);
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
