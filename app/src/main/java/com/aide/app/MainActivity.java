package com.aide.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private BottomNavigationView nav;

    private final ActivityResultLauncher<String[]> perms =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> { });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        Ui.insets(findViewById(R.id.root));
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            show(item.getItemId());
            return true;
        });
        if (b == null) {
            if (wantsTimetable(getIntent())) {
                nav.setSelectedItemId(R.id.nav_timetable);
            } else {
                show(R.id.nav_home);
            }
        }
        askPermissions();
        Usage.apply(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (wantsTimetable(intent)) {
            nav.setSelectedItemId(R.id.nav_timetable);
        } else if (intent.hasExtra("voice") || intent.hasExtra("plan")) {
            nav.setSelectedItemId(R.id.nav_home);
        }
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.container);
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            if (f instanceof HomeFragment) ((HomeFragment) f).consumeIntent();
            if (f instanceof TimetableFragment) ((TimetableFragment) f).consumeIntent();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.Context app = getApplicationContext();
        Bg.run(() -> {
            Agenda.tick(app);
            CalSync.maybe(app);
        });
    }

    private static boolean wantsTimetable(Intent i) {
        return i != null && (i.hasExtra("confirm") || i.hasExtra("review"));
    }

    void openTimetable() {
        nav.setSelectedItemId(R.id.nav_timetable);
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (!CalendarHelper.has(this)) need.addAll(Arrays.asList(CalendarHelper.PERMS));
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) perms.launch(need.toArray(new String[0]));
    }

    private void show(int id) {
        Fragment f;
        String title;
        if (id == R.id.nav_timetable) {
            f = new TimetableFragment();
            title = "Timetable";
        } else {
            f = new HomeFragment();
            title = getString(R.string.app_name);
        }
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(title);
        getSupportFragmentManager().beginTransaction().replace(R.id.container, f).commit();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.main, m);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (i.getItemId() == R.id.action_limit) {
            limitDialog();
            return true;
        }
        return super.onOptionsItemSelected(i);
    }

    private void limitDialog() {
        String[] labels = {"In 1 hour", "In 2 hours", "In 3 hours", "In 5 hours"};
        int[] hours = {1, 2, 3, 5};
        new MaterialAlertDialogBuilder(this)
                .setTitle("When does your AI limit reset?")
                .setItems(labels, (d, which) -> {
                    Db.Item it = new Db.Item();
                    it.title = "AI limit resets";
                    it.kind = "other";
                    it.start = System.currentTimeMillis() + hours[which] * 3600000L;
                    it.offsets = "0";
                    String err = Items.save(this, it);
                    Toast.makeText(this, err == null ? "Reminder set" : err, Toast.LENGTH_LONG).show();
                    Fragment f = getSupportFragmentManager().findFragmentById(R.id.container);
                    if (f instanceof HomeFragment) ((HomeFragment) f).refresh();
                })
                .show();
    }
}
