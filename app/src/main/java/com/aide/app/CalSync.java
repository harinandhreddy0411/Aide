package com.aide.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CalSync {
    private static final int DAYS = 14;
    private static final String TAG = "Aide timetable";

    private static final class Want {
        String key;
        String title;
        String where = "";
        long start;
        long end;
        boolean allDay;
        int remind = -1;
        int year;
        int month;
        int day;
        String sig;
    }

    private CalSync() {}

    private static long at(Calendar day, int min) {
        Calendar t = (Calendar) day.clone();
        t.set(Calendar.HOUR_OF_DAY, min / 60);
        t.set(Calendar.MINUTE, min % 60);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        return t.getTimeInMillis();
    }

    private static Map<String, Want> wanted(Context c) {
        Map<String, Want> out = new LinkedHashMap<>();
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        int remind = Prefs.classRemind(c);
        for (int i = 0; i < DAYS; i++) {
            Schedule.DayInfo info = Schedule.resolve(c, day);
            if (info.banner.startsWith("Holiday") || info.banner.startsWith("Exam")) {
                Want w = new Want();
                w.key = "d:" + info.key;
                w.title = info.banner;
                w.allDay = true;
                w.year = day.get(Calendar.YEAR);
                w.month = day.get(Calendar.MONTH);
                w.day = day.get(Calendar.DAY_OF_MONTH);
                w.sig = w.title + "|" + info.key;
                out.put(w.key, w);
            } else {
                boolean first = true;
                for (Schedule.Block b : info.blocks) {
                    if (!Schedule.isClass(b)) continue;
                    Want w = new Want();
                    w.key = "c:" + (b.slotId > 0 ? String.valueOf(b.slotId) : "x" + b.start) + ":" + info.key;
                    w.title = b.title;
                    if (b.ref instanceof Db.Slot) w.where = ((Db.Slot) b.ref).room;
                    w.start = at(day, b.start);
                    w.end = at(day, b.end);
                    w.remind = first && remind > 0 ? remind : -1;
                    first = false;
                    w.sig = w.title + "|" + w.start + "|" + w.end + "|" + w.where + "|" + w.remind;
                    out.put(w.key, w);
                }
            }
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    private static String dayOf(String key) {
        return key.substring(key.length() - 10);
    }

    static synchronized String run(Context c) {
        if (!Prefs.syncOn(c)) return null;
        if (!CalendarHelper.has(c)) return "Calendar access is off. Allow it in Android settings.";
        long cal = CalendarHelper.resolveCalendarId(c);
        if (cal < 0) return "No writable calendar found.";
        Db db = Db.get(c);
        Map<String, Want> want = wanted(c);
        Map<String, Db.Sync> have = db.calSync();
        String today = Agenda.dayKey(System.currentTimeMillis());

        List<String> keys = new ArrayList<>(have.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            Db.Sync s = have.get(key);
            if (dayOf(key).compareTo(today) < 0) {
                db.deleteSync(key);
                continue;
            }
            Want w = want.get(key);
            if (w == null || !w.sig.equals(s.sig)) {
                CalendarHelper.delete(c, s.eventId);
                db.deleteSync(key);
                have.remove(key);
            }
        }

        for (Want w : want.values()) {
            Db.Sync s = have.get(w.key);
            if (s != null && s.sig.equals(w.sig)) continue;
            long id;
            if (w.allDay) {
                id = CalendarHelper.insertAllDay(c, cal, w.title, TAG, w.year, w.month, w.day);
            } else {
                int[] offsets = w.remind > 0 ? new int[]{w.remind} : new int[0];
                id = CalendarHelper.insertRange(c, cal, w.title, TAG, w.where, w.start, w.end, offsets);
            }
            if (id > 0) db.putSync(w.key, id, w.sig);
        }
        Prefs.setLastSyncAt(c, System.currentTimeMillis());
        return null;
    }

    static void maybe(Context c) {
        if (System.currentTimeMillis() - Prefs.lastSyncAt(c) > 3L * 3600000L) run(c);
    }
}
