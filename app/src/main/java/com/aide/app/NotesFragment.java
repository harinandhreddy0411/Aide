package com.aide.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class NotesFragment extends Fragment {
    private LinearLayout list;
    private View empty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_notes, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        super.onViewCreated(v, b);
        list = v.findViewById(R.id.list);
        empty = v.findViewById(R.id.empty);
        v.findViewById(R.id.fab).setOnClickListener(x -> edit(null));
        refresh();
    }

    private void refresh() {
        Context c = requireContext();
        List<Db.Note> notes = Db.get(c).notes();
        list.removeAllViews();
        empty.setVisibility(notes.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(c);
        for (Db.Note n : notes) {
            TextView row = (TextView) inf.inflate(R.layout.item_note, list, false);
            row.setText(n.body);
            row.setOnClickListener(x -> edit(n));
            list.addView(row);
        }
    }

    private void edit(@Nullable Db.Note n) {
        Context c = requireContext();
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        TextInputEditText in = view.findViewById(R.id.input);
        if (n != null) in.setText(n.body);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(c)
                .setTitle(n == null ? "New note" : "Edit note")
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    String s = in.getText() == null ? "" : in.getText().toString().trim();
                    if (!s.isEmpty()) {
                        Db.Note x = n == null ? new Db.Note() : n;
                        x.body = s;
                        Db.get(c).saveNote(x);
                    }
                    refresh();
                })
                .setNegativeButton("Cancel", null);
        if (n != null) {
            builder.setNeutralButton("Delete", (d, w) -> {
                Db.get(c).deleteNote(n.id);
                refresh();
            });
        }
        builder.show();
    }
}
