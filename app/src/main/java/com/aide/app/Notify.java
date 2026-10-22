package com.aide.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

final class Notify {
    static final String UPDATES = "aide_updates";
    static final String PRACTICE = "aide_practice";
    static final String BRIEF = "aide_brief";
    static final String NUDGE = "aide_nudge";

    private static final int FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

    private Notify() {}

    private static boolean ready(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(UPDATES, "Replies and reviews", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(PRACTICE, "Daily practice", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(BRIEF, "Morning brief", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(NUDGE, "Screen time", NotificationManager.IMPORTANCE_DEFAULT));
        return Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void post(Context c, String channel, int id, String title, String body, Intent open,
                     NotificationCompat.Action... actions) {
        if (!ready(c)) return;
        PendingIntent pi = PendingIntent.getActivity(c, id, open, FLAGS);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true);
        for (NotificationCompat.Action a : actions) b.addAction(a);
        Notification n = b.build();
        NotificationManagerCompat.from(c).notify(id, n);
    }

    static void cancel(Context c, int id) {
        NotificationManagerCompat.from(c).cancel(id);
    }

    static NotificationCompat.Action broadcastAction(Context c, int request, String label, Intent i) {
        PendingIntent pi = PendingIntent.getBroadcast(c, request, i, FLAGS);
        return new NotificationCompat.Action.Builder(0, label, pi).build();
    }

    static NotificationCompat.Action activityAction(Context c, int request, String label, Intent i) {
        PendingIntent pi = PendingIntent.getActivity(c, request, i, FLAGS);
        return new NotificationCompat.Action.Builder(0, label, pi).build();
    }

    static Intent home(Context c) {
        return new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static void reply(Context c, String text) {
        Intent i = home(c).putExtra("tab", "chat");
        post(c, UPDATES, 1, "Reply ready", text, i);
    }

    static void info(Context c, String title, String body, int id) {
        post(c, UPDATES, id, title, body, home(c));
    }

    static void review(Context c, Db.Item it, int id) {
        Intent i = EditItemActivity.intentFor(c, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        post(c, UPDATES, id, "Ready to review", it.title + ", " + Ui.timeText(it.start), i);
    }
}
