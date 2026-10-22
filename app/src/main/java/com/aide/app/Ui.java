package com.aide.app;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class Ui {
    private Ui() {}

    static void insets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, ins) -> {
            Insets bars = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = ins.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }

    static String clock(int min) {
        int h = min / 60;
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.getDefault(), "%d:%02d %s", h12, min % 60, h < 12 ? "AM" : "PM");
    }

    static String daysLabel(int mask) {
        if (mask == 127) return "Every day";
        String[] names = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        int[] order = {1, 2, 3, 4, 5, 6, 0};
        StringBuilder sb = new StringBuilder();
        for (int i : order) {
            if ((mask & (1 << i)) != 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }

    static String duration(long ms) {
        long m = Math.max(1, ms / 60000L);
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    static String kindLabel(String k) {
        if ("viva".equals(k)) return "Viva";
        if ("task".equals(k)) return "Task";
        return "Other";
    }

    static String timeText(long ms) {
        return new SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(new Date(ms));
    }

    private static void clear(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    static String relative(long start) {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(start);
        clear(t);
        Calendar n = Calendar.getInstance();
        clear(n);
        long days = Math.round((t.getTimeInMillis() - n.getTimeInMillis()) / 86400000.0);
        if (days < 0) return "Overdue";
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        return "In " + days + " days";
    }

    static String offLabel(int m) {
        if (m == 0) return "at the time";
        if (m % 10080 == 0) {
            int w = m / 10080;
            return w + (w == 1 ? " week" : " weeks") + " before";
        }
        if (m % 1440 == 0) {
            int d = m / 1440;
            return d + (d == 1 ? " day" : " days") + " before";
        }
        if (m % 60 == 0) {
            int h = m / 60;
            return h + (h == 1 ? " hour" : " hours") + " before";
        }
        return m + " min before";
    }

    static String subline(Db.Item it) {
        StringBuilder sb = new StringBuilder(kindLabel(it.kind));
        sb.append(", ").append(new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(it.start)));
        int[] offs = it.offsetArray();
        if (offs.length > 0) {
            sb.append(", remind ");
            for (int i = 0; i < offs.length; i++) {
                if (i > 0) sb.append(" and ");
                sb.append(offLabel(offs[i]));
            }
        }
        return sb.toString();
    }
}
