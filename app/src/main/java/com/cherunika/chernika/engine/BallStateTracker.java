package com.cherunika.chernika.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Temporal ball identity, motion and inventory tracker for one 9-ball rack.
 *
 * It deliberately starts fail-closed: a complete 1..9 rack and cue ball must
 * be seen in two consecutive, spatially stable frames before inventory memory
 * is trusted. A ball is only marked pocketed after it moved, was last seen in
 * a pocket capture zone, and is absent for multiple frames.
 */
public final class BallStateTracker {
    private static final double MIN_DETECTION_CONFIDENCE = 0.72;
    private static final double MIN_IDENTITY_CONFIDENCE = 0.80;
    private static final double INHERITED_IDENTITY_CONFIDENCE = 0.78;
    private static final int BASELINE_CONFIRM_FRAMES = 2;
    private static final int MISSING_FRAMES_TO_CONFIRM_POCKET = 2;

    private final Set<Integer> expected = new LinkedHashSet<>();
    private final Set<Integer> pocketed = new LinkedHashSet<>();
    private final Map<Integer, BallObservation> lastKnown = new HashMap<>();
    private final Map<Integer, Integer> missingFrames = new HashMap<>();
    private final Map<Integer, Boolean> movedSinceLastSeen = new HashMap<>();
    private Map<Integer, BallObservation> previousVisible = new LinkedHashMap<>();
    private Map<Integer, BallObservation> baselinePrevious = new LinkedHashMap<>();
    private boolean baselineEstablished;
    private int baselineFrames;
    private int stableFrames;

