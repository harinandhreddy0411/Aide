package com.aide.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public class TimetableFragment extends Fragment {

    private interface Pick {
        void got(Db.Subject s);
    }

    private Calendar day = today();
    private TextView dayTitle;
    private TextView banner;
    private TextView empty;
    private LinearLayout blocks;
    private View progress;
    private Button confirmBtn;
    private TextInputLayout cmdLayout;
    private TextInputEditText cmd;

    private final ActivityResultLauncher<Intent> speech = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                Intent data = r.getData();
                if (r.getResultCode() != Activity.RESULT_OK || data == null) return;
                ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (heard == null || heard.isEmpty() || !isAdded() || getView() == null) return;
                cmd.setText(heard.get(0));
                submit();
            });

    private final ActivityResultLauncher<String> imagePick = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::importPhoto);

    private static Calendar today() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_timetable, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        super.onViewCreated(v, b);
        dayTitle = v.findViewById(R.id.day_title);
        banner = v.findViewById(R.id.banner);
        empty = v.findViewById(R.id.empty);
        blocks = v.findViewById(R.id.blocks);
        progress = v.findViewById(R.id.progress);
        confirmBtn = v.findViewById(R.id.confirm_btn);
        cmdLayout = v.findViewById(R.id.cmd_layout);
        cmd = v.findViewById(R.id.cmd);

        v.findViewById(R.id.day_prev).setOnClickListener(x -> shift(-1));
        v.findViewById(R.id.day_next).setOnClickListener(x -> shift(1));
        dayTitle.setOnClickListener(x -> pickDate());
        v.findViewById(R.id.day_more).setOnClickListener(x -> more());
        confirmBtn.setOnClickListener(x -> askNext(false));
        cmdLayout.setEndIconOnClickListener(x -> submit());
        cmdLayout.setStartIconOnClickListener(x -> startVoice());
        cmd.setOnEditorActionListener((tv, action, e) -> {
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
        String confirm = in.getStringExtra("confirm");
        if (confirm != null) {
            in.removeExtra("confirm");
            Calendar d = Schedule.parseDay(confirm);
            if (d != null) day = d;
            refresh();
            askNext(true);
        }
        if (in.hasExtra("review")) {
            in.removeExtra("review");
            showPending();
        }
    }

    private void shift(int by) {
        day.add(Calendar.DAY_OF_MONTH, by);
        refresh();
    }

    private String key() {
        return Agenda.dayKey(day.getTimeInMillis());
    }

    private static String utcKey(long utcMillis) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(utcMillis);
        return String.format(Locale.US, "%04d-%02d-%02d", u.get(Calendar.YEAR), u.get(Calendar.MONTH) + 1, u.get(Calendar.DAY_OF_MONTH));
    }

    private void pickDate() {
        MaterialDatePicker<Long> dp = MaterialDatePicker.Builder.datePicker().setTitleText("Show a day").build();
        dp.addOnPositiveButtonClickListener(sel -> {
            Calendar d = Schedule.parseDay(utcKey(sel));
            if (d != null) {
                day = d;
                refresh();
            }
        });
        dp.show(getParentFragmentManager(), "day");
    }

    void refresh() {
        if (!isAdded() || getView() == null) return;
        Context c = requireContext();
        Calendar t = today();
        boolean isToday = t.getTimeInMillis() == day.getTimeInMillis();
        dayTitle.setText(new SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(day.getTime()) + (isToday ? "  ·  Today" : ""));
        Schedule.DayInfo info = Schedule.resolve(c, day);
        banner.setVisibility(info.banner.isEmpty() ? View.GONE : View.VISIBLE);
        banner.setText(info.banner);
        blocks.removeAllViews();
        empty.setVisibility(info.blocks.isEmpty() ? View.VISIBLE : View.GONE);
        int open = 0;
        LayoutInflater inf = LayoutInflater.from(c);
        Set<Long> absent = Attendance.on(c) ? Db.get(c).absencesOn(key()) : new HashSet<Long>();
        for (Schedule.Block b : info.blocks) {
            if (b.unresolved && !b.cancelled) open++;
            blocks.addView(row(inf, c, b, absent.contains(b.slotId) && Attendance.counts(b)));
        }
        confirmBtn.setVisibility(open > 0 ? View.VISIBLE : View.GONE);
        confirmBtn.setText(open == 1 ? "Confirm 1 slot" : "Confirm " + open + " slots");
    }

    private int barColor(Context c, Schedule.Block b) {
        int res = R.color.block_class;
        if (b.cat.equals("meal")) res = R.color.block_meal;
        else if (b.cat.equals("practice")) res = R.color.block_practice;
        else if (b.cat.equals("item")) res = R.color.block_item;
        else if (b.type.equals("lab")) res = R.color.block_lab;
        return c.getColor(res);
    }

    private View row(LayoutInflater inf, Context c, Schedule.Block b, boolean missed) {
        View row = inf.inflate(R.layout.item_block, blocks, false);
        ((TextView) row.findViewById(R.id.t_start)).setText(Ui.clock(b.start));
        ((TextView) row.findViewById(R.id.t_end)).setText(b.cat.equals("item") ? "" : Ui.clock(b.end));
        row.findViewById(R.id.bar).setBackgroundColor(barColor(c, b));
        TextView title = row.findViewById(R.id.title);
        title.setText(b.title);
        if (b.cancelled) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        else title.setPaintFlags(title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        TextView sub = row.findViewById(R.id.sub);
        sub.setText(b.sub);
        sub.setVisibility(b.sub.isEmpty() ? View.GONE : View.VISIBLE);
        TextView status = row.findViewById(R.id.status);
        String st = b.cancelled ? "Cancelled" : missed ? "Absent" : b.unresolved ? "Confirm" : b.optional ? "Optional" : "";
        status.setText(st);
        status.setVisibility(st.isEmpty() ? View.GONE : View.VISIBLE);
        row.setOnClickListener(x -> onBlock(b));
        return row;
    }

    private void onBlock(Schedule.Block b) {
        Context c = requireContext();
        if (b.ref instanceof Db.Item) {
            startActivity(EditItemActivity.intentFor(c, (Db.Item) b.ref));
        } else if (b.ref instanceof Db.Routine) {
            startActivity(EditRoutineActivity.intentFor(c, (Db.Routine) b.ref));
        } else if (b.flexible && b.ref instanceof Db.Slot) {
            flexMenu(b, this::refresh, true);
        } else if (b.ref instanceof Db.Slot) {
            slotMenu(b);
        } else if (b.ref instanceof Db.Meal) {
            mealMenu(b);
        }
    }

    private void menu(String title, List<String> labels, List<Runnable> acts, boolean cancelable) {
        MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (dlg, i) -> acts.get(i).run());
        d.setNegativeButton(cancelable ? "Close" : "Later", null);
        d.show();
    }

    private void pickSubject(Pick cb) {
        List<Db.Subject> all = Db.get(requireContext()).subjects();
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) names[i] = all.get(i).name;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Which subject does it count for?")
                .setItems(names, (d, i) -> cb.got(all.get(i)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void flexMenu(Schedule.Block b, Runnable done, boolean cancelable) {
        Context c = requireContext();
        Db.Slot s = (Db.Slot) b.ref;
        String k = key();
        long id = b.slotId;
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (s.type.equals("lunch") || s.type.equals("advisory")) {
            labels.add("Free, no class");
            acts.add(() -> {
                Changes.setFlex(c, id, k, "free");
                done.run();
            });
            labels.add("A faculty took it, pick the subject");
            acts.add(() -> pickSubject(sub -> {
                Changes.setFlex(c, id, k, "taken:" + sub.id);
                done.run();
            }));
        } else if (s.type.equals("tutorial")) {
            labels.add("Tutorial held as usual");
            acts.add(() -> {
                Changes.setFlex(c, id, k, "usual");
                done.run();
            });
            labels.add("No tutorial, free");
            acts.add(() -> {
                Changes.setFlex(c, id, k, "free");
                done.run();
            });
            labels.add("Another faculty took it, pick the subject");
            acts.add(() -> pickSubject(sub -> {
                Changes.setFlex(c, id, k, "taken:" + sub.id);
                done.run();
            }));
        } else if (s.optional) {
            labels.add("I will attend this one");
            acts.add(() -> {
                Changes.setFlex(c, id, k, "attend");
                done.run();
            });
            labels.add("Skipping it");
            acts.add(() -> {
                Changes.setFlex(c, id, k, "usual");
                done.run();
            });
        }
        if (!b.unresolved) {
            labels.add("Ask me again");
            acts.add(() -> {
                Changes.setFlex(c, id, k, null);
                done.run();
            });
        }
        if (labels.isEmpty()) return;
        menu(b.title + ", " + Ui.clock(b.start), labels, acts, cancelable);
    }

    private void askNext(boolean evening) {
        if (!isAdded()) return;
        Context c = requireContext();
        List<Schedule.Block> open = Schedule.unresolved(c, day);
        if (open.isEmpty()) {
            if (evening && Attendance.on(c) && Attendance.hasClasses(c, day)) {
                Attendance.markDialog(requireActivity(), key(), this::refresh);
            }
            return;
        }
        flexMenu(open.get(0), () -> {
            refresh();
            askNext(evening);
        }, false);
    }

    private void slotMenu(Schedule.Block b) {
        Context c = requireContext();
        Db.Slot s = (Db.Slot) b.ref;
        String k = key();
        long id = b.slotId;
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add(b.cancelled ? "Restore it today" : "Cancel today only");
        acts.add(() -> {
            Changes.cancelSlot(c, id, k, !b.cancelled);
            refresh();
        });
        labels.add("Move today only");
        acts.add(() -> TimePick.range(getParentFragmentManager(), b.start, b.end, (st, en) -> {
            Changes.moveSlot(c, id, k, st, en);
            refresh();
        }));
        labels.add("Change its time every week");
        acts.add(() -> TimePick.range(getParentFragmentManager(), s.startMin, s.endMin, (st, en) -> {
            Changes.moveSlotWeekly(c, id, st, en);
            refresh();
        }));
        labels.add("Edit this slot for every week");
        acts.add(() -> startActivity(new Intent(c, SlotEditActivity.class).putExtra("id", id)));
        labels.add("Remove it from every week");
        acts.add(() -> new MaterialAlertDialogBuilder(c)
                .setTitle("Remove for good?")
                .setMessage(b.title + " will disappear from every " + Schedule.dayName(s.dow) + ". Use Cancel today only for a one-off.")
                .setPositiveButton("Remove", (d, w) -> {
                    Changes.removeSlotWeekly(c, id);
                    refresh();
                })
                .setNegativeButton("Keep", null)
                .show());
        if (Attendance.on(c) && Attendance.counts(b)) {
            boolean missed = Db.get(c).absencesOn(k).contains(id);
            labels.add(missed ? "Mark present" : "Mark absent");
            acts.add(() -> {
                Db.get(c).setAbsent(k, id, !missed);
                refresh();
            });
        }
        labels.add("Undo today's changes to it");
        acts.add(() -> {
            Changes.resetSlot(c, id, k);
            refresh();
        });
        menu(b.title + ", " + Ui.clock(b.start), labels, acts, true);
    }

    private void mealMenu(Schedule.Block b) {
        Context c = requireContext();
        String kind = b.type;
        String k = key();
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("Change the time today only");
        acts.add(() -> TimePick.range(getParentFragmentManager(), b.start, b.end, (st, en) -> {
            Changes.mealToday(c, kind, k, st, en, false);
            refresh();
        }));
        labels.add("Change the time for more days");
        acts.add(() -> TimePick.range(getParentFragmentManager(), b.start, b.end, (st, en) -> mealScope(kind, st, en)));
        labels.add("Skip it today");
        acts.add(() -> {
            Changes.mealToday(c, kind, k, 0, 0, true);
            refresh();
        });
        menu(b.title, labels, acts, true);
    }

    private void mealScope(String kind, int st, int en) {
        Context c = requireContext();
        int dow = day.get(Calendar.DAY_OF_WEEK);
        boolean weekend = dow == 1 || dow == 7;
        String[] labels = {"Every " + Schedule.dayName(dow), weekend ? "Every weekend day" : "Every weekday", "Every day"};
        int[][] sets = {{dow}, weekend ? new int[]{1, 7} : new int[]{2, 3, 4, 5, 6}, {1, 2, 3, 4, 5, 6, 7}};
        new MaterialAlertDialogBuilder(c)
                .setTitle("Apply " + Ui.clock(st) + " to")
                .setItems(labels, (d, i) -> {
                    Changes.mealGroup(c, kind, sets[i], st, en);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        cmdLayout.setEnabled(!busy);
    }

    void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what changed");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            speech.launch(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "No speech service found on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private void submit() {
        String text = cmd.getText() == null ? "" : cmd.getText().toString().trim();
        if (text.isEmpty()) return;
        setBusy(true);
        Context app = requireContext().getApplicationContext();
        Bg.run(() -> {
            Gemini.Result r = Assistant.change(app, text);
            Changes.Parsed p = r.ok() ? Changes.parse(app, r.text) : null;
            boolean queued = false;
            if (!r.ok() && r.retryable()) {
                Db.get(app).addJob("change", text);
                Queue.kick(app, Math.max(60000L, r.retryMs));
                queued = true;
            }
            boolean wait = queued;
            Bg.main(() -> {
                if (!isAdded()) return;
                setBusy(false);
                if (wait) {
                    cmd.setText("");
                    Toast.makeText(app, "Rate limited. I will keep trying and notify you when it is ready to review.",
                            Toast.LENGTH_LONG).show();
                } else if (!r.ok()) {
                    Toast.makeText(app, r.error, Toast.LENGTH_LONG).show();
                } else if (p.error != null) {
                    Toast.makeText(app, p.error, Toast.LENGTH_LONG).show();
                } else {
                    cmd.setText("");
                    preview(p, false);
                }
            });
        });
    }

    private void showPending() {
        Context c = requireContext();
        String json = Prefs.pendingChange(c);
        if (json.isEmpty()) return;
        Changes.Parsed p = Changes.parse(c, json);
        if (p.error != null) {
            Prefs.setPendingChange(c, "");
            Toast.makeText(c, p.error, Toast.LENGTH_LONG).show();
            return;
        }
        preview(p, true);
    }

    private void preview(Changes.Parsed p, boolean pending) {
        Context c = requireContext();
        StringBuilder sb = new StringBuilder();
        for (Changes.Change ch : p.list) sb.append("• ").append(ch.text).append('\n');
        if (!p.problems.isEmpty()) {
            sb.append("\nSkipped:\n");
            for (String s : p.problems) sb.append("• ").append(s).append('\n');
        }
        new MaterialAlertDialogBuilder(c)
                .setTitle("Apply these changes?")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Apply", (d, w) -> {
                    Changes.apply(c, p);
                    if (pending) Prefs.setPendingChange(c, "");
                    refresh();
                })
                .setNegativeButton("Discard", (d, w) -> {
                    if (pending) Prefs.setPendingChange(c, "");
                })
                .setCancelable(!pending)
                .show();
    }

    private void more() {
        Context c = requireContext();
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("Mark a holiday or exam period");
        acts.add(this::modeKind);
        labels.add("Back to the normal timetable");
        acts.add(this::clearRange);
        labels.add("This day follows another weekday");
        acts.add(this::followDay);
        labels.add("Add a one-off reminder");
        acts.add(() -> startActivity(new Intent(c, EditItemActivity.class)));
        labels.add("Meal times");
        acts.add(() -> startActivity(new Intent(c, MealsActivity.class)));
        labels.add("Attendance");
        acts.add(() -> startActivity(new Intent(c, AttendanceActivity.class)));
        labels.add("Subjects and attendance rules");
        acts.add(() -> startActivity(new Intent(c, SubjectsActivity.class)));
        labels.add("Edit the weekly timetable");
        acts.add(() -> startActivity(new Intent(c, WeekActivity.class)));
        labels.add("Sync to Google Calendar now");
        acts.add(this::syncNow);
        labels.add("Replace timetable from a photo");
        acts.add(() -> imagePick.launch("image/*"));
        labels.add("Replace timetable from JSON");
        acts.add(this::jsonImport);
        menu("Timetable", labels, acts, true);
    }

    private void modeKind() {
        String[] labels = {"Holiday", "Exam period"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("What is it?")
                .setItems(labels, (d, i) -> pickRange(i == 0 ? "holiday" : "exam"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickRange(String kind) {
        MaterialDatePicker<Pair<Long, Long>> dp = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText(kind.equals("exam") ? "Exam dates" : "Holiday dates").build();
        dp.addOnPositiveButtonClickListener(sel -> {
            if (sel.first == null || sel.second == null) return;
            String from = utcKey(sel.first);
            String to = utcKey(sel.second);
            View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
            ((TextInputLayout) view.findViewById(R.id.input_layout)).setHint("Name, like Diwali or CAT 1 (optional)");
            TextInputEditText in = view.findViewById(R.id.input);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(from.equals(to) ? from : from + " to " + to)
                    .setView(view)
                    .setPositiveButton("Save", (d, w) -> {
                        Changes.dayMode(requireContext(), kind, from, to, in.getText() == null ? "" : in.getText().toString().trim());
                        refresh();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        dp.show(getParentFragmentManager(), "range");
    }

    private void clearRange() {
        MaterialDatePicker<Pair<Long, Long>> dp = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("Back to normal for").build();
        dp.addOnPositiveButtonClickListener(sel -> {
            if (sel.first == null || sel.second == null) return;
            Changes.dayMode(requireContext(), null, utcKey(sel.first), utcKey(sel.second), null);
            refresh();
        });
        dp.show(getParentFragmentManager(), "clear");
    }

    private void followDay() {
        String[] names = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(day.getTime()) + " follows")
                .setItems(names, (d, i) -> {
                    Changes.follow(requireContext(), key(), i + 2);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void syncNow() {
        Context app = requireContext().getApplicationContext();
        setBusy(true);
        Bg.run(() -> {
            String err = CalSync.run(app);
            Bg.main(() -> {
                if (!isAdded()) return;
                setBusy(false);
                Toast.makeText(app, err == null ? "Calendar updated" : err, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void jsonImport() {
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        ((TextInputLayout) view.findViewById(R.id.input_layout)).setHint("Paste the timetable JSON");
        TextInputEditText in = view.findViewById(R.id.input);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Import timetable")
                .setView(view)
                .setPositiveButton("Read", (d, w) -> {
                    TimetableImport.Result r = TimetableImport.fromJson(in.getText() == null ? "" : in.getText().toString());
                    if (r.error != null) {
                        Toast.makeText(requireContext(), r.error, Toast.LENGTH_LONG).show();
                    } else {
                        confirmImport(r);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importPhoto(Uri uri) {
        if (uri == null || !isAdded()) return;
        setBusy(true);
        Context app = requireContext().getApplicationContext();
        Bg.run(() -> {
            TimetableImport.Result r = TimetableImport.fromPhoto(app, uri);
            Bg.main(() -> {
                if (!isAdded()) return;
                setBusy(false);
                if (r.error != null) {
                    Toast.makeText(app, r.retry ? "Rate limited. Try again in a minute." : r.error, Toast.LENGTH_LONG).show();
                } else {
                    confirmImport(r);
                }
            });
        });
    }

    private void confirmImport(TimetableImport.Result r) {
        Context c = requireContext();
        new MaterialAlertDialogBuilder(c)
                .setTitle("Replace the whole timetable?")
                .setMessage(TimetableImport.summary(r.entries)
                        + "\n\nYour renamed subjects are kept. Slot-level changes you made for single days are cleared.")
                .setPositiveButton("Replace", (d, w) -> {
                    TimetableImport.replace(c, r.entries);
                    refresh();
                    Toast.makeText(c, "Timetable replaced. Check it in Edit the weekly timetable.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
