package com.norrawitro.gpstracker;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Daily route files: Documents/GPS-Track/YYYY/MM/DD/YYYY-MM-DD-track.csv
 * Columns: วัน-เดือน-ปี เวลา, ละติจูด, ลองจิจูด, ชื่อสถานที่ (empty when unknown).
 * The folder comes from each row's own time, so a changed clock starts a new day's folder.
 */
public final class TrackLog {
    public static final String ROOT = "Documents/GPS-Track/";
    private static final String HEADER = "วันเวลา,ละติจูด,ลองจิจูด,ชื่อสถานที่\r\n";
    private static final Uri FILES = MediaStore.Files.getContentUri("external");

    private TrackLog() {}

    public static String stamp(long millis) {
        return new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private static String folderFor(long millis) {
        return ROOT + new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new Date(millis)) + "/";
    }

    private static String fileFor(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis)) + "-track.csv";
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
        }
        return v;
    }

    /** Appends one row to that day's file, creating folders and the file when needed. */
    public static synchronized void append(Context c, long millis, double lat, double lng, String place) throws Exception {
        ContentResolver cr = c.getContentResolver();
        String folder = folderFor(millis), name = fileFor(millis);
        Uri uri = find(cr, folder, name);
        boolean fresh = false;
        if (uri == null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
            uri = cr.insert(FILES, v);
            if (uri == null) throw new Exception("สร้างไฟล์บันทึกไม่ได้");
            fresh = true;
        }
        String row = stamp(millis) + "," + String.format(Locale.US, "%.6f", lat) + ","
                + String.format(Locale.US, "%.6f", lng) + "," + csv(place == null ? "" : place.trim()) + "\r\n";
        try (OutputStream os = cr.openOutputStream(uri, "wa")) {
            if (os == null) throw new Exception("เปิดไฟล์บันทึกไม่ได้");
            if (fresh) {
                os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM so Excel reads Thai correctly
                os.write(HEADER.getBytes(StandardCharsets.UTF_8));
            }
            os.write(row.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Uri find(ContentResolver cr, String folder, String name) {
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cur = cr.query(FILES, new String[]{MediaStore.MediaColumns._ID}, sel, new String[]{folder, name}, null)) {
            if (cur != null && cur.moveToFirst()) return ContentUris.withAppendedId(FILES, cur.getLong(0));
        }
        return null;
    }

    /** Every route file this app can see, newest first: [{id, name, folder, size}]. */
    public static JSONArray list(Context c) throws Exception {
        JSONArray out = new JSONArray();
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE};
        try (Cursor cur = c.getContentResolver().query(FILES, proj, sel, new String[]{ROOT + "%", "%-track.csv"},
                MediaStore.MediaColumns.DISPLAY_NAME + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", cur.getLong(0));
                o.put("name", cur.getString(1));
                o.put("folder", cur.getString(2));
                o.put("size", cur.getLong(3));
                out.put(o);
            }
        }
        return out;
    }

    /** Rows of one file (header and BOM removed): [[time, lat, lng, place], ...]. */
    public static JSONArray read(Context c, long id) throws Exception {
        JSONArray rows = new JSONArray();
        Uri uri = ContentUris.withAppendedId(FILES, id);
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("เปิดไฟล์ไม่ได้");
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) {
                    first = false;
                    if (line.startsWith("﻿")) line = line.substring(1);
                    if (line.startsWith("วันเวลา")) continue;
                }
                if (line.trim().isEmpty()) continue;
                rows.put(parse(line));
            }
        }
        return rows;
    }

    private static JSONArray parse(String line) {
        JSONArray cells = new JSONArray();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') q = false;
                else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { cells.put(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        cells.put(cur.toString());
        return cells;
    }
}
