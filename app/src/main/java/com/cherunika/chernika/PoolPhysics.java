package com.cherunika.chernika;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic 2-D rolling-ball simulator for Plato-style pool tables.
 *
 * This first model includes rolling friction, equal-mass ball collisions,
 * cushion restitution and six-pocket capture gates. It intentionally omits
 * spin, throw, masse, cloth anisotropy and perspective-dependent cushion
 * physics; those require measurements from live gameplay recordings.
 */
final class PoolPhysics {
    private static final double TIME_STEP_SECONDS = 1.0 / 240.0;
    private static final double MAX_SIMULATION_SECONDS = 40.0;

    // Recalibrated from the supplied 720x1216 Plato replay (2026-11-06):
    // a ~65% shot moved the cue ball about 70x27 px in its first 0.4 s,
    // before object-ball contact. These remain provisional single-replay values.
    private static final double BALL_RESTITUTION = 0.946;
    private static final double RAIL_RESTITUTION = 0.815;
    private static final double ROLLING_RESISTANCE_COEFFICIENT = 0.0095;
    private static final double MAX_CUE_SPEED_METERS_PER_SECOND = 0.66;
    private static final double MAX_UI_POWER_PERCENT = 97.8;

    // The calibrated portrait table is consistent with an approximately
    // 0.99 m x 1.98 m playing surface (7-foot table proportions).
    private static final double TABLE_WIDTH_METERS = 0.99;
    private static final double TABLE_LENGTH_METERS = 1.98;
    private static final double GRAVITY_METERS_PER_SECOND_SQUARED = 9.81;
    private static final double MIN_MOVING_SPEED_PER_RADIUS = 0.05;
    private static final double MIN_COLLISION_SPEED_PER_RADIUS = 0.08;
    // Revisit simultaneous contacts in a tightly packed rack. A single pass
    // made energy propagation depend on ball-number iteration order.
    private static final int BALL_COLLISION_ITERATIONS = 4;

    static final class BallResult {
        final Ball source;
        final Vec2 position;
        final Vec2 velocity;
        final boolean pocketed;
        final boolean offTable;

        BallResult(Ball source, Vec2 position, Vec2 velocity,
                   boolean pocketed, boolean offTable) {
            this.source = source;
            this.position = position;
            this.velocity = velocity;
            this.pocketed = pocketed;
            this.offTable = offTable;
        }
    }

    static final class Result {
        final List<BallResult> balls;
        final Ball firstContactBall;
        final int ballCollisions;
        final int cushionContacts;
        final double simulatedSeconds;
        final boolean cueScratch;
        final boolean anyBallOffTable;

        Result(List<BallResult> balls, Ball firstContactBall,
               int ballCollisions, int cushionContacts,
               double simulatedSeconds, boolean cueScratch,
               boolean anyBallOffTable) {
            this.balls = Collections.unmodifiableList(new ArrayList<>(balls));
            this.firstContactBall = firstContactBall;
            this.ballCollisions = ballCollisions;
            this.cushionContacts = cushionContacts;
            this.simulatedSeconds = simulatedSeconds;
            this.cueScratch = cueScratch;
            this.anyBallOffTable = anyBallOffTable;
        }

        boolean pocketed(Ball ball) {
            if (ball == null) return false;
            for (BallResult result : balls) {
                if (result.source == ball) return result.pocketed;
            }
            return false;
        }

        boolean pocketedNumber(int number) {
            if (number < 1 || number > 9) return false;
            for (BallResult result : balls) {
                if (!result.source.cue && result.source.number == number && result.pocketed) return true;
            }
            return false;
        }

        Vec2 finalPosition(Ball ball) {
            if (ball == null) return null;
            for (BallResult result : balls) {
                if (result.source == ball) return result.position;
            }
            return null;
        }
    }

    private static final class Body {
        final Ball source;
        final double radius;
        double x;
        double y;
        double vx;
        double vy;
        boolean pocketed;
        boolean offTable;

        Body(Ball source) {
            this.source = source;
            this.radius = source.radius;
            this.x = source.center.x;
            this.y = source.center.y;
        }

        boolean active() { return !pocketed && !offTable; }
        double speed() { return Math.hypot(vx, vy); }
    }

    private enum Rail { LEFT, RIGHT, TOP, BOTTOM }

