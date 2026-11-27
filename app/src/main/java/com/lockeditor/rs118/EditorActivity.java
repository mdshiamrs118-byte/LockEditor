package com.lockeditor.rs118;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Full-screen grid editor drawn over the current lock screen wallpaper. */
public class EditorActivity extends Activity implements GridEditorView.Host {
    private static final int PANEL = 0xE616161E, ACC = 0xFF3B5BDB;

    private Config cfg;
    private GridEditorView editor;
    private SnapSync snaps;
    private FrameLayout root;
    private LinearLayout inspector, toolbar;
    private float d;
    private int insetTop, insetBottom;
    private boolean inspectorTop;
    private final Map<String, AppWidgetProviderInfo> infos = new HashMap<>();
    private final Map<String, Drawable> previews = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // draw under the camera cutout so the editor matches the real screen geometry
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        d = getResources().getDisplayMetrics().density;
        cfg = ConfigStore.load(this);
        for (AppWidgetProviderInfo i : AppWidgetManager.getInstance(this).getInstalledProviders())
            infos.put(i.provider.flattenToString(), i);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        editor = new GridEditorView(this);
        editor.setHost(this);
        snaps = new SnapSync(this, 2500, () -> editor.invalidate());
        editor.setMarginDp(0);
        editor.setConfig(cfg);
        root.addView(editor, new FrameLayout.LayoutParams(-1, -1));

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = chip("‹  Back");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(chip(cfg.rows + " × " + cfg.cols + " grid"));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tlp.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(top, tlp);

        // inspector (selected widget)
        inspector = new LinearLayout(this);
        inspector.setOrientation(LinearLayout.VERTICAL);
        inspector.setPadding(dp(14), dp(10), dp(14), dp(10));
        inspector.setBackground(rounded(PANEL, 20));
        inspector.setVisibility(View.GONE);
        root.addView(inspector, new FrameLayout.LayoutParams(-1, -2));

