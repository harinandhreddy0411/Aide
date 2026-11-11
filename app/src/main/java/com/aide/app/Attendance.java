package com.aide.app;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Attendance {

    static final class Row {
        Db.Subject subject;
        int held;
        int absent;
        int present;
        double pct = 100;
        int skip = -1;
        int need;
        boolean low;
    }

    private Attendance() {}

    static boolean on(Context c) {
        return !Prefs.attendFrom(c).isEmpty();
    }

    private static Calendar midnight() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    static boolean counts(Schedule.Block b) {
        if (!b.cat.equals("class") || b.cancelled || b.slotId <= 0 || b.subjectId <= 0) return false;
        if (!(b.ref instanceof Db.Slot)) return false;
        return !((Db.Slot) b.ref).optional;
    }

    static List<Schedule.Block> countedOn(Context c, Calendar day) {
        List<Schedule.Block> out = new ArrayList<>();
        for (Schedule.Block b : Schedule.resolve(c, day).blocks) if (counts(b)) out.add(b);
        return out;
    }

    static boolean hasClasses(Context c, Calendar day) {
        return !countedOn(c, day).isEmpty();
    }

    static int units(Schedule.Block b) {
        return Math.max(1, ((Db.Slot) b.ref).units);
    }

    private static String weekKey(Calendar d) {
        Calendar w = (Calendar) d.clone();
        int back = (d.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        w.add(Calendar.DAY_OF_MONTH, -back);
        return Agenda.dayKey(w.getTimeInMillis());
    }

    static List<Row> compute(Context c) {
        List<Row> out = new ArrayList<>();
        String from = Prefs.attendFrom(c);
        Calendar d = Schedule.parseDay(from);
        if (d == null) return out;
        Db db = Db.get(c);
        Map<Long, Row> rows = new LinkedHashMap<>();
        for (Db.Subject s : db.subjects()) {
            Row r = new Row();
            r.subject = s;
            rows.put(s.id, r);
        }
        Set<String> abs = db.absences(from);
        Calendar today = midnight();
        int nowMin = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) * 60 + Calendar.getInstance().get(Calendar.MINUTE);
        Map<String, int[]> weekly = new HashMap<>();
        Map<String, Long> weeklySubject = new HashMap<>();

        while (!d.after(today)) {
            boolean isToday = d.getTimeInMillis() == today.getTimeInMillis();
            String key = Agenda.dayKey(d.getTimeInMillis());
            String wk = weekKey(d);
            for (Schedule.Block b : Schedule.resolve(c, d).blocks) {
                if (!b.cat.equals("class") || b.cancelled || b.slotId <= 0 || b.subjectId <= 0) continue;
                if (!(b.ref instanceof Db.Slot)) continue;
                if (isToday && b.end > nowMin) continue;
                Db.Slot s = (Db.Slot) b.ref;
                Row r = rows.get(b.subjectId);
                if (r == null) continue;
                int u = Math.max(1, s.units);
                String wkey = wk + "|" + b.subjectId;
                int[] w = weekly.get(wkey);
                if (w == null) {
                    w = new int[2];
                    weekly.put(wkey, w);
                    weeklySubject.put(wkey, b.subjectId);
                }
                if (s.optional) {
                    if (!b.optional) w[1] += u;
                    continue;
                }
                r.held += u;
                if (abs.contains(key + "|" + b.slotId)) w[0] += u;
            }
            d.add(Calendar.DAY_OF_MONTH, 1);
        }

        for (Map.Entry<String, int[]> e : weekly.entrySet()) {
            Row r = rows.get(weeklySubject.get(e.getKey()));
            if (r != null) r.absent += Math.max(0, e.getValue()[0] - e.getValue()[1]);
        }

        for (Row r : rows.values()) {
            r.present = r.held - r.absent;
            int min = r.subject.minPct;
            if (r.held > 0) {
                r.pct = 100.0 * r.present / r.held;
                if (r.present * 100 >= min * r.held) {
                    r.skip = (r.present * 100) / Math.max(1, min) - r.held;
                } else {
                    r.low = true;
                    r.need = min >= 100 ? -1 : (min * r.held - 100 * r.present + (100 - min) - 1) / (100 - min);
                }
            }
            if (r.held > 0 || Db.get(c).slotsUse(r.subject.id)) out.add(r);
        }
        return out;
    }

    static String line(Row r) {
        if (r.held == 0) return "No classes counted yet";
        String unit = r.subject.kind.equals("lab") ? "sessions" : "periods";
        String base = r.present + " of " + r.held + " " + unit + ", " + Math.round(r.pct) + "%";
        if (r.low) {
            if (r.need < 0) return base + ". Cannot reach " + r.subject.minPct + "%.";
            return base + ". Attend the next " + r.need + " to get back to " + r.subject.minPct + "%.";
        }
        if (r.skip <= 0) return base + ". You cannot skip any more.";
        return base + ". You can skip " + r.skip + " more.";
    }

    static String warning(Context c) {
        if (!on(c)) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Row r : compute(c)) {
            if (r.held == 0 || n >= 3) continue;
            if (r.low || r.skip <= 1) {
                sb.append(r.subject.name).append(" ").append(Math.round(r.pct)).append("%");
                sb.append(r.low ? ", below " + r.subject.minPct + "%" : ", " + Math.max(0, r.skip) + " to spare").append('\n');
                n++;
            }
        }
        return sb.length() == 0 ? "" : "Attendance\n" + sb;
    }

    static void markDialog(Activity a, String dayKey, Runnable done) {
        Calendar day = Schedule.parseDay(dayKey);
        if (day == null) return;
        List<Schedule.Block> list = countedOn(a, day);
        if (list.isEmpty()) {
            Toast.makeText(a, "No counted classes on that day.", Toast.LENGTH_LONG).show();
            if (done != null) done.run();
            return;
        }
        Db db = Db.get(a);
        Set<Long> already = db.absencesOn(dayKey);
        String[] labels = new String[list.size()];
        boolean[] checked = new boolean[list.size()];
        for (int i = 0; i < labels.length; i++) {
            Schedule.Block b = list.get(i);
            labels[i] = Ui.clock(b.start) + "  " + b.title;
            checked[i] = already.contains(b.slotId);
        }
        new MaterialAlertDialogBuilder(a)
                .setTitle("Tick the classes you missed")
                .setMultiChoiceItems(labels, checked, (d, i, on) -> checked[i] = on)
                .setPositiveButton("Save", (d, w) -> {
                    for (int i = 0; i < labels.length; i++) db.setAbsent(dayKey, list.get(i).slotId, checked[i]);
                    if (done != null) done.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