    Result simulate(FrameState frame, Vec2 cueDirection, double powerPercent) {
        if (frame == null || frame.table == null || frame.cueBall == null
                || cueDirection == null || cueDirection.length() < 1e-8) {
            return emptyResult();
        }

        List<Body> bodies = new ArrayList<>();
        boolean cueIncluded = false;
        for (Ball ball : frame.balls) {
            if (ball == null || ball.radius <= 0) continue;
            bodies.add(new Body(ball));
            if (ball == frame.cueBall) cueIncluded = true;
        }
        if (!cueIncluded) bodies.add(new Body(frame.cueBall));

        Body cue = null;
        for (Body body : bodies) {
            if (body.source == frame.cueBall) {
                cue = body;
                break;
            }
        }
        if (cue == null) return emptyResult();

        double power = clamp(powerPercent, 0.0, 100.0);
        double radius = cue.radius;
        double pixelsPerMeter = pixelsPerMeter(frame.table);
        // The user's 97.8% slider reading represents maximum power.
        double normalizedPower = Math.min(1.0, power / MAX_UI_POWER_PERCENT);
        double speed = normalizedPower * MAX_CUE_SPEED_METERS_PER_SECOND * pixelsPerMeter;
        Vec2 direction = cueDirection.normalized();
        cue.vx = direction.x * speed;
        cue.vy = direction.y * speed;

        double rollingDeceleration = ROLLING_RESISTANCE_COEFFICIENT
                * GRAVITY_METERS_PER_SECOND_SQUARED * pixelsPerMeter;
        double stopSpeed = Math.max(0.25, radius * MIN_MOVING_SPEED_PER_RADIUS);
        double collisionSpeed = Math.max(0.25, radius * MIN_COLLISION_SPEED_PER_RADIUS);
        Ball firstContactBall = null;
        int ballCollisions = 0;
        int cushionContacts = 0;
        double elapsed = 0;
        int maxSteps = (int) Math.ceil(MAX_SIMULATION_SECONDS / TIME_STEP_SECONDS);

        for (int step = 0; step < maxSteps; step++) {
            boolean anyMoving = false;
            for (Body body : bodies) {
                if (!body.active()) continue;
                if (body.speed() > stopSpeed) anyMoving = true;
                body.x += body.vx * TIME_STEP_SECONDS;
                body.y += body.vy * TIME_STEP_SECONDS;
            }

            if (!anyMoving && step > 0) break;

            // Resolve simultaneous rack contacts over multiple passes so
            // energy propagation is not dependent on ball-number ordering.
            for (int iteration = 0; iteration < BALL_COLLISION_ITERATIONS; iteration++) {
                for (int i = 0; i < bodies.size(); i++) {
                    Body a = bodies.get(i);
                    if (!a.active()) continue;
                    for (int j = i + 1; j < bodies.size(); j++) {
                        Body b = bodies.get(j);
                        if (!b.active()) continue;
                        double dx = b.x - a.x;
                        double dy = b.y - a.y;
                        double minDistance = a.radius + b.radius;
                        double distanceSquared = dx * dx + dy * dy;
                        if (distanceSquared >= minDistance * minDistance) continue;

                        double distance = Math.sqrt(Math.max(1e-12, distanceSquared));
                        double nx = distance < 1e-6 ? 1.0 : dx / distance;
                        double ny = distance < 1e-6 ? 0.0 : dy / distance;
                        double overlap = minDistance - distance;
                        double correction = overlap * 0.5 + 0.002;
                        a.x -= nx * correction;
                        a.y -= ny * correction;
                        b.x += nx * correction;
                        b.y += ny * correction;

                        double relativeNormalSpeed = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
                        if (relativeNormalSpeed < -collisionSpeed) {
                            double impulse = -(1.0 + BALL_RESTITUTION) * relativeNormalSpeed * 0.5;
                            a.vx -= nx * impulse;
                            a.vy -= ny * impulse;
                            b.vx += nx * impulse;
                            b.vy += ny * impulse;
                            ballCollisions++;

                            if (firstContactBall == null) {
                                if (a.source.cue && !b.source.cue) firstContactBall = b.source;
                                else if (b.source.cue && !a.source.cue) firstContactBall = a.source;
                            }
                        }
                    }
                }
            }

            for (Body body : bodies) {
                if (!body.active()) continue;
                Pocket nearbyPocket = pocketAtBallCenter(frame.table, body);
                if (nearbyPocket != null) {
                    body.pocketed = true;
                    body.vx = 0;
                    body.vy = 0;
                    continue;
                }

                int railHits = resolveRails(frame.table, body);
                cushionContacts += railHits;
                if (!body.active()) continue;

                if (body.x < frame.table.left - body.radius
                        || body.x > frame.table.right + body.radius
                        || body.y < frame.table.top - body.radius
                        || body.y > frame.table.bottom + body.radius) {
                    body.offTable = true;
                    body.vx = 0;
                    body.vy = 0;
                    continue;
                }

                double currentSpeed = body.speed();
                if (currentSpeed > 0) {
                    double nextSpeed = Math.max(0.0, currentSpeed - rollingDeceleration * TIME_STEP_SECONDS);
                    double scale = nextSpeed / currentSpeed;
                    body.vx *= scale;
                    body.vy *= scale;
                }
                if (body.speed() <= stopSpeed) {
                    body.vx = 0;
                    body.vy = 0;
                }
            }

            elapsed += TIME_STEP_SECONDS;
            if (allBodiesStopped(bodies, stopSpeed)) break;
        }

        List<BallResult> results = new ArrayList<>(bodies.size());
        boolean cueScratch = false;
        boolean anyOffTable = false;
        for (Body body : bodies) {
            results.add(new BallResult(body.source,
                    new Vec2(body.x, body.y),
                    new Vec2(body.vx, body.vy),
                    body.pocketed, body.offTable));
            if (body.source.cue && body.pocketed) cueScratch = true;
            if (body.offTable) anyOffTable = true;
        }

        return new Result(results, firstContactBall, ballCollisions,
                cushionContacts, elapsed, cueScratch, anyOffTable);
    }