    public Result update(List<BallObservation> detections, List<PocketObservation> pockets) {
        List<BallObservation> source = detections == null
                ? Collections.emptyList() : detections;
        List<PocketObservation> pocketList = pockets == null
                ? Collections.emptyList() : pockets;

        BallObservation cue = null;
        Map<Integer, BallObservation> recognized = new LinkedHashMap<>();
        List<BallObservation> unknown = new ArrayList<>();
        for (BallObservation ball : source) {
            if (ball == null || ball.detectionConfidence < MIN_DETECTION_CONFIDENCE) {
                unknown.add(ball);
                continue;
            }
            if (ball.cueBall) {
                if (cue != null) return invalid(source, "More than one cue-ball candidate was detected.");
                cue = ball;
            } else if (ball.number >= 1 && ball.number <= 9
                    && ball.identityConfidence >= MIN_IDENTITY_CONFIDENCE) {
                if (recognized.put(ball.number, ball) != null) {
                    return invalid(source, "Duplicate OCR identity for ball " + ball.number + ".");
                }
            } else {
                unknown.add(ball);
            }
        }

        if (cue == null) {
            stableFrames = 0;
            previousVisible.clear();
            return result(source, false, true,
                    "Cue ball is not confidently visible; inventory and motion state are unknown.");
        }

        if (!baselineEstablished) {
            return establishBaseline(cue, recognized, unknown);
        }

        Map<Integer, BallObservation> current = new LinkedHashMap<>(recognized);
        Set<Integer> usedNumbers = new HashSet<>(recognized.keySet());
        int unresolvedUnknown = 0;
        for (BallObservation ball : unknown) {
            if (ball == null || ball.cueBall) {
                unresolvedUnknown++;
                continue;
            }
            Integer inherited = findUniqueSpatialIdentity(ball, usedNumbers);
            if (inherited == null) {
                unresolvedUnknown++;
                continue;
            }
            BallObservation resolved = new BallObservation(ball.center, ball.radius,
                    inherited, false, ball.detectionConfidence,
                    Math.min(INHERITED_IDENTITY_CONFIDENCE, ball.detectionConfidence));
            current.put(inherited, resolved);
            usedNumbers.add(inherited);
        }

        for (Integer number : current.keySet()) {
            if (pocketed.contains(number)) {
                stableFrames = 0;
                previousVisible.clear();
                return result(withCue(cue, current), false, true,
                        "A previously pocketed ball number reappeared; reset and reacquire the rack.");
            }
            if (!expected.contains(number)) {
                stableFrames = 0;
                previousVisible.clear();
                return result(withCue(cue, current), false, true,
                        "Observed ball " + number + " is outside the remembered rack inventory.");
            }
        }

        boolean missingUnconfirmed = false;
        List<Integer> newlyPocketed = new ArrayList<>();
        for (Integer number : new ArrayList<>(expected)) {
            BallObservation now = current.get(number);
            if (now == null) {
                int count = missingFrames.containsKey(number)
                        ? missingFrames.get(number) + 1 : 1;
                missingFrames.put(number, count);
                BallObservation last = lastKnown.get(number);
                boolean moved = Boolean.TRUE.equals(movedSinceLastSeen.get(number));
                if (count >= MISSING_FRAMES_TO_CONFIRM_POCKET && moved
                        && last != null && nearConfidentPocket(last, pocketList)) {
                    newlyPocketed.add(number);
                } else {
                    missingUnconfirmed = true;
                }
                continue;
            }

            BallObservation last = lastKnown.get(number);
            if (last != null && now.center.distance(last.center) > movementThreshold(now, last)) {
                movedSinceLastSeen.put(number, true);
            }
            lastKnown.put(number, now);
            missingFrames.put(number, 0);
        }

        for (Integer number : newlyPocketed) {
            expected.remove(number);
            pocketed.add(number);
            lastKnown.remove(number);
            missingFrames.remove(number);
            movedSinceLastSeen.remove(number);
        }

        List<BallObservation> normalized = withCue(cue, current);
        boolean allExpectedVisible = current.keySet().containsAll(expected)
                && current.size() == expected.size();
        boolean inventoryComplete = unresolvedUnknown == 0
                && !missingUnconfirmed && allExpectedVisible;

        Map<Integer, BallObservation> currentPositions = new LinkedHashMap<>();
        currentPositions.put(0, cue);
        for (Map.Entry<Integer, BallObservation> entry : current.entrySet()) {
            currentPositions.put(entry.getKey(), entry.getValue());
        }

        boolean sameVisibleSet = currentPositions.keySet().equals(previousVisible.keySet());
        boolean moving = !sameVisibleSet;
        if (sameVisibleSet) {
            for (Integer key : currentPositions.keySet()) {
                BallObservation before = previousVisible.get(key);
                BallObservation after = currentPositions.get(key);
                if (before == null || after.center.distance(before.center)
                        > movementThreshold(after, before)) {
                    moving = true;
                    break;
                }
            }
        }

        if (!inventoryComplete) {
            stableFrames = 0;
            moving = true;
        } else if (moving) {
            stableFrames = 0;
        } else {
            stableFrames++;
        }
        previousVisible = currentPositions;

        String reason;
        if (unresolvedUnknown > 0) {
            reason = unresolvedUnknown + " detected ball(s) have unresolved identities.";
        } else if (missingUnconfirmed) {
            reason = "A remembered ball is missing but has not been confirmed pocketed.";
        } else if (!inventoryComplete) {
            reason = "The visible balls do not match the remembered remaining inventory.";
        } else if (moving) {
            reason = "Ball movement or an inventory transition was detected.";
        } else {
            reason = "Inventory reconciled; " + expected.size()
                    + " object ball(s) remain and the table is stable.";
        }
        return new Result(normalized, inventoryComplete, moving, stableFrames,
                baselineEstablished, expected, pocketed, reason);
    }

