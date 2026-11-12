package com.norrawitro.gpstracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SQLite tables: named places (used to recognise where the person is; not editable from the file viewer)
 * and reminders. One shared helper for the whole app: separate helpers opened per GPS fix were never
 * closed and could block one another on the database lock.
 */
public class PlacesDb extends SQLiteOpenHelper {
    private static PlacesDb shared;

    public static synchronized PlacesDb get(Context c) {
        if (shared == null) shared = new PlacesDb(c.getApplicationContext());
        return shared;
    }

    public static final class Place {
        public final String name;
        public final double lat, lng, radiusM, distanceM;
        Place(String name, double lat, double lng, double radiusM, double distanceM) {
            this.name = name; this.lat = lat; this.lng = lng; this.radiusM = radiusM; this.distanceM = distanceM;
        }
    }

    private PlacesDb(Context c) {
        super(c, "places.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE places (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +          // ชื่อสถานที่
                "lat REAL NOT NULL," +            // ละติจูด
                "lng REAL NOT NULL," +            // ลองจิจูด
                "radius_m REAL NOT NULL," +       // รัศมีจับคู่ (เมตร)
                "first_saved TEXT NOT NULL)");    // วันที่บันทึกครั้งแรก
        createReminders(db);
    }

    private static void createReminders(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reminders (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "text TEXT NOT NULL," +           // ข้อความเตือน
                "kind TEXT NOT NULL," +           // time | place
                "time TEXT," +                    // HH:MM (kind = time), repeats every day
                "place TEXT," +                   // saved place name (kind = place), or a label for a pin
                "pin_lat REAL," +                 // a pin dropped for this reminder only (kind = place)
                "pin_lng REAL," +
                "pin_radius REAL," +
                "enabled INTEGER NOT NULL DEFAULT 1," +
                "armed INTEGER NOT NULL DEFAULT 1," + // place: 1 = will fire on the next entry
                "created TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            createReminders(db); // already has the pin columns
        } else if (oldV < 3) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN pin_lat REAL");
            db.execSQL("ALTER TABLE reminders ADD COLUMN pin_lng REAL");
            db.execSQL("ALTER TABLE reminders ADD COLUMN pin_radius REAL");
        }
    }

    // ---------- reminders ----------

    public JSONArray reminders() throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, text, kind, time, place, enabled, pin_lat, pin_lng, pin_radius FROM reminders ORDER BY kind, time, place, id", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("text", c.getString(1));
                o.put("kind", c.getString(2));
                o.put("time", c.isNull(3) ? "" : c.getString(3));
                o.put("place", c.isNull(4) ? "" : c.getString(4));
                o.put("enabled", c.getInt(5) == 1);
                if (!c.isNull(6)) { o.put("pinLat", c.getDouble(6)); o.put("pinLng", c.getDouble(7)); o.put("pinRadius", c.getDouble(8)); }
                out.put(o);
            }
        }
        return out;
    }

    public long addReminder(String text, String kind, String time, String place, String created) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("kind", kind);
        v.put("time", time);
        v.put("place", place);
        v.put("enabled", 1);
        v.put("armed", 1);
        v.put("created", created);
        return getWritableDatabase().insert("reminders", null, v);
    }

    /** A place reminder on a pin dropped for it, not one of the saved places. */
    public long addPinReminder(String text, String label, double lat, double lng, double radiusM, String created) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("kind", "place");
        v.put("place", label);
        v.put("pin_lat", lat);
        v.put("pin_lng", lng);
        v.put("pin_radius", radiusM);
        v.put("enabled", 1);
        v.put("armed", 1);
        v.put("created", created);
        return getWritableDatabase().insert("reminders", null, v);
    }

    public void setReminderEnabled(long id, boolean on) {
        ContentValues v = new ContentValues();
        v.put("enabled", on ? 1 : 0);
        if (on) v.put("armed", 1);
        getWritableDatabase().update("reminders", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteReminder(long id) {
        getWritableDatabase().delete("reminders", "id=?", new String[]{String.valueOf(id)});
    }

    public void setArmed(long id, boolean armed) {
        ContentValues v = new ContentValues();
        v.put("armed", armed ? 1 : 0);
        getWritableDatabase().update("reminders", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** One reminder as {id, text, kind, time, place, enabled, armed}, or null. */
    public JSONObject reminder(long id) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, text, kind, time, place, enabled, armed, pin_lat, pin_lng, pin_radius FROM reminders WHERE id=?", new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst()) return null;
            JSONObject o = new JSONObject();
            o.put("id", c.getLong(0));
            o.put("text", c.getString(1));
            o.put("kind", c.getString(2));
            o.put("time", c.isNull(3) ? "" : c.getString(3));
            o.put("place", c.isNull(4) ? "" : c.getString(4));
            o.put("enabled", c.getInt(5) == 1);
            o.put("armed", c.getInt(6) == 1);
            if (!c.isNull(7)) { o.put("pinLat", c.getDouble(7)); o.put("pinLng", c.getDouble(8)); o.put("pinRadius", c.getDouble(9)); }
            return o;
        }
    }

    /** Place names that have at least one saved point, A-Z, for the reminder picker. */
    public JSONArray placeNames() throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT name FROM places ORDER BY name", null)) {
            while (c.moveToNext()) out.put(c.getString(0));
        }
        return out;
    }

    /** True when the point is inside the radius of any saved point with this name. */
    public boolean insideNamed(String name, double lat, double lng) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT lat, lng, radius_m FROM places WHERE name=?", new String[]{name})) {
            while (c.moveToNext()) {
                if (distanceM(lat, lng, c.getDouble(0), c.getDouble(1)) <= c.getDouble(2)) return true;
            }
        }
        return false;
    }

    public void add(String name, double lat, double lng, double radiusM, String savedAt) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("lat", lat);
        v.put("lng", lng);
        v.put("radius_m", radiusM);
        v.put("first_saved", savedAt);
        getWritableDatabase().insert("places", null, v);
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM places", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** The closest saved place whose own radius contains the point, or null. */
    public Place nearestWithin(double lat, double lng) {
        Place best = null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name, lat, lng, radius_m FROM places", null)) {
            while (c.moveToNext()) {
                double d = distanceM(lat, lng, c.getDouble(1), c.getDouble(2));
                if (d <= c.getDouble(3) && (best == null || d < best.distanceM)) {
                    best = new Place(c.getString(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), d);
                }
            }
        }
        return best;
    }

    /** The closest saved place at any distance, or null when none are saved. */
    public Place nearest(double lat, double lng) {
        Place best = null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name, lat, lng, radius_m FROM places", null)) {
            while (c.moveToNext()) {
                double d = distanceM(lat, lng, c.getDouble(1), c.getDouble(2));
                if (best == null || d < best.distanceM) {
                    best = new Place(c.getString(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), d);
                }
            }
        }
        return best;
    }

    /** Great-circle distance in metres (haversine). */
    public static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371008.8;
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
