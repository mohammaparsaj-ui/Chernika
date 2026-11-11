package com.cherunika.chernika;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.cherunika.chernika.engine.BallObservation;
import com.cherunika.chernika.engine.BallStateTracker;
import com.cherunika.chernika.engine.EngineDecision;
import com.cherunika.chernika.engine.GameObservation;
import com.cherunika.chernika.engine.HudInstruction;
import com.cherunika.chernika.engine.HudInstructionParser;
import com.cherunika.chernika.engine.InstructionFirstEngine;
import com.cherunika.chernika.engine.PocketObservation;
import com.cherunika.chernika.engine.Point2;
import com.cherunika.chernika.engine.Rect2;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MediaProjection supplies screen frames; Accessibility handles app detection and gestures. */
public class ChernikaAccessibilityService extends AccessibilityService {
    private static final long CAPTURE_INTERVAL_MS = 1100;
    private static final long GESTURE_RETRY_COOLDOWN_MS = 4500;
    private static final long NO_MOTION_REARM_MS = 8000;
    private static final double MIN_POWER_PERCENT = 26.5;
    private static final double MAX_POWER_PERCENT = 62.4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean captureBusy = new AtomicBoolean(false);
    private final VisionAnalyzer vision = new VisionAnalyzer();
    private final HudInstructionParser hudParser = new HudInstructionParser();
    private final InstructionFirstEngine engine = new InstructionFirstEngine();
    private final BallStateTracker ballStateTracker = new BallStateTracker();

