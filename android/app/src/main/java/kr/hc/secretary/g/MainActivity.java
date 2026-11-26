package kr.hc.secretary.g; // ← 저장소 패키지에 맞게 수정

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

/** HC 비서실 G: 앱 안에 들어 있는 게임 화면(HTML)을 띄우고, 진동·링크·HTTP(CORS 우회)만 담당. 자산 앱과 무관. */
public class MainActivity extends Activity {
    private static final String HOST = "hcg.local";
    private static final String START = "https://" + HOST + "/index.html";
    /** 네이티브 HTTP 허용 주소 (AI 대화용) */
    private static final String[] HTTP_HOSTS = { "api.x.ai", "api.anthropic.com" };
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private WebView web;
    private volatile String curUrl = "";
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    /** 브리지는 앱 자체 화면(https://hcg.local)에서만 */
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

        // Android 15+는 화면이 상태바·내비게이션바 아래까지 그려지므로 그만큼 여백 (키보드 높이 포함)
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
                if (!HOST.equals(u.getHost())) return null;           // 그 외(글꼴, AI API 등)는 그대로
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
                // 외부 링크: 사용자가 누른 https만 브라우저로, 그 외(intent:, file:, content: 등)는 차단
                if (req.isForMainFrame() && req.hasGesture() && "https".equals(u.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (Exception ignored) { }
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) { curUrl = url == null ? "" : url; super.onPageStarted(v, url, f); }
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

    // ---- 뒤로가기: 열린 창·대화방이면 게임 안에서 처리(로비로), 아니면 앱 닫기 ----
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
        } catch (Exception ignored) { }
        return false;
    }

    /** 허용된 주소로 HTTPS 요청 → 끝나면 window.hcHttpDone(id, status, body). status 0 = 실패 */
    private void request(String id, String method, String url, String headersJson, String body) {
        if (!trusted()) return;
        final String sid = id == null ? "" : id;
        if (!allowed(url)) { js("window.hcHttpDone && window.hcHttpDone(" + JSONObject.quote(sid) + ",0," + JSONObject.quote("허용되지 않은 주소") + ")"); return; }
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

    /** 화면(HTML)에서 부르는 기능들 */
    final class Bridge {
        /** 외부 링크(출처·키 발급 페이지) 열기: https만 */
        @JavascriptInterface
        public void openUrl(String url) {
            if (!trusted()) return;
            Uri u = Uri.parse(url == null ? "" : url);
            if (!"https".equals(u.getScheme()) || u.getHost() == null) return;
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (Exception ignored) { } });
        }

        /** 짧은 진동: tick(기본), confirm(완료) */
        @JavascriptInterface
        public void haptic(String type) {
            runOnUiThread(() -> {
                int c = "confirm".equals(type) && Build.VERSION.SDK_INT >= 30 ? android.view.HapticFeedbackConstants.CONFIRM
                        : android.view.HapticFeedbackConstants.CLOCK_TICK;
                web.performHapticFeedback(c);
            });
        }

        /** AI API POST (브라우저 fetch가 CORS로 막힐 때만 씀). 결과는 비동기로 hcHttpDone */
        @JavascriptInterface
        public void httpPost(String id, String url, String headersJson, String body) { request(id, "POST", url, headersJson, body); }

        /** AI API GET (모델 목록) */
        @JavascriptInterface
        public void httpGet(String id, String url, String headersJson) { request(id, "GET", url, headersJson, null); }
    }
}
