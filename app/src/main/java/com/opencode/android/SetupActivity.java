package com.opencode.android;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * OpenCode: Clean 3-permission Setup with unified OpenCode dark aesthetics.
 * All UI copy comes from Android string resources (English default, localized per device locale).
 */
public class SetupActivity extends Activity {

    private static final int REQ_STORAGE = 101;
    private static final String PREF_SETUP_DONE = "setup_done";

    static boolean needed(Context c) {
        if (!filesOk(c) || !unknownOk(c)) return true;
        return !c.getSharedPreferences("opencode_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_SETUP_DONE, false);
    }

    static boolean unknownOk(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean filesOk(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && c != null) {
            return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    static boolean batteryOk(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception ignored) {
            return true;
        }
    }

    private void openUnknownSources() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())));
        } catch (Exception e1) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
            } catch (Exception e2) {
                openAppDetails();
            }
        }
    }

    private void openAppDetails() {
        Intent[] candidates = new Intent[]{
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())),
            new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent it : candidates) {
            try {
                startActivity(it);
                return;
            } catch (Exception ignored) {}
        }
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_STORAGE);
            }
            return;
        }
        try {
            startActivity(new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (Exception e1) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                openAppDetails();
            }
        }
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) {}
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return;
            } catch (Exception ignored) {}
        }
        openAppDetails();
    }

    private static GradientDrawable roundedBox(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, stroke);
        return d;
    }

    private Button filesBtn;
    private TextView filesText;
    private LinearLayout filesCard;
    private Button installBtn;
    private TextView installText;
    private LinearLayout installCard;
    private Button batteryBtn;
    private TextView batteryText;
    private LinearLayout batteryCard;
    private TextView status;
    private Button continueBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean alive;
    private boolean launched;
    private boolean serverKicked;

    private void startServiceCompat(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        // Pre-copy only the server binary + lightweight wrappers while the user is in Setup
        startServiceCompat(new Intent(this, OpenCodeService.class).setAction(OpenCodeService.ACTION_PREWARM));

        float d = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int) (22 * d);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFAFAFA);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.setup_subtitle, BuildConfig.VERSION_NAME));
        sub.setTextColor(0xFF9CA3AF);
        sub.setTextSize(14);
        sub.setPadding(0, (int) (6 * d), 0, (int) (20 * d));
        root.addView(sub);

        String allowLabel = getString(R.string.setup_allow);

        filesBtn = mkActionButton(allowLabel, d);
        filesBtn.setOnClickListener(v -> openAllFilesSettings());
        filesText = new TextView(this);
        filesCard = makeCardRow(filesBtn, filesText, d);
        root.addView(filesCard);

        installBtn = mkActionButton(allowLabel, d);
        installBtn.setOnClickListener(v -> openUnknownSources());
        installText = new TextView(this);
        installCard = makeCardRow(installBtn, installText, d);
        root.addView(installCard);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            batteryBtn = mkActionButton(allowLabel, d);
            batteryBtn.setOnClickListener(v -> openBatterySettings());
            batteryText = new TextView(this);
            batteryCard = makeCardRow(batteryBtn, batteryText, d);
            root.addView(batteryCard);
        }

        status = new TextView(this);
        status.setTextColor(0xFF71717A);
        status.setTextSize(13);
        status.setPadding(0, (int) (14 * d), 0, (int) (14 * d));
        root.addView(status);

        continueBtn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        continueBtn.setAllCaps(false);
        continueBtn.setTextSize(15);
        continueBtn.setTypeface(null, Typeface.BOLD);
        continueBtn.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        continueBtn.setOnClickListener(v -> finishSetup());
        root.addView(continueBtn, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(root);
        setContentView(scroll);
    }

    private void finishSetup() {
        if (launched || !filesOk(this) || !unknownOk(this)) return;
        launched = true;
        alive = false;
        handler.removeCallbacksAndMessages(null);
        getSharedPreferences("opencode_prefs", MODE_PRIVATE)
            .edit().putBoolean(PREF_SETUP_DONE, true).apply();
        startServiceCompat(new Intent(this, OpenCodeService.class));
        finish();
    }

    private LinearLayout makeCardRow(Button btn, TextView label, float d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (14 * d);
        int vPad = (int) (13 * d);
        card.setPadding(hPad, vPad, hPad, vPad);
        card.setBackground(roundedBox(0xFF111114, 0xFF242429, Math.max(1, (int) (1 * d)), 12 * d));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = (int) (10 * d);
        card.setLayoutParams(cardLp);

        label.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(0, 0, (int) (12 * d), 0);
        card.addView(label, lp);
        card.addView(btn);
        return card;
    }

    /** Unified OpenCode monochrome button style across every permission row. */
    private Button mkActionButton(String s, float d) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFFFAFAFA);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextSize(13);
        b.setBackground(roundedBox(0xFF222227, 0xFF34343B, Math.max(1, (int) (1 * d)), 8 * d));
        b.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
        b.setMinWidth((int) (88 * d));
        b.setMinimumWidth((int) (88 * d));
        b.setMinHeight((int) (36 * d));
        b.setMinimumHeight((int) (36 * d));
        return b;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && !filesOk(this)
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            openAppDetails();
        }
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        updateUi();
    }

    @Override
    protected void onPause() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void styleRow(LinearLayout card, Button btn, TextView tv, boolean granted, int grantedRes, int pendingRes, float d) {
        if (btn != null) btn.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (tv != null) {
            tv.setText(granted ? "✓  " + getString(grantedRes) : getString(pendingRes));
            tv.setTextColor(granted ? 0xFFA1A1AA : 0xFFFAFAFA);
            tv.setTypeface(null, granted ? Typeface.NORMAL : Typeface.BOLD);
        }
        if (card != null) {
            card.setBackground(roundedBox(
                granted ? 0xFF0C0C0E : 0xFF111114,
                granted ? 0xFF1D1D22 : 0xFF27272D,
                Math.max(1, (int) (1 * d)), 12 * d));
        }
    }

    private void updateUi() {
        if (!alive || launched) return;
        float d = getResources().getDisplayMetrics().density;
        boolean files = filesOk(this);
        boolean unknown = unknownOk(this);
        boolean battery = batteryOk(this);

        // As soon as storage is granted, boot :4096 in background so entering OpenCode is instant
        if (files && !serverKicked) {
            serverKicked = true;
            startServiceCompat(new Intent(this, OpenCodeService.class));
        }

        styleRow(filesCard, filesBtn, filesText, files,
            R.string.setup_files_granted, R.string.setup_files_pending, d);
        styleRow(installCard, installBtn, installText, unknown,
            R.string.setup_install_granted, R.string.setup_install_pending, d);
        if (batteryBtn != null && batteryText != null) {
            styleRow(batteryCard, batteryBtn, batteryText, battery,
                R.string.setup_battery_granted, R.string.setup_battery_pending, d);
        }

        boolean reqOk = files && unknown;
        if (status != null) {
            if (reqOk && battery) {
                status.setTextColor(0xFFFAFAFA);
                status.setText(R.string.setup_status_all_ready);
            } else {
                String ok = getString(R.string.setup_state_ok);
                String pending = getString(R.string.setup_state_pending);
                String optional = getString(R.string.setup_state_optional);
                status.setTextColor(0xFF71717A);
                status.setText(getString(R.string.setup_status_summary,
                    files ? ok : pending, unknown ? ok : pending, battery ? ok : optional));
            }
        }

        if (continueBtn != null) {
            continueBtn.setEnabled(reqOk);
            if (reqOk) {
                continueBtn.setText(R.string.setup_btn_enter);
                continueBtn.setBackground(roundedBox(0xFFFAFAFA, 0xFFFAFAFA, 0, 10 * d));
                continueBtn.setTextColor(0xFF09090B);
            } else {
                continueBtn.setText(R.string.setup_btn_grant_required);
                continueBtn.setBackground(roundedBox(0xFF141417, 0xFF242429, Math.max(1, (int) (1 * d)), 10 * d));
                continueBtn.setTextColor(0xFF71717A);
            }
        }

        if (reqOk && battery) {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(this::finishSetup, 180);
            return;
        }

        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::updateUi, 650);
    }
}
