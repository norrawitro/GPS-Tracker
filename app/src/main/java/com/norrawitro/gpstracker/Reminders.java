package com.norrawitro.gpstracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Reminders with the person's own text (issue #8).
 * - time: fires at HH:MM, and again every time that clock time comes round (every day).
 * - place: fires once on entering a saved place's radius; it re-arms only after leaving, so it fires
 *   again on the next entry. Checked on each GPS fix, so it needs recording on.
 */
public class Reminders extends BroadcastReceiver {
    private static final String ACTION = "com.norrawitro.gpstracker.REMINDER";

    // ---------- time reminders ----------

    private static PendingIntent pi(Context c, long id) {
        Intent i = new Intent(c, Reminders.class).setAction(ACTION + "." + id).putExtra("id", id);
        return PendingIntent.getBroadcast(c, (int) (100000 + id % 800000), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Next time "HH:MM" occurs after now, in millis, or -1 if unreadable. */
    static long nextAt(String hhmm) {
        String[] t = String.valueOf(hhmm).split(":");
        if (t.length != 2) return -1;
        int h, m;
        try { h = Integer.parseInt(t[0].trim()); m = Integer.parseInt(t[1].trim()); } catch (NumberFormatException e) { return -1; }
        if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    static void schedule(Context c, long id, String hhmm) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pi(c, id));
        long at = nextAt(hhmm);
        if (at < 0) return;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, id));
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, id));
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, id));
        }
    }

    static void cancel(Context c, long id) {
        c.getSystemService(AlarmManager.class).cancel(pi(c, id));
    }

    /** Sets every enabled time reminder again (after a restart, an update, or a clock change). */
    static void rescheduleAll(Context c) {
        try {
            JSONArray all = PlacesDb.get(c).reminders();
            for (int i = 0; i < all.length(); i++) {
                JSONObject r = all.getJSONObject(i);
                if (!"time".equals(r.optString("kind"))) continue;
                if (r.optBoolean("enabled")) schedule(c, r.getLong("id"), r.optString("time"));
                else cancel(c, r.getLong("id"));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        long id = intent.getLongExtra("id", -1);
        if (id < 0) return;
        try {
            JSONObject r = PlacesDb.get(c).reminder(id);
            if (r == null || !r.optBoolean("enabled")) return;
            ReminderAlert.show(c, id, "เตือนเวลา " + r.optString("time"), r.optString("text"));
            schedule(c, id, r.optString("time")); // the same time tomorrow
        } catch (Exception ignored) {}
    }

    // ---------- place reminders ----------

    /** Called with each GPS fix while recording. */
    static void onFix(Context c, double lat, double lng) {
        try {
            PlacesDb db = PlacesDb.get(c);
            JSONArray all = db.reminders();
            for (int i = 0; i < all.length(); i++) {
                JSONObject r = all.getJSONObject(i);
                if (!"place".equals(r.optString("kind")) || !r.optBoolean("enabled")) continue;
                long id = r.getLong("id");
                JSONObject full = db.reminder(id);
                if (full == null) continue;
                boolean inside = db.insideNamed(r.optString("place"), lat, lng);
                boolean armed = full.optBoolean("armed", true);
                if (inside && armed) {
                    db.setArmed(id, false);
                    ReminderAlert.show(c, id, "ถึง " + r.optString("place"), r.optString("text"));
                } else if (!inside && !armed) {
                    db.setArmed(id, true); // left the place: fire again on the next entry
                }
            }
        } catch (Exception ignored) {}
    }
}
