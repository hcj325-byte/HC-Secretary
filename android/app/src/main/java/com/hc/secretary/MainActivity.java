package com.hc.secretary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HOST = "hcg.local";
    private static final String START = "https://" + HOST + "/index.html";
    private static final String[] HTTP_HOSTS = { "api.x.ai", "api.anthropic.com" };
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private WebView web;
    private volatile String curUrl = "";
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private boolean trusted() { return curUrl.startsWith("https://" + HOST + "/"); }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? Color.parseColor("#111412") : Color.parseColor("#F4F7F5");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                ((FrameLayout.LayoutParams) web.getLayoutParams()).setMargins(i.left, i.top, i.right, Math.max(i.bottom, ime.bottom));
                web.requestLayout();
                return insets;
            });
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(false);
        s.setSafeBrowsingEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "HCNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                if (!"https".equals(u.getScheme())) return empty(403, "Forbidden");
                String path = u.getPath() == null || u.getPath().equals("/") ? "index.html" : u.getPath().substring(1);
                if (path.contains("..")) return empty(404, "Not Found");
                try {
                    InputStream in = getAssets().open(path);
                    String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "application/javascript"
                        : path.endsWith(".css") ? "text/css" : path.endsWith(".png") ? "image/png" : "application/octet-stream";
                    return new WebResourceResponse(mime, "utf-8", in);
                } catch (Exception e) {
                    return empty(404, "Not Found");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("https".equals(u.getScheme()) && HOST.equals(u.getHost())) return false;
                if (req.isForMainFrame() && req.hasGesture() && "https".equals(u.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (Exception ignored) {}
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                curUrl = url == null ? "" : url;
                super.onPageStarted(v, url, f);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.loadUrl(START);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private static WebResourceResponse empty(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason, new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private void handleBack() {
        web.evaluateJavascript("(window.hcBack && window.hcBack()) ? 1 : 0", r -> {
            if (!"1".equals(r)) finish();
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    private static boolean allowed(String url) {
        try {
            Uri u = Uri.parse(url == null ? "" : url);
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getPort() != -1) return false;
            for (String h : HTTP_HOSTS) if (h.equals(u.getHost())) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void request(String id, String method, String url, String headersJson, String body) {
        if (!trusted()) return;
        final String sid = id == null ? "" : id;
        if (!allowed(url)) {
            js("window.hcHttpDone && window.hcHttpDone(" + JSONObject.quote(sid) + ",0," + JSONObject.quote("blocked") + ")");
            return;
        }
        pool.execute(() -> {
            int status = 0;
            String text;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(15000);
                c.setReadTimeout(150000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "HCSecretary-Android/1");
                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject h = new JSONObject(headersJson);
                    for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0) continue;
                        String v = h.optString(k, "");
                        if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) continue;
                        c.setRequestProperty(k, v);
                    }
                }
                if ("POST".equals(method)) {
                    byte[] out = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(out.length);
                    try (OutputStream os = c.getOutputStream()) { os.write(out); }
                }
                status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                text = in == null ? "" : readAll(in);
            } catch (Exception e) {
                status = 0;
                text = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
            js("window.hcHttpDone && window.hcHttpDone(" + JSONObject.quote(sid) + "," + status + "," + JSONObject.quote(text) + ")");
        });
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try (InputStream is = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BODY) break;
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    final class Bridge {
        @JavascriptInterface
        public void openUrl(String url) {
            if (!trusted()) return;
            Uri u = Uri.parse(url == null ? "" : url);
            if (!"https".equals(u.getScheme()) || u.getHost() == null) return;
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (Exception ignored) {} });
        }

        @JavascriptInterface
        public void haptic(String type) {
            runOnUiThread(() -> {
                int c = "confirm".equals(type) && Build.VERSION.SDK_INT >= 30 ? android.view.HapticFeedbackConstants.CONFIRM
                        : android.view.HapticFeedbackConstants.CLOCK_TICK;
                web.performHapticFeedback(c);
            });
        }

        @JavascriptInterface
        public void httpPost(String id, String url, String headersJson, String body) { request(id, "POST", url, headersJson, body); }

        @JavascriptInterface
        public void httpGet(String id, String url, String headersJson) { request(id, "GET", url, headersJson, null); }
    }
}
