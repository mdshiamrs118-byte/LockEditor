package com.lockeditor.rs118.xposed;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.lockeditor.rs118.Config;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/** Hosts AppWidgets inside SystemUI and lays them out on the user's grid. */
final class LockWidgetLayer {
    static final int HOST_ID = 0x4C4B21;
    private static final Uri URI = Uri.parse(Config.URI);

    final Overlay overlay;
    private final Context ctx;
    private final AppWidgetHost host;
    private final AppWidgetManager mgr;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, Integer> lastHash = new HashMap<>();
    private final Runnable snapRun = new Runnable() { @Override public void run() { snapshot(); } };
    private boolean lastShown;

    private static final class Entry {
        final String uid;
        final int id;
        final AppWidgetHostView view;
        Entry(String uid, int id, AppWidgetHostView view) { this.uid = uid; this.id = id; this.view = view; }
    }

    LockWidgetLayer(Context viewCtx) {
        ctx = viewCtx;
        Context app = viewCtx.getApplicationContext();
        if (app == null) app = viewCtx;
        mgr = AppWidgetManager.getInstance(app);
        host = new AppWidgetHost(app, HOST_ID);
        overlay = new Overlay(viewCtx);
        try {
            host.startListening();
            KeyguardHooks.dlog("widget host started");
            KeyguardHooks.flag("host", "started");
        } catch (Throwable t) {
            KeyguardHooks.dlog("host.startListening failed: " + t);
            KeyguardHooks.flag("host", "failed: " + t);
        }
    }

    /** Adds the overlay next to the keyguard status view (same parent, drawn on top). */
    void attachTo(ViewGroup statusView) {
        ViewParent p = statusView.getParent();
        if (!(p instanceof ViewGroup)) {
            KeyguardHooks.dlog("status view has no ViewGroup parent, cannot attach layer");
            KeyguardHooks.flag("layer", "the status view has no parent view");
            return;
        }
        ViewGroup parent = (ViewGroup) p;
        if (overlay.getParent() != parent) {
            if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
            parent.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
            KeyguardHooks.dlog("layer attached to " + parent.getClass().getName() + " ("
                    + parent.getWidth() + "x" + parent.getHeight() + ", children=" + parent.getChildCount() + ")");
            KeyguardHooks.flag("layer", "ok");
        }
        sync(statusView);
    }

    /** Called every frame: layer follows the keyguard status view. */
    void sync(View sv) {
        int vis = sv.getVisibility();
        float a = sv.getAlpha();
        if (overlay.getVisibility() != vis) overlay.setVisibility(vis);
        if (overlay.getAlpha() != a) overlay.setAlpha(a);
        boolean shown = vis == View.VISIBLE && a > 0.05f;
        KeyguardHooks.applyNotifTransform(sv, shown);
        KeyguardHooks.reportNotifBounds(sv);
        if (shown != lastShown) {
            lastShown = shown;
            KeyguardHooks.dlog("lock screen " + (shown ? "SHOWN" : "hidden") + " (alpha=" + a + ")");
            if (shown) KeyguardHooks.maybeRefresh(sv.getContext());
        }
    }

    boolean owns(View v) {
        View x = v;
        while (true) {
            if (x == overlay) return true;
            ViewParent p = x.getParent();
            if (!(p instanceof View)) return false;
            x = (View) p;
        }
    }

    private AppWidgetProviderInfo findInfo(String flat) {
        ComponentName cn = ComponentName.unflattenFromString(flat);
        if (cn == null) return null;
        for (AppWidgetProviderInfo i : mgr.getInstalledProviders()) if (i.provider.equals(cn)) return i;
        return null;
    }

    void update(Config c) {
        KeyguardHooks.dlog("config loaded: " + c.widgets.size() + " widget(s), grid " + c.rows + "x" + c.cols);
        overlay.setConfig(c);
        for (int oid : c.orphans) {
            try { host.deleteAppWidgetId(oid); } catch (Throwable ignored) { }
        }
        Set<String> want = new HashSet<>();
        for (Config.Widget w : c.widgets) want.add(w.uid);

        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            if (!want.contains(e.uid)) {
                overlay.removeView(e.view);
                try { host.deleteAppWidgetId(e.id); } catch (Throwable ignored) { }
                synchronized (lastHash) { lastHash.remove(e.uid); }
                it.remove();
            }
        }

