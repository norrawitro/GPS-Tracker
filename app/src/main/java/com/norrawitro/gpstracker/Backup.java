package com.norrawitro.gpstracker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Backup of what lives inside the app (saved places, reminders, settings), as a JSON file in
 * Documents/GPS-Track/backup/. Uninstalling the app (needed to go back to an older version)
 * deletes that data; the route CSV files in Documents stay either way.
 */
final class Backup {
    static final String FOLDER = TrackLog.ROOT + "backup/";

    private Backup() {}

    /** Writes a backup and returns its path as shown to the person. */
    static String export(Context c) throws Exception {
        PlacesDb db = PlacesDb.get(c);
        SharedPreferences p = TrackingService.prefs(c);
        JSONObject o = new JSONObject();
        o.put("app", "GPS-Tracker");
        o.put("fromVersion", BuildConfig.VERSION_NAME);
        o.put("savedAt", TrackLog.stamp(System.currentTimeMillis()));
        o.put("places", db.allPlaces());
        o.put("reminders", db.reminders());
        JSONObject s = new JSONObject();
        s.put("intervalMin", TrackingService.intervalMin(c));
        s.put("checkEvery", TrackingService.checkEvery(c));
        s.put("radiusM", TrackingService.radiusM(c));
        o.put("settings", s);

        String name = "gps-tracker-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        ContentResolver cr = c.getContentResolver();
        Uri uri = cr.insert(MediaStore.Files.getContentUri("external"), v);
        if (uri == null) throw new Exception("สร้างไฟล์สำรองไม่ได้");
        try (OutputStream os = cr.openOutputStream(uri, "w")) {
            if (os == null) throw new Exception("เขียนไฟล์สำรองไม่ได้");
            os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        p.edit().putString("lastBackup", FOLDER + name).apply();
        return FOLDER + name;
    }

    /** Restores from a backup file the person picked. Returns a summary for the person. */
    static String restore(Context c, Uri file) throws Exception {
        String text;
        try (InputStream in = c.getContentResolver().openInputStream(file)) {
            if (in == null) throw new Exception("เปิดไฟล์ไม่ได้");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            text = bo.toString(StandardCharsets.UTF_8.name());
        }
        JSONObject o = new JSONObject(text);
        if (!"GPS-Tracker".equals(o.optString("app"))) throw new Exception("ไฟล์นี้ไม่ใช่ไฟล์สำรองของ GPS Tracker");
        PlacesDb db = PlacesDb.get(c);
        int places = 0, reminders = 0;
        JSONArray ps = o.optJSONArray("places");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.getJSONObject(i);
                if (db.addIfNew(p.getString("name"), p.getDouble("lat"), p.getDouble("lng"), p.optDouble("radiusM", 100),
                        p.optString("firstSaved", TrackLog.stamp(System.currentTimeMillis())))) places++;
            }
        }
        JSONArray existing = db.reminders();
        JSONArray rs = o.optJSONArray("reminders");
        if (rs != null) {
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.getJSONObject(i);
                if (alreadyThere(existing, r)) continue;
                String now = TrackLog.stamp(System.currentTimeMillis());
                long id;
                if (r.has("pinLat")) {
                    id = db.addPinReminder(r.optString("text"), r.optString("place"), r.getDouble("pinLat"), r.getDouble("pinLng"), r.optDouble("pinRadius", 150), now);
                } else {
                    id = db.addReminder(r.optString("text"), r.optString("kind"),
                            "time".equals(r.optString("kind")) ? r.optString("time") : null,
                            "place".equals(r.optString("kind")) ? r.optString("place") : null, now);
                }
                if (id > 0 && !r.optBoolean("enabled", true)) db.setReminderEnabled(id, false);
                reminders++;
            }
        }
        JSONObject s = o.optJSONObject("settings");
        if (s != null) {
            TrackingService.prefs(c).edit()
                    .putInt("intervalMin", s.optInt("intervalMin", TrackingService.intervalMin(c)))
                    .putInt("checkEvery", s.optInt("checkEvery", TrackingService.checkEvery(c)))
                    .putInt("radiusM", s.optInt("radiusM", TrackingService.radiusM(c))).apply();
        }
        Reminders.rescheduleAll(c);
        return "กู้ข้อมูลแล้ว: สถานที่ " + places + " จุด, การเตือน " + reminders + " รายการ";
    }

    private static boolean alreadyThere(JSONArray existing, JSONObject r) {
        for (int i = 0; i < existing.length(); i++) {
            JSONObject e = existing.optJSONObject(i);
            if (e != null && e.optString("text").equals(r.optString("text")) && e.optString("kind").equals(r.optString("kind"))
                    && e.optString("time").equals(r.optString("time")) && e.optString("place").equals(r.optString("place"))) return true;
        }
        return false;
    }
}
