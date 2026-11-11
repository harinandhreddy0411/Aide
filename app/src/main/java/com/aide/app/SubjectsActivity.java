package com.aide.app;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

public class SubjectsActivity extends AppCompatActivity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_subjects);
        Ui.insets(findViewById(R.id.root));
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);
        findViewById(R.id.btn_add).setOnClickListener(v -> edit(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        for (Db.Subject s : Db.get(this).subjects()) {
            String sub = s.code + ", " + (s.kind.equals("lab") ? "lab" : "theory") + ", minimum " + s.minPct + "%";
            list.addView(Ui.line(list, s.name, sub, v -> edit(s)));
        }
    }

    private void edit(Db.Subject existing) {
        View view = getLayoutInflater().inflate(R.layout.dialog_subject, null);
        TextInputEditText name = view.findViewById(R.id.s_name);
        TextInputEditText code = view.findViewById(R.id.s_code);
        TextInputEditText pct = view.findViewById(R.id.s_pct);
        ChipGroup kind = view.findViewById(R.id.s_kind);
        if (existing != null) {
            name.setText(existing.name);
            code.setText(existing.code);
            pct.setText(String.valueOf(existing.minPct));
            kind.check(existing.kind.equals("lab") ? R.id.s_lab : R.id.s_theory);
        } else {
            pct.setText("75");
            kind.check(R.id.s_theory);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "New subject" : "Edit subject")
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText() == null ? "" : name.getText().toString().trim();
                    String c = code.getText() == null ? "" : code.getText().toString().trim();
                    int p;
                    try {
                        p = Integer.parseInt(pct.getText() == null ? "" : pct.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        p = 75;
                    }
                    if (n.isEmpty()) {
                        Toast.makeText(this, "A subject needs a name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Db.Subject s = existing == null ? new Db.Subject() : existing;
                    s.name = n;
                    s.code = c.isEmpty() ? n : c;
                    s.minPct = Math.max(1, Math.min(100, p));
                    s.kind = kind.getCheckedChipId() == R.id.s_lab ? "lab" : "theory";
                    Db.get(this).saveSubject(s);
                    Schedule.changed(this);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
