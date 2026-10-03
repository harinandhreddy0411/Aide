package com.aide.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
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
    private TextView doneLabel;
    private View nextCard;
    private View nextDone;
    private View progress;
    private LinearLayout list;
    private LinearLayout doneList;
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
        doneLabel = v.findViewById(R.id.done_label);
        nextCard = v.findViewById(R.id.next_card);
        nextDone = v.findViewById(R.id.next_done);
        progress = v.findViewById(R.id.progress);
        list = v.findViewById(R.id.list);
        doneList = v.findViewById(R.id.done_list);
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

    private View row(LayoutInflater inf, ViewGroup parent, Context c, Db.Item it, boolean done) {
        View row = inf.inflate(R.layout.item_row, parent, false);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(it.start);
        ((TextView) row.findViewById(R.id.day)).setText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
        ((TextView) row.findViewById(R.id.month)).setText(
                new SimpleDateFormat("MMM", Locale.getDefault()).format(new Date(it.start)));
        TextView title = row.findViewById(R.id.title);
        title.setText(it.title);
        int flags = title.getPaintFlags();
        title.setPaintFlags(done ? flags | Paint.STRIKE_THRU_TEXT_FLAG : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
        ((TextView) row.findViewById(R.id.sub)).setText(Ui.subline(it));
        CheckBox check = row.findViewById(R.id.check);
        check.setChecked(done);
        check.setOnClickListener(x -> {
            String err = Items.setDone(c, it, !done);
            if (err != null) Toast.makeText(c, err, Toast.LENGTH_LONG).show();
            refresh();
        });
        row.setOnClickListener(x -> startActivity(EditItemActivity.intentFor(c, it)));
        return row;
    }

    void refresh() {
        if (!isAdded() || getView() == null) return;
        Context c = requireContext();
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.getTime()));

        Db db = Db.get(c);
        LayoutInflater inf = LayoutInflater.from(c);
        List<Db.Item> hist = db.history();
        doneList.removeAllViews();
        doneLabel.setVisibility(hist.isEmpty() ? View.GONE : View.VISIBLE);
        for (Db.Item it : hist) doneList.addView(row(inf, doneList, c, it, true));

        List<Db.Item> items = db.open();
        list.removeAllViews();
        if (items.isEmpty()) {
            nextRel.setText("All clear");
            nextTitle.setText("Nothing scheduled");
            nextWhen.setText("Add a viva or a task below.");
            nextCard.setOnClickListener(null);
            nextCard.setClickable(false);
            nextDone.setVisibility(View.GONE);
            laterLabel.setVisibility(View.GONE);
            return;
        }

        Db.Item first = items.get(0);
        nextRel.setText(Ui.relative(first.start));
        nextTitle.setText(first.title);
        nextWhen.setText(Ui.timeText(first.start));
        nextCard.setOnClickListener(x -> startActivity(EditItemActivity.intentFor(c, first)));
        nextDone.setVisibility(View.VISIBLE);
        nextDone.setOnClickListener(x -> {
            Items.setDone(c, first, true);
            refresh();
        });

        laterLabel.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
        for (int i = 1; i < items.size(); i++) list.addView(row(inf, list, c, items.get(i), false));
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
            boolean queued = false;
            if (p.item == null && p.retry) {
                Db.get(app).addJob("quick", text);
                Queue.kick(app, Math.max(60000L, p.retryMs));
                queued = true;
            }
            boolean wait = queued;
            Bg.main(() -> {
                if (!isAdded()) return;
                setBusy(false);
                if (p.item == null) {
                    if (wait) quick.setText("");
                    Toast.makeText(app, wait ? "Rate limited. I will keep trying and notify you when it is ready." : p.error,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                quick.setText("");
                startActivity(EditItemActivity.intentFor(requireContext(), p.item));
            });
        });
    }
}
