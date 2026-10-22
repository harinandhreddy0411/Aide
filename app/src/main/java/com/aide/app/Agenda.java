package com.aide.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Agenda {
    static final String TICK = "com.aide.app.TICK";
    static final String ROUTINE_DONE = "com.aide.app.ROUTINE_DONE";
    static final String ROUTINE_SKIP = "com.aide.app.ROUTINE_SKIP";
    static final String SNOOZE = "com.aide.app.SNOOZE";

    private static final int BRIEF = 1;
    private static final int START = 2;
    private static final int NUDGE = 3;
    private static final int ASK = 4;
    private static final int AGAIN = 5;

    private static final long HOUR = 3600000L;
    private static final int LAST_ASK_MIN = 1410;

    private static final class Ev {
        long time;
        int type;
        Db.Routine routine;
        String day;
        long endMs;
    }

    private Agenda() {}

    static String dayKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    static int notifId(long routineId) {
        return 20000 + (int) (routineId % 5000);
    }

    private static Calendar at(Calendar day, int min) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, min / 60);
        c.set(Calendar.MINUTE, min % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static Calendar midnight(int offsetDays) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, offsetDays);
        return c;
    }

    private static List<Ev> events(Context c) {
        Db db = Db.get(c);
        List<Db.Routine> routines = db.routines();
        boolean brief = Prefs.briefOn(c);
        int briefMin = Prefs.briefMin(c);
        List<Ev> out = new ArrayList<>();
        for (int d = -1; d <= 8; d++) {
            Calendar day = midnight(d);
            String key = dayKey(day.getTimeInMillis());
            if (brief) {
                Ev e = new Ev();
                e.type = BRIEF;
                e.time = at(day, briefMin).getTimeInMillis();
                e.day = key;
                out.add(e);
            }
            for (Db.Routine r : routines) {
                if (!r.runsOn(day)) continue;
                long endMs = at(day, r.endMin).getTimeInMillis();
                add(out, START, r, key, at(day, r.startMin).getTimeInMillis(), endMs);
                if (r.nudgeMin > 0) {
                    for (int m = r.startMin + r.nudgeMin; m <= r.endMin - 5; m += r.nudgeMin) {
                        add(out, NUDGE, r, key, at(day, m).getTimeInMillis(), endMs);
                    }
                }
                add(out, ASK, r, key, endMs, endMs);
                for (int k = 1; k <= 2; k++) {
                    int m = r.endMin + 30 * k;
                    if (m <= LAST_ASK_MIN) add(out, AGAIN, r, key, at(day, m).getTimeInMillis(), endMs);
                }
            }
        }
        return out;
    }

    private static void add(List<Ev> out, int type, Db.Routine r, String day, long time, long endMs) {
        Ev e = new Ev();
        e.type = type;
        e.routine = r;
        e.day = day;
        e.time = time;
        e.endMs = endMs;
        out.add(e);
    }

    private static long grace(int type) {
        return type == BRIEF ? 4 * HOUR : 2 * HOUR;
    }

    static synchronized void tick(Context c) {
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        long last = Prefs.lastTick(app);
        if (last <= 0 || last > now) last = now;
        long next = Long.MAX_VALUE;
        for (Ev e : events(app)) {
            if (e.time > last && e.time <= now) {
                if (now - e.time <= grace(e.type)) fire(app, e, now);
            } else if (e.time > now && e.time < next) {
                next = e.time;
            }
        }
        Prefs.setLastTick(app, now);
        arm(app, next);
        Db.get(app).trimLog(dayKey(now - 400L * 24 * HOUR));
    }

    static synchronized void rearm(Context c) {
        Prefs.setLastTick(c.getApplicationContext(), System.currentTimeMillis());
        tick(c);
    }

    private static void arm(Context c, long next) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 77, new Intent(c, AgendaReceiver.class).setAction(TICK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == Long.MAX_VALUE) {
            am.cancel(pi);
            return;
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
    }

    private static void fire(Context c, Ev e, long now) {
        if (e.type == BRIEF) {
            brief(c, now);
            return;
        }
        Db.Routine r = e.routine;
        if (Db.get(c).status(r.id, e.day) != 0) return;
        int id = notifId(r.id);
        String slot = Ui.clock(r.startMin) + " to " + Ui.clock(r.endMin);
        Intent open = Notify.home(c);
        if (e.type == START) {
            Notify.post(c, Notify.PRACTICE, id, "Time for " + r.title,
                    "Your slot is " + slot + ". Tick it off when you finish.", open,
                    answer(c, r, e.day, id, "Done", ROUTINE_DONE, 1), answer(c, r, e.day, id, "Skip today", ROUTINE_SKIP, 2));
        } else if (e.type == NUDGE) {
            long left = Math.max(1, (e.endMs - e.time) / 60000L);
            Notify.post(c, Notify.PRACTICE, id, "Keep going: " + r.title,
                    left + " min left in your slot (" + slot + ").", open,
                    answer(c, r, e.day, id, "Done", ROUTINE_DONE, 1), answer(c, r, e.day, id, "Skip today", ROUTINE_SKIP, 2));
        } else {
            String body = e.type == ASK
                    ? "Your slot (" + slot + ") is over. Did you do it?"
                    : "Still waiting for your answer. Did you get it done today?";
            Notify.post(c, Notify.PRACTICE, id, "Did you finish " + r.title + "?", body, open,
                    answer(c, r, e.day, id, "Done", ROUTINE_DONE, 1), answer(c, r, e.day, id, "Not done", ROUTINE_SKIP, 2));
        }
    }

    private static androidx.core.app.NotificationCompat.Action answer(Context c, Db.Routine r, String day, int nid,
                                                                       String label, String action, int slot) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(action)
                .putExtra("rid", r.id).putExtra("day", day).putExtra("nid", nid);
        return Notify.broadcastAction(c, nid * 4 + slot, label, i);
    }

    static List<String> pendingToday(Context c, int max) {
        List<String> out = new ArrayList<>();
        Calendar today = midnight(0);
        String key = dayKey(today.getTimeInMillis());
        Db db = Db.get(c);
        for (Db.Routine r : db.routines()) {
            if (out.size() >= max) break;
            if (r.runsOn(today) && db.status(r.id, key) == 0) out.add(r.title + " (" + Ui.clock(r.startMin) + ")");
        }
        return out;
    }

    static int streak(Db db, Db.Routine r) {
        Set<String> done = db.doneDays(r.id);
        Calendar d = midnight(0);
        if (!done.contains(dayKey(d.getTimeInMillis()))) d.add(Calendar.DAY_OF_MONTH, -1);
        int n = 0;
        for (int i = 0; i < 400; i++) {
            if (r.runsOn(d)) {
                if (!done.contains(dayKey(d.getTimeInMillis()))) break;
                n++;
            }
            d.add(Calendar.DAY_OF_MONTH, -1);
        }
        return n;
    }

    private static void brief(Context c, long now) {
        Db db = Db.get(c);
        Calendar today = midnight(0);
        long start = today.getTimeInMillis();
        long end = midnight(1).getTimeInMillis();
        String key = dayKey(start);
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        int overdue = 0;
        int count = 0;
        for (Db.Item it : db.open()) {
            if (it.start < start) {
                overdue++;
            } else if (it.start < end && count < 6) {
                sb.append(f.format(new Date(it.start))).append("  ").append(it.title).append('\n');
                count++;
            }
        }
        StringBuilder pr = new StringBuilder();
        for (Db.Routine r : db.routines()) {
            if (r.runsOn(today) && db.status(r.id, key) == 0) {
                pr.append(Ui.clock(r.startMin)).append("  ").append(r.title).append('\n');
            }
        }
        StringBuilder body = new StringBuilder();
        if (sb.length() > 0) body.append("Today\n").append(sb);
        if (pr.length() > 0) body.append("Practice\n").append(pr);
        if (overdue > 0) body.append("Overdue: ").append(overdue).append('\n');
        if (body.length() == 0) body.append("Nothing on your list today. Want me to plan it?");
        String title = new SimpleDateFormat("'Your day,' EEE d MMM", Locale.getDefault()).format(new Date(now));
        Intent plan = Notify.home(c).putExtra("plan", true);
        Notify.post(c, Notify.BRIEF, 9000, title, body.toString().trim(), Notify.home(c),
                Notify.activityAction(c, 9001, "Plan my day", plan));
    }
}
