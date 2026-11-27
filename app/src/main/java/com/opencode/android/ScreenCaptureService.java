package com.opencode.android;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
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
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** OpenCode: Official persistent Android MediaProjection service for full-screen capture (`pc shot`). */
public class ScreenCaptureService extends Service {

    private static final String TAG = "OpenCodeCap";
    private static final String EXTRA_CODE = "result_code";
    private static final String EXTRA_DATA = "result_data";
    private static final String EXTRA_CONSENT_ID = "consent_id";

    private static volatile ScreenCaptureService instance;
    private static volatile CountDownLatch consentLatch;
    private static volatile boolean consentDenied;
    private static volatile boolean consentCompleted;
    private static volatile long consentSeq;
    private static volatile long activeConsentId;
    private static final Object CAP_LOCK = new Object();

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private int capW;
    private int capH;
    private int capDpi;

    private final Object frameLock = new Object();
    private Bitmap latestNonBlankBitmap;
    private Bitmap latestAnyBitmap;
    private long nonBlankSeq = 0;
    private long anySeq = 0;
    private long lastFrameMs = 0;
    private long sessionStartedMs = 0;

    static boolean isConsentFlowCompleted() {
        return consentCompleted;
    }

    static boolean hasActiveProjection() {
        ScreenCaptureService s = instance;
        return s != null && s.projection != null && s.virtualDisplay != null && s.imageReader != null;
    }

    static void startWithConsent(Context ctx, int resultCode, Intent data) {
        long reqId = activeConsentId;
        if (reqId == 0L) {
            consentDenied = true;
            consentCompleted = true;
            return;
        }
        consentDenied = false;
        consentCompleted = true;
        Intent svc = new Intent(ctx, ScreenCaptureService.class);
        svc.putExtra(EXTRA_CODE, resultCode);
        svc.putExtra(EXTRA_DATA, data);
        svc.putExtra(EXTRA_CONSENT_ID, reqId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }

    static void onConsentDenied() {
        activeConsentId = 0L;
        consentDenied = true;
        consentCompleted = true;
        CountDownLatch l = consentLatch;
        if (l != null) l.countDown();
    }

    static void stopCapture(Context ctx) {
        ScreenCaptureService s = instance;
        if (s != null) {
            s.releaseResources();
        }
        try {
            ctx.stopService(new Intent(ctx, ScreenCaptureService.class));
        } catch (Exception ignored) {}
    }

    /** Captures the current device display to PNG bytes, requesting user consent once per session if needed. */
    static byte[] capturePng(Context ctx) {
        synchronized (CAP_LOCK) {
            boolean fresh = false;
            if (!hasActiveProjection()) {
                fresh = true;
                consentDenied = false;
                consentCompleted = false;
                long reqId = ++consentSeq;
                activeConsentId = reqId;
                consentLatch = new CountDownLatch(1);
                Intent ask = new Intent(ctx, ScreenCaptureActivity.class);
                ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                ctx.startActivity(ask);
                boolean signaled;
                try {
                    signaled = consentLatch.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    if (activeConsentId == reqId) activeConsentId = 0L;
                    return null;
                }
                if (activeConsentId == reqId) activeConsentId = 0L;
                if (!signaled || consentDenied || !hasActiveProjection()) {
                    return null;
                }
            }
            ScreenCaptureService s = instance;
            return s != null ? s.grabPngFrame(fresh) : null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        bgThread = new HandlerThread("OpenCode-Cap");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notif = buildCapNotification();
        if (intent == null || !intent.hasExtra(EXTRA_DATA)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
        Intent data = (Build.VERSION.SDK_INT >= 33)
            ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
            : intent.getParcelableExtra(EXTRA_DATA);
        long reqId = intent.getLongExtra(EXTRA_CONSENT_ID, 0L);
        if (code != Activity.RESULT_OK || data == null || reqId == 0L || reqId != activeConsentId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(3, notif);
            }
            onConsentDenied();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(3, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(3, notif);
        }

        try {
            releaseProjectionOnly();
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) {
                onConsentDenied();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user");
                    releaseProjectionOnly();
                    stopForeground(true);
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            setupVirtualDisplay();
            CountDownLatch l = consentLatch;
            if (l != null) l.countDown();
            return START_NOT_STICKY;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaProjection", e);
            onConsentDenied();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void setupVirtualDisplay() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        int realW = 1080;
        int realH = 2400;
        int dpi = DisplayMetrics.DENSITY_HIGH;
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
                realW = Math.max(1, b.width());
                realH = Math.max(1, b.height());
                dm = getResources().getDisplayMetrics();
                dpi = dm.densityDpi;
            } else {
                wm.getDefaultDisplay().getRealMetrics(dm);
                realW = Math.max(1, dm.widthPixels);
                realH = Math.max(1, dm.heightPixels);
                dpi = dm.densityDpi;
            }
        }
        if (realW > 1080 && realW <= realH) {
            float scale = 1080f / realW;
            capW = 1080;
            capH = Math.max(1, Math.round(realH * scale));
        } else if (realH > 1080 && realW > realH) {
            float scale = 1080f / realH;
            capH = 1080;
            capW = Math.max(1, Math.round(realW * scale));
        } else {
            capW = realW;
            capH = realH;
        }
        capDpi = dpi;

        synchronized (frameLock) {
            sessionStartedMs = System.currentTimeMillis();
            nonBlankSeq = 0;
            anySeq = 0;
            lastFrameMs = 0;
            if (latestNonBlankBitmap != null) { latestNonBlankBitmap.recycle(); latestNonBlankBitmap = null; }
            if (latestAnyBitmap != null) { latestAnyBitmap.recycle(); latestAnyBitmap = null; }
        }

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                Bitmap bmp = imageToBitmap(img);
                if (bmp == null) return;
                boolean blank = isBlankFrame(bmp);
                synchronized (frameLock) {
                    lastFrameMs = System.currentTimeMillis();
                    anySeq++;
                    if (latestAnyBitmap != null) latestAnyBitmap.recycle();
                    latestAnyBitmap = bmp;
                    if (!blank) {
                        nonBlankSeq++;
                        if (latestNonBlankBitmap != null) latestNonBlankBitmap.recycle();
                        latestNonBlankBitmap = bmp.copy(bmp.getConfig(), false);
                    }
                    frameLock.notifyAll();
                }
            } catch (Exception e) {
                Log.w(TAG, "Frame listener error: " + e.getMessage());
            } finally {
                if (img != null) {
                    try { img.close(); } catch (Exception ignored) {}
                }
            }
        }, bgHandler);

