package com.aide.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

public class EditRoutineActivity extends AppCompatActivity {
    private long id;
    private int startMin = 18 * 60;
    private int endMin = 19 * 60;
    private TextInputLayout titleLayout;
    private TextInputEditText title;
    private Button startBtn;
    private Button endBtn;
    private ChipGroup dayGroup;
    private ChipGroup nudgeGroup;

    static Intent intentFor(Context c, Db.Routine r) {
        Intent i = new Intent(c, EditRoutineActivity.class);
        if (r != null) i.putExtra("id", r.id);
        return i;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_routine);
        Ui.insets(findViewById(R.id.root));

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        titleLayout = findViewById(R.id.title_layout);
        title = findViewById(R.id.title);
        startBtn = findViewById(R.id.btn_start);
        endBtn = findViewById(R.id.btn_end);
        dayGroup = findViewById(R.id.day_group);
        nudgeGroup = findViewById(R.id.nudge_group);

        id = getIntent().getLongExtra("id", 0);
        int days = 127;
        int nudge = 30;
        if (id > 0) {
            Db.Routine r = Db.get(this).routine(id);
            if (r == null) {
                finish();
                return;
            }
            title.setText(r.title);
            startMin = r.startMin;
            endMin = r.endMin;
            days = r.days;
            nudge = r.nudgeMin;
            tb.inflateMenu(R.menu.edit);
            tb.setOnMenuItemClickListener(mi -> {
                if (mi.getItemId() == R.id.action_delete) {
                    confirmDelete();
                    return true;
                }
                return false;
            });
        }

        for (int i = 0; i < dayGroup.getChildCount(); i++) {
            View v = dayGroup.getChildAt(i);
            if (v instanceof Chip) {
                int dow = Integer.parseInt(String.valueOf(v.getTag()));
                ((Chip) v).setChecked((days & (1 << (dow - 1))) != 0);
            }
        }
        boolean matched = false;
        for (int i = 0; i < nudgeGroup.getChildCount(); i++) {
            View v = nudgeGroup.getChildAt(i);
            if (v instanceof Chip && Integer.parseInt(String.valueOf(v.getTag())) == nudge) {
                ((Chip) v).setChecked(true);
                matched = true;
            }
        }
        if (!matched) nudgeGroup.check(R.id.nudge_30);

        startBtn.setOnClickListener(v -> pick(true));
        endBtn.setOnClickListener(v -> pick(false));
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        render();
    }

    private void render() {
        startBtn.setText("From " + Ui.clock(startMin));
        endBtn.setText("Until " + Ui.clock(endMin));
    }

    private void pick(boolean start) {
        int current = start ? startMin : endMin;
        MaterialTimePicker tp = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(current / 60)
                .setMinute(current % 60)
                .setTitleText(start ? "Start of slot" : "End of slot")
                .build();
        tp.addOnPositiveButtonClickListener(v -> {
            int m = tp.getHour() * 60 + tp.getMinute();
            if (start) {
                startMin = m;
                if (endMin <= startMin) endMin = Math.min(startMin + 60, 23 * 60 + 59);
            } else {
                endMin = m;
            }
            render();
        });
        tp.show(getSupportFragmentManager(), start ? "start" : "end");
    }

    private int daysMask() {
        int mask = 0;
        for (int i = 0; i < dayGroup.getChildCount(); i++) {
            View v = dayGroup.getChildAt(i);
            if (v instanceof Chip && ((Chip) v).isChecked()) {
                mask |= 1 << (Integer.parseInt(String.valueOf(v.getTag())) - 1);
            }
        }
        return mask;
    }

    private int nudgeMinutes() {
        for (int i = 0; i < nudgeGroup.getChildCount(); i++) {
            View v = nudgeGroup.getChildAt(i);
            if (v instanceof Chip && ((Chip) v).isChecked()) return Integer.parseInt(String.valueOf(v.getTag()));
        }
        return 0;
    }

    private void save() {
        String t = title.getText() == null ? "" : title.getText().toString().trim();
        if (t.isEmpty()) {
            titleLayout.setError("What do you practice?");
            return;
        }
        titleLayout.setError(null);
        int mask = daysMask();
        if (mask == 0) {
            Toast.makeText(this, "Pick at least one day.", Toast.LENGTH_LONG).show();
            return;
        }
        if (endMin <= startMin) {
            Toast.makeText(this, "The slot must end after it starts.", Toast.LENGTH_LONG).show();
            return;
        }
        Db.Routine r = new Db.Routine();
        r.id = id;
        r.title = t;
        r.startMin = startMin;
        r.endMin = endMin;
        r.days = mask;
        r.nudgeMin = nudgeMinutes();
        Db.get(this).saveRoutine(r);
        Context app = getApplicationContext();
        Bg.run(() -> Agenda.rearm(app));
        finish();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this practice?")
                .setMessage("Its history and reminders are removed.")
                .setPositiveButton("Delete", (d, w) -> {
                    Db.get(this).deleteRoutine(id);
                    Notify.cancel(this, Agenda.notifId(id));
                    Context app = getApplicationContext();
                    Bg.run(() -> Agenda.rearm(app));
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