    private TextRecognizer recognizer;
    private long lastCaptureRequestAt;
    private long lastGestureAttemptAt;
    private long lastShotAt;
    private int lastProjectionFrameSequence;
    private boolean loopStarted;
    private String foregroundPackage = "";
    private int stableFrames;
    private int candidatePlanFrames;
    private int yourTurnFalseFrames;
    private String candidatePlanKey = "";
    private String trackingStatus = "Waiting for a full rack baseline.";
    private String lastShotInstructionKey = "";
    private int lastShotLegalBall;
    private boolean awaitingShotResolution;
    private boolean motionObservedSinceShot;
    private boolean lastTurnWasOurs;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (isEngineEnabled()) {
                if (isPlatoForeground()) {
                    captureFrame();
                } else {
                    setStatus("Activated · waiting for Plato 9-ball.");
                }
            }
            handler.postDelayed(this, CAPTURE_INTERVAL_MS);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 250;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        if (!loopStarted) {
            loopStarted = true;
            handler.post(loop);
        }
        setStatus("Accessibility ready · waiting for activation.");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            String eventPackage = event.getPackageName().toString();
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || isPlatoPackage(eventPackage)) {
                foregroundPackage = eventPackage;
            }
        }
        if (isEngineEnabled() && isPlatoForeground() && !captureBusy.get()) captureFrame();
    }

    @Override public void onInterrupt() {
        setStatus("Accessibility interrupted · automation paused.");
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        ballStateTracker.reset();
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }

    private boolean isEngineEnabled() {
        return getSharedPreferences("chernika", MODE_PRIVATE)
                .getBoolean("engine_enabled", false);
    }

    private boolean isPlatoPackage(String packageName) {
        return packageName != null
                && packageName.toLowerCase(Locale.ROOT).contains("plato");
    }

    private boolean isPlatoForeground() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                foregroundPackage = root.getPackageName().toString();
                return isPlatoPackage(foregroundPackage);
            }
        } catch (Throwable ignored) {
            // Fall back to the most recent window-state package event.
        } finally {
            if (root != null) root.recycle();
        }
        return isPlatoPackage(foregroundPackage);
    }

    private void captureFrame() {
        if (!isEngineEnabled() || !isPlatoForeground()) return;
        long now = System.currentTimeMillis();
        if (now - lastCaptureRequestAt < 1000) return;
        if (!captureBusy.compareAndSet(false, true)) return;
        lastCaptureRequestAt = now;

        MediaProjectionCaptureService.FrameSnapshot snapshot =
                MediaProjectionCaptureService.getLatestFrame();
        if (snapshot == null) {
            captureBusy.set(false);
            if (MediaProjectionCaptureService.isRunning()) {
                setStatus("Screen capture is starting · waiting for the first frame.");
            } else {
                setStatus("Screen capture permission is missing. Tap STOP, then ACTIVATE and approve it.");
            }
            return;
        }
        if (snapshot.sequence <= lastProjectionFrameSequence) {
            if (!snapshot.bitmap.isRecycled()) snapshot.bitmap.recycle();
            captureBusy.set(false);
            return;
        }
        lastProjectionFrameSequence = snapshot.sequence;
        processFrame(snapshot.bitmap);
    }

    private void processFrame(Bitmap bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    if (!isEngineEnabled() || !isPlatoForeground()) {
                        finishCapture(bitmap);
                        return;
                    }
                    String visibleText = text == null ? "" : text.getText();
                    final FrameState frame;
                    try {
                        frame = vision.analyze(bitmap);
                    } catch (Throwable error) {
                        setStatus("Vision analysis failed: " + error.getClass().getSimpleName());
                        finishCapture(bitmap);
                        return;
                    }
                    readBallNumbers(bitmap, frame, () -> {
                        if (isEngineEnabled() && isPlatoForeground()) {
                            evaluateAndReport(frame, visibleText);
                        }
                        finishCapture(bitmap);
                    });
                })
                .addOnFailureListener(error -> {
                    setStatus("HUD OCR not ready: " + error.getClass().getSimpleName()
                            + ". First use may be downloading the Google Play services OCR model; "
                            + "keep internet on and leave Chernika active.");
                    finishCapture(bitmap);
                });
    }

    private void readBallNumbers(Bitmap source, FrameState frame, Runnable finished) {
        List<Ball> candidates = new ArrayList<>();
        for (Ball ball : frame.balls) if (!ball.cue) candidates.add(ball);
        List<Task<NumberRead>> tasks = new ArrayList<>();
        for (Ball ball : candidates) tasks.add(readBallNumber(source, ball));

        if (tasks.isEmpty()) {
            finished.run();
            return;
        }
        Tasks.whenAllComplete(tasks).addOnSuccessListener(ignored -> {
            for (int i = 0; i < tasks.size(); i++) {
                Task<NumberRead> task = tasks.get(i);
                if (task.isSuccessful() && task.getResult() != null) {
                    NumberRead read = task.getResult();
                    candidates.get(i).number = read.number;
                    candidates.get(i).numberConfidence = read.confidence;
                }
            }
            finished.run();
        }).addOnFailureListener(error -> {
            setStatus("Ball-number OCR not ready: " + error.getClass().getSimpleName()
                    + ". The first-run OCR model may still be downloading.");
            finished.run();
        });
    }

    private Task<NumberRead> readBallNumber(Bitmap source, Ball ball) {
        try {
            int radius = Math.max(10, (int) Math.ceil(ball.radius * 1.35));
            int left = Math.max(0, (int) ball.center.x - radius);
            int top = Math.max(0, (int) ball.center.y - radius);
            int right = Math.min(source.getWidth(), (int) ball.center.x + radius);
            int bottom = Math.min(source.getHeight(), (int) ball.center.y + radius);
            if (right - left < 4 || bottom - top < 4) {
                return Tasks.forResult(new NumberRead(0, 0.0));
            }
            Bitmap crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top);
            Bitmap enlarged = Bitmap.createScaledBitmap(
                    crop, crop.getWidth() * 4, crop.getHeight() * 4, true);
            crop.recycle();
            return recognizer.process(InputImage.fromBitmap(enlarged, 0)).continueWith(task -> {
                try {
                    if (!task.isSuccessful() || task.getResult() == null) {
                        return new NumberRead(0, 0.0);
                    }
                    Matcher matcher = Pattern.compile("(?<!\\d)([1-9])(?!\\d)")
                            .matcher(task.getResult().getText());
                    if (!matcher.find()) return new NumberRead(0, 0.0);
                    int number = Integer.parseInt(matcher.group(1));
                    if (matcher.find()) return new NumberRead(0, 0.0);
                    return new NumberRead(number, 0.84);
                } finally {
                    if (!enlarged.isRecycled()) enlarged.recycle();
                }
            });
        } catch (Throwable ignored) {
            return Tasks.forResult(new NumberRead(0, 0.0));
        }
    }

    private void evaluateAndReport(FrameState frame, String visibleText) {
        GameObservation observation = toObservation(frame, visibleText);

        if (!observation.yourTurn) {
            candidatePlanFrames = 0;
            candidatePlanKey = "";
            yourTurnFalseFrames++;
            if (yourTurnFalseFrames >= 2 && lastTurnWasOurs) {
                awaitingShotResolution = false;
                motionObservedSinceShot = false;
                lastTurnWasOurs = false;
            }
            setStatus("Plato detected · waiting for your turn."
                    + "\nTracking: " + trackingStatus);
            return;
        }

        yourTurnFalseFrames = 0;
        if (!lastTurnWasOurs) {
            awaitingShotResolution = false;
            motionObservedSinceShot = false;
            candidatePlanFrames = 0;
            candidatePlanKey = "";
        }
        lastTurnWasOurs = true;

        if (awaitingShotResolution && observation.ballsMoving) {
            motionObservedSinceShot = true;
        }

        EngineDecision decision = engine.evaluate(observation);
        long now = System.currentTimeMillis();

        if (awaitingShotResolution) {
            if (decision.isPlanReady() && decision.legalBall != lastShotLegalBall) {
                awaitingShotResolution = false;
            } else if (motionObservedSinceShot && !observation.ballsMoving
                    && observation.consecutiveStableFrames >= 3) {
                awaitingShotResolution = false;
            } else if (!motionObservedSinceShot && now - lastShotAt >= NO_MOTION_REARM_MS) {
                awaitingShotResolution = false;
            }
        }

        if (decision.status == EngineDecision.Status.WAIT) {
            candidatePlanFrames = 0;
            candidatePlanKey = "";
            if (awaitingShotResolution) {
                setStatus("Shot sent · waiting for balls to stop and the next legal state."
                        + "\nTracking: " + trackingStatus);
            } else {
                setStatus("WAIT · " + decision.reason
                        + "\nTracking: " + trackingStatus
                        + "\nStable frames: " + stableFrames
                        + " · pockets refined: " + frame.refinedPocketCount + "/6");
            }
            return;
        }

        if (awaitingShotResolution) {
            setStatus("Shot sent · waiting for the result before selecting another shot.");
            return;
        }

        String planKey = String.format(Locale.US, "%d:%s:%d:%d:%.0f",
                decision.legalBall, decision.shotKind,
                Math.round(decision.aimPoint.x / 12.0),
                Math.round(decision.aimPoint.y / 12.0),
                decision.powerPercent);
        if (planKey.equals(candidatePlanKey)) {
            candidatePlanFrames++;
        } else {
            candidatePlanKey = planKey;
            candidatePlanFrames = 1;
        }

        if (candidatePlanFrames < 2) {
            setStatus(String.format(Locale.US,
                    "Ready · direct %s on ball %d · power %.1f%% · confirming (%d/2).",
                    decision.shotKind, decision.legalBall, decision.powerPercent,
                    candidatePlanFrames));
            return;
        }

        if (now - lastGestureAttemptAt < GESTURE_RETRY_COOLDOWN_MS) {
            setStatus("Ready · waiting for the gesture cooldown.");
            return;
        }
        executePlan(frame, decision, observation);
    }

    private GameObservation toObservation(FrameState frame, String visibleText) {
        Rect2 bounds = null;
        double tableConfidence = 0.0;
        List<PocketObservation> pockets = new ArrayList<>();
        if (frame.table != null) {
            bounds = new Rect2(frame.table.cushionNoseBounds.left,
                    frame.table.cushionNoseBounds.top,
                    frame.table.cushionNoseBounds.right,
                    frame.table.cushionNoseBounds.bottom);
            tableConfidence = frame.table.aspectRatioFit;
            for (Pocket pocket : frame.table.pockets()) {
                pockets.add(new PocketObservation(
                        new Point2(pocket.center.x, pocket.center.y),
                        pocket.openingWidth, pocket.openingHeight,
                        pocket.visionConfidence));
            }
        }

        List<BallObservation> rawBalls = new ArrayList<>();
        for (Ball ball : frame.balls) {
            rawBalls.add(new BallObservation(
                    new Point2(ball.center.x, ball.center.y),
                    ball.radius,
                    ball.cue ? 0 : ball.number,
                    ball.cue,
                    ball.detectionConfidence,
                    ball.cue ? 1.0 : ball.numberConfidence));
        }

        HudInstruction instruction = hudParser.parse(visibleText);
        boolean replay = isReplayOrResultsScreen(visibleText);
        if (replay || !frame.hasUsableTable()) ballStateTracker.reset();
        BallStateTracker.Result tracked = ballStateTracker.update(
                replay ? Collections.emptyList() : rawBalls, pockets);
        List<BallObservation> balls = tracked.balls;
        stableFrames = tracked.consecutiveStableFrames;
        trackingStatus = tracked.reason;

        boolean live = !replay && (instruction.type == HudInstruction.Type.HIT_BALL
                || instruction.type == HudInstruction.Type.POCKET_BALL
                || instruction.type == HudInstruction.Type.BALL_IN_HAND);
        boolean yourTurn = isUsersTurn(visibleText);
        double powerConfidence = frame.powerBar == null ? 0.0 : frame.powerBar.confidence;

        return new GameObservation(bounds, tableConfidence, pockets, balls, instruction,
                tracked.inventoryComplete, live, replay, tracked.ballsMoving,
                tracked.consecutiveStableFrames, powerConfidence, yourTurn);
    }

    private boolean isUsersTurn(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9']+", " ").replaceAll("\\s+", " ").trim();
        return normalized.contains("your turn")
                || normalized.contains("chernika's turn")
                || normalized.contains("chernika s turn");
    }

    private boolean isReplayOrResultsScreen(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return normalized.contains("best shot") || normalized.contains("game over")
                || normalized.contains("match over") || normalized.contains("match complete")
                || normalized.contains("match finished") || normalized.contains("game finished")
                || normalized.contains("play again") || normalized.contains("watch replay")
                || normalized.contains("replay") || normalized.contains("you won")
                || normalized.contains("you lost") || normalized.contains("you lose")
                || normalized.contains("opponent won") || normalized.contains("victory")
                || normalized.contains("defeat");
    }

    private void executePlan(FrameState frame, EngineDecision decision,
                             GameObservation observation) {
        if (!isEngineEnabled() || !isPlatoForeground()
                || !decision.gestureExecutionEnabled || frame.cueBall == null
                || frame.powerBar == null) return;

        double powerPercent = decision.powerPercent;
        if (powerPercent < MIN_POWER_PERCENT || powerPercent > MAX_POWER_PERCENT) {
            setStatus("WAIT · proposed power is outside the recorded safe interval.");
            return;
        }

        Vec2 cue = frame.cueBall.center;
        Vec2 aim = new Vec2(decision.aimPoint.x, decision.aimPoint.y);
        double aimDistance = cue.distance(aim);
        if (aimDistance < Math.max(24.0, frame.cueBall.radius * 1.7)) {
            setStatus("WAIT · aim displacement is too small to execute safely.");
            return;
        }

        lastGestureAttemptAt = System.currentTimeMillis();
        setStatus(String.format(Locale.US,
                "Executing direct shot · legal ball %d · %s · %.1f%% power.",
                decision.legalBall, decision.shotKind, powerPercent));

        dispatchDrag(cue, aim, 360, aimAccepted -> {
            if (!aimAccepted || !isEngineEnabled() || !isPlatoForeground()) {
                candidatePlanFrames = 0;
                setStatus("Aim gesture was not accepted · engine remains armed.");
                return;
            }

            handler.postDelayed(() -> {
                if (!isEngineEnabled() || !isPlatoForeground()) return;
                PowerBar bar = frame.powerBar;
                float targetY = bar.yForPower((float) powerPercent);
                if (targetY <= bar.top + 1.0f || targetY >= bar.bottom - 1.0f) {
                    candidatePlanFrames = 0;
                    setStatus("Power target is outside the detected slider.");
                    return;
                }
                Vec2 powerStart = new Vec2(bar.centerX, bar.top);
                Vec2 powerEnd = new Vec2(bar.centerX, targetY);
                dispatchDrag(powerStart, powerEnd, 320, powerAccepted -> {
                    if (powerAccepted && isEngineEnabled()) {
                        awaitingShotResolution = true;
                        motionObservedSinceShot = false;
                        lastShotAt = System.currentTimeMillis();
                        lastShotLegalBall = decision.legalBall;
                        lastShotInstructionKey = observation.instruction.type + ":"
                                + observation.instruction.ballNumber;
                        candidatePlanFrames = 0;
                        candidatePlanKey = "";
                        setStatus(String.format(Locale.US,
                                "Shot sent · ball %d · %.1f%% slider power · waiting for result.",
                                decision.legalBall, powerPercent));
                    } else {
                        candidatePlanFrames = 0;
                        setStatus("Power gesture failed · no shot was confirmed.");
                    }
                });
            }, 180);
        });
    }

    private void dispatchDrag(Vec2 from, Vec2 to, long durationMs,
                              GestureResult callback) {
        if (!isEngineEnabled() || !isPlatoForeground()
                || from == null || to == null || from.distance(to) < 2.0) {
            callback.complete(false);
            return;
        }
        Path path = new Path();
        path.moveTo((float) from.x, (float) from.y);
        path.lineTo((float) to.x, (float) to.y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                callback.complete(true);
            }
            @Override public void onCancelled(GestureDescription description) {
                callback.complete(false);
            }
        }, handler);
        if (!accepted) callback.complete(false);
    }

    private interface GestureResult {
        void complete(boolean success);
    }

    private void finishCapture(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        captureBusy.set(false);
    }

    private void setStatus(String message) {
        getSharedPreferences("chernika", MODE_PRIVATE).edit()
                .putString("last_status", message).apply();
    }

    private static final class NumberRead {
        final int number;
        final double confidence;

        NumberRead(int number, double confidence) {
            this.number = number;
            this.confidence = confidence;
        }
    }

}
