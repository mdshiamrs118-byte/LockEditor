package com.lockeditor.rs118.xposed;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.lockeditor.rs118.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Adds the overlay next to the keyguard status view (same parent, drawn on top). */
    void attachTo(ViewGroup statusView) {
        ViewParent p = statusView.getParent();
        if (!(p instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) p;
        if (overlay.getParent() != parent) {
            if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
            parent.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        }
        overlay.setVisibility(statusView.getVisibility());
        overlay.setAlpha(statusView.getAlpha());
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
                    XposedBridge.log(KeyguardHooks.TAG + "provider not installed: " + w.provider);
                    continue;
                }
                int id = w.id;
                boolean fresh = false;
                if (id <= 0 || mgr.getAppWidgetInfo(id) == null) {
                    id = host.allocateAppWidgetId();
                    boolean ok = false;
                    try { ok = mgr.bindAppWidgetIdIfAllowed(id, info.provider); } catch (Throwable t) { XposedBridge.log(t); }
                    if (!ok) {
                        try { host.deleteAppWidgetId(id); } catch (Throwable ignored) { }
                        XposedBridge.log(KeyguardHooks.TAG + "bind denied for " + w.provider
                                + " (run: cmd appwidget grantbind --package com.android.systemui)");
                        continue;
                    }
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

                if (needCfg) launchConfigure(info, id);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        overlay.requestLayout();
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
            return;
        } catch (Throwable t) {
            XposedBridge.log(KeyguardHooks.TAG + "intent sender failed, falling back: " + t);
        }
        try {
            ctx.startActivity(new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(info.configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            XposedBridge.log(t);
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
        private final Map<View, int[]> lastSize = new WeakHashMap<>();

        Overlay(Context c) { super(c); }

        void setConfig(Config c) {
            cfg = c;
            float d = getResources().getDisplayMetrics().density;
            setPadding(0, Math.round(c.insetTop * d), 0, Math.round(c.insetBottom * d));
            requestLayout();
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

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int pl = getPaddingLeft(), pt = getPaddingTop();
            int iw = r - l - pl - getPaddingRight(), ih = b - t - pt - getPaddingBottom();
            float d = getResources().getDisplayMetrics().density;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                Rect rc = cell(c, iw, ih);
                if (rc == null) { c.layout(0, 0, 0, 0); continue; }
                c.layout(pl + rc.left, pt + rc.top, pl + rc.right, pt + rc.bottom);
                if (c instanceof AppWidgetHostView) {
                    int wdp = Math.round(rc.width() / d), hdp = Math.round(rc.height() / d);
                    int[] last = lastSize.get(c);
                    if (last == null || last[0] != wdp || last[1] != hdp) {
                        lastSize.put(c, new int[]{wdp, hdp});
                        try {
                            ((AppWidgetHostView) c).updateAppWidgetSize(null, wdp, hdp, wdp, hdp);
                        } catch (Throwable ignored) { }
                    }
                }
            }
        }
    }
}
