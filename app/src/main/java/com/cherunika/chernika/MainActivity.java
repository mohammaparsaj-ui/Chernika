package com.cherunika.chernika;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 7, 17);
    private static final int PANEL = Color.rgb(25, 18, 39);
    private static final int PANEL_LIGHT = Color.rgb(39, 27, 60);
    private static final int LAVENDER = Color.rgb(205, 178, 255);
    private static final int WHITE = Color.rgb(247, 242, 255);
    private static final int MUTED = Color.rgb(177, 163, 198);
    private static final int VIOLET = Color.rgb(139, 75, 224);
    private static final int GREEN = Color.rgb(105, 226, 166);
    private static final int AMBER = Color.rgb(255, 203, 119);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView engineState;
    private TextView accessibilityState;
    private TextView liveStatus;
    private TextView primaryAction;
    private boolean settingsScreen;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("chernika", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        renderHome();
    }

    private void renderHome() {
        settingsScreen = false;
        LinearLayout page = createPage();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.chernika_icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(round(PANEL_LIGHT, 0xFF7250A7, 24));
        logo.setClipToOutline(true);
        header.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(12), 0, 0, 0);
        TextView brandName = text("CHERNIKA", 21, WHITE, true);
        brandName.setLetterSpacing(0.09f);
        brand.addView(brandName);
        TextView brandSub = text("9-BALL ASSISTANT", 10, MUTED, true);
        brandSub.setLetterSpacing(0.12f);
        brand.addView(brandSub);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView settings = text("⚙", 25, LAVENDER, true);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(round(PANEL_LIGHT, 0xFF604381, 18));
        settings.setOnClickListener(v -> renderSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);

        addGap(page, 20);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(12), dp(18));
        hero.setBackground(gradient(new int[]{0xFF382253, 0xFF1B1329, 0xFF15101F}, 0xFF7952B0, 24));

        LinearLayout heroCopy = new LinearLayout(this);
        heroCopy.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("WELCOME BACK", 10, LAVENDER, true);
        eyebrow.setLetterSpacing(0.16f);
        heroCopy.addView(eyebrow);
        addGap(heroCopy, 8);
        heroCopy.addView(text("Your table.\nYour strategy.", 24, WHITE, true));
        addGap(heroCopy, 8);
        heroCopy.addView(text("A focused companion for Plato 9-ball.", 12, MUTED, false));
        addGap(heroCopy, 12);
        TextView mode = text("  PLATO  •  9-BALL  ", 10, WHITE, true);
        mode.setPadding(dp(10), dp(7), dp(10), dp(7));
        mode.setBackground(round(0xFF56347D, 0, 30));
        heroCopy.addView(mode);
        hero.addView(heroCopy, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView character = new ImageView(this);
        character.setImageResource(R.drawable.chernika_icon);
        character.setScaleType(ImageView.ScaleType.CENTER_CROP);
        character.setBackground(round(0xFF211535, 0xFF8C62CB, 20));
        character.setClipToOutline(true);
        LinearLayout.LayoutParams characterLp = new LinearLayout.LayoutParams(dp(126), dp(154));
        characterLp.leftMargin = dp(8);
        hero.addView(character, characterLp);
        page.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        addGap(page, 16);

        primaryAction = actionButton("START ENGINE", true);
        primaryAction.setOnClickListener(v -> toggleEngine());
        page.addView(primaryAction, new LinearLayout.LayoutParams(-1, dp(58)));

        addGap(page, 12);

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        TextView permission = actionButton("GAME CONTROL SETUP", false);
        permission.setTextSize(12);
        permission.setOnClickListener(v -> openAccessibilitySettings());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1f);
        quickActions.addView(permission, half);

        TextView settingsShortcut = actionButton("SETTINGS", false);
        settingsShortcut.setTextSize(12);
        settingsShortcut.setOnClickListener(v -> renderSettings());
        LinearLayout.LayoutParams secondHalf = new LinearLayout.LayoutParams(0, dp(48), 1f);
        secondHalf.leftMargin = dp(10);
        quickActions.addView(settingsShortcut, secondHalf);
        page.addView(quickActions);

        addGap(page, 18);

        TextView section = text("ENGINE OVERVIEW", 11, LAVENDER, true);
        section.setLetterSpacing(0.14f);
        page.addView(section);
        addGap(page, 9);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.addView(statusTile("ENGINE", true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        View spacer = new View(this);
        statusRow.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));
        statusRow.addView(statusTile("GAME CONTROL", false),
                new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(statusRow);

        addGap(page, 12);

        LinearLayout statusCard = card();
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        statusCard.addView(text("LIVE ENGINE STATUS", 11, LAVENDER, true));
        addGap(statusCard, 8);
        liveStatus = text("Waiting for game screen.", 13, WHITE, false);
        liveStatus.setLineSpacing(dp(3), 1.0f);
        statusCard.addView(liveStatus);
        page.addView(statusCard, new LinearLayout.LayoutParams(-1, -2));

        addGap(page, 12);

        LinearLayout guide = card();
        guide.setOrientation(LinearLayout.VERTICAL);
        guide.setPadding(dp(16), dp(15), dp(16), dp(15));
        guide.addView(text("GET STARTED", 11, LAVENDER, true));
        addGap(guide, 9);
        guide.addView(text("1   Enable Chernika Game Control in Android Accessibility settings.", 12, WHITE, false));
        addGap(guide, 7);
        guide.addView(text("2   Return here and tap START ENGINE.", 12, WHITE, false));
        addGap(guide, 7);
        guide.addView(text("3   Open Plato 9-ball and keep the full table visible in portrait orientation.", 12, WHITE, false));
        addGap(guide, 10);
        guide.addView(text("Chernika monitors the visible game only while enabled. If the table, target, cue ball or power control is uncertain, it waits instead of shooting.", 11, MUTED, false));
        page.addView(guide, new LinearLayout.LayoutParams(-1, -2));

        addGap(page, 18);
        TextView footer = text("CHERNIKA  •  ON-DEVICE VISION  •  9-BALL", 9, 0xFF827493, true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.08f);
        page.addView(footer);

        refreshStatus();
    }

    private void renderSettings() {
        settingsScreen = true;
        LinearLayout page = createPage();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 34, LAVENDER, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> renderHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView title = text("Settings", 24, WHITE, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = dp(8);
        header.addView(title, titleLp);
        page.addView(header);

        addGap(page, 20);

        LinearLayout accessCard = card();
        accessCard.setOrientation(LinearLayout.VERTICAL);
        accessCard.setPadding(dp(17), dp(17), dp(17), dp(17));
        accessCard.addView(text("GAME CONTROL PERMISSION", 11, LAVENDER, true));
        addGap(accessCard, 9);
        accessCard.addView(text("Chernika uses Android Accessibility to read the visible Plato screen and perform calibrated touch gestures when the engine is enabled.", 13, WHITE, false));
        addGap(accessCard, 12);
        accessibilityState = text("", 12, MUTED, true);
        accessCard.addView(accessibilityState);
        addGap(accessCard, 12);
        TextView openAccess = actionButton("OPEN ACCESSIBILITY SETTINGS", false);
        openAccess.setTextSize(12);
        openAccess.setOnClickListener(v -> openAccessibilitySettings());
        accessCard.addView(openAccess, new LinearLayout.LayoutParams(-1, dp(48)));
        page.addView(accessCard, new LinearLayout.LayoutParams(-1, -2));

        addGap(page, 12);

        LinearLayout operationCard = card();
        operationCard.setOrientation(LinearLayout.VERTICAL);
        operationCard.setPadding(dp(17), dp(17), dp(17), dp(17));
        operationCard.addView(text("ENGINE OPERATION", 11, LAVENDER, true));
        addGap(operationCard, 9);
        operationCard.addView(text("The engine captures the screen, checks the table and target, plans a shot, then sends aim and power gestures. It continues monitoring after a shot and waits whenever confidence is insufficient.", 13, WHITE, false));
        addGap(operationCard, 12);
        TextView stop = actionButton("STOP ENGINE", false);
        stop.setOnClickListener(v -> {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putString("last_status", "Engine stopped by user.").apply();
            refreshStatus();
        });
        operationCard.addView(stop, new LinearLayout.LayoutParams(-1, dp(48)));
        page.addView(operationCard, new LinearLayout.LayoutParams(-1, -2));

        addGap(page, 12);

        LinearLayout privacyCard = card();
        privacyCard.setOrientation(LinearLayout.VERTICAL);
        privacyCard.setPadding(dp(17), dp(17), dp(17), dp(17));
        privacyCard.addView(text("PRIVACY & PROCESSING", 11, LAVENDER, true));
        addGap(privacyCard, 9);
        privacyCard.addView(text("Text recognition runs on-device. Screen capture and touch gestures are handled by the Android accessibility service while the engine is enabled.", 13, WHITE, false));
        page.addView(privacyCard, new LinearLayout.LayoutParams(-1, -2));

        addGap(page, 18);
        TextView footer = text("CHERNIKA ENGINE  •  9-BALL", 9, 0xFF827493, true);
        footer.setGravity(Gravity.CENTER);
        page.addView(footer);
        refreshStatus();
    }

    private LinearLayout createPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF100B1A, BG, 0xFF130D20}));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(26));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        return page;
    }

    private LinearLayout statusTile(String label, boolean engine) {
        LinearLayout tile = card();
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(13), dp(14), dp(13));
        tile.addView(text(label, 9, MUTED, true));
        addGap(tile, 7);
        TextView value = text("", 13, WHITE, true);
        if (engine) engineState = value;
        else accessibilityState = value;
        tile.addView(value);
        return tile;
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setBackground(round(PANEL, 0xFF493465, 18));
        return view;
    }

    private TextView actionButton(String label, boolean primary) {
        TextView button = text(label, 14, WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setLetterSpacing(0.05f);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(primary
                ? gradient(new int[]{0xFF9B61F2, 0xFF6B36B3, 0xFF49236F}, 0xFFBD9AFF, 18)
                : gradient(new int[]{0xFF38264F, 0xFF251A35}, 0xFF62458A, 16));
        button.setElevation(dp(primary ? 5 : 1));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable gradient(int[] colors, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void addGap(LinearLayout parent, int heightDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toggleEngine() {
        boolean enabled = prefs.getBoolean("engine_enabled", false);
        if (enabled) {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putString("last_status", "Engine stopped by user.").apply();
        } else if (!isAccessibilityEnabled()) {
            prefs.edit().putBoolean("engine_enabled", false)
                    .putString("last_status",
                            "Enable Chernika Game Control in Accessibility settings, then return and tap START ENGINE.")
                    .apply();
            openAccessibilitySettings();
        } else {
            prefs.edit().putBoolean("engine_enabled", true)
                    .putString("last_status",
                            "Engine armed. Open Plato 9-ball in portrait orientation.")
                    .apply();
        }
        refreshStatus();
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
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

    private void refreshStatus() {
        if (prefs == null) return;
        boolean engineEnabled = prefs.getBoolean("engine_enabled", false);
        boolean accessibilityEnabled = isAccessibilityEnabled();

        if (engineState != null) {
            if (engineEnabled && accessibilityEnabled) {
                engineState.setText("ARMED");
                engineState.setTextColor(GREEN);
            } else if (engineEnabled) {
                engineState.setText("BLOCKED");
                engineState.setTextColor(AMBER);
            } else {
                engineState.setText("STOPPED");
                engineState.setTextColor(MUTED);
            }
        }

        if (accessibilityState != null) {
            accessibilityState.setText(accessibilityEnabled ? "ENABLED" : "NEEDS SETUP");
            accessibilityState.setTextColor(accessibilityEnabled ? GREEN : AMBER);
        }

        if (liveStatus != null) {
            liveStatus.setText(prefs.getString("last_status", "Waiting for game screen."));
        }

        if (primaryAction != null) {
            primaryAction.setText(engineEnabled ? "STOP ENGINE" : "START ENGINE");
        }
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
