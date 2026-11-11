package com.aide.app;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Schedule {
    static final String[] MEALS = {"breakfast", "lunch", "dinner"};
    private static final String[] DAYS = {"", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

    static final class Block {
        int start;
        int end;
        String title = "";
        String sub = "";
        String cat = "class";
        String type = "class";
        long slotId;
        long subjectId;
        boolean cancelled;
        boolean optional;
        boolean flexible;
        boolean unresolved;
        Object ref;
    }

    static final class DayInfo {
        String key;
        Calendar day;
        String banner = "";
        int effDow;
        List<Block> blocks = new ArrayList<>();
    }

    private Schedule() {}

    static String dayName(int dow) {
        return dow >= 1 && dow <= 7 ? DAYS[dow] : "";
    }

    static int dowFromName(String s) {
        if (s == null) return 0;
        String t = s.trim().toLowerCase(Locale.US);
        for (int i = 1; i <= 7; i++) {
            if (t.equals(DAYS[i].toLowerCase(Locale.US)) || (t.length() >= 3 && DAYS[i].toLowerCase(Locale.US).startsWith(t))) return i;
        }
        return 0;
    }

    static Calendar parseDay(String key) {
        if (key == null) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            Date d = f.parse(key.trim());
            if (d == null) return null;
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    static String subjectName(Db db, long id) {
        Db.Subject s = db.subject(id);
        return s == null ? "Class" : s.name;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long slotId(String target) {
        if (target == null || !target.startsWith("slot:")) return 0;
        try {
            return Long.parseLong(target.substring(5));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int[] times(String arg) {
        if (arg == null) return null;
        String[] p = arg.split(",");
        if (p.length != 2) return null;
        try {
            int a = Integer.parseInt(p[0].trim());
            int b = Integer.parseInt(p[1].trim());
            return b > a && a >= 0 && b <= 1440 ? new int[]{a, b} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long takenId(String arg) {
        if (arg == null || !arg.startsWith("taken:")) return 0;
        try {
            return Long.parseLong(arg.substring(6));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void becomeClass(Block b, Map<Long, Db.Subject> subjects, long subjectId, String note) {
        Db.Subject sub = subjects.get(subjectId);
        b.cat = "class";
        b.type = "class";
        b.subjectId = subjectId;
        b.title = sub == null ? "Class" : sub.name;
        b.sub = note;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static DayInfo resolve(Context c, Calendar day) {
        Db db = Db.get(c);
        DayInfo info = new DayInfo();
        info.day = day;
        info.key = Agenda.dayKey(day.getTimeInMillis());
        int dow = day.get(Calendar.DAY_OF_WEEK);
        info.effDow = dow;

        boolean off = false;
        Set<Long> cancelled = new HashSet<>();
        Map<Long, int[]> moved = new HashMap<>();
        Map<String, int[]> mealMoved = new HashMap<>();
        Set<String> mealSkipped = new HashSet<>();
        Map<Long, String> flex = new HashMap<>();
        for (Db.Exc e : db.exceptionsOn(info.key)) {
            if (e.kind.equals("holiday") || e.kind.equals("exam")) {
                off = true;
                String label = e.kind.equals("exam") ? "Exam period" : "Holiday";
                info.banner = e.note.isEmpty() ? label : label + ": " + e.note;
            } else if (e.kind.equals("follow")) {
                int w = toInt(e.arg);
                if (w >= 2 && w <= 6) {
                    info.effDow = w;
                    info.banner = "Follows the " + dayName(w) + " timetable";
                }
            } else if (e.kind.equals("cancel")) {
                long id = slotId(e.target);
                if (id > 0) cancelled.add(id);
            } else if (e.kind.equals("override")) {
                long id = slotId(e.target);
                int[] t = times(e.arg);
                if (id > 0 && t != null) moved.put(id, t);
            } else if (e.kind.equals("flex")) {
                long id = slotId(e.target);
                if (id > 0) flex.put(id, e.arg);
            } else if (e.kind.equals("meal") && e.target.startsWith("meal:")) {
                if (e.arg.equals("skip")) {
                    mealSkipped.add(e.target.substring(5));
                } else {
                    int[] t = times(e.arg);
                    if (t != null) mealMoved.put(e.target.substring(5), t);
                }
            }
        }

        Map<Long, Db.Subject> subjects = new HashMap<>();
        for (Db.Subject s : db.subjects()) subjects.put(s.id, s);

        boolean hasLunch = false;
        if (!off) {
            for (Db.Slot s : db.slotsFor(info.effDow)) {
                Block b = new Block();
                b.slotId = s.id;
                b.subjectId = s.subjectId;
                b.type = s.type;
                b.optional = s.optional;
                b.ref = s;
                int[] t = moved.get(s.id);
                b.start = t != null ? t[0] : s.startMin;
                b.end = t != null ? t[1] : s.endMin;
                b.cancelled = cancelled.contains(s.id);
                String fa = flex.get(s.id);
                if (s.type.equals("lunch")) {
                    hasLunch = true;
                    b.title = "Lunch";
                    b.cat = "meal";
                    b.flexible = true;
                    b.unresolved = fa == null;
                    b.sub = "Free unless a faculty takes it";
                    if (takenId(fa) > 0) becomeClass(b, subjects, takenId(fa), "Took the lunch break");
                } else if (s.type.equals("advisory")) {
                    b.title = "Advisory Hour";
                    b.flexible = true;
                    b.unresolved = fa == null;
                    b.sub = "Free unless a faculty takes it";
                    if (takenId(fa) > 0) becomeClass(b, subjects, takenId(fa), "Took the advisory hour");
                } else {
                    Db.Subject sub = subjects.get(s.subjectId);
                    String name = sub == null ? "Class" : sub.name;
                    b.title = s.label.isEmpty() ? name : name + " · " + s.label;
                    List<String> parts = new ArrayList<>();
                    if (!s.room.isEmpty()) parts.add(s.room);
                    if (s.type.equals("lab")) parts.add("Lab");
                    if (s.type.equals("tutorial")) {
                        parts.add("Tutorial");
                        b.flexible = true;
                        b.unresolved = fa == null;
                        if ("free".equals(fa)) {
                            b.cancelled = true;
                        } else if (takenId(fa) > 0 && takenId(fa) != s.subjectId) {
                            Db.Subject other = subjects.get(takenId(fa));
                            if (other != null) {
                                b.subjectId = other.id;
                                b.title = other.name;
                            }
                        }
                    }
                    if (s.optional) {
                        parts.add("Optional");
                        b.flexible = true;
                        b.unresolved = fa == null;
                        if ("attend".equals(fa)) b.optional = false;
                    }
                    b.sub = String.join(", ", parts);
                }
                info.blocks.add(b);
            }
        }

        for (String kind : MEALS) {
            if (kind.equals("lunch") && hasLunch) continue;
            if (mealSkipped.contains(kind)) continue;
            Db.Meal m = db.meal(kind, dow);
            if (m == null) continue;
            int[] t = mealMoved.get(kind);
            Block b = new Block();
            b.cat = "meal";
            b.type = kind;
            b.title = cap(kind);
            b.start = t != null ? t[0] : m.startMin;
            b.end = t != null ? t[1] : m.endMin;
            b.ref = m;
            info.blocks.add(b);
        }

        for (Db.Routine r : db.routines()) {
            if (!r.runsOn(day)) continue;
            Block b = new Block();
            b.cat = "practice";
            b.type = "practice";
            b.title = r.title;
            b.sub = "Daily practice";
            b.start = r.startMin;
            b.end = r.endMin;
            b.ref = r;
            info.blocks.add(b);
        }

        Calendar next = (Calendar) day.clone();
        next.add(Calendar.DAY_OF_MONTH, 1);
        long lo = day.getTimeInMillis();
        long hi = next.getTimeInMillis();
        for (Db.Item it : db.open()) {
            if (it.start < lo || it.start >= hi) continue;
            Calendar when = Calendar.getInstance();
            when.setTimeInMillis(it.start);
            Block b = new Block();
            b.cat = "item";
            b.type = "item";
            b.title = it.title;
            b.sub = Ui.kindLabel(it.kind);
            b.start = when.get(Calendar.HOUR_OF_DAY) * 60 + when.get(Calendar.MINUTE);
            b.end = b.start;
            b.ref = it;
            info.blocks.add(b);
        }

        Collections.sort(info.blocks, new Comparator<Block>() {
            @Override
            public int compare(Block a, Block b) {
                if (a.start != b.start) return Integer.compare(a.start, b.start);
                return Integer.compare(a.end, b.end);
            }
        });
        return info;
    }

    static boolean isClass(Block b) {
        return b.cat.equals("class") && !b.cancelled && !b.optional && !b.type.equals("advisory");
    }

    static List<Block> unresolved(Context c, Calendar day) {
        List<Block> out = new ArrayList<>();
        DayInfo info = resolve(c, day);
        for (Block b : info.blocks) if (b.unresolved && !b.cancelled) out.add(b);
        return out;
    }

    static boolean isBusy(Block b) {
        if (b.cancelled || b.optional || b.cat.equals("item")) return false;
        if (b.type.equals("advisory")) return false;
        return true;
    }

    static String planText(Context c, Calendar day, boolean fromNow) {
        DayInfo info = resolve(c, day);
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int from = fromNow ? ((nowMin + 4) / 5) * 5 : 0;
        int lo = Math.max(from, 420);
        int hi = 1380;

        List<int[]> order = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<int[]> busy = new ArrayList<>();
        List<String> cancelled = new ArrayList<>();
        for (Block b : info.blocks) {
            if (b.cancelled) {
                cancelled.add(b.title);
                continue;
            }
            if (b.cat.equals("item")) {
                if (b.start < from) continue;
                lines.add(Ui.clock(b.start) + "  " + b.title + " (" + b.sub + ")");
                starts.add(b.start);
                continue;
            }
            if (b.end <= from) continue;
            String text = Ui.clock(b.start) + " - " + Ui.clock(b.end) + "  " + b.title;
            if (!b.sub.isEmpty()) text += " (" + b.sub + ")";
            lines.add(text);
            starts.add(b.start);
            if (isBusy(b)) busy.add(new int[]{b.start, b.end});
        }

        Collections.sort(busy, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Integer.compare(a[0], b[0]);
            }
        });
        int cursor = lo;
        int freeTotal = 0;
        for (int[] iv : busy) {
            if (iv[1] <= cursor) continue;
            if (iv[0] > cursor + 24 && iv[0] <= hi) {
                int end = Math.min(iv[0], hi);
                lines.add("Free  " + Ui.clock(cursor) + " - " + Ui.clock(end) + "  (" + Ui.duration((end - cursor) * 60000L) + ")");
                starts.add(cursor);
                freeTotal += end - cursor;
            }
            cursor = Math.max(cursor, iv[1]);
        }
        if (hi > cursor + 24) {
            lines.add("Free  " + Ui.clock(cursor) + " - " + Ui.clock(hi) + "  (" + Ui.duration((hi - cursor) * 60000L) + ")");
            starts.add(cursor);
            freeTotal += hi - cursor;
        }

        for (int i = 0; i < lines.size(); i++) order.add(new int[]{starts.get(i), i});
        Collections.sort(order, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
                return Integer.compare(a[1], b[1]);
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(day.getTime()));
        if (!info.banner.isEmpty()) sb.append(" - ").append(info.banner);
        sb.append('\n');
        for (int[] o : order) sb.append(lines.get(o[1])).append('\n');
        if (!cancelled.isEmpty()) sb.append("Cancelled: ").append(String.join(", ", cancelled)).append('\n');
        if (freeTotal > 0) sb.append("Free time: ").append(Ui.duration(freeTotal * 60000L));
        return sb.toString().trim();
    }

    static String conflict(Context c, int daysMask, int start, int end) {
        Db db = Db.get(c);
        for (int dow = 1; dow <= 7; dow++) {
            if ((daysMask & (1 << (dow - 1))) == 0) continue;
            String day = dayName(dow).substring(0, 3);
            for (Db.Slot s : db.slotsFor(dow)) {
                if (s.optional || s.type.equals("advisory")) continue;
                if (start < s.endMin && s.startMin < end) {
                    String name = s.type.equals("lunch") ? "Lunch" : subjectName(db, s.subjectId);
                    return day + ": " + name + " " + Ui.clock(s.startMin) + " to " + Ui.clock(s.endMin);
                }
            }
            for (String kind : MEALS) {
                Db.Meal m = db.meal(kind, dow);
                if (m != null && start < m.endMin && m.startMin < end) {
                    return day + ": " + cap(kind) + " " + Ui.clock(m.startMin) + " to " + Ui.clock(m.endMin);
                }
            }
        }
        return null;
    }

    static void changed(Context c) {
        Context app = c.getApplicationContext();
        Bg.run(() -> CalSync.run(app));
    }
}
