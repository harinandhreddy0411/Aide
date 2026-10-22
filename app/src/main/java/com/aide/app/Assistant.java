package com.aide.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Assistant {
    private static final int SUMMARY_TRIGGER_TOKENS = 12000;
    private static final int KEEP_RECENT = 6;

    private static final Pattern NOTE = Pattern.compile("\\[\\[note:(.+?)\\]\\]", Pattern.DOTALL);
    private static final Pattern EVENT = Pattern.compile("\\[\\[event:\\s*(\\{.*?\\})\\s*\\]\\]", Pattern.DOTALL);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|passcode|\\botp\\b|\\bcvv\\b|\\bpin\\b|card number|\\d{6,})");

    private static final String RULES =
            "\nTo remember a lasting fact or preference about the user, add a line at the very end of your reply: "
                    + "[[note: the fact]]. Never store passwords, codes or card numbers.\n"
                    + "When the user wants something scheduled, add a line at the very end: "
                    + "[[event: {\"title\":\"...\",\"kind\":\"viva|task|other\",\"date\":\"yyyy-MM-dd\","
                    + "\"time\":\"HH:mm\",\"remind\":[2880]}]] where remind lists minutes before the event, "
                    + "and tell the user it is ready to review. These bracketed lines are hidden from the user.";

    private static final String COMPRESS_PROMPT =
            "You compress conversations. Write a compact factual summary that keeps every fact, decision and "
                    + "open task needed to continue the conversation. Plain text, under 250 words.";

    private static final String HANDOFF_PROMPT =
            "Write a handoff summary of this conversation so the user can continue it in a new chat with any AI "
                    + "without re-explaining. Cover the goal, key facts and decisions, current state, open questions "
                    + "and next steps. Plain text, under 300 words.";

    static final class Turn {
        String error;
        String reply;
        Db.Item proposal;
        boolean retry;
        long retryMs;
    }

    static final class Parsed {
        Db.Item item;
        String error;
        boolean retry;
        long retryMs;
    }

    private Assistant() {}

    private static JSONArray single(String text) throws JSONException {
        return new JSONArray().put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", text))));
    }

    private static JSONArray contents(List<Db.Msg> msgs) throws JSONException {
        List<String[]> merged = new ArrayList<>();
        for (Db.Msg m : msgs) {
            String role = "user".equals(m.role) ? "user" : "model";
            if (merged.isEmpty() && !role.equals("user")) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1)[0].equals(role)) {
                String[] last = merged.get(merged.size() - 1);
                last[1] = last[1] + "\n\n" + m.body;
            } else {
                merged.add(new String[]{role, m.body});
            }
        }
        JSONArray arr = new JSONArray();
        for (String[] p : merged) {
            arr.put(new JSONObject().put("role", p[0])
                    .put("parts", new JSONArray().put(new JSONObject().put("text", p[1]))));
        }
        return arr;
    }

    private static String system(Context c) {
        Db db = Db.get(c);
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("You are the user's personal assistant inside their phone app. Be brief, direct and practical.\n");
        sb.append("Now: ").append(new SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(new Date(now)))
                .append(" (").append(TimeZone.getDefault().getID()).append(").\n");

        StringBuilder notes = new StringBuilder();
        for (Db.Note n : db.notes()) {
            int cap = n.body.startsWith("Handoff:") ? 3000 : 600;
            String b = n.body.length() > cap ? n.body.substring(0, cap) : n.body;
            if (notes.length() + b.length() > 8000) break;
            notes.append("- ").append(b.replace('\n', ' ')).append('\n');
        }
        if (notes.length() > 0) sb.append("\nWhat you know about the user:\n").append(notes);

        List<Db.Item> up = db.upcoming(now);
        if (!up.isEmpty()) {
            sb.append("\nUpcoming items:\n");
            SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.US);
            for (int i = 0; i < up.size() && i < 10; i++) {
                Db.Item it = up.get(i);
                sb.append("- ").append(it.kind).append(": ").append(it.title).append(", ")
                        .append(f.format(new Date(it.start))).append('\n');
            }
        }

        String summary = Prefs.summary(c);
        if (!summary.isEmpty()) sb.append("\nSummary of the earlier conversation:\n").append(summary).append('\n');
        sb.append(RULES);
        return sb.toString();
    }

    private static void maybeSummarize(Context c) {
        try {
            Db db = Db.get(c);
            List<Db.Msg> active = db.activeMsgs();
            String prev = Prefs.summary(c);
            int chars = prev.length();
            for (Db.Msg m : active) chars += m.body.length();
            if (chars / 4 < SUMMARY_TRIGGER_TOKENS || active.size() <= KEEP_RECENT + 2) return;

            List<Db.Msg> old = active.subList(0, active.size() - KEEP_RECENT);
            StringBuilder sb = new StringBuilder();
            if (!prev.isEmpty()) sb.append("Previous summary:\n").append(prev).append("\n\n");
            sb.append("Conversation to fold in:\n");
            for (Db.Msg m : old) {
                sb.append("user".equals(m.role) ? "User: " : "Assistant: ").append(m.body).append('\n');
            }
            Gemini.Result r = Gemini.call(c, COMPRESS_PROMPT, single(sb.toString()), false);
            if (r.ok()) {
                Prefs.setSummary(c, r.text);
                db.markSummarized(old.get(old.size() - 1).id);
            }
        } catch (Exception ignored) {
        }
    }

    static Turn respond(Context c) {
        Turn t = new Turn();
        synchronized (Queue.LOCK) {
            try {
                Db db = Db.get(c);
                List<Db.Msg> active = db.activeMsgs();
                if (!active.isEmpty() && "model".equals(active.get(active.size() - 1).role)) return t;
                maybeSummarize(c);
                Gemini.Result r = Gemini.call(c, system(c), contents(db.activeMsgs()), false);
                if (!r.ok()) {
                    t.error = r.error;
                    t.retry = r.retryable();
                    t.retryMs = r.retryMs;
                    return t;
                }
                String raw = r.text;

                Matcher nm = NOTE.matcher(raw);
                int saved = 0;
                while (nm.find() && saved < 3) {
                    String n = nm.group(1).trim();
                    if (!n.isEmpty() && !SECRET.matcher(n).find()) {
                        db.addNoteIfNew(n);
                        saved++;
                    }
                }

                Matcher em = EVENT.matcher(raw);
                if (em.find()) t.proposal = itemFromJson(new JSONObject(em.group(1)));

                String clean = EVENT.matcher(NOTE.matcher(raw).replaceAll("")).replaceAll("").trim();
                if (clean.isEmpty()) clean = "Done.";
                db.addMsg("model", clean);
                db.deleteJobs("chat");
                t.reply = clean;
            } catch (Exception e) {
                t.error = "Something went wrong. Please try again.";
            }
        }
        return t;
    }

    static Gemini.Result handoff(Context c) {
        Gemini.Result none = new Gemini.Result();
        try {
            List<Db.Msg> msgs = Db.get(c).activeMsgs();
            String summary = Prefs.summary(c);
            if (msgs.isEmpty() && summary.isEmpty()) {
                none.error = "Nothing to summarize yet.";
                return none;
            }
            StringBuilder sb = new StringBuilder();
            if (!summary.isEmpty()) sb.append("Earlier summary:\n").append(summary).append("\n\n");
            sb.append("Conversation:\n");
            for (Db.Msg m : msgs) {
                sb.append("user".equals(m.role) ? "User: " : "Assistant: ").append(m.body).append('\n');
            }
            return Gemini.call(c, HANDOFF_PROMPT, single(sb.toString()), false);
        } catch (Exception e) {
            none.error = "Something went wrong. Please try again.";
            return none;
        }
    }

    private static final String PLAN_PROMPT =
            "You plan the user's day on their phone. Build a realistic time-blocked plan from now until bedtime "
                    + "(assume 23:00 unless the notes say otherwise). Keep every fixed item and practice slot at its "
                    + "stated time. Fit the user's extra requests into free time, add short breaks and meals, and say "
                    + "plainly if something does not fit. Plain text only: no markdown, no asterisks, no headings. "
                    + "One line per block like '6:00 PM - 7:00 PM  Guitar practice'. At most 14 lines, then one line "
                    + "of advice.";

    static Gemini.Result plan(Context c, String extra) {
        Gemini.Result none = new Gemini.Result();
        try {
            Db db = Db.get(c);
            long now = System.currentTimeMillis();
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            long dayStart = day.getTimeInMillis();
            Calendar next = (Calendar) day.clone();
            next.add(Calendar.DAY_OF_MONTH, 1);
            long dayEnd = next.getTimeInMillis();
            String key = Agenda.dayKey(dayStart);
            SimpleDateFormat tf = new SimpleDateFormat("h:mm a", Locale.US);

            StringBuilder sb = new StringBuilder();
            sb.append("Now: ").append(new SimpleDateFormat("EEEE, yyyy-MM-dd h:mm a", Locale.US).format(new Date(now)))
                    .append(" (").append(TimeZone.getDefault().getID()).append(").\n");

            StringBuilder fixed = new StringBuilder();
            for (Db.Item it : db.open()) {
                if (it.start >= dayEnd) continue;
                fixed.append("- ").append(it.kind).append(": ").append(it.title).append(", ")
                        .append(it.start < dayStart ? "overdue" : tf.format(new Date(it.start))).append('\n');
            }
            sb.append("\nFixed items:\n").append(fixed.length() > 0 ? fixed.toString() : "none\n");

            StringBuilder practice = new StringBuilder();
            for (Db.Routine r : db.routines()) {
                if (!r.runsOn(day)) continue;
                practice.append("- ").append(r.title).append(", ").append(Ui.clock(r.startMin)).append(" to ")
                        .append(Ui.clock(r.endMin));
                if (db.status(r.id, key) == 1) practice.append(" (already done)");
                practice.append('\n');
            }
            sb.append("\nDaily practice slots today:\n").append(practice.length() > 0 ? practice.toString() : "none\n");

            StringBuilder notes = new StringBuilder();
            for (Db.Note n : db.notes()) {
                if (n.body.startsWith("Handoff:")) continue;
                String b = n.body.length() > 300 ? n.body.substring(0, 300) : n.body;
                if (notes.length() + b.length() > 1500) break;
                notes.append("- ").append(b.replace('\n', ' ')).append('\n');
            }
            if (notes.length() > 0) sb.append("\nWhat you know about the user:\n").append(notes);

            String ask = extra == null ? "" : extra.trim();
            sb.append("\nExtra requests for today: ").append(ask.isEmpty() ? "none" : ask).append('\n');
            return Gemini.call(c, PLAN_PROMPT, single(sb.toString()), false);
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
