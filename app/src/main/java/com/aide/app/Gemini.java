package com.aide.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Gemini {

    static final class Result {
        String text = "";
        String error;
        int code = 200;
        long retryMs;

        boolean ok() {
            return error == null;
        }

        boolean retryable() {
            return error != null && (code == 0 || code == 429 || code >= 500);
        }
    }

    private Gemini() {}

    static Result call(Context c, String system, JSONArray contents, boolean json) {
        String primary = Prefs.model(c);
        Result r = once(c, primary, system, contents, json);
        if (!r.retryable() || r.code == 0) return r;
        String fb = Prefs.fallbackModel(c);
        if (fb.isEmpty() || fb.equals(primary)) return r;
        Result r2 = once(c, fb, system, contents, json);
        if (r2.ok()) return r2;
        if (r2.retryMs > 0 && (r.retryMs == 0 || r2.retryMs < r.retryMs)) r.retryMs = r2.retryMs;
        return r;
    }

    private static Result once(Context c, String model, String system, JSONArray contents, boolean json) {
        Result r = new Result();
        String key = Prefs.apiKey(c).trim();
        if (key.isEmpty()) {
            r.error = "Add your Gemini API key in Settings first.";
            r.code = -1;
            return r;
        }
        HttpURLConnection conn = null;
        try {
            JSONObject cfg = new JSONObject();
            cfg.put("temperature", json ? 0.1 : 0.7);
            if (json) cfg.put("responseMimeType", "application/json");
            JSONObject body = new JSONObject();
            body.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", system))));
            body.put("contents", contents);
            body.put("generationConfig", cfg);

            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-goog-api-key", key);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            r.code = code;
            boolean good = code >= 200 && code < 300;
            String resp = readAll(good ? conn.getInputStream() : conn.getErrorStream());
            if (!good) {
                r.error = failure(code, resp);
                r.retryMs = retryDelay(resp);
                return r;
            }

            JSONObject o = new JSONObject(resp);
            JSONArray cands = o.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) {
                r.error = "The model returned no answer. It may have been blocked.";
                return r;
            }
            JSONObject content = cands.getJSONObject(0).optJSONObject("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                JSONArray parts = content.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        sb.append(parts.getJSONObject(i).optString("text", ""));
                    }
                }
            }
            r.text = sb.toString().trim();
            if (r.text.isEmpty()) r.error = "The model returned an empty answer.";
        } catch (IOException e) {
            r.code = 0;
            r.error = "No connection. Check your internet and try again.";
        } catch (Exception e) {
            r.code = -2;
            r.error = "The request failed. Please try again.";
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private static long retryDelay(String resp) {
        try {
            JSONArray d = new JSONObject(resp).getJSONObject("error").optJSONArray("details");
            if (d == null) return 0;
            for (int i = 0; i < d.length(); i++) {
                String s = d.getJSONObject(i).optString("retryDelay", "");
                if (s.endsWith("s")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String failure(int code, String resp) {
        if (code == 429) return "Quota or rate limit reached.";
        try {
            return new JSONObject(resp).getJSONObject("error").optString("message", "Request failed (" + code + ")");
        } catch (Exception e) {
            return "Request failed (" + code + ")";
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }
}
