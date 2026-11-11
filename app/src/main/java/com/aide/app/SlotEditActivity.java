package com.aide.app;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class SlotEditActivity extends AppCompatActivity {
    private static final String[] TYPES = {"class", "lab", "tutorial", "lunch", "advisory"};
    private static final String[] TYPE_LABELS = {"Class", "Lab", "Tutorial", "Lunch break", "Advisory hour"};

    private Db.Slot slot;
    private int dayIndex;
    private int typeIndex;
    private int subjectIndex;
    private int start = 540;
    private int end = 590;
    private List<Db.Subject> subjects;
    private MaterialAutoCompleteTextView fDay;
    private MaterialAutoCompleteTextView fType;
    private MaterialAutoCompleteTextView fSubject;
    private TextInputEditText fLabel;
    private TextInputEditText fRoom;
    private TextInputEditText fUnits;
    private MaterialSwitch fOptional;
    private Button fStart;
    private Button fEnd;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_slot);
        Ui.insets(findViewById(R.id.root));
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());

        Db db = Db.get(this);
        subjects = db.subjects();
        fDay = findViewById(R.id.f_day);
        fType = findViewById(R.id.f_type);
        fSubject = findViewById(R.id.f_subject);
        fLabel = findViewById(R.id.f_label);
        fRoom = findViewById(R.id.f_room);
        fUnits = findViewById(R.id.f_units);
        fOptional = findViewById(R.id.f_optional);
        fStart = findViewById(R.id.f_start);
        fEnd = findViewById(R.id.f_end);

        long id = getIntent().getLongExtra("id", 0);
        slot = id > 0 ? db.slot(id) : null;
        int dow = getIntent().getIntExtra("dow", 2);
        subjectIndex = -1;
        if (slot != null) {
            dow = slot.dow;
            start = slot.startMin;
            end = slot.endMin;
            for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(slot.type)) typeIndex = i;
            for (int i = 0; i < subjects.size(); i++) if (subjects.get(i).id == slot.subjectId) subjectIndex = i;
            fLabel.setText(slot.label);
            fRoom.setText(slot.room);
            fUnits.setText(String.valueOf(slot.units));
            fOptional.setChecked(slot.optional);
            findViewById(R.id.btn_delete).setVisibility(android.view.View.VISIBLE);
        } else {
            fUnits.setText("1");
        }
        dayIndex = Math.max(0, Math.min(4, dow - 2));

        String[] days = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
        fDay.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, days));
        fDay.setText(days[dayIndex], false);
        fDay.setOnItemClickListener((p, v, pos, r) -> dayIndex = pos);

        fType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, TYPE_LABELS));
        fType.setText(TYPE_LABELS[typeIndex], false);
        fType.setOnItemClickListener((p, v, pos, r) -> {
            typeIndex = pos;
            subjectState();
        });

        List<String> names = new ArrayList<>();
        for (Db.Subject s : subjects) names.add(s.name);
        fSubject.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        if (subjectIndex >= 0) fSubject.setText(names.get(subjectIndex), false);
        fSubject.setOnItemClickListener((p, v, pos, r) -> subjectIndex = pos);
        subjectState();

        times();
        fStart.setOnClickListener(v -> TimePick.one(getSupportFragmentManager(), "Start time", start, m -> {
            int len = Math.max(30, end - start);
            start = m;
            end = Math.min(1439, m + len);
            times();
        }));
        fEnd.setOnClickListener(v -> TimePick.one(getSupportFragmentManager(), "End time", end, m -> {
            end = m;
            times();
        }));
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_delete).setOnClickListener(v -> {
            if (slot != null) db.deleteSlot(slot.id);
            Schedule.changed(this);
            finish();
        });
    }

    private void subjectState() {
        boolean needs = TYPES[typeIndex].equals("class") || TYPES[typeIndex].equals("lab") || TYPES[typeIndex].equals("tutorial");
        findViewById(R.id.f_subject_layout).setVisibility(needs ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void times() {
        fStart.setText("Starts " + Ui.clock(start));
        fEnd.setText("Ends " + Ui.clock(end));
    }

    private void save() {
        String type = TYPES[typeIndex];
        boolean needs = type.equals("class") || type.equals("lab") || type.equals("tutorial");
        if (end <= start) {
            Toast.makeText(this, "The end must be after the start.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (needs && subjectIndex < 0) {
            Toast.makeText(this, "Pick a subject.", Toast.LENGTH_SHORT).show();
            return;
        }
        int units;
        try {
            units = Integer.parseInt(fUnits.getText() == null ? "" : fUnits.getText().toString().trim());
        } catch (NumberFormatException e) {
            units = 1;
        }
        Db.Slot s = slot == null ? new Db.Slot() : slot;
        s.dow = dayIndex + 2;
        s.startMin = start;
        s.endMin = end;
        s.type = type;
        s.subjectId = needs ? subjects.get(subjectIndex).id : 0;
        s.label = fLabel.getText() == null ? "" : fLabel.getText().toString().trim();
        s.room = fRoom.getText() == null ? "" : fRoom.getText().toString().trim();
        s.optional = fOptional.isChecked();
        s.units = Math.max(1, Math.min(12, units));
        Db.get(this).saveSlot(s);
        Schedule.changed(this);
        finish();
    }
}
