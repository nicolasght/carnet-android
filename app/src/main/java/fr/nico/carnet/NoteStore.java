package fr.nico.carnet;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Local, transactional storage. Versions are never silently pruned. */
public final class NoteStore extends SQLiteOpenHelper {
    public record Note(long id, String title, String body, long updated, boolean pinned, boolean deleted) {
        public String label() { return title.trim().isEmpty() ? "Sans titre" : title; }
    }
    public record Version(long id, String title, String body, long saved) {}
    public NoteStore(Context context) { super(context, "carnet.db", null, 1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,body TEXT NOT NULL,updated INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE versions(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,title TEXT NOT NULL,body TEXT NOT NULL,saved INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX versions_note ON versions(note_id,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Migration requise"); }
    private static ContentValues content(String title, String body) {
        ContentValues v = new ContentValues(); v.put("title", title); v.put("body", body); return v;
    }
    public long create(String title, String body) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            long now = System.currentTimeMillis(); ContentValues v = content(title, body); v.put("updated", now);
            long id = db.insertOrThrow("notes", null, v); addVersion(db, id, title, body, now);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    private void addVersion(SQLiteDatabase db, long id, String title, String body, long time) {
        ContentValues v = content(title, body); v.put("note_id", id); v.put("saved", time);
        db.insertOrThrow("versions", null, v);
    }
    public boolean save(long id, String title, String body) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Note old = get(id);
            if (old == null) throw new IllegalArgumentException("Note introuvable");
            if (old.title.equals(title) && old.body.equals(body)) return false;
            long now = System.currentTimeMillis(); ContentValues v = content(title, body); v.put("updated", now);
            db.update("notes", v, "id=?", new String[]{"" + id}); addVersion(db, id, title, body, now);
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    private Note read(Cursor c) { return new Note(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) != 0, c.getInt(5) != 0); }
    public Note get(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM notes WHERE id=?", new String[]{"" + id})) { return c.moveToFirst() ? read(c) : null; }
    }
    public List<Note> list(String query, int filter) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String sql = "SELECT * FROM notes WHERE deleted=? AND (title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\')" + (filter == 1 ? " AND pinned=1" : "") + " ORDER BY pinned DESC,updated DESC,id DESC";
        ArrayList<Note> notes = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{filter == 2 ? "1" : "0", "%" + escaped + "%", "%" + escaped + "%"})) { while (c.moveToNext()) notes.add(read(c)); }
        return notes;
    }
    public void flag(long id, String key, boolean value) {
        if (!key.equals("pinned") && !key.equals("deleted")) throw new IllegalArgumentException("Champ invalide");
        ContentValues v = new ContentValues(); v.put(key, value ? 1 : 0); getWritableDatabase().update("notes", v, "id=?", new String[]{"" + id});
    }
    public void remove(long id) { getWritableDatabase().delete("notes", "id=? AND deleted=1", new String[]{"" + id}); }
    public List<Version> history(long id) {
        ArrayList<Version> versions = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,body,saved FROM versions WHERE note_id=? ORDER BY id DESC", new String[]{"" + id})) {
            while (c.moveToNext()) versions.add(new Version(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)));
        } return versions;
    }
    public JSONObject backup() throws JSONException {
        SQLiteDatabase db = getReadableDatabase(); db.beginTransaction();
        try {
            JSONArray notes = new JSONArray();
            try (Cursor c = db.rawQuery("SELECT * FROM notes ORDER BY id", null)) {
                while(c.moveToNext()) {
                    Note n = read(c); JSONArray versions = new JSONArray();
                    for (Version v : history(n.id)) versions.put(new JSONObject().put("title", v.title).put("body", v.body).put("saved", v.saved));
                    notes.put(new JSONObject().put("title", n.title).put("body", n.body).put("updated", n.updated).put("pinned", n.pinned).put("deleted", n.deleted).put("versions", versions));
                }
            }
            db.setTransactionSuccessful(); return new JSONObject().put("format", "carnet").put("version", 1).put("notes", notes);
        } finally { db.endTransaction(); }
    }
    private static String checkedText(JSONObject o, String key) throws JSONException {
        Object value = o.get(key);
        if (!(value instanceof String) || ((String)value).length() > 1_000_000) throw new JSONException("Texte invalide ou trop long");
        return (String)value;
    }
    private static long checkedTime(JSONObject o, String key) throws JSONException {
        Object value = o.get(key);
        if (!(value instanceof Number) || ((Number)value).longValue() < 0) throw new JSONException("Date invalide");
        return ((Number)value).longValue();
    }
    public int importBackup(JSONObject backup) throws JSONException {
        if (!"carnet".equals(backup.getString("format")) || backup.getInt("version") != 1) throw new JSONException("Format de sauvegarde incompatible");
        JSONArray notes = backup.getJSONArray("notes");
        if (notes.length() > 10000) throw new JSONException("Trop de notes");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction(); int count = 0;
        try {
            for (int i = 0; i < notes.length(); i++) {
                JSONObject n = notes.getJSONObject(i); String title = checkedText(n, "title"), body = checkedText(n, "body");
                ContentValues values = content(title, body); long updated = checkedTime(n, "updated"); values.put("updated", updated);
                values.put("pinned", n.getBoolean("pinned")); values.put("deleted", n.getBoolean("deleted"));
                long id = db.insertOrThrow("notes", null, values); JSONArray versions = n.getJSONArray("versions");
                count += versions.length(); if (count > 100000) throw new JSONException("Trop de versions");
                for (int j = versions.length() - 1; j >= 0; j--) {
                    JSONObject v = versions.getJSONObject(j); addVersion(db, id, checkedText(v, "title"), checkedText(v, "body"), checkedTime(v, "saved"));
                }
                if (versions.length() == 0) addVersion(db, id, title, body, updated);
            }
            db.setTransactionSuccessful(); return notes.length();
        } finally { db.endTransaction(); }
    }
}
