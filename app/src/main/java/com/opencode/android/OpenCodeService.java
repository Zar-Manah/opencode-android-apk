package com.opencode.android;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/** OpenCode: Foreground service managing local server, fast asset seeding, and lifecycle. */
public class OpenCodeService extends Service {

    private static final String TAG = "OpenCodeService";
    static final int PORT = 4096;

    public static final String ACTION_STOP_SERVICE = "com.opencode.android.ACTION_STOP_SERVICE";
    public static final String ACTION_TOGGLE_24_7 = "com.opencode.android.ACTION_TOGGLE_24_7";
    public static final String ACTION_PREWARM = "com.opencode.android.ACTION_PREWARM";

    // Live boot progress observed by MainActivity splash UI
    static volatile String bootStage = "Starting OpenCode…";
    static volatile int bootProgress = 0;
    static volatile boolean factoryReady = false;
    static volatile boolean engineStarted = false;
    static volatile boolean uiFirstPaintReady = false;
    static volatile String fatalError = null;

    private final Object lifecycleLock = new Object();
    private final Object serverLock = new Object();
    private final Object factoryLock = new Object();
    private Process server;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notifyManager;
    private volatile File cachedServerDir = null;
    private volatile boolean assetsWarmed = false;
    private volatile boolean prewarmStarted = false;
    private volatile boolean serverRunning = false;
    private volatile boolean is24_7 = false;
    private volatile boolean initThreadStarted = false;
    private volatile boolean stopping = false;

