package com.cherunika.chernika;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Minimal one-control launcher. No character art, dashboard or game overlay. */
public class MainActivity extends Activity {
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

    @Override protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("chernika", MODE_PRIVATE);

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
        note.setText("First activation opens Android Accessibility settings. Enable Chernika Game Control and return here.");
        note.setTextColor(0xFF888888);
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = 22;
        root.addView(note, noteLp);

        setContentView(root);
    }

    private void onTogglePressed() {
        boolean enabled = prefs.getBoolean("engine_enabled", false);
        if (enabled) {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putBoolean("pending_activation", false)
                    .putString("last_status", "Stopped by user. No further game gestures will be sent.")
                    .apply();
            refreshUi();
            return;
        }

        if (!isAccessibilityEnabled()) {
            prefs.edit().putBoolean("pending_activation", true)
                    .putString("last_status", "Enable Chernika Game Control in Accessibility settings, then return here.")
                    .apply();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            refreshUi();
            return;
        }

        prefs.edit().putBoolean("engine_enabled", true)
                .putBoolean("pending_activation", false)
                .putString("last_status", "Activated · waiting for Plato 9-ball.")
                .apply();
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
        if (prefs != null && prefs.getBoolean("pending_activation", false)
                && isAccessibilityEnabled()) {
            prefs.edit().putBoolean("pending_activation", false)
                    .putBoolean("engine_enabled", true)
                    .putString("last_status", "Activated · waiting for Plato 9-ball.")
                    .apply();
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
