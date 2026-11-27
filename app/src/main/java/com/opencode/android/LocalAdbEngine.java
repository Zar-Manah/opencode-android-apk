package com.opencode.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * OpenCode: Self-contained localhost ADB engine (no AccessibilityService, no companion app).
 * Supports Android 11+ Wireless Debugging (mDNS TLS + SPAKE2 pairing) and ADB TCP (localhost:5555).
 */
public final class LocalAdbEngine {

    private static final String TAG = "OpenCodeAdb";
    private static final String ADB_SERVER_PORT = "15037";
    private static final String SVC_CONNECT = "_adb-tls-connect._tcp.";
    private static final String SVC_PAIRING = "_adb-tls-pairing._tcp.";

    private static volatile String activeSerial = null;
    private static volatile boolean rootAvailable = false;
    private static volatile boolean rootChecked = false;
    private static volatile String suBinary = "su";
    private static volatile String lastConnectError = null;
    private static final Object LOCK = new Object();

    static String getLastError() {
        return lastConnectError;
    }

    static boolean hasActiveSerial() {
        return activeSerial != null || rootAvailable;
    }

    static boolean isRootAvailable() {
        if (!rootChecked) {
            probeRoot();
        }
        return rootAvailable;
    }

    private static synchronized void probeRoot() {
        if (rootChecked) return;
        rootChecked = true;
        for (String suPath : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "su"}) {
            try {
                Process p = new ProcessBuilder(suPath, "-c", "id").redirectErrorStream(true).start();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[512];
                    int n;
                    while ((n = in.read(b)) != -1) o.write(b, 0, n);
                }
                if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0 && o.toString("UTF-8").contains("uid=0")) {
                    suBinary = suPath;
                    rootAvailable = true;
                    Log.i(TAG, "Root control available via " + suPath);
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    private static String runRootText(String cmd, int timeoutSec) throws Exception {
        Process p = new ProcessBuilder(suBinary, "-c", cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
        }
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("su timeout");
        }
        String res = o.toString("UTF-8");
        if (p.exitValue() != 0) throw new RuntimeException("su failed: " + res.trim());
        return res;
    }

