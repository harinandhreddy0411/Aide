package com.aide.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String DEFAULT_MODEL = "gemini-2.5-flash-lite";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("aide_prefs", Context.MODE_PRIVATE);
    }

    static String apiKey(Context c) {
        return Secure.decrypt(sp(c).getString("key", ""));
    }

    static void setApiKey(Context c, String k) {
        sp(c).edit().putString("key", Secure.encrypt(k)).apply();
    }

    static String model(Context c) {
        String m = sp(c).getString("model", DEFAULT_MODEL);
        return m == null || m.trim().isEmpty() ? DEFAULT_MODEL : m.trim();
    }

    static void setModel(Context c, String m) {
        sp(c).edit().putString("model", m == null ? "" : m.trim()).apply();
    }

    static final String DEFAULT_FALLBACK = "gemini-2.5-flash";

    static String fallbackModel(Context c) {
        String m = sp(c).getString("fallback", DEFAULT_FALLBACK);
        return m == null ? "" : m.trim();
    }

    static void setFallbackModel(Context c, String m) {
        sp(c).edit().putString("fallback", m == null ? "" : m.trim()).apply();
    }

    static long calendarId(Context c) {
        return sp(c).getLong("cal", -1L);
    }

    static void setCalendarId(Context c, long id) {
        sp(c).edit().putLong("cal", id).apply();
    }

    static boolean briefOn(Context c) {
        return sp(c).getBoolean("brief_on", true);
    }

    static int briefMin(Context c) {
        return sp(c).getInt("brief_min", 450);
    }

    static void setBrief(Context c, boolean on, int min) {
        sp(c).edit().putBoolean("brief_on", on).putInt("brief_min", min).apply();
    }

    static boolean screenOn(Context c) {
        return sp(c).getBoolean("screen_on", false);
    }

    static int screenMin(Context c) {
        return sp(c).getInt("screen_min", 45);
    }

    static void setScreen(Context c, boolean on, int min) {
        sp(c).edit().putBoolean("screen_on", on).putInt("screen_min", min).apply();
    }

    static long snoozeUntil(Context c) {
        return sp(c).getLong("snooze", 0L);
    }

    static void setSnoozeUntil(Context c, long t) {
        sp(c).edit().putLong("snooze", t).apply();
    }

    static long nudgeStart(Context c) {
        return sp(c).getLong("nudge_start", 0L);
    }

    static int nudgeLevel(Context c) {
        return sp(c).getInt("nudge_level", 0);
    }

    static void setNudge(Context c, long start, int level) {
        sp(c).edit().putLong("nudge_start", start).putInt("nudge_level", level).apply();
    }

    static long lastTick(Context c) {
        return sp(c).getLong("last_tick", 0L);
    }

    static void setLastTick(Context c, long t) {
        sp(c).edit().putLong("last_tick", t).apply();
    }

    static String planText(Context c) {
        return sp(c).getString("plan_text", "");
    }

    static String planDay(Context c) {
        return sp(c).getString("plan_day", "");
    }

    static void setPlan(Context c, String day, String text) {
        sp(c).edit().putString("plan_day", day).putString("plan_text", text == null ? "" : text).apply();
    }

    static String aboutMe(Context c) {
        return sp(c).getString("about", "");
    }

    static void setAboutMe(Context c, String s) {
        sp(c).edit().putString("about", s == null ? "" : s.trim()).apply();
    }

    static boolean syncOn(Context c) {
        return sp(c).getBoolean("sync_on", true);
    }

    static void setSyncOn(Context c, boolean on) {
        sp(c).edit().putBoolean("sync_on", on).apply();
    }

    static int classRemind(Context c) {
        return sp(c).getInt("class_remind", 15);
    }

    static long lastSyncAt(Context c) {
        return sp(c).getLong("last_sync", 0L);
    }

    static void setLastSyncAt(Context c, long t) {
        sp(c).edit().putLong("last_sync", t).apply();
    }

    static String pendingChange(Context c) {
        return sp(c).getString("pending_change", "");
    }

    static void setPendingChange(Context c, String json) {
        sp(c).edit().putString("pending_change", json == null ? "" : json).apply();
    }

    static String attendFrom(Context c) {
        return sp(c).getString("attend_from", "");
    }

    static void setAttendFrom(Context c, String key) {
        sp(c).edit().putString("attend_from", key == null ? "" : key).apply();
    }

    static void setClassRemind(Context c, int m) {
        sp(c).edit().putInt("class_remind", m).apply();
    }
}
