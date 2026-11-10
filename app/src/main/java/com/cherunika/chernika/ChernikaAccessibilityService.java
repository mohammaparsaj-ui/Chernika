package com.cherunika.chernika;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import com.cherunika.chernika.engine.BallObservation;
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
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screenshot/OCR adapter for the instruction-first engine.
 *
 * This development build is intentionally DRY-RUN ONLY. It reports legal
 * direct-shot candidates but does not dispatch taps, drags, spin or power input.
 */
public class ChernikaAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean captureBusy = new AtomicBoolean(false);
    private final VisionAnalyzer vision = new VisionAnalyzer();
    private final HudInstructionParser hudParser = new HudInstructionParser();
    private final InstructionFirstEngine engine = new InstructionFirstEngine();

    private TextRecognizer recognizer;
    private long lastCaptureRequestAt;
    private boolean loopStarted;
    private int stableFrames;
    private Map<String, Vec2> previousPositions = new HashMap<>();

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (isEngineEnabled()) captureFrame();
            handler.postDelayed(this, 1100);
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
        setStatus("Instruction-first engine connected in DRY-RUN mode. No touch gestures will be sent.");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isEngineEnabled() && !captureBusy.get()) captureFrame();
    }

    @Override public void onInterrupt() {
        setStatus("Accessibility interrupted. No game gestures are active.");
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }

    private boolean isEngineEnabled() {
        return getSharedPreferences("chernika", MODE_PRIVATE)
                .getBoolean("engine_enabled", false);
    }

    private void captureFrame() {
        long now = System.currentTimeMillis();
        if (now - lastCaptureRequestAt < 1000) return;
        if (Build.VERSION.SDK_INT < 30 || recognizer == null
                || !captureBusy.compareAndSet(false, true)) return;
        lastCaptureRequestAt = now;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap bitmap = null;
                    HardwareBuffer buffer = null;
                    try {
                        buffer = result.getHardwareBuffer();
                        ColorSpace colorSpace = result.getColorSpace();
                        Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                        if (wrapped != null) {
                            bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                            wrapped.recycle();
                        }
                    } catch (Throwable error) {
                        setStatus("Screen capture failed: " + error.getClass().getSimpleName());
                    } finally {
                        if (buffer != null) buffer.close();
                    }
                    if (bitmap == null) {
                        captureBusy.set(false);
                        return;
                    }
                    processFrame(bitmap);
                }

                @Override public void onFailure(int errorCode) {
                    captureBusy.set(false);
                    setStatus("Screen capture unavailable (code " + errorCode + ").");
                }
            });
        } catch (Throwable error) {
            captureBusy.set(false);
            setStatus("Capture error: " + error.getClass().getSimpleName());
        }
    }

    private void processFrame(Bitmap bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    if (!isEngineEnabled()) {
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
                        if (isEngineEnabled()) evaluateAndReport(frame, visibleText);
                        finishCapture(bitmap);
                    });
                })
                .addOnFailureListener(error -> {
                    setStatus("HUD OCR failed: " + error.getClass().getSimpleName());
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
            setStatus("Ball-number OCR failed: " + error.getClass().getSimpleName());
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
                    // OCR confidence is intentionally conservative until it is
                    // calibrated against a labelled set of Plato ball crops.
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
        EngineDecision decision = engine.evaluate(observation);
        if (decision.status == EngineDecision.Status.WAIT) {
            setStatus("DRY RUN · WAIT\n" + decision.reason
                    + "\nStable frames: " + stableFrames
                    + " · refined pockets: " + frame.refinedPocketCount + "/6"
                    + "\nNo touch gestures are sent by this build.");
            return;
        }

        setStatus(String.format(Locale.US,
                "DRY RUN · candidate only\nLegal first contact: ball %d\nRoute: %s\nAim point: %.1f, %.1f\n"
                        + "Spin: center only · stable frames: %d\nNo touch gestures are sent; power calibration is not yet authorized.",
                decision.legalBall, decision.shotKind, decision.aimPoint.x,
                decision.aimPoint.y, stableFrames));
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

        List<BallObservation> balls = new ArrayList<>();
        for (Ball ball : frame.balls) {
            double confidence = ball.cue
                    ? ball.detectionConfidence
                    : Math.min(ball.detectionConfidence, ball.numberConfidence);
            balls.add(new BallObservation(
                    new Point2(ball.center.x, ball.center.y),
                    ball.radius, ball.cue ? 0 : ball.number,
                    ball.cue, confidence));
        }

        HudInstruction instruction = hudParser.parse(visibleText);
        boolean replay = isReplayOrResultsScreen(visibleText);
        boolean live = !replay && (instruction.type == HudInstruction.Type.HIT_BALL
                || instruction.type == HudInstruction.Type.POCKET_BALL
                || instruction.type == HudInstruction.Type.BALL_IN_HAND);
        int stable = updateStability(frame);
        boolean inventoryComplete = hasNumberedInventory(frame);
        double powerConfidence = frame.powerBar == null ? 0.0 : 0.80;

        return new GameObservation(bounds, tableConfidence, pockets, balls, instruction,
                inventoryComplete, live, replay, stable < 3, stable, powerConfidence);
    }

    private boolean hasNumberedInventory(FrameState frame) {
        int cueCount = 0;
        int objectCount = 0;
        Set<Integer> numbers = new HashSet<>();
        for (Ball ball : frame.balls) {
            if (ball.cue) {
                cueCount++;
            } else {
                objectCount++;
                if (ball.number < 1 || ball.number > 9 || ball.numberConfidence < 0.80
                        || !numbers.add(ball.number)) return false;
            }
        }
        return cueCount == 1 && objectCount >= 1 && objectCount <= 9;
    }

    private int updateStability(FrameState frame) {
        Map<String, Vec2> current = new HashMap<>();
        for (Ball ball : frame.balls) {
            String key;
            if (ball.cue) {
                key = "cue";
            } else {
                if (ball.number < 1 || ball.number > 9 || current.containsKey("ball-" + ball.number)) {
                    stableFrames = 0;
                    previousPositions.clear();
                    return 0;
                }
                key = "ball-" + ball.number;
            }
            if (current.put(key, ball.center) != null) {
                stableFrames = 0;
                previousPositions.clear();
                return 0;
            }
        }
        if (!current.containsKey("cue") || current.isEmpty()) {
            stableFrames = 0;
            previousPositions.clear();
            return 0;
        }

        boolean sameInventory = current.keySet().equals(previousPositions.keySet());
        boolean positionsStable = sameInventory;
        if (sameInventory) {
            for (String key : current.keySet()) {
                if (current.get(key).distance(previousPositions.get(key)) > 2.0) {
                    positionsStable = false;
                    break;
                }
            }
        }
        stableFrames = positionsStable ? stableFrames + 1 : 1;
        previousPositions = current;
        return stableFrames;
    }

    private boolean isReplayOrResultsScreen(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return normalized.contains("best shot") || normalized.contains("game over")
                || normalized.contains("match over") || normalized.contains("play again")
                || normalized.contains("watch replay") || normalized.contains("replay");
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
