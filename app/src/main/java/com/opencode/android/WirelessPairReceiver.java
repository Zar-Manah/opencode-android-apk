package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.util.Locale;

/** OpenCode: Handles inline notification RemoteInput for 6-digit Wireless Debugging pairing. */
public class WirelessPairReceiver extends BroadcastReceiver {

    static final String ACTION_PAIR_REPLY = "com.opencode.android.ACTION_PAIR_REPLY";
    static final String KEY_PAIR_CODE = "pair_code";
    private static final int NOTIF_ID = 4;
    private static final String CHANNEL_ID = "opencode_pair";

    private static boolean isEs() {
        return "es".equalsIgnoreCase(Locale.getDefault().getLanguage());
    }

    static void showPairingNotification(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                isEs() ? "Emparejar Control Táctil" : "Wireless Control Pairing",
                NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }

        Intent replyIntent = new Intent(ctx, WirelessPairReceiver.class).setAction(ACTION_PAIR_REPLY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent replyPending = PendingIntent.getBroadcast(ctx, 40, replyIntent, flags);

        RemoteInput remoteInput = new RemoteInput.Builder(KEY_PAIR_CODE)
            .setLabel(isEs() ? "Código de 6 dígitos (ej: 482910)" : "6-digit code (e.g. 482910)")
            .build();

        Notification.Action action = new Notification.Action.Builder(
            null, isEs() ? "Poner código" : "Enter code", replyPending)
            .addRemoteInput(remoteInput)
            .build();

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx);
        b.setContentTitle(isEs() ? "OpenCode · Listo para emparejar" : "OpenCode · Ready to Pair")
            .setContentText(isEs()
                ? "Pulsa 'Poner código' cuando aparezcan los 6 dígitos"
                : "Tap 'Enter code' when the 6-digit dialog is shown")
            .setSmallIcon(ctx.getResources().getIdentifier("ic_launcher", "mipmap", ctx.getPackageName()))
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(action)
            .setAutoCancel(true);

        nm.notify(NOTIF_ID, b.build());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_PAIR_REPLY.equals(intent.getAction())) return;
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;
        CharSequence raw = results.getCharSequence(KEY_PAIR_CODE);
        final String rawStr = raw != null ? raw.toString().trim() : "";

        // Support "482910" (auto mDNS port) or "482910 37421" / "482910:37421"
        String[] parts = rawStr.split("[\\s:]+");
        final String code = parts.length > 0 ? parts[0] : "";
        int parsedPort = -1;
        if (parts.length > 1) {
            try { parsedPort = Integer.parseInt(parts[1].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
        }
        final int port = parsedPort;

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                String res = LocalAdbEngine.pair(context, code, port);
                boolean ok = res.startsWith("OK:");
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    int pFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                    Intent backIntent = new Intent(context, SetupActivity.needed(context) ? SetupActivity.class : MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    PendingIntent backPending = PendingIntent.getActivity(context, 41, backIntent, pFlags);

                    Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
                    b.setContentTitle(ok
                            ? (isEs() ? "✓ ¡Control táctil activado!" : "✓ Device Control Enabled!")
                            : (isEs() ? "Error al emparejar" : "Pairing Failed"))
                        .setContentText(ok
                            ? (isEs() ? "Toca aquí para volver a OpenCode" : "Tap here to return to OpenCode")
                            : res)
                        .setSmallIcon(context.getResources().getIdentifier("ic_launcher", "mipmap", context.getPackageName()))
                        .setContentIntent(backPending)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .setAutoCancel(true);
                    nm.notify(NOTIF_ID, b.build());
                }
            } finally {
                pending.finish();
            }
        }, "OpenCode-Pair").start();
    }
}
