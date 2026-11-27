package com.opencode.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** OpenCode: Universal Android WebView host with live startup progress and safe external handling. */
public class MainActivity extends Activity {

    private static final String TAG = "OpenCodeMain";
    static final String SERVER_URL = "http://127.0.0.1:4096/";

    private WebView web;
    private View splash;
    private ProgressBar progressBar;
    private TextView progressLabel;
    private volatile boolean loaded;
    private volatile boolean splashDismissed;
    private ValueCallback<Uri[]> fileCallback;
    private final AtomicBoolean waitingForServer = new AtomicBoolean(false);
    private volatile String lastChatUrl = null;
    private volatile String cachedMobileScript = null;
    private volatile String cachedWorkspacePath = null;

    private static String encodeDirSlug(String path) {
        if (path == null || path.isEmpty()) return "";
        try {
            return android.util.Base64.encodeToString(path.getBytes(StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isArm64Supported() {
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private File workspaceDir() {
        File ext = new File(Environment.getExternalStorageDirectory(), "OpenCode");
        if (ext.exists() || ext.mkdirs()) {
            if (ext.canWrite()) return ext;
        }
        File priv = new File(getFilesDir(), "server-home/OpenCode");
        if (!priv.exists()) priv.mkdirs();
        return priv;
    }

    public class AndroidBridge {
        @JavascriptInterface
        public String listProjects() {
            try {
                File dir = workspaceDir();
                File[] files = dir.listFiles();
                JSONArray arr = new JSONArray();
                if (files != null) {
                    Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (File f : files) {
                        if (f.isDirectory() && !f.getName().startsWith(".")) {
                            JSONObject o = new JSONObject();
                            o.put("name", f.getName());
                            o.put("path", f.getAbsolutePath());
                            arr.put(o);
                        }
                    }
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String createProject(final String name) {
            try {
                String n = (name == null || name.trim().isEmpty())
                    ? "project-" + (System.currentTimeMillis() % 10000) : name.trim();
                String clean = n.replaceAll("[^a-zA-Z0-9._-]", "-").replaceAll("^\\.+", "");
                if (clean.isEmpty()) clean = "project-" + (System.currentTimeMillis() % 10000);
                final String finalName = clean;
                final File pDir = new File(workspaceDir(), finalName);
                new Thread(() -> {
                    try {
                        if (!pDir.exists()) {
                            pDir.mkdirs();
                            File readme = new File(pDir, "README.md");
                            try (FileOutputStream fos = new FileOutputStream(readme)) {
                                fos.write(("# " + finalName + "\n\nCreated with OpenCode on Android.\n")
                                    .getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        openDirSessionAsync(pDir, finalName, false);
                    } catch (Exception e) {
                        notifyProjectError(e.getMessage());
                    }
                }, "OpenCode-CreateProj").start();
                return new JSONObject().put("started", true).put("name", finalName).toString();
            } catch (Exception e) {
                return "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
            }
        }

        @JavascriptInterface
        public String openProject(final String name) {
            try {
                if (name == null || name.trim().isEmpty()) {
                    return "{\"error\":\"empty project name\"}";
                }
                final File pDir = new File(workspaceDir(), name.trim());
                new Thread(() -> {
                    try {
                        if (!pDir.exists()) pDir.mkdirs();
                        openDirSessionAsync(pDir, name.trim(), true);
                    } catch (Exception e) {
                        notifyProjectError(e.getMessage());
                    }
                }, "OpenCode-OpenProj").start();
                return new JSONObject().put("started", true).put("name", name.trim()).toString();
            } catch (Exception e) {
                return "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
            }
        }

        @JavascriptInterface
        public void openDeviceControl() {
            startActivity(new Intent(MainActivity.this, WirelessPairActivity.class));
        }

        @JavascriptInterface
        public String getRuntimeStatus() {
            try {
                return new JSONObject()
                    .put("factoryReady", OpenCodeService.factoryReady)
                    .put("adbConnected", LocalAdbEngine.hasActiveSerial())
                    .put("progress", OpenCodeService.bootProgress)
                    .put("stage", OpenCodeService.bootStage)
                    .toString();
            } catch (Exception e) {
                return "{\"factoryReady\":true}";
            }
        }

        @JavascriptInterface
        public void notifyUiReady() {
            OpenCodeService.uiFirstPaintReady = true;
            runOnUiThread(MainActivity.this::dismissSplashOnce);
        }

        private void notifyProjectError(final String msg) {
            Log.e(TAG, "Project error: " + msg);
            runOnUiThread(() -> {
                if (web != null) {
                    web.evaluateJavascript(
                        "window._ocOnProjectError && window._ocOnProjectError("
                            + JSONObject.quote(msg != null ? msg : "Failed to open project") + ");",
                        null);
                }
            });
        }

        private void openDirSessionAsync(File pDir, String clean, boolean preferExisting) throws Exception {
            String encodedDir = URLEncoder.encode(pDir.getAbsolutePath(), "UTF-8");
            String id = null;
            if (preferExisting) {
                try {
                    String listRes = http("http://127.0.0.1:4096/session?directory=" + encodedDir, null);
                    JSONArray arr = new JSONArray(listRes);
                    long bestTime = -1;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject s = arr.getJSONObject(i);
                        long t = s.optJSONObject("time") != null
                            ? s.optJSONObject("time").optLong("updated", 0) : 0;
                        if (t >= bestTime) {
                            bestTime = t;
                            id = s.optString("id", null);
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (id == null || id.isEmpty()) {
                JSONObject payload = new JSONObject().put("title", "Chat");
                Exception lastErr = null;
                for (int attempt = 0; attempt < 15; attempt++) {
                    try {
                        String sessRes = http("http://127.0.0.1:4096/session?directory=" + encodedDir,
                            payload.toString());
                        JSONObject obj = new JSONObject(sessRes);
                        id = obj.getString("id");
                        break;
                    } catch (Exception e) {
                        lastErr = e;
                        try { Thread.sleep(350); } catch (InterruptedException ie) { break; }
                    }
                }
                if (id == null || id.isEmpty()) {
                    throw lastErr != null ? lastErr : new RuntimeException("Could not open session");
                }
            }

            String dirSlug = encodeDirSlug(pDir.getAbsolutePath());
            final String targetUrl = "http://127.0.0.1:4096/" + dirSlug + "/session/" + id;
            lastChatUrl = targetUrl;
            runOnUiThread(() -> {
                if (web != null) {
                    web.evaluateJavascript(
                        "window._ocOnProjectReady && window._ocOnProjectReady();"
                            + "window.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true}));",
                        null);
                    web.postDelayed(() -> {
                        if (web != null) web.loadUrl(targetUrl);
                    }, 40);
                }
            });
        }
    }

    private String getMobileScript() {
        String workPath = workspaceDir().getAbsolutePath();
        if (cachedMobileScript != null && workPath.equals(cachedWorkspacePath)) {
            return cachedMobileScript;
        }
        try (InputStream in = getAssets().open("mobile-enhancer.js");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            String workJson = JSONObject.quote(workPath);
            cachedWorkspacePath = workPath;
            cachedMobileScript = "window._ocWorkspace=" + workJson + ";\n"
                + new String(out.toByteArray(), StandardCharsets.UTF_8);
            return cachedMobileScript;
        } catch (Exception e) {
            return "";
        }
    }

    private void injectMobileEnhancements() {
        if (web == null) return;
        String js = getMobileScript();
        if (!js.isEmpty()) web.evaluateJavascript(js, null);
    }

    private WebResourceResponse handleIntercept(Uri uri) {
        if (uri == null || uri.getPath() == null) return null;
        String path = uri.getPath();
        File workDir = workspaceDir();
        String workPath = workDir.getAbsolutePath();

        if (path.equals("/path")) {
            try {
                String fullUrl = "http://127.0.0.1:4096/path" + (uri.getQuery() != null ? "?" + uri.getQuery() : "");
                HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream is = conn.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                } finally {
                    conn.disconnect();
                }
                JSONObject json = new JSONObject(bos.toString("UTF-8"));
                json.put("home", workPath);
                String curDir = json.optString("directory", "");
                if (curDir.isEmpty() || curDir.contains("server-home")) json.put("directory", workPath);
                String curWt = json.optString("worktree", "");
                if (curWt.isEmpty() || curWt.contains("server-home")) json.put("worktree", workPath);
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(data));
            } catch (Exception ignored) {}
        }

        if (path.equals("/file")) {
            String dirParam = uri.getQueryParameter("directory");
            if (dirParam == null || dirParam.isEmpty() || dirParam.equals(workPath)
                || dirParam.endsWith("/OpenCode") || dirParam.contains("server-home") || dirParam.equals("~")) {
                try {
                    File[] subFiles = workDir.listFiles();
                    JSONArray arr = new JSONArray();
                    if (subFiles != null) {
                        for (File f : subFiles) {
                            if (f.isDirectory() && !f.getName().startsWith(".")
                                && !f.getName().equals("android-sdk") && !f.getName().equals("toolchain")) {
                                JSONObject itm = new JSONObject();
                                itm.put("name", f.getName());
                                itm.put("path", f.getAbsolutePath());
                                itm.put("type", "directory");
                                arr.put(itm);
                            }
                        }
                    }
                    byte[] data = arr.toString().getBytes(StandardCharsets.UTF_8);
                    return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(data));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private void showUnsupportedAbiScreen() {
        LinearLayout errLayout = new LinearLayout(this);
        errLayout.setOrientation(LinearLayout.VERTICAL);
        errLayout.setBackgroundColor(Color.BLACK);
        errLayout.setGravity(Gravity.CENTER);
        int pad = (int) (28 * getResources().getDisplayMetrics().density);
        errLayout.setPadding(pad, pad, pad, pad);

        TextView t = new TextView(this);
        t.setText(R.string.unsupported_abi_title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        errLayout.addView(t);

        TextView msg = new TextView(this);
        msg.setText(getString(R.string.unsupported_abi_msg, Arrays.toString(Build.SUPPORTED_ABIS)));
        msg.setTextColor(0xFF9CA3AF);
        msg.setPadding(0, 16, 0, 0);
        errLayout.addView(msg);
        setContentView(errLayout);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        if (!isArm64Supported()) {
            showUnsupportedAbiScreen();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
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

        // Subclass WebView so every Android IME Enter / Action / sendKeyEvent / commitText(\n) reaches the active project input safely on the UI thread
        web = new WebView(this) {
            private void triggerImeEnterOnUi() {
                runOnUiThread(() -> {
                    if (web != null && !isFinishing() && !isDestroyed()) {
                        web.evaluateJavascript(
                            "window._ocHandleImeEnter && window._ocHandleImeEnter()",
                            res -> Log.d(TAG, "IME enter handled=" + res));
                    }
                });
            }

            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                final InputConnection base = super.onCreateInputConnection(outAttrs);
                if (base == null) return null;
                return new InputConnectionWrapper(base, true) {
                    @Override
                    public boolean performEditorAction(int editorAction) {
                        Log.d(TAG, "IME performEditorAction=" + editorAction);
                        triggerImeEnterOnUi();
                        return super.performEditorAction(editorAction);
                    }

                    @Override
                    public boolean sendKeyEvent(KeyEvent event) {
                        if (event != null && event.getAction() == KeyEvent.ACTION_UP
                            && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            Log.d(TAG, "IME sendKeyEvent Enter");
                            triggerImeEnterOnUi();
                        }
                        return super.sendKeyEvent(event);
                    }

                    @Override
                    public boolean commitText(CharSequence text, int newCursorPosition) {
                        if (text != null && text.toString().indexOf('\n') >= 0) {
                            Log.d(TAG, "IME commitText newline");
                            triggerImeEnterOnUi();
                        }
                        return super.commitText(text, newCursorPosition);
                    }
                };
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event != null && event.getAction() == KeyEvent.ACTION_UP
                    && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    triggerImeEnterOnUi();
                }
                return super.dispatchKeyEvent(event);
            }
        };
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 OpenCode-Android");

        web.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(i);
            } catch (Exception ignored) {}
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                return handleExternalLink(uri);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalLink(url != null ? Uri.parse(url) : null);
            }

            private boolean handleExternalLink(Uri uri) {
                if (uri == null) return false;
                String host = uri.getHost();
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                    return false;
                }
                try {
                    Intent ext = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(ext);
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    WebResourceResponse res = handleIntercept(request.getUrl());
                    if (res != null) return res;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null) {
                    WebResourceResponse res = handleIntercept(Uri.parse(url));
                    if (res != null) return res;
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("/session/")) lastChatUrl = url;
                injectMobileEnhancements();
                if (web != null) {
                    web.postDelayed(MainActivity.this::injectMobileEnhancements, 400);
                    if (!splashDismissed) {
                        web.postDelayed(MainActivity.this::dismissSplashOnce, 1800);
                    }
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (url != null && url.contains("/session/")) lastChatUrl = url;
                injectMobileEnhancements();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null && cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(TAG, "WebError L" + cm.lineNumber() + ": " + cm.message());
                }
                return super.onConsoleMessage(cm);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(i, null), 1001);
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Sleek splash screen with live extraction/startup progress bar
        float d = getResources().getDisplayMetrics().density;
        LinearLayout splashWrap = new LinearLayout(this);
        splashWrap.setOrientation(LinearLayout.VERTICAL);
        splashWrap.setBackgroundColor(Color.BLACK);
        splashWrap.setGravity(Gravity.CENTER);
        int sidePad = (int) (40 * d);
        splashWrap.setPadding(sidePad, sidePad, sidePad, sidePad);

        ImageView icon = new ImageView(this);
        icon.setImageResource(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()));
        int iconSize = (int) (96 * d);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.bottomMargin = (int) (20 * d);
        splashWrap.addView(icon, iconLp);

        TextView brand = new TextView(this);
        brand.setText("OpenCode");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(22);
        brand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(-2, -2);
        brandLp.bottomMargin = (int) (20 * d);
        splashWrap.addView(brand, brandLp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        GradientDrawable progBg = new GradientDrawable();
        progBg.setColor(0xFF1F1F24);
        progBg.setCornerRadius(4 * d);
        GradientDrawable progFillDraw = new GradientDrawable();
        progFillDraw.setColor(0xFFFAFAFA);
        progFillDraw.setCornerRadius(4 * d);
        ClipDrawable progFill = new ClipDrawable(progFillDraw, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{progBg, progFill});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        progressBar.setProgressDrawable(layers);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams((int) (220 * d), (int) (4 * d));
        barLp.bottomMargin = (int) (12 * d);
        splashWrap.addView(progressBar, barLp);

        progressLabel = new TextView(this);
        String curStage = OpenCodeService.bootStage;
        progressLabel.setText(curStage != null && !curStage.isEmpty() ? curStage : getString(R.string.boot_starting));
        progressLabel.setTextColor(0xFFA1A1AA);
        progressLabel.setTextSize(13);
        progressLabel.setGravity(Gravity.CENTER);
        splashWrap.addView(progressLabel, new LinearLayout.LayoutParams(-2, -2));

        root.addView(splashWrap, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        splash = splashWrap;

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBackAction);
        }

        if (SetupActivity.needed(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        startOpenCodeService();
        waitForServer();
    }

    private void startOpenCodeService() {
        try {
            Intent svc = new Intent(this, OpenCodeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start OpenCodeService", e);
        }
    }

    private void dismissSplashOnce() {
        if (splashDismissed) return;
        splashDismissed = true;
        OpenCodeService.uiFirstPaintReady = true;
        if (splash != null) splash.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isArm64Supported()) return;
        if (SetupActivity.needed(this)) {
            if (progressLabel != null) {
                progressLabel.setText(R.string.tap_complete_setup);
            }
            if (splash != null) {
                splash.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
            }
            return;
        }
        if (splash != null) splash.setOnClickListener(null);
        if (!loaded || !OpenCodeService.engineStarted) {
            startOpenCodeService();
        }
        if (!loaded) waitForServer();
    }

    private void waitForServer() {
        if (loaded) return;
        if (!waitingForServer.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                while (!loaded && !isFinishing()) {
                    final String stage = OpenCodeService.fatalError != null
                        ? OpenCodeService.fatalError
                        : OpenCodeService.bootStage;
                    final int pct = OpenCodeService.bootProgress;
                    runOnUiThread(() -> {
                        if (progressBar != null) progressBar.setProgress(pct);
                        if (progressLabel != null) progressLabel.setText(stage);
                    });

                    if (OpenCodeService.engineStarted && serverUp()) {
                        loaded = true;
                        final String url = resolveChatUrl();
                        lastChatUrl = url;
                        runOnUiThread(() -> {
                            web.loadUrl(url);
                            web.postDelayed(this::dismissSplashOnce, 3200);
                        });
                        return;
                    }
                    try { Thread.sleep(120); } catch (InterruptedException ignored) { return; }
                }
            } finally {
                waitingForServer.set(false);
            }
        }, "OpenCode-Wait").start();
    }

    private String resolveChatUrl() {
        try {
            File workDir = workspaceDir();
            if (!workDir.exists()) workDir.mkdirs();

            JSONArray allSessions = fetchJsonArrayWithRetry("http://127.0.0.1:4096/session", 12, 250);
            if (allSessions != null) {
                String bestId = null;
                String bestDir = null;
                long bestTime = -1;
                for (int i = 0; i < allSessions.length(); i++) {
                    JSONObject s = allSessions.getJSONObject(i);
                    String dir = s.optString("directory", null);
                    if (dir == null || !isUsableProjectDir(new File(dir), workDir)) continue;
                    long t = s.optJSONObject("time") != null
                        ? s.optJSONObject("time").optLong("updated", 0) : 0;
                    if (t >= bestTime) {
                        bestTime = t;
                        bestId = s.optString("id", null);
                        bestDir = dir;
                    }
                }
                if (bestId != null && bestDir != null) {
                    String slug = encodeDirSlug(bestDir);
                    if (!slug.isEmpty()) {
                        return "http://127.0.0.1:4096/" + slug + "/session/" + bestId;
                    }
                }
            }

            File projDir = findOrCreateInitialProjectDir(workDir);
            String projPath = projDir.getAbsolutePath();
            String slug = encodeDirSlug(projPath);
            String sessId = ensureSessionForDirectory(projPath);
            if (sessId != null && !slug.isEmpty()) {
                return "http://127.0.0.1:4096/" + slug + "/session/" + sessId;
            }
            if (!slug.isEmpty()) {
                return "http://127.0.0.1:4096/" + slug;
            }
            return SERVER_URL;
        } catch (Exception ignored) {
            return SERVER_URL;
        }
    }

    private JSONArray fetchJsonArrayWithRetry(String url, int maxAttempts, long delayMs) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (isFinishing() || isDestroyed()) return null;
            try {
                String raw = http(url, null);
                if (raw != null && raw.trim().startsWith("[")) {
                    return new JSONArray(raw);
                }
            } catch (Exception ignored) {
            }
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    private static boolean isUsableProjectDir(File dir, File workDir) {
        if (dir == null || !dir.isDirectory()) return false;
        String path = dir.getAbsolutePath();
        if ("/".equals(path) || path.equals(workDir.getAbsolutePath()) || path.contains("server-home")) {
            return false;
        }
        String name = dir.getName();
        return !name.isEmpty()
            && !name.startsWith(".")
            && !"tmp".equals(name)
            && !"tools".equals(name)
            && !"android-sdk".equals(name)
            && !"toolchain".equals(name);
    }

    private File findOrCreateInitialProjectDir(File workDir) {
        File latest = null;
        long latestMod = Long.MIN_VALUE;
        File[] children = workDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!isUsableProjectDir(child, workDir)) continue;
                long mod = child.lastModified();
                if (mod >= latestMod) {
                    latestMod = mod;
                    latest = child;
                }
            }
        }
        if (latest != null) return latest;

        String defaultName = getString(R.string.default_project_dir).trim();
        if (defaultName.isEmpty()) defaultName = "initial-project";
        File initialDir = new File(workDir, defaultName);
        if (!initialDir.exists()) initialDir.mkdirs();
        File readme = new File(initialDir, "README.md");
        if (!readme.exists()) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(readme)) {
                String content = "# " + initialDir.getName() + "\n\nCreated with OpenCode on Android.\n";
                out.write(content.getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return initialDir;
    }

    private String ensureSessionForDirectory(String projPath) {
        try {
            String encodedDir = java.net.URLEncoder.encode(projPath, "UTF-8");
            String queryUrl = "http://127.0.0.1:4096/session?directory=" + encodedDir;

            JSONArray existing = fetchJsonArrayWithRetry(queryUrl, 3, 200);
            if (existing != null && existing.length() > 0) {
                String bestId = null;
                long bestTime = -1;
                for (int i = 0; i < existing.length(); i++) {
                    JSONObject s = existing.getJSONObject(i);
                    long t = s.optJSONObject("time") != null
                        ? s.optJSONObject("time").optLong("updated", 0) : 0;
                    if (t >= bestTime) {
                        bestTime = t;
                        bestId = s.optString("id", null);
                    }
                }
                if (bestId != null && !bestId.isEmpty()) return bestId;
            }

            for (int attempt = 0; attempt < 12; attempt++) {
                if (isFinishing() || isDestroyed()) return null;
                try {
                    String created = http(queryUrl, "{\"title\":\"Chat\"}");
                    JSONObject s = new JSONObject(created);
                    String id = s.optString("id", null);
                    if (id != null && !id.isEmpty()) return id;
                } catch (Exception ignored) {
                }
                try { Thread.sleep(300); } catch (InterruptedException e) { return null; }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String http(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream in = stream) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
                }
            }
            String txt = new String(o.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new IOException("HTTP " + code + " from " + url + ": " + txt);
            }
            return txt;
        } finally {
            c.disconnect();
        }
    }

    private static boolean serverUp() {
        return OpenCodeService.serverUp();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && fileCallback != null) {
            Uri[] uris = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    uris = new Uri[n];
                    for (int i = 0; i < n; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    uris = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        }
    }

    private void handleBackAction() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript("(function(){\n"
            + "  var hasDialog = !!document.querySelector('[data-component=\"dialog-overlay\"], [data-dialog-layer], [data-slot=\"dialog-content\"], div[role=\"dialog\"]');\n"
            + "  if (hasDialog) {\n"
            + "    window.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true, cancelable:true}));\n"
            + "    var closeBtn = document.querySelector('button[aria-label*=\"close\" i], [data-slot=\"dialog-close-button\"]');\n"
            + "    if (closeBtn) closeBtn.click();\n"
            + "    return 'closed_dialog';\n"
            + "  }\n"
            + "  var drawer = document.querySelector('div[class*=\"fixed top-10 bottom-0 start-0\"]');\n"
            + "  if (drawer && drawer.classList.contains('translate-x-0')) {\n"
            + "    var toggleBtn = document.querySelector('button[aria-label*=\"sidebar\" i], button[aria-label*=\"menu\" i]');\n"
            + "    if (toggleBtn) toggleBtn.click();\n"
            + "    return 'closed_drawer';\n"
            + "  }\n"
            + "  if (window.location.pathname === '/' || window.location.pathname === '') return 'on_home';\n"
            + "  return 'none';\n"
            + "})()", value -> {
            String res = value != null ? value.replace("\"", "").trim() : "";
            if ("closed_dialog".equals(res) || "closed_drawer".equals(res)) return;
            if ("on_home".equals(res)) {
                if (web.canGoBack()) web.goBack();
                else if (lastChatUrl != null && !lastChatUrl.isEmpty()) web.loadUrl(lastChatUrl);
                return;
            }
            if (web.canGoBack()) web.goBack();
            else finish();
        });
    }

    @Override
    public void onBackPressed() {
        handleBackAction();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("opencode_prefs", MODE_PRIVATE);
        boolean is24_7 = prefs.getBoolean("pref_24_7", false);
        if (isFinishing() && !is24_7) {
            startService(new Intent(this, OpenCodeService.class).setAction(OpenCodeService.ACTION_STOP_SERVICE));
        }
        super.onDestroy();
    }
}
