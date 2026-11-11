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
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView status;
    private TextView serviceState;
    private final android.os.Handler handler = new android.os.Handler();

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(prefs.getString("last_status", "Waiting for game screen."));
            if (serviceState != null) {
                boolean enabled = isAccessibilityEnabled();
                serviceState.setText(enabled ? "Accessibility: enabled" : "Accessibility: not enabled");
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("chernika", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 32, 28, 28);
        root.setBackgroundColor(0xFF090711);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("CHERNIKA ENGINE");
        title.setTextColor(0xFFF4EEFF);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.08f);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Raw autonomous 9-ball engine · on-device vision");
        subtitle.setTextColor(0xFFB9A8D9);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = 10;
        subLp.bottomMargin = 28;
        root.addView(subtitle, subLp);

        serviceState = new TextView(this);
        serviceState.setTextColor(0xFFFFFFFF);
        serviceState.setTextSize(15);
        root.addView(serviceState, new LinearLayout.LayoutParams(-1, -2));

        Button accessibility = button("Enable game-control accessibility");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(root, accessibility);

        Button start = button("START ENGINE");
        start.setOnClickListener(v -> {
            prefs.edit().putBoolean("engine_enabled", true).apply();
            if (!isAccessibilityEnabled()) {
                prefs.edit().putString("last_status",
                        "Enable Chernika Game Control in Accessibility settings, then return here.").apply();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else {
                prefs.edit().putString("last_status",
                        "Engine armed. Open Plato 9-ball in portrait orientation.").apply();
            }
        });
        addButton(root, start);

        Button stop = button("STOP ENGINE");
        stop.setOnClickListener(v -> {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putString("last_status", "Engine stopped by user.").apply();
        });
        addButton(root, stop);

        TextView permissions = new TextView(this);
        permissions.setText("The accessibility service reads the visible game screen and performs touch gestures only while the engine is enabled. It waits rather than shooting when the table, target, cue ball, or power control is uncertain.");
        permissions.setTextColor(0xFFB9B2C7);
        permissions.setTextSize(13);
        permissions.setLineSpacing(5, 1.0f);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
        pLp.topMargin = 26;
        root.addView(permissions, pLp);

        TextView current = new TextView(this);
        current.setText("ENGINE STATUS");
        current.setTextColor(0xFFB98CFF);
        current.setTextSize(12);
        current.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
        cLp.topMargin = 30;
        root.addView(current, cLp);

        ScrollView scroll = new ScrollView(this);
        status = new TextView(this);
        status.setTextColor(0xFFE4DDF0);
        status.setTextSize(14);
        status.setPadding(0, 8, 0, 12);
        scroll.addView(status);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = 6;
        root.addView(scroll, scrollLp);

        setContentView(root);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setAllCaps(false);
        return b;
    }

    private void addButton(LinearLayout root, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 10;
        root.addView(button, lp);
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
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
