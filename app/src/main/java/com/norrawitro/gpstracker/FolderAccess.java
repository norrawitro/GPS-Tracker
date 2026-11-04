package com.norrawitro.gpstracker;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reading route files through a folder the person picked (Storage Access Framework).
 * MediaStore only shows files this install created; files left by an earlier install of the app
 * stay invisible to it. Once the person grants Documents/GPS-Track, every file there can be read.
 */
final class FolderAccess {
    private FolderAccess() {}

    static Uri tree(Context c) {
        String s = c.getSharedPreferences("folder", Context.MODE_PRIVATE).getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission()) return u;
        }
        return null; // permission was revoked
    }

    static void save(Context c, Uri tree) {
        c.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.getSharedPreferences("folder", Context.MODE_PRIVATE).edit().putString("tree", tree.toString()).apply();
    }

    /** Opens the picker at Documents/GPS-Track when the system allows a starting point. */
    static Intent pickIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            Uri start = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/GPS-Track");
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);
        } catch (Exception ignored) {}
        return i;
    }

    /** Every *-track*.csv under the picked folder: [{id: document uri, name, folder, size}]. */
    static JSONArray list(Context c, Uri tree) throws Exception {
        JSONArray out = new JSONArray();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        walk(c.getContentResolver(), tree, rootId, "", out, 0);
        return out;
    }

    private static void walk(ContentResolver cr, Uri tree, String docId, String path, JSONArray out, int depth) throws Exception {
        if (depth > 6) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] proj = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cur = cr.query(children, proj, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(cr, tree, id, path + name + "/", out, depth + 1);
                } else if (name != null && name.matches("\\d{4}-\\d{2}-\\d{2}-track.*\\.csv")) {
                    JSONObject o = new JSONObject();
                    o.put("id", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                    o.put("name", name);
                    o.put("folder", TrackLog.ROOT + path);
                    o.put("size", cur.isNull(3) ? 0 : cur.getLong(3));
                    out.put(o);
                }
            }
        }
    }
}
