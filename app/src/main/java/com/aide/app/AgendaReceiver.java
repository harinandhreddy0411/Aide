package com.aide.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AgendaReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        Context app = c.getApplicationContext();
        PendingResult pr = goAsync();
        Bg.run(() -> {
            try {
                Agenda.tick(app);
                NextItemWidget.refresh(app);
            } finally {
                pr.finish();
            }
        });
    }
}
