package com.lockeditor.rs118;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class MainActivity extends Activity implements GridEditorView.Host {
    private static final int BG = 0xFFF1F3F8, ACC = 0xFF3B5BDB, TXT = 0xFF1B1F29, SUB = 0xFF4A5262;

    private Config cfg;
    private Limits lim;
    private float d;
    private GridEditorView preview;
    private SnapSync snaps;
    private TextView gridInfo, widgetInfo;
    private boolean built;
    private final Map<String, AppWidgetProviderInfo> infos = new HashMap<>();
    private final Map<String, Drawable> previews = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
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
        if (built) { // widgets may have changed in the editor
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

    private int dp(int v) { return Math.round(v * d); }

    private void save() {
        ConfigStore.saveLayout(this, cfg);
        if (preview != null) preview.invalidate();
        refreshInfo();
    }

    private void updateAspect() {
        if (preview != null) preview.invalidate(); // insets changed: the grid area is recomputed on draw
    }

    private void refreshInfo() {
        if (gridInfo == null) return;
        gridInfo.setText("Rows  " + cfg.rows + "\nColumns  " + cfg.cols);
        StringBuilder sb = new StringBuilder(cfg.widgets.size() + (cfg.widgets.size() == 1 ? " widget" : " widgets"));
        for (int i = 0; i < Math.min(3, cfg.widgets.size()); i++) sb.append("\n").append(label(cfg.widgets.get(i).provider));
        if (cfg.widgets.size() > 3) sb.append("\n+").append(cfg.widgets.size() - 3).append(" more");
        widgetInfo.setText(sb.toString());
    }

    // ---------- building blocks ----------
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void add(LinearLayout parent, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        parent.addView(v, lp);
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout card(String heading) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(8));
        c.setBackground(rounded(Color.WHITE, 20));
        if (heading != null) {
            TextView h = text(heading, 13, SUB, true);
            h.setPadding(0, 0, 0, dp(6));
            c.addView(h);
        }
        return c;
    }

    private LinearLayout rowBase() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(48));
        return r;
    }

    private View switchRow(String label, boolean init, Consumer<Boolean> cb) {
        LinearLayout r = rowBase();
        TextView t = text(label, 15, TXT, false);
        Switch sw = new Switch(this);
        sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACC, Color.WHITE}));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{0x883B5BDB, 0xFFC9CED8}));
        sw.setChecked(init);
        sw.setOnCheckedChangeListener((v, on) -> cb.accept(on));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(sw);
        return r;
    }

    private View tapRow(String label, View.OnClickListener l) {
        LinearLayout r = rowBase();
        r.addView(text(label, 15, TXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(text("›", 20, SUB, false));
        r.setOnClickListener(l);
        return r;
    }

    private TextView circleBtn(String s, View.OnClickListener l) {
        TextView t = text(s, 20, ACC, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(0xFFE6EAF4, 22));
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return t;
    }

    private View stepperRow(String label, int init, int min, int max, IntConsumer cb) {
        final int[] val = {Math.max(min, Math.min(max, init))};
        LinearLayout r = rowBase();
        final TextView v = text(String.valueOf(val[0]), 16, TXT, true);
        v.setGravity(Gravity.CENTER);
        r.addView(text(label, 15, TXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(circleBtn("−", x -> { if (val[0] > min) { val[0]--; v.setText(String.valueOf(val[0])); cb.accept(val[0]); } }));
        r.addView(v, new LinearLayout.LayoutParams(dp(44), -2));
        r.addView(circleBtn("+", x -> { if (val[0] < max) { val[0]++; v.setText(String.valueOf(val[0])); cb.accept(val[0]); } }));
        return r;
    }

    private View slider(String label, int init, int min, int max, String unit, IntConsumer cb) {
        int start = Math.max(min, Math.min(max, init));
        LinearLayout r = rowBase();
        r.setMinimumHeight(dp(44));
        final TextView val = text(start + unit, 13, SUB, false);
        val.setGravity(Gravity.END);
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(start - min);
        s.setProgressTintList(ColorStateList.valueOf(ACC));
        s.setThumbTintList(ColorStateList.valueOf(ACC));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                val.setText((p + min) + unit);
                if (user) cb.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { save(); }
        });
        r.addView(text(label, 14, TXT, false), new LinearLayout.LayoutParams(dp(92), -2));
        r.addView(s, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(val, new LinearLayout.LayoutParams(dp(56), -2));
        return r;
    }

    // ---------- screen ----------
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(16));
        scroll.addView(root);

        TextView title = text("Lock Editor", 30, TXT, true);
        title.setPadding(dp(4), 0, 0, dp(14));
        root.addView(title);

        // preview card
        LinearLayout pc = card(null);
        pc.setOrientation(LinearLayout.HORIZONTAL);
        pc.setPadding(dp(16), dp(16), dp(16), dp(16));
        preview = new GridEditorView(this);
        preview.setHost(this);
        preview.setLight(true);
        preview.setInteractive(false);
        preview.setMarginDp(0);
        preview.setCornerDp(18);
        preview.setConfig(cfg);
        updateAspect();
        pc.addView(preview, new LinearLayout.LayoutParams(dp(172), dp(372)));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(16), 0, 0, 0);
        side.addView(text("Grid", 13, SUB, true));
        gridInfo = text("", 15, TXT, true);
        gridInfo.setPadding(0, dp(4), 0, dp(16));
        side.addView(gridInfo);
        side.addView(text("Widgets", 13, SUB, true));
        widgetInfo = text("", 14, SUB, false);
        widgetInfo.setPadding(0, dp(4), 0, 0);
        side.addView(widgetInfo);
        pc.addView(side, new LinearLayout.LayoutParams(0, -2, 1f));
        add(root, pc);

        TextView edit = text("Edit", 16, Color.WHITE, true);
        edit.setGravity(Gravity.CENTER);
        edit.setBackground(rounded(ACC, 14));
        edit.setOnClickListener(v -> startActivity(new Intent(this, EditorActivity.class)));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, dp(52));
        elp.bottomMargin = dp(14);
        root.addView(edit, elp);

        // grid & area
        LinearLayout gc = card("Grid and area");
        gc.addView(stepperRow("Rows", cfg.rows, lim.min[Limits.ROWS], lim.max[Limits.ROWS], v -> { cfg.rows = v; cfg.clamp(); save(); }));
        gc.addView(stepperRow("Columns", cfg.cols, lim.min[Limits.COLS], lim.max[Limits.COLS], v -> { cfg.cols = v; cfg.clamp(); save(); }));
        gc.addView(slider("Top inset", cfg.insetTop, lim.min[Limits.ITOP], lim.max[Limits.ITOP], "dp", v -> { cfg.insetTop = v; updateAspect(); }));
        gc.addView(slider("Bottom inset", cfg.insetBottom, lim.min[Limits.IBOT], lim.max[Limits.IBOT], "dp", v -> { cfg.insetBottom = v; updateAspect(); }));
        add(root, gc);

        // hide stock elements
        LinearLayout hc = card("Hide stock elements");
        hc.addView(switchRow("Clock", cfg.hideClock, v -> { cfg.hideClock = v; save(); }));
        hc.addView(switchRow("Date", cfg.hideDate, v -> { cfg.hideDate = v; save(); }));
        hc.addView(switchRow("Notifications", cfg.hideNotifs, v -> { cfg.hideNotifs = v; save(); }));
        hc.addView(switchRow("Bottom shortcuts", cfg.hideShortcuts, v -> { cfg.hideShortcuts = v; save(); }));
        add(root, hc);

        // notification list
        LinearLayout nc = card("Notification list");
        nc.addView(slider("Width", cfg.notifW, lim.min[Limits.NW], lim.max[Limits.NW], "%", v -> cfg.notifW = v));
        nc.addView(slider("Height", cfg.notifH, lim.min[Limits.NH], lim.max[Limits.NH], "%", v -> cfg.notifH = v));
        nc.addView(slider("Left / right", cfg.notifDx, lim.min[Limits.NDX], lim.max[Limits.NDX], "dp", v -> cfg.notifDx = v));
        nc.addView(slider("Up / down", cfg.notifDy, lim.min[Limits.NDY], lim.max[Limits.NDY], "dp", v -> cfg.notifDy = v));
        nc.addView(tapRow("Reset notification list", v -> {
            cfg.notifW = 100; cfg.notifH = 100; cfg.notifDx = 0; cfg.notifDy = 0;
            save();
            recreate();
        }));
        add(root, nc);

        // tools
        LinearLayout tc = card("Tools");
        tc.addView(tapRow("Status log", v -> showLog()));
        tc.addView(switchRow("Debug marker", cfg.debug, v -> { cfg.debug = v; save(); }));
        tc.addView(tapRow("Restart SystemUI", v -> runRoot("killall com.android.systemui", "Restarting SystemUI")));
        tc.addView(tapRow("Grant widget access (root)", v -> runRoot("cmd appwidget grantbind --package com.android.systemui", "Done")));
        tc.addView(tapRow("Advanced", v -> showAdvanced()));
        add(root, tc);

        setContentView(scroll);
        built = true;
        refreshInfo();
    }

    // ---------- dialogs ----------
    private void showLog() {
        String log = ConfigStore.readLog(this);
        if (log.isEmpty()) log = "(empty)\n\nThe SystemUI hook has not reported anything.\n"
                + "Check: module enabled in LSPosed with scopes System UI and System Framework, phone rebooted, then lock and unlock once.";
        TextView t = text(log, 11, TXT, false);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("SystemUI status").setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear", (x, w) -> ConfigStore.clearLog(this)).show();
    }

    private void showAdvanced() {
        final EditText[][] f = new EditText[Limits.N][2];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView hint = text("Set the minimum and maximum of every slider, and of rows and columns.", 12, SUB, false);
        hint.setPadding(0, 0, 0, dp(8));
        box.addView(hint);
        for (int i = 0; i < Limits.N; i++) {
            box.addView(text(Limits.NAMES[i], 13, TXT, true));
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
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
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
