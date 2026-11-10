package com.cherunika.chernika.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * Fail-closed instruction-first decision core for Plato 9-ball.
 * This class only proposes a direct shot; it never dispatches taps or drags.
 */
public final class InstructionFirstEngine {
    private static final double MIN_TABLE_CONFIDENCE = 0.88;
    private static final double MIN_POCKET_CONFIDENCE = 0.72;
    private static final double MIN_BALL_CONFIDENCE = 0.78;
    private static final int REQUIRED_STABLE_FRAMES = 3;

    private final DirectShotPlanner planner = new DirectShotPlanner();

    public EngineDecision evaluate(GameObservation observation) {
        String invalid = validate(observation);
        if (invalid != null) return EngineDecision.waitFor(invalid);

        if (observation.instruction.type == HudInstruction.Type.BALL_IN_HAND) {
            return EngineDecision.waitFor(
                    "Ball-in-hand detected. v1 does not reposition the cue ball automatically.");
        }
        if (observation.instruction.type != HudInstruction.Type.HIT_BALL
                && observation.instruction.type != HudInstruction.Type.POCKET_BALL) {
            return EngineDecision.waitFor("No unambiguous live 9-ball HUD instruction.");
        }

        BallObservation cue = null;
        BallObservation[] byNumber = new BallObservation[10];
        int lowest = 10;
        for (BallObservation ball : observation.balls) {
            if (ball.cueBall) {
                cue = ball;
            } else {
                byNumber[ball.number] = ball;
                lowest = Math.min(lowest, ball.number);
            }
        }
        if (cue == null || lowest > 9) return EngineDecision.waitFor("Cue ball or object-ball inventory is missing.");

        int objectiveBall = 0;
        int legalBall = lowest;
        if (observation.instruction.type == HudInstruction.Type.HIT_BALL) {
            legalBall = observation.instruction.ballNumber;
            if (legalBall != lowest) {
                return EngineDecision.waitFor("HUD target " + legalBall
                        + " disagrees with the lowest remaining ball " + lowest + ".");
            }
        } else {
            objectiveBall = observation.instruction.ballNumber;
            if (objectiveBall != 9) {
                return EngineDecision.waitFor("v1 only recognizes the confirmed 9-ball pocket objective.");
            }
            if (byNumber[objectiveBall] == null) {
                return EngineDecision.waitFor("HUD requests the 9 ball, but ball 9 is not confidently visible.");
            }
        }

        BallObservation target = byNumber[legalBall];
        if (target == null) return EngineDecision.waitFor("The required first-contact ball is not visible.");

        DirectShotPlanner.Result result = planner.plan(observation, cue, target, objectiveBall);
        if (result == null) {
            return EngineDecision.waitFor("No unobstructed direct legal-contact route is available.");
        }

        String reason = result.kind == EngineDecision.ShotKind.DIRECT_POT
                ? "Direct pot candidate found. Verify power calibration before any gesture."
                : "Direct legal first-contact candidate found; no direct pot is authorized in this state.";
        return EngineDecision.plan(legalBall, objectiveBall, result.kind,
                result.aimPoint, result.pocketIndex, reason);
    }

    private String validate(GameObservation observation) {
        if (observation == null) return "No observation.";
        if (observation.replayScreen || !observation.liveTable) {
            return "Screen is not confirmed as a live table; replay and non-game screens are blocked.";
        }
        if (observation.ballsMoving || observation.consecutiveStableFrames < REQUIRED_STABLE_FRAMES) {
            return "Waiting for the table to stop moving and remain stable for three frames.";
        }
        if (observation.playableBounds == null
                || observation.tableConfidence < MIN_TABLE_CONFIDENCE) {
            return "Table geometry is not sufficiently confident.";
        }
        if (observation.pockets.size() != 6) {
            return "Exactly six detected pockets are required; found " + observation.pockets.size() + ".";
        }
        for (PocketObservation pocket : observation.pockets) {
            if (pocket == null || pocket.confidence < MIN_POCKET_CONFIDENCE) {
                return "At least one pocket is not confidently localized.";
            }
        }
        if (!observation.inventoryComplete) {
            return "Ball inventory is incomplete; unknown or missed balls could obstruct the shot.";
        }
        if (observation.balls.isEmpty()) return "No balls detected.";
        int cueCount = 0;
        Set<Integer> numbers = new HashSet<>();
        for (BallObservation ball : observation.balls) {
            if (ball == null || ball.confidence < MIN_BALL_CONFIDENCE
                    || !observation.playableBounds.contains(ball.center, ball.radius * 0.75)) {
                return "A ball is uncertain or outside the calibrated playable surface.";
            }
            if (ball.cueBall) {
                cueCount++;
            } else {
                if (ball.number < 1 || ball.number > 9) {
                    return "An object ball has no reliable number; color alone is insufficient.";
                }
                if (!numbers.add(ball.number)) {
                    return "Duplicate detection for ball " + ball.number + ".";
                }
            }
        }
        if (cueCount != 1) return "Exactly one confidently detected cue ball is required.";
        if (numbers.isEmpty()) return "No numbered object balls remain visible.";
        if (observation.instruction == null) return "HUD instruction is missing.";
        return null;
    }
}
