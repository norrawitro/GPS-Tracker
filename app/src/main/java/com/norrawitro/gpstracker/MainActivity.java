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
    private static final int REQ_FOLDER = 9002;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    private static volatile MainActivity visible;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Network work (update check and download) runs apart, so a slow connection never holds up reading files. */
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private WebView web;
    private boolean pageReady;
    /** True while the app password has not been entered since the app was last opened. */
    private volatile boolean locked;
    private java.io.File downloadedApk;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TrackingService.ensureChannels(this);
        TrackingService.reconcile(this);
        io.execute(() -> Reminders.rescheduleAll(this)); // alarms are lost after a force stop
        locked = AppLock.enabled(this);

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
                emitLock();
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
        // Back from the "install unknown apps" setting: continue the install if it is allowed now.
        if (downloadedApk != null && Updater.ensureInstallAllowedSilently(this)) {
            java.io.File apk = downloadedApk;
            downloadedApk = null;
            Updater.install(this, apk);
        }
        if (pageReady) { emitLock(); emitState(); emitPendingAsk(); }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the app (Home, another app, screen off) locks it again.
        if (AppLock.enabled(this)) locked = true;
    }

    private void emitLock() {
        emit("lock", "{\"enabled\":" + AppLock.enabled(this) + ",\"locked\":" + locked + "}");
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
        if (!locked && pend != null && TrackingService.recording(this)) emit("ask", pend);
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FOLDER) return;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                FolderAccess.save(this, data.getData());
                emit("folderPicked", "true");
            } catch (Exception e) {
                emit("warn", JSONObject.quote("ใช้โฟลเดอร์นี้ไม่ได้ ลองเลือกโฟลเดอร์ GPS-Track อีกครั้ง"));
            }
        }
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
            if (locked) return "{\"locked\":true}";
            return TrackingService.state(MainActivity.this).toString();
        }

        /**
         * Checks the password off the page's thread (the PBKDF2 hash takes a moment, and running it
         * inside the call froze the screen). Answers with the "unlocked" event: "" or a message.
         */
        @JavascriptInterface
        public void unlockAsync(String pw) {
            io.execute(() -> {
                String err = AppLock.unlock(MainActivity.this, pw);
                if (err.isEmpty()) {
                    locked = false;
                    emitState();
                    emitPendingAsk();
                }
                emit("unlocked", JSONObject.quote(err));
            });
        }

        /** Set, change or (new = "") remove the app password. Answers with the "pwChanged" event. */
        @JavascriptInterface
        public void changePasswordAsync(String current, String next) {
            if (locked) { emit("pwChanged", JSONObject.quote("ปลดล็อกแอปก่อน")); return; }
            io.execute(() -> {
                String err = AppLock.change(MainActivity.this, current, next);
                if (err.isEmpty()) emitLock();
                emit("pwChanged", JSONObject.quote(err));
            });
        }

        // ---------- reminders (issue #8) ----------

        /** Sends {reminders: [...], places: [names]} as the "reminders" event. */
        @JavascriptInterface
        public void listReminders() {
            if (locked) return;
            io.execute(() -> {
                try {
                    JSONObject o = new JSONObject();
                    o.put("reminders", PlacesDb.get(MainActivity.this).reminders());
                    o.put("places", PlacesDb.get(MainActivity.this).placeNames());
                    emit("reminders", o.toString());
                } catch (Exception e) {
                    emit("warn", JSONObject.quote("อ่านรายการเตือนไม่ได้"));
                }
            });
        }

        /** kind "time" uses time ("HH:MM"); kind "place" uses place (a saved place name). */
        @JavascriptInterface
        public void addReminder(String text, String kind, String time, String place) {
            if (locked) return;
            io.execute(() -> {
                long id = PlacesDb.get(MainActivity.this).addReminder(text.trim(), kind,
                        "time".equals(kind) ? time : null, "place".equals(kind) ? place : null,
                        TrackLog.stamp(System.currentTimeMillis()));
                if ("time".equals(kind) && id > 0) Reminders.schedule(MainActivity.this, id, time);
                listReminders();
            });
        }

        @JavascriptInterface
        public void setReminderEnabled(String id, boolean on) {
            if (locked) return;
            io.execute(() -> {
                long rid = Long.parseLong(id);
                PlacesDb.get(MainActivity.this).setReminderEnabled(rid, on);
                Reminders.rescheduleAll(MainActivity.this);
                listReminders();
            });
        }

        @JavascriptInterface
        public void deleteReminder(String id) {
            if (locked) return;
            io.execute(() -> {
                long rid = Long.parseLong(id);
                Reminders.cancel(MainActivity.this, rid);
                PlacesDb.get(MainActivity.this).deleteReminder(rid);
                listReminders();
            });
        }

        @JavascriptInterface
        public void testReminder(String text) {
            ReminderAlert.show(MainActivity.this, 999, "ทดสอบการเตือน", text == null || text.isEmpty() ? "ตัวอย่างการเตือน" : text);
        }

        /** True when location is allowed "all the time", which lets recording resume after a restart. */
        @JavascriptInterface
        public boolean backgroundLocation() {
            return BootReceiver.backgroundLocation(MainActivity.this);
        }

        @JavascriptInterface
        public boolean lockEnabled() {
            return AppLock.enabled(MainActivity.this);
        }

        /** Button 1. */
        @JavascriptInterface
        public void toggle() {
            if (locked) return;
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
            if (locked) return;
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
            if (locked) return;
            main.post(() -> {
                TrackingService s = TrackingService.instance;
                if (s != null) s.answer(name);
                emitState();
            });
        }

        @JavascriptInterface
        public void setSettings(int intervalMin, int checkEvery, int radiusM) {
            if (locked) return;
            TrackingService.prefs(MainActivity.this).edit()
                    .putInt("intervalMin", intervalMin).putInt("checkEvery", Math.max(1, checkEvery))
                    .putInt("radiusM", Math.max(5, radiusM)).apply();
            emitState();
        }

        /** Lets the person pick Documents/GPS-Track once, so files from an earlier install can be read too. */
        @JavascriptInterface
        public void pickFolder() {
            if (locked) return;
            main.post(() -> {
                try { startActivityForResult(FolderAccess.pickIntent(), REQ_FOLDER); }
                catch (Exception e) { emit("warn", JSONObject.quote("เปิดหน้าเลือกโฟลเดอร์ไม่ได้")); }
            });
        }

        @JavascriptInterface
        public boolean hasFolder() {
            return FolderAccess.tree(MainActivity.this) != null;
        }

        @JavascriptInterface
        public void listFiles() {
            if (locked) return;
            io.execute(() -> {
                try {
                    Uri tree = FolderAccess.tree(MainActivity.this);
                    emit("files", (tree != null ? FolderAccess.list(MainActivity.this, tree) : TrackLog.list(MainActivity.this)).toString());
                }
                catch (Throwable e) { emit("filesError", JSONObject.quote(String.valueOf(e.getMessage()))); }
            });
        }

        @JavascriptInterface
        public void readFile(String id) {
            if (locked) return;
            io.execute(() -> {
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("rows", TrackLog.readAll(MainActivity.this, id));
                    emit("file", o.toString());
                } catch (Exception e) {
                    emit("file", "{\"id\":" + JSONObject.quote(id) + ",\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                }
            });
        }

        /** Opens or shares a route file with another app (Sheets, Excel, a file manager). */
        @JavascriptInterface
        public void shareFile(String id, String name) {
            if (locked) return;
            main.post(() -> {
                String first = id.split("\\|")[0].trim();
                Uri uri = first.startsWith("content:") ? Uri.parse(first)
                        : Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), first);
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/csv")
                        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(send, name)); } catch (Exception ignored) {}
            });
        }

        /** "Display over other apps" setting, needed for the floating "คุณอยู่ที่ไหน?" window. */
        @JavascriptInterface
        public void openOverlaySettings() {
            main.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            main.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } catch (Exception ignored) {}
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
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        /** Asks GitHub Releases for a newer version. Answers with the "update" event. */
        @JavascriptInterface
        public void checkUpdate() {
            net.execute(() -> {
                try { emit("update", Updater.check().toString()); }
                catch (Exception e) { emit("update", "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}"); }
            });
        }

        @JavascriptInterface
        public void installUpdate(String url) {
            net.execute(() -> {
                try {
                    java.io.File apk = Updater.download(MainActivity.this, url, pct -> emit("updateProgress", String.valueOf(pct)));
                    emit("updateProgress", "100");
                    main.post(() -> {
                        if (Updater.ensureInstallAllowed(MainActivity.this)) Updater.install(MainActivity.this, apk);
                        else downloadedApk = apk;
                    });
                } catch (Exception e) {
                    emit("update", "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                }
            });
        }

        @JavascriptInterface
        public boolean permissionDenied() {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        }
    }
}
