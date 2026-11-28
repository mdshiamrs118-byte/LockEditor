package com.lockeditor.rs118;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Full-screen grid editor drawn over the current lock screen wallpaper. */
public class EditorActivity extends Activity implements GridEditorView.Host {
    private static final int PANEL = 0xE616161E, ACC = 0xFF3B5BDB;
    private static final float[] SNAPS = {1f, 0.5f, 0.25f, 0f}; // 0 = free
    private static final int MAX_HISTORY = 60;

    private Config cfg;
    private GridEditorView editor;
    private SnapSync snaps;
    private SharedPreferences uiPrefs;
    private FrameLayout root;
    private LinearLayout inspector, toolbar, topBar;
    private TextView undoBtn, redoBtn, snapBtn, guideBtn, notifBtn;
    private float d;
    private int insetTop, insetBottom;
    private boolean inspectorTop;
    private float snap = 1f;
    private boolean guides = true;
    private boolean showNotif = true;
    private Limits limits;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rectPoll = new Runnable() {
        @Override public void run() {
            loadNotifRect();
            handler.postDelayed(this, 2000);
        }
    };
    private final ArrayDeque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private String cur; // layout state after the last committed change
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
        uiPrefs = getSharedPreferences("lockeditor_ui", MODE_PRIVATE);
        snap = uiPrefs.getFloat("snap", 1f);
        guides = uiPrefs.getBoolean("guides", true);
        showNotif = uiPrefs.getBoolean("showNotif", true);
        limits = Limits.load(this);
        cfg = ConfigStore.load(this);
        cur = state();
        for (AppWidgetProviderInfo i : AppWidgetManager.getInstance(this).getInstalledProviders())
            infos.put(i.provider.flattenToString(), i);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        editor = new GridEditorView(this);
        editor.setHost(this);
        snaps = new SnapSync(this, 2500, () -> editor.invalidate());
        editor.setMarginDp(0);
        editor.setConfig(cfg);
        editor.setSnap(snap);
        editor.setGuides(guides);
        editor.setLimits(limits);
        editor.setShowNotif(showNotif);
        root.addView(editor, new FrameLayout.LayoutParams(-1, -1));

        // top bar: row 1 = back / undo / redo / grid size, row 2 = snap + guides
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = chip("‹  Back");
        back.setOnClickListener(v -> finish());
        r1.addView(back);
        undoBtn = chip("↶");
        undoBtn.setOnClickListener(v -> doUndo());
        r1.addView(undoBtn, marginLp(dp(8)));
        redoBtn = chip("↷");
        redoBtn.setOnClickListener(v -> doRedo());
        r1.addView(redoBtn, marginLp(dp(6)));
        View spacer = new View(this);
        r1.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        r1.addView(chip(cfg.rows + " × " + cfg.cols));
        topBar.addView(r1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.END);
        notifBtn = chip("");
        notifBtn.setOnClickListener(v -> toggleNotif());
        r2.addView(notifBtn);
        snapBtn = chip("");
        snapBtn.setOnClickListener(v -> cycleSnap());
        r2.addView(snapBtn, marginLp(dp(6)));
        guideBtn = chip("");
        guideBtn.setOnClickListener(v -> toggleGuides());
        r2.addView(guideBtn, marginLp(dp(6)));
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.topMargin = dp(8);
        topBar.addView(r2, r2lp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tlp.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(topBar, tlp);
        refreshChips();

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
            ((FrameLayout.LayoutParams) topBar.getLayoutParams()).topMargin = insetTop + dp(10);
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
        handler.removeCallbacks(rectPoll);
        rectPoll.run();
        // re-read on every resume: picks up a changed wallpaper
        WallpaperLoader.load(this, bmp -> { if (bmp != null) editor.setWallpaper(bmp); });
    }

    @Override protected void onPause() { snaps.stop(); handler.removeCallbacks(rectPoll); super.onPause(); }

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

