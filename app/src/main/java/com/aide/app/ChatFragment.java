package com.aide.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

public class ChatFragment extends Fragment {
    private final Adapter adapter = new Adapter();
    private RecyclerView list;
    private TextView empty;
    private EditText input;
    private View progress;
    private View send;
    private View statusRow;
    private TextView status;
    private boolean busy;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle b) {
        return inf.inflate(R.layout.fragment_chat, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        super.onViewCreated(v, b);
        list = v.findViewById(R.id.list);
        empty = v.findViewById(R.id.empty);
        input = v.findViewById(R.id.input);
        progress = v.findViewById(R.id.progress);
        send = v.findViewById(R.id.send);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        send.setOnClickListener(x -> send());
        v.findViewById(R.id.btn_handoff).setOnClickListener(x -> handoff());
        v.findViewById(R.id.btn_new).setOnClickListener(x -> confirmNew());
        statusRow = v.findViewById(R.id.status_row);
        status = v.findViewById(R.id.status);
        v.findViewById(R.id.btn_retry).setOnClickListener(x -> Queue.kick(requireContext().getApplicationContext(), 0));
        v.findViewById(R.id.btn_cancel).setOnClickListener(x -> {
            Db db = Db.get(requireContext());
            db.deleteJobs("chat");
            db.deleteTrailingUserMsgs();
            reload();
            updateStatus();
        });
        reload();
        updateStatus();
    }

    @Override
    public void onStart() {
        super.onStart();
        Queue.setVisible(true);
        Queue.setUi(() -> {
            if (isAdded()) {
                reload();
                updateStatus();
            }
        });
    }

    @Override
    public void onStop() {
        super.onStop();
        Queue.setVisible(false);
        Queue.setUi(null);
    }

    private void updateStatus() {
        Db.Job chat = null;
        for (Db.Job j : Db.get(requireContext()).jobs()) {
            if ("chat".equals(j.kind)) {
                chat = j;
                break;
            }
        }
        if (chat == null) {
            statusRow.setVisibility(View.GONE);
            return;
        }
        statusRow.setVisibility(View.VISIBLE);
        status.setText("Waiting for a free slot (tried " + (chat.attempts + 1) + " times). I will keep trying and notify you.");
    }

    private void reload() {
        adapter.set(Db.get(requireContext()).allMsgs());
        int n = adapter.getItemCount();
        empty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        if (n > 0) list.scrollToPosition(n - 1);
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        send.setEnabled(!value);
    }

    private void send() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        if (text.equalsIgnoreCase("/save")) {
            handoff();
            return;
        }
        Context app = requireContext().getApplicationContext();
        Db.get(app).addMsg("user", text);
        reload();
        setBusy(true);
        Bg.run(() -> {
            Assistant.Turn t = Assistant.respond(app);
            boolean queued = false;
            if (t.error != null && t.retry) {
                if (Db.get(app).jobCount("chat") == 0) Db.get(app).addJob("chat", "");
                Queue.kick(app, Math.max(60000L, t.retryMs));
                queued = true;
            }
            boolean wait = queued;
            Bg.main(() -> {
                if (!isAdded()) return;
                setBusy(false);
                reload();
                updateStatus();
                if (t.error != null && !wait) {
                    Toast.makeText(app, t.error, Toast.LENGTH_LONG).show();
                } else if (t.proposal != null) {
                    offer(t.proposal);
                }
            });
        });
    }

    private void offer(Db.Item item) {
        Snackbar.make(requireView(), "Ready to review: " + item.title, 10000)
                .setAction("Review", x -> startActivity(EditItemActivity.intentFor(requireContext(), item)))
                .show();
    }

    private void handoff() {
        if (busy) return;
        setBusy(true);
        Context app = requireContext().getApplicationContext();
        Bg.run(() -> {
            Gemini.Result r = Assistant.handoff(app);
            Bg.main(() -> {
                if (!isAdded()) return;
                setBusy(false);
                if (r.ok()) {
                    showHandoff(r.text);
                } else {
                    Toast.makeText(app, r.error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showHandoff(String text) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Handoff summary")
                .setMessage(text)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("handoff", text));
                    Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Share", (d, w) -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("text/plain");
                    s.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(s, null));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmNew() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Start a new chat?")
                .setMessage("Your notes and calendar items stay. The current conversation is cleared.")
                .setPositiveButton("Start new chat", (d, w) -> {
                    Db.get(requireContext()).clearMsgs();
                    Prefs.setSummary(requireContext(), "");
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private List<Db.Msg> data = new ArrayList<>();

        void set(List<Db.Msg> d) {
            data = d;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int type) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_msg, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Db.Msg m = data.get(i);
            boolean user = "user".equals(m.role);
            Context c = h.card.getContext();
            h.text.setText(m.body);
            h.text.setTextColor(ContextCompat.getColor(c, user ? R.color.bubble_user_text : R.color.bubble_ai_text));
            h.card.setCardBackgroundColor(ContextCompat.getColor(c, user ? R.color.bubble_user : R.color.bubble_ai));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.card.getLayoutParams();
            lp.gravity = user ? Gravity.END : Gravity.START;
            h.card.setLayoutParams(lp);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final TextView text;

            VH(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                text = v.findViewById(R.id.text);
            }
        }
    }
}
