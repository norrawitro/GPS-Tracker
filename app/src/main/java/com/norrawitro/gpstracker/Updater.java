package com.norrawitro.gpstracker;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Over-the-air updates from GitHub Releases. Each release is tagged v1.0.N where N is the versionCode,
 * and carries the signed APK as an asset. Android asks the person to confirm every install.
 */
public final class Updater {
    public interface Progress { void onProgress(int percent); }

    private Updater() {}

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GPS-Tracker-Android");
        return c;
    }

    /** Returns {available, current, latest, versionCode, notes, url, page} describing the newest release. */
    public static JSONObject check() throws Exception {
        HttpURLConnection c = open("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        JSONObject out = new JSONObject();
        out.put("current", BuildConfig.VERSION_NAME);
        out.put("currentCode", BuildConfig.VERSION_CODE);
        if (code == 404) {
            out.put("available", false);
            out.put("message", "ยังไม่มีเวอร์ชันที่เผยแพร่");
            return out;
        }
        if (code != 200) throw new Exception("GitHub ตอบกลับรหัส " + code);
        JSONObject rel = new JSONObject(readAll(c.getInputStream()));
        String tag = rel.optString("tag_name", "");
        int latestCode = parseCode(tag);
        String apkUrl = null;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break; }
            }
        }
        out.put("latest", tag.startsWith("v") ? tag.substring(1) : tag);
        out.put("latestCode", latestCode);
        out.put("notes", rel.optString("body", ""));
        out.put("page", rel.optString("html_url", ""));
        out.put("url", apkUrl == null ? JSONObject.NULL : apkUrl);
        out.put("available", apkUrl != null && latestCode > BuildConfig.VERSION_CODE);
        return out;
    }

    static int parseCode(String tag) {
        int dot = tag.lastIndexOf('.');
        String tail = dot >= 0 ? tag.substring(dot + 1) : tag.replaceAll("[^0-9]", "");
        try { return Integer.parseInt(tail); } catch (NumberFormatException e) { return -1; }
    }

    /** Downloads the APK into the app cache and returns the file. */
    public static File download(Context ctx, String url, Progress p) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("สร้างโฟลเดอร์ดาวน์โหลดไม่ได้");
        File out = new File(dir, "update.apk");
        HttpURLConnection c = open(url);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("ดาวน์โหลดไม่สำเร็จ (รหัส " + code + ")");
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != last) { last = pct; p.onProgress(pct); }
                }
            }
        }
        return out;
    }

    /** True when Android lets this app install packages; otherwise opens the setting and returns false. */
    public static boolean ensureInstallAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return false;
        }
        return true;
    }

    public static boolean ensureInstallAllowedSilently(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.getPackageManager().canRequestPackageInstalls();
    }

    public static void install(Context ctx, File apk) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString(StandardCharsets.UTF_8.name());
        }
    }
}
