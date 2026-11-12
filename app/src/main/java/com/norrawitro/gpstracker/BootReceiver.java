package com.norrawitro.gpstracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * After the phone restarts, or the app is updated, nothing of ours is running.
 * Time reminders are set again. Recording that was on before resumes by itself when Android allows
 * background location ("อนุญาตตลอดเวลา"); otherwise a notification asks to tap to resume.
 */
public class BootReceiver extends BroadcastReceiver {
    static final int NOTIF_RESUME = 5102;

    static boolean backgroundLocation(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        Reminders.rescheduleAll(c);
        String action = String.valueOf(intent.getAction());
        boolean restart = action.endsWith("BOOT_COMPLETED") || action.endsWith("QUICKBOOT_POWERON")
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!restart || !TrackingService.recording(c) || TrackingService.instance != null) return;

        if (TrackingService.hasLocationPermission(c) && backgroundLocation(c)) {
            try {
                TrackingService.start(c);
                return;
            } catch (Exception ignored) {
                // Android refused to start background work; fall through to asking the person.
            }
        }
        TrackingService.prefs(c).edit().putBoolean("recording", false).putInt("count", 0).remove("pending")
                .putString("lastError", "หยุดบันทึกเพราะเปิดเครื่องใหม่หรืออัปเดตแอป กดเริ่มบันทึกอีกครั้ง").apply();
        TrackingService.ensureChannels(c);
        Notification n = new Notification.Builder(c, TrackingService.CH_ASK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("การบันทึกการเดินทางหยุดอยู่")
                .setContentText("แตะเพื่อเปิดแอปแล้วกดเริ่มบันทึกอีกครั้ง")
                .setAutoCancel(true)
                .setContentIntent(TrackingService.openApp(c))
                .build();
        try { c.getSystemService(NotificationManager.class).notify(NOTIF_RESUME, n); } catch (SecurityException ignored) {}
    }
}
