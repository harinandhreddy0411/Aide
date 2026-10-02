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

    static long calendarId(Context c) {
        return sp(c).getLong("cal", -1L);
    }

    static void setCalendarId(Context c, long id) {
        sp(c).edit().putLong("cal", id).apply();
    }

    static String summary(Context c) {
        return sp(c).getString("summary", "");
    }

    static void setSummary(Context c, String s) {
        sp(c).edit().putString("summary", s == null ? "" : s).apply();
    }
}
