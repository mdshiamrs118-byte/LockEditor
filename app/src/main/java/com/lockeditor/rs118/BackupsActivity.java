package com.lockeditor.rs118;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Saved layouts: create, restore, rename, delete, export to a file, import from a file. */
public class BackupsActivity extends BaseActivity {
    private static final int REQ_EXPORT = 1, REQ_IMPORT = 2;

    private LinearLayout listBox;
    private String pendingExportId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(PAD), dp(20), dp(PAD), dp(PAD));
        scroll.addView(root);

        TextView title = text("Backups", 28, ui.txt, true);
        title.setPadding(dp(6), 0, 0, dp(4));
        root.addView(title);
        TextView hint = text("Saved inside the app. Restore a layout, or export it to a file to keep it elsewhere.", 12, ui.sub, false);
        hint.setPadding(dp(6), 0, dp(6), dp(12));
        root.addView(hint);

        LinearLayout actions = new LinearLayout(this);
        TextView add = button("New backup", true, v -> askNewBackup());
        TextView imp = button("Import file", false, v -> pickImport());
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        a1.rightMargin = dp(GAP / 2);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        a2.leftMargin = dp(GAP / 2);
        actions.addView(add, a1);
        actions.addView(imp, a2);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.bottomMargin = dp(GAP + 2);
        root.addView(actions, alp);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        render();
    }

    private String fmtDate(long t) {
        return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(t));
    }

    private void render() {
        listBox.removeAllViews();
        List<BackupStore.Backup> all = BackupStore.list(this);
        if (all.isEmpty()) {
            TextView e = text("No backups yet. Tap \"New backup\" to save the current layout.", 14, ui.sub, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(16), dp(40), dp(16), dp(40));
            listBox.addView(e);
            return;
        }
        for (final BackupStore.Backup bk : all) {
            LinearLayout c = card(null);
            c.addView(text(bk.name, 16, ui.txt, true));
            TextView sub = text(fmtDate(bk.created) + "  ·  " + bk.widgets + (bk.widgets == 1 ? " widget" : " widgets")
                    + "  ·  " + bk.rows + "×" + bk.cols + " grid", 12, ui.sub, false);
            sub.setPadding(0, dp(2), 0, dp(10));
            c.addView(sub);
            LinearLayout row = new LinearLayout(this);
            row.addView(btn("Restore", true, v -> confirmRestore(bk)));
            row.addView(btn("Export", false, v -> startExport(bk)));
            row.addView(btn("Rename", false, v -> askRename(bk)));
            row.addView(btn("Delete", false, v -> confirmDelete(bk)));
            c.addView(row);
            put(listBox, c);
        }
    }

    private TextView btn(String label, boolean primary, android.view.View.OnClickListener l) {
        TextView t = button(label, primary, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp.setMargins(dp(2), 0, dp(2), dp(4));
        t.setLayoutParams(lp);
        return t;
    }

    // ---------- actions ----------
    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(this, ui.dialogTheme()); }

    private EditText nameInput(String initial) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSingleLine(true);
        e.setText(initial);
        e.setSelectAllOnFocus(true);
        e.setTextColor(ui.txt);
        return e;
    }

    private void askNewBackup() {
        final EditText e = nameInput("Backup " + new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date()));
        dialog().setTitle("New backup").setView(pad(e))
                .setPositiveButton("Save", (x, w) -> {
                    BackupStore.Backup bk = BackupStore.create(this, e.getText().toString(), ConfigStore.load(this));
                    Toast.makeText(this, bk != null ? "Backup saved" : "Could not save the backup", Toast.LENGTH_SHORT).show();
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void askRename(final BackupStore.Backup bk) {
        final EditText e = nameInput(bk.name);
        dialog().setTitle("Rename backup").setView(pad(e))
                .setPositiveButton("Rename", (x, w) -> {
                    BackupStore.rename(this, bk.id, e.getText().toString());
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(final BackupStore.Backup bk) {
        dialog().setTitle("Delete backup?").setMessage("\"" + bk.name + "\" will be removed. This cannot be undone.")
                .setPositiveButton("Delete", (x, w) -> { BackupStore.delete(this, bk.id); render(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmRestore(final BackupStore.Backup bk) {
        dialog().setTitle("Restore \"" + bk.name + "\"?")
                .setMessage("This replaces the current layout and settings. A backup of the current state is saved first.\n\n"
                        + "Widgets that are not in the current layout get set up again, so widgets like KWGT or Tasker "
                        + "may need to be configured again.")
                .setPositiveButton("Restore", (x, w) -> {
                    BackupStore.create(this, "Before restoring \"" + bk.name + "\"", ConfigStore.load(this));
                    BackupStore.restore(this, bk);
                    Toast.makeText(this, "Restored", Toast.LENGTH_SHORT).show();
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private android.view.View pad(android.view.View v) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    // ---------- export / import (system file picker, no storage permission needed) ----------
    private void startExport(BackupStore.Backup bk) {
        pendingExportId = bk.id;
        String file = bk.name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (file.isEmpty()) file = "backup";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "LockEditor - " + file + ".json");
        try { startActivityForResult(i, REQ_EXPORT); }
        catch (Throwable t) { Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show(); }
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(i, REQ_IMPORT); }
        catch (Throwable t) { Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_EXPORT) {
            BackupStore.Backup bk = BackupStore.get(this, pendingExportId);
            if (bk == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                out.write(BackupStore.exportJson(bk).getBytes("UTF-8"));
                Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "Export failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (req == REQ_IMPORT) {
            try {
                BackupStore.Backup bk = BackupStore.importJson(this, BackupStore.readAll(getContentResolver().openInputStream(uri)));
                Toast.makeText(this, bk != null ? "Imported \"" + bk.name + "\"" : "That is not a Lock Editor backup",
                        Toast.LENGTH_LONG).show();
                render();
            } catch (Throwable t) {
                Toast.makeText(this, "Import failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
