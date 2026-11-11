package com.aide.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.ChipGroup;

public class WeekActivity extends AppCompatActivity {
    private LinearLayout list;
    private ChipGroup group;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_week);
        Ui.insets(findViewById(R.id.root));
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);
        group = findViewById(R.id.day_group);
        int dow = getIntent().getIntExtra("dow", 2);
        group.check(dow == 3 ? R.id.d3 : dow == 4 ? R.id.d4 : dow == 5 ? R.id.d5 : dow == 6 ? R.id.d6 : R.id.d2);
        group.setOnCheckedStateChangeListener((g, ids) -> refresh());
        findViewById(R.id.btn_add).setOnClickListener(v ->
                startActivity(new Intent(this, SlotEditActivity.class).putExtra("dow", dow())));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dow() {
        View v = findViewById(group.getCheckedChipId());
        return v == null ? 2 : Integer.parseInt(String.valueOf(v.getTag()));
    }

    static String slotTitle(Db db, Db.Slot s) {
        if (s.type.equals("lunch")) return "Lunch Break";
        if (s.type.equals("advisory")) return "Advisory Hour";
        String name = Schedule.subjectName(db, s.subjectId);
        return s.label.isEmpty() ? name : name + " · " + s.label;
    }

    private void refresh() {
        list.removeAllViews();
        Db db = Db.get(this);
        for (Db.Slot s : db.slotsFor(dow())) {
            StringBuilder sub = new StringBuilder(Ui.clock(s.startMin)).append(" to ").append(Ui.clock(s.endMin));
            sub.append(", ").append(Schedule.cap(s.type));
            if (!s.room.isEmpty()) sub.append(", ").append(s.room);
            if (s.optional) sub.append(", optional");
            list.addView(Ui.line(list, slotTitle(db, s), sub.toString(), v ->
                    startActivity(new Intent(this, SlotEditActivity.class).putExtra("id", s.id))));
        }
    }
}