        for (Config.Widget w : c.widgets) {
            Entry e = entries.get(w.uid);
            if (e != null) {
                e.view.setTag(w);
                w.id = e.id;
                AppWidgetProviderInfo ci = findInfo(w.provider);
                if (ci != null && ci.configure != null && !w.configured) {
                    w.configured = true;
                    report(w.uid, e.id, true);
                    launchConfigure(ci, e.id);
                }
                continue;
            }
            try {
                AppWidgetProviderInfo info = findInfo(w.provider);
                if (info == null) {
                    KeyguardHooks.dlog("provider not installed/visible: " + w.provider);
                    continue;
                }
                int id = w.id;
                boolean fresh = false;
                if (id <= 0 || mgr.getAppWidgetInfo(id) == null) {
                    id = host.allocateAppWidgetId();
                    boolean ok = false;
                    try { ok = mgr.bindAppWidgetIdIfAllowed(id, info.provider); } catch (Throwable t) { KeyguardHooks.dlog("bind threw: " + t); }
                    if (!ok) {
                        try { host.deleteAppWidgetId(id); } catch (Throwable ignored) { }
                        KeyguardHooks.dlog("BIND DENIED for " + w.provider + " - enable the System Framework scope for Lock Editor in LSPosed, then reboot");
                        KeyguardHooks.flag("bind", "denied");
                        requestGrant();
                        continue;
                    }
                    KeyguardHooks.dlog("bound " + w.provider + " as id " + id);
                    fresh = true;
                    w.configured = false;
                }
                boolean needCfg = info.configure != null && !w.configured;
                if (fresh || needCfg) {
                    w.configured = w.configured || needCfg;
                    report(w.uid, id, w.configured);
                }
                w.id = id;

                AppWidgetHostView hv = host.createView(ctx, id, info);
                hv.setTag(w);
                overlay.addView(hv);
                entries.put(w.uid, new Entry(w.uid, id, hv));
                KeyguardHooks.flag("bind", "ok");
                KeyguardHooks.dlog("widget view created (id " + id + ", cell " + w.col + "," + w.row + " span " + w.colSpan + "x" + w.rowSpan + ")");

                if (needCfg) launchConfigure(info, id);
            } catch (Throwable t) {
                KeyguardHooks.dlog("widget setup failed: " + t);
            }
        }
        applyOrder(c);
        overlay.requestLayout();
        scheduleSnapshot(1500);
    }

    /** Makes the overlay's child order match the layer order from the editor (last widget = on top). */
    private void applyOrder(Config c) {
        List<View> want = new ArrayList<>();
        for (Config.Widget w : c.widgets) {
            Entry e = entries.get(w.uid);
            if (e != null) want.add(e.view);
        }
        List<View> have = new ArrayList<>();
        for (int i = 0; i < overlay.getChildCount(); i++) {
            View v = overlay.getChildAt(i);
            if (want.contains(v)) have.add(v);
        }
        if (have.equals(want)) return;
        for (View v : want) v.bringToFront();
    }

    // ---------------- snapshots for the editor / home preview ----------------
    void scheduleSnapshot(long delayMs) {
        KeyguardHooks.MAIN.removeCallbacks(snapRun);
        KeyguardHooks.MAIN.postDelayed(snapRun, delayMs);
    }

    private Bitmap render(View v) {
        if (!overlay.forceLayout(v)) return null;
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return null;
        float s = Math.min(1f, 1024f / Math.max(w, h));
        Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.scale(s, s);
        v.draw(cv);
        return b;
    }

    /** Renders every hosted widget (works even while the lock screen is hidden) and ships changed ones to the app. */
    private void snapshot() {
        KeyguardHooks.reportNotifBounds(overlay);
        final Map<String, Bitmap> shots = new HashMap<>();
        for (Entry e : entries.values()) {
            try {
                Bitmap b = render(e.view);
                if (b != null) shots.put(e.uid, b);
            } catch (Throwable t) {
                KeyguardHooks.dlog("snapshot failed: " + t);
            }
        }
        if (shots.isEmpty()) return;
        KeyguardHooks.IO.execute(new Runnable() {
            @Override public void run() {
                boolean changed = false;
                for (Map.Entry<String, Bitmap> s : shots.entrySet()) {
                    try {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        s.getValue().compress(Bitmap.CompressFormat.PNG, 100, bo);
                        byte[] data = bo.toByteArray();
                        int hash = Arrays.hashCode(data);
                        synchronized (lastHash) {
                            Integer old = lastHash.get(s.getKey());
                            if (old != null && old == hash) continue;
                        }
                        OutputStream os = ctx.getContentResolver().openOutputStream(Uri.parse(Config.URI_SNAP_FILE + "/" + s.getKey()));
                        if (os == null) continue;
                        try { os.write(data); } finally { os.close(); }
                        synchronized (lastHash) { lastHash.put(s.getKey(), hash); }
                        changed = true;
                    } catch (Throwable t) {
                        KeyguardHooks.dlog("snapshot write failed: " + t);
                    } finally {
                        s.getValue().recycle();
                    }
                }
                if (changed) {
                    try { ctx.getContentResolver().call(URI, "snap_done", null, null); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private void requestGrant() {
        KeyguardHooks.IO.execute(new Runnable() {
            @Override public void run() {
                try { ctx.getContentResolver().call(URI, "bind_denied", null, null); } catch (Throwable ignored) { }
            }
        });
    }

    /** Launch the widget's setup screen the same way a launcher does (system intent sender). */
    private void launchConfigure(AppWidgetProviderInfo info, int id) {
        try {
            Field f = AppWidgetHost.class.getDeclaredField("sService");
            f.setAccessible(true);
            Object svc = f.get(null);
            Method m = svc.getClass().getMethod("createAppWidgetConfigIntentSender", String.class, int.class, int.class);
            IntentSender is = (IntentSender) m.invoke(svc, ctx.getPackageName(), id, 0);
            ctx.startIntentSender(is, null, Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0);
            KeyguardHooks.dlog("launched widget setup screen for id " + id);
            return;
        } catch (Throwable t) {
            KeyguardHooks.dlog("intent sender failed, falling back: " + t);
        }
        try {
            ctx.startActivity(new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(info.configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            KeyguardHooks.dlog("could not launch setup screen: " + t);
        }
    }

    private void report(final String uid, int id, boolean configured) {
        final Bundle b = new Bundle();
        b.putInt("id", id);
        b.putBoolean("configured", configured);
        KeyguardHooks.IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    ctx.getContentResolver().call(URI, "set_id", uid, b);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    /** Full-size layer; children are placed on the rows x cols grid taken from their Widget tag. */
    static final class Overlay extends FrameLayout {
        private Config cfg;
        private boolean loggedLayout;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<View, int[]> lastSize = new WeakHashMap<>();

        Overlay(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void setConfig(Config c) {
            cfg = c;
            float d = getResources().getDisplayMetrics().density;
            setPadding(0, Math.round(c.insetTop * d), 0, Math.round(c.insetBottom * d));
            requestLayout();
            invalidate();
        }

        private Rect cell(View c, int iw, int ih) {
            if (cfg == null || !(c.getTag() instanceof Config.Widget)) return null;
            Config.Widget w = (Config.Widget) c.getTag();
            float cw = iw / (float) cfg.cols, ch = ih / (float) cfg.rows;
            return new Rect(Math.round(w.col * cw), Math.round(w.row * ch),
                    Math.round((w.col + w.colSpan) * cw), Math.round((w.row + w.rowSpan) * ch));
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            int iw = w - getPaddingLeft() - getPaddingRight(), ih = h - getPaddingTop() - getPaddingBottom();
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                Rect r = cell(c, iw, ih);
                if (r == null) continue;
                c.measure(MeasureSpec.makeMeasureSpec(Math.max(0, r.width()), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(0, r.height()), MeasureSpec.EXACTLY));
            }
        }

        private void notifySize(View c, Rect rc) {
            if (!(c instanceof AppWidgetHostView)) return;
            float d = getResources().getDisplayMetrics().density;
            int wdp = Math.round(rc.width() / d), hdp = Math.round(rc.height() / d);
            int[] last = lastSize.get(c);
            if (last == null || last[0] != wdp || last[1] != hdp) {
                lastSize.put(c, new int[]{wdp, hdp});
                try {
                    ((AppWidgetHostView) c).updateAppWidgetSize(null, wdp, hdp, wdp, hdp);
                } catch (Throwable ignored) { }
            }
        }

        /** Lays a child out in its grid cell even while the layer is hidden (needed for snapshots). */
        boolean forceLayout(View c) {
            int ow = getWidth(), oh = getHeight();
            if (ow <= 0 || oh <= 0) {
                android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                ow = m.widthPixels;
                oh = m.heightPixels;
            }
            int pl = getPaddingLeft(), pt = getPaddingTop();
            Rect rc = cell(c, ow - pl - getPaddingRight(), oh - pt - getPaddingBottom());
            if (rc == null || rc.width() <= 0 || rc.height() <= 0) return false;
            if (c.getWidth() != rc.width() || c.getHeight() != rc.height()) {
                c.measure(MeasureSpec.makeMeasureSpec(rc.width(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rc.height(), MeasureSpec.EXACTLY));
                c.layout(pl + rc.left, pt + rc.top, pl + rc.right, pt + rc.bottom);
                notifySize(c, rc);
            }
            return true;
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (!loggedLayout && r - l > 0 && b - t > 0) {
                loggedLayout = true;
                KeyguardHooks.dlog("layer laid out " + (r - l) + "x" + (b - t) + " children=" + getChildCount());
            }
            int pl = getPaddingLeft(), pt = getPaddingTop();
            int iw = r - l - pl - getPaddingRight(), ih = b - t - pt - getPaddingBottom();
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                Rect rc = cell(c, iw, ih);
                if (rc == null) { c.layout(0, 0, 0, 0); continue; }
                c.layout(pl + rc.left, pt + rc.top, pl + rc.right, pt + rc.bottom);
                notifySize(c, rc);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (cfg != null && cfg.debug) {
                float d = getResources().getDisplayMetrics().density;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3 * d);
                paint.setColor(0xFFFF00FF);
                canvas.drawRect(2 * d, getPaddingTop() + 2 * d, getWidth() - 2 * d, getHeight() - getPaddingBottom() - 2 * d, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(14 * d);
                canvas.drawText("Lock Editor layer - " + cfg.widgets.size() + " widget(s)", 10 * d, getPaddingTop() + 22 * d, paint);
            }
        }
    }
}
