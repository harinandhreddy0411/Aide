package com.aide.app;

import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

public class MealsActivity extends AppCompatActivity {
    private static final String[] TITLES = {
            "Breakfast on weekdays", "Breakfast on weekends", "Lunch on weekends and holidays",
            "Dinner on weekdays", "Dinner on weekends"};
    private static final String[] KINDS = {"breakfast", "breakfast", "lunch", "dinner", "dinner"};
    private static final int[][] DOWS = {{2, 3, 4, 5, 6}, {1, 7}, {1, 2, 3, 4, 5, 6, 7}, {2, 3, 4, 5, 6}, {1, 7}};
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_meals);
        Ui.insets(findViewById(R.id.root));
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.list);
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        Db db = Db.get(this);
        for (int i = 0; i < TITLES.length; i++) {
            int row = i;
            Db.Meal m = db.meal(KINDS[i], i == 2 ? 1 : DOWS[i][0]);
            String time = m == null ? "Not set" : Ui.clock(m.startMin) + " to " + Ui.clock(m.endMin);
            String sub = i == 2 ? time + ". Weekday lunch follows the timetable's lunch slot." : time;
            list.addView(Ui.line(list, TITLES[i], sub, v -> pick(row, m)));
        }
    }

    private void pick(int row, Db.Meal current) {
        int s = current == null ? 720 : current.startMin;
        int e = current == null ? 780 : current.endMin;
        TimePick.range(getSupportFragmentManager(), s, e, (start, end) -> {
            Changes.mealGroup(this, KINDS[row], DOWS[row], start, end);
            refresh();
        });
    }
}
