package com.aide.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.speech.RecognizerIntent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    private View nextDone;
    private View progress;
    private LinearLayout list;
    private LinearLayout practiceList;
    private TextView practiceEmpty;
    private View planCard;
    private TextView planBody;
    private Button planBtn;
    private TextInputLayout quickLayout;
    private TextInputEditText quick;

    private final ActivityResultLauncher<Intent> speech = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                Intent data = r.getData();
                if (r.getResultCode() != Activity.RESULT_OK || data == null) return;
                ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (heard == null || heard.isEmpty() || !isAdded() || getView() == null) return;
                quick.setText(heard.get(0));
                submit();
            });

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
        nextDone = v.findViewById(R.id.next_done);
        progress = v.findViewById(R.id.progress);
        list = v.findViewById(R.id.list);
        practiceList = v.findViewById(R.id.practice_list);
        practiceEmpty = v.findViewById(R.id.practice_empty);
        planCard = v.findViewById(R.id.plan_card);
        planBody = v.findViewById(R.id.plan_body);
        planBtn = v.findViewById(R.id.btn_plan);
        quickLayout = v.findViewById(R.id.quick_layout);
        quick = v.findViewById(R.id.quick);

        v.findViewById(R.id.fab).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), EditItemActivity.class)));
        v.findViewById(R.id.practice_add).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), EditRoutineActivity.class)));
        planBtn.setOnClickListener(x -> planDialog());
        v.findViewById(R.id.plan_replan).setOnClickListener(x -> planDialog());
        v.findViewById(R.id.plan_dismiss).setOnClickListener(x -> {
            Prefs.setPlan(requireContext(), "", "");
            refresh();
        });
        quickLayout.setEndIconOnClickListener(x -> submit());
        quickLayout.setStartIconOnClickListener(x -> startVoice());
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
        consumeIntent();
    }

    void consumeIntent() {
        if (!isAdded()) return;
        Intent in = requireActivity().getIntent();
        if (in == null) return;
        if (in.hasExtra("voice")) {
            in.removeExtra("voice");
            startVoice();
        } else if (in.hasExtra("plan")) {
            in.removeExtra("plan");
            planDialog();
        }
    }

    void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a task or schedule");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            speech.launch(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "No speech service found on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private View row(LayoutInflater inf, ViewGroup parent, Context c, Db.Item it) {
        View row = inf.inflate(R.layout.item_row, parent, false);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(it.start);
        ((TextView) row.findViewById(R.id.day)).setText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
        ((TextView) row.findViewById(R.id.month)).setText(
                new SimpleDateFormat("MMM", Locale.getDefault()).format(new Date(it.start)));
        ((TextView) row.findViewById(R.id.title)).setText(it.title);
        ((TextView) row.findViewById(R.id.sub)).setText(Ui.subline(it));
        ((CheckBox) row.findViewById(R.id.check)).setOnClickListener(x -> complete(it));
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
        db.purgeDone();
        LayoutInflater inf = LayoutInflater.from(c);
        refreshPractice(c, inf, db);
        refreshPlan(c);

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
        nextDone.setOnClickListener(x -> complete(first));

        laterLabel.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
        for (int i = 1; i < items.size(); i++) list.addView(row(inf, list, c, items.get(i)));
    }

    private void refreshPractice(Context c, LayoutInflater inf, Db db) {
        practiceList.removeAllViews();
        List<Db.Routine> all = db.routines();
        practiceEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        Calendar today = Calendar.getInstance();
        String key = Agenda.dayKey(today.getTimeInMillis());
        for (Db.Routine r : all) {
            if (r.runsOn(today)) practiceList.addView(routineRow(inf, c, db, r, true, key));
        }
        for (Db.Routine r : all) {
            if (!r.runsOn(today)) practiceList.addView(routineRow(inf, c, db, r, false, key));
        }
    }

    private View routineRow(LayoutInflater inf, Context c, Db db, Db.Routine r, boolean today, String key) {
        View row = inf.inflate(R.layout.item_row, practiceList, false);
        int status = today ? db.status(r.id, key) : 0;
        String clock = Ui.clock(r.startMin);
        int space = clock.lastIndexOf(' ');
        TextView time = row.findViewById(R.id.day);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        time.setText(clock.substring(0, space));
        ((TextView) row.findViewById(R.id.month)).setText(clock.substring(space + 1));
        ((TextView) row.findViewById(R.id.title)).setText(r.title);

        StringBuilder sub = new StringBuilder();
        if (!today) {
            sub.append("Not today, ").append(Ui.daysLabel(r.days));
        } else if (status == 1) {
            sub.append("Done today");
        } else if (status == 2) {
            sub.append("Skipped today");
        } else {
            sub.append(Ui.clock(r.startMin)).append(" to ").append(Ui.clock(r.endMin));
        }
        int streak = Agenda.streak(db, r);
        if (streak > 0) sub.append(", ").append(streak).append(" day streak");
        ((TextView) row.findViewById(R.id.sub)).setText(sub);

        CheckBox check = row.findViewById(R.id.check);
        if (!today) {
            check.setVisibility(View.INVISIBLE);
        } else {
            check.setChecked(status == 1);
            check.setOnClickListener(x -> {
                db.setStatus(r.id, key, check.isChecked() ? 1 : 0);
                Notify.cancel(c, Agenda.notifId(r.id));
                refresh();
            });
        }
        row.setOnClickListener(x -> startActivity(EditRoutineActivity.intentFor(c, r)));
        return row;
    }

    private void refreshPlan(Context c) {
        String text = Prefs.planText(c);
        boolean show = !text.isEmpty() && Agenda.dayKey(System.currentTimeMillis()).equals(Prefs.planDay(c));
        planCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) planBody.setText(text);
    }

    private void planDialog() {
        Context c = requireContext();
        if (Db.get(c).jobCount("plan") > 0) {
            Toast.makeText(c, "Your plan is already waiting for the AI limit. I will notify you.", Toast.LENGTH_LONG).show();
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        ((TextInputLayout) view.findViewById(R.id.input_layout)).setHint("Anything to fit in today? (optional)");
        TextInputEditText in = view.findViewById(R.id.input);
        new MaterialAlertDialogBuilder(c)
                .setTitle("Plan my day")
                .setView(view)
                .setPositiveButton("Plan", (d, w) -> runPlan(in.getText() == null ? "" : in.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runPlan(String extra) {
        setBusy(true);
        Context app = requireContext().getApplicationContext();
        Bg.run(() -> {
            Gemini.Result r = Assistant.plan(app, extra);
            boolean queued = false;
            if (r.ok()) {
                Prefs.setPlan(app, Agenda.dayKey(System.currentTimeMillis()), r.text);
            } else if (r.retryable()) {
                Db.get(app).addJob("plan", extra);
                Queue.kick(app, Math.max(60000L, r.retryMs));
                queued = true;
            }
            boolean wait = queued;
            Bg.main(() -> {
                if (!isAdded()) return;
                setBusy(false);
                if (r.ok()) {
                    refresh();
                    return;
                }
                Toast.makeText(app, wait ? "Rate limited. I will keep trying and notify you when the plan is ready." : r.error,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void complete(Db.Item it) {
        Context c = requireContext();
        Items.remove(c, it);
        refresh();
        Snackbar.make(requireView(), "Done: " + it.title, 6000)
                .setAction("Undo", x -> {
                    Db.Item back = new Db.Item();
                    back.title = it.title;
                    back.kind = it.kind;
                    back.start = it.start;
                    back.offsets = it.offsets;
                    String err = Items.save(c, back);
                    if (err != null) Toast.makeText(c, err, Toast.LENGTH_LONG).show();
                    refresh();
                })
                .show();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        quickLayout.setEnabled(!busy);
        planBtn.setEnabled(!busy);
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
