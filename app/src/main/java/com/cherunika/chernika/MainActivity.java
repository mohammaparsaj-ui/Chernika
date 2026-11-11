package com.cherunika.chernika;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Minimal one-control launcher. Screen capture requires explicit Android consent. */
public class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 5401;

    private SharedPreferences prefs;
    private Button toggle;
    private TextView status;
    private final android.os.Handler handler = new android.os.Handler();

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshUi();
            handler.postDelayed(this, 800);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("chernika", MODE_PRIVATE);

        // Migrate the previous build's pending activation to the new two-step flow.
        if (prefs.getBoolean("pending_activation", false)) {
            prefs.edit().putBoolean("pending_activation", false)
                    .putBoolean("pending_accessibility", true).apply();
        }
        // A previous APK could leave the engine flag on without a MediaProjection token.
        if (prefs.getBoolean("engine_enabled", false)
                && !MediaProjectionCaptureService.isRunning()) {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putString("last_status",
                            "Update requires a fresh Android screen-capture approval. Tap ACTIVATE.")
                    .apply();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 28);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF090909);

        TextView title = new TextView(this);
        title.setText("CHERNIKA");
        title.setTextColor(0xFFF4F4F4);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(0xFFCCCCCC);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(4, 1.0f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = 20;
        statusLp.bottomMargin = 28;
        root.addView(status, statusLp);

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextSize(16);
        toggle.setOnClickListener(v -> onTogglePressed());
        root.addView(toggle, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("First enable Chernika Game Control in Accessibility settings. "
                + "Then approve Android's screen-capture prompt and choose Entire screen.");
        note.setTextColor(0xFF888888);
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = 22;
        root.addView(note, noteLp);

        setContentView(root);
        refreshUi();
    }

    private void onTogglePressed() {
        boolean enabled = prefs.getBoolean("engine_enabled", false);
        if (enabled) {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putBoolean("pending_accessibility", false)
                    .putString("last_status",
                            "Stopped by user. Screen capture and game gestures are disabled.")
                    .apply();
            stopService(new Intent(this, MediaProjectionCaptureService.class));
            refreshUi();
            return;
        }

        if (!isAccessibilityEnabled()) {
            prefs.edit().putBoolean("pending_accessibility", true)
                    .putString("last_status",
                            "Enable Chernika Game Control in Accessibility settings, then return here.")
                    .apply();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            refreshUi();
            return;
        }

        requestScreenCapturePermission();
    }

    private void requestScreenCapturePermission() {
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                setStatus("Android screen capture is unavailable on this device.");
                return;
            }
            prefs.edit().putString("last_status",
                    "Approve the Android screen-capture prompt. Choose Entire screen.")
                    .apply();
            startActivityForResult(manager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION);
        } catch (Throwable error) {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putString("last_status", "Could not request screen capture: "
                            + error.getClass().getSimpleName()).apply();
            refreshUi();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MEDIA_PROJECTION) return;

        if (resultCode != RESULT_OK || data == null) {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putString("last_status",
                            "Screen capture was not approved. Tap ACTIVATE and allow it to continue.")
                    .apply();
            refreshUi();
            return;
        }

        Intent captureService = new Intent(this, MediaProjectionCaptureService.class);
        captureService.putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, resultCode);
        captureService.putExtra(MediaProjectionCaptureService.EXTRA_RESULT_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(captureService);
            } else {
                startService(captureService);
            }
            prefs.edit().putBoolean("pending_accessibility", false)
                    .putBoolean("engine_enabled", true)
                    .putString("last_status",
                            "Screen capture approved · starting engine. Open Plato 9-ball.")
                    .apply();
        } catch (Throwable error) {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putString("last_status", "Could not start screen capture: "
                            + error.getClass().getSimpleName()).apply();
        }
        refreshUi();
    }

    private void refreshUi() {
        if (toggle == null || prefs == null) return;
        boolean enabled = prefs.getBoolean("engine_enabled", false);
        toggle.setText(enabled ? "STOP" : "ACTIVATE");
        String message = prefs.getString("last_status", "Ready.");
        if (!isAccessibilityEnabled() && enabled) {
            message = "Accessibility permission is off. Tap STOP, then ACTIVATE to enable it again.";
        }
        status.setText(message);
    }

    private void setStatus(String message) {
        prefs.edit().putString("last_status", message).apply();
        refreshUi();
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String expected = new ComponentName(this,
                ChernikaAccessibilityService.class).flattenToString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String item : splitter) {
            if (expected.equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && prefs.getBoolean("pending_accessibility", false)) {
            prefs.edit().putBoolean("pending_accessibility", false).apply();
            if (isAccessibilityEnabled()) {
                requestScreenCapturePermission();
            } else {
                prefs.edit().putString("last_status",
                        "Accessibility service is still disabled. Tap ACTIVATE to try again.")
                        .apply();
            }
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
