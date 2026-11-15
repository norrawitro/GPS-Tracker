package com.norrawitro.gpstracker;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Looper;

/**
 * Speed alert (owner order 2026-11-15): while it is on, GPS is read every second inside the tracking service, and a short
 * "beep-beep" plays when the speed is over the limit typed in Settings. Sound only: no text, no popup.
 * While still over the limit it beeps again every 10 seconds; after dropping to the limit or below, the next time over
 * beeps at once.
 */
final class SpeedAlert implements LocationListener {
    static final long REPEAT_MS = 10_000;

    private final Context ctx;
    private boolean running;
    private long lastBeep;
    private long lastPush;

    SpeedAlert(Context ctx) { this.ctx = ctx; }

    // ---------- settings ----------

    static boolean on(Context c) { return TrackingService.prefs(c).getBoolean("speedOn", false); }
    static int limit(Context c) { return TrackingService.prefs(c).getInt("speedLimit", 0); }
    /** On and a limit is set: the service must keep running for it. */
    static boolean active(Context c) { return on(c) && limit(c) > 0; }

    // ---------- watching ----------

    boolean running() { return running; }

    void start() {
        if (running || !TrackingService.hasLocationPermission(ctx)) return;
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            running = true;
            lastBeep = 0;
        } catch (SecurityException | IllegalArgumentException e) {
            TrackingService.prefs(ctx).edit().putString("speedError", "เปิด GPS ไม่ได้: " + e.getMessage()).apply();
        }
    }

    void stop() {
        if (!running) return;
        try { ctx.getSystemService(LocationManager.class).removeUpdates(this); } catch (Exception ignored) {}
        running = false;
        TrackingService.prefs(ctx).edit().putInt("speedNow", -1).apply();
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null || !loc.hasSpeed()) return;
        // Skip readings the phone itself marks as poor (more than 5 m/s uncertain).
        if (loc.hasSpeedAccuracy() && loc.getSpeedAccuracyMetersPerSecond() > 5f) return;
        int kmh = Math.round(loc.getSpeed() * 3.6f);
        int lim = limit(ctx);
        long now = System.currentTimeMillis();
        if (lim > 0 && kmh > lim) {
            if (now - lastBeep >= REPEAT_MS) {
                lastBeep = now;
                Beep.play();
            }
        } else {
            lastBeep = 0;
        }
        TrackingService.prefs(ctx).edit().putInt("speedNow", kmh).apply();
        if (now - lastPush >= 2000) { lastPush = now; MainActivity.push(ctx); }
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    // ---------- the sound: two short beeps, made in code ----------

    static final class Beep {
        private static final int RATE = 22050;
        private static short[] pcm;

        private Beep() {}

        private static short[] make() {
            double tone = 0.11, gap = 0.08;
            int n = (int) (RATE * (tone * 2 + gap));
            short[] out = new short[n];
            for (int hit = 0; hit < 2; hit++) {
                int start = (int) (hit * (tone + gap) * RATE);
                int len = (int) (tone * RATE);
                for (int i = 0; i < len && start + i < n; i++) {
                    double t = i / (double) RATE;
                    // 8 ms fade in and out so the beep does not click.
                    double env = Math.min(1, Math.min(t, tone - t) / 0.008);
                    out[start + i] = (short) (Math.sin(2 * Math.PI * 1760 * t) * 12000 * env);
                }
            }
            return out;
        }

        static void play() {
            new Thread(() -> {
                try {
                    if (pcm == null) pcm = make();
                    AudioTrack track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    // The navigation-guidance stream plays over music like a navigation app's voice.
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(pcm.length * 2)
                            .build();
                    track.write(pcm, 0, pcm.length);
                    track.play();
                    Thread.sleep(pcm.length * 1000L / RATE + 150);
                    track.release();
                } catch (Exception ignored) {}
            }, "speed-beep").start();
        }
    }
}
