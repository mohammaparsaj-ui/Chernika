package com.cherunika.chernika;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChernikaAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean captureBusy = new AtomicBoolean(false);
    private final VisionAnalyzer vision = new VisionAnalyzer();
    private final TargetAnnouncementParser parser = new TargetAnnouncementParser();
    private final ShotPlanner planner = new ShotPlanner();
    private final LowestBallSelector lowestBallSelector = new LowestBallSelector();
    private final DiamondRackDetector rackDetector = new DiamondRackDetector();
    private TextRecognizer recognizer;
    private long lastShotAt = 0;
    private long lastCaptureRequestAt = 0;
    private String lastShotSignature = "";
    private boolean loopStarted = false;

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
        setStatus("Accessibility connected. Engine is waiting for Start.");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isEngineEnabled() && !captureBusy.get()) captureFrame();
    }

    @Override public void onInterrupt() {
        setStatus("Accessibility interrupted. Re-enable the service if needed.");
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
                    } catch (Throwable t) {
                        setStatus("Screen capture failed: " + t.getClass().getSimpleName());
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
                    setStatus("Screen capture unavailable (code " + errorCode
                            + "). Check Android accessibility permission.");
                }
            });
        } catch (Throwable t) {
            captureBusy.set(false);
            setStatus("Capture error: " + t.getClass().getSimpleName());
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
                    TargetAnnouncement announcement = parser.parse(visibleText);
                    FrameState frame = vision.analyze(bitmap);
                    setStatus(buildStatus(frame, announcement));
                    if (!frame.hasUsableTable() || frame.balls.isEmpty()) {
                        finishCapture(bitmap);
                        return;
                    }
                    readBallNumbers(bitmap, frame, announcement, () -> finishCapture(bitmap));
                })
                .addOnFailureListener(e -> {
                    setStatus("OCR error: " + e.getClass().getSimpleName());
                    finishCapture(bitmap);
                });
    }

    private void finishCapture(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        captureBusy.set(false);
    }

    private void readBallNumbers(Bitmap bitmap, FrameState frame,
                                 TargetAnnouncement announcement, Runnable finished) {
        List<Ball> candidates = new ArrayList<>();
        for (Ball ball : frame.balls) if (!ball.cue) candidates.add(ball);
        if (candidates.size() > 12) candidates = candidates.subList(0, 12);
        List<Task<Integer>> tasks = new ArrayList<>();
        for (Ball ball : candidates) tasks.add(readBallNumber(bitmap, ball));
        final List<Ball> finalCandidates = candidates;
        Tasks.whenAllComplete(tasks).addOnSuccessListener(done -> {
            for (int i = 0; i < tasks.size(); i++) {
                Task<Integer> task = tasks.get(i);
                if (task.isSuccessful() && task.getResult() != null
                        && task.getResult() >= 1 && task.getResult() <= 9) {
                    finalCandidates.get(i).applyOcrNumber(task.getResult());
                }
            }
            if (!isEngineEnabled()) {
                finished.run();
                return;
            }
            if (announcement.scratch) {
                handleScratch(frame, announcement);
                finished.run();
                return;
            }

            ShotPlan plan = null;
            if (announcement.hasTarget()) {
                plan = planner.plan(frame, announcement.legalBall, announcement.objectiveBall);
            } else {
                DiamondRackDetector.Result rack = rackDetector.detect(frame.balls, frame.table);
                if (rack.detected) {
                    plan = planner.planBreak(frame);
                } else {
                    LowestBallSelector.Selection selection =
                            lowestBallSelector.select(frame.balls, 0);
                    if (selection.isConfident()) {
                        plan = planner.plan(frame, selection.number, 0);
                    }
                }
            }

            if (plan == null) {
                setStatus(announcement.hasTarget()
                        ? "HUD target read; waiting for that ball and a clear pocket route."
                        : "Waiting for a verified diamond rack or a confident lowest-ball color read.");
                finished.run();
                return;
            }
            executePlan(frame, plan, announcement);
            finished.run();
        }).addOnFailureListener(e -> {
            setStatus("Ball recognition failed: " + e.getClass().getSimpleName());
            finished.run();
        });
    }

    private Task<Integer> readBallNumber(Bitmap source, Ball ball) {
        try {
            int radius = Math.max(10, (int) Math.ceil(ball.radius * 1.35));
            int left = Math.max(0, (int) ball.center.x - radius);
            int top = Math.max(0, (int) ball.center.y - radius);
            int right = Math.min(source.getWidth(), (int) ball.center.x + radius);
            int bottom = Math.min(source.getHeight(), (int) ball.center.y + radius);
            if (right - left < 4 || bottom - top < 4) return Tasks.forResult(0);
            Bitmap crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top);
            Bitmap enlarged = Bitmap.createScaledBitmap(crop,
                    crop.getWidth() * 4, crop.getHeight() * 4, true);
            crop.recycle();
            return recognizer.process(InputImage.fromBitmap(enlarged, 0)).continueWith(task -> {
                int number = 0;
                if (task.isSuccessful() && task.getResult() != null) {
                    Matcher matcher = Pattern.compile("(?<!\\d)([1-9])(?!\\d)")
                            .matcher(task.getResult().getText());
                    if (matcher.find()) number = Integer.parseInt(matcher.group(1));
                }
                enlarged.recycle();
                return number;
            });
        } catch (Throwable ignored) {
            return Tasks.forResult(0);
        }
    }

    private void handleScratch(FrameState frame, TargetAnnouncement announcement) {
        if (frame.cueBall == null) {
            setStatus("Scratch / ball-in-hand detected. Waiting for the cue ball to become visible.");
            return;
        }
        if (announcement.legalBall == 0) {
            setStatus("Scratch detected. Waiting for the next legal-ball announcement before repositioning.");
            return;
        }
        ShotPlan current = planner.plan(frame, announcement.legalBall, announcement.objectiveBall);
        if (current != null) {
            setStatus("Scratch detected, but a clear potting route already exists. Keeping the cue ball in place.");
            return;
        }
        Vec2 placement = planner.bestCuePlacement(frame,
                announcement.legalBall, announcement.objectiveBall);
        if (placement == null
                || placement.distance(frame.cueBall.center) < frame.cueBall.radius * 1.5) {
            setStatus("Scratch detected. No safe cue-ball placement found; waiting.");
            return;
        }
        dispatchDrag(frame.cueBall.center, placement, 420, success -> {
            setStatus(success ? "Cue-ball placement gesture sent. Rechecking the table."
                    : "Cue-ball placement gesture failed.");
        });
    }

    private void executePlan(FrameState frame, ShotPlan plan, TargetAnnouncement announcement) {
        if (frame.cueBall == null || frame.powerBar == null) {
            setStatus("Route found; waiting for cue ball and power-bar detection.");
            return;
        }
        String targetKey = plan.breakShot ? "break" : String.valueOf(plan.target.number);
        String pocketKey = plan.pocket == null ? "rack"
                : Math.round(plan.pocket.center.x / 12) + ":"
                    + Math.round(plan.pocket.center.y / 12);
        String signature = targetKey + ":" + Math.round(frame.cueBall.center.x / 12) + ":"
                + Math.round(frame.cueBall.center.y / 12) + ":"
                + Math.round(plan.target.center.x / 12) + ":"
                + Math.round(plan.target.center.y / 12) + ":" + pocketKey;
        long now = System.currentTimeMillis();
        if (signature.equals(lastShotSignature) || now - lastShotAt < 2400) return;

        String route = plan.breakShot ? "Diamond-rack break"
                : (plan.comboShot ? "9-ball combo route"
                : (plan.bankShot ? "Bank route" : "Direct route"));
        setStatus(String.format(Locale.US,
                "%s calculated. Power %.0f%%. Sending cue alignment.",
                route, plan.powerPercent));
        dispatchAim(frame.cueBall.center, plan.aimPoint, success -> {
            if (!success || !isEngineEnabled()) {
                setStatus("Aim input was not accepted; staying in monitor mode.");
                return;
            }
            handler.postDelayed(() -> {
                if (!isEngineEnabled()) return;
                Vec2 powerStart = frame.powerBar.startPoint();
                Vec2 powerTarget = frame.powerBar.pointForPower(plan.powerPercent);
                dispatchDrag(powerStart, powerTarget, 320, fired -> {
                    if (fired) {
                        lastShotSignature = signature;
                        lastShotAt = System.currentTimeMillis();
                        setStatus("Shot input sent. Continuous table monitoring remains active.");
                    } else {
                        setStatus("Power input failed. Monitoring without repeating the same shot.");
                    }
                });
            }, 260);
        });
    }

    private void dispatchAim(Vec2 from, Vec2 to, GestureResult callback) {
        if (from.distance(to) < 70) dispatchTap(to, callback);
        else dispatchDrag(from, to, 360, callback);
    }

    private interface GestureResult { void complete(boolean success); }

    private void dispatchTap(Vec2 point, GestureResult callback) {
        Path path = new Path();
        path.moveTo((float) point.x, (float) point.y);
        path.lineTo((float) point.x + 0.5f, (float) point.y + 0.5f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 90)).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                callback.complete(true);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                callback.complete(false);
            }
        }, handler);
        if (!accepted) callback.complete(false);
    }

    private void dispatchDrag(Vec2 from, Vec2 to, long durationMs, GestureResult callback) {
        Path path = new Path();
        path.moveTo((float) from.x, (float) from.y);
        path.lineTo((float) to.x, (float) to.y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                callback.complete(true);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                callback.complete(false);
            }
        }, handler);
        if (!accepted) callback.complete(false);
    }

    private String buildStatus(FrameState frame, TargetAnnouncement announcement) {
        if (!isEngineEnabled()) return "Engine is stopped. Tap START ENGINE when ready.";
        if (!frame.hasUsableTable()) return "Monitoring screen. Table geometry not confidently detected yet.";
        int objectBalls = 0;
        for (Ball ball : frame.balls) if (!ball.cue) objectBalls++;

        if (!announcement.hasTarget()) {
            DiamondRackDetector.Result rack = rackDetector.detect(frame.balls, frame.table);
            if (rack.detected) {
                return "Table and six pockets detected · 9-ball diamond rack verified · "
                        + String.format(Locale.US, "%.0f%% rack fit", rack.confidence * 100.0);
            }
            LowestBallSelector.Selection selection = lowestBallSelector.select(frame.balls, 0);
            if (selection.isConfident()) {
                return "Table detected · lowest ball #" + selection.number
                        + " selected from color · " + objectBalls + " object-ball candidates.";
            }
        }

        return "Table detected; target: Hit " + announcement.legalBall
                + " · pocket objective: "
                + (announcement.objectiveBall == 0 ? "not read" : announcement.objectiveBall)
                + " · ball candidates: " + objectBalls
                + " · pocket vision: " + frame.refinedPocketCount + "/6"
                + (frame.ballRadiusRefined ? " · ball scale refined" : " · ball scale uses reference")
                + (frame.powerBar == null ? " · power bar not found"
                    : " · " + frame.powerBar.orientation.name().toLowerCase(Locale.ROOT)
                    + " power bar found");
    }

    private void setStatus(String message) {
        getSharedPreferences("chernika", MODE_PRIVATE)
                .edit().putString("last_status", message).apply();
    }
}
