package com.aide.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private TextInputEditText key;
    private TextInputEditText model;
    private TextInputEditText fallback;
    private MaterialAutoCompleteTextView calendar;
    private List<CalendarHelper.Cal> cals = new ArrayList<>();
    private int selected = -1;
    private int briefMin;
    private MaterialSwitch briefSwitch;
    private MaterialSwitch screenSwitch;
    private ChipGroup screenGroup;
    private Button briefTime;
    private Button usageBtn;

    private final ActivityResultLauncher<String[]> perms =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> loadCalendars());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);
        Ui.insets(findViewById(R.id.root));

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        key = findViewById(R.id.key);
        model = findViewById(R.id.model);
        fallback = findViewById(R.id.fallback);
        calendar = findViewById(R.id.calendar);

        briefSwitch = findViewById(R.id.sw_brief);
        screenSwitch = findViewById(R.id.sw_screen);
        screenGroup = findViewById(R.id.screen_group);
        briefTime = findViewById(R.id.btn_brief_time);
        usageBtn = findViewById(R.id.btn_usage);

        briefSwitch.setChecked(Prefs.briefOn(this));
        briefMin = Prefs.briefMin(this);
        briefTime.setOnClickListener(v -> pickBrief());
        screenSwitch.setChecked(Prefs.screenOn(this));
        screenSwitch.setOnCheckedChangeListener((v, on) -> usageState());
        int threshold = Prefs.screenMin(this);
        boolean matched = false;
        for (int i = 0; i < screenGroup.getChildCount(); i++) {
            View v = screenGroup.getChildAt(i);
            if (v instanceof Chip && Integer.parseInt(String.valueOf(v.getTag())) == threshold) {
                ((Chip) v).setChecked(true);
                matched = true;
            }
        }
        if (!matched && screenGroup.getChildCount() > 1) ((Chip) screenGroup.getChildAt(1)).setChecked(true);
        usageBtn.setOnClickListener(v -> openUsageAccess());
        renderBrief();

        key.setText(Prefs.apiKey(this));
        model.setText(Prefs.model(this));
        fallback.setText(Prefs.fallbackModel(this));
        calendar.setOnItemClickListener((p, v, pos, rowId) -> selected = pos);
        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        if (CalendarHelper.has(this)) {
            loadCalendars();
        } else {
            perms.launch(CalendarHelper.PERMS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        usageState();
    }

    private void renderBrief() {
        briefTime.setText("Brief at " + Ui.clock(briefMin));
    }

    private void pickBrief() {
        MaterialTimePicker tp = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(briefMin / 60)
                .setMinute(briefMin % 60)
                .setTitleText("Morning brief time")
                .build();
        tp.addOnPositiveButtonClickListener(v -> {
            briefMin = tp.getHour() * 60 + tp.getMinute();
            renderBrief();
        });
        tp.show(getSupportFragmentManager(), "brief");
    }

    private void usageState() {
        usageBtn.setVisibility(screenSwitch.isChecked() && !Usage.has(this) ? View.VISIBLE : View.GONE);
    }

    private void openUsageAccess() {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private int screenMinutes() {
        for (int i = 0; i < screenGroup.getChildCount(); i++) {
            View v = screenGroup.getChildAt(i);
            if (v instanceof Chip && ((Chip) v).isChecked()) return Integer.parseInt(String.valueOf(v.getTag()));
        }
        return 45;
    }

    private void loadCalendars() {
        cals = CalendarHelper.calendars(this);
        List<String> labels = new ArrayList<>();
        for (CalendarHelper.Cal k : cals) labels.add(k.label);
        calendar.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
        long current = CalendarHelper.resolveCalendarId(this);
        selected = -1;
        for (int i = 0; i < cals.size(); i++) {
            if (cals.get(i).id == current) {
                selected = i;
                calendar.setText(labels.get(i), false);
            }
        }
    }

    private void save() {
        String k = key.getText() == null ? "" : key.getText().toString().trim();
        String m = model.getText() == null ? "" : model.getText().toString().trim();
        Prefs.setApiKey(this, k);
        if (!k.isEmpty() && Prefs.apiKey(this).isEmpty()) {
            Toast.makeText(this, "Could not store the key securely on this device.", Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setModel(this, m);
        Prefs.setFallbackModel(this, fallback.getText() == null ? "" : fallback.getText().toString().trim());
        if (selected >= 0 && selected < cals.size()) Prefs.setCalendarId(this, cals.get(selected).id);
        Prefs.setBrief(this, briefSwitch.isChecked(), briefMin);
        Prefs.setScreen(this, screenSwitch.isChecked(), screenMinutes());
        Usage.apply(this);
        android.content.Context app = getApplicationContext();
        Bg.run(() -> Agenda.rearm(app));
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}
