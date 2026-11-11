package com.aide.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class AttendanceActivity extends AppCompatActivity {
    private TextView intro;
    private View progress;
    private LinearLayout list;
    private View markBtn;
    private android.widget.Button startBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_attendance);
        Ui.insets(findViewById(R.id.root));
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        intro = findViewById(R.id.intro);
        progress = findViewById(R.id.progress);
        list = findViewById(R.id.list);
        markBtn = findViewById(R.id.btn_mark);
        startBtn = findViewById(R.id.btn_start);
        markBtn.setOnClickListener(v -> pickDay());
        startBtn.setOnClickListener(v -> pickStart());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private static String utcKey(long utcMillis) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(utcMillis);
        return String.format(Locale.US, "%04d-%02d-%02d", u.get(Calendar.YEAR), u.get(Calendar.MONTH) + 1, u.get(Calendar.DAY_OF_MONTH));
    }

    private void refresh() {
        list.removeAllViews();
        boolean on = Attendance.on(this);
        markBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            intro.setText("Attendance is off. Everyone counts as present until you mark a miss, so start it on the day your semester begins counting. The minimum is 75% for theory and for labs.");
            startBtn.setText("Start tracking from a date");
            return;
        }
        intro.setText("Counting from " + Prefs.attendFrom(this) + ". You are present unless you mark a miss. Cancelled classes and holidays do not count.");
        startBtn.setText("Start a new semester");
        progress.setVisibility(View.VISIBLE);
        Context app = getApplicationContext();
        Bg.run(() -> {
            List<Attendance.Row> rows = Attendance.compute(app);
            Bg.main(() -> {
                if (isFinishing()) return;
                progress.setVisibility(View.GONE);
                show(rows);
            });
        });
    }

    private void show(List<Attendance.Row> rows) {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        if (rows.isEmpty()) {
            intro.setText(intro.getText() + "\n\nNo classes counted yet.");
            return;
        }
        for (Attendance.Row r : rows) {
            View v = inf.inflate(R.layout.item_attend, list, false);
            String kind = r.subject.kind.equals("lab") ? "  ·  lab" : "";
            ((TextView) v.findViewById(R.id.title)).setText(r.subject.name + kind);
            ProgressBar bar = v.findViewById(R.id.bar);
            bar.setProgress((int) Math.round(r.pct));
            TextView sub = v.findViewById(R.id.sub);
            sub.setText(Attendance.line(r));
            if (r.low) sub.setTextColor(getColor(R.color.block_meal));
            list.addView(v);
        }
    }

    private void pickDay() {
        MaterialDatePicker<Long> dp = MaterialDatePicker.Builder.datePicker().setTitleText("Which day?").build();
        dp.addOnPositiveButtonClickListener(sel -> Attendance.markDialog(this, utcKey(sel), this::refresh));
        dp.show(getSupportFragmentManager(), "mark");
    }

    private void pickStart() {
        MaterialDatePicker<Long> dp = MaterialDatePicker.Builder.datePicker().setTitleText("Count from").build();
        dp.addOnPositiveButtonClickListener(sel -> {
            String key = utcKey(sel);
            if (!Attendance.on(this)) {
                begin(key);
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Start a new semester?")
                    .setMessage("This clears every absence you marked and counts again from " + key + ".")
                    .setPositiveButton("Start", (d, w) -> begin(key))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        dp.show(getSupportFragmentManager(), "start");
    }

    private void begin(String key) {
        Db.get(this).clearAbsences();
        Prefs.setAttendFrom(this, key);
        refresh();
    }
}
