package com.aide.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Db extends SQLiteOpenHelper {

    static final class Item {
        long id;
        String title;
        String kind;
        long start;
        String offsets;
        long eventId = -1;
        long calId = -1;
        boolean done;

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

    static final class Routine {
        long id;
        String title;
        int startMin;
        int endMin;
        int days = 127;
        int nudgeMin;

        boolean runsOn(Calendar c) {
            return (days & (1 << (c.get(Calendar.DAY_OF_WEEK) - 1))) != 0;
        }
    }

    static final class Job {
        long id;
        String kind;
        String payload;
        int attempts;
        long created;
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

    private static final String JOBS_SQL = "CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, "
            + "payload TEXT, attempts INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)";

    private static final String ROUTINES_SQL = "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, "
            + "start_min INTEGER NOT NULL, end_min INTEGER NOT NULL, days INTEGER NOT NULL DEFAULT 127, nudge_min INTEGER NOT NULL DEFAULT 0)";
    private static final String LOG_SQL = "CREATE TABLE routine_log (routine_id INTEGER NOT NULL, day TEXT NOT NULL, "
            + "status INTEGER NOT NULL, PRIMARY KEY (routine_id, day))";

    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "aide.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, kind TEXT NOT NULL, "
                + "start_ms INTEGER NOT NULL, offsets TEXT, event_id INTEGER DEFAULT -1, cal_id INTEGER DEFAULT -1, done INTEGER NOT NULL DEFAULT 0)");
        d.execSQL(JOBS_SQL);
        d.execSQL(ROUTINES_SQL);
        d.execSQL(LOG_SQL);
        d.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, updated INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, body TEXT NOT NULL, "
                + "ts INTEGER NOT NULL, summarized INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            d.execSQL("ALTER TABLE items ADD COLUMN done INTEGER NOT NULL DEFAULT 0");
            d.execSQL(JOBS_SQL);
        }
        if (oldVersion < 3) {
            d.execSQL(ROUTINES_SQL);
            d.execSQL(LOG_SQL);
        }
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
        it.done = c.getInt(c.getColumnIndexOrThrow("done")) == 1;
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
        v.put("done", it.done ? 1 : 0);
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

    synchronized void purgeDone() {
        getWritableDatabase().delete("items", "done = 1", null);
    }

    synchronized List<Item> upcoming(long from) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("items", null, "start_ms >= ? AND done = 0",
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

    synchronized void deleteTrailingUserMsgs() {
        getWritableDatabase().execSQL("DELETE FROM msgs WHERE role = 'user' AND id > "
                + "(SELECT COALESCE(MAX(id), 0) FROM msgs WHERE role = 'model')");
    }

    private List<Item> items(String where, String order, String limit) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("items", null, where, null, null, null, order, limit)) {
            while (c.moveToNext()) out.add(readItem(c));
        }
        return out;
    }

    synchronized List<Item> open() {
        return items("done = 0", "start_ms ASC", "50");
    }

    synchronized List<Item> history() {
        return items("done = 1", "start_ms DESC", "10");
    }

    private static Routine readRoutine(Cursor c) {
        Routine r = new Routine();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        r.title = c.getString(c.getColumnIndexOrThrow("title"));
        r.startMin = c.getInt(c.getColumnIndexOrThrow("start_min"));
        r.endMin = c.getInt(c.getColumnIndexOrThrow("end_min"));
        r.days = c.getInt(c.getColumnIndexOrThrow("days"));
        r.nudgeMin = c.getInt(c.getColumnIndexOrThrow("nudge_min"));
        return r;
    }

    synchronized long saveRoutine(Routine r) {
        ContentValues v = new ContentValues();
        v.put("title", r.title);
        v.put("start_min", r.startMin);
        v.put("end_min", r.endMin);
        v.put("days", r.days);
        v.put("nudge_min", r.nudgeMin);
        SQLiteDatabase d = getWritableDatabase();
        if (r.id > 0) {
            d.update("routines", v, "id=?", new String[]{String.valueOf(r.id)});
            return r.id;
        }
        return d.insert("routines", null, v);
    }

    synchronized void deleteRoutine(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("routines", "id=?", new String[]{String.valueOf(id)});
        d.delete("routine_log", "routine_id=?", new String[]{String.valueOf(id)});
    }

    synchronized List<Routine> routines() {
        List<Routine> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("routines", null, null, null, null, null, "start_min ASC, id ASC")) {
            while (c.moveToNext()) out.add(readRoutine(c));
        }
        return out;
    }

    synchronized Routine routine(long id) {
        try (Cursor c = getReadableDatabase().query("routines", null, "id=?", new String[]{String.valueOf(id)},
                null, null, null)) {
            return c.moveToFirst() ? readRoutine(c) : null;
        }
    }

    synchronized int status(long routineId, String day) {
        try (Cursor c = getReadableDatabase().query("routine_log", new String[]{"status"}, "routine_id=? AND day=?",
                new String[]{String.valueOf(routineId), day}, null, null, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized void setStatus(long routineId, String day, int status) {
        SQLiteDatabase d = getWritableDatabase();
        if (status == 0) {
            d.delete("routine_log", "routine_id=? AND day=?", new String[]{String.valueOf(routineId), day});
            return;
        }
        ContentValues v = new ContentValues();
        v.put("routine_id", routineId);
        v.put("day", day);
        v.put("status", status);
        d.insertWithOnConflict("routine_log", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized Set<String> doneDays(long routineId) {
        Set<String> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().query("routine_log", new String[]{"day"}, "routine_id=? AND status=1",
                new String[]{String.valueOf(routineId)}, null, null, null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    synchronized void trimLog(String beforeDay) {
        getWritableDatabase().delete("routine_log", "day < ?", new String[]{beforeDay});
    }

    synchronized long addJob(String kind, String payload) {
        ContentValues v = new ContentValues();
        v.put("kind", kind);
        v.put("payload", payload);
        v.put("attempts", 0);
        v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("jobs", null, v);
    }

    synchronized List<Job> jobs() {
        List<Job> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("jobs", null, null, null, null, null, "id ASC")) {
            while (c.moveToNext()) {
                Job j = new Job();
                j.id = c.getLong(c.getColumnIndexOrThrow("id"));
                j.kind = c.getString(c.getColumnIndexOrThrow("kind"));
                j.payload = c.getString(c.getColumnIndexOrThrow("payload"));
                j.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
                j.created = c.getLong(c.getColumnIndexOrThrow("created"));
                out.add(j);
            }
        }
        return out;
    }

    synchronized int jobCount(String kind) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM jobs WHERE kind = ?", new String[]{kind})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized void bumpJob(long id) {
        getWritableDatabase().execSQL("UPDATE jobs SET attempts = attempts + 1 WHERE id = ?", new Object[]{id});
    }

    synchronized void deleteJob(long id) {
        getWritableDatabase().delete("jobs", "id=?", new String[]{String.valueOf(id)});
    }

    synchronized void deleteJobs(String kind) {
        getWritableDatabase().delete("jobs", "kind=?", new String[]{kind});
    }
}
