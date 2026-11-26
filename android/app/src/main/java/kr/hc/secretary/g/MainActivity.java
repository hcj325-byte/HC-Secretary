package kr.hc.secretary.g;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
    private WebView web;
    private volatile String curUrl = "";
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "HCNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = "index.html";
                try {
                    InputStream in = getAssets().open(path);
                    return new WebResourceResponse("text/html", "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
                }
            }
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                curUrl = url == null ? "" : url;
            }
        });
        web.loadUrl("https://hcg.local/index.html");
    }

    private boolean trusted() {
        return curUrl.startsWith("https://hcg.local/");
    }

    private void done(String id, int status, String body) {
        runOnUiThread(() -> web.evaluateJavascript(
            "window.hcHttpDone && window.hcHttpDone(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body) + ")", null));
    }

    final class Bridge {
        @JavascriptInterface
        public void httpPost(String id, String url, String headersJson, String body) {
            if (!trusted()) return;
            pool.execute(() -> doRequest(id, "POST", url, headersJson, body));
        }
        @JavascriptInterface
        public void httpGet(String id, String url, String headersJson) {
            if (!trusted()) return;
            pool.execute(() -> doRequest(id, "GET", url, headersJson, null));
        }
        @JavascriptInterface
        public void openUrl(String url) {}
        @JavascriptInterface
        public void haptic(String type) {}
    }

    private void doRequest(String id, String method, String url, String headersJson, String body) {
        if (url == null || !url.startsWith("https://api.x.ai") && !url.startsWith("https://api.anthropic.com")) {
            done(id, 0, "blocked");
            return;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            if (headersJson != null && !headersJson.isEmpty()) {
                JSONObject h = new JSONObject(headersJson);
                Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, h.optString(k, ""));
                }
            }
            if ("POST".equals(method) && body != null) {
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) { os.write(out); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                text = bo.toString("UTF-8");
            }
            done(id, status, text);
        } catch (Exception e) {
            done(id, 0, e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
