package com.lockeditor.rs118;

import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

public class MainActivity extends BaseActivity implements GridEditorView.Host {
    private Config cfg;
    private Limits lim;
    private GridEditorView preview;
    private SnapSync snaps;
    private TextView widgetInfo;
    private LinearLayout backupsCard;
    private boolean built;
    private final Map<String, AppWidgetProviderInfo> infos = new HashMap<>();
    private final Map<String, Drawable> previews = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = ConfigStore.load(this);
        lim = Limits.load(this);
        for (AppWidgetProviderInfo i : AppWidgetManager.getInstance(this).getInstalledProviders())
            infos.put(i.provider.flattenToString(), i);
        snaps = new SnapSync(this, 4000, () -> { if (preview != null) preview.invalidate(); });
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (built) { // widgets may have changed in the editor, or a backup was restored
            cfg = ConfigStore.load(this);
            preview.setConfig(cfg);
            refreshInfo();
        }
        snaps.start();
        // re-read on every resume: picks up a changed wallpaper
        WallpaperLoader.load(this, bmp -> { if (bmp != null && preview != null) preview.setWallpaper(bmp); });
    }

    @Override
    protected void onPause() {
        snaps.stop();
        super.onPause();
    }

    private void save() {
        ConfigStore.saveLayout(this, cfg);
        if (preview != null) preview.invalidate();
        refreshInfo();
    }

    private void refreshInfo() {
        if (widgetInfo != null) {
            int n = cfg.widgets.size();
            widgetInfo.setText(n + (n == 1 ? " widget" : " widgets"));
        }
        if (backupsCard != null) {
            TextView s = backupsCard.findViewWithTag("sub");
            if (s != null) s.setText(BackupStore.list(this).size() + " saved");
        }
    }

    // ---------- building blocks ----------
    private LinearLayout actionCard(String title, String sub, View.OnClickListener l) {
        LinearLayout c = card(null);
        c.setPadding(dp(CARD_PAD), dp(12), dp(CARD_PAD), dp(12));
        c.setOnClickListener(l);
        c.addView(text(title, 14, ui.txt, true));
        TextView s = text(sub, 12, ui.sub, false);
        s.setTag("sub");
        c.addView(s);
        return c;
    }

    private TextView circleBtn(String s, View.OnClickListener l) {
        TextView t = text(s, 20, ui.acc, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(ui.track, 20));
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return t;
    }

    /** Label on top, [-] value [+] below: fits a half-width card. */
    private View stepper(String label, int init, int min, int max, IntConsumer cb) {
        final int[] val = {Math.max(min, Math.min(max, init))};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(2), 0, dp(6));
        box.addView(text(label, 12, ui.sub, false));
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        final TextView v = text(String.valueOf(val[0]), 17, ui.txt, true);
        v.setGravity(Gravity.CENTER);
        r.addView(circleBtn("−", x -> { if (val[0] > min) { val[0]--; v.setText(String.valueOf(val[0])); cb.accept(val[0]); } }));
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(circleBtn("+", x -> { if (val[0] < max) { val[0]++; v.setText(String.valueOf(val[0])); cb.accept(val[0]); } }));
        box.addView(r);
        return box;
    }

    /** Label and value on one line, slider below it. */
    private View slider(String label, int init, int min, int max, String unit, IntConsumer cb) {
        int start = Math.max(min, Math.min(max, init));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, 0);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView val = text(start + unit, 12, ui.sub, false);
        head.addView(text(label, 13, ui.txt, false), new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(val);
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(start - min);
        s.setProgressTintList(ColorStateList.valueOf(ui.acc));
        s.setThumbTintList(ColorStateList.valueOf(ui.acc));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                val.setText((p + min) + unit);
                if (user) cb.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { save(); }
        });
        box.addView(head);
        box.addView(s, new LinearLayout.LayoutParams(-1, dp(32)));
        return box;
    }

    // ---------- screen ----------
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(PAD), dp(28), dp(PAD), dp(PAD));
        scroll.addView(root);

        TextView title = text("Lock Editor", 30, ui.txt, true);
        title.setPadding(dp(6), 0, 0, dp(14));
        root.addView(title);

        // quick actions: setup check + backups
        LinearLayout quick = new LinearLayout(this);
        LinearLayout setupCard = actionCard("Setup check", "Verify everything works",
                v -> startActivity(new Intent(this, SetupActivity.class)));
        backupsCard = actionCard("Backups", "", v -> startActivity(new Intent(this, BackupsActivity.class)));
        LinearLayout.LayoutParams q1 = new LinearLayout.LayoutParams(0, -2, 1f);
        q1.rightMargin = dp(GAP / 2);
        LinearLayout.LayoutParams q2 = new LinearLayout.LayoutParams(0, -2, 1f);
        q2.leftMargin = dp(GAP / 2);
        quick.addView(setupCard, q1);
        quick.addView(backupsCard, q2);
        put(root, quick);

        // two columns; cards inside each column take the height of their content
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1f);
        llp.rightMargin = dp(GAP / 2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.leftMargin = dp(GAP / 2);
        cols.addView(left, llp);
        cols.addView(right, rlp);
        root.addView(cols);

        // ---- left top: lock screen preview (tap = edit) ----
        LinearLayout pc = card("Lock screen");
        DisplayMetrics real = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(real);
        int colW = (getResources().getDisplayMetrics().widthPixels - 2 * dp(PAD) - dp(GAP)) / 2;
        int innerW = colW - 2 * dp(CARD_PAD);
        int innerH = Math.round(innerW * (real.heightPixels / (float) Math.max(1, real.widthPixels)));
        preview = new GridEditorView(this);
        preview.setHost(this);
        preview.setLight(!ui.dark);
        preview.setInteractive(false);
        preview.setMarginDp(0);
        preview.setCornerDp(16);
        preview.setConfig(cfg);
        FrameLayout wrap = new FrameLayout(this); // preview does not consume touches, so the wrapper gets the tap
        wrap.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        wrap.setOnClickListener(v -> startActivity(new Intent(this, EditorActivity.class)));
        pc.addView(wrap, new LinearLayout.LayoutParams(innerW, innerH));
        widgetInfo = text("", 12, ui.sub, false);
        widgetInfo.setGravity(Gravity.CENTER);
        widgetInfo.setPadding(0, dp(8), 0, dp(8));
        pc.addView(widgetInfo, new LinearLayout.LayoutParams(-1, -2));
        TextView edit = button("Edit layout", true, v -> startActivity(new Intent(this, EditorActivity.class)));
        edit.setTextSize(14);
        pc.addView(edit, new LinearLayout.LayoutParams(-1, dp(44)));
        put(left, pc);

        // ---- left bottom: hide stock elements ----
        LinearLayout hc = card("Hide stock");
        hc.addView(switchRow("Clock", cfg.hideClock, v -> { cfg.hideClock = v; save(); }));
        hc.addView(switchRow("Date", cfg.hideDate, v -> { cfg.hideDate = v; save(); }));
        hc.addView(switchRow("Notifications", cfg.hideNotifs, v -> { cfg.hideNotifs = v; save(); }));
        hc.addView(switchRow("Shortcuts", cfg.hideShortcuts, v -> { cfg.hideShortcuts = v; save(); }));
        hc.addView(switchRow("Lock icon", cfg.hideLockIcon, v -> { cfg.hideLockIcon = v; save(); }));
        hc.addView(switchRow("Hints", cfg.hideHints, v -> { cfg.hideHints = v; save(); }));
        hc.addView(switchRow("Carrier name", cfg.hideCarrier, v -> { cfg.hideCarrier = v; save(); }));
        hc.addView(switchRow("Status bar", cfg.hideStatusBar, v -> { cfg.hideStatusBar = v; save(); }));
        put(left, hc);

        // ---- right top: grid and area ----
        LinearLayout gc = card("Grid and area");
        gc.addView(stepper("Rows", cfg.rows, lim.min[Limits.ROWS], lim.max[Limits.ROWS], v -> { cfg.rows = v; cfg.clamp(); save(); }));
        gc.addView(stepper("Columns", cfg.cols, lim.min[Limits.COLS], lim.max[Limits.COLS], v -> { cfg.cols = v; cfg.clamp(); save(); }));
        gc.addView(slider("Top inset", cfg.insetTop, lim.min[Limits.ITOP], lim.max[Limits.ITOP], "dp", v -> { cfg.insetTop = v; preview.invalidate(); }));
        gc.addView(slider("Bottom inset", cfg.insetBottom, lim.min[Limits.IBOT], lim.max[Limits.IBOT], "dp", v -> { cfg.insetBottom = v; preview.invalidate(); }));
        put(right, gc);

        // ---- right middle: notification list ----
        LinearLayout nc = card("Notification list");
        nc.addView(slider("Width", cfg.notifW, lim.min[Limits.NW], lim.max[Limits.NW], "%", v -> cfg.notifW = v));
        nc.addView(slider("Height", cfg.notifH, lim.min[Limits.NH], lim.max[Limits.NH], "%", v -> cfg.notifH = v));
        nc.addView(slider("Left / right", cfg.notifDx, lim.min[Limits.NDX], lim.max[Limits.NDX], "dp", v -> cfg.notifDx = v));
        nc.addView(slider("Up / down", cfg.notifDy, lim.min[Limits.NDY], lim.max[Limits.NDY], "dp", v -> cfg.notifDy = v));
        nc.addView(tapRow("Size mode: " + (cfg.notifMode == 1 ? "Reflow" : "Stretch"), v -> {
            cfg.notifMode = cfg.notifMode == 1 ? 0 : 1;
            save();
            recreate();
        }));
        nc.addView(slider("Max (0 = auto)", cfg.notifMax, 0, 12, "", v -> cfg.notifMax = v));
        nc.addView(tapRow("Reset", v -> {
            cfg.notifW = 100; cfg.notifH = 100; cfg.notifDx = 0; cfg.notifDy = 0;
            save();
            recreate();
        }));
        put(right, nc);

        // ---- right bottom: tools ----
        LinearLayout tc = card("Tools");
        tc.addView(tapRow("Status log", v -> showLog()));
        tc.addView(switchRow("Debug marker", cfg.debug, v -> { cfg.debug = v; save(); }));
        tc.addView(tapRow("Theme: " + themeName(Ui.mode(this)), v -> showTheme()));
        tc.addView(tapRow("Restart SystemUI", v -> runRoot("killall com.android.systemui", "Restarting SystemUI")));
        tc.addView(tapRow("Grant widget access", v -> runRoot("cmd appwidget grantbind --package com.android.systemui", "Done")));
        tc.addView(tapRow("Advanced", v -> showAdvanced()));
        put(right, tc);

        setContentView(scroll);
        built = true;
        refreshInfo();
    }

    // ---------- dialogs ----------
    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(this, ui.dialogTheme()); }

    private static String themeName(int m) {
        return m == Ui.LIGHT ? "Light" : m == Ui.DARK ? "Dark" : "System";
    }

    private void showTheme() {
        final String[] names = {"System default", "Light", "Dark"};
        dialog().setTitle("Theme").setSingleChoiceItems(names, Ui.mode(this), (dlg, which) -> {
            Ui.setMode(this, which);
            dlg.dismiss();
            recreate();
        }).setNegativeButton("Cancel", null).show();
    }

    private void showLog() {
        String log = ConfigStore.readLog(this);
        if (log.isEmpty()) log = "(empty)\n\nThe SystemUI hook has not reported anything.\n"
                + "Check: module enabled in LSPosed with scopes System UI and System Framework, phone rebooted, then lock and unlock once.";
        TextView t = text(log, 11, ui.txt, false);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        dialog().setTitle("SystemUI status").setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear", (x, w) -> ConfigStore.clearLog(this)).show();
    }

    private void showAdvanced() {
        final EditText[][] f = new EditText[Limits.N][2];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView hint = text("Set the minimum and maximum of every slider, and of rows and columns.", 12, ui.sub, false);
        hint.setPadding(0, 0, 0, dp(8));
        box.addView(hint);
        for (int i = 0; i < Limits.N; i++) {
            box.addView(text(Limits.NAMES[i], 13, ui.txt, true));
            LinearLayout r = new LinearLayout(this);
            for (int k = 0; k < 2; k++) {
                EditText e = new EditText(this);
                e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                e.setHint(k == 0 ? "Min" : "Max");
                e.setText(String.valueOf(k == 0 ? lim.min[i] : lim.max[i]));
                e.setTextSize(15);
                f[i][k] = e;
                r.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            box.addView(r);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog dlg = dialog()
                .setTitle("Advanced: limits").setView(sv)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
                .setNeutralButton("Defaults", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Limits def = new Limits();
            for (int i = 0; i < Limits.N; i++) {
                f[i][0].setText(String.valueOf(def.min[i]));
                f[i][1].setText(String.valueOf(def.max[i]));
            }
        });
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Limits n = new Limits();
            for (int i = 0; i < Limits.N; i++) {
                try {
                    n.min[i] = Integer.parseInt(f[i][0].getText().toString().trim());
                    n.max[i] = Integer.parseInt(f[i][1].getText().toString().trim());
                } catch (NumberFormatException ex) {
                    Toast.makeText(this, Limits.NAMES[i] + ": enter whole numbers", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean needsPositive = i <= Limits.COLS;
                boolean noNeg = i == Limits.ITOP || i == Limits.IBOT;
                if (n.min[i] >= n.max[i] || (needsPositive && n.min[i] < 1) || (noNeg && n.min[i] < 0)
                        || ((i == Limits.NW || i == Limits.NH) && n.min[i] < 10) || (needsPositive && n.max[i] > 200)) {
                    Toast.makeText(this, Limits.NAMES[i] + ": min must be below max and in a sensible range", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            lim = n;
            lim.save(this);
            cfg.rows = lim.clamp(Limits.ROWS, cfg.rows);
            cfg.cols = lim.clamp(Limits.COLS, cfg.cols);
            cfg.notifW = lim.clamp(Limits.NW, cfg.notifW);
            cfg.notifH = lim.clamp(Limits.NH, cfg.notifH);
            cfg.notifDx = lim.clamp(Limits.NDX, cfg.notifDx);
            cfg.notifDy = lim.clamp(Limits.NDY, cfg.notifDy);
            cfg.insetTop = lim.clamp(Limits.ITOP, cfg.insetTop);
            cfg.insetBottom = lim.clamp(Limits.IBOT, cfg.insetBottom);
            cfg.clamp();
            ConfigStore.saveLayout(this, cfg);
            dlg.dismiss();
            recreate();
        });
    }

    private void runRoot(final String cmd, final String okMsg) {
        new Thread(() -> {
            boolean ok = cmd.startsWith("cmd appwidget grantbind") ? RootUtil.grantBind() : RootUtil.run(cmd);
            runOnUiThread(() -> Toast.makeText(this, ok ? okMsg : "Root command failed (is root granted to Lock Editor?)",
                    Toast.LENGTH_SHORT).show());
        }).start();
    }

    // ---------- GridEditorView.Host ----------
    @Override public Drawable preview(String p) {
        Drawable dr = previews.get(p);
        if (dr == null) {
            AppWidgetProviderInfo i = infos.get(p);
            if (i != null) {
                try { dr = i.loadPreviewImage(this, 0); } catch (Throwable ignored) { }
                if (dr == null) try { dr = i.loadIcon(this, 0); } catch (Throwable ignored) { }
            }
            if (dr != null) previews.put(p, dr);
        }
        return dr;
    }

    @Override public String label(String p) {
        String l = labels.get(p);
        if (l == null) {
            AppWidgetProviderInfo i = infos.get(p);
            l = i != null ? i.loadLabel(getPackageManager()) : p;
            labels.put(p, l);
        }
        return l;
    }

    @Override public Bitmap snapshot(String uid) { return snaps != null ? snaps.get(uid) : null; }
    @Override public void onChanged() { }
    @Override public void onSelected(Config.Widget w) { }
}
