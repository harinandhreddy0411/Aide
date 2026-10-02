package com.aide.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class EditItemActivity extends AppCompatActivity {
    private long id;
    private long eventId = -1;
    private final Calendar when = Calendar.getInstance();
    private TextInputLayout titleLayout;
    private TextInputEditText title;
    private ChipGroup kindGroup;
    private ChipGroup offsetGroup;
    private Button dateBtn;
    private Button timeBtn;

    private final ActivityResultLauncher<String[]> perms =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (CalendarHelper.has(this)) {
                    save();
                } else {
                    Toast.makeText(this, "Calendar access is needed to set reminders.", Toast.LENGTH_LONG).show();
                }
            });

    static Intent intentFor(Context c, Db.Item it) {
        Intent i = new Intent(c, EditItemActivity.class);
        i.putExtra("id", it.id);
        i.putExtra("event_id", it.eventId);
        i.putExtra("title", it.title);
        i.putExtra("kind", it.kind);
        i.putExtra("start", it.start);
        i.putExtra("offsets", it.offsets);
        return i;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_edit);
        Ui.insets(findViewById(R.id.root));

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        titleLayout = findViewById(R.id.title_layout);
        title = findViewById(R.id.title);
        kindGroup = findViewById(R.id.kind_group);
        offsetGroup = findViewById(R.id.offset_group);
        dateBtn = findViewById(R.id.btn_date);
        timeBtn = findViewById(R.id.btn_time);

        Intent in = getIntent();
        id = in.getLongExtra("id", 0);
        eventId = in.getLongExtra("event_id", -1);
        long start = in.getLongExtra("start", 0);
        if (start == 0) {
            when.add(Calendar.DAY_OF_MONTH, 1);
            when.set(Calendar.HOUR_OF_DAY, 9);
            when.set(Calendar.MINUTE, 0);
        } else {
            when.setTimeInMillis(start);
        }
        when.set(Calendar.SECOND, 0);
        when.set(Calendar.MILLISECOND, 0);

        String t = in.getStringExtra("title");
        if (t != null) title.setText(t);

        String kind = in.getStringExtra("kind");
        int chip = R.id.chip_task;
        if ("viva".equals(kind)) chip = R.id.chip_viva;
        else if ("subscription".equals(kind)) chip = R.id.chip_sub;
        else if ("other".equals(kind)) chip = R.id.chip_other;
        kindGroup.check(chip);

        String offs = in.getStringExtra("offsets");
        int[] chosen = Db.Item.parse(offs == null ? "1440" : offs);
        for (int i = 0; i < offsetGroup.getChildCount(); i++) {
            View v = offsetGroup.getChildAt(i);
            if (v instanceof Chip) {
                int m = Integer.parseInt(String.valueOf(v.getTag()));
                boolean on = false;
                for (int c : chosen) if (c == m) on = true;
                ((Chip) v).setChecked(on);
            }
        }

        dateBtn.setOnClickListener(v -> pickDate());
        timeBtn.setOnClickListener(v -> pickTime());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        if (id > 0) {
            tb.inflateMenu(R.menu.edit);
            tb.setOnMenuItemClickListener(mi -> {
                if (mi.getItemId() == R.id.action_delete) {
                    confirmDelete();
                    return true;
                }
                return false;
            });
        }
        render();
    }

    private void render() {
        dateBtn.setText(new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(when.getTime()));
        timeBtn.setText(new SimpleDateFormat("h:mm a", Locale.getDefault()).format(when.getTime()));
    }

    private void pickDate() {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(when.get(Calendar.YEAR), when.get(Calendar.MONTH), when.get(Calendar.DAY_OF_MONTH));
        MaterialDatePicker<Long> p = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Date")
                .setSelection(utc.getTimeInMillis())
                .build();
        p.addOnPositiveButtonClickListener(sel -> {
            Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            u.setTimeInMillis(sel);
            when.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH));
            render();
        });
        p.show(getSupportFragmentManager(), "date");
    }

    private void pickTime() {
        MaterialTimePicker tp = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(when.get(Calendar.HOUR_OF_DAY))
                .setMinute(when.get(Calendar.MINUTE))
                .setTitleText("Time")
                .build();
        tp.addOnPositiveButtonClickListener(v -> {
            when.set(Calendar.HOUR_OF_DAY, tp.getHour());
            when.set(Calendar.MINUTE, tp.getMinute());
            render();
        });
        tp.show(getSupportFragmentManager(), "time");
    }

    private String kindKey() {
        int c = kindGroup.getCheckedChipId();
        if (c == R.id.chip_viva) return "viva";
        if (c == R.id.chip_sub) return "subscription";
        if (c == R.id.chip_other) return "other";
        return "task";
    }

    private String offsetsCsv() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < offsetGroup.getChildCount(); i++) {
            View v = offsetGroup.getChildAt(i);
            if (v instanceof Chip && ((Chip) v).isChecked()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(String.valueOf(v.getTag()));
            }
        }
        return sb.toString();
    }

    private void save() {
        String t = title.getText() == null ? "" : title.getText().toString().trim();
        if (t.isEmpty()) {
            titleLayout.setError("Give it a title");
            return;
        }
        titleLayout.setError(null);
        if (!CalendarHelper.has(this)) {
            perms.launch(CalendarHelper.PERMS);
            return;
        }
        Db.Item it = new Db.Item();
        it.id = id;
        it.title = t;
        it.kind = kindKey();
        it.start = when.getTimeInMillis();
        it.offsets = offsetsCsv();
        it.eventId = eventId;
        String err = Items.save(this, it);
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            return;
        }
        finish();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this item?")
                .setMessage("It is also removed from your calendar.")
                .setPositiveButton("Delete", (d, w) -> {
                    Db.Item it = new Db.Item();
                    it.id = id;
                    it.eventId = eventId;
                    Items.remove(this, it);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