        virtualDisplay = projection.createVirtualDisplay(
            "OpenCodeScreen",
            capW, capH, capDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null, bgHandler
        );
    }

    private Bitmap imageToBitmap(Image img) {
        try {
            int imgW = img.getWidth();
            int imgH = img.getHeight();
            if (imgW <= 0 || imgH <= 0) return null;
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) return null;
            Image.Plane plane = planes[0];
            ByteBuffer buf = plane.getBuffer();
            buf.rewind();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * imgW;
            int paddedW = imgW + (pixelStride > 0 && rowPadding > 0 ? rowPadding / pixelStride : 0);
            Bitmap full = Bitmap.createBitmap(paddedW, imgH, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buf);
            if (paddedW == imgW) return full;
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, imgW, imgH);
            full.recycle();
            return cropped;
        } catch (Exception e) {
            return null;
        }
    }

    private void nudgeVirtualDisplay() {
        VirtualDisplay vd = virtualDisplay;
        if (vd == null || capW <= 0 || capH <= 0) return;
        try {
            int altDpi = (capDpi > 120) ? (capDpi - 1) : (capDpi + 1);
            vd.resize(capW, capH, altDpi);
            Thread.sleep(40);
            vd.resize(capW, capH, capDpi);
        } catch (Exception ignored) {}
    }

    private static boolean isBlankFrame(Bitmap bmp) {
        if (bmp == null) return true;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return true;
        int stepX = Math.max(1, w / 28);
        int stepY = Math.max(1, h / 36);
        int total = 0;
        int nonBlack = 0;
        int maxY = Math.max(1, (int) (h * 0.94f));
        for (int y = 0; y < maxY; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int p = bmp.getPixel(x, y);
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                if (r > 12 || g > 12 || b > 12) nonBlack++;
                total++;
            }
        }
        return total > 0 && nonBlack < 3;
    }

    private byte[] grabPngFrame(boolean fresh) {
        if (fresh) {
            // Wait for the permission dialog overlay animation to vanish before snapping
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            long waitDeadline = System.currentTimeMillis() + 2000L;
            boolean nudged = false;
            while (System.currentTimeMillis() < waitDeadline) {
                synchronized (frameLock) {
                    if (nonBlankSeq > 0 && System.currentTimeMillis() - sessionStartedMs >= 650L) {
                        break;
                    }
                    long rem = waitDeadline - System.currentTimeMillis();
                    if (rem > 0) {
                        try { frameLock.wait(Math.min(rem, 350L)); } catch (InterruptedException ignored) { break; }
                    }
                }
                if (!nudged && nonBlankSeq == 0) {
                    nudged = true;
                    nudgeVirtualDisplay();
                }
            }
        } else {
            long startAny;
            long age;
            synchronized (frameLock) {
                startAny = anySeq;
                age = System.currentTimeMillis() - lastFrameMs;
            }
            if (age > 180L) {
                nudgeVirtualDisplay();
                long deadline = System.currentTimeMillis() + 650L;
                synchronized (frameLock) {
                    while (anySeq == startAny && System.currentTimeMillis() < deadline) {
                        long rem = deadline - System.currentTimeMillis();
                        if (rem <= 0) break;
                        try { frameLock.wait(rem); } catch (InterruptedException ignored) { break; }
                    }
                }
            }
        }

        Bitmap snapshot = null;
        synchronized (frameLock) {
            Bitmap src = (latestNonBlankBitmap != null) ? latestNonBlankBitmap : latestAnyBitmap;
            if (src != null && !src.isRecycled()) {
                snapshot = src.copy(src.getConfig(), false);
            }
        }
        if (snapshot == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            snapshot.compress(Bitmap.CompressFormat.PNG, 92, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Frame compress failed", e);
            return null;
        } finally {
            snapshot.recycle();
        }
    }

    private synchronized void releaseProjectionOnly() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        synchronized (frameLock) {
            if (latestNonBlankBitmap != null) { latestNonBlankBitmap.recycle(); latestNonBlankBitmap = null; }
            if (latestAnyBitmap != null) { latestAnyBitmap.recycle(); latestAnyBitmap = null; }
            nonBlankSeq = 0;
            anySeq = 0;
        }
    }

    private synchronized void releaseResources() {
        releaseProjectionOnly();
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
            bgHandler = null;
        }
    }

    private Notification buildCapNotification() {
        String ch = "opencode_cap";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(ch, getString(R.string.cap_channel_name), NotificationManager.IMPORTANCE_LOW);
            chan.setShowBadge(false);
            nm.createNotificationChannel(chan);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.cap_notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        releaseResources();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
