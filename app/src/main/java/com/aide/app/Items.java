package com.aide.app;

import android.content.Context;

final class Items {
    private Items() {}

    static String save(Context c, Db.Item it) {
        if (it.done) {
            Db.get(c).saveItem(it);
            NextItemWidget.refresh(c);
            return null;
        }
        if (!CalendarHelper.has(c)) return "Calendar access is off. Allow it in Android settings.";
        long cal = CalendarHelper.resolveCalendarId(c);
        if (cal < 0) return "No writable calendar found. Add a Google account with Calendar.";
        if (it.eventId > 0) CalendarHelper.delete(c, it.eventId);
        long ev = CalendarHelper.insert(c, cal, it.title, "Added by Aide", it.start, it.offsetArray());
        if (ev < 0) return "Could not write to the calendar.";
        it.eventId = ev;
        it.calId = cal;
        Db.get(c).saveItem(it);
        NextItemWidget.refresh(c);
        return null;
    }

    static String setDone(Context c, Db.Item it, boolean done) {
        if (done) {
            if (it.eventId > 0 && CalendarHelper.has(c)) CalendarHelper.delete(c, it.eventId);
            it.eventId = -1;
            it.done = true;
            Db.get(c).saveItem(it);
            NextItemWidget.refresh(c);
            return null;
        }
        it.done = false;
        if (it.start < System.currentTimeMillis()) {
            it.eventId = -1;
            Db.get(c).saveItem(it);
            NextItemWidget.refresh(c);
            return null;
        }
        return save(c, it);
    }

    static void remove(Context c, Db.Item it) {
        if (it.eventId > 0 && CalendarHelper.has(c)) CalendarHelper.delete(c, it.eventId);
        if (it.id > 0) Db.get(c).deleteItem(it.id);
        NextItemWidget.refresh(c);
    }
}
