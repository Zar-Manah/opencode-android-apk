package com.opencode.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** OpenCode: Transparent single-purpose activity to request official MediaProjection full-display consent. */
public class ScreenCaptureActivity extends Activity {

    private static final int REQ_MEDIA_PROJ = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            ScreenCaptureService.onConsentDenied();
            finish();
            return;
        }
        try {
            // On Android 14+ (API 34+), request default display capture explicitly so "single app" does not
            // trap the translucent activity and terminate MediaProjection right after finish().
            Intent capIntent = (Build.VERSION.SDK_INT >= 34)
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
            startActivityForResult(capIntent, REQ_MEDIA_PROJ);
        } catch (Exception e) {
            try {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJ);
            } catch (Exception e2) {
                ScreenCaptureService.onConsentDenied();
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJ) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.startWithConsent(this, resultCode, data);
            } else {
                ScreenCaptureService.onConsentDenied();
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && !ScreenCaptureService.isConsentFlowCompleted()) {
            ScreenCaptureService.onConsentDenied();
        }
        super.onDestroy();
    }
}
