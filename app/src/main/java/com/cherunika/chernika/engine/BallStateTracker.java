package com.cherunika.chernika.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks the visible table scene without requiring a perfect 1..9 rack baseline.
 *
 * A full rack can never be guaranteed after a break: balls may overlap, be
 * unreadable, or already be pocketed. The HUD is the primary legal-target
 * source; every detected but unread ball is retained as a physical obstacle.
 */
public final class BallStateTracker {
    private static final double MIN_DETECTION_CONFIDENCE = 0.72;
    private static final double INHERITED_IDENTITY_CONFIDENCE = 0.82;

    private List<BallObservation> previousVisible = Collections.emptyList();
    private int stableFrames;
    private boolean baselineEstablished;

    public Result update(List<BallObservation> detections, List<PocketObservation> pockets) {
        List<BallObservation> source = detections == null
                ? Collections.emptyList() : detections;

        List<BallObservation> filtered = new ArrayList<>();
        BallObservation cue = null;
        int cueCount = 0;
        for (BallObservation ball : source) {
            if (ball == null || ball.detectionConfidence < MIN_DETECTION_CONFIDENCE) continue;
            filtered.add(ball);
            if (ball.cueBall) {
                cue = ball;
                cueCount++;
            }
        }

        if (cueCount != 1) {
            stableFrames = 0;
            previousVisible = Collections.emptyList();
            String reason = cueCount == 0
                    ? "Cue ball is not confidently visible."
                    : "More than one cue-ball candidate was detected.";
            return result(filtered, false, true, false, reason,
                    Collections.emptySet(), Collections.emptySet());
        }

        Set<Integer> usedNumbers = new HashSet<>();
        List<BallObservation> normalized = new ArrayList<>();
        normalized.add(cue);
        int unresolved = 0;
        boolean duplicateIdentity = false;

        // Preserve explicit OCR identities first.
        for (BallObservation ball : filtered) {
            if (ball.cueBall) continue;
            if (ball.number >= 1 && ball.number <= 9
                    && ball.identityConfidence >= 0.78) {
                if (!usedNumbers.add(ball.number)) duplicateIdentity = true;
                normalized.add(ball);
            }
        }

        // Keep every unread ball as an obstacle. Inherit a number only when a
        // unique prior track is spatially close enough to make the match safe.
        for (BallObservation ball : filtered) {
            if (ball.cueBall || (ball.number >= 1 && ball.number <= 9
                    && ball.identityConfidence >= 0.78)) continue;

            Integer inherited = findUniqueSpatialIdentity(ball, usedNumbers);
            if (inherited != null) {
                BallObservation resolved = new BallObservation(
                        ball.center, ball.radius, inherited, false,
                        ball.detectionConfidence,
                        Math.min(INHERITED_IDENTITY_CONFIDENCE, ball.detectionConfidence));
                normalized.add(resolved);
                usedNumbers.add(inherited);
            } else {
                normalized.add(new BallObservation(
                        ball.center, ball.radius, 0, false,
                        ball.detectionConfidence, 0.0));
                unresolved++;
            }
        }

        boolean moving = !sameScene(previousVisible, normalized);
        if (moving) {
            stableFrames = 0;
        } else {
            stableFrames++;
        }
        previousVisible = new ArrayList<>(normalized);

        Set<Integer> visibleNumbers = new LinkedHashSet<>();
        for (BallObservation ball : normalized) {
            if (!ball.cueBall && ball.number >= 1 && ball.number <= 9
                    && ball.identityConfidence >= 0.78) {
                visibleNumbers.add(ball.number);
            }
        }

        boolean visibleIdentitiesResolved = unresolved == 0 && !duplicateIdentity;
        baselineEstablished = stableFrames >= 1 && normalized.size() >= 2;

        String reason;
        if (duplicateIdentity) {
            reason = "Duplicate ball-number OCR; ambiguous identities are retained as obstacles.";
        } else if (unresolved > 0) {
            reason = unresolved + " unread ball(s) retained as obstacles; HUD target remains authoritative.";
        } else if (moving) {
            reason = "Ball movement or a scene change was detected.";
        } else {
            reason = "Scene stable; " + (normalized.size() - 1)
                    + " object ball(s) visible, " + visibleNumbers.size() + " identified.";
        }

        return result(normalized, visibleIdentitiesResolved, moving,
                baselineEstablished, reason, visibleNumbers, Collections.emptySet());
    }

