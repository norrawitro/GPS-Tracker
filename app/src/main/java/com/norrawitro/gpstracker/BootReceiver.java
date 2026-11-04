package com.norrawitro.gpstracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart nothing is running, so the saved state is set back to "หยุดบันทึก". */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (TrackingService.recording(c)) {
            TrackingService.prefs(c).edit().putBoolean("recording", false).putInt("count", 0).remove("pending")
                    .putString("lastError", "หยุดบันทึกเพราะเปิดเครื่องใหม่ กดเริ่มบันทึกอีกครั้ง").apply();
        }
    }
}
