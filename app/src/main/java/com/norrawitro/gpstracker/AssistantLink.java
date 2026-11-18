package com.norrawitro.gpstracker;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;

/**
 * Tells N-assistant where the phone is, so it can remind the owner when they arrive at or leave a saved place
 * ("ออกจากบ้าน → อย่าลืมเติมน้ำมัน"). Explicit broadcasts to the N-assistant package, guarded by a signature
 * permission that only apps signed with the owner's key hold. Nothing leaves the phone.
 *
 * Events (action PLACE_EVENT, extra "type"):
 *   enter / leave: arrived at / left the saved place "place";
 *   fix: every recorded fix, with the current place ("" when not at a saved place).
 * Every event also carries lat, lng, at and "places" (the saved place names, for N-assistant's place picker).
 */
final class AssistantLink {
    static final String ACTION = "com.norrawitro.gpstracker.PLACE_EVENT";
    static final String PERMISSION = "com.norrawitro.gpstracker.permission.PLACE_EVENTS";
    static final String TARGET = "com.norrawitro.nassistant";

    private AssistantLink() {}

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("assistant_link", Context.MODE_PRIVATE); }

    /**
     * Called with each recorded fix. `near` is the saved place the fix is in, or null.
     * Leaving needs the fix to be clearly outside (twice the place radius, at least 200 m), so GPS jitter at the
     * edge of a place does not send leave/enter over and over.
     */
    static void onFix(Context c, long at, double lat, double lng, PlacesDb.Place near) {
        SharedPreferences p = prefs(c);
        String cur = p.getString("place", "");
        double cLat = Double.longBitsToDouble(p.getLong("lat", 0)), cLng = Double.longBitsToDouble(p.getLong("lng", 0));
        float cRad = p.getFloat("radius", 100);
        String now = cur;
        if (near != null && !near.name.equals(cur)) {
            if (!cur.isEmpty()) send(c, "leave", cur, at, lat, lng);
            now = near.name;
            p.edit().putString("place", now).putLong("lat", Double.doubleToRawLongBits(near.lat))
                    .putLong("lng", Double.doubleToRawLongBits(near.lng)).putFloat("radius", (float) near.radiusM).apply();
            send(c, "enter", now, at, lat, lng);
        } else if (near == null && !cur.isEmpty()
                && PlacesDb.distanceM(lat, lng, cLat, cLng) > Math.max(200, 2 * cRad)) {
            send(c, "leave", cur, at, lat, lng);
            now = "";
            p.edit().putString("place", "").apply();
        }
        send(c, "fix", now, at, lat, lng);
    }

    /** Recording stopped: N-assistant should not think the phone is still somewhere. */
    static void onStop(Context c) {
        prefs(c).edit().putString("place", "").apply();
        send(c, "stopped", "", System.currentTimeMillis(), 0, 0);
    }

    private static void send(Context c, String type, String place, long at, double lat, double lng) {
        try {
            Intent i = new Intent(ACTION).setPackage(TARGET)
                    .putExtra("type", type).putExtra("place", place == null ? "" : place)
                    .putExtra("at", at).putExtra("lat", lat).putExtra("lng", lng);
            try { JSONArray names = PlacesDb.get(c).placeNames(); i.putExtra("places", names.toString()); } catch (Exception ignored) {}
            c.sendBroadcast(i, PERMISSION);
        } catch (Exception ignored) {
            // N-assistant not installed, or an older version: nothing to do.
        }
    }
}
