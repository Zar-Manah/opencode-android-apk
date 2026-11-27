package com.opencode.android;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** OpenCode: Guided Wireless Debugging setup checking Wi-Fi, notifications & Developer Options first. */
public class WirelessPairActivity extends Activity {

    private static final int REQ_NOTIF = 301;
    private TextView statusView;
    private TextView guideView;
    private Button mainBtn;
    private TextView manualToggle;
    private LinearLayout manualBox;
    private EditText codeInput;
    private EditText portInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean alive;
    private boolean autoConnecting = false;
    private boolean needWifiAction = false;

    private static boolean isEs() {
        return "es".equalsIgnoreCase(Locale.getDefault().getLanguage());
    }

    private boolean isDevOptionsEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean isAdbWifiSwitchOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        float d = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * d);
        root.setPadding(pad, pad, pad, pad);

        boolean es = isEs();

        TextView title = new TextView(this);
        title.setText(es ? "Control del dispositivo" : "Device Control");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(0xFF9CA3AF);
        statusView.setTextSize(14);
        statusView.setPadding(0, (int) (10 * d), 0, (int) (14 * d));
        root.addView(statusView);

        guideView = new TextView(this);
        android.graphics.drawable.GradientDrawable gBg = new android.graphics.drawable.GradientDrawable();
        gBg.setColor(0xFF111114);
        gBg.setStroke(Math.max(1, (int) (1 * d)), 0xFF242429);
        gBg.setCornerRadius(12 * d);
        guideView.setBackground(gBg);
        guideView.setTextColor(0xFFE5E7EB);
        guideView.setTextSize(14);
        int gPad = (int) (16 * d);
        guideView.setPadding(gPad, gPad, gPad, gPad);
        root.addView(guideView);

        mainBtn = makeButton(es ? "Abrir Depuración inalámbrica →" : "Open Wireless Debugging →", 0xFFFAFAFA, 0xFF09090B);
        mainBtn.setOnClickListener(v -> onMainAction());
        root.addView(mainBtn);

        Button backBtn = makeButton(es ? "Volver" : "Back", 0xFF222227, 0xFFFAFAFA);
        backBtn.setOnClickListener(v -> finish());
        root.addView(backBtn);

        manualToggle = new TextView(this);
        manualToggle.setText(es ? "Escribir código manualmente ▾" : "Enter code manually ▾");
        manualToggle.setTextColor(0xFF6B7280);
        manualToggle.setTextSize(13);
        manualToggle.setPadding(0, (int) (22 * d), 0, (int) (8 * d));
        manualToggle.setOnClickListener(v -> {
            if (manualBox != null) {
                manualBox.setVisibility(manualBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        root.addView(manualToggle);

        manualBox = new LinearLayout(this);
        manualBox.setOrientation(LinearLayout.VERTICAL);
        manualBox.setVisibility(View.GONE);

        codeInput = makeInput(es ? "Código de 6 dígitos" : "6-digit code");
        manualBox.addView(codeInput);

        portInput = makeInput(es ? "Puerto (opcional, auto por defecto)" : "Port (optional, auto by default)");
        manualBox.addView(portInput);

        Button pairBtn = makeButton(es ? "Vincular con código" : "Pair with code", 0xFF1F2937, Color.WHITE);
        pairBtn.setOnClickListener(v -> doPair());
        manualBox.addView(pairBtn);

        root.addView(manualBox);

        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        refreshState();
    }

    @Override
    protected void onPause() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void onMainAction() {
        boolean es = isEs();
        if (LocalAdbEngine.hasActiveSerial()) {
            finish();
            return;
        }
        if (needWifiAction || !LocalAdbEngine.isWifiConnected(this)) {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) {
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                return;
            } catch (Exception ignored) {}
        }

        if (!isDevOptionsEnabled()) {
            statusView.setTextColor(0xFFFBBF24);
            statusView.setText(es
                ? "Activa Opciones de desarrollador tocando 7 veces en 'Número de compilación'."
                : "Enable Developer Options by tapping 'Build number' 7 times.");
            try {
                startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
            }
            return;
        }

        // Pre-warm local ADB daemon before leaving so pairing finishes instantaneously on notification reply
        new Thread(() -> LocalAdbEngine.warmServer(this), "OpenCode-WarmAdb").start();
        WirelessPairReceiver.showPairingNotification(this);
        try {
            Intent dev = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            dev.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
            startActivity(dev);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onMainAction();
        } else {
            refreshState();
        }
    }

    private void refreshState() {
        if (!alive) return;
        boolean es = isEs();
        boolean binReady = LocalAdbEngine.isBinaryReady(this);

        if (LocalAdbEngine.hasActiveSerial()) {
            applyConnectedUi(es);
            return;
        }

        if (binReady && !autoConnecting) {
            autoConnecting = true;
            statusView.setTextColor(0xFF9CA3AF);
            statusView.setText(es ? "Buscando conexión automática…" : "Checking automatic connection…");
            new Thread(() -> {
                LocalAdbEngine.isRootAvailable();
                LocalAdbEngine.ensureConnected(this);
                runOnUiThread(() -> {
                    autoConnecting = false;
                    if (!alive) return;
                    if (LocalAdbEngine.hasActiveSerial()) {
                        applyConnectedUi(es);
                    } else {
                        applyNeedPairUi(es, true);
                    }
                });
            }, "OpenCode-AutoCheck").start();
            return;
        }

        applyNeedPairUi(es, binReady);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::refreshState, 900);
    }

    private void applyConnectedUi(boolean es) {
        needWifiAction = false;
        statusView.setTextColor(0xFFFAFAFA);
        statusView.setText(es ? "✓ Control del dispositivo ACTIVO" : "✓ Device Control ACTIVE");
        guideView.setText(es
            ? "OpenCode ya puede hacer clics, gestos, escribir texto y leer pantallas automáticamente sin bloqueo de Play Protect."
            : "OpenCode can now perform taps, swipes, typing, and UI dumps cleanly.");
        mainBtn.setText(es ? "✓ Listo · Volver" : "✓ Ready · Back");
        styleButton(mainBtn, 0xFFFAFAFA, 0xFF09090B);
        manualToggle.setVisibility(View.GONE);
        manualBox.setVisibility(View.GONE);
    }

    private void applyNeedPairUi(boolean es, boolean ready) {
        boolean wifiOk = LocalAdbEngine.isWifiConnected(this);
        needWifiAction = !wifiOk;

        if (!ready) {
            statusView.setTextColor(0xFFFBBF24);
            statusView.setText(es
                ? "Preparando motor interno… (" + OpenCodeService.bootProgress + "%)"
                : "Preparing built-in engine… (" + OpenCodeService.bootProgress + "%)");
        } else if (!wifiOk) {
            statusView.setTextColor(0xFFFBBF24);
            statusView.setText(es
                ? "⚠️ Conecta el Wi-Fi primero (necesario para Depuración inalámbrica)"
                : "⚠️ Connect Wi-Fi first (required by Android Wireless Debugging)");
        } else {
            statusView.setTextColor(0xFF9CA3AF);
            statusView.setText(es
                ? (isAdbWifiSwitchOn() ? "✓ Wi-Fi y Depuración inalámbrica encendidos · falta vincular 1 vez"
                                       : "✓ Wi-Fi conectado · listo para activar y vincular")
                : "✓ Wi-Fi connected · ready for one-time pairing");
        }

        if (!wifiOk) {
            guideView.setText(es
                ? "Por qué salían las opciones en gris:\n"
                  + "Android bloquea en gris 'Emparejar con código' y el 'QR' si el Wi-Fi está apagado o sin red.\n\n"
                  + "1. Pulsa el botón de abajo y conéctate a una red Wi-Fi.\n"
                  + "2. Vuelve aquí en cuanto conecte."
                : "Why pairing appeared grayed out:\n"
                  + "Android disables Wireless Debugging pairing when Wi-Fi is off.\n\n"
                  + "1. Tap below to connect to Wi-Fi.\n"
                  + "2. Return here once connected.");
            mainBtn.setText(es ? "Conectar Wi-Fi →" : "Connect Wi-Fi →");
            styleButton(mainBtn, 0xFFFBBF24, 0xFF09090B);
        } else if (!isDevOptionsEnabled()) {
            guideView.setText(es
                ? "Paso previo:\n1. Pulsa el botón de abajo.\n2. Toca 7 veces en 'Número de compilación' (Info del software) para activar Opciones de desarrollador.\n3. Vuelve aquí."
                : "One-time step:\n1. Tap below.\n2. Tap 'Build number' 7 times in About Phone.\n3. Return here.");
            mainBtn.setText(es ? "Desbloquear Opciones de desarrollador →" : "Unlock Developer Options →");
            styleButton(mainBtn, 0xFFFAFAFA, 0xFF09090B);
        } else {
            guideView.setText(es
                ? "1. Pulsa el botón de abajo.\n"
                  + "2. En Opciones de desarrollador entra en 'Depuración inalámbrica' (activa el interruptor superior si está apagado).\n"
                  + "3. Pulsa 'Vincular dispositivo con código de emparejamiento'.\n"
                  + "4. Baja la barra de notificaciones, pulsa 'Poner código' en el aviso de OpenCode y escribe los 6 dígitos."
                : "1. Tap the button below.\n"
                  + "2. Enable & open 'Wireless debugging' (top toggle must be ON).\n"
                  + "3. Tap 'Pair device with pairing code'.\n"
                  + "4. Pull down notifications, tap 'Enter code' on OpenCode and type the 6 digits.");
            mainBtn.setText(es ? "Abrir Depuración inalámbrica →" : "Open Wireless Debugging →");
            styleButton(mainBtn, 0xFFFAFAFA, 0xFF09090B);
        }
    }

    private void doPair() {
        String code = codeInput.getText().toString().trim();
        String portStr = portInput.getText().toString().trim();
        int port = -1;
        try { if (!portStr.isEmpty()) port = Integer.parseInt(portStr); } catch (Exception ignored) {}
        final int p = port;
        statusView.setTextColor(0xFF9CA3AF);
        statusView.setText(isEs() ? "Conectando…" : "Connecting…");
        new Thread(() -> {
            String res = LocalAdbEngine.pair(this, code, p);
            boolean ok = res.startsWith("OK:");
            runOnUiThread(() -> {
                if (ok) {
                    applyConnectedUi(isEs());
                } else {
                    statusView.setTextColor(0xFFF87171);
                    statusView.setText(res);
                }
            });
        }).start();
    }

    private EditText makeInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(0xFF6B7280);
        et.setTextColor(Color.WHITE);
        et.setBackgroundColor(0xFF141414);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setPadding(32, 24, 32, 24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 14;
        et.setLayoutParams(lp);
        return et;
    }

    private void styleButton(Button b, int bgColor, int fgColor) {
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(10 * d);
        b.setBackground(bg);
        b.setTextColor(fgColor);
    }

    private Button makeButton(String label, int bgColor, int fgColor) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextSize(15);
        styleButton(b, bgColor, fgColor);
        b.setPadding(32, 26, 32, 26);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 16;
        b.setLayoutParams(lp);
        return b;
    }
}
