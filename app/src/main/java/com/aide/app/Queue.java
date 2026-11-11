package com.aide.app;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

final class Queue {
    static final Object LOCK = new Object();
    private static final long MAX_AGE = 24L * 3600000L;
    private static final int DONE = 0;
    private static final int RETRY = 1;

    private Queue() {}

    static void kick(Context c, long delayMs) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(QueueWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(c.getApplicationContext()).enqueueUniqueWork("aide-queue", ExistingWorkPolicy.REPLACE, req);
    }

    static boolean process(Context c) {
        Db db = Db.get(c);
        long now = System.currentTimeMillis();
        for (Db.Job j : db.jobs()) {
            if (!"quick".equals(j.kind) && !"plan".equals(j.kind) && !"change".equals(j.kind)) {
                db.deleteJob(j.id);
                continue;
            }
            if (now - j.created > MAX_AGE) {
                giveUp(c, db, j);
                continue;
            }
            int out;
            if ("plan".equals(j.kind)) {
                out = plan(c, j);
            } else if ("change".equals(j.kind)) {
                out = change(c, j);
            } else {
                out = quick(c, j);
            }
            if (out == DONE) {
                db.deleteJob(j.id);
            } else {
                db.bumpJob(j.id);
                break;
            }
        }
        return !db.jobs().isEmpty();
    }

    private static int quick(Context c, Db.Job j) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Assistant.Parsed p = Assistant.quick(c, j.payload);
            if (p.item != null) {
                Notify.review(c, p.item, 3000 + (int) (j.id % 1000));
                return DONE;
            }
            if (!p.retry) {
                Notify.info(c, "Could not add an item", String.valueOf(p.error), 3000 + (int) (j.id % 1000));
                return DONE;
            }
            if (attempt == 0 && p.retryMs > 0 && p.retryMs <= 65000 && pause(p.retryMs + 1000)) continue;
            return RETRY;
        }
        return RETRY;
    }

    private static int plan(Context c, Db.Job j) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Gemini.Result r = Assistant.plan(c, j.payload);
            if (r.ok()) {
                Prefs.setPlan(c, Agenda.dayKey(System.currentTimeMillis()), r.text);
                Notify.info(c, "Your day plan is ready", r.text, 4000);
                return DONE;
            }
            if (!r.retryable()) {
                Notify.info(c, "Could not plan your day", String.valueOf(r.error), 4000);
                return DONE;
            }
            if (attempt == 0 && r.retryMs > 0 && r.retryMs <= 65000 && pause(r.retryMs + 1000)) continue;
            return RETRY;
        }
        return RETRY;
    }

    private static int change(Context c, Db.Job j) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Gemini.Result r = Assistant.change(c, j.payload);
            if (r.ok()) {
                Changes.Parsed p = Changes.parse(c, r.text);
                if (p.error != null) {
                    Notify.info(c, "Could not apply your change", p.error, 4100);
                } else {
                    Prefs.setPendingChange(c, r.text);
                    Notify.open(c, 4100, "Review your schedule change", "Tap to preview and apply it.", "review", "1");
                }
                return DONE;
            }
            if (!r.retryable()) {
                Notify.info(c, "Could not apply your change", String.valueOf(r.error), 4100);
                return DONE;
            }
            if (attempt == 0 && r.retryMs > 0 && r.retryMs <= 65000 && pause(r.retryMs + 1000)) continue;
            return RETRY;
        }
        return RETRY;
    }

    private static void giveUp(Context c, Db db, Db.Job j) {
        if ("plan".equals(j.kind)) {
            Notify.info(c, "Could not plan your day", "Rate limits kept blocking it. Try again later.", 4000);
        } else if ("change".equals(j.kind)) {
            Notify.info(c, "Could not apply your change", "Rate limits kept blocking it. Try again later.", 4100);
        } else {
            Notify.info(c, "Could not add an item", "Rate limits kept blocking it. Try again later.",
                    3000 + (int) (j.id % 1000));
        }
        db.deleteJob(j.id);
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
