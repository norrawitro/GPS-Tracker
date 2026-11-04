package com.norrawitro.gpstracker;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Interval alarm, and the reply / swipe-away of the "คุณอยู่ที่ไหน?" notification. */
public class CaptureReceiver extends BroadcastReceiver {
    static final String ACTION_ANSWER = "com.norrawitro.gpstracker.ANSWER";
    static final String ACTION_DISMISS = "com.norrawitro.gpstracker.DISMISS";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = String.valueOf(intent.getAction());
        TrackingService s = TrackingService.instance;
        if (ACTION_ANSWER.equals(action) || ACTION_DISMISS.equals(action)) {
            String name = "";
            Bundle r = RemoteInput.getResultsFromIntent(intent);
            if (r != null && r.getCharSequence(TrackingService.KEY_REPLY) != null) {
                name = r.getCharSequence(TrackingService.KEY_REPLY).toString();
            }
            if (s != null) s.answer(name);
            return;
        }
        // Interval alarm.
        if (!TrackingService.recording(c)) return;
        if (s != null) {
            s.capture();
            return;
        }
        // The system stopped the service; try to bring it back, or record that tracking stopped.
        try {
            c.startForegroundService(new Intent(c, TrackingService.class).setAction("capture"));
        } catch (Exception e) {
            TrackingService.prefs(c).edit().putBoolean("recording", false)
                    .putString("lastError", "Android หยุดการบันทึกระหว่างทำงานเบื้องหลัง กดเริ่มใหม่อีกครั้ง").apply();
        }
    }
}
