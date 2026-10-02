package com.aide.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class ShareReceiverActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        CharSequence t = in == null ? null : in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (t != null && t.toString().trim().length() > 0) {
            Db.Note n = new Db.Note();
            n.body = "Handoff: " + t.toString().trim();
            Db.get(this).saveNote(n);
            Toast.makeText(this, "Saved to your notes", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