    private void setStatus(String stage, int pct) {
        bootStage = stage;
        bootProgress = pct;
        updateNotification();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        stopping = false;
        fatalError = null;
        if (!factoryReady) {
            bootStage = getString(R.string.boot_starting);
            bootProgress = 0;
        }
        DeviceBridgeService.startBridge(this);
        SharedPreferences prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);
        is24_7 = prefs.getBoolean("pref_24_7", false);
        if (is24_7) {
            acquireWakeLock();
        }
        startForeground(1, buildNotification());
    }

    private synchronized void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:24_7");
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.i(TAG, "WakeLock acquired (24/7 mode enabled)");
        }
    }

    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released (24/7 mode disabled)");
        }
    }

    private void stopServer() {
        Process p;
        synchronized (lifecycleLock) {
            p = server;
            server = null;
            serverRunning = false;
        }
        if (p != null) {
            try {
                p.destroy();
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {
                try { p.destroyForcibly(); } catch (Exception ignored2) {}
            }
        }
        updateNotification();
    }

    private void shutdownCleanly() {
        stopping = true;
        stopServer();
        DeviceBridgeService.stopBridge();
        ScreenCaptureService.stopCapture(this);
        LocalAdbEngine.shutdown(this);
        releaseWakeLock();
        factoryReady = false;
        engineStarted = false;
        uiFirstPaintReady = false;
        assetsWarmed = false;
        prewarmStarted = false;
        initThreadStarted = false;
        cachedServerDir = null;
        stopForeground(true);
        stopSelf();
    }

    private void updateNotification() {
        try {
            if (stopping) return;
            if (notifyManager == null) {
                notifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            }
            if (notifyManager != null) {
                notifyManager.notify(1, buildNotification());
            }
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (ACTION_STOP_SERVICE.equals(act)) {
                Log.i(TAG, "ACTION_STOP_SERVICE received");
                new Thread(this::shutdownCleanly, "OpenCode-Shutdown").start();
                return START_NOT_STICKY;
            } else if (ACTION_PREWARM.equals(act)) {
                if (!prewarmStarted && !initThreadStarted) {
                    synchronized (lifecycleLock) {
                        if (!prewarmStarted && !initThreadStarted) {
                            prewarmStarted = true;
                            new Thread(this::prewarmAssets, "OpenCode-Prewarm").start();
                        }
                    }
                }
                return START_STICKY;
            } else if (ACTION_TOGGLE_24_7.equals(act)) {
                is24_7 = !is24_7;
                getSharedPreferences("opencode_prefs", MODE_PRIVATE)
                    .edit().putBoolean("pref_24_7", is24_7).apply();
                if (is24_7) acquireWakeLock();
                else releaseWakeLock();
                updateNotification();
                return START_STICKY;
            }
        }

        if (!initThreadStarted) {
            synchronized (lifecycleLock) {
                if (!initThreadStarted) {
                    initThreadStarted = true;
                    new Thread(this::initializeAndBoot, "OpenCode-Init").start();
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!is24_7) {
            Log.i(TAG, "App swiped from Recents with 24/7 off: shutting down cleanly");
            shutdownCleanly();
        }
        super.onTaskRemoved(rootIntent);
    }

    private long totalDeviceRamMb() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (mi.totalMem > 0) return mi.totalMem / (1024L * 1024L);
            }
        } catch (Exception ignored) {}
        return 4096L;
    }

    private String optimalGradleXmx() {
        long mb = totalDeviceRamMb();
        if (mb >= 10000) return "-Xmx3072m";
        if (mb >= 7000) return "-Xmx2048m";
        if (mb >= 5000) return "-Xmx1536m";
        return "-Xmx1024m";
    }

    private String optimalD8Xmx() {
        long mb = totalDeviceRamMb();
        if (mb >= 10000) return "-Xmx2048m";
        if (mb >= 7000) return "-Xmx1536m";
        if (mb >= 5000) return "-Xmx1024m";
        return "-Xmx768m";
    }

    private void prewarmAssets() {
        try {
            File home = new File(getFilesDir(), "server-home");
            if (!home.exists()) home.mkdirs();
            synchronized (serverLock) {
                if (!assetsWarmed) {
                    setStatus(getString(R.string.boot_preparing_engine), 20);
                    cachedServerDir = installServer();
                    setStatus(getString(R.string.boot_configuring_wrappers), 55);
                    installPc();
                    assetsWarmed = true;
                }
            }
            if (!stopping && !serverRunning) {
                setStatus(getString(R.string.boot_engine_ready_setup), 85);
            }
        } catch (Exception e) {
            Log.e(TAG, "Prewarm failed", e);
        }
    }

    private void initializeAndBoot() {
        try {
            File home = new File(getFilesDir(), "server-home");
            if (!home.exists()) home.mkdirs();

            synchronized (serverLock) {
                if (!assetsWarmed || cachedServerDir == null) {
                    setStatus(getString(R.string.boot_preparing_engine), 20);
                    cachedServerDir = installServer();
                    setStatus(getString(R.string.boot_configuring_wrappers), 55);
                    installPc();
                    assetsWarmed = true;
                } else {
                    refreshWorkspaceConfig(home);
                }
            }

            // Boot :4096 IMMEDIATELY with zero heavy tar contention
            setStatus(getString(R.string.boot_starting_server), 78);
            bootServer(cachedServerDir);

            // Fast loopback socket probe until :4096 binds (0.1ms per check, zero HTTP overhead)
            long t0 = System.currentTimeMillis();
            while (!stopping && !serverPortOpen() && System.currentTimeMillis() - t0 < 8000L) {
                try { Thread.sleep(60); } catch (InterruptedException ignored) { break; }
            }

            if (!stopping) {
                setStatus(getString(is24_7 ? R.string.boot_active_24_7 : R.string.boot_active), 100);
            }

            // Wait for WebView first meaningful paint (or 8s fallback) BEFORE unpacking 626MB of SDK/Gradle tars
            Thread factoryThread = new Thread(() -> {
                long waitStart = System.currentTimeMillis();
                while (!stopping && !uiFirstPaintReady && System.currentTimeMillis() - waitStart < 8000L) {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) { return; }
                }
                try { Thread.sleep(1200); } catch (InterruptedException ignored) { return; }
                synchronized (factoryLock) {
                    ensureFactory();
                }
                if (!stopping) {
                    setStatus(getString(is24_7 ? R.string.boot_active_24_7 : R.string.boot_active), 100);
                }
            }, "OpenCode-FactoryBG");
            factoryThread.setPriority(Thread.MIN_PRIORITY);
            factoryThread.start();

            // Probe local ADB/root separately so it never delays UI or toolchain setup
            Thread adbThread = new Thread(() -> {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) { return; }
                if (!stopping) {
                    LocalAdbEngine.isRootAvailable();
                    LocalAdbEngine.ensureConnected(this);
                }
            }, "OpenCode-AdbProbe");
            adbThread.setPriority(Thread.MIN_PRIORITY);
            adbThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Initialization failed", e);
            fatalError = e.getMessage();
            setStatus(getString(R.string.boot_startup_error, String.valueOf(e.getMessage())), 0);
        }
    }

    private void ensureSymlink(File target, File link) {
        try {
            if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                link.delete();
            }
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(new String[]{"/system/bin/ln", "-sfn",
                    target.getAbsolutePath(), link.getAbsolutePath()}).waitFor();
            } catch (Exception ignored) {}
        }
    }

    private void wrapToolchainDirect(File tc, File home) {
        try {
            String homePath = home.getAbsolutePath();
            File usrBin = new File(tc, "usr/bin");
            wrapDirectBinary(new File(usrBin, "git"), homePath, "usr/bin/git.real", null, "usr/lib");
            wrapDirectBinary(new File(usrBin, "aapt2"), homePath, "usr/bin/aapt2.real", null, "usr/lib");
            wrapDirectBinary(new File(usrBin, "adb"), homePath, "usr/bin/adb.real", null, "usr/lib/adb-libs");
            File jvmBin = new File(tc, "usr/lib/jvm/java-21-openjdk/bin");
            for (String b : new String[]{"java", "javac", "keytool", "jar"}) {
                wrapDirectBinary(new File(jvmBin, b), homePath, "usr/lib/jvm/java-21-openjdk/bin/" + b + ".real",
                    homePath + "/toolchain/usr/lib/jvm/java-21-openjdk", "usr/lib");
            }
        } catch (Exception e) {
            Log.e(TAG, "wrapToolchainDirect failed", e);
        }
    }

    private void wrapDirectBinary(File bin, String homePath, String relReal, String javaHome, String relLibDir) {
        try {
            if (!bin.exists()) return;
            File real = new File(homePath, "toolchain/" + relReal);
            if (!real.exists()) {
                bin.renameTo(real);
                chmod(real, 0755);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("#!/system/bin/sh\n");
            sb.append("export HOME=\"").append(homePath).append("\"\n");
            if (javaHome != null) {
                sb.append("export JAVA_HOME=\"").append(javaHome).append("\"\n");
            }
            if ("usr/lib/adb-libs".equals(relLibDir)) {
                sb.append("export ANDROID_USER_HOME=\"").append(homePath).append("/.android\"\n");
                sb.append("export TMPDIR=\"").append(homePath).append("/tmp\"\n");
                sb.append("export ANDROID_ADB_SERVER_PORT=\"${ANDROID_ADB_SERVER_PORT:-15037}\"\n");
            }
            sb.append("LD_LIBRARY_PATH=\"").append(homePath).append("/toolchain/").append(relLibDir).append("\" exec \"")
              .append(real.getAbsolutePath()).append("\" \"$@\"\n");
            writeTextIfChanged(bin, sb.toString());
            chmod(bin, 0755);
        } catch (Exception ignored) {}
    }

    private void ensureFactory() {
        try {
            File home = new File(getFilesDir(), "server-home");
            File tc = new File(home, "toolchain");
            File sdk = new File(home, "android-sdk");
            File gradleModules = new File(home, ".gradle/caches/modules-2");

            File factoryVerFile = new File(home, ".factory-version");
            String wantVer = String.valueOf(BuildConfig.VERSION_CODE);
            File tcJava = new File(tc, "usr/lib/jvm/java-21-openjdk/bin/java");
            File plat = new File(sdk, "platforms/android-35/android.jar");
            File d8Jar = new File(sdk, "build-tools/35.0.0/lib/d8.jar");
            File gFiles = new File(gradleModules, "files-2.1");

            boolean sameVer = wantVer.equals(readMarker(factoryVerFile));
            if (sameVer && tcJava.canExecute() && plat.exists() && d8Jar.exists() && gFiles.exists()) {
                factoryReady = true;
                return;
            }

            setStatus(getString(R.string.boot_install_tc), 45);
            File tcMarker = new File(home, ".toolchain-version");
            if (!wantVer.equals(readMarker(tcMarker)) || !tcJava.canExecute()) {
                extractTarAsset("toolchain/toolchain-native.tar", "toolchain/toolchain-native.tar.gz", tc, 45, 70);
                wrapToolchainDirect(tc, home);
                chmod(new File(tc, "usr/bin/aapt2"), 0755);
                chmod(new File(tc, "usr/bin/git"), 0755);
                chmod(new File(tc, "usr/bin/adb"), 0755);
                chmod(new File(tc, "usr/bin/less"), 0755);
                chmod(new File(tc, "usr/bin/wcurl"), 0755);
                chmod(new File(tc, "usr/lib/jvm/java-21-openjdk/lib/jspawnhelper"), 0755);
                writeMarker(tcMarker, wantVer);
            }

            setStatus(getString(R.string.boot_install_sdk), 72);
            File sdkMarker = new File(home, ".sdk-version");
            if (!wantVer.equals(readMarker(sdkMarker)) || !plat.exists() || !d8Jar.exists()) {
                extractTarAsset("sdk/sdk-native.tar", "sdk/sdk-native.tar.gz", sdk, 72, 82);
                for (String ver : new String[]{"35.0.0", "34.0.0"}) {
                    File bdir = new File(sdk, "build-tools/" + ver);
                    for (String tool : new String[]{"d8", "apksigner", "aapt2", "aapt", "aidl", "zipalign", "dexdump", "split-select", "llvm-rs-cc", "bcc_compat", "lld"}) {
                        chmod(new File(bdir, tool), 0755);
                    }
                    chmod(new File(bdir, "lld-bin/lld"), 0755);
                }
                writeMarker(sdkMarker, wantVer);
            }

            setStatus(getString(R.string.boot_install_gradle), 84);
            File gcMarker = new File(home, ".gradle-cache-version");
            if (!wantVer.equals(readMarker(gcMarker)) || !gFiles.exists()) {
                extractTarAsset("gradle-cache/gradle-cache.tar", "gradle-cache/gradle-cache.tar.gz", gradleModules, 84, 96);
                writeMarker(gcMarker, wantVer);
            }

            // Finalize factory verification via open-factory-setup
            File script = new File(home, "bin/open-factory-setup");
            if (script.exists()) {
                setStatus(getString(R.string.boot_verify_tc), 97);
                File log = new File(home, "factory-setup.log");
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", script.getAbsolutePath());
                Map<String, String> env = pb.environment();
                env.put("HOME", home.getAbsolutePath());
                env.put("OPENCODE_GRADLE_XMX", optimalGradleXmx());
                env.put("OPENCODE_D8_XMX", optimalD8Xmx());
                pb.directory(home);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
                pb.redirectErrorStream(true);
                int exit = pb.start().waitFor();
                Log.i(TAG, "factory setup exit=" + exit);
            }

            if (tcJava.canExecute() && plat.exists() && d8Jar.exists()) {
                writeMarker(factoryVerFile, wantVer);
                writeMarker(new File(home, ".factory-ready"), "ok");
                factoryReady = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "factory failed", e);
            fatalError = getString(R.string.boot_toolchain_error, String.valueOf(e.getMessage()));
        }
    }

    private void extractTarAsset(String primaryTar, String fallbackGz, File dest, int pctStart, int pctEnd) throws Exception {
        if (!dest.exists()) dest.mkdirs();
        boolean gz = false;
        InputStream in;
        try {
            in = getAssets().open(primaryTar);
        } catch (Exception e1) {
            in = getAssets().open(fallbackGz);
            gz = true;
        }
        try (InputStream raw = in) {
            extractTar(raw, gz, dest);
        }
        if (pctEnd >= 0) {
            bootProgress = pctEnd;
            updateNotification();
        }
    }

    private void bootServer(File dir) {
        Log.i(TAG, "boot: start");
        try {
            if (serverUp()) {
                Log.i(TAG, "server already running");
                engineStarted = true;
                return;
            }
            File home = new File(getFilesDir(), "server-home");
            if (!home.exists()) home.mkdirs();
            File work = new File(Environment.getExternalStorageDirectory(), "OpenCode");
            try {
                if (!work.exists()) work.mkdirs();
            } catch (Exception ignored) {}
            if (!work.exists() || !work.canWrite()) {
                work = new File(home, "OpenCode");
                if (!work.exists()) work.mkdirs();
            } else {
                ensureSymlink(work, new File(home, "OpenCode"));
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(new File(dir, "ld-musl-aarch64.so.1").getAbsolutePath());
            cmd.add("--library-path");
            cmd.add(dir.getAbsolutePath());
            cmd.add(new File(dir, "opencode").getAbsolutePath());
            cmd.add("web");
            cmd.add("--port");
            cmd.add(String.valueOf(PORT));
            cmd.add("--hostname");
            cmd.add("127.0.0.1");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(work);
            Map<String, String> env = pb.environment();
            env.put("HOME", home.getAbsolutePath());
            env.remove("LD_LIBRARY_PATH");
            env.put("OPENCODE_BRIDGE_TOKEN", DeviceBridgeService.getToken(this));
            env.put("OPENCODE_GRADLE_XMX", optimalGradleXmx());
            env.put("OPENCODE_D8_XMX", optimalD8Xmx());
            env.put("SSL_CERT_FILE", new File(dir, "ca-certificates.crt").getAbsolutePath());
            env.put("SSL_CERT_DIR", "");
            env.put("ANDROID_HOME", new File(home, "android-sdk").getAbsolutePath());
            env.put("ANDROID_SDK_ROOT", new File(home, "android-sdk").getAbsolutePath());
            env.put("JAVA_HOME", new File(home, "toolchain/usr/lib/jvm/java-21-openjdk").getAbsolutePath());
            env.put("ANDROID_USER_HOME", new File(home, ".android").getAbsolutePath());
            env.put("ANDROID_ADB_SERVER_PORT", "15037");
            File tmpDir = new File(home, "tmp");
            if (!tmpDir.exists()) tmpDir.mkdirs();
            env.put("TMPDIR", tmpDir.getAbsolutePath());
            env.put("GRADLE_USER_HOME", new File(home, ".gradle").getAbsolutePath());
            env.put("XDG_CONFIG_HOME", new File(home, ".config").getAbsolutePath());
            env.put("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
            env.put("XDG_CACHE_HOME", new File(home, ".cache").getAbsolutePath());
            env.put("ENV", new File(home, ".mkshrc").getAbsolutePath());
            String path = home.getAbsolutePath() + "/bin:"
                + home.getAbsolutePath() + "/toolchain/bin:"
                + home.getAbsolutePath() + "/toolchain/usr/bin:"
                + home.getAbsolutePath() + "/android-sdk/platform-tools:"
                + home.getAbsolutePath() + "/android-sdk/build-tools/35.0.0:"
                + home.getAbsolutePath() + "/android-sdk/build-tools/34.0.0:"
                + "/system/bin";
            env.put("PATH", path);
            env.put("SHELL", "/system/bin/sh");
            pb.redirectErrorStream(true);

            File sLog = new File(getFilesDir(), "server.log");
            synchronized (lifecycleLock) {
                server = pb.start();
                serverRunning = true;
                engineStarted = true;
            }
            updateNotification();

            final Process p = server;
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                     FileOutputStream out = new FileOutputStream(sLog, true)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.i(TAG, line);
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {}
                serverRunning = false;
                updateNotification();
            }, "OpenCode-ServerLog").start();
            Log.i(TAG, "server started");
        } catch (Exception e) {
            Log.e(TAG, "boot failed", e);
            fatalError = getString(R.string.boot_engine_launch_failed, String.valueOf(e.getMessage()));
        }
    }

    private File installServer() throws Exception {
        File dir = new File(getFilesDir(), "server");
        if (!dir.exists()) dir.mkdirs();
        File bin = new File(dir, "opencode");
        File loader = new File(dir, "ld-musl-aarch64.so.1");
        File runtimeVerMarker = new File(dir, ".runtime-version");
        File engineVerMarker = new File(dir, ".version");

        String expectedAppVer = String.valueOf(BuildConfig.VERSION_CODE);
        String expectedEngineVer = assetText("server/engine.version");

        // Fast-path: zero asset enumeration/IO when current engine & runtime libs are already seeded
        if (bin.canExecute() && loader.canExecute() && bin.length() >= 50000000L
            && expectedAppVer.equals(readMarker(runtimeVerMarker))
            && expectedEngineVer.equals(readMarker(engineVerMarker))) {
            return dir;
        }

        boolean refreshRuntime = !expectedAppVer.equals(readMarker(runtimeVerMarker));
        String[] libs = getAssets().list("server");
        if (libs == null || libs.length == 0) throw new RuntimeException("assets/server empty");
        for (String name : libs) {
            if (name.equals("engine.version") || name.equals("opencode")) continue;
            File dest = new File(dir, name);
            if (refreshRuntime || !dest.exists()) {
                copyAsset("server/" + name, dest);
            }
        }
        chmod(loader, 0755);
        for (String name : libs) {
            if (name.startsWith("ld-musl")) continue;
            if (name.endsWith(".so") || name.endsWith(".so.1")) chmod(new File(dir, name), 0644);
        }
        writeMarker(runtimeVerMarker, expectedAppVer);

        boolean needEngineCopy = !bin.exists() || bin.length() < 50000000L
            || !expectedEngineVer.equals(readMarker(engineVerMarker));
        if (needEngineCopy) {
            try {
                copyAsset("server/opencode", bin);
                chmod(bin, 0755);
                writeMarker(engineVerMarker, expectedEngineVer);
                Log.i(TAG, "engine seeded from assets: " + expectedEngineVer);
            } catch (Exception e) {
                Log.e(TAG, "engine seed error", e);
            }
        } else {
            chmod(bin, 0755);
        }

        if (!bin.exists() || bin.length() < 1000) {
            try {
                String latest = EngineFetcher.latestTag();
                EngineFetcher.fetch(bin, (done, total) -> setStatus(
                    total > 0
                        ? getString(R.string.boot_downloading_engine_total, done / 1048576, total / 1048576)
                        : getString(R.string.boot_downloading_engine, done / 1048576), 20));
                if (!latest.isEmpty()) writeMarker(engineVerMarker, latest);
            } catch (Exception e) {
                Log.e(TAG, "fetch fallback failed", e);
            }
        }
        chmod(bin, 0755);
        if (!bin.exists() || bin.length() < 1000) throw new RuntimeException("engine binary not available");
        return dir;
    }

    private void refreshWorkspaceConfig(File home) {
        try {
            File work = new File(Environment.getExternalStorageDirectory(), "OpenCode");
            if (!work.exists()) {
                try { work.mkdirs(); } catch (Exception ignored) {}
            }
            if (work.exists() && work.canWrite()) {
                copyAssetIfChanged("config/AGENTS.workspace.stub.md", new File(work, "AGENTS.md"));
                ensureSymlink(work, new File(home, "OpenCode"));
            }
        } catch (Exception ignored) {}
    }

    private void installPc() {
        File home = new File(getFilesDir(), "server-home");
        File assetsMarker = new File(home, ".assets-version");
        File pcBin = new File(home, "bin/pc");
        if (BuildConfig.VERSION_NAME.equals(readMarker(assetsMarker)) && pcBin.canExecute()) {
            refreshWorkspaceConfig(home);
            return;
        }
        try {
            File homeBin = new File(home, "bin");
            if (!homeBin.exists()) homeBin.mkdirs();

            File wrapDir = new File(homeBin, "wrap");
            if (!wrapDir.exists()) wrapDir.mkdirs();
            String[] wraps = new String[]{"java", "javac", "keytool", "git", "aapt2", "d8", "apksigner", "adb"};
            for (String w : wraps) {
                copyAssetIfChanged("bin/wrap/" + w, new File(wrapDir, w));
            }
            for (String b : new String[]{"pc", "rg", "open-factory-setup", "gradle-oc", "iconos", "opencode-maintenance"}) {
                copyAssetIfChanged("bin/" + b, new File(homeBin, b));
                chmod(new File(homeBin, b), 0755);
            }
            copyAssetIfChanged("bin/iconos.jar", new File(homeBin, "iconos.jar"));

            File tcBin = new File(home, "toolchain/bin");
            if (!tcBin.exists()) tcBin.mkdirs();
            copyAssetIfChanged("bin/gradle-oc", new File(tcBin, "gradle-oc"));
            chmod(new File(tcBin, "gradle-oc"), 0755);
            copyAssetIfChanged("bin/iconos", new File(tcBin, "iconos"));
            chmod(new File(tcBin, "iconos"), 0755);
            copyAssetIfChanged("bin/iconos.jar", new File(tcBin, "iconos.jar"));

            File tcUsrBin = new File(home, "toolchain/usr/bin");
            if (tcUsrBin.exists()) {
                copyAssetIfChanged("bin/gradle-oc", new File(tcUsrBin, "gradle-oc"));
                chmod(new File(tcUsrBin, "gradle-oc"), 0755);
            }

            for (String w : wraps) {
                copyAssetIfChanged("bin/wrap/" + w, new File(homeBin, w));
                chmod(new File(homeBin, w), 0755);
                copyAssetIfChanged("bin/wrap/" + w, new File(tcBin, w));
                chmod(new File(tcBin, w), 0755);
            }

            File gdir = new File(home, ".gradle");
            if (!gdir.exists()) gdir.mkdirs();
            upsertGradleProperties(new File(gdir, "gradle.properties"), home);

            String rcContent = "export HOME=\"" + home.getAbsolutePath() + "\"\n"
                + "export ANDROID_HOME=\"" + home.getAbsolutePath() + "/android-sdk\"\n"
                + "export ANDROID_SDK_ROOT=\"" + home.getAbsolutePath() + "/android-sdk\"\n"
                + "export JAVA_HOME=\"" + home.getAbsolutePath() + "/toolchain/usr/lib/jvm/java-21-openjdk\"\n"
                + "export ANDROID_USER_HOME=\"" + home.getAbsolutePath() + "/.android\"\n"
                + "export GRADLE_USER_HOME=\"" + home.getAbsolutePath() + "/.gradle\"\n"
                + "export PATH=\"" + home.getAbsolutePath() + "/bin:"
                + home.getAbsolutePath() + "/toolchain/bin:"
                + home.getAbsolutePath() + "/toolchain/usr/bin:"
                + home.getAbsolutePath() + "/android-sdk/platform-tools:"
                + home.getAbsolutePath() + "/android-sdk/build-tools/35.0.0:"
                + home.getAbsolutePath() + "/android-sdk/build-tools/34.0.0:/system/bin:${PATH:-}\"\n"
                + "alias gradle=\"gradle-oc\"\n";
            writeTextIfChanged(new File(home, ".mkshrc"), rcContent);
            writeTextIfChanged(new File(home, ".profile"), rcContent);
            writeTextIfChanged(new File(home, ".bashrc"), rcContent);
        } catch (Exception e) {
            Log.e(TAG, "bin install failed", e);
        }
        try {
            File cfg = new File(home, ".config/opencode");
            if (!cfg.exists()) cfg.mkdirs();
            copyAssetIfChanged("config/opencode.jsonc", new File(cfg, "opencode.jsonc"));
            copyAssetIfChanged("config/AGENTS.md", new File(cfg, "AGENTS.md"));
            File dup = new File(home, "AGENTS.md");
            if (dup.exists()) dup.delete();
            ensureMemory(cfg);

            File work = new File(Environment.getExternalStorageDirectory(), "OpenCode");
            if (work.exists()) {
                copyAssetIfChanged("config/AGENTS.workspace.stub.md", new File(work, "AGENTS.md"));
                ensureSymlink(work, new File(home, "OpenCode"));
            }

            File skills = new File(cfg, "skills/android-app-factory");
            if (!skills.exists()) skills.mkdirs();
            copyAssetIfChanged("skills/android-app-factory/SKILL.md", new File(skills, "SKILL.md"));

            File plugins = new File(cfg, "plugins");
            if (!plugins.exists()) plugins.mkdirs();
            copyAssetIfChanged("plugins/memory.js", new File(plugins, "memory.js"));
            writeMarker(new File(home, ".assets-version"), BuildConfig.VERSION_NAME);
        } catch (Exception e) {
            Log.e(TAG, "config install failed", e);
        }
    }

    private void upsertGradleProperties(File dest, File home) {
        String desiredXmx = optimalGradleXmx();
        List<String> required = Arrays.asList(
            "org.gradle.jvmargs=" + desiredXmx,
            "android.aapt2FromMavenOverride=" + home.getAbsolutePath() + "/toolchain/bin/aapt2",
            "android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE",
            "android.suppressUnsupportedCompileSdk=35"
        );
        try {
            byte[] existingBytes = dest.exists() ? fileBytes(dest) : null;
            String cur = existingBytes != null ? new String(existingBytes, StandardCharsets.UTF_8) : "";
            List<String> outLines = new ArrayList<>();
            if (!cur.isEmpty()) {
                for (String line : cur.split("\n")) {
                    if (line.startsWith("org.gradle.jvmargs=") || line.startsWith("android.aapt2FromMavenOverride=")) {
                        continue; // replaced with current device/RAM values
                    }
                    outLines.add(line);
                }
            }
            for (String req : required) {
                String key = req.substring(0, req.indexOf('=') + 1);
                boolean has = false;
                for (String line : outLines) {
                    if (line.startsWith(key)) { has = true; break; }
                }
                if (!has) outLines.add(req);
            }
            StringBuilder sb = new StringBuilder();
            for (String l : outLines) {
                if (!l.trim().isEmpty()) sb.append(l).append('\n');
            }
            writeTextIfChanged(dest, sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "gradle.properties upsert failed", e);
        }
    }

    private String assetText(String path) {
        try (InputStream in = getAssets().open(path)) {
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n > 0) return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        return "unknown";
    }

    private void copyAsset(String asset, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (Files.isSymbolicLink(dest.toPath())) dest.delete();
        try (InputStream in = getAssets().open(asset);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1048576];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private byte[] assetBytes(String path) throws Exception {
        try (InputStream in = getAssets().open(path);
             ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static byte[] fileBytes(File f) {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(f.length(), 1048576))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            return o.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Copy only when missing, symlink, or length/bytes differ. */
    private boolean copyAssetIfChanged(String asset, File dest) {
        try {
            byte[] want = assetBytes(asset);
            if (dest.exists() && !Files.isSymbolicLink(dest.toPath()) && dest.length() == want.length) {
                byte[] have = fileBytes(dest);
                if (have != null && Arrays.equals(have, want)) return false;
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (Files.isSymbolicLink(dest.toPath())) dest.delete();
            try (OutputStream out = new FileOutputStream(dest)) {
                out.write(want);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "asset update failed " + asset, e);
            return false;
        }
    }

    private boolean writeTextIfChanged(File dest, String text) {
        try {
            byte[] want = text.getBytes(StandardCharsets.UTF_8);
            if (dest.exists() && !Files.isSymbolicLink(dest.toPath()) && dest.length() == want.length) {
                byte[] have = fileBytes(dest);
                if (have != null && Arrays.equals(have, want)) return false;
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (Files.isSymbolicLink(dest.toPath())) dest.delete();
            try (FileOutputStream out = new FileOutputStream(dest)) {
                out.write(want);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "write failed " + dest, e);
            return false;
        }
    }

    private static boolean isValidJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (!(t.startsWith("{") && t.endsWith("}"))) return false;
        try {
            new org.json.JSONObject(t);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureMemory(File cfg) {
        try {
            File memDir = new File(cfg, "memory");
            if (!memDir.exists()) memDir.mkdirs();
            File mem = new File(memDir, "memory.json");
            File bak = new File(memDir, "memory.json.bak");
            if (!mem.exists()) {
                copyAsset("config/memory.json", mem);
                copyAsset("config/memory.json", bak);
                return;
            }
            byte[] mb = fileBytes(mem);
            String cur = mb != null ? new String(mb, StandardCharsets.UTF_8) : "";
            if (isValidJson(cur)) {
                try {
                    org.json.JSONObject curObj = new org.json.JSONObject(cur);
                    org.json.JSONObject seedObj = new org.json.JSONObject(
                        new String(assetBytes("config/memory.json"), StandardCharsets.UTF_8));
                    String seedVer = seedObj.optString("version", "");
                    String curVer = curObj.optString("version", "");
                    org.json.JSONObject curArch = curObj.optJSONObject("architecture");
                    boolean hasLegacyDevCtrl = curArch != null && curArch.has("device_control");
                    if (!seedVer.equals(curVer) || hasLegacyDevCtrl) {
                        curObj.put("version", seedVer);
                        curObj.put("identity", seedObj.optString("identity", curObj.optString("identity")));
                        curObj.put("environment", seedObj.optJSONObject("environment"));
                        curObj.put("architecture", seedObj.optJSONObject("architecture"));
                        curObj.put("capabilities", seedObj.optJSONArray("capabilities"));
                        if (!curObj.has("projects")) curObj.put("projects", new org.json.JSONObject());
                        cur = curObj.toString(2) + "\n";
                        writeTextIfChanged(mem, cur);
                    }
                } catch (Exception ignored) {}
                writeTextIfChanged(bak, cur);
                return;
            }
            Log.w(TAG, "memory.json invalid, restoring from backup");
            mem.renameTo(new File(memDir, "memory.json.corrupt-" + System.currentTimeMillis()));
            boolean restored = false;
            if (bak.exists()) {
                byte[] bb = fileBytes(bak);
                String b = bb != null ? new String(bb, StandardCharsets.UTF_8) : "";
                if (isValidJson(b)) {
                    writeTextIfChanged(mem, b);
                    restored = true;
                }
            }
            if (!restored) {
                copyAsset("config/memory.json", mem);
            }
        } catch (Exception e) {
            Log.e(TAG, "memory ensure failed", e);
        }
    }

    private static void chmod(File f, int mode) {
        if (f == null || !f.exists()) return;
        try {
            f.setReadable(true, false);
            if ((mode & 0111) != 0) f.setExecutable(true, false);
        } catch (Exception ignored) {}
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(new String[]{
                    "/system/bin/chmod", (mode & 0111) != 0 ? "755" : "644", f.getAbsolutePath()
                }).waitFor();
            } catch (Exception ignored) {}
        }
    }

    /** Extracts a GNU/USTAR tar (plain or gzip) safely using a single reusable I/O buffer (zero GC storm). */
    private void extractTar(InputStream raw, boolean gz, File dest) throws Exception {
        InputStream in = gz ? new GZIPInputStream(raw, 262144) : new java.io.BufferedInputStream(raw, 524288);
        byte[] h = new byte[512];
        byte[] ioBuf = new byte[262144];
        String pendingName = null;
        String pendingLink = null;
        String destRootCanon = dest.getCanonicalPath();
        String destCanon = destRootCanon + File.separator;
        int entryCount = 0;
        while (true) {
            if (readFully(in, h) < 512) break;
            if (isZeroBlock(h)) break;
            char type = (char) (h[156] & 0xFF);
            long size = octal(h, 124, 12);

            if (type == 'L' || type == 'K') {
                byte[] nb = new byte[(int) size];
                readFully(in, nb);
                String val = new String(nb, StandardCharsets.UTF_8).trim();
                if (type == 'L') pendingName = val;
                else pendingLink = val;
                skipPad(in, size, ioBuf);
                continue;
            }
            if (type == 'x' || type == 'g') {
                skipN(in, size, ioBuf);
                skipPad(in, size, ioBuf);
                continue;
            }

            String name = pendingName != null ? pendingName : cstr(h, 0, 100);
            String prefix = pendingName != null ? "" : cstr(h, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            String linkTarget = pendingLink != null ? pendingLink : cstr(h, 157, 100);
            pendingName = null;
            pendingLink = null;

            int mode = (int) octal(h, 100, 8);
            int slash = name.lastIndexOf('/');
            String base = slash >= 0 ? name.substring(slash + 1) : name;
            boolean skip = base.startsWith("._") || name.isEmpty() || ".".equals(name);
            File f = new File(dest, name);
            String fCanon = f.getCanonicalPath();
            boolean inside = fCanon.startsWith(destCanon) || fCanon.equals(destRootCanon);

            if (!skip && inside) {
                if (type == '5' || name.endsWith("/")) {
                    if (!f.exists()) f.mkdirs();
                } else if (type == '2') {
                    File p = f.getParentFile();
                    if (p != null && !p.exists()) p.mkdirs();
                    if (f.exists() || Files.isSymbolicLink(f.toPath())) f.delete();
                    try { Os.symlink(linkTarget, f.getAbsolutePath()); }
                    catch (Exception e) { Log.e(TAG, "symlink " + f, e); }
                } else if (type == '0' || type == 0 || type == ' ') {
                    File p = f.getParentFile();
                    if (p != null && !p.exists()) p.mkdirs();
                    if (Files.isSymbolicLink(f.toPath())) f.delete();
                    try (OutputStream o = new FileOutputStream(f)) {
                        copyN(in, o, size, ioBuf);
                    }
                    try { Os.chmod(f.getAbsolutePath(), mode & 0777); }
                    catch (Exception ignored) {}
                } else {
                    skipN(in, size, ioBuf);
                }
            } else {
                skipN(in, size, ioBuf);
            }
            skipPad(in, size, ioBuf);
            if ((++entryCount & 63) == 0) Thread.yield();
        }
    }

    private static int readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int n = 0;
        while (n < len && b[off + n] != 0) n++;
        return new String(b, off, n, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c >= '0' && c <= '7') v = (v << 3) + (c - '0');
            else if (c == 0 || c == ' ') break;
        }
        return v;
    }

    private static void copyN(InputStream in, OutputStream o, long n, byte[] buf) throws Exception {
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new RuntimeException("tar truncated");
            o.write(buf, 0, r);
            n -= r;
        }
    }

    private static void skipN(InputStream in, long n, byte[] buf) throws Exception {
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) break;
            n -= r;
        }
    }

    private static void skipPad(InputStream in, long size, byte[] buf) throws Exception {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skipN(in, pad, buf);
    }

    private static String readMarker(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n > 0) return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private static void writeMarker(File f, String v) {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(v.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    static boolean serverPortOpen() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 150);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean serverUp() {
        if (!serverPortOpen()) return false;
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + PORT + "/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Notification buildNotification() {
        String ch = "opencode";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(ch, "OpenCode",
                NotificationManager.IMPORTANCE_LOW);
            chan.setShowBadge(false);
            nm.createNotificationChannel(chan);
        }

        int pFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent appIntent = new Intent(this, MainActivity.class);
        PendingIntent appPending = PendingIntent.getActivity(this, 0, appIntent, pFlags);

        Intent exitIntent = new Intent(this, OpenCodeService.class).setAction(ACTION_STOP_SERVICE);
        PendingIntent exitPending = PendingIntent.getService(this, 1, exitIntent, pFlags);

        Intent toggle247Intent = new Intent(this, OpenCodeService.class).setAction(ACTION_TOGGLE_24_7);
        PendingIntent toggle247Pending = PendingIntent.getService(this, 2, toggle247Intent, pFlags);

        String sub = bootStage;
        if (factoryReady && serverRunning) {
            sub = getString(is24_7 ? R.string.notif_running_24_7 : R.string.notif_running_local);
        }

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, ch) : new Notification.Builder(this);

        b.setContentTitle(getString(R.string.app_name))
            .setContentText(sub)
            .setSmallIcon(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()))
            .setContentIntent(appPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false);

        String toggleLabel = getString(is24_7 ? R.string.notif_action_24_7_on : R.string.notif_action_24_7_off);
        String exitLabel = getString(R.string.notif_action_exit);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            b.addAction(new Notification.Action.Builder(null, toggleLabel, toggle247Pending).build());
            b.addAction(new Notification.Action.Builder(null, exitLabel, exitPending).build());
        } else {
            b.addAction(0, toggleLabel, toggle247Pending);
            b.addAction(0, exitLabel, exitPending);
        }

        return b.build();
    }

    @Override
    public void onDestroy() {
        stopServer();
        DeviceBridgeService.stopBridge();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
