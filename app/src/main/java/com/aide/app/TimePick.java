package com.aide.app;

import androidx.fragment.app.FragmentManager;

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

final class TimePick {
    interface One {
        void done(int minutes);
    }

    interface Range {
        void done(int start, int end);
    }

    private TimePick() {}

    static void one(FragmentManager fm, String title, int minutes, One cb) {
        MaterialTimePicker tp = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(minutes / 60)
                .setMinute(minutes % 60)
                .setTitleText(title)
                .build();
        tp.addOnPositiveButtonClickListener(v -> cb.done(tp.getHour() * 60 + tp.getMinute()));
        tp.show(fm, title);
    }

    static void range(FragmentManager fm, int start, int end, Range cb) {
        one(fm, "Start time", start, s -> one(fm, "End time", Math.min(Math.max(end, s + 30), 1439),
                e -> cb.done(s, e > s ? e : Math.min(s + 30, 1439))));
    }
}
