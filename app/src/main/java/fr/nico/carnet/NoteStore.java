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
    public record Note(long id, String body, long updated) {}
    public record Version(long id, String body, long saved) {}
    public NoteStore(Context context) { super(context, "carnet.db", null, 3); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,body TEXT NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE versions(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,body TEXT NOT NULL,saved INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX versions_note ON versions(note_id,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion > 2 || newVersion != 3) throw new IllegalStateException("Migration requise");
        if(oldVersion==1) {
        // The helper runs this migration transactionally. Preserve former trash as regular notes.
        db.execSQL("ALTER TABLE versions RENAME TO legacy_versions");
        db.execSQL("ALTER TABLE notes RENAME TO legacy_notes");
        db.execSQL("DROP INDEX versions_note");
        onCreate(db);
        String text = "CASE WHEN trim(title)='' THEN body WHEN body='' THEN title ELSE title||char(10)||char(10)||body END";
        db.execSQL("INSERT INTO notes(id,body,updated) SELECT id," + text + ",updated FROM legacy_notes");
        db.execSQL("INSERT INTO versions(id,note_id,body,saved) SELECT id,note_id," + text + ",saved FROM legacy_versions");
        db.execSQL("DROP TABLE legacy_versions");
        db.execSQL("DROP TABLE legacy_notes");
        }
        List<Long> empty=new ArrayList<>();
        try(Cursor notes=db.rawQuery("SELECT id,body FROM notes",null)) {
            while(notes.moveToNext())if(isBlank(notes.getString(1)))empty.add(notes.getLong(0));
        }
        for(long id:empty) {
            boolean restored=false;
            try(Cursor versions=db.rawQuery("SELECT body,saved FROM versions WHERE note_id=? ORDER BY id DESC",new String[]{""+id})) {
                while(versions.moveToNext())if(!isBlank(versions.getString(0))) {
                    ContentValues values=content(versions.getString(0));values.put("updated",versions.getLong(1));db.update("notes",values,"id=?",new String[]{""+id});restored=true;break;
                }
            }
            if(!restored)db.delete("notes","id=?",new String[]{""+id});
        }
    }
    private static ContentValues content(String body) {
        ContentValues v = new ContentValues(); v.put("body", body); return v;
    }
    public static boolean isBlank(String text) {
        return text.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x200B||c==0xFEFF);
    }
    public long create(String body) {
        if(isBlank(body))throw new IllegalArgumentException("Note vide");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            long now = System.currentTimeMillis(); ContentValues v = content(body); v.put("updated", now);
            long id = db.insertOrThrow("notes", null, v); addVersion(db, id, body, now);
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    private void addVersion(SQLiteDatabase db, long id, String body, long time) {
        ContentValues v = content(body); v.put("note_id", id); v.put("saved", time);
        db.insertOrThrow("versions", null, v);
    }
    public boolean save(long id, String body) {
        if(isBlank(body))throw new IllegalArgumentException("Note vide");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Note old = get(id);
            if (old == null) throw new IllegalArgumentException("Note introuvable");
            if (old.body.equals(body)) return false;
            long now = System.currentTimeMillis(); ContentValues v = content(body); v.put("updated", now);
            db.update("notes", v, "id=?", new String[]{"" + id}); addVersion(db, id, body, now);
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    private Note read(Cursor c) { return new Note(c.getLong(0), c.getString(1), c.getLong(2)); }
    public Note get(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM notes WHERE id=?", new String[]{"" + id})) { return c.moveToFirst() ? read(c) : null; }
    }
    public List<Note> list(String query) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        ArrayList<Note> notes = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM notes WHERE body LIKE ? ESCAPE '\\' ORDER BY updated DESC,id DESC", new String[]{"%" + escaped + "%"})) {
            while (c.moveToNext()) notes.add(read(c));
        }
        return notes;
    }
    public void remove(long id) { getWritableDatabase().delete("notes", "id=?", new String[]{"" + id}); }
    public List<Version> history(long id) {
        ArrayList<Version> versions = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,body,saved FROM versions WHERE note_id=? ORDER BY id DESC", new String[]{"" + id})) {
            while (c.moveToNext()) versions.add(new Version(c.getLong(0), c.getString(1), c.getLong(2)));
        } return versions;
    }
    public JSONObject backup() throws JSONException {
        SQLiteDatabase db = getReadableDatabase(); db.beginTransaction();
        try {
            JSONArray notes = new JSONArray();
            try (Cursor c = db.rawQuery("SELECT * FROM notes ORDER BY id", null)) {
                while(c.moveToNext()) {
                    Note n = read(c); JSONArray versions = new JSONArray();
                    for (Version v : history(n.id)) versions.put(new JSONObject().put("body", v.body).put("saved", v.saved));
                    notes.put(new JSONObject().put("body", n.body).put("updated", n.updated).put("versions", versions));
                }
            }
            db.setTransactionSuccessful(); return new JSONObject().put("format", "carnet").put("version", 2).put("notes", notes);
        } finally { db.endTransaction(); }
    }
    private static String checkedText(JSONObject o, String key) throws JSONException {
        Object value = o.get(key);
        if (!(value instanceof String) || ((String)value).length() > 1_000_000) throw new JSONException("Texte invalide ou trop long");
        return (String)value;
    }
    private static String importedText(JSONObject o, int format) throws JSONException {
        String body = checkedText(o, "body");
        if (format == 1) {
            String title = checkedText(o, "title");
            if (!title.trim().isEmpty()) return body.isEmpty() ? title : title + "\n\n" + body;
        }
        return body;
    }
    private static long checkedTime(JSONObject o, String key) throws JSONException {
        Object value = o.get(key);
        if (!(value instanceof Number) || ((Number)value).longValue() < 0) throw new JSONException("Date invalide");
        return ((Number)value).longValue();
    }
    public int importBackup(JSONObject backup) throws JSONException {
        int format = backup.getInt("version");
        if (!"carnet".equals(backup.getString("format")) || (format != 1 && format != 2)) throw new JSONException("Format de sauvegarde incompatible");
        JSONArray notes = backup.getJSONArray("notes");
        if (notes.length() > 10000) throw new JSONException("Trop de notes");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction(); int count = 0, imported = 0;
        try {
            for (int i = 0; i < notes.length(); i++) {
                JSONObject n = notes.getJSONObject(i); String body = importedText(n, format);
                long updated = checkedTime(n, "updated");JSONArray versions = n.getJSONArray("versions");
                count += versions.length(); if (count > 100000) throw new JSONException("Trop de versions");
                if(isBlank(body)) {
                    for(int j=0;j<versions.length();j++) {
                        JSONObject version=versions.getJSONObject(j);String previous=importedText(version,format);
                        if(!isBlank(previous)){body=previous;updated=checkedTime(version,"saved");break;}
                    }
                    if(isBlank(body))continue;
                }
                ContentValues values = content(body); values.put("updated", updated);
                long id = db.insertOrThrow("notes", null, values);imported++;
                for (int j = versions.length() - 1; j >= 0; j--) {
                    JSONObject v = versions.getJSONObject(j); addVersion(db, id, importedText(v, format), checkedTime(v, "saved"));
                }
                if (versions.length() == 0) addVersion(db, id, body, updated);
            }
            db.setTransactionSuccessful(); return imported;
        } finally { db.endTransaction(); }
    }
}
