package com.norrawitro.gpstracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** SQLite table of named places, used to recognise where the person is. Not editable from the file viewer. */
public class PlacesDb extends SQLiteOpenHelper {
    public static final class Place {
        public final String name;
        public final double lat, lng, radiusM, distanceM;
        Place(String name, double lat, double lng, double radiusM, double distanceM) {
            this.name = name; this.lat = lat; this.lng = lng; this.radiusM = radiusM; this.distanceM = distanceM;
        }
    }

    public PlacesDb(Context c) {
        super(c, "places.db", null, 1);
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

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
