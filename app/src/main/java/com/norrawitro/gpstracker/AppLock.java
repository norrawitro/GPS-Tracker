package com.norrawitro.gpstracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Optional password to open the app. Only a salted PBKDF2 hash is kept. Recording and the
 * "คุณอยู่ที่ไหน?" notification keep working while the app is locked.
 */
public final class AppLock {
    private static final int ITERATIONS = 120_000;
    private static final int MAX_TRIES = 5;
    private static final long WAIT_MS = 30_000;

    private AppLock() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lock", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getString("hash", null) != null;
    }

    private static byte[] derive(String pw, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    /** Checks a password without counting tries (used before changing it). */
    private static boolean matches(Context c, String pw) throws Exception {
        SharedPreferences p = prefs(c);
        String hash = p.getString("hash", null), salt = p.getString("salt", null);
        if (hash == null || salt == null) return true;
        byte[] got = derive(pw, Base64.decode(salt, Base64.NO_WRAP));
        return MessageDigest.isEqual(got, Base64.decode(hash, Base64.NO_WRAP));
    }

    /** Milliseconds to wait before another try is allowed (after too many wrong ones), or 0. */
    public static long waitMs(Context c) {
        long until = prefs(c).getLong("lockedUntil", 0);
        return Math.max(0, until - System.currentTimeMillis());
    }

    /** Returns "" on success, otherwise a message for the person. */
    public static String unlock(Context c, String pw) {
        long wait = waitMs(c);
        if (wait > 0) return "ใส่รหัสผิดหลายครั้ง รออีก " + ((wait + 999) / 1000) + " วินาที";
        try {
            if (matches(c, pw == null ? "" : pw)) {
                prefs(c).edit().putInt("fails", 0).apply();
                return "";
            }
        } catch (Exception e) {
            return "ตรวจรหัสผ่านไม่ได้";
        }
        int fails = prefs(c).getInt("fails", 0) + 1;
        SharedPreferences.Editor e = prefs(c).edit().putInt("fails", fails);
        if (fails >= MAX_TRIES) {
            e.putInt("fails", 0).putLong("lockedUntil", System.currentTimeMillis() + WAIT_MS);
            e.apply();
            return "ใส่รหัสผิด " + MAX_TRIES + " ครั้ง รอ 30 วินาทีแล้วลองใหม่";
        }
        e.apply();
        return "รหัสผ่านไม่ถูกต้อง (เหลืออีก " + (MAX_TRIES - fails) + " ครั้ง)";
    }

    /**
     * Sets, changes or (with an empty new password) removes the password.
     * Returns "" on success, otherwise a message for the person.
     */
    public static String change(Context c, String current, String next) {
        try {
            if (enabled(c) && !matches(c, current == null ? "" : current)) return "รหัสผ่านปัจจุบันไม่ถูกต้อง";
            if (next == null || next.isEmpty()) {
                prefs(c).edit().clear().apply();
                return "";
            }
            if (next.length() < 4) return "รหัสผ่านต้องยาวอย่างน้อย 4 ตัว";
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            prefs(c).edit()
                    .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("hash", Base64.encodeToString(derive(next, salt), Base64.NO_WRAP))
                    .putInt("fails", 0).remove("lockedUntil")
                    .apply();
            return "";
        } catch (Exception e) {
            return "ตั้งรหัสผ่านไม่สำเร็จ";
        }
    }
}
