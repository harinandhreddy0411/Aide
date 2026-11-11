package com.aide.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

final class CalendarHelper {
    static final String[] PERMS = {Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR};

    static final class Cal {
        long id;
        String label;
        boolean google;
        boolean googlePrimary;
    }

    private CalendarHelper() {}

    static boolean has(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    static List<Cal> calendars(Context c) {
        List<Cal> out = new ArrayList<>();
        if (!has(c)) return out;
        String[] proj = {Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE, Calendars.IS_PRIMARY};
        try (Cursor q = c.getContentResolver().query(Calendars.CONTENT_URI, proj,
                Calendars.CALENDAR_ACCESS_LEVEL + " >= ?",
                new String[]{String.valueOf(Calendars.CAL_ACCESS_CONTRIBUTOR)},
                Calendars.ACCOUNT_NAME + " ASC")) {
            if (q == null) return out;
            while (q.moveToNext()) {
                Cal k = new Cal();
                k.id = q.getLong(0);
                String name = q.getString(1);
                String acc = q.getString(2);
                boolean google = "com.google".equals(q.getString(3));
                k.label = acc != null && !acc.equals(name) ? name + " (" + acc + ")" : String.valueOf(name);
                k.google = google;
                k.googlePrimary = google && q.getInt(4) == 1;
                out.add(k);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static long resolveCalendarId(Context c) {
        List<Cal> all = calendars(c);
        if (all.isEmpty()) return -1;
        long saved = Prefs.calendarId(c);
        for (Cal k : all) if (k.id == saved) return saved;
        for (Cal k : all) if (k.googlePrimary) return k.id;
        for (Cal k : all) if (k.google) return k.id;
        return all.get(0).id;
    }

    static long insert(Context c, long calId, String title, String desc, long start, int[] offsets) {
        try {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            v.put(CalendarContract.Events.TITLE, title);
            v.put(CalendarContract.Events.DESCRIPTION, desc);
            v.put(CalendarContract.Events.DTSTART, start);
            v.put(CalendarContract.Events.DTEND, start + 3600000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (u == null) return -1;
            long id = ContentUris.parseId(u);
            for (int m : offsets) {
                ContentValues r = new ContentValues();
                r.put(CalendarContract.Reminders.EVENT_ID, id);
                r.put(CalendarContract.Reminders.MINUTES, m);
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
            }
            return id;
        } catch (Exception e) {
            return -1;
        }
    }

    static long insertRange(Context c, long calId, String title, String desc, String where, long start, long end, int[] offsets) {
        try {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            v.put(CalendarContract.Events.TITLE, title);
            v.put(CalendarContract.Events.DESCRIPTION, desc);
            v.put(CalendarContract.Events.EVENT_LOCATION, where);
            v.put(CalendarContract.Events.DTSTART, start);
            v.put(CalendarContract.Events.DTEND, end);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            v.put(CalendarContract.Events.HAS_ALARM, offsets.length > 0 ? 1 : 0);
            Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (u == null) return -1;
            long id = ContentUris.parseId(u);
            for (int m : offsets) {
                ContentValues r = new ContentValues();
                r.put(CalendarContract.Reminders.EVENT_ID, id);
                r.put(CalendarContract.Reminders.MINUTES, m);
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
            }
            return id;
        } catch (Exception e) {
            return -1;
        }
    }

    static long insertAllDay(Context c, long calId, String title, String desc, int year, int month, int day) {
        try {
            java.util.Calendar utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utc.clear();
            utc.set(year, month, day);
            long start = utc.getTimeInMillis();
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            v.put(CalendarContract.Events.TITLE, title);
            v.put(CalendarContract.Events.DESCRIPTION, desc);
            v.put(CalendarContract.Events.DTSTART, start);
            v.put(CalendarContract.Events.DTEND, start + 86400000L);
            v.put(CalendarContract.Events.ALL_DAY, 1);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
            v.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE);
            Uri u = c.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
            return u == null ? -1 : ContentUris.parseId(u);
        } catch (Exception e) {
            return -1;
        }
    }

    static void delete(Context c, long eventId) {
        try {
            c.getContentResolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null);
        } catch (Exception ignored) {
        }
    }
}
