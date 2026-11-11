package com.aide.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    static final class Subject {
        long id;
        String code;
        String name;
        String kind = "theory";
        int minPct = 75;
    }

    static final class Slot {
        long id;
        int dow;
        int startMin;
        int endMin;
        long subjectId;
        String label = "";
        String room = "";
        String type = "class";
        boolean optional;
        int units = 1;
    }

    static final class Exc {
        long id;
        String kind;
        String from;
        String to;
        String target = "";
        String arg = "";
        String note = "";
    }

    static final class Meal {
        String kind;
        int dow;
        int startMin;
        int endMin;
    }

    static final class Sync {
        long eventId;
        String sig;
    }

    static final class Job {
        long id;
        String kind;
        String payload;
        int attempts;
        long created;
    }

    private static final String JOBS_SQL = "CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, "
            + "payload TEXT, attempts INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)";

    private static final String ROUTINES_SQL = "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, "
            + "start_min INTEGER NOT NULL, end_min INTEGER NOT NULL, days INTEGER NOT NULL DEFAULT 127, nudge_min INTEGER NOT NULL DEFAULT 0)";
    private static final String LOG_SQL = "CREATE TABLE routine_log (routine_id INTEGER NOT NULL, day TEXT NOT NULL, "
            + "status INTEGER NOT NULL, PRIMARY KEY (routine_id, day))";

    private static final String SUBJECTS_SQL = "CREATE TABLE subjects (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, "
            + "name TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'theory', min_pct INTEGER NOT NULL DEFAULT 75)";
    private static final String SLOTS_SQL = "CREATE TABLE slots (id INTEGER PRIMARY KEY AUTOINCREMENT, dow INTEGER NOT NULL, "
            + "start_min INTEGER NOT NULL, end_min INTEGER NOT NULL, subject_id INTEGER NOT NULL DEFAULT 0, label TEXT, room TEXT, "
            + "type TEXT NOT NULL, optional INTEGER NOT NULL DEFAULT 0, units INTEGER NOT NULL DEFAULT 1)";
    private static final String EXC_SQL = "CREATE TABLE exceptions (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, "
            + "d_from TEXT NOT NULL, d_to TEXT NOT NULL, target TEXT, arg TEXT, note TEXT)";
    private static final String MEALS_SQL = "CREATE TABLE meals (kind TEXT NOT NULL, dow INTEGER NOT NULL, start_min INTEGER NOT NULL, "
            + "end_min INTEGER NOT NULL, PRIMARY KEY (kind, dow))";
    private static final String ABS_SQL = "CREATE TABLE absences (day TEXT NOT NULL, slot_id INTEGER NOT NULL, PRIMARY KEY (day, slot_id))";
    private static final String SYNC_SQL = "CREATE TABLE cal_sync (k TEXT PRIMARY KEY, event_id INTEGER NOT NULL, sig TEXT NOT NULL)";

    private static final int[] PERIOD_START = {540, 590, 650, 700, 750, 800, 850, 910, 960};
    private static final int[] PERIOD_END = {590, 640, 700, 750, 800, 850, 900, 960, 1010};

    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "aide.db", null, 5);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, kind TEXT NOT NULL, "
                + "start_ms INTEGER NOT NULL, offsets TEXT, event_id INTEGER DEFAULT -1, cal_id INTEGER DEFAULT -1, done INTEGER NOT NULL DEFAULT 0)");
        d.execSQL(JOBS_SQL);
        d.execSQL(ROUTINES_SQL);
        d.execSQL(LOG_SQL);
        createSchedule(d);
        d.execSQL(ABS_SQL);
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
        if (oldVersion < 4) createSchedule(d);
        if (oldVersion < 5) d.execSQL(ABS_SQL);
    }

    @Override
    public void onDowngrade(SQLiteDatabase d, int oldVersion, int newVersion) {
    }

    private static void createSchedule(SQLiteDatabase d) {
        d.execSQL(SUBJECTS_SQL);
        d.execSQL(SLOTS_SQL);
        d.execSQL(EXC_SQL);
        d.execSQL(MEALS_SQL);
        d.execSQL(SYNC_SQL);
        seed(d);
    }

    private static long addSubject(SQLiteDatabase d, String code, String name, String kind) {
        ContentValues v = new ContentValues();
        v.put("code", code);
        v.put("name", name);
        v.put("kind", kind);
        v.put("min_pct", 75);
        return d.insert("subjects", null, v);
    }

    private static void addSlot(SQLiteDatabase d, int dow, int fromPeriod, int toPeriod, long subject, String label,
                                String room, String type, int optional, int units) {
        ContentValues v = new ContentValues();
        v.put("dow", dow);
        v.put("start_min", PERIOD_START[fromPeriod - 1]);
        v.put("end_min", PERIOD_END[toPeriod - 1]);
        v.put("subject_id", subject);
        v.put("label", label);
        v.put("room", room);
        v.put("type", type);
        v.put("optional", optional);
        v.put("units", units);
        d.insert("slots", null, v);
    }

    private static void seed(SQLiteDatabase d) {
        long ppc = addSubject(d, "23CSE201", "Procedural Programming using C", "theory");
        long dbms = addSubject(d, "23CSE202", "Database Management Systems", "theory");
        long dsa = addSubject(d, "23CSE203", "Data Structures and Algorithms", "theory");
        long s205 = addSubject(d, "23CSE205", "23CSE205", "theory");
        long opt = addSubject(d, "23MAT206", "Optimization Techniques", "theory");
        long cir = addSubject(d, "23LSE201", "CIR", "theory");
        long adm = addSubject(d, "ADM", "ADM", "theory");
        long lab = addSubject(d, "23CSE285", "Digital Lab", "lab");

        addSlot(d, 2, 1, 1, dbms, "Tutorial", "", "tutorial", 0, 1);
        addSlot(d, 2, 2, 2, cir, "Aptitude Skills", "", "class", 0, 1);
        addSlot(d, 2, 3, 3, ppc, "", "S102", "class", 0, 1);
        addSlot(d, 2, 4, 5, dbms, "", "S304B", "class", 0, 2);
        addSlot(d, 2, 6, 6, 0, "", "", "lunch", 0, 1);
        addSlot(d, 2, 7, 9, lab, "", "N109 A", "lab", 0, 1);

        addSlot(d, 3, 1, 2, dsa, "", "S304B", "class", 0, 2);
        addSlot(d, 3, 3, 3, dsa, "", "N303", "class", 0, 1);
        addSlot(d, 3, 4, 4, 0, "", "", "lunch", 0, 1);
        addSlot(d, 3, 5, 5, opt, "", "", "class", 0, 1);
        addSlot(d, 3, 6, 6, cir, "Verbal Skills", "A402", "class", 0, 1);
        addSlot(d, 3, 7, 7, adm, "", "A402", "class", 0, 1);
        addSlot(d, 3, 8, 8, dbms, "", "", "class", 0, 1);
        addSlot(d, 3, 9, 9, ppc, "", "", "class", 0, 1);

        addSlot(d, 4, 1, 1, dsa, "", "N207", "class", 0, 1);
        addSlot(d, 4, 2, 2, dsa, "", "N207", "class", 0, 1);
        addSlot(d, 4, 3, 4, ppc, "", "S304B", "class", 0, 2);
        addSlot(d, 4, 5, 5, 0, "", "", "lunch", 0, 1);
        addSlot(d, 4, 6, 6, cir, "", "A307", "class", 0, 1);
        addSlot(d, 4, 7, 7, s205, "", "", "class", 0, 1);
        addSlot(d, 4, 8, 8, opt, "", "", "class", 0, 1);
        addSlot(d, 4, 9, 9, dbms, "", "", "class", 0, 1);

        addSlot(d, 5, 1, 2, opt, "", "A207B", "class", 0, 2);
        addSlot(d, 5, 3, 3, dbms, "", "A207B", "class", 0, 1);
        addSlot(d, 5, 4, 4, 0, "", "", "lunch", 0, 1);
        addSlot(d, 5, 5, 5, s205, "", "", "class", 0, 1);
        addSlot(d, 5, 6, 6, ppc, "", "", "class", 0, 1);
        addSlot(d, 5, 7, 7, dsa, "", "", "class", 0, 1);

        addSlot(d, 6, 1, 3, lab, "Optional", "N109 A", "lab", 1, 1);
        addSlot(d, 6, 4, 4, 0, "", "", "advisory", 0, 1);
        addSlot(d, 6, 5, 5, 0, "", "", "lunch", 0, 1);
        addSlot(d, 6, 6, 6, s205, "", "", "class", 0, 1);
        addSlot(d, 6, 7, 7, opt, "", "", "class", 0, 1);

        for (int dow = 1; dow <= 7; dow++) {
            addMeal(d, "breakfast", dow, 420, 480);
            addMeal(d, "lunch", dow, 750, 800);
            addMeal(d, "dinner", dow, 1140, 1200);
        }
    }

    private static void addMeal(SQLiteDatabase d, String kind, int dow, int start, int end) {
        ContentValues v = new ContentValues();
        v.put("kind", kind);
        v.put("dow", dow);
        v.put("start_min", start);
        v.put("end_min", end);
        d.insertWithOnConflict("meals", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized List<Subject> subjects() {
        List<Subject> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("subjects", null, null, null, null, null, "id ASC")) {
            while (c.moveToNext()) out.add(readSubject(c));
        }
        return out;
    }

    synchronized Subject subject(long id) {
        try (Cursor c = getReadableDatabase().query("subjects", null, "id=?", new String[]{String.valueOf(id)},
                null, null, null)) {
            return c.moveToFirst() ? readSubject(c) : null;
        }
    }

    private static Subject readSubject(Cursor c) {
        Subject s = new Subject();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.code = c.getString(c.getColumnIndexOrThrow("code"));
        s.name = c.getString(c.getColumnIndexOrThrow("name"));
        s.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        s.minPct = c.getInt(c.getColumnIndexOrThrow("min_pct"));
        return s;
    }

    synchronized long saveSubject(Subject s) {
        ContentValues v = new ContentValues();
        v.put("code", s.code);
        v.put("name", s.name);
        v.put("kind", s.kind);
        v.put("min_pct", s.minPct);
        SQLiteDatabase d = getWritableDatabase();
        if (s.id > 0) {
            d.update("subjects", v, "id=?", new String[]{String.valueOf(s.id)});
            return s.id;
        }
        return d.insert("subjects", null, v);
    }

    synchronized Subject subjectByCode(String code) {
        try (Cursor c = getReadableDatabase().query("subjects", null, "LOWER(code)=?",
                new String[]{code.trim().toLowerCase(Locale.ROOT)}, null, null, null)) {
            return c.moveToFirst() ? readSubject(c) : null;
        }
    }

    static int periodStart(int p) {
        return PERIOD_START[p - 1];
    }

    static int periodEnd(int p) {
        return PERIOD_END[p - 1];
    }

    synchronized void deleteSlot(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("slots", "id=?", new String[]{String.valueOf(id)});
        d.delete("exceptions", "target=?", new String[]{"slot:" + id});
    }

    synchronized void deleteAllSlots() {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("slots", null, null);
        d.delete("exceptions", "target LIKE 'slot:%'", null);
    }

    synchronized void deleteExceptionsFor(String target, String key) {
        getWritableDatabase().delete("exceptions", "target=? AND d_from=?", new String[]{target, key});
    }

    synchronized void deleteDayModes(String from, String to) {
        getWritableDatabase().delete("exceptions", "kind IN ('holiday','exam','follow') AND d_from <= ? AND d_to >= ?",
                new String[]{to, from});
    }

    private static Slot readSlot(Cursor c) {
        Slot s = new Slot();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.dow = c.getInt(c.getColumnIndexOrThrow("dow"));
        s.startMin = c.getInt(c.getColumnIndexOrThrow("start_min"));
        s.endMin = c.getInt(c.getColumnIndexOrThrow("end_min"));
        s.subjectId = c.getLong(c.getColumnIndexOrThrow("subject_id"));
        String label = c.getString(c.getColumnIndexOrThrow("label"));
        String room = c.getString(c.getColumnIndexOrThrow("room"));
        s.label = label == null ? "" : label;
        s.room = room == null ? "" : room;
        s.type = c.getString(c.getColumnIndexOrThrow("type"));
        s.optional = c.getInt(c.getColumnIndexOrThrow("optional")) == 1;
        s.units = c.getInt(c.getColumnIndexOrThrow("units"));
        return s;
    }

    synchronized List<Slot> slotsFor(int dow) {
        List<Slot> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("slots", null, "dow=?", new String[]{String.valueOf(dow)},
                null, null, "start_min ASC")) {
            while (c.moveToNext()) out.add(readSlot(c));
        }
        return out;
    }

    synchronized Slot slot(long id) {
        try (Cursor c = getReadableDatabase().query("slots", null, "id=?", new String[]{String.valueOf(id)},
                null, null, null)) {
            return c.moveToFirst() ? readSlot(c) : null;
        }
    }

    synchronized void saveSlot(Slot s) {
        ContentValues v = new ContentValues();
        v.put("dow", s.dow);
        v.put("start_min", s.startMin);
        v.put("end_min", s.endMin);
        v.put("subject_id", s.subjectId);
        v.put("label", s.label);
        v.put("room", s.room);
        v.put("type", s.type);
        v.put("optional", s.optional ? 1 : 0);
        v.put("units", s.units);
        SQLiteDatabase d = getWritableDatabase();
        if (s.id > 0) {
            d.update("slots", v, "id=?", new String[]{String.valueOf(s.id)});
        } else {
            d.insert("slots", null, v);
        }
    }

    private static Exc readExc(Cursor c) {
        Exc e = new Exc();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        e.from = c.getString(c.getColumnIndexOrThrow("d_from"));
        e.to = c.getString(c.getColumnIndexOrThrow("d_to"));
        String target = c.getString(c.getColumnIndexOrThrow("target"));
        String arg = c.getString(c.getColumnIndexOrThrow("arg"));
        String note = c.getString(c.getColumnIndexOrThrow("note"));
        e.target = target == null ? "" : target;
        e.arg = arg == null ? "" : arg;
        e.note = note == null ? "" : note;
        return e;
    }

    synchronized List<Exc> exceptionsOn(String key) {
        List<Exc> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("exceptions", null, "d_from <= ? AND d_to >= ?",
                new String[]{key, key}, null, null, "id ASC")) {
            while (c.moveToNext()) out.add(readExc(c));
        }
        return out;
    }

    synchronized List<Exc> exceptionsFrom(String key) {
        List<Exc> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("exceptions", null, "d_to >= ?", new String[]{key},
                null, null, "d_from ASC, id ASC")) {
            while (c.moveToNext()) out.add(readExc(c));
        }
        return out;
    }

    synchronized long saveException(Exc e) {
        ContentValues v = new ContentValues();
        v.put("kind", e.kind);
        v.put("d_from", e.from);
        v.put("d_to", e.to);
        v.put("target", e.target);
        v.put("arg", e.arg);
        v.put("note", e.note);
        return getWritableDatabase().insert("exceptions", null, v);
    }

    synchronized void deleteException(long id) {
        getWritableDatabase().delete("exceptions", "id=?", new String[]{String.valueOf(id)});
    }

    synchronized void deleteExceptionsWhere(String kind, String target, String key) {
        getWritableDatabase().delete("exceptions", "kind=? AND target=? AND d_from=?", new String[]{kind, target, key});
    }

    synchronized Meal meal(String kind, int dow) {
        try (Cursor c = getReadableDatabase().query("meals", null, "kind=? AND dow=?",
                new String[]{kind, String.valueOf(dow)}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            Meal m = new Meal();
            m.kind = kind;
            m.dow = dow;
            m.startMin = c.getInt(c.getColumnIndexOrThrow("start_min"));
            m.endMin = c.getInt(c.getColumnIndexOrThrow("end_min"));
            return m;
        }
    }

    synchronized void saveMeal(Meal m) {
        addMeal(getWritableDatabase(), m.kind, m.dow, m.startMin, m.endMin);
    }

    synchronized boolean slotsUse(long subjectId) {
        try (Cursor c = getReadableDatabase().query("slots", new String[]{"id"}, "subject_id=?",
                new String[]{String.valueOf(subjectId)}, null, null, null, "1")) {
            return c.moveToFirst();
        }
    }

    synchronized Set<String> absences(String fromKey) {
        Set<String> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().query("absences", null, "day >= ?", new String[]{fromKey}, null, null, null)) {
            while (c.moveToNext()) {
                out.add(c.getString(c.getColumnIndexOrThrow("day")) + "|" + c.getLong(c.getColumnIndexOrThrow("slot_id")));
            }
        }
        return out;
    }

    synchronized Set<Long> absencesOn(String day) {
        Set<Long> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().query("absences", null, "day = ?", new String[]{day}, null, null, null)) {
            while (c.moveToNext()) out.add(c.getLong(c.getColumnIndexOrThrow("slot_id")));
        }
        return out;
    }

    synchronized void setAbsent(String day, long slotId, boolean on) {
        SQLiteDatabase d = getWritableDatabase();
        if (on) {
            ContentValues v = new ContentValues();
            v.put("day", day);
            v.put("slot_id", slotId);
            d.insertWithOnConflict("absences", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } else {
            d.delete("absences", "day=? AND slot_id=?", new String[]{day, String.valueOf(slotId)});
        }
    }

    synchronized void clearAbsences() {
        getWritableDatabase().delete("absences", null, null);
    }

    synchronized Map<String, Sync> calSync() {
        Map<String, Sync> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().query("cal_sync", null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                Sync s = new Sync();
                s.eventId = c.getLong(c.getColumnIndexOrThrow("event_id"));
                s.sig = c.getString(c.getColumnIndexOrThrow("sig"));
                out.put(c.getString(c.getColumnIndexOrThrow("k")), s);
            }
        }
        return out;
    }

    synchronized void putSync(String key, long eventId, String sig) {
        ContentValues v = new ContentValues();
        v.put("k", key);
        v.put("event_id", eventId);
        v.put("sig", sig);
        getWritableDatabase().insertWithOnConflict("cal_sync", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void deleteSync(String key) {
        getWritableDatabase().delete("cal_sync", "k=?", new String[]{key});
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
