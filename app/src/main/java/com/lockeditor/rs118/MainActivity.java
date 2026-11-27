package com.lockeditor.rs118;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class MainActivity extends Activity implements GridEditorView.Host {
    private Config cfg;
    private GridEditorView editor;
    private float d;
    private final Map<String, AppWidgetProviderInfo> infos = new HashMap<>();
    private final Map<String, Drawable> previews = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        cfg = ConfigStore.load(this);
        for (AppWidgetProviderInfo i : AppWidgetManager.getInstance(this).getInstalledProviders())
            infos.put(i.provider.flattenToString(), i);
        buildUi();
    }

    private int dp(int v) { return Math.round(v * d); }

    private void save() {
        ConfigStore.saveLayout(this, cfg);
        if (editor != null) editor.invalidate();
    }

    private void updateAspect() {
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        float h = m.heightPixels - (cfg.insetTop + cfg.insetBottom) * m.density;
        if (editor != null) editor.setAspect(m.widthPixels / Math.max(h, 1f));
    }

    // ---------- UI ----------
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView title = new TextView(this);
        title.setText("Lock Editor");
        title.setTextSize(20);
        root.addView(title);

        root.addView(row(check("Hide clock", cfg.hideClock, v -> { cfg.hideClock = v; save(); }),
                check("Hide date", cfg.hideDate, v -> { cfg.hideDate = v; save(); })));
        root.addView(row(check("Hide notifications", cfg.hideNotifs, v -> { cfg.hideNotifs = v; save(); }),
                check("Hide shortcuts", cfg.hideShortcuts, v -> { cfg.hideShortcuts = v; save(); })));
        root.addView(row(stepper("Rows", cfg.rows, 1, 24, v -> { cfg.rows = v; cfg.clamp(); save(); }),
                stepper("Cols", cfg.cols, 1, 12, v -> { cfg.cols = v; cfg.clamp(); save(); })));
        root.addView(slider("Top inset", cfg.insetTop, 200, v -> { cfg.insetTop = v; updateAspect(); }));
        root.addView(slider("Bottom inset", cfg.insetBottom, 200, v -> { cfg.insetBottom = v; updateAspect(); }));

        editor = new GridEditorView(this);
        editor.setHost(this);
        editor.setConfig(cfg);
        updateAspect();
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(row(btn("Add widget", v -> pickWidget()), btn("Delete selected", v -> deleteSelected())));
        root.addView(row(btn("Configure selected", v -> configureSelected()), btn("Reload widgets", v -> ConfigStore.notifyChanged(this))));
        root.addView(row(btn("Grant bind (root)", v -> runRoot(
                        "cmd appwidget grantbind --package com.android.systemui", "Granted", () -> ConfigStore.notifyChanged(this))),
                btn("Restart SystemUI", v -> runRoot("killall com.android.systemui", "Restarting SystemUI", null))));

        TextView hint = new TextView(this);
        hint.setTextSize(11);
        hint.setText("Changes apply live. Widgets not showing? Grant bind, then Restart SystemUI. "
                + "Notification toggle takes effect after the next lock/unlock.");
        root.addView(hint);
        setContentView(root);
    }

    private LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private CheckBox check(String label, boolean init, Consumer<Boolean> cb) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(init);
        c.setOnCheckedChangeListener((v, on) -> cb.accept(on));
        return c;
    }

    private Button btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private View stepper(String label, int init, int min, int max, IntConsumer cb) {
        final int[] val = {init};
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        final TextView t = new TextView(this);
        t.setText(String.valueOf(init));
        t.setGravity(Gravity.CENTER);
        Button minus = btn("−", null), plus = btn("+", null);
        minus.setMinWidth(0); minus.setMinimumWidth(dp(44));
        plus.setMinWidth(0); plus.setMinimumWidth(dp(44));
        minus.setOnClickListener(v -> { if (val[0] > min) { val[0]--; t.setText(String.valueOf(val[0])); cb.accept(val[0]); } });
        plus.setOnClickListener(v -> { if (val[0] < max) { val[0]++; t.setText(String.valueOf(val[0])); cb.accept(val[0]); } });
        r.addView(l);
        r.addView(minus);
        r.addView(t, new LinearLayout.LayoutParams(dp(32), -2));
        r.addView(plus);
        return r;
    }

    private View slider(String label, int init, int max, IntConsumer cb) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        final TextView val = new TextView(this);
        val.setText(init + "dp");
        val.setTextSize(12);
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(init);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                val.setText(p + "dp");
                if (user) cb.accept(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { save(); }
        });
        r.addView(l, new LinearLayout.LayoutParams(dp(84), -2));
        r.addView(s, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(val, new LinearLayout.LayoutParams(dp(52), -2));
        return r;
    }

    // ---------- widgets ----------
    private Drawable loadPreview(AppWidgetProviderInfo i) {
        Drawable dr = null;
        try { dr = i.loadPreviewImage(this, 0); } catch (Throwable ignored) { }
        if (dr == null) try { dr = i.loadIcon(this, 0); } catch (Throwable ignored) { }
        return dr;
    }

    private void pickWidget() {
        final PackageManager pm = getPackageManager();
        final List<AppWidgetProviderInfo> list =
                new ArrayList<>(AppWidgetManager.getInstance(this).getInstalledProviders());
        Collections.sort(list, (a, b) -> a.loadLabel(pm).compareToIgnoreCase(b.loadLabel(pm)));
        ListView lv = new ListView(this);
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return list.size(); }
            @Override public Object getItem(int i) { return list.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                AppWidgetProviderInfo info = list.get(i);
                LinearLayout r = new LinearLayout(MainActivity.this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(12), dp(8), dp(12), dp(8));
                ImageView iv = new ImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageDrawable(loadPreview(info));
                r.addView(iv, new LinearLayout.LayoutParams(dp(72), dp(56)));
                TextView t = new TextView(MainActivity.this);
                t.setText(info.loadLabel(pm) + "\n" + info.provider.getPackageName() + " · "
                        + Math.round(info.minWidth / d) + "×" + Math.round(info.minHeight / d) + " dp");
                t.setPadding(dp(12), 0, 0, 0);
                r.addView(t);
                return r;
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Pick a widget")
                .setView(lv).setNegativeButton("Cancel", null).create();
        lv.setOnItemClickListener((p, v, i, id) -> { dlg.dismiss(); addWidget(list.get(i)); });
        dlg.show();
    }

    private void addWidget(AppWidgetProviderInfo info) {
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        float wdp = m.widthPixels / d;
        float hdp = m.heightPixels / d - cfg.insetTop - cfg.insetBottom;
        int cs = (int) Math.ceil((info.minWidth / d) / (wdp / cfg.cols));
        int rs = (int) Math.ceil((info.minHeight / d) / (hdp / cfg.rows));
        Config.Widget w = new Config.Widget();
        w.uid = UUID.randomUUID().toString();
        w.provider = info.provider.flattenToString();
        w.colSpan = Math.max(1, Math.min(cs, cfg.cols));
        w.rowSpan = Math.max(1, Math.min(rs, cfg.rows));
        int[] pos = cfg.findFree(w.rowSpan, w.colSpan);
        w.row = pos[0];
        w.col = pos[1];
        cfg.widgets.add(w);
        infos.put(w.provider, info);
        editor.setSelected(w);
        // make sure SystemUI may bind widgets, then save (which makes SystemUI pick the widget up)
        runRoot("cmd appwidget grantbind --package com.android.systemui", null, this::save);
    }

    private void deleteSelected() {
        Config.Widget w = editor.getSelected();
        if (w == null) { Toast.makeText(this, "Select a widget first", Toast.LENGTH_SHORT).show(); return; }
        cfg.widgets.remove(w);
        editor.setSelected(null);
        save();
    }

    private void configureSelected() {
        Config.Widget w = editor.getSelected();
        if (w == null) { Toast.makeText(this, "Select a widget first", Toast.LENGTH_SHORT).show(); return; }
        int id = -1;
        for (Config.Widget f : ConfigStore.load(this).widgets) if (f.uid.equals(w.uid)) id = f.id;
        if (id <= 0) {
            Toast.makeText(this, "Widget not bound yet: Grant bind, then Restart SystemUI", Toast.LENGTH_LONG).show();
            return;
        }
        ConfigStore.setRuntime(this, w.uid, id, false); // SystemUI sees "not configured" and launches setup
        ConfigStore.notifyChanged(this);
    }

    private void runRoot(final String cmd, final String okMsg, final Runnable after) {
        new Thread(() -> {
            String msg;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                int rc = p.waitFor();
                msg = rc == 0 ? okMsg : "Command failed (exit " + rc + ")";
            } catch (Exception ex) {
                msg = "Root unavailable: " + ex.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                if (m != null) Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
                if (after != null) after.run();
            });
        }).start();
    }

    // ---------- GridEditorView.Host ----------
    @Override public Drawable preview(String p) {
        Drawable dr = previews.get(p);
        if (dr == null) {
            AppWidgetProviderInfo i = infos.get(p);
            if (i != null) dr = loadPreview(i);
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

    @Override public void onChanged() { save(); }
    @Override public void onSelected(Config.Widget w) { }
}