    private Result establishBaseline(BallObservation cue,
                                     Map<Integer, BallObservation> recognized,
                                     List<BallObservation> unknown) {
        boolean fullRack = recognized.size() == 9 && unknown.isEmpty();
        for (int number = 1; number <= 9; number++) {
            if (!recognized.containsKey(number)) fullRack = false;
        }
        if (!fullRack) {
            baselineFrames = 0;
            baselinePrevious.clear();
            stableFrames = 0;
            return result(withCue(cue, recognized), false, true,
                    "Rack baseline not established: show the cue ball and all numbered balls 1–9 before the break.");
        }

        Map<Integer, BallObservation> current = new LinkedHashMap<>();
        current.put(0, cue);
        current.putAll(recognized);
        boolean stableAgainstPrevious = current.keySet().equals(baselinePrevious.keySet());
        if (stableAgainstPrevious) {
            for (Integer key : current.keySet()) {
                BallObservation before = baselinePrevious.get(key);
                BallObservation after = current.get(key);
                if (before == null || after.center.distance(before.center)
                        > movementThreshold(after, before)) {
                    stableAgainstPrevious = false;
                    break;
                }
            }
        }
        baselineFrames = stableAgainstPrevious ? baselineFrames + 1 : 1;
        baselinePrevious = current;
        if (baselineFrames < BASELINE_CONFIRM_FRAMES) {
            stableFrames = 0;
            return result(withCue(cue, recognized), false, true,
                    "Full rack seen once; waiting for a second matching frame to lock ball identities.");
        }

        baselineEstablished = true;
        for (int number = 1; number <= 9; number++) {
            expected.add(number);
            lastKnown.put(number, recognized.get(number));
            missingFrames.put(number, 0);
            movedSinceLastSeen.put(number, false);
        }
        previousVisible = current;
        stableFrames = 2;
        return new Result(withCue(cue, recognized), true, false, stableFrames,
                true, expected, pocketed,
                "Full 1–9 rack baseline locked from two consecutive matching frames.");
    }

    private Integer findUniqueSpatialIdentity(BallObservation unknown,
                                               Set<Integer> alreadyUsed) {
        Integer bestNumber = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double secondDistance = Double.POSITIVE_INFINITY;
        for (Integer number : expected) {
            if (alreadyUsed.contains(number)) continue;
            BallObservation last = lastKnown.get(number);
            if (last == null) continue;
            double distance = unknown.center.distance(last.center);
            double maxDistance = Math.max(20.0, Math.max(unknown.radius, last.radius) * 3.0);
            if (distance > maxDistance) continue;
            if (distance < bestDistance) {
                secondDistance = bestDistance;
                bestDistance = distance;
                bestNumber = number;
            } else if (distance < secondDistance) {
                secondDistance = distance;
            }
        }
        if (bestNumber == null) return null;
        if (Double.isFinite(secondDistance)
                && secondDistance - bestDistance < Math.max(6.0, bestDistance * 0.35)) {
            return null;
        }
        return bestNumber;
    }

    private boolean nearConfidentPocket(BallObservation ball,
                                        List<PocketObservation> pockets) {
        for (PocketObservation pocket : pockets) {
            if (pocket == null || pocket.confidence < 0.72) continue;
            double captureRadius = Math.max(pocket.openingWidth, pocket.openingHeight) * 0.55
                    + ball.radius * 1.45;
            if (ball.center.distance(pocket.center) <= captureRadius) return true;
        }
        return false;
    }

    private double movementThreshold(BallObservation a, BallObservation b) {
        return Math.max(2.5, Math.max(a.radius, b.radius) * 0.22);
    }

    private List<BallObservation> withCue(BallObservation cue,
                                          Map<Integer, BallObservation> objects) {
        List<BallObservation> result = new ArrayList<>();
        if (cue != null) result.add(cue);
        result.addAll(objects.values());
        return result;
    }

    private Result invalid(List<BallObservation> balls, String reason) {
        stableFrames = 0;
        previousVisible.clear();
        return result(balls, false, true, reason);
    }

    private Result result(List<BallObservation> balls, boolean complete,
                          boolean moving, String reason) {
        return new Result(balls, complete, moving, stableFrames,
                baselineEstablished, expected, pocketed, reason);
    }

    public void reset() {
        expected.clear();
        pocketed.clear();
        lastKnown.clear();
        missingFrames.clear();
        movedSinceLastSeen.clear();
        previousVisible.clear();
        baselinePrevious.clear();
        baselineEstablished = false;
        baselineFrames = 0;
        stableFrames = 0;
    }

    public static final class Result {
        public final List<BallObservation> balls;
        public final boolean inventoryComplete;
        public final boolean ballsMoving;
        public final int consecutiveStableFrames;
        public final boolean baselineEstablished;
        public final Set<Integer> remainingNumbers;
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
