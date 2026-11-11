package com.aide.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TimetableImport {

    static final class Entry {
        int dow;
        int start;
        int end;
        String type = "class";
        String code = "";
        String name = "";
        String label = "";
        String room = "";
        boolean optional;
    }

    static final class Result {
        List<Entry> entries = new ArrayList<>();
        String error;
        boolean retry;
        long retryMs;
    }

    private static final int MAX_SIDE = 1800;

    private TimetableImport() {}

    static String encode(Context c, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("no stream");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > MAX_SIDE * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("no stream");
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IllegalStateException("bad image");

        int rotation = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            }
        } catch (Exception ignored) {
        }

        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        Matrix m = new Matrix();
        if (scale < 1f) m.postScale(scale, scale);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap out = bmp;
        if (!m.isIdentity()) {
            out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (out != bmp) bmp.recycle();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 88, bos);
        out.recycle();
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    static Result fromPhoto(Context c, Uri uri) {
        Result r = new Result();
        String data;
        try {
            data = encode(c, uri);
        } catch (Exception e) {
            r.error = "Could not read that image.";
            return r;
        }
        Gemini.Result g = Assistant.photo(c, data);
        if (!g.ok()) {
            r.error = g.error;
            r.retry = g.retryable();
            r.retryMs = g.retryMs;
            return r;
        }
        return fromJson(g.text);
    }

    static Result fromJson(String json) {
        Result r = new Result();
        try {
            String s = json == null ? "" : json;
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a >= 0 && b > a) s = s.substring(a, b + 1);
            JSONArray arr = new JSONObject(s).optJSONArray("slots");
            if (arr == null) {
                r.error = "No slots found in that.";
                return r;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.dow = Schedule.dowFromName(o.optString("day", ""));
                e.start = Changes.minutes(o.optString("start", ""));
                e.end = Changes.minutes(o.optString("end", ""));
                if (e.dow < 2 || e.dow > 6 || e.start < 0 || e.end <= e.start) continue;
                String t = o.optString("type", "class").trim().toLowerCase(Locale.US);
                e.type = t.equals("lab") || t.equals("tutorial") || t.equals("lunch") || t.equals("advisory") ? t : "class";
                e.code = clean(o.optString("code", ""));
                e.name = clean(o.optString("name", ""));
                e.label = clean(o.optString("label", ""));
                e.room = clean(o.optString("room", ""));
                e.optional = o.optBoolean("optional", false);
                r.entries.add(e);
            }
            if (r.entries.isEmpty()) r.error = "No usable slots found in that.";
        } catch (Exception ex) {
            r.error = "Could not read that as a timetable.";
        }
        return r;
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.trim();
        return t.equals("null") ? "" : t;
    }

    static String summary(List<Entry> list) {
        int[] perDay = new int[7];
        for (Entry e : list) perDay[e.dow - 2]++;
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < 5; d++) {
            if (d > 0) sb.append("\n");
            sb.append(Schedule.dayName(d + 2)).append(": ").append(perDay[d]).append(" slots");
        }
        return sb.toString();
    }

    static void replace(Context c, List<Entry> list) {
        Db db = Db.get(c);
        db.deleteAllSlots();
        for (Entry e : list) {
            Db.Slot s = new Db.Slot();
            s.dow = e.dow;
            s.startMin = e.start;
            s.endMin = e.end;
            s.type = e.type;
            s.label = e.label;
            s.room = e.room;
            s.optional = e.optional;
            boolean subjectType = e.type.equals("class") || e.type.equals("lab") || e.type.equals("tutorial");
            if (subjectType) {
                s.subjectId = subjectFor(db, e);
                s.units = e.type.equals("lab") ? 1 : Math.max(1, Math.round((e.end - e.start) / 50f));
            }
            db.saveSlot(s);
        }
        Schedule.changed(c);
    }

    private static long subjectFor(Db db, Entry e) {
        Db.Subject s = null;
        if (!e.code.isEmpty()) s = db.subjectByCode(e.code);
        if (s == null && !e.name.isEmpty()) s = Changes.findSubject(db, e.name);
        if (s != null) return s.id;
        if (e.code.isEmpty() && e.name.isEmpty()) return 0;
        Db.Subject n = new Db.Subject();
        n.code = e.code.isEmpty() ? e.name : e.code;
        n.name = e.name.isEmpty() ? e.code : e.name;
        n.kind = e.type.equals("lab") ? "lab" : "theory";
        return db.saveSubject(n);
    }
}
