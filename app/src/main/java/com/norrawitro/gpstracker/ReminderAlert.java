package com.norrawitro.gpstracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shows a reminder: a box in the middle of the screen (over any app, when "display over other apps"
 * is allowed) with the reminder's text, a short bicycle-bell sound, and a notification as backup.
 */
final class ReminderAlert {
    private static final String CHANNEL = "reminder_v1";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View shown;

    private ReminderAlert() {}

    static void show(Context ctx, long id, String title, String text) {
        Context app = ctx.getApplicationContext();
        Bell.ring();
        notify(app, id, title, text);
        MAIN.post(() -> showBox(app, title, text));
    }

    private static void notify(Context c, long id, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "การเตือนความจำ", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("การเตือนตามเวลาและสถานที่ที่ตั้งไว้");
            ch.setSound(null, null); // the app plays its own bell
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(TrackingService.openApp(c))
                .build();
        try { nm.notify((int) (6000 + (id % 1000)), n); } catch (SecurityException ignored) {}
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static void hide(Context c) {
        if (shown == null) return;
        try { c.getSystemService(WindowManager.class).removeView(shown); } catch (Exception ignored) {}
        shown = null;
    }

    private static void showBox(Context c, String title, String text) {
        if (!WherePopup.allowed(c)) return; // the notification is the fallback
        hide(c);
        boolean dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? Color.parseColor("#18211D") : Color.WHITE;
        int ink = dark ? Color.parseColor("#E4ECE7") : Color.parseColor("#15231D");
        int muted = dark ? Color.parseColor("#97A89F") : Color.parseColor("#5B6B63");
        int accent = dark ? Color.parseColor("#6CC79C") : Color.parseColor("#1F6B4F");
        int accentInk = dark ? Color.parseColor("#0D1A14") : Color.WHITE;

        FrameLayout outer = new FrameLayout(c);
        outer.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(bg);
        cardBg.setCornerRadius(dp(c, 18));
        cardBg.setStroke(dp(c, 2), accent);
        card.setBackground(cardBg);
        card.setElevation(dp(c, 12));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(muted);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(t);
        TextView body = new TextView(c);
        body.setText(text);
        body.setTextColor(ink);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        body.setTypeface(Typeface.DEFAULT_BOLD);
        body.setPadding(0, dp(c, 6), 0, dp(c, 14));
        card.addView(body);

        Button ok = new Button(c);
        ok.setText("รับทราบ");
        ok.setAllCaps(false);
        ok.setTextColor(accentInk);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(accent);
        okBg.setCornerRadius(dp(c, 10));
        ok.setBackground(okBg);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.gravity = Gravity.END;
        card.addView(ok, op);
        ok.setOnClickListener(v -> hide(c));

        outer.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // No keyboard focus and not touch-modal, so the rest of the phone stays usable.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        try {
            c.getSystemService(WindowManager.class).addView(outer, lp);
            shown = outer;
        } catch (Exception ignored) {
            shown = null;
        }
    }

    /** A short two-strike bicycle bell, synthesised so the app needs no sound file. */
    static final class Bell {
        private static final int RATE = 22050;
        private static short[] pcm;

        private Bell() {}

        private static short[] make() {
            double strike = 0.18, ring = 0.55;
            int n = (int) (RATE * (strike + ring));
            short[] out = new short[n];
            // Partials of a small bell, each decaying at its own rate.
            double[][] partials = {{2093, 1.0, 9}, {4186, 0.45, 14}, {5320, 0.30, 18}, {6300, 0.18, 24}};
            for (int hit = 0; hit < 2; hit++) {
                int start = (int) (hit * strike * RATE);
                for (int i = start; i < n; i++) {
                    double t = (i - start) / (double) RATE;
                    double s = 0;
                    for (double[] p : partials) s += p[1] * Math.exp(-p[2] * t) * Math.sin(2 * Math.PI * p[0] * t);
                    int v = out[i] + (int) (s * 7000);
                    out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
                }
            }
            return out;
        }

        static void ring() {
            new Thread(() -> {
                try {
                    if (pcm == null) pcm = make();
                    AudioTrack track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
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
                    Thread.sleep(pcm.length * 1000L / RATE + 200);
                    track.release();
                } catch (Exception ignored) {}
            }, "bell").start();
        }
    }
}
