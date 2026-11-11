package com.cherunika.chernika.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * HUD-first 9-ball decision core.
 *
 * The live HUD is the authoritative legal first-contact instruction. Readable
 * ball numbers are used to cross-check contradictions; unread balls remain
 * physical obstacles instead of blocking the entire game.
 */
public final class InstructionFirstEngine {
    private static final double MIN_TABLE_CONFIDENCE = 0.88;
    private static final double MIN_POCKET_CONFIDENCE = 0.72;
    private static final double MIN_BALL_CONFIDENCE = 0.72;
    private static final double MIN_IDENTITY_CONFIDENCE = 0.78;
    private static final double MIN_POWER_CONTROL_CONFIDENCE = 0.80;
    private static final int REQUIRED_STABLE_FRAMES = 2;

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
        Set<Integer> visibleNumbers = new HashSet<>();
        for (BallObservation ball : observation.balls) {
            if (ball.cueBall) {
                cue = ball;
            } else if (ball.number >= 1 && ball.number <= 9
                    && ball.identityConfidence >= MIN_IDENTITY_CONFIDENCE) {
                byNumber[ball.number] = ball;
                visibleNumbers.add(ball.number);
            }
        }
        if (cue == null) return EngineDecision.waitFor("Cue ball is not confidently visible.");

        int objectiveBall = 0;
        int legalBall;
        if (observation.instruction.type == HudInstruction.Type.HIT_BALL) {
            legalBall = observation.instruction.ballNumber;
            if (legalBall < 1 || legalBall > 9) {
                return EngineDecision.waitFor("HUD target is outside the 1–9 ball range.");
            }
            for (int number : visibleNumbers) {
                if (number < legalBall) {
                    return EngineDecision.waitFor("HUD says hit " + legalBall
                            + ", but lower-numbered ball " + number
                            + " is also visible. Recheck the live table and HUD.");
                }
            }
        } else {
            objectiveBall = observation.instruction.ballNumber;
            if (objectiveBall != 9) {
                return EngineDecision.waitFor("v1 only recognizes the confirmed 9-ball pocket objective.");
            }
            for (int number : visibleNumbers) {
                if (number < 9) {
                    return EngineDecision.waitFor("HUD says pocket the 9, but ball "
                            + number + " is still visible.");
                }
            }
            legalBall = 9;
        }

        BallObservation target = byNumber[legalBall];
        if (target == null) {
            return EngineDecision.waitFor("The HUD-required ball " + legalBall
                    + " is not confidently identified. Unread balls remain obstacles.");
        }

        DirectShotPlanner.Result result = planner.plan(
                observation, cue, target, objectiveBall);
        if (result == null) {
            return EngineDecision.waitFor("No unobstructed direct legal-contact route is available.");
        }

        String reason = result.kind == EngineDecision.ShotKind.DIRECT_POT
                ? "Direct pot candidate found from the HUD target."
                : "Direct legal first-contact candidate found; no direct pot is available.";
        return EngineDecision.plan(legalBall, objectiveBall, result.kind,
                result.aimPoint, result.pocketIndex, result.powerPercent, reason);
    }

    private String validate(GameObservation observation) {
        if (observation == null) return "No observation.";
        if (observation.replayScreen || !observation.liveTable) {
            return "Screen is not confirmed as a live table; replay and non-game screens are blocked.";
        }
        if (!observation.yourTurn) return "Waiting for the player's own turn.";
        if (observation.ballsMoving
                || observation.consecutiveStableFrames < REQUIRED_STABLE_FRAMES) {
            return "Waiting for the table to stop moving and remain stable for two frames.";
        }
        if (observation.playableBounds == null
                || observation.tableConfidence < MIN_TABLE_CONFIDENCE) {
            return "Table geometry is not sufficiently confident.";
        }
        if (observation.pockets.size() != 6) {
            return "Exactly six detected pockets are required; found "
                    + observation.pockets.size() + ".";
        }
        for (PocketObservation pocket : observation.pockets) {
            if (pocket == null || pocket.confidence < MIN_POCKET_CONFIDENCE) {
                return "At least one pocket is not confidently localized.";
            }
        }
        if (observation.powerControlConfidence < MIN_POWER_CONTROL_CONFIDENCE) {
            return "The vertical power slider is not confidently detected.";
        }
        if (observation.balls.isEmpty()) return "No balls detected.";

        int cueCount = 0;
        Set<Integer> numbers = new HashSet<>();
        for (BallObservation ball : observation.balls) {
            if (ball == null || ball.detectionConfidence < MIN_BALL_CONFIDENCE
                    || !observation.playableBounds.contains(
                            ball.center, ball.radius * 0.75)) {
                return "A detected ball is uncertain or outside the calibrated playable surface.";
            }
            if (ball.cueBall) {
                cueCount++;
                continue;
            }

            // Number 0 is allowed: it is still a physical obstacle for the
            // geometric planner. Only the HUD-required target needs an ID.
            if (ball.number == 0) continue;
            if (ball.number < 1 || ball.number > 9
                    || ball.identityConfidence < MIN_IDENTITY_CONFIDENCE) {
                return "A ball has an invalid or low-confidence identity.";
            }
            if (!numbers.add(ball.number)) {
                return "Duplicate detection for ball " + ball.number + ".";
            }
        }
        if (cueCount != 1) return "Exactly one confidently detected cue ball is required.";
        if (observation.instruction == null) return "HUD instruction is missing.";
        return null;
    }
}
