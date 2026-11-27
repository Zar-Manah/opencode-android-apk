package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OpenCode: Authenticated local device control bridge at 127.0.0.1:4399 for `pc` CLI. */
public class DeviceBridgeService {

    private static final String TAG = "DeviceBridge";
    private static final int PORT = 4399;
    private static final int MAX_HEADER_BYTES = 16384;

    private static volatile ServerSocket server;
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static volatile Context appContext;
    private static volatile String bridgeToken;

    static synchronized String getToken(Context c) {
        if (bridgeToken == null) {
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : rnd) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            bridgeToken = hex.toString();
        }
        if (c != null) {
            try {
                File home = new File(c.getFilesDir(), "server-home");
                if (!home.exists()) home.mkdirs();
                File tokFile = new File(home, ".bridge-token");
                try (FileOutputStream out = new FileOutputStream(tokFile)) {
                    out.write(bridgeToken.getBytes(StandardCharsets.UTF_8));
                }
                Os.chmod(tokFile.getAbsolutePath(), 0600);
            } catch (Exception ignored) {}
        }
        return bridgeToken;
    }

    static boolean isOn() {
        return server != null && !server.isClosed();
    }

    public static synchronized void startBridge(Context c) {
        if (c != null && appContext == null) {
            appContext = c.getApplicationContext();
        }
        getToken(appContext != null ? appContext : c);
        if (server != null && !server.isClosed()) return;
        Thread t = new Thread(DeviceBridgeService::serve, "OpenCode-Bridge");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stopBridge() {
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {}
            server = null;
        }
    }

    private static Context getCtx() {
        if (appContext == null) throw new RuntimeException("Bridge context not ready");
        return appContext;
    }

    private static void serve() {
        try {
            if (server != null && !server.isClosed()) return;
            server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "Bridge server started on 127.0.0.1:" + PORT);
            while (server != null && !server.isClosed()) {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        } catch (Exception e) {
            if (server != null) {
                Log.e(TAG, "Bridge server stopped", e);
            }
        }
    }

    private static boolean isAuthorized(String supplied) {
        if (supplied == null || bridgeToken == null) return false;
        return MessageDigest.isEqual(
            supplied.trim().getBytes(StandardCharsets.UTF_8),
            bridgeToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            ByteArrayOutputStream hdrBuf = new ByteArrayOutputStream();
            byte[] win = new byte[4];
            int total = 0;
            int b;
            while (total < MAX_HEADER_BYTES && (b = in.read()) != -1) {
                hdrBuf.write(b);
                total++;
                win[0] = win[1];
                win[1] = win[2];
                win[2] = win[3];
                win[3] = (byte) b;
                if (total >= 4 && win[0] == '\r' && win[1] == '\n' && win[2] == '\r' && win[3] == '\n') {
                    break;
                }
            }
            if (total == 0) return;

            String req = new String(hdrBuf.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = req.split("\r\n");
            if (lines.length == 0) return;
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) return;

            String tokenHdr = null;
            for (int i = 1; i < lines.length; i++) {
                int col = lines[i].indexOf(':');
                if (col > 0) {
                    String name = lines[i].substring(0, col).trim();
                    if ("X-Bridge-Token".equalsIgnoreCase(name)) {
                        tokenHdr = lines[i].substring(col + 1).trim();
                        break;
                    }
                }
            }

            if (!isAuthorized(tokenHdr)) {
                byte[] denied = err("unauthorized: missing or invalid X-Bridge-Token");
                writeResponse(out, 403, "application/json", denied);
                return;
            }

            String path = parts[1];
            String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            Map<String, String> q = query(path);
            byte[] body;
            String type = "application/json";
            int code = 200;

            try {
                switch (route) {
                    case "/ping": {
                        body = new JSONObject()
                            .put("ok", true)
                            .put("status", "ready")
                            .put("screenCapture", true)
                            .toString().getBytes(StandardCharsets.UTF_8);
                        break;
                    }
                    case "/open":
                        open(str(q, "p"));
                        body = ok("open");
                        break;
                    case "/notify":
                        notify(str(q, "m"), str(q, "t"));
                        body = ok("notify");
                        break;
                    case "/install":
                        install(str(q, "p"));
                        body = installResult().getBytes(StandardCharsets.UTF_8);
                        break;
                    case "/shell":
                        body = shell(str(q, "c")).getBytes(StandardCharsets.UTF_8);
                        type = "text/plain";
                        break;
                    case "/list":
                        body = apps(str(q, "f")).getBytes(StandardCharsets.UTF_8);
                        break;
                    case "/shot":
                        body = shot();
                        if (body.length == 0) {
                            code = 501;
                            body = err("screen capture cancelled or unavailable");
                        } else {
                            type = "image/png";
                        }
                        break;
                    default:
                        code = 404;
                        body = err("unknown route");
                        break;
                }
            } catch (Exception e) {
                code = 500;
                body = err(e.getMessage());
            }
            writeResponse(out, code, type, body);
        } catch (Exception ignored) {}
    }

    private static void writeResponse(OutputStream out, int code, String type, byte[] body) throws Exception {
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 400: status = "Bad Request"; break;
            case 403: status = "Forbidden"; break;
            case 404: status = "Not Found"; break;
            case 500: status = "Internal Server Error"; break;
            case 501: status = "Not Implemented"; break;
            default: status = "Error"; break;
        }
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static byte[] ok(String s) {
        try {
            return new JSONObject().put("ok", true).put("r", s).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] err(String s) {
        try {
            return new JSONObject().put("ok", false).put("error", s != null ? s : "unknown error")
                .toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"error\"}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> query(String path) {
        Map<String, String> m = new HashMap<>();
        int i = path.indexOf('?');
        if (i < 0) return m;
        for (String kv : path.substring(i + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) {
                try {
                    m.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                } catch (Exception ignored) {}
            }
        }
        return m;
    }

    private static int num(Map<String, String> q, String k) { return num(q, k, 0); }

    private static int num(Map<String, String> q, String k, int d) {
        try { return Integer.parseInt(q.get(k)); } catch (Exception e) { return d; }
    }

    private static String str(Map<String, String> q, String k) {
        String v = q.get(k);
        return v == null ? "" : v;
    }

    private static void open(String p) {
        if (p == null || p.isEmpty()) throw new RuntimeException("missing package or URL");
        Context ctx = getCtx();
        Intent i;
        if (p.startsWith("http://") || p.startsWith("https://") || p.contains("://")) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(p));
        } else {
            i = ctx.getPackageManager().getLaunchIntentForPackage(p);
            if (i == null) throw new RuntimeException("no launchable app " + p);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private static String shell(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "(empty)";
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1 && total < 200000) {
                o.write(buf, 0, n);
                total += n;
            }
            boolean done = CompatUtils.waitForProcess(p, 120000L);
            if (!done) {
                CompatUtils.destroyForciblyCompat(p);
                return "(timeout)\n" + o.toString("UTF-8");
            }
            return "exit=" + p.exitValue() + "\n" + o.toString("UTF-8");
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static void install(String apkPath) {
        Context ctx = getCtx();
        File apk = new File(apkPath);
        if (!apk.exists()) throw new RuntimeException("no apk found at " + apkPath);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                throw new RuntimeException("REQUEST_INSTALL_PACKAGES permission not granted. Opened Settings.");
            }
        }

        File res = new File(ctx.getFilesDir(), "server-home/last-install.txt");
        try {
            if (res.exists()) res.delete();
        } catch (Exception ignored) {}

        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            } catch (Throwable ignored) {}
        }

        PackageInstaller.Session s = null;
        try {
            int id = pi.createSession(params);
            s = pi.openSession(id);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("apk", 0, apk.length())) {
                byte[] buf = new byte[262144];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                s.fsync(out);
            }

            Intent intent = new Intent(ctx, InstallResultReceiver.class);
            intent.setAction("com.opencode.android.ACTION_INSTALL_COMMIT");
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= android.app.PendingIntent.FLAG_MUTABLE;
            }
            android.app.PendingIntent pi2 = android.app.PendingIntent.getBroadcast(ctx, id, intent, flags);
            s.commit(pi2.getIntentSender());
            s.close();
            s = null;
            Log.i(TAG, "Install session " + id + " committed for " + apkPath);
        } catch (Exception e) {
            if (s != null) {
                try { s.abandon(); } catch (Exception ignored) {}
            }
            Log.e(TAG, "PackageInstaller session error: " + e.getMessage(), e);
            throw new RuntimeException("install: " + e.getMessage());
        }
    }

    private static String installResult() {
        Context ctx = getCtx();
        try {
            File res = new File(ctx.getFilesDir(), "server-home/last-install.txt");
            long t = System.currentTimeMillis();
            while (!res.exists() && System.currentTimeMillis() - t < 120000) {
                Thread.sleep(500);
            }
            if (!res.exists()) {
                return "{\"ok\":false,\"error\":\"install-timeout\"}";
            }
            byte[] rawBytes = CompatUtils.readFileBytes(res);
            String raw = new String(rawBytes, StandardCharsets.UTF_8).trim();

            int status = -999;
            String pkg = null;
            String msg = null;
            for (String part : raw.split(" ")) {
                if (part.startsWith("status=")) {
                    try { status = Integer.parseInt(part.substring(7)); } catch (Exception ignored) {}
                } else if (part.startsWith("pkg=")) {
                    pkg = part.substring(4);
                } else if (part.startsWith("msg=")) {
                    msg = part.substring(4);
                }
            }
            boolean ok = (status == 0);
            JSONObject json = new JSONObject();
            json.put("ok", ok);
            json.put("status", status);
            if (pkg != null && !pkg.isEmpty()) json.put("pkg", pkg);
            if (msg != null && !msg.isEmpty()) json.put("msg", msg);
            return json.toString();
        } catch (Exception e) {
            return new String(err(e.getMessage()), StandardCharsets.UTF_8);
        }
    }

    private static void notify(String msg, String title) {
        Context ctx = getCtx();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String ch = "pc";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(ch, "OpenCode Alerts",
                NotificationManager.IMPORTANCE_DEFAULT));
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(ctx, ch) : new Notification.Builder(ctx);
        b.setContentText(msg).setSmallIcon(android.R.drawable.ic_dialog_info);
        if (title != null && !title.isEmpty()) b.setContentTitle(title);
        nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
    }

    private static String apps(String filter) {
        Context ctx = getCtx();
        PackageManager pm = ctx.getPackageManager();
        JSONArray arr = new JSONArray();
        List<ApplicationInfo> list = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo a : list) {
            String label = String.valueOf(pm.getApplicationLabel(a));
            if (filter != null && !filter.isEmpty()
                && !a.packageName.contains(filter) && !label.contains(filter)) continue;
            try {
                arr.put(new JSONObject().put("pkg", a.packageName).put("label", label));
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    private static byte[] shot() {
        Context ctx = getCtx();
        try {
            byte[] png = ScreenCaptureService.capturePng(ctx);
            return png != null ? png : new byte[0];
        } catch (Exception ignored) {
            return new byte[0];
        }
    }
}