        // bottom toolbar
        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        toolbar.setBackground(rounded(PANEL, 30));
        TextView add = pill("Add widget", true);
        add.setOnClickListener(v -> pickWidget());
        TextView conf = pill("Configure", false);
        conf.setOnClickListener(v -> configureSelected());
        TextView del = pill("Delete", false);
        del.setOnClickListener(v -> deleteSelected());
        toolbar.addView(add);
        toolbar.addView(conf);
        toolbar.addView(del);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(20);
        root.addView(toolbar, blp);

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insetTop = Math.max(ins.getSystemWindowInsetTop(),
                    ins.getDisplayCutout() != null ? ins.getDisplayCutout().getSafeInsetTop() : 0);
            insetBottom = ins.getSystemWindowInsetBottom();
            ((FrameLayout.LayoutParams) top.getLayoutParams()).topMargin = insetTop + dp(10);
            ((FrameLayout.LayoutParams) toolbar.getLayoutParams()).bottomMargin = insetBottom + dp(20);
            placeInspector();
            return ins;
        });

        setContentView(root);
        immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        snaps.start();
        // re-read on every resume: picks up a changed wallpaper
        WallpaperLoader.load(this, bmp -> { if (bmp != null) editor.setWallpaper(bmp); });
    }
    @Override protected void onPause() { snaps.stop(); super.onPause(); }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private int dp(int v) { return Math.round(v * d); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView chip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Color.WHITE);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(rounded(PANEL, 18));
        return t;
    }

    private TextView pill(String s, boolean primary) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(Color.WHITE);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setMinHeight(dp(46));
        if (primary) t.setBackground(rounded(ACC, 23));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void save() {
        ConfigStore.saveLayout(this, cfg);
        editor.invalidate();
    }

    // ---------- inspector ----------
    private void placeInspector() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) inspector.getLayoutParams();
        lp.setMargins(dp(12), 0, dp(12), 0);
        if (inspectorTop) { lp.gravity = Gravity.TOP; lp.topMargin = insetTop + dp(62); lp.bottomMargin = 0; }
        else { lp.gravity = Gravity.BOTTOM; lp.bottomMargin = insetBottom + dp(90); lp.topMargin = 0; }
        inspector.setLayoutParams(lp);
    }

    private void rebuildInspector() {
        inspector.removeAllViews();
        Config.Widget w = editor.getSelected();
        if (w == null) { inspector.setVisibility(View.GONE); return; }
        inspector.setVisibility(View.VISIBLE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(label(w.provider));
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView move = chip(inspectorTop ? "Move panel down" : "Move panel up");
        move.setBackground(rounded(0x33FFFFFF, 16));
        move.setOnClickListener(v -> { inspectorTop = !inspectorTop; placeInspector(); });
        head.addView(move);
        inspector.addView(head);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.addView(mini("Col", w.col + 1, 0), new LinearLayout.LayoutParams(0, -2, 1f));
        r1.addView(mini("Row", w.row + 1, 1), new LinearLayout.LayoutParams(0, -2, 1f));
        r2.addView(mini("Width", w.colSpan, 2), new LinearLayout.LayoutParams(0, -2, 1f));
        r2.addView(mini("Height", w.rowSpan, 3), new LinearLayout.LayoutParams(0, -2, 1f));
        inspector.addView(r1);
        inspector.addView(r2);
    }

    private View mini(String label, int value, final int field) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFB8BCC8);
        l.setTextSize(12);
        r.addView(l, new LinearLayout.LayoutParams(dp(46), -2));
        r.addView(step("−", field, -1));
        TextView v = new TextView(this);
        v.setText(String.valueOf(value));
        v.setTextColor(Color.WHITE);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        r.addView(v, new LinearLayout.LayoutParams(dp(30), -2));
        r.addView(step("+", field, 1));
        return r;
    }

    private TextView step(String s, final int field, final int delta) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(0x33FFFFFF, 18));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        t.setOnClickListener(v -> adjust(field, delta));
        return t;
    }

    private static int cl(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void adjust(int field, int delta) {
        Config.Widget w = editor.getSelected();
        if (w == null) return;
        switch (field) {
            case 0: w.col = cl(w.col + delta, 0, cfg.cols - w.colSpan); break;
            case 1: w.row = cl(w.row + delta, 0, cfg.rows - w.rowSpan); break;
            case 2: w.colSpan = cl(w.colSpan + delta, 1, cfg.cols - w.col); break;
            default: w.rowSpan = cl(w.rowSpan + delta, 1, cfg.rows - w.row);
        }
        save();
        rebuildInspector();
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
                LinearLayout r = new LinearLayout(EditorActivity.this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(12), dp(8), dp(12), dp(8));
                ImageView iv = new ImageView(EditorActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageDrawable(loadPreview(info));
                r.addView(iv, new LinearLayout.LayoutParams(dp(72), dp(56)));
                TextView t = new TextView(EditorActivity.this);
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
        save();
        rebuildInspector();
    }

    private void deleteSelected() {
        Config.Widget w = editor.getSelected();
        if (w == null) { Toast.makeText(this, "Select a widget first", Toast.LENGTH_SHORT).show(); return; }
        cfg.widgets.remove(w);
        editor.setSelected(null);
        save();
        rebuildInspector();
    }

    private void configureSelected() {
        Config.Widget w = editor.getSelected();
        if (w == null) { Toast.makeText(this, "Select a widget first", Toast.LENGTH_SHORT).show(); return; }
        int id = -1;
        for (Config.Widget f : ConfigStore.load(this).widgets) if (f.uid.equals(w.uid)) id = f.id;
        if (id <= 0) {
            Toast.makeText(this, "Widget not bound yet: lock and unlock once, then try again", Toast.LENGTH_LONG).show();
            return;
        }
        ConfigStore.setRuntime(this, w.uid, id, false); // SystemUI sees "not configured" and opens setup
        ConfigStore.notifyChanged(this);
        Toast.makeText(this, "Opening the widget's setup screen…", Toast.LENGTH_SHORT).show();
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

    @Override public Bitmap snapshot(String uid) { return snaps != null ? snaps.get(uid) : null; }
    @Override public void onChanged() { save(); rebuildInspector(); }
    @Override public void onSelected(Config.Widget w) { rebuildInspector(); }
}
