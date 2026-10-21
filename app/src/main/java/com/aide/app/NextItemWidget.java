package com.aide.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;

public class NextItemWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(c));
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, NextItemWidget.class));
        for (int id : ids) m.updateAppWidget(id, build(c));
    }

    private static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_next);
        List<Db.Item> items = Db.get(c).open();
        if (items.isEmpty()) {
            v.setTextViewText(R.id.w_rel, "All clear");
            v.setTextViewText(R.id.w_title, "Nothing scheduled");
            v.setTextViewText(R.id.w_when, "");
        } else {
            Db.Item it = items.get(0);
            v.setTextViewText(R.id.w_rel, Ui.relative(it.start));
            v.setTextViewText(R.id.w_title, it.title);
            v.setTextViewText(R.id.w_when, Ui.timeText(it.start));
        }
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 10, new Intent(c, MainActivity.class), flags));
        v.setOnClickPendingIntent(R.id.w_add, PendingIntent.getActivity(c, 11, new Intent(c, EditItemActivity.class), flags));
        v.setOnClickPendingIntent(R.id.w_speak, PendingIntent.getActivity(c, 12,
                new Intent(c, MainActivity.class).putExtra("voice", true), flags));
        return v;
    }
}
