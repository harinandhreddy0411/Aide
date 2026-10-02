package com.aide.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment {
    private TextView greeting;
    private TextView date;
    private TextView nextRel;
    private TextView nextTitle;
    private TextView nextWhen;
    private TextView laterLabel;
    private View nextCard;
    private View progress;
    private LinearLayout list;
    private TextInputLayout quickLayout;
    private TextInputEditText quick;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_home, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        super.onViewCreated(v, b);
        greeting = v.findViewById(R.id.greeting);
        date = v.findViewById(R.id.date);
        nextRel = v.findViewById(R.id.next_rel);
        nextTitle = v.findViewById(R.id.next_title);
        nextWhen = v.findViewById(R.id.next_when);
        laterLabel = v.findViewById(R.id.later_label);
        nextCard = v.findViewById(R.id.next_card);
        progress = v.findViewById(R.id.progress);
        list = v.findViewById(R.id.list);
        quickLayout = v.findViewById(R.id.quick_layout);
        quick = v.findViewById(R.id.quick);

        v.findViewById(R.id.fab).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), EditItemActivity.class)));
        quickLayout.setEndIconOnClickListener(x -> submit());
        quick.setOnEditorActionListener((tv, action, e) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    void refresh() {
        if (!isAdded() || getView() == null) return;
        Context c = requireContext();
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.getTime()));

        List<Db.Item> items = Db.get(c).upcoming(System.currentTimeMillis() - 3600000L);
        list.removeAllViews();
        if (items.isEmpty()) {
            nextRel.setText("All clear");
            nextTitle.setText("Nothing scheduled");
            nextWhen.setText("Add a viva, task or subscription below.");
            nextCard.setOnClickListener(null);
            nextCard.setClickable(false);
            laterLabel.setVisibility(View.GONE);
            return;
        }

        Db.Item first = items.get(0);
        nextRel.setText(Ui.relative(first.start));
        nextTitle.setText(first.title);
        nextWhen.setText(Ui.timeText(first.start));
        nextCard.setOnClickListener(x -> startActivity(EditItemActivity.intentFor(c, first)));

        laterLabel.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(c);
        for (int i = 1; i < items.size(); i++) {
            Db.Item it = items.get(i);
            View row = inf.inflate(R.layout.item_row, list, false);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(it.start);
            ((TextView) row.findViewById(R.id.day)).setText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
            ((TextView) row.findViewById(R.id.month)).setText(
                    new SimpleDateFormat("MMM", Locale.getDefault()).format(new Date(it.start)));
            ((TextView) row.findViewById(R.id.title)).setText(it.title);
            ((TextView) row.findViewById(R.id.sub)).setText(Ui.subline(it));
            row.setOnClickListener(x -> startActivity(EditItemActivity.intentFor(c, it)));
            list.addView(row);
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        quickLayout.setEnabled(!busy);
    }

    private void submit() {
        String text = quick.getText() == null ? "" : quick.getText().toString().trim();
        if (text.isEmpty()) return;
        setBusy(true);
        Context app = requireContext().getApplicationContext();
        Bg.run(() -> {
            Assistant.Parsed p = Assistant.quick(app, text);
            Bg.main(() -> {
                if (!isAdded()) return;
                setBusy(false);
                if (p.item == null) {
                    Toast.makeText(app, p.error, Toast.LENGTH_LONG).show();
                    return;
                }
                quick.setText("");
                startActivity(EditItemActivity.intentFor(requireContext(), p.item));
            });
        });
    }
}
