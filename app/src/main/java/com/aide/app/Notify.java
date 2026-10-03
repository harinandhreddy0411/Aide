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
    private static final String CHANNEL = "aide_updates";

    private Notify() {}

    private static boolean ready(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Replies and reviews", NotificationManager.IMPORTANCE_DEFAULT);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        return Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static void post(Context c, int id, String title, String body, Intent open) {
        if (!ready(c)) return;
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManagerCompat.from(c).notify(id, n);
    }

    static void reply(Context c, String text) {
        Intent i = new Intent(c, MainActivity.class).putExtra("tab", "chat")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        post(c, 1, "Reply ready", text, i);
    }

    static void info(Context c, String title, String body, int id) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        post(c, id, title, body, i);
    }

    static void review(Context c, Db.Item it, int id) {
        Intent i = EditItemActivity.intentFor(c, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        post(c, id, "Ready to review", it.title + ", " + Ui.timeText(it.start), i);
    }
}
