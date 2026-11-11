package com.cherunika.chernika;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/** User-consented Android screen capture, kept alive by a visible foreground service. */
public final class MediaProjectionCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "screen_capture_result_code";
    public static final String EXTRA_RESULT_DATA = "screen_capture_result_data";

    private static final String CHANNEL_ID = "chernika_screen_capture";
    private static final int NOTIFICATION_ID = 7405;
    private static final Object FRAME_LOCK = new Object();

    private static volatile boolean running;
    private static Bitmap latestBitmap;
    private static long latestFrameTimestamp;
    private static int latestFrameSequence;

    private HandlerThread workerThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private boolean projectionStoppedExternally;
    private boolean cleaningUp;

    public static boolean isRunning() {
        return running;
    }

    /** Returns an independent bitmap copy; the caller owns and must recycle it. */
    public static FrameSnapshot getLatestFrame() {
        synchronized (FRAME_LOCK) {
            if (!running || latestBitmap == null || latestBitmap.isRecycled()) return null;
            Bitmap copy = latestBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) return null;
            return new FrameSnapshot(copy, latestFrameTimestamp, latestFrameSequence);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                throw new SecurityException("Android screen-capture approval is missing.");
            }

            workerThread = new HandlerThread("ChernikaScreenFrames");
            workerThread.start();
            captureHandler = new Handler(workerThread.getLooper());

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("MediaProjectionManager unavailable.");

            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new SecurityException("Android did not grant screen capture.");

            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() {
                    projectionStoppedExternally = true;
                    running = false;
                    getSharedPreferences("chernika", MODE_PRIVATE).edit()
                            .putBoolean("engine_enabled", false)
                            .putString("last_status",
                                    "Android screen capture was stopped. Tap ACTIVATE to grant it again.")
                            .apply();
                    stopSelf();
                }
            };
            projection.registerCallback(projectionCallback, captureHandler);

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) throw new IllegalStateException("WindowManager unavailable.");
            Display display = windowManager.getDefaultDisplay();
            display.getRealMetrics(metrics);

            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int densityDpi = metrics.densityDpi;
            if (width <= 0 || height <= 0 || densityDpi <= 0) {
                throw new IllegalStateException("Invalid display dimensions.");
            }

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

            virtualDisplay = projection.createVirtualDisplay(
                    "ChernikaScreenCapture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    captureHandler
            );
            if (virtualDisplay == null) {
                throw new IllegalStateException("Android could not create the capture display.");
            }

            running = true;
            setStatus("Screen capture approved · ready. Switch to Plato 9-ball.");
        } catch (Throwable error) {
            running = false;
            getSharedPreferences("chernika", MODE_PRIVATE).edit()
                    .putBoolean("engine_enabled", false)
                    .putString("last_status", "Screen capture failed: "
                            + error.getClass().getSimpleName()
                            + ". Tap ACTIVATE and approve screen capture again.")
                    .apply();
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        Bitmap padded = null;
        Bitmap frame = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride <= 0 || rowStride < width * pixelStride) return;

            int paddedWidth = rowStride / pixelStride;
            padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);

            if (paddedWidth == width) {
                frame = padded;
                padded = null;
            } else {
                frame = Bitmap.createBitmap(padded, 0, 0, width, height);
            }
            publishFrame(frame);
            frame = null;
        } catch (Throwable error) {
            setStatus("Screen frame conversion failed: " + error.getClass().getSimpleName());
        } finally {
            if (image != null) image.close();
            if (padded != null && !padded.isRecycled()) padded.recycle();
            if (frame != null && !frame.isRecycled()) frame.recycle();
        }
    }

    private static void publishFrame(Bitmap frame) {
        synchronized (FRAME_LOCK) {
            if (latestBitmap != null && !latestBitmap.isRecycled()) latestBitmap.recycle();
            latestBitmap = frame;
            latestFrameTimestamp = System.currentTimeMillis();
            latestFrameSequence++;
        }
    }

    private static void clearFrameStore() {
        synchronized (FRAME_LOCK) {
            if (latestBitmap != null && !latestBitmap.isRecycled()) latestBitmap.recycle();
            latestBitmap = null;
            latestFrameTimestamp = 0L;
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 7405, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Chernika screen capture active")
                .setContentText("Screen analysis is running for Plato. Tap STOP in Chernika to end.")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Chernika screen capture", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Visible notification while Chernika reads the game screen.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void setStatus(String message) {
        getSharedPreferences("chernika", MODE_PRIVATE).edit()
                .putString("last_status", message).apply();
    }

    @Override public void onDestroy() {
        cleanup();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    private void cleanup() {
        if (cleaningUp) return;
        cleaningUp = true;
        running = false;

        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            MediaProjection oldProjection = projection;
            projection = null;
            if (projectionCallback != null) {
                try { oldProjection.unregisterCallback(projectionCallback); }
                catch (Throwable ignored) { }
            }
            if (!projectionStoppedExternally) {
                try { oldProjection.stop(); }
                catch (Throwable ignored) { }
            }
            projectionCallback = null;
        }
        clearFrameStore();
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            captureHandler = null;
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    public static final class FrameSnapshot {
        public final Bitmap bitmap;
        public final long timestamp;
        public final int sequence;

        FrameSnapshot(Bitmap bitmap, long timestamp, int sequence) {
            this.bitmap = bitmap;
            this.timestamp = timestamp;
            this.sequence = sequence;
        }
    }
}
