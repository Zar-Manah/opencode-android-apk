package com.opencode.android;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Shared API-level compatibility helpers safe down to minSdkVersion 24. */
final class CompatUtils {

    private CompatUtils() {}

    /** Waits up to {@code timeoutMs} milliseconds for {@code p} to exit. */
    static boolean waitForProcess(Process p, long timeoutMs) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        while (true) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException ignored) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                long remNs = deadline - System.nanoTime();
                if (remNs <= 0L) return false;
                Thread.sleep(Math.min(25L, Math.max(1L, remNs / 1_000_000L)));
            }
        }
    }

    /** Terminates {@code p} forcibly on API 26+ or via destroy() on API 24-25. */
    static void destroyForciblyCompat(Process p) {
        if (p == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            p.destroyForcibly();
        } else {
            p.destroy();
        }
    }

    /** Returns true if {@code f} is a symbolic link without using java.nio.file. */
    static boolean isSymbolicLink(File f) {
        if (f == null) return false;
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getAbsolutePath()).st_mode);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Reads the entire contents of {@code f} into a byte array. */
    static byte[] readFileBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
