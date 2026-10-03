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
    private static Runnable ui;
    private static boolean visible;

    private Queue() {}

    static void setUi(Runnable r) {
        ui = r;
    }

    static void setVisible(boolean v) {
        visible = v;
    }

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
            if (now - j.created > MAX_AGE) {
                giveUp(c, db, j);
                continue;
            }
            int out = "chat".equals(j.kind) ? chat(c, db) : quick(c, j);
            if (out == DONE) {
                db.deleteJob(j.id);
            } else {
                db.bumpJob(j.id);
                break;
            }
        }
        Runnable r = ui;
        if (r != null) Bg.main(r);
        return !db.jobs().isEmpty();
    }

    private static int chat(Context c, Db db) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Assistant.Turn t = Assistant.respond(c);
            if (t.error == null) {
                if (t.proposal != null) Notify.review(c, t.proposal, 2000);
                if (t.reply != null && !visible) Notify.reply(c, t.reply);
                return DONE;
            }
            if (!t.retry) {
                db.addMsg("model", "I could not answer: " + t.error);
                return DONE;
            }
            if (attempt == 0 && t.retryMs > 0 && t.retryMs <= 65000 && pause(t.retryMs + 1000)) continue;
            return RETRY;
        }
        return RETRY;
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

    private static void giveUp(Context c, Db db, Db.Job j) {
        if ("chat".equals(j.kind)) {
            db.addMsg("model", "I could not get a reply because of rate limits. Please send it again later.");
            db.deleteJobs("chat");
        } else {
            Notify.info(c, "Could not add an item", "Rate limits kept blocking it. Try again later.",
                    3000 + (int) (j.id % 1000));
            db.deleteJob(j.id);
        }
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
