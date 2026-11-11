package com.aide.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class Assistant {

    static final class Parsed {
        Db.Item item;
        String error;
        boolean retry;
        long retryMs;
    }

    private static final String PLAN_PROMPT =
            "You plan the user's day on their phone. The schedule below lists fixed classes, meals, practice slots and "
                    + "items, plus the free gaps. Keep every fixed block at its stated time. Fill the free gaps with the "
                    + "user's extra requests, study or rest, and short breaks, and say plainly if something does not fit. "
                    + "Plain text only: no markdown, no asterisks, no headings. One line per block like "
                    + "'6:00 PM - 7:00 PM  Guitar practice'. At most 14 lines, then one line of advice.";

    private static final String PHOTO_PROMPT =
            "You read a photo of a weekly class timetable and return JSON only: "
                    + "{\"slots\":[{\"day\":\"Mon\",\"start\":\"09:00\",\"end\":\"09:50\","
                    + "\"type\":\"class|lab|tutorial|lunch|advisory\",\"code\":\"course code or empty\","
                    + "\"name\":\"subject name or empty\",\"label\":\"short extra label or empty\","
                    + "\"room\":\"room or empty\",\"optional\":false}]}. "
                    + "Use 24-hour HH:mm times taken from the period header row. A cell spanning several periods is one "
                    + "slot that starts at the first period and ends at the last period it covers. Lunch break cells have "
                    + "type lunch and Advisory Hour cells have type advisory. Cells marked Tutorial have type tutorial. "
                    + "Cells that say Lab have type lab. Skip empty cells. Never invent subjects.";

    private Assistant() {}

    static JSONArray single(String text) throws JSONException {
        return new JSONArray().put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", text))));
    }

    static Gemini.Result plan(Context c, String extra) {
        Gemini.Result none = new Gemini.Result();
        try {
            long now = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            sb.append("Now: ").append(new SimpleDateFormat("EEEE, yyyy-MM-dd h:mm a", Locale.US).format(new Date(now)))
                    .append(" (").append(TimeZone.getDefault().getID()).append(").\n\n");
            sb.append("Schedule:\n").append(Schedule.planText(c, Calendar.getInstance(), true)).append('\n');
            String about = Prefs.aboutMe(c);
            if (!about.isEmpty()) sb.append("\nAbout the user: ").append(about.replace('\n', ' ')).append('\n');
            String ask = extra == null ? "" : extra.trim();
            sb.append("\nExtra requests for today: ").append(ask.isEmpty() ? "none" : ask).append('\n');
            return Gemini.call(c, PLAN_PROMPT, single(sb.toString()), false);
        } catch (Exception e) {
            none.error = "Something went wrong. Please try again.";
            return none;
        }
    }

    static Gemini.Result change(Context c, String text) {
        Gemini.Result none = new Gemini.Result();
        try {
            StringBuilder subs = new StringBuilder();
            for (Db.Subject s : Db.get(c).subjects()) {
                subs.append("- ").append(s.code).append(": ").append(s.name).append(" (").append(s.kind).append(")\n");
            }
            String now = new SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(new Date());
            String sys = "You turn a student's short message about their class timetable and daily schedule into JSON. "
                    + "Now: " + now + " (" + TimeZone.getDefault().getID() + "). Resolve relative dates from now. "
                    + "Subjects (use the exact code in the subject field):\n" + subs
                    + "Reply with JSON only: {\"changes\":[...]} where each change is one of:\n"
                    + "{\"op\":\"cancel\",\"date\":\"yyyy-MM-dd\",\"subject\":\"code or empty\",\"period\":0,\"weekly\":false}\n"
                    + "{\"op\":\"move\",\"date\":\"yyyy-MM-dd\",\"subject\":\"code or empty\",\"period\":0,\"start\":\"HH:mm\",\"end\":\"HH:mm\",\"weekly\":false}\n"
                    + "{\"op\":\"holiday\",\"from\":\"yyyy-MM-dd\",\"to\":\"yyyy-MM-dd\",\"note\":\"\"}\n"
                    + "{\"op\":\"exam\",\"from\":\"yyyy-MM-dd\",\"to\":\"yyyy-MM-dd\",\"note\":\"\"}\n"
                    + "{\"op\":\"normal\",\"from\":\"yyyy-MM-dd\",\"to\":\"yyyy-MM-dd\"}\n"
                    + "{\"op\":\"follow\",\"date\":\"yyyy-MM-dd\",\"weekday\":\"Monday to Friday name\"}\n"
                    + "{\"op\":\"meal\",\"meal\":\"breakfast|lunch|dinner\",\"date\":\"yyyy-MM-dd or empty for every week\","
                    + "\"days\":\"all|weekdays|weekend\",\"start\":\"HH:mm\",\"end\":\"HH:mm\",\"skip\":false}\n"
                    + "{\"op\":\"taken\",\"date\":\"yyyy-MM-dd\",\"slot\":\"lunch|advisory|tutorial|period number\",\"subject\":\"code\"}\n"
                    + "{\"op\":\"free\",\"date\":\"yyyy-MM-dd\",\"slot\":\"lunch|advisory|tutorial|period number\"}\n"
                    + "{\"op\":\"attend\",\"date\":\"yyyy-MM-dd\"}\n"
                    + "{\"op\":\"rename\",\"from\":\"code or name\",\"to\":\"new name\"}\n"
                    + "Use empty strings and 0 when unknown. Periods are numbered 1 to 9 from 9:00 AM. "
                    + "\"No class\" or \"class cancelled\" is cancel. \"Faculty took lunch\" is taken. "
                    + "Set weekly true only when the user says every week or from now on. "
                    + "If the message is none of these, reply {\"changes\":[]}.";
            return Gemini.call(c, sys, single(text), true);
        } catch (Exception e) {
            none.error = "Something went wrong. Please try again.";
            return none;
        }
    }

    static Gemini.Result photo(Context c, String jpegBase64) {
        Gemini.Result none = new Gemini.Result();
        try {
            JSONArray parts = new JSONArray()
                    .put(new JSONObject().put("text", "Read this timetable."))
                    .put(new JSONObject().put("inlineData",
                            new JSONObject().put("mimeType", "image/jpeg").put("data", jpegBase64)));
            JSONArray contents = new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts));
            return Gemini.call(c, PHOTO_PROMPT, contents, true);
        } catch (Exception e) {
            none.error = "Something went wrong. Please try again.";
            return none;
        }
    }

    static Parsed quick(Context c, String text) {
        Parsed p = new Parsed();
        try {
            String now = new SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(new Date());
            String sys = "Turn the user's sentence into one calendar item. Now: " + now + " ("
                    + TimeZone.getDefault().getID() + "). Reply with JSON only: "
                    + "{\"title\":string,\"kind\":\"viva|task|other\",\"date\":\"yyyy-MM-dd\","
                    + "\"time\":\"HH:mm\" or \"\",\"remind\":[minutes before the event]}. "
                    + "Resolve relative dates from now. If the user gives no reminder, use [1440]. Keep the title short.";
            Gemini.Result r = Gemini.call(c, sys, single(text), true);
            if (!r.ok()) {
                p.error = r.error;
                p.retry = r.retryable();
                p.retryMs = r.retryMs;
                return p;
            }
            String s = r.text;
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a >= 0 && b > a) s = s.substring(a, b + 1);
            p.item = itemFromJson(new JSONObject(s));
            if (p.item == null) p.error = "Could not find a date in that. Try including one.";
        } catch (Exception e) {
            p.error = "Could not understand that. Try rephrasing.";
        }
        return p;
    }

    static Db.Item itemFromJson(JSONObject o) {
        try {
            String title = o.optString("title", "").trim();
            String date = o.optString("date", "").trim();
            String time = o.optString("time", "").trim();
            if (title.isEmpty() || date.isEmpty()) return null;
            if (time.isEmpty()) time = "09:00";
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            f.setLenient(false);
            Date d = f.parse(date + " " + time);
            if (d == null) return null;

            Db.Item it = new Db.Item();
            it.title = title;
            it.kind = normKind(o.optString("kind", "other"));
            it.start = d.getTime();
            StringBuilder offs = new StringBuilder();
            JSONArray rem = o.optJSONArray("remind");
            if (rem != null) {
                for (int i = 0; i < rem.length(); i++) {
                    int m = rem.optInt(i, -1);
                    if (m >= 0 && m <= 43200) {
                        if (offs.length() > 0) offs.append(',');
                        offs.append(m);
                    }
                }
            }
            it.offsets = offs.length() > 0 ? offs.toString() : "1440";
            return it;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normKind(String k) {
        String s = k == null ? "" : k.toLowerCase(Locale.US).trim();
        if (s.equals("viva") || s.equals("task")) return s;
        return "other";
    }
}