    private int resolveRails(TableGeometry table, Body body) {
        int contacts = 0;
        double r = body.radius;

        if (body.vx < 0 && body.x - r <= table.left) {
            if (pocketAtRail(table, body, Rail.LEFT)) {
                body.pocketed = true;
                body.vx = 0;
                body.vy = 0;
                return contacts;
            }
            body.x = table.left + r;
            body.vx = -body.vx * RAIL_RESTITUTION;
            contacts++;
        } else if (body.vx > 0 && body.x + r >= table.right) {
            if (pocketAtRail(table, body, Rail.RIGHT)) {
                body.pocketed = true;
                body.vx = 0;
                body.vy = 0;
                return contacts;
            }
            body.x = table.right - r;
            body.vx = -body.vx * RAIL_RESTITUTION;
            contacts++;
        }

        if (body.vy < 0 && body.y - r <= table.top) {
            if (pocketAtRail(table, body, Rail.TOP)) {
                body.pocketed = true;
                body.vx = 0;
                body.vy = 0;
                return contacts;
            }
            body.y = table.top + r;
            body.vy = -body.vy * RAIL_RESTITUTION;
            contacts++;
        } else if (body.vy > 0 && body.y + r >= table.bottom) {
            if (pocketAtRail(table, body, Rail.BOTTOM)) {
                body.pocketed = true;
                body.vx = 0;
                body.vy = 0;
                return contacts;
            }
            body.y = table.bottom - r;
            body.vy = -body.vy * RAIL_RESTITUTION;
            contacts++;
        }
        return contacts;
    }

    private Pocket pocketAtBallCenter(TableGeometry table, Body body) {
        for (Pocket pocket : table.pockets()) {
            double effectiveRadius = Math.max(2.0, pocket.captureRadius - body.radius * 0.75);
            if (Math.hypot(body.x - pocket.center.x, body.y - pocket.center.y) <= effectiveRadius) {
                return pocket;
            }
        }
        return null;
    }

    private boolean pocketAtRail(TableGeometry table, Body body, Rail rail) {
        for (Pocket pocket : table.pockets()) {
            double halfOpening;
            boolean adjacentToRail;
            double alongCoordinate;
            double pocketAlongCoordinate;

            switch (rail) {
                case LEFT:
                    adjacentToRail = Math.abs(pocket.center.x - table.left) <= body.radius * 2.0;
                    halfOpening = pocket.openingHeight * 0.5;
                    alongCoordinate = body.y;
                    pocketAlongCoordinate = pocket.center.y;
                    break;
                case RIGHT:
                    adjacentToRail = Math.abs(pocket.center.x - table.right) <= body.radius * 2.0;
                    halfOpening = pocket.openingHeight * 0.5;
                    alongCoordinate = body.y;
                    pocketAlongCoordinate = pocket.center.y;
                    break;
                case TOP:
                    adjacentToRail = Math.abs(pocket.center.y - table.top) <= body.radius * 2.0;
                    halfOpening = pocket.openingWidth * 0.5;
                    alongCoordinate = body.x;
                    pocketAlongCoordinate = pocket.center.x;
                    break;
                default:
                    adjacentToRail = Math.abs(pocket.center.y - table.bottom) <= body.radius * 2.0;
                    halfOpening = pocket.openingWidth * 0.5;
                    alongCoordinate = body.x;
                    pocketAlongCoordinate = pocket.center.x;
                    break;
            }

            double usableHalfOpening = Math.max(1.0, halfOpening - body.radius * 0.72);
            if (adjacentToRail && Math.abs(alongCoordinate - pocketAlongCoordinate) <= usableHalfOpening) {
                return true;
            }
        }
        return false;
    }

    private double pixelsPerMeter(TableGeometry table) {
        double horizontalScale = table.width / TABLE_WIDTH_METERS;
        double verticalScale = table.height / TABLE_LENGTH_METERS;
        return (horizontalScale + verticalScale) * 0.5;
    }

    private boolean allBodiesStopped(List<Body> bodies, double stopSpeed) {
        for (Body body : bodies) {
            if (body.active() && body.speed() > stopSpeed) return false;
        }
        return true;
    }

    private Result emptyResult() {
        return new Result(Collections.emptyList(), null, 0, 0, 0, false, false);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
