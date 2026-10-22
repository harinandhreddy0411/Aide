package com.aide.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

final class Usage {
    private static final int FOREGROUND = 1;
    private static final int BACKGROUND = 2;
    private static final int SCREEN_OFF = 16;
    private static final long WINDOW = 6L * 3600000L;
    private static final long GAP = 2L * 60000L;

    private Usage() {}

    static final class Session {
        String pkg;
        long start;
    }

    static boolean has(Context c) {
        AppOpsManager a = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        int mode = Build.VERSION.SDK_INT >= 29
                ? a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                : a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static void apply(Context c) {
        WorkManager wm = WorkManager.getInstance(c.getApplicationContext());
        if (Prefs.screenOn(c)) {
            PeriodicWorkRequest r = new PeriodicWorkRequest.Builder(ScreenWorker.class, 15, TimeUnit.MINUTES).build();
            wm.enqueueUniquePeriodicWork("aide-screen", ExistingPeriodicWorkPolicy.KEEP, r);
        } else {
            wm.cancelUniqueWork("aide-screen");
        }
    }

    private static String launcher(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
    }

    private static boolean ignored(Context c, String pkg) {
        return pkg.equals(c.getPackageName()) || pkg.equals(launcher(c)) || pkg.equals("android")
                || pkg.startsWith("com.android.systemui") || pkg.contains("dialer") || pkg.contains("incallui")
                || pkg.contains("telecom");
    }

    static Session session(Context c, long now) {
        UsageStatsManager u = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        UsageEvents events = u.queryEvents(now - WINDOW, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null;
        long start = 0;
        long left = 0;
        boolean away = false;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            long ts = e.getTimeStamp();
            String p = e.getPackageName();
            if (type == FOREGROUND) {
                if (pkg != null && p.equals(pkg) && (!away || ts - left <= GAP)) {
                    away = false;
                } else {
                    pkg = p;
                    start = ts;
                    away = false;
                }
            } else if (type == BACKGROUND) {
                if (pkg != null && p.equals(pkg)) {
                    away = true;
                    left = ts;
                }
            } else if (type == SCREEN_OFF) {
                if (pkg != null && !away) {
                    away = true;
                    left = ts;
                }
            }
        }
        if (pkg == null || ignored(c, pkg)) return null;
        if (away && now - left > GAP) return null;
        Session s = new Session();
        s.pkg = pkg;
        s.start = start;
        return s;
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            int dot = pkg.lastIndexOf('.');
            return dot >= 0 ? pkg.substring(dot + 1) : pkg;
        }
    }

    static void check(Context c) {
        if (!Prefs.screenOn(c) || !has(c)) return;
        long now = System.currentTimeMillis();
        if (now < Prefs.snoozeUntil(c)) return;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (!pm.isInteractive()) return;
        Session s = session(c, now);
        if (s == null) return;
        long threshold = Math.max(5, Prefs.screenMin(c)) * 60000L;
        int level = (int) ((now - s.start) / threshold);
        if (level < 1) return;
        if (s.start != Prefs.nudgeStart(c)) Prefs.setNudge(c, s.start, 0);
        if (level <= Prefs.nudgeLevel(c)) return;
        Prefs.setNudge(c, s.start, level);

        String app = label(c, s.pkg);
        StringBuilder body = new StringBuilder("You have been on " + app + " for " + Ui.duration(now - s.start)
                + ". Time to switch?");
        List<String> pending = Agenda.pendingToday(c, 2);
        if (!pending.isEmpty()) body.append("\nStill to do today: ").append(String.join(", ", pending));
        Intent snooze = new Intent(c, ActionReceiver.class).setAction(Agenda.SNOOZE).putExtra("nid", 9100);
        Notify.post(c, Notify.NUDGE, 9100, "Long session: " + app, body.toString(), Notify.home(c),
                Notify.broadcastAction(c, 9101, "Snooze 1 h", snooze));
    }
}