    private Integer findUniqueSpatialIdentity(BallObservation unknown,
                                               Set<Integer> alreadyUsed) {
        Integer bestNumber = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double secondDistance = Double.POSITIVE_INFINITY;

        // Only inherit from the immediately preceding frame. A pocketed ball's
        // stale position must never be reused for a different ball later.
        for (BallObservation previous : previousVisible) {
            if (previous.cueBall || previous.number < 1 || previous.number > 9
                    || previous.identityConfidence < 0.78
                    || alreadyUsed.contains(previous.number)) continue;
            double distance = unknown.center.distance(previous.center);
            double maxDistance = Math.max(14.0,
                    Math.max(unknown.radius, previous.radius) * 1.8);
            if (distance > maxDistance) continue;

            if (distance < bestDistance) {
                secondDistance = bestDistance;
                bestDistance = distance;
                bestNumber = previous.number;
            } else if (distance < secondDistance) {
                secondDistance = distance;
            }
        }

        if (bestNumber == null) return null;
        if (Double.isFinite(secondDistance)
                && secondDistance - bestDistance < Math.max(5.0, bestDistance * 0.40)) {
            return null;
        }
        return bestNumber;
    }

    /** Position-only matching means OCR changes do not masquerade as ball motion. */
    private boolean sameScene(List<BallObservation> before,
                              List<BallObservation> after) {
        if (before == null || before.isEmpty() || before.size() != after.size()) return false;

        BallObservation oldCue = null;
        BallObservation newCue = null;
        List<BallObservation> oldObjects = new ArrayList<>();
        List<BallObservation> newObjects = new ArrayList<>();
        for (BallObservation ball : before) {
            if (ball.cueBall) oldCue = ball;
            else oldObjects.add(ball);
        }
        for (BallObservation ball : after) {
            if (ball.cueBall) newCue = ball;
            else newObjects.add(ball);
        }
        if (oldCue == null || newCue == null
                || oldCue.center.distance(newCue.center) > movementThreshold(oldCue, newCue)
                || oldObjects.size() != newObjects.size()) return false;

        boolean[] matched = new boolean[oldObjects.size()];
        for (BallObservation current : newObjects) {
            int bestIndex = -1;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < oldObjects.size(); i++) {
                if (matched[i]) continue;
                BallObservation previous = oldObjects.get(i);
                double distance = current.center.distance(previous.center);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }
            if (bestIndex < 0) return false;
            BallObservation previous = oldObjects.get(bestIndex);
            if (bestDistance > movementThreshold(current, previous)) return false;
            matched[bestIndex] = true;
        }
        return true;
    }

    private double movementThreshold(BallObservation a, BallObservation b) {
        return Math.max(3.0, Math.max(a.radius, b.radius) * 0.28);
    }

    private Result result(List<BallObservation> balls, boolean identitiesResolved,
                          boolean moving, boolean baseline, String reason,
                          Set<Integer> remaining, Set<Integer> pocketed) {
        return new Result(balls, identitiesResolved, moving, stableFrames,
                baseline, remaining, pocketed, reason);
    }

    public void reset() {
        previousVisible = Collections.emptyList();
        stableFrames = 0;
        baselineEstablished = false;
    }

    public static final class Result {
        public final List<BallObservation> balls;
        /** True only when every currently visible object ball has a resolved identity. */
        public final boolean inventoryComplete;
        public final boolean ballsMoving;
        public final int consecutiveStableFrames;
        public final boolean baselineEstablished;
        /** Numbers identified in the current frame, not a claim about the full rack. */
        public final Set<Integer> remainingNumbers;
        /** Pocket inference is intentionally left to the live HUD/state transition. */
        public final Set<Integer> pocketedNumbers;
        public final String reason;

        private Result(List<BallObservation> balls, boolean inventoryComplete,
                       boolean ballsMoving, int stableFrames, boolean baselineEstablished,
                       Set<Integer> remaining, Set<Integer> pocketed, String reason) {
            this.balls = Collections.unmodifiableList(new ArrayList<>(balls));
            this.inventoryComplete = inventoryComplete;
            this.ballsMoving = ballsMoving;
            this.consecutiveStableFrames = stableFrames;
            this.baselineEstablished = baselineEstablished;
            this.remainingNumbers = Collections.unmodifiableSet(new LinkedHashSet<>(remaining));
            this.pocketedNumbers = Collections.unmodifiableSet(new LinkedHashSet<>(pocketed));
            this.reason = reason == null ? "" : reason;
        }
    }
}
