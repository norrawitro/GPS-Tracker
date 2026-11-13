package com.norrawitro.gpstracker;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Two-way status link with a GitHub repo the owner creates.
 *
 * - The owner puts any file in the repo's commands/ folder. The app looks for it when the app is opened, when
 *   "ตรวจคำสั่งตอนนี้" is pressed, and while recording at most once per "ตรวจทุก" minutes (on a GPS fix, no extra wake-ups).
 * - When a command is there, the app uploads its status to status/latest.enc and status/YYYY-MM-DD/HHmmss.enc,
 *   then deletes the command file. Nothing is sent on a timer without a command.
 * - "ส่งสถานะตอนนี้" uploads the status at once.
 *
 * Every uploaded file is AES-256-CBC with PBKDF2 (SHA-256, 200000 rounds) in OpenSSL's "Salted__" format, base64 on one
 * line, the same as NRW chat.sh, so the owner decrypts it with:
 *   openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -a -A -pass pass:KEY -in latest.enc
 *
 * The GitHub token and the AES key are typed into the app; they are stored encrypted with a key held in the Android
 * Keystore, are never shown again, and never leave the phone except the token in the Authorization header to GitHub.
 */
final class StatusLink {
    private StatusLink() {}

    static final int ITER = 200_000;
    private static final String KS_ALIAS = "status_link_v1";
    private static final String API = "https://api.github.com";
    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private static final long OPEN_GAP_MS = 60_000;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("status_link", Context.MODE_PRIVATE);
    }

    // ---------- settings ----------

    static String repo(Context c) { return prefs(c).getString("repo", ""); }
    static int everyMin(Context c) { return prefs(c).getInt("everyMin", 15); }
    static boolean hasToken(Context c) { return prefs(c).contains("token"); }
    static boolean hasKey(Context c) { return prefs(c).contains("aes"); }
    static boolean ready(Context c) { return validRepo(repo(c)) && hasToken(c) && hasKey(c); }

    static boolean validRepo(String r) {
        return r != null && r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    }

    /** Saves the settings. An empty token or key keeps the stored one. Returns an error text, or null. */
    static String save(Context c, String repo, String token, String aesKey, int everyMin) {
        repo = repo == null ? "" : repo.trim();
        if (!repo.isEmpty() && !validRepo(repo)) return "ชื่อ repo ต้องเป็นแบบ เจ้าของ/ชื่อ เช่น norrawitro/gps-status";
        token = token == null ? "" : token.trim();
        aesKey = aesKey == null ? "" : aesKey.trim();
        if (!aesKey.isEmpty() && aesKey.length() < 16) return "กุญแจเข้ารหัสต้องยาวอย่างน้อย 16 ตัว";
        SharedPreferences.Editor e = prefs(c).edit().putString("repo", repo).putInt("everyMin", Math.max(0, Math.min(1440, everyMin)));
        try {
            if (!token.isEmpty()) e.putString("token", seal(token));
            if (!aesKey.isEmpty()) e.putString("aes", seal(aesKey));
        } catch (Exception ex) {
            return "เก็บข้อมูลลับไม่สำเร็จ: " + ex.getMessage();
        }
        e.apply();
        log(c, "บันทึกการตั้งค่า" + (token.isEmpty() ? "" : " (token ใหม่)") + (aesKey.isEmpty() ? "" : " (กุญแจใหม่)"));
        return null;
    }

    static void clearSecrets(Context c) {
        prefs(c).edit().remove("token").remove("aes").apply();
        log(c, "ลบ token และกุญแจออกจากเครื่องแล้ว");
    }

    static JSONObject info(Context c) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = prefs(c);
            o.put("repo", repo(c));
            o.put("everyMin", everyMin(c));
            o.put("hasToken", hasToken(c));
            o.put("hasKey", hasKey(c));
            o.put("ready", ready(c));
            o.put("lastCheck", p.getLong("lastCheck", 0));
            o.put("lastSent", p.getLong("lastSent", 0));
            o.put("usageAllowed", usageAllowed(c));
            o.put("log", new JSONArray(p.getString("log", "[]")));
        } catch (Exception ignored) {}
        return o;
    }

    // ---------- the log the person sees (never holds the token or key) ----------

    static synchronized void log(Context c, String msg) {
        try {
            SharedPreferences p = prefs(c);
            JSONArray old = new JSONArray(p.getString("log", "[]"));
            JSONArray now = new JSONArray();
            now.put(TrackLog.stamp(System.currentTimeMillis()) + "  " + msg);
            for (int i = 0; i < old.length() && now.length() < 30; i++) now.put(old.get(i));
            p.edit().putString("log", now.toString()).apply();
        } catch (Exception ignored) {}
        MainActivity.pushLink(c);
    }

    // ---------- when to look for a command ----------

    /** Called with each GPS fix while recording. Looks for a command at most once per "everyMin" minutes. */
    static void onFix(Context c) {
        int every = everyMin(c);
        if (every <= 0 || !ready(c)) return;
        long last = prefs(c).getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last < every * 60_000L - 30_000L) return;
        checkAsync(c, "ระหว่างบันทึก");
    }

    /** Called when the app comes on screen. */
    static void onOpen(Context c) {
        if (!ready(c)) return;
        long last = prefs(c).getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last < OPEN_GAP_MS) return;
        checkAsync(c, "เปิดแอป");
    }

    static void checkAsync(Context c, String why) {
        Context app = c.getApplicationContext();
        net.execute(() -> check(app, why));
    }

    static void sendAsync(Context c, String why) {
        Context app = c.getApplicationContext();
        net.execute(() -> {
            try {
                send(app, why);
            } catch (Exception e) {
                log(app, "ส่งสถานะไม่สำเร็จ: " + reason(e));
            }
        });
    }

    // ---------- GitHub ----------

    private static void check(Context c, String why) {
        if (!ready(c)) { log(c, "ยังตั้งค่าไม่ครบ (repo, token, กุญแจ)"); return; }
        prefs(c).edit().putLong("lastCheck", System.currentTimeMillis()).apply();
        try {
            Resp r = call(c, "GET", contentsUrl(c, "commands"), null);
            if (r.code == 404) { log(c, "ตรวจคำสั่ง (" + why + "): ไม่มีคำสั่ง"); return; }
            if (r.code != 200) throw new Exception(httpReason(r));
            JSONArray files = new JSONArray(r.body);
            int handled = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (!"file".equals(f.optString("type"))) continue;
                String name = f.optString("name");
                if (name.startsWith(".")) continue; // e.g. .gitkeep
                String cmd = readCommand(c, f.optString("path"));
                String path = send(c, "คำสั่ง " + name + (cmd.isEmpty() ? "" : " (" + cmd + ")"));
                deleteFile(c, f.optString("path"), f.optString("sha"), "Done: " + name + " -> " + path);
                handled++;
            }
            if (handled == 0) log(c, "ตรวจคำสั่ง (" + why + "): ไม่มีคำสั่ง");
        } catch (Exception e) {
            log(c, "ตรวจคำสั่งไม่สำเร็จ (" + why + "): " + reason(e));
        }
    }

    /** First line of the command file, trimmed and shortened; for the log only. */
    private static String readCommand(Context c, String path) {
        try {
            Resp r = call(c, "GET", contentsUrl(c, path), null);
            if (r.code != 200) return "";
            String b64 = new JSONObject(r.body).optString("content", "").replace("\n", "");
            String text = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
            int nl = text.indexOf('\n');
            if (nl >= 0) text = text.substring(0, nl).trim();
            text = text.replaceAll("[^\\p{L}\\p{N} _.:-]", "");
            return text.length() > 40 ? text.substring(0, 40) : text;
        } catch (Exception e) {
            return "";
        }
    }

    /** Builds, encrypts and uploads the status. Returns the dated file's path. */
    static String send(Context c, String why) throws Exception {
        if (!ready(c)) throw new Exception("ยังตั้งค่าไม่ครบ (repo, token, กุญแจ)");
        long now = System.currentTimeMillis();
        JSONObject s = collect(c, now, why);
        String enc = opensslEncrypt(s.toString(2).getBytes(StandardCharsets.UTF_8), open(prefs(c).getString("aes", "")));
        byte[] file = (enc + "\n").getBytes(StandardCharsets.UTF_8);
        Date d = new Date(now);
        String dated = "status/" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
                + "/" + new SimpleDateFormat("HHmmss", Locale.US).format(d) + ".enc";
        putFile(c, dated, file, "Status " + TrackLog.stamp(now));
        putFile(c, "status/latest.enc", file, "Latest status " + TrackLog.stamp(now));
        prefs(c).edit().putLong("lastSent", now).apply();
        log(c, "ส่งสถานะแล้ว (" + why + ") → " + dated);
        return dated;
    }

    private static void putFile(Context c, String path, byte[] content, String message) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", message);
        body.put("content", Base64.encodeToString(content, Base64.NO_WRAP));
        Resp old = call(c, "GET", contentsUrl(c, path), null);
        if (old.code == 200) body.put("sha", new JSONObject(old.body).optString("sha"));
        else if (old.code != 404) throw new Exception(httpReason(old));
        Resp r = call(c, "PUT", contentsUrl(c, path), body.toString());
        if (r.code != 200 && r.code != 201) throw new Exception(httpReason(r));
    }

    private static void deleteFile(Context c, String path, String sha, String message) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", message);
        body.put("sha", sha);
        Resp r = call(c, "DELETE", contentsUrl(c, path), body.toString());
        if (r.code != 200 && r.code != 404) throw new Exception("ส่งสถานะแล้ว แต่ลบไฟล์คำสั่งไม่ได้: " + httpReason(r));
    }

    private static String contentsUrl(Context c, String path) {
        StringBuilder b = new StringBuilder(API).append("/repos/").append(repo(c)).append("/contents/");
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append('/');
            try { b.append(java.net.URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20")); } catch (Exception e) { b.append(parts[i]); }
        }
        return b.toString();
    }

    private static final class Resp { int code; String body; }

    private static Resp call(Context c, String method, String url, String json) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(20_000);
            h.setReadTimeout(30_000);
            h.setRequestMethod(method);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            h.setRequestProperty("User-Agent", "GPS-Tracker-Android");
            h.setRequestProperty("Authorization", "Bearer " + open(prefs(c).getString("token", "")));
            if (json != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = h.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            Resp r = new Resp();
            r.code = h.getResponseCode();
            InputStream in = r.code < 400 ? h.getInputStream() : h.getErrorStream();
            r.body = in == null ? "" : readAll(in);
            return r;
        } finally {
            h.disconnect();
        }
    }

    private static String httpReason(Resp r) {
        String m = "";
        try { m = new JSONObject(r.body).optString("message", ""); } catch (Exception ignored) {}
        switch (r.code) {
            case 401: return "token ใช้ไม่ได้หรือหมดอายุ (401)";
            case 403: return "token ไม่มีสิทธิ์เขียน repo นี้ (403" + (m.isEmpty() ? "" : ": " + m) + ")";
            case 404: return "ไม่พบ repo หรือ token มองไม่เห็น repo (404)";
            case 409: return "ไฟล์ชนกัน ลองใหม่ (409)";
            case 422: return "GitHub ไม่รับข้อมูล (422" + (m.isEmpty() ? "" : ": " + m) + ")";
            default: return "GitHub ตอบ " + r.code + (m.isEmpty() ? "" : ": " + m);
        }
    }

    private static String reason(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "ไม่มีอินเทอร์เน็ต";
        if (e instanceof java.net.SocketTimeoutException) return "เชื่อมต่อช้าเกินไป";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    // ---------- the status ----------

    static JSONObject collect(Context c, long now, String why) throws Exception {
        JSONObject s = new JSONObject();
        s.put("app", "GPS-Tracker");
        s.put("version", BuildConfig.VERSION_NAME);
        s.put("time", TrackLog.stamp(now));
        s.put("reason", why);
        s.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        s.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

        // battery
        JSONObject b = new JSONObject();
        Intent bi = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager bm = c.getSystemService(BatteryManager.class);
        b.put("percent", TrackingService.batteryPct(c));
        if (bi != null) {
            int mv = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0 && mv < 100) mv *= 1000; // a few phones report volts
            b.put("voltage_mV", mv > 0 ? mv : JSONObject.NULL);
            int t = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            b.put("temperature_C", t != Integer.MIN_VALUE ? t / 10.0 : JSONObject.NULL);
            b.put("status", batteryStatus(bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)));
            b.put("plugged", plugged(bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)));
            b.put("health", health(bi.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)));
        }
        long cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        b.put("current_mA", cur != Long.MIN_VALUE && cur != 0 ? Math.round(cur / 1000.0) : JSONObject.NULL);
        PowerManager pm = c.getSystemService(PowerManager.class);
        b.put("power_saver", pm.isPowerSaveMode());
        b.put("app_unrestricted", pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        s.put("battery", b);

        // time
        JSONObject u = new JSONObject();
        long phoneUp = SystemClock.elapsedRealtime();
        u.put("phone_on_min", phoneUp / 60_000);
        u.put("app_running_min", (phoneUp - Process.getStartElapsedRealtime()) / 60_000);
        SharedPreferences tp = TrackingService.prefs(c);
        long recSince = tp.getLong("recStartedAt", 0);
        boolean rec = TrackingService.recording(c) && TrackingService.instance != null;
        u.put("recording_min", rec && recSince > 0 ? (now - recSince) / 60_000 : 0);
        u.put("recording_since", rec && recSince > 0 ? TrackLog.stamp(recSince) : JSONObject.NULL);
        Long fg = foregroundToday(c);
        u.put("on_screen_today_min", fg == null ? JSONObject.NULL : fg / 60_000);
        s.put("usage", u);

        // recording
        JSONObject r = new JSONObject();
        r.put("on", rec);
        r.put("interval_min", TrackingService.intervalMin(c));
        r.put("fixes_since_start", tp.getInt("count", 0));
        long lastAt = tp.getLong("lastAt", 0);
        r.put("last_fix", lastAt > 0 ? TrackLog.stamp(lastAt) : JSONObject.NULL);
        if (lastAt > 0) {
            r.put("last_lat", Double.longBitsToDouble(tp.getLong("lastLat", 0)));
            r.put("last_lng", Double.longBitsToDouble(tp.getLong("lastLng", 0)));
        }
        r.put("last_place", tp.getString("lastPlace", ""));
        r.put("near", tp.getString("nearName", ""));
        long next = tp.getLong("nextAt", 0);
        r.put("next_fix", next > 0 ? TrackLog.stamp(next) : JSONObject.NULL);
        r.put("last_error", tp.getString("lastError", ""));
        s.put("recording", r);

        // permissions
        JSONObject p = new JSONObject();
        p.put("location", TrackingService.hasLocationPermission(c));
        p.put("location_always", c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED);
        p.put("notifications", c.getSystemService(NotificationManager.class).areNotificationsEnabled());
        p.put("draw_over_apps", WherePopup.allowed(c));
        s.put("permissions", p);

        // data
        JSONObject d = new JSONObject();
        PlacesDb db = PlacesDb.get(c);
        d.put("places", db.count());
        d.put("reminders", db.reminders().length());
        StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        d.put("free_storage_MB", fs.getAvailableBytes() / (1024 * 1024));
        s.put("data", d);

        s.put("network", network(c));
        return s;
    }

    private static String batteryStatus(int v) {
        switch (v) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not charging";
            default: return "unknown";
        }
    }

    private static String plugged(int v) {
        if (v == 0) return "no";
        if ((v & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "ac";
        if ((v & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "usb";
        if ((v & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "wireless";
        return "yes";
    }

    private static String health(int v) {
        switch (v) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "cold";
            default: return "unknown";
        }
    }

    private static String network(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return "none";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "mobile";
            return "other";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ---------- time on screen today (needs "usage access", optional) ----------

    static boolean usageAllowed(Context c) {
        try {
            AppOpsManager ops = c.getSystemService(AppOpsManager.class);
            int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** This app's time on screen since midnight, or null without usage access. */
    private static Long foregroundToday(Context c) {
        if (!usageAllowed(c)) return null;
        try {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
            List<UsageStats> list = um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.getTimeInMillis(), System.currentTimeMillis());
            long total = 0;
            if (list != null) for (UsageStats u : list) if (c.getPackageName().equals(u.getPackageName())) total += u.getTotalTimeInForeground();
            return total;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- OpenSSL-compatible encryption (same as NRW chat.sh) ----------

    /** openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -a -A */
    static String opensslEncrypt(byte[] plain, String pass) throws Exception {
        byte[] salt = new byte[8];
        new SecureRandom().nextBytes(salt);
        byte[] kiv = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(pass.toCharArray(), salt, ITER, 48 * 8)).getEncoded();
        Cipher ci = Cipher.getInstance("AES/CBC/PKCS5Padding");
        ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(kiv, 0, 32), "AES"),
                new IvParameterSpec(Arrays.copyOfRange(kiv, 32, 48)));
        byte[] ct = ci.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("Salted__".getBytes(StandardCharsets.US_ASCII));
        out.write(salt);
        out.write(ct);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    // ---------- Android Keystore for the token and the key ----------

    private static SecretKey ksKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KS_ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String seal(String secret) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, ksKey());
        byte[] ct = ci.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ci.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String open(String sealed) throws Exception {
        int i = sealed.indexOf(':');
        if (i < 0) throw new Exception("ไม่พบ token หรือกุญแจ ใส่ใหม่ในตั้งค่า");
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, ksKey(), new GCMParameterSpec(128, Base64.decode(sealed.substring(0, i), Base64.NO_WRAP)));
        return new String(ci.doFinal(Base64.decode(sealed.substring(i + 1), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