    private static byte[] runRootRaw(String cmd, int timeoutSec) throws Exception {
        Process p = new ProcessBuilder(suBinary, "-c", cmd).redirectErrorStream(false).start();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
        }
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("su timeout");
        }
        return o.toByteArray();
    }

    static boolean isConnected(Context ctx) {
        if (activeSerial != null && checkDeviceOnline(ctx, activeSerial)) {
            return true;
        }
        return findConnectedSerial(ctx) != null;
    }

    private static File homeDir(Context ctx) {
        return new File(ctx.getFilesDir(), "server-home");
    }

    private static File adbBinary(Context ctx) {
        File h = homeDir(ctx);
        File real = new File(h, "toolchain/usr/bin/adb.real");
        if (real.canExecute()) return real;
        return new File(h, "toolchain/usr/bin/adb");
    }

    static boolean isBinaryReady(Context ctx) {
        File b = adbBinary(ctx);
        return b.exists() && b.canExecute();
    }

    private static ProcessBuilder buildAdbCmd(Context ctx, List<String> args) {
        File h = homeDir(ctx);
        File tmp = new File(h, "tmp");
        File androidDir = new File(h, ".android");
        if (!tmp.exists()) tmp.mkdirs();
        if (!androidDir.exists()) androidDir.mkdirs();

        List<String> cmd = new ArrayList<>();
        cmd.add(adbBinary(ctx).getAbsolutePath());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("HOME", h.getAbsolutePath());
        env.put("ANDROID_USER_HOME", androidDir.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("ANDROID_ADB_SERVER_PORT", ADB_SERVER_PORT);
        env.put("LD_LIBRARY_PATH", new File(h, "toolchain/usr/lib/adb-libs").getAbsolutePath());
        return pb;
    }

    private static byte[] runRaw(Context ctx, int timeoutSec, String... args) throws Exception {
        if (!isBinaryReady(ctx)) {
            throw new RuntimeException("ADB binary is still extracting");
        }
        List<String> list = new ArrayList<>();
        Collections.addAll(list, args);
        ProcessBuilder pb = buildAdbCmd(ctx, list);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        Thread errReader = new Thread(() -> {
            try (InputStream ein = p.getErrorStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = ein.read(b)) != -1) err.write(b, 0, n);
            } catch (Exception ignored) {}
        });
        errReader.start();

        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }

        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        errReader.join(500);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("adb timeout");
        }
        if (p.exitValue() != 0 && out.size() == 0 && err.size() > 0) {
            throw new RuntimeException(new String(err.toByteArray()).trim());
        }
        return out.toByteArray();
    }

    private static String runText(Context ctx, int timeoutSec, String... args) throws Exception {
        if (!isBinaryReady(ctx)) {
            throw new RuntimeException("ADB binary is still extracting");
        }
        List<String> list = new ArrayList<>();
        Collections.addAll(list, args);
        ProcessBuilder pb = buildAdbCmd(ctx, list);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("adb command timeout");
        }
        String res = out.toString("UTF-8");
        if (p.exitValue() != 0 && res.trim().isEmpty()) {
            throw new RuntimeException("adb exited with " + p.exitValue());
        }
        return res;
    }

    private static boolean checkDeviceOnline(Context ctx, String serial) {
        try {
            String out = runText(ctx, 3, "-s", serial, "get-state").trim();
            return "device".equals(out);
        } catch (Exception e) {
            return false;
        }
    }

    private static String findConnectedSerial(Context ctx) {
        try {
            String devs = runText(ctx, 4, "devices");
            for (String line : devs.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("List of devices")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && "device".equals(parts[1])) {
                    activeSerial = parts[0];
                    return activeSerial;
                }
            }
        } catch (Exception ignored) {}
        activeSerial = null;
        return null;
    }

    private static String getLocalIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName();
                if (name != null && (name.startsWith("wlan") || name.startsWith("eth"))) {
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Discovers mDNS ADB endpoints (_adb-tls-connect or _adb-tls-pairing), returning ordered candidates. */
    static List<String> discoverEndpoints(Context ctx, String serviceType, int timeoutMs) {
        Set<String> candidates = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return new ArrayList<>();
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return new ArrayList<>();

        WifiManager.MulticastLock mLock = null;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mLock = wm.createMulticastLock("opencode_mdns");
                mLock.setReferenceCounted(false);
                mLock.acquire();
            }
        } catch (Exception ignored) {}

        final CountDownLatch latch = new CountDownLatch(1);
        final Object resolveLock = new Object();

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String st, int err) { latch.countDown(); }
            @Override public void onStopDiscoveryFailed(String st, int err) {}
            @Override public void onDiscoveryStarted(String st) {}
            @Override public void onDiscoveryStopped(String st) {}
            @Override public void onServiceLost(NsdServiceInfo si) {}

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                synchronized (resolveLock) {
                    try {
                        nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}
                            @Override public void onServiceResolved(NsdServiceInfo info) {
                                InetAddress host = info.getHost();
                                int port = info.getPort();
                                if (port > 0) {
                                    synchronized (candidates) {
                                        candidates.add("127.0.0.1:" + port);
                                        if (host != null && host.getHostAddress() != null) {
                                            String ip = host.getHostAddress();
                                            if (!ip.contains(":")) {
                                                candidates.add(ip + ":" + port);
                                            }
                                        }
                                        String localIp = getLocalIpv4();
                                        if (localIp != null) {
                                            candidates.add(localIp + ":" + port);
                                        }
                                    }
                                    Log.i(TAG, "Discovered " + serviceType + " port=" + port);
                                    latch.countDown();
                                }
                            }
                        });
                    } catch (Exception ignored) {}
                }
            }
        };

        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.w(TAG, "mDNS discovery error", e);
        } finally {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignored) {}
            if (mLock != null) {
                try { if (mLock.isHeld()) mLock.release(); } catch (Exception ignored) {}
            }
        }
        synchronized (candidates) {
            return new ArrayList<>(candidates);
        }
    }

    static boolean isWifiConnected(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network nw = cm.getActiveNetwork();
                if (nw != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
                }
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
                }
            }
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getIpAddress() != 0) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    static void warmServer(Context ctx) {
        try {
            if (isBinaryReady(ctx)) runText(ctx, 4, "start-server");
        } catch (Exception ignored) {}
    }

    /** Ensures ADB is connected to local device (via Wireless Debugging TLS or localhost:5555). */
    static String ensureConnected(Context ctx) {
        synchronized (LOCK) {
            if (!isBinaryReady(ctx)) {
                lastConnectError = "ADB binary still unpacking";
                return null;
            }
            if (activeSerial != null && checkDeviceOnline(ctx, activeSerial)) {
                return activeSerial;
            }
            String existing = findConnectedSerial(ctx);
            if (existing != null) return existing;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isWifiConnected(ctx)) {
                List<String> targets = discoverEndpoints(ctx, SVC_CONNECT, 2400);
                for (String target : targets) {
                    try {
                        String res = runText(ctx, 6, "connect", target);
                        Log.i(TAG, "adb connect " + target + " -> " + res.trim());
                        if (res.contains("connected to") || res.contains("already connected")) {
                            String s = findConnectedSerial(ctx);
                            if (s != null) {
                                lastConnectError = null;
                                return s;
                            }
                        } else {
                            lastConnectError = res.trim();
                        }
                    } catch (Exception e) {
                        lastConnectError = e.getMessage();
                    }
                }
            }

            // Fallback: standard ADB over TCP on port 5555 (Android 7-10 or pre-enabled tcpip)
            try {
                String res = runText(ctx, 2, "connect", "127.0.0.1:5555");
                if (res.contains("connected to") || res.contains("already connected")) {
                    String s = findConnectedSerial(ctx);
                    if (s != null) {
                        lastConnectError = null;
                        return s;
                    }
                }
            } catch (Exception ignored) {}

            if (lastConnectError == null) {
                lastConnectError = "Device control not paired yet. Open OpenCode Control/Settings to pair Wireless Debugging.";
            }
            return null;
        }
    }

    /** Pairs Android 11+ Wireless Debugging with 6-digit code and optional port (auto-discovered if <= 0). */
    static String pair(Context ctx, String code, int port) {
        synchronized (LOCK) {
            if (!isBinaryReady(ctx)) {
                return "Toolchain is still extracting; wait a second and retry.";
            }
            String cleanCode = code != null ? code.replaceAll("[^0-9]", "") : "";
            if (cleanCode.length() < 4) {
                return "Enter the 6-digit pairing code shown under Wireless Debugging.";
            }

            // Warm ADB daemon so adbkey is ready before mDNS discovery
            try { runText(ctx, 5, "start-server"); } catch (Exception ignored) {}

            List<String> targets = new ArrayList<>();
            if (port > 0) {
                targets.add("127.0.0.1:" + port);
                String localIp = getLocalIpv4();
                if (localIp != null) targets.add(localIp + ":" + port);
            } else {
                targets = discoverEndpoints(ctx, SVC_PAIRING, 4500);
            }
            if (targets.isEmpty()) {
                return "Pairing service not found. Keep the 'Pair device with pairing code' dialog open, or add the port after the code (e.g. " + cleanCode + " 37421).";
            }

            String lastOut = "";
            for (String target : targets) {
                try {
                    String out = runText(ctx, 12, "pair", target, cleanCode);
                    Log.i(TAG, "adb pair " + target + " -> " + out);
                    lastOut = out.trim();
                    if (out.toLowerCase().contains("successfully paired")) {
                        ctx.getSharedPreferences("opencode_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("pref_adb_paired", true).apply();
                        if (ensureConnected(ctx) == null) {
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                            ensureConnected(ctx);
                        }
                        return "OK: " + lastOut;
                    }
                } catch (Exception e) {
                    lastOut = e.getMessage() != null ? e.getMessage() : "error";
                }
            }
            return "Pair failed: " + lastOut;
        }
    }

    static String verifyControl(Context ctx) {
        try {
            String size = shell(ctx, "wm size").trim();
            return "OK: " + (size.isEmpty() ? "Connected" : size);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static String shell(Context ctx, String cmd) throws Exception {
        if (isRootAvailable()) {
            return runRootText(cmd, 20);
        }
        String serial = ensureConnected(ctx);
        if (serial == null) {
            throw new RuntimeException(lastConnectError != null ? lastConnectError : "ADB not connected");
        }
        String res = runText(ctx, 20, "-s", serial, "shell", cmd);
        if (res.contains("device offline") || res.contains("device not found")) {
            activeSerial = null;
            serial = ensureConnected(ctx);
            if (serial == null) throw new RuntimeException("ADB disconnected");
            res = runText(ctx, 20, "-s", serial, "shell", cmd);
        }
        return res;
    }

    static byte[] execOut(Context ctx, String cmd) throws Exception {
        if (isRootAvailable()) {
            return runRootRaw(cmd, 15);
        }
        String serial = ensureConnected(ctx);
        if (serial == null) {
            throw new RuntimeException(lastConnectError != null ? lastConnectError : "ADB not connected");
        }
        return runRaw(ctx, 15, "-s", serial, "exec-out", cmd);
    }

    static String dumpUi(Context ctx) throws Exception {
        String xml = shell(ctx, "uiautomator dump /data/local/tmp/oc_dump.xml >/dev/null 2>&1 && cat /data/local/tmp/oc_dump.xml");
        int idx = xml.indexOf("<?xml");
        if (idx >= 0) return xml.substring(idx);
        idx = xml.indexOf("<hierarchy");
        if (idx >= 0) return xml.substring(idx);
        throw new RuntimeException("UI dump empty: " + xml.trim());
    }

    static boolean installApk(Context ctx, String apkPath) throws Exception {
        if (isRootAvailable()) {
            String out = runRootText("pm install -r '" + apkPath.replace("'", "'\\''") + "'", 60);
            return out.contains("Success");
        }
        String serial = ensureConnected(ctx);
        if (serial == null) return false;
        String out = runText(ctx, 60, "-s", serial, "install", "-r", apkPath);
        Log.i(TAG, "adb install -> " + out);
        return out.contains("Success");
    }

    static void shutdown(Context ctx) {
        activeSerial = null;
        try {
            if (isBinaryReady(ctx)) runText(ctx, 2, "kill-server");
        } catch (Exception ignored) {}
    }
}
