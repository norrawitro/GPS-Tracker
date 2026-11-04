package com.norrawitro.gpstracker;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background GPS recording. While recording, an exact alarm wakes the service every interval to take one fix.
 * Every fix is written to the day's CSV; on every Nth fix the position is matched against saved places,
 * and when nothing is near, the person is asked "คุณอยู่ที่ไหน?".
 */
public class TrackingService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_CAPTURE = "capture";
    static final String KEY_REPLY = "place_name";

    private static final String CH_RUN = "tracking_v1";
    private static final String CH_ASK = "where_v1";
    static final int NOTIF_RUN = 5100;
    static final int NOTIF_ASK = 5101;
    private static final long FIX_TIMEOUT_MS = 45_000;

    /** Running instance, so alarms and the page can reach it without starting a new service. */
    static volatile TrackingService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wake;
    private WherePopup popup;
    private boolean fixing;

    public interface FixCallback { void onFix(Location loc, String error); }

    // ---------- state shared with the page ----------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tracker", Context.MODE_PRIVATE);
    }

    static int intervalMin(Context c) { return prefs(c).getInt("intervalMin", 10); }
    static int checkEvery(Context c) { return Math.max(1, prefs(c).getInt("checkEvery", 6)); }
    static int radiusM(Context c) { return Math.max(5, prefs(c).getInt("radiusM", 100)); }
    static boolean recording(Context c) { return prefs(c).getBoolean("recording", false); }

    static boolean hasLocationPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Rule 20: a force close kills the service and its alarms but leaves "recording" saved.
     * When the app opens again and no service is running, the recording has stopped.
     */
    static void reconcile(Context c) {
        if (recording(c) && instance == null) {
            prefs(c).edit().putBoolean("recording", false).putInt("count", 0).remove("pending").apply();
        }
    }

    static JSONObject state(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        try {
            o.put("recording", p.getBoolean("recording", false));
            o.put("count", p.getInt("count", 0));
            o.put("checkEvery", checkEvery(c));
            o.put("intervalMin", intervalMin(c));
            o.put("radiusM", radiusM(c));
            o.put("lastAt", p.getLong("lastAt", 0));
            o.put("lastLat", Double.longBitsToDouble(p.getLong("lastLat", 0)));
            o.put("lastLng", Double.longBitsToDouble(p.getLong("lastLng", 0)));
            o.put("lastPlace", p.getString("lastPlace", ""));
            o.put("nearName", p.getString("nearName", ""));
            o.put("nearM", p.getLong("nearM", -1));
            o.put("nextAt", p.getLong("nextAt", 0));
            o.put("lastError", p.getString("lastError", ""));
            o.put("pending", p.getString("pending", null) != null ? new JSONObject(p.getString("pending", "{}")) : JSONObject.NULL);
            o.put("places", new PlacesDb(c).count());
            o.put("permission", hasLocationPermission(c));
            o.put("overlay", WherePopup.allowed(c));
            o.put("notifications", c.getSystemService(NotificationManager.class).areNotificationsEnabled());
        } catch (Exception ignored) {}
        return o;
    }

    // ---------- start / stop ----------

    static void start(Context c) {
        prefs(c).edit().putBoolean("recording", true).putInt("count", 0).remove("pending").remove("lastError").apply();
        c.startForegroundService(new Intent(c, TrackingService.class).setAction(ACTION_START));
    }

    static void stop(Context c) {
        prefs(c).edit().putBoolean("recording", false).putInt("count", 0).remove("pending").putLong("nextAt", 0).apply();
        cancelAlarm(c);
        c.getSystemService(NotificationManager.class).cancel(NOTIF_ASK);
        TrackingService s = instance;
        if (s != null) {
            if (s.popup != null) s.popup.hide();
            s.stopSelf();
        }
        MainActivity.push(c);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        popup = new WherePopup(this);
        ensureChannels(this);
        startAsForeground();
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (popup != null) popup.hide();
        cancelAlarm(this);
        releaseWake();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (!recording(this)) { stopSelf(); return START_NOT_STICKY; }
        capture(); // start, or an alarm arriving while the service had been stopped by the system
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Notification n = new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("กำลังบันทึกการเดินทาง")
                .setContentText("จับพิกัดทุก " + intervalLabel(intervalMin(this)) + " · ครั้งที่ " + prefs(this).getInt("count", 0) + "/" + checkEvery(this))
                .setOngoing(true)
                .setContentIntent(openApp(this))
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_RUN, n);
        }
    }

    static String intervalLabel(int min) {
        if (min < 60) return min + " นาที";
        return (min / 60) + " ชั่วโมง";
    }

    // ---------- alarms: one exact alarm per interval ----------

    private static PendingIntent alarmPi(Context c) {
        Intent i = new Intent(c, CaptureReceiver.class).setAction("com.norrawitro.gpstracker.CAPTURE");
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancelAlarm(Context c) {
        c.getSystemService(AlarmManager.class).cancel(alarmPi(c));
    }

    private void scheduleNext() {
        if (!recording(this)) return;
        long at = System.currentTimeMillis() + intervalMin(this) * 60_000L; // settings apply from the next round
        AlarmManager am = getSystemService(AlarmManager.class);
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(this));
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(this));
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(this));
        }
        prefs(this).edit().putLong("nextAt", at).apply();
    }

    // ---------- taking a fix ----------

    private void holdWake() {
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpstracker:fix");
            wake.setReferenceCounted(false);
        }
        wake.acquire(FIX_TIMEOUT_MS + 15_000);
    }

    private void releaseWake() {
        if (wake != null && wake.isHeld()) wake.release();
    }

    /** One fresh GPS fix, falling back to the newest last-known position if the sky is not visible in time. */
    void requestFix(FixCallback cb) {
        if (!hasLocationPermission(this)) { cb.onFix(null, "ไม่มีสิทธิ์เข้าถึงตำแหน่ง"); return; }
        LocationManager lm = getSystemService(LocationManager.class);
        final boolean[] done = {false};
        Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            Location last = bestLastKnown(lm);
            cb.onFix(last, last == null ? "จับสัญญาณ GPS ไม่ได้" : null);
        };
        main.postDelayed(timeout, FIX_TIMEOUT_MS);
        String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
                : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
        if (provider == null) {
            main.removeCallbacks(timeout);
            cb.onFix(null, "ปิดบริการตำแหน่ง (GPS) ของเครื่องอยู่");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, new CancellationSignal(), getMainExecutor(), loc -> {
                    if (done[0]) return;
                    done[0] = true;
                    main.removeCallbacks(timeout);
                    Location use = loc != null ? loc : bestLastKnown(lm);
                    cb.onFix(use, use == null ? "จับสัญญาณ GPS ไม่ได้" : null);
                });
            } else {
                lm.requestSingleUpdate(provider, loc -> {
                    if (done[0]) return;
                    done[0] = true;
                    main.removeCallbacks(timeout);
                    cb.onFix(loc, null);
                }, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            main.removeCallbacks(timeout);
            cb.onFix(null, "ไม่มีสิทธิ์เข้าถึงตำแหน่ง");
        }
    }

    private Location bestLastKnown(LocationManager lm) {
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {}
        return best;
    }

    void capture() {
        if (fixing) return;
        fixing = true;
        holdWake();
        requestFix((loc, err) -> {
            fixing = false;
            if (loc == null) {
                prefs(this).edit().putString("lastError", err).apply();
                scheduleNext();
                refresh();
                releaseWake();
                return;
            }
            long now = System.currentTimeMillis();
            onFix(now, loc.getLatitude(), loc.getLongitude());
            scheduleNext();
            refresh();
            releaseWake();
        });
    }

    private void onFix(long at, double lat, double lng) {
        SharedPreferences p = prefs(this);
        int count = p.getInt("count", 0) + 1;
        p.edit().putInt("count", count).putLong("lastAt", at)
                .putLong("lastLat", Double.doubleToRawLongBits(lat)).putLong("lastLng", Double.doubleToRawLongBits(lng))
                .remove("lastError").apply();

        boolean askingAlready = p.getString("pending", null) != null;
        if (count < checkEvery(this) || askingAlready) {
            write(at, lat, lng, "");
            return;
        }
        // Rule 10-12: compare with every saved place.
        PlacesDb.Place near = new PlacesDb(this).nearestWithin(lat, lng);
        if (near != null) {
            write(at, lat, lng, near.name);
            p.edit().putInt("count", 0).putString("lastPlace", near.name).apply();
            return;
        }
        try {
            JSONObject pend = new JSONObject();
            pend.put("at", at);
            pend.put("lat", lat);
            pend.put("lng", lng);
            p.edit().putString("pending", pend.toString()).apply();
        } catch (Exception ignored) {}
        if (!MainActivity.askWhere(this)) {
            // Rule 12 while the app is in the background: a floating window over any app, plus a notification.
            showAskNotification();
            String detail = String.format(java.util.Locale.US, "ยังไม่มีสถานที่ที่บันทึกไว้ใกล้จุดนี้\n%.5f, %.5f · %s",
                    lat, lng, TrackLog.stamp(at).substring(11, 16));
            popup.show(detail, this::answer);
        }
    }

    /** Rule 13/14: the person named the place, or closed the prompt without a name. */
    void answer(String name) {
        SharedPreferences p = prefs(this);
        String raw = p.getString("pending", null);
        getSystemService(NotificationManager.class).cancel(NOTIF_ASK);
        if (popup != null) popup.hide();
        if (raw == null) return;
        try {
            JSONObject pend = new JSONObject(raw);
            long at = pend.getLong("at");
            double lat = pend.getDouble("lat"), lng = pend.getDouble("lng");
            String clean = name == null ? "" : name.trim();
            if (!clean.isEmpty()) {
                new PlacesDb(this).add(clean, lat, lng, radiusM(this), TrackLog.stamp(at));
                p.edit().putString("lastPlace", clean).apply();
            }
            write(at, lat, lng, clean);
        } catch (Exception ignored) {}
        p.edit().remove("pending").putInt("count", 0).apply();
        refresh();
    }

    /** Rule 8: button 2 while recording — name the current position now. */
    void saveManualPlace(String name, FixCallback done) {
        holdWake();
        requestFix((loc, err) -> {
            releaseWake();
            if (loc == null) { done.onFix(null, err); return; }
            long at = System.currentTimeMillis();
            String clean = name.trim();
            new PlacesDb(this).add(clean, loc.getLatitude(), loc.getLongitude(), radiusM(this), TrackLog.stamp(at));
            prefs(this).edit().putString("lastPlace", clean).apply();
            write(at, loc.getLatitude(), loc.getLongitude(), clean);
            done.onFix(loc, null);
            refresh();
        });
    }

    private void write(long at, double lat, double lng, String place) {
        io.execute(() -> {
            try {
                PlacesDb.Place near = new PlacesDb(this).nearest(lat, lng);
                TrackLog.append(this, at, lat, lng, place, near == null ? null : near.name, near == null ? 0 : near.distanceM);
                SharedPreferences.Editor e = prefs(this).edit();
                if (near == null) e.remove("nearName").remove("nearM");
                else e.putString("nearName", near.name).putLong("nearM", Math.round(near.distanceM));
                e.apply();
            } catch (Exception e) {
                prefs(this).edit().putString("lastError", "เขียนไฟล์ไม่สำเร็จ: " + e.getMessage()).apply();
            }
            MainActivity.push(this);
        });
    }

    private void refresh() {
        startAsForeground(); // updates the ongoing notification's counter
        MainActivity.push(this);
    }

    // ---------- "คุณอยู่ที่ไหน?" when the app is not on screen ----------

    private void showAskNotification() {
        RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel("ชื่อสถานที่").build();
        PendingIntent replyPi = PendingIntent.getBroadcast(this, 2,
                new Intent(this, CaptureReceiver.class).setAction(CaptureReceiver.ACTION_ANSWER),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        PendingIntent dismissPi = PendingIntent.getBroadcast(this, 3,
                new Intent(this, CaptureReceiver.class).setAction(CaptureReceiver.ACTION_DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action reply = new Notification.Action.Builder(null, "บันทึก", replyPi)
                .addRemoteInput(input).build();
        Notification n = new Notification.Builder(this, CH_ASK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("คุณอยู่ที่ไหน?")
                .setContentText("พิมพ์ชื่อสถานที่แล้วกดบันทึก หรือปัดทิ้งเพื่อบันทึกแค่พิกัด")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openApp(this))
                .setDeleteIntent(dismissPi)
                .addAction(reply)
                .build();
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_ASK, n);
        } catch (SecurityException e) {
            answer(""); // cannot ask: keep the coordinates without a name
        }
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH_RUN) == null) {
            NotificationChannel ch = new NotificationChannel(CH_RUN, "กำลังบันทึกการเดินทาง", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("แสดงตลอดเวลาที่แอปกำลังจับพิกัด");
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_ASK) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ASK, "ถามชื่อสถานที่", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("ถามว่า \"คุณอยู่ที่ไหน?\" เมื่ออยู่ในที่ที่ยังไม่เคยบันทึก");
            nm.createNotificationChannel(ch);
        }
    }
}
