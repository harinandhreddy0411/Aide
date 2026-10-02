package com.aide.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class Db extends SQLiteOpenHelper {

    static final class Item {
        long id;
        String title;
        String kind;
        long start;
        String offsets;
        long eventId = -1;
        long calId = -1;

        int[] offsetArray() {
            return parse(offsets);
        }

        static int[] parse(String csv) {
            if (csv == null || csv.trim().isEmpty()) return new int[0];
            String[] parts = csv.split(",");
            int[] out = new int[parts.length];
            int n = 0;
            for (String s : parts) {
                try {
                    int v = Integer.parseInt(s.trim());
                    if (v >= 0) out[n++] = v;
                } catch (NumberFormatException ignored) {
                }
            }
            return Arrays.copyOf(out, n);
        }
    }

    static final class Note {
        long id;
        String body;
    }

    static final class Msg {
        long id;
        String role;
        String body;
    }

    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "aide.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, kind TEXT NOT NULL, "
                + "start_ms INTEGER NOT NULL, offsets TEXT, event_id INTEGER DEFAULT -1, cal_id INTEGER DEFAULT -1)");
        d.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, updated INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, body TEXT NOT NULL, "
                + "ts INTEGER NOT NULL, summarized INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
    }

    private static Item readItem(Cursor c) {
        Item it = new Item();
        it.id = c.getLong(c.getColumnIndexOrThrow("id"));
        it.title = c.getString(c.getColumnIndexOrThrow("title"));
        it.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        it.start = c.getLong(c.getColumnIndexOrThrow("start_ms"));
        it.offsets = c.getString(c.getColumnIndexOrThrow("offsets"));
        it.eventId = c.getLong(c.getColumnIndexOrThrow("event_id"));
        it.calId = c.getLong(c.getColumnIndexOrThrow("cal_id"));
        return it;
    }

    synchronized long saveItem(Item it) {
        ContentValues v = new ContentValues();
        v.put("title", it.title);
        v.put("kind", it.kind);
        v.put("start_ms", it.start);
        v.put("offsets", it.offsets);
        v.put("event_id", it.eventId);
        v.put("cal_id", it.calId);
        SQLiteDatabase d = getWritableDatabase();
        if (it.id > 0) {
            d.update("items", v, "id=?", new String[]{String.valueOf(it.id)});
            return it.id;
        }
        return d.insert("items", null, v);
    }

    synchronized void deleteItem(long id) {
        getWritableDatabase().delete("items", "id=?", new String[]{String.valueOf(id)});
    }

    synchronized List<Item> upcoming(long from) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("items", null, "start_ms >= ?",
                new String[]{String.valueOf(from)}, null, null, "start_ms ASC", "50")) {
            while (c.moveToNext()) out.add(readItem(c));
        }
        return out;
    }

    synchronized List<Note> notes() {
        List<Note> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("notes", null, null, null, null, null, "updated DESC")) {
            while (c.moveToNext()) {
                Note n = new Note();
                n.id = c.getLong(c.getColumnIndexOrThrow("id"));
                n.body = c.getString(c.getColumnIndexOrThrow("body"));
                out.add(n);
            }
        }
        return out;
    }

    synchronized void saveNote(Note n) {
        ContentValues v = new ContentValues();
        v.put("body", n.body);
        v.put("updated", System.currentTimeMillis());
        SQLiteDatabase d = getWritableDatabase();
        if (n.id > 0) {
            d.update("notes", v, "id=?", new String[]{String.valueOf(n.id)});
        } else {
            d.insert("notes", null, v);
        }
    }

    synchronized void deleteNote(long id) {
        getWritableDatabase().delete("notes", "id=?", new String[]{String.valueOf(id)});
    }

    synchronized void addNoteIfNew(String body) {
        String b = body.length() > 600 ? body.substring(0, 600) : body;
        SQLiteDatabase d = getWritableDatabase();
        try (Cursor c = d.query("notes", new String[]{"id"}, "LOWER(body) = ?",
                new String[]{b.toLowerCase(Locale.ROOT)}, null, null, null, "1")) {
            if (c.moveToFirst()) return;
        }
        ContentValues v = new ContentValues();
        v.put("body", b);
        v.put("updated", System.currentTimeMillis());
        d.insert("notes", null, v);
    }

    synchronized void addMsg(String role, String body) {
        ContentValues v = new ContentValues();
        v.put("role", role);
        v.put("body", body);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("msgs", null, v);
    }

    private List<Msg> readMsgs(String where) {
        List<Msg> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("msgs", null, where, null, null, null, "id ASC")) {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.id = c.getLong(c.getColumnIndexOrThrow("id"));
                m.role = c.getString(c.getColumnIndexOrThrow("role"));
                m.body = c.getString(c.getColumnIndexOrThrow("body"));
                out.add(m);
            }
        }
        return out;
    }

    synchronized List<Msg> allMsgs() {
        return readMsgs(null);
    }

    synchronized List<Msg> activeMsgs() {
        return readMsgs("summarized = 0");
    }

    synchronized void markSummarized(long uptoId) {
        getWritableDatabase().execSQL("UPDATE msgs SET summarized = 1 WHERE id <= ?", new Object[]{uptoId});
    }

    synchronized void clearMsgs() {
        getWritableDatabase().delete("msgs", null, null);
    }
}
