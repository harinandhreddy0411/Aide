package com.aide.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String action = i.getAction();
        if (action == null) return;
        Context app = c.getApplicationContext();
        long rid = i.getLongExtra("rid", -1);
        String day = i.getStringExtra("day");
        int nid = i.getIntExtra("nid", -1);
        PendingResult pr = goAsync();
        Bg.run(() -> {
            try {
                if (Agenda.ROUTINE_DONE.equals(action) && rid > 0 && day != null) {
                    Db.get(app).setStatus(rid, day, 1);
                } else if (Agenda.ROUTINE_SKIP.equals(action) && rid > 0 && day != null) {
                    Db.get(app).setStatus(rid, day, 2);
                } else if (Agenda.SNOOZE.equals(action)) {
                    Prefs.setSnoozeUntil(app, System.currentTimeMillis() + 3600000L);
                }
                if (nid > 0) Notify.cancel(app, nid);
            } finally {
                pr.finish();
            }
        });
    }
}
