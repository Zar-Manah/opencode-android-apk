package com.opencode.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/** OpenCode: Resumes background server on boot only when 24/7 mode is enabled and permitted. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "OpenCodeBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("opencode_prefs", Context.MODE_PRIVATE);
        boolean is24_7 = prefs.getBoolean("pref_24_7", false);
        if (!is24_7 || SetupActivity.needed(context)) return;

        Intent i = new Intent(context, OpenCodeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to auto-start foreground service on boot: " + e.getMessage());
        }
    }
}
