package com.aide.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class MainActivity extends AppCompatActivity {

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

        BottomNavigationView nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            show(item.getItemId());
            return true;
        });
        if (b == null) show(R.id.nav_home);
        if (!CalendarHelper.has(this)) perms.launch(CalendarHelper.PERMS);
    }

    private void show(int id) {
        Fragment f;
        String title;
        if (id == R.id.nav_chat) {
            f = new ChatFragment();
            title = "Assistant";
        } else if (id == R.id.nav_notes) {
            f = new NotesFragment();
            title = "Notes";
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
