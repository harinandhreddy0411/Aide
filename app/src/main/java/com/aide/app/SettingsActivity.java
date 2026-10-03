package com.aide.app;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private TextInputEditText key;
    private TextInputEditText model;
    private TextInputEditText fallback;
    private MaterialAutoCompleteTextView calendar;
    private List<CalendarHelper.Cal> cals = new ArrayList<>();
    private int selected = -1;

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
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}
