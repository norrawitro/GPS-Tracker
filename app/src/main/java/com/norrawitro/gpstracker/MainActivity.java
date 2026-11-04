package com.norrawitro.gpstracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 9001;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    private static volatile MainActivity visible;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView web;
    private boolean pageReady;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TrackingService.ensureChannels(this);
        TrackingService.reconcile(this);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                emitState();
                emitPendingAsk();
            }
        });
        setContentView(web);
        web.loadUrl(START_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = this;
        TrackingService.reconcile(this);
        if (pageReady) { emitState(); emitPendingAsk(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (visible == this) visible = null;
    }

    @Override
    public void onBackPressed() {
        // Leaving with Back keeps recording in the background, like Home does.
        moveTaskToBack(true);
    }

    private void emit(String event, String payloadJs) {
        main.post(() -> web.evaluateJavascript(
                "window.__native&&window.__native(" + JSONObject.quote(event) + "," + payloadJs + ")", null));
    }

    private void emitState() {
        emit("state", TrackingService.state(this).toString());
    }

    private void emitPendingAsk() {
        String pend = TrackingService.prefs(this).getString("pending", null);
        if (pend != null && TrackingService.recording(this)) emit("ask", pend);
    }

    /** Sends the current state to the page if the app is on screen. */
    static void push(Context c) {
        MainActivity a = visible;
        if (a != null && a.pageReady) a.emitState();
    }

    /** Shows "คุณอยู่ที่ไหน?" in the app. Returns false when the app is not on screen. */
    static boolean askWhere(Context c) {
        MainActivity a = visible;
        if (a == null || !a.pageReady) return false;
        a.emitPendingAsk();
        a.emitState();
        return true;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (android.os.Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(need.toArray(new String[0]), REQ_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        if (TrackingService.hasLocationPermission(this)) {
            TrackingService.start(this);
            emitState();
        } else {
            // Rule 3: without location access recording cannot start.
            emit("warn", JSONObject.quote("ยังไม่ได้เปิดสิทธิ์เข้าถึงตำแหน่ง เปิดสิทธิ์ก่อนจึงจะเริ่มบันทึกได้"));
            emit("needPermission", "true");
        }
    }

    private class Bridge {
        @JavascriptInterface
        public String state() {
            return TrackingService.state(MainActivity.this).toString();
        }

        /** Button 1. */
        @JavascriptInterface
        public void toggle() {
            main.post(() -> {
                if (TrackingService.recording(MainActivity.this)) {
                    TrackingService.stop(MainActivity.this);
                    emitState();
                } else if (TrackingService.hasLocationPermission(MainActivity.this)) {
                    TrackingService.start(MainActivity.this);
                    emitState();
                } else {
                    requestNeededPermissions();
                }
            });
        }

        /** Button 2: name the current place. */
        @JavascriptInterface
        public void savePlace(String name) {
            main.post(() -> {
                TrackingService s = TrackingService.instance;
                if (!TrackingService.recording(MainActivity.this) || s == null) {
                    emit("placeSaved", "{\"ok\":false,\"error\":\"ไม่ได้เริ่มบันทึกการเดินทาง\"}");
                    return;
                }
                s.saveManualPlace(name, (loc, err) -> {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("ok", loc != null);
                        o.put("name", name.trim());
                        if (loc != null) { o.put("lat", loc.getLatitude()); o.put("lng", loc.getLongitude()); }
                        else o.put("error", err);
                        emit("placeSaved", o.toString());
                    } catch (Exception ignored) {}
                });
            });
        }

        /** Answer to "คุณอยู่ที่ไหน?" shown in the app. Empty name = closed without naming. */
        @JavascriptInterface
        public void answer(String name) {
            main.post(() -> {
                TrackingService s = TrackingService.instance;
                if (s != null) s.answer(name);
                emitState();
            });
        }

        @JavascriptInterface
        public void setSettings(int intervalMin, int checkEvery, int radiusM) {
            TrackingService.prefs(MainActivity.this).edit()
                    .putInt("intervalMin", intervalMin).putInt("checkEvery", Math.max(1, checkEvery))
                    .putInt("radiusM", Math.max(5, radiusM)).apply();
            emitState();
        }

        @JavascriptInterface
        public void listFiles() {
            io.execute(() -> {
                try { emit("files", TrackLog.list(MainActivity.this).toString()); }
                catch (Exception e) { emit("files", "[]"); }
            });
        }

        @JavascriptInterface
        public void readFile(String id) {
            io.execute(() -> {
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("rows", TrackLog.read(MainActivity.this, Long.parseLong(id)));
                    emit("file", o.toString());
                } catch (Exception e) {
                    emit("file", "{\"id\":" + JSONObject.quote(id) + ",\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                }
            });
        }

        /** Opens or shares a route file with another app (Sheets, Excel, a file manager). */
        @JavascriptInterface
        public void shareFile(String id, String name) {
            main.post(() -> {
                Uri uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id);
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/csv")
                        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(send, name)); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            main.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public boolean permissionDenied() {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        }
    }
}