    private LinearLayout.LayoutParams marginLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = left;
        return lp;
    }

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
        t.setGravity(Gravity.CENTER);
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

    private TextView miniChip(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Color.WHITE);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        t.setBackground(rounded(0x33FFFFFF, 14));
        t.setOnClickListener(l);
        return t;
    }

    private void save() {
        ConfigStore.saveLayout(this, cfg);
        editor.invalidate();
    }

    // ---------- undo / redo ----------
    private String state() { return cfg.toJson(false).toString(); }

    /** Call after every layout change: records history, saves, refreshes the panel. */
    private void commit() {
        String now = state();
        if (!now.equals(cur)) {
            undo.push(cur);
            while (undo.size() > MAX_HISTORY) undo.removeLast();
            redo.clear();
            cur = now;
        }
        save();
        rebuildInspector();
        refreshChips();
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        redo.push(cur);
        cur = undo.pop();
        restore(cur);
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        undo.push(cur);
        cur = redo.pop();
        restore(cur);
    }

    private void restore(String s) {
        Config t = Config.fromJson(s);
        Config.Widget old = editor.getSelected();
        String selUid = old != null ? old.uid : null;
        cfg.widgets.clear();
        cfg.widgets.addAll(t.widgets);
        cfg.notifW = t.notifW;
        cfg.notifH = t.notifH;
        cfg.notifDx = t.notifDx;
        cfg.notifDy = t.notifDy;
        cfg.hideNotifs = t.hideNotifs;
        Config.Widget keep = null;
        for (Config.Widget w : cfg.widgets) if (w.uid.equals(selUid)) keep = w;
        editor.setSelected(keep);
        save();
        rebuildInspector();
        refreshChips();
    }

    private void refreshChips() {
        undoBtn.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        redoBtn.setAlpha(redo.isEmpty() ? 0.35f : 1f);
        snapBtn.setText("Snap: " + snapLabel(snap));
        notifBtn.setText("Notifs: " + (showNotif ? "on" : "off"));
        guideBtn.setText("Guides: " + (guides ? "on" : "off"));
    }

    private static String snapLabel(float s) {
        if (s == 1f) return "1";
        if (s == 0.5f) return "½";
        if (s == 0.25f) return "¼";
        return "Free";
    }

    private void cycleSnap() {
        int i = 0;
        for (int k = 0; k < SNAPS.length; k++) if (SNAPS[k] == snap) i = k;
        snap = SNAPS[(i + 1) % SNAPS.length];
        uiPrefs.edit().putFloat("snap", snap).apply();
        editor.setSnap(snap);
        refreshChips();
        rebuildInspector();
    }

    private void toggleGuides() {
        guides = !guides;
        uiPrefs.edit().putBoolean("guides", guides).apply();
        editor.setGuides(guides);
        refreshChips();
    }

    // ---------- inspector ----------
    private void placeInspector() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) inspector.getLayoutParams();
        lp.setMargins(dp(12), 0, dp(12), 0);
        if (inspectorTop) { lp.gravity = Gravity.TOP; lp.topMargin = insetTop + dp(104); lp.bottomMargin = 0; }
        else { lp.gravity = Gravity.BOTTOM; lp.bottomMargin = insetBottom + dp(90); lp.topMargin = 0; }
        inspector.setLayoutParams(lp);
    }

    private void rebuildInspector() {
        inspector.removeAllViews();
        if (editor.isNotifSelected()) { buildNotifInspector(); return; }
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

        // layers: the list order is the draw order, last = on top
        int idx = cfg.widgets.indexOf(w), n = cfg.widgets.size();
        LinearLayout lr = new LinearLayout(this);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        lr.setPadding(0, dp(6), 0, 0);
        TextView ll = new TextView(this);
        ll.setText("Layer " + (idx + 1) + "/" + n);
        ll.setTextColor(0xFFB8BCC8);
        ll.setTextSize(12);
        lr.addView(ll, new LinearLayout.LayoutParams(0, -2, 1f));
        lr.addView(miniChip("Back", v -> layer(0)), marginLp(dp(6)));
        lr.addView(miniChip("▼", v -> layer(1)), marginLp(dp(6)));
        lr.addView(miniChip("▲", v -> layer(2)), marginLp(dp(6)));
        lr.addView(miniChip("Front", v -> layer(3)), marginLp(dp(6)));
        inspector.addView(lr);
    }

    private void layer(int op) { // 0 = send to back, 1 = one down, 2 = one up, 3 = bring to front
        Config.Widget w = editor.getSelected();
        if (w == null) return;
        int i = cfg.widgets.indexOf(w), n = cfg.widgets.size(), j;
        switch (op) {
            case 0: j = 0; break;
            case 1: j = Math.max(0, i - 1); break;
            case 2: j = Math.min(n - 1, i + 1); break;
            default: j = n - 1;
        }
        if (i < 0 || j == i) return;
        cfg.widgets.remove(i);
        cfg.widgets.add(j, w);
        commit();
    }

    private String fmtPos(float v) { return Config.fmt(v); }

    private View mini(String label, float value, final int field) { return mini(label, value, field, 42); }

    private View mini(String label, float value, final int field, int labelDp) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFB8BCC8);
        l.setTextSize(12);
        r.addView(l, new LinearLayout.LayoutParams(dp(labelDp), -2));
        r.addView(step("−", field, -1));
        TextView v = new TextView(this);
        v.setText(fmtPos(value));
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        r.addView(v, new LinearLayout.LayoutParams(dp(40), -2));
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
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        t.setOnClickListener(v -> adjust(field, delta));
        return t;
    }

    private float stepSize() { return snap > 0 ? snap : 0.1f; }
    private float minSpan() { return snap > 0 ? snap : Config.MIN_SPAN; }
    private static float r3(float v) { return Math.round(v * 1000f) / 1000f; }
    private static float clf(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private void adjust(int field, int delta) {
        if (field >= 4) { adjustNotif(field, delta); return; }
        Config.Widget w = editor.getSelected();
        if (w == null) return;
        float s = delta * stepSize();
        switch (field) {
            case 0: w.col = clf(r3(w.col + s), 0, cfg.cols - w.colSpan); break;
            case 1: w.row = clf(r3(w.row + s), 0, cfg.rows - w.rowSpan); break;
            case 2: w.colSpan = clf(r3(w.colSpan + s), minSpan(), cfg.cols - w.col); break;
            default: w.rowSpan = clf(r3(w.rowSpan + s), minSpan(), cfg.rows - w.row);
        }
        commit();
    }

    // ---------- notification list ----------
    private void toggleNotif() {
        showNotif = !showNotif;
        uiPrefs.edit().putBoolean("showNotif", showNotif).apply();
        editor.setShowNotif(showNotif);
        refreshChips();
        rebuildInspector();
    }

    /** Where SystemUI says the stock notification list sits; until then the editor draws an estimate. */
    private void loadNotifRect() {
        JSONObject o = ConfigStore.flags(this).optJSONObject("notifrect");
        if (o == null) return;
        String[] p = o.optString("v", "").split(",");
        if (p.length != 6) return;
        try {
            float[] r = new float[6];
            for (int i = 0; i < 6; i++) r[i] = Float.parseFloat(p[i].trim());
            if (r[2] > r[0] && r[3] > r[1] && r[4] > 0 && r[5] > 0) editor.setNotifRect(r);
        } catch (NumberFormatException ignored) { }
    }

    private void buildNotifInspector() {
        inspector.setVisibility(View.VISIBLE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText("Notifications");
        name.setTextColor(0xFFFFC107);
        name.setTextSize(15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView move = chip(inspectorTop ? "Move panel down" : "Move panel up");
        move.setBackground(rounded(0x33FFFFFF, 16));
        move.setOnClickListener(v -> { inspectorTop = !inspectorTop; placeInspector(); });
        head.addView(move);
        inspector.addView(head);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.addView(mini("W %", cfg.notifW, 4, 36), new LinearLayout.LayoutParams(0, -2, 1f));
        r1.addView(mini("H %", cfg.notifH, 5, 36), new LinearLayout.LayoutParams(0, -2, 1f));
        r2.addView(mini("X dp", cfg.notifDx, 6, 36), new LinearLayout.LayoutParams(0, -2, 1f));
        r2.addView(mini("Y dp", cfg.notifDy, 7, 36), new LinearLayout.LayoutParams(0, -2, 1f));
        inspector.addView(r1);
        inspector.addView(r2);
        TextView cap = new TextView(this);
        cap.setText("Drag the box to move it, the corner handle to resize. W/H = size in %, X/Y = offset in dp.");
        cap.setTextColor(0xFFB8BCC8);
        cap.setTextSize(11);
        cap.setPadding(0, dp(4), 0, dp(6));
        inspector.addView(cap);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(miniChip("Reset", v -> {
            cfg.notifW = 100; cfg.notifH = 100; cfg.notifDx = 0; cfg.notifDy = 0;
            commit();
        }));
        row.addView(miniChip(cfg.hideNotifs ? "Notifications: hidden" : "Notifications: shown", v -> {
            cfg.hideNotifs = !cfg.hideNotifs;
            commit();
        }), marginLp(dp(8)));
        inspector.addView(row);
    }

    private void adjustNotif(int field, int delta) {
        switch (field) {
            case 4: cfg.notifW = limits.clamp(Limits.NW, cfg.notifW + delta * 5); break;
            case 5: cfg.notifH = limits.clamp(Limits.NH, cfg.notifH + delta * 5); break;
            case 6: cfg.notifDx = limits.clamp(Limits.NDX, cfg.notifDx + delta * 10); break;
            default: cfg.notifDy = limits.clamp(Limits.NDY, cfg.notifDy + delta * 10);
        }
        commit();
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
        commit();
    }

    private void deleteSelected() {
        Config.Widget w = editor.getSelected();
        if (w == null) { Toast.makeText(this, "Select a widget first", Toast.LENGTH_SHORT).show(); return; }
        cfg.widgets.remove(w);
        editor.setSelected(null);
        commit();
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
    @Override public void onChanged() { commit(); }
    @Override public void onSelected(Config.Widget w) { rebuildInspector(); }
}
