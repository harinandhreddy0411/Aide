package com.aide.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

final class Changes {

    interface Op {
        void run(Db db);
    }

    static final class Change {
        String text;
        Op op;
    }

    static final class Parsed {
        List<Change> list = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String error;
    }

    private Changes() {}

    static Parsed parse(Context c, String json) {
        Parsed p = new Parsed();
        try {
            String s = json == null ? "" : json;
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a >= 0 && b > a) s = s.substring(a, b + 1);
            JSONArray arr = new JSONObject(s).optJSONArray("changes");
            if (arr == null || arr.length() == 0) {
                p.error = "I could not find a schedule change in that.";
                return p;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String err = build(c, o, p);
                if (err != null) p.problems.add(err);
            }
            if (p.list.isEmpty()) {
                p.error = p.problems.isEmpty() ? "I could not find a schedule change in that."
                        : String.join(" ", p.problems);
            }
        } catch (Exception e) {
            p.error = "Could not understand that. Try rephrasing.";
        }
        return p;
    }

    static void apply(Context c, Parsed p) {
        Db db = Db.get(c);
        for (Change ch : p.list) ch.op.run(db);
        Schedule.changed(c);
    }

    private static void add(Parsed p, String text, Op op) {
        Change ch = new Change();
        ch.text = text;
        ch.op = op;
        p.list.add(ch);
    }

    static String label(Calendar day) {
        return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(day.getTime());
    }

    private static String key(Calendar day) {
        return Agenda.dayKey(day.getTimeInMillis());
    }

    private static String first(JSONObject o, String a, String b) {
        String x = o.optString(a, "").trim();
        if (!x.isEmpty() && !x.equals("null")) return x;
        String y = o.optString(b, "").trim();
        return y.equals("null") ? "" : y;
    }

    static int minutes(String hhmm) {
        if (hhmm == null) return -1;
        String[] p = hhmm.trim().split(":");
        if (p.length != 2) return -1;
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            return h >= 0 && h < 24 && m >= 0 && m < 60 ? h * 60 + m : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static Db.Subject findSubject(Db db, String q) {
        if (q == null) return null;
        String t = q.trim().toLowerCase(Locale.US);
        if (t.isEmpty()) return null;
        List<Db.Subject> all = db.subjects();
        for (Db.Subject s : all) if (s.code.toLowerCase(Locale.US).equals(t) || s.name.toLowerCase(Locale.US).equals(t)) return s;
        for (Db.Subject s : all) if (s.name.toLowerCase(Locale.US).contains(t) || t.contains(s.name.toLowerCase(Locale.US))) return s;
        return null;
    }

    private static List<Schedule.Block> blocksOn(Context c, Calendar day, String subject, int period, String word) {
        Db db = Db.get(c);
        Db.Subject sub = findSubject(db, subject);
        String w = word == null ? "" : word.trim().toLowerCase(Locale.US);
        if (period <= 0 && !w.isEmpty() && Character.isDigit(w.charAt(0))) {
            try {
                period = Integer.parseInt(w);
            } catch (NumberFormatException ignored) {
            }
            w = "";
        }
        List<Schedule.Block> out = new ArrayList<>();
        if (sub == null && period <= 0 && w.isEmpty()) return out;
        for (Schedule.Block b : Schedule.resolve(c, day).blocks) {
            if (b.slotId <= 0 || !(b.ref instanceof Db.Slot)) continue;
            Db.Slot s = (Db.Slot) b.ref;
            if (!w.isEmpty() && !s.type.equals(w)) continue;
            if (sub != null && s.subjectId != sub.id && b.subjectId != sub.id) continue;
            if (period > 0) {
                int t = Db.periodStart(period);
                if (t < b.start || t >= b.end) continue;
            }
            out.add(b);
        }
        return out;
    }

    private static Db.Exc exc(String kind, String from, String to, String target, String arg, String note) {
        Db.Exc e = new Db.Exc();
        e.kind = kind;
        e.from = from;
        e.to = to;
        e.target = target;
        e.arg = arg;
        e.note = note;
        return e;
    }

    private static String build(Context c, JSONObject o, Parsed p) {
        Db db = Db.get(c);
        String op = o.optString("op", "").trim().toLowerCase(Locale.US);
        switch (op) {
            case "cancel":
            case "move":
                return buildSlot(c, o, p, op.equals("move"));
            case "holiday":
            case "exam":
            case "normal":
                return buildMode(o, p, op);
            case "follow": {
                Calendar day = Schedule.parseDay(first(o, "date", "from"));
                int dow = Schedule.dowFromName(o.optString("weekday", ""));
                if (day == null) return "Which date follows another day's timetable?";
                if (dow < 2 || dow > 6) return "Which weekday's timetable should it follow?";
                String k = key(day);
                add(p, label(day) + " follows the " + Schedule.dayName(dow) + " timetable", d -> {
                    d.deleteDayModes(k, k);
                    d.saveException(exc("follow", k, k, "", String.valueOf(dow), ""));
                });
                return null;
            }
            case "meal":
                return buildMeal(o, p);
            case "taken":
            case "free":
            case "attend":
                return buildFlex(c, o, p, op);
            case "rename": {
                Db.Subject s = findSubject(db, o.optString("from", ""));
                String to = o.optString("to", "").trim();
                if (s == null || to.isEmpty()) return "Which subject should be renamed, and to what?";
                long id = s.id;
                add(p, "Rename " + s.name + " to " + to, d -> {
                    Db.Subject x = d.subject(id);
                    if (x == null) return;
                    x.name = to;
                    d.saveSubject(x);
                });
                return null;
            }
            default:
                return "I did not understand one of the changes.";
        }
    }

    private static String buildSlot(Context c, JSONObject o, Parsed p, boolean move) {
        Calendar day = Schedule.parseDay(first(o, "date", "from"));
        if (day == null) return "Which date is that for?";
        boolean weekly = o.optBoolean("weekly", false);
        List<Schedule.Block> bl = blocksOn(c, day, o.optString("subject", ""), o.optInt("period", 0), o.optString("slot", ""));
        if (bl.isEmpty()) return "I found no matching class on " + label(day) + ".";
        int ns = move ? minutes(o.optString("start", "")) : -1;
        int ne = move ? minutes(o.optString("end", "")) : -1;
        if (move && ns < 0) return "What time should it move to?";
        String k = key(day);
        int added = 0;
        for (Schedule.Block b : bl) {
            if (!move && b.cancelled) continue;
            long id = b.slotId;
            String target = "slot:" + id;
            String name = b.title;
            if (move) {
                int len = b.end - b.start;
                int s = ns;
                int e = ne > ns ? ne : Math.min(ns + len, 1440);
                String when = Ui.clock(s) + " to " + Ui.clock(e);
                if (weekly) {
                    add(p, "Move " + name + " to " + when + " every " + Schedule.dayName(day.get(Calendar.DAY_OF_WEEK)), d -> {
                        Db.Slot sl = d.slot(id);
                        if (sl == null) return;
                        sl.startMin = s;
                        sl.endMin = e;
                        d.saveSlot(sl);
                    });
                } else {
                    add(p, "Move " + name + " on " + label(day) + " to " + when, d -> {
                        d.deleteExceptionsWhere("override", target, k);
                        d.saveException(exc("override", k, k, target, s + "," + e, ""));
                    });
                }
            } else if (weekly) {
                add(p, "Remove " + name + " from every " + Schedule.dayName(day.get(Calendar.DAY_OF_WEEK)) + " for good", d -> d.deleteSlot(id));
            } else {
                add(p, "Cancel " + name + " on " + label(day), d -> {
                    d.deleteExceptionsWhere("cancel", target, k);
                    d.saveException(exc("cancel", k, k, target, "", ""));
                });
            }
            added++;
        }
        return added == 0 ? "That class is already cancelled." : null;
    }

    private static String buildMode(JSONObject o, Parsed p, String op) {
        Calendar a = Schedule.parseDay(first(o, "from", "date"));
        if (a == null) return "Which date does that start on?";
        Calendar b = Schedule.parseDay(first(o, "to", "date"));
        if (b == null || b.before(a)) b = a;
        String fk = key(a);
        String tk = key(b);
        String note = o.optString("note", "").trim();
        if (note.equals("null")) note = "";
        String span = fk.equals(tk) ? label(a) : label(a) + " to " + label(b);
        if (op.equals("normal")) {
            add(p, "Back to the normal timetable for " + span, d -> d.deleteDayModes(fk, tk));
        } else {
            String n = note;
            add(p, (op.equals("exam") ? "Exam period: " : "Holiday: ") + span, d -> {
                d.deleteDayModes(fk, tk);
                d.saveException(exc(op, fk, tk, "", "", n));
            });
        }
        return null;
    }

    private static boolean inGroup(String group, int dow) {
        boolean weekend = dow == 1 || dow == 7;
        if (group.equals("weekend")) return weekend;
        if (group.equals("weekdays")) return !weekend;
        return true;
    }

    private static String buildMeal(JSONObject o, Parsed p) {
        String kind = o.optString("meal", "").trim().toLowerCase(Locale.US);
        boolean ok = false;
        for (String m : Schedule.MEALS) if (m.equals(kind)) ok = true;
        if (!ok) return "Which meal: breakfast, lunch or dinner?";
        boolean skip = o.optBoolean("skip", false);
        int s = minutes(o.optString("start", ""));
        int e = minutes(o.optString("end", ""));
        if (!skip && (s < 0 || e <= s)) return "What time range for " + kind + "?";
        Calendar day = Schedule.parseDay(o.optString("date", ""));
        if (day != null) {
            String k = key(day);
            String target = "meal:" + kind;
            String arg = skip ? "skip" : s + "," + e;
            String text = skip ? "Skip " + kind + " on " + label(day)
                    : Schedule.cap(kind) + " on " + label(day) + " at " + Ui.clock(s) + " to " + Ui.clock(e);
            add(p, text, d -> {
                d.deleteExceptionsWhere("meal", target, k);
                d.saveException(exc("meal", k, k, target, arg, ""));
            });
            return null;
        }
        if (skip) return "Which date should the " + kind + " be skipped on?";
        String group = o.optString("days", "all").trim().toLowerCase(Locale.US);
        if (!group.equals("weekdays") && !group.equals("weekend")) group = "all";
        String g = group;
        String who = group.equals("all") ? "every day" : group.equals("weekdays") ? "every weekday" : "every weekend day";
        String note = kind.equals("lunch") && !group.equals("weekend") ? " (weekday lunch follows the timetable's lunch slot)" : "";
        add(p, Schedule.cap(kind) + " " + Ui.clock(s) + " to " + Ui.clock(e) + " " + who + note, d -> {
            for (int dow = 1; dow <= 7; dow++) {
                if (!inGroup(g, dow)) continue;
                Db.Meal m = new Db.Meal();
                m.kind = kind;
                m.dow = dow;
                m.startMin = s;
                m.endMin = e;
                d.saveMeal(m);
            }
        });
        return null;
    }

    private static String buildFlex(Context c, JSONObject o, Parsed p, String op) {
        Calendar day = Schedule.parseDay(first(o, "date", "from"));
        if (day == null) return "Which date is that for?";
        Db db = Db.get(c);
        Db.Subject sub = null;
        if (op.equals("taken")) {
            sub = findSubject(db, o.optString("subject", ""));
            if (sub == null) return "Which subject did the faculty take it for?";
        }
        List<Schedule.Block> found = new ArrayList<>();
        if (op.equals("attend")) {
            for (Schedule.Block b : Schedule.resolve(c, day).blocks) {
                if (b.slotId > 0 && b.ref instanceof Db.Slot && ((Db.Slot) b.ref).optional) found.add(b);
            }
        } else {
            for (Schedule.Block b : blocksOn(c, day, "", o.optInt("period", 0), o.optString("slot", ""))) {
                if (b.flexible) found.add(b);
            }
        }
        if (found.isEmpty()) return "I found no lunch, advisory, tutorial or optional slot on " + label(day) + ".";
        String k = key(day);
        for (Schedule.Block b : found) {
            long id = b.slotId;
            String target = "slot:" + id;
            String arg = op.equals("taken") ? "taken:" + sub.id : op.equals("free") ? "free" : "attend";
            String text;
            if (op.equals("taken")) {
                text = b.title + " on " + label(day) + ": a faculty took it, counts as " + sub.name;
            } else if (op.equals("free")) {
                text = b.title + " on " + label(day) + ": free";
            } else {
                text = "You will attend " + b.title + " on " + label(day);
            }
            add(p, text, d -> {
                d.deleteExceptionsWhere("flex", target, k);
                d.saveException(exc("flex", k, k, target, arg, ""));
            });
        }
        return null;
    }

    static void setFlex(Context c, long slotId, String key, String arg) {
        Db db = Db.get(c);
        String target = "slot:" + slotId;
        db.deleteExceptionsWhere("flex", target, key);
        if (arg != null) db.saveException(exc("flex", key, key, target, arg, ""));
        Schedule.changed(c);
    }

    static void cancelSlot(Context c, long slotId, String key, boolean cancel) {
        Db db = Db.get(c);
        String target = "slot:" + slotId;
        db.deleteExceptionsWhere("cancel", target, key);
        if (cancel) db.saveException(exc("cancel", key, key, target, "", ""));
        Schedule.changed(c);
    }

    static void moveSlot(Context c, long slotId, String key, int start, int end) {
        Db db = Db.get(c);
        String target = "slot:" + slotId;
        db.deleteExceptionsWhere("override", target, key);
        db.saveException(exc("override", key, key, target, start + "," + end, ""));
        Schedule.changed(c);
    }

    static void moveSlotWeekly(Context c, long slotId, int start, int end) {
        Db db = Db.get(c);
        Db.Slot s = db.slot(slotId);
        if (s == null) return;
        s.startMin = start;
        s.endMin = end;
        db.saveSlot(s);
        Schedule.changed(c);
    }

    static void removeSlotWeekly(Context c, long slotId) {
        Db.get(c).deleteSlot(slotId);
        Schedule.changed(c);
    }

    static void resetSlot(Context c, long slotId, String key) {
        Db.get(c).deleteExceptionsFor("slot:" + slotId, key);
        Schedule.changed(c);
    }

    static void mealToday(Context c, String kind, String key, int start, int end, boolean skip) {
        Db db = Db.get(c);
        String target = "meal:" + kind;
        db.deleteExceptionsWhere("meal", target, key);
        db.saveException(exc("meal", key, key, target, skip ? "skip" : start + "," + end, ""));
        Schedule.changed(c);
    }

    static void mealWeekly(Context c, String kind, int dow, int start, int end) {
        Db.Meal m = new Db.Meal();
        m.kind = kind;
        m.dow = dow;
        m.startMin = start;
        m.endMin = end;
        Db.get(c).saveMeal(m);
        Schedule.changed(c);
    }

    static void mealGroup(Context c, String kind, int[] dows, int start, int end) {
        Db db = Db.get(c);
        for (int dow : dows) {
            Db.Meal m = new Db.Meal();
            m.kind = kind;
            m.dow = dow;
            m.startMin = start;
            m.endMin = end;
            db.saveMeal(m);
        }
        Schedule.changed(c);
    }

    static void dayMode(Context c, String kind, String from, String to, String note) {
        Db db = Db.get(c);
        db.deleteDayModes(from, to);
        if (kind != null) db.saveException(exc(kind, from, to, "", "", note == null ? "" : note));
        Schedule.changed(c);
    }

    static void follow(Context c, String key, int dow) {
        Db db = Db.get(c);
        db.deleteDayModes(key, key);
        db.saveException(exc("follow", key, key, "", String.valueOf(dow), ""));
        Schedule.changed(c);
    }
}
