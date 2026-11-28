package com.lockeditor.rs118.xposed;

import android.app.PendingIntent;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.lockeditor.rs118.Config;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** All hooks that run inside com.android.systemui. */
final class KeyguardHooks {
    static final String TAG = "[LockEditor] ";
    static final Uri URI = Uri.parse(Config.URI);
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final Map<View, String> hidden = Collections.synchronizedMap(new WeakHashMap<View, String>());
    private static final Map<View, Integer> orig = Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    private static final List<String> pendingLog = new ArrayList<>();
    private static volatile Context logCtx;

    static volatile Config cfg = new Config();
    static volatile LockWidgetLayer layer;
    private static WeakReference<ViewTreeObserver.OnPreDrawListener> lastListener = new WeakReference<>(null);
    private static WeakReference<View> lastListenerView = new WeakReference<>(null);
    private static ClassLoader sysuiLoader;
    private static boolean applying, observing;
    private static WeakReference<View> notifRef = new WeakReference<>(null);
    private static boolean notifApplied, notifMissingLogged;
    private static long lastRefresh;

    private static final String[] SHORTCUT_IDS = {
            "left_button", "camera_button", "right_button", "start_button",
            "end_button", "wallet_button", "controls_button"};

    private KeyguardHooks() { }

    // ---------------- diagnostics (shown in the app's "Status log") ----------------
    static void dlog(String s) {
        XposedBridge.log(TAG + s);
        synchronized (pendingLog) { pendingLog.add(s); }
        flushLog();
    }

    private static void flushLog() {
        final Context c = logCtx;
        if (c == null) return;
        IO.execute(new Runnable() {
            @Override public void run() {
                List<String> copy;
                synchronized (pendingLog) {
                    copy = new ArrayList<>(pendingLog);
                    pendingLog.clear();
                }
                for (String s : copy) {
                    try { c.getContentResolver().call(URI, "log", s, null); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static void setLogCtx(Context c) {
        if (logCtx != null || c == null) return;
        Context a = c.getApplicationContext();
        logCtx = a != null ? a : c;
        flushLog();
    }

    static void install(final ClassLoader cl) {
        sysuiLoader = cl;
        dlog("hooks installed in SystemUI");

        // 1) Find keyguard views when they attach
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    View v = (View) p.thisObject;
                    if (logCtx == null) setLogCtx(v.getContext());
                    String n = v.getClass().getName();
                    if (n.equals("com.android.keyguard.KeyguardStatusView")) onStatusView((ViewGroup) v);
                    else if (n.equals("com.android.systemui.statusbar.phone.KeyguardBottomAreaView")) onBottomArea((ViewGroup) v);
                    else detectStock(v, n);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });

        // 2) Keep hidden views hidden even when SystemUI resets their visibility
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                String cat = hidden.get(v);
                if (cat != null) {
                    if (!applying) orig.put(v, (Integer) p.args[0]);
                    if (isHidden(cat)) p.args[0] = View.GONE;
                }
            }
        });

        // 3) Lock screen notifications
        try {
            Class<?> c = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl", cl);
            if (c != null) {
                XposedBridge.hookAllMethods(c, "shouldShowOnKeyguard", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (cfg.hideNotifs) p.setResult(Boolean.FALSE);
                    }
                });
            } else {
                dlog("NotificationLockscreenUserManagerImpl not found (notification toggle won't work)");
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        // 4) Widget taps on the lock screen: dismiss keyguard then launch
        try {
            Class<?> och = XposedHelpers.findClassIfExists("android.widget.RemoteViews$OnClickHandler", cl);
            if (och != null) {
                XposedBridge.hookAllMethods(och, "onClickHandler", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LockWidgetLayer l = layer;
                            if (l == null || p.args.length < 3) return;
                            if (!(p.args[0] instanceof View) || !(p.args[1] instanceof PendingIntent)) return;
                            if (p.args[2] != null) return;
                            if (!l.owns((View) p.args[0])) return;
                            Class<?> dep = XposedHelpers.findClass("com.android.systemui.Dependency", sysuiLoader);
                            Class<?> as = XposedHelpers.findClass("com.android.systemui.plugins.ActivityStarter", sysuiLoader);
                            Object starter = XposedHelpers.callStaticMethod(dep, "get", as);
                            XposedHelpers.callMethod(starter, "postStartActivityDismissingKeyguard", p.args[1]);
                            p.setResult(Boolean.TRUE);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    // ---------------- status flags for the app's Setup check ----------------
    static void flag(final String key, final String value) {
        final Context c = logCtx;
        if (c == null) return;
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    Bundle b = new Bundle();
                    b.putString("v", value);
                    c.getContentResolver().call(URI, "flag", key, b);
                } catch (Throwable ignored) { }
            }
        });
    }

    // ---------------- extra stock elements (lock icon, hints, carrier, status bar) ----------------
    private static final java.util.Set<String> stockFound = new java.util.TreeSet<>();

    private static void detectStock(View v, String cls) {
        String cat = null;
        if (cls.endsWith(".LockIcon")) cat = "lockicon";
        else if (cls.endsWith(".KeyguardStatusBarView")) cat = "statusbar";
        else {
            int id = v.getId();
            if (id != View.NO_ID && (id >>> 24) == 0x7f) { // SystemUI's own resources only
                try {
                    switch (v.getResources().getResourceEntryName(id)) {
                        case "lock_icon": cat = "lockicon"; break;
                        case "keyguard_indication_area": cat = "hint"; break;
                        case "keyguard_carrier_text": cat = "carrier"; break;
                        default: break;
                    }
                } catch (Throwable ignored) { }
            }
        }
        if (cat == null) return;
        register(v, cat);
        boolean first;
        String joined;
        synchronized (stockFound) {
            first = stockFound.add(cat);
            joined = android.text.TextUtils.join(",", stockFound);
        }
        if (first) {
            dlog("stock element found: " + cat + " (" + cls + ")");
            flag("stock", joined);
        }
        applyHidden();
    }

    // ---------------- view discovery ----------------
    private static void onStatusView(final ViewGroup sv) {
        Context ctx = sv.getContext();
        dlog("KeyguardStatusView attached (parent=" + (sv.getParent() == null ? "null" : sv.getParent().getClass().getName()) + ")");
        flag("statusview", "ok");
        registerClockViews(sv);
        if (layer == null) layer = new LockWidgetLayer(ctx);
        final LockWidgetLayer l = layer;
        sv.post(new Runnable() {
            @Override public void run() { l.attachTo(sv); }
        });

        // follow the status view's visibility/alpha every frame (animators bypass setAlpha/setVisibility hooks)
        try {
            View old = lastListenerView.get();
            ViewTreeObserver.OnPreDrawListener ol = lastListener.get();
            if (old != null && ol != null) {
                try { old.getViewTreeObserver().removeOnPreDrawListener(ol); } catch (Throwable ignored) { }
            }
            ViewTreeObserver.OnPreDrawListener nl = new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    LockWidgetLayer ly = layer;
                    if (ly != null) ly.sync(sv);
                    return true;
                }
            };
            sv.getViewTreeObserver().addOnPreDrawListener(nl);
            lastListener = new WeakReference<>(nl);
            lastListenerView = new WeakReference<View>(sv);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        observe(ctx);
        refresh(ctx);
        applyHidden();
    }

    private static void onBottomArea(ViewGroup ba) {
        for (String n : SHORTCUT_IDS) {
            int id = ba.getResources().getIdentifier(n, "id", "com.android.systemui");
            if (id == 0) continue;
            View v = ba.findViewById(id);
            if (v != null) register(v, "shortcut");
        }
        applyHidden();
    }

    private static void registerClockViews(ViewGroup sv) {
        List<View> switches = new ArrayList<>(), slices = new ArrayList<>();
        collect(sv, "com.android.keyguard.KeyguardClockSwitch", switches);
        collect(sv, "com.android.keyguard.KeyguardSliceView", slices);
        View slice = slices.isEmpty() ? null : slices.get(0);
        for (View s : switches) {
            if (!(s instanceof ViewGroup)) continue;
            ViewGroup g = (ViewGroup) s;
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (slice == null || !isAncestorOrSelf(ch, slice)) register(ch, "clock");
            }
        }
        if (slice != null) register(slice, "date");
        dlog("clock switches=" + switches.size() + " slice=" + (slice != null));
    }

    private static void collect(View v, String cls, List<View> out) {
        if (v.getClass().getName().equals(cls)) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), cls, out);
        }
    }

    private static boolean isAncestorOrSelf(View anc, View v) {
        for (Object x = v; x instanceof View; x = ((View) x).getParent()) if (x == anc) return true;
        return false;
    }

    // ---------------- notification list size / position ----------------
    /** Called every frame from the layer sync. Scales/moves the lock screen notification list. */
    static void applyNotifTransform(View statusView, boolean shown) {
        Config c = cfg;
        boolean custom = c.notifW != 100 || c.notifH != 100 || c.notifDx != 0 || c.notifDy != 0;
        boolean want = shown && custom;
        if (!want && !notifApplied) return;
        View n = notifRef.get();
        if (n == null || !n.isAttachedToWindow()) {
            n = null;
            if (statusView.getParent() instanceof View) {
                List<View> found = new ArrayList<>();
                collect((View) statusView.getParent(),
                        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", found);
                if (!found.isEmpty()) n = found.get(0);
            }
            notifRef = new WeakReference<>(n);
            flag("notif", n == null ? "missing" : "ok");
            if (n == null) {
                if (!notifMissingLogged) {
                    notifMissingLogged = true;
                    dlog("notification list view not found (size/position sliders won't work)");
                }
                return;
            }
        }
        if (want) {
            float d = n.getResources().getDisplayMetrics().density;
            float sx = c.notifW / 100f, sy = c.notifH / 100f, tx = c.notifDx * d, ty = c.notifDy * d;
            n.setPivotX(n.getWidth() / 2f);
            n.setPivotY(0f);
            if (n.getScaleX() != sx) n.setScaleX(sx);
            if (n.getScaleY() != sy) n.setScaleY(sy);
            if (n.getTranslationX() != tx) n.setTranslationX(tx);
            if (n.getTranslationY() != ty) n.setTranslationY(ty);
            notifApplied = true;
        } else {
            n.setScaleX(1f);
            n.setScaleY(1f);
            n.setTranslationX(0f);
            n.setTranslationY(0f);
            notifApplied = false;
        }
    }

    // ---------------- hide logic ----------------
    private static boolean isHidden(String cat) {
        Config c = cfg;
        switch (cat) {
            case "clock": return c.hideClock;
            case "date": return c.hideDate;
            case "shortcut": return c.hideShortcuts;
            case "lockicon": return c.hideLockIcon;
            case "hint": return c.hideHints;
            case "carrier": return c.hideCarrier;
            case "statusbar": return c.hideStatusBar;
            default: return false;
        }
    }

    private static void register(View v, String cat) {
        synchronized (hidden) {
            if (hidden.containsKey(v)) return;
            orig.put(v, v.getVisibility());
            hidden.put(v, cat);
        }
    }

    static void applyHidden() {
        List<View> keys;
        synchronized (hidden) { keys = new ArrayList<>(hidden.keySet()); }
        applying = true;
        try {
            for (View v : keys) {
                String cat = hidden.get(v);
                if (cat == null) continue;
                Integer o = orig.get(v);
                int want = isHidden(cat) ? View.GONE : (o != null ? o : View.VISIBLE);
                if (v.getVisibility() != want) v.setVisibility(want);
            }
        } finally {
            applying = false;
        }
    }

    // ---------------- config sync ----------------
    private static void observe(final Context ctx) {
        if (observing) return;
        observing = true;
        ctx.getContentResolver().registerContentObserver(URI, true, new ContentObserver(MAIN) {
            @Override public void onChange(boolean selfChange) { onChange(selfChange, null); }
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (uri != null && "snap".equals(uri.getLastPathSegment())) {
                    LockWidgetLayer l = layer;
                    if (l != null) l.scheduleSnapshot(100);   // editor/preview asked for fresh renders
                } else {
                    refresh(ctx);
                }
            }
        });
    }

    static void maybeRefresh(Context ctx) {
        if (SystemClock.uptimeMillis() - lastRefresh > 5000) refresh(ctx);
    }

    static void refresh(final Context ctx) {
        lastRefresh = SystemClock.uptimeMillis();
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    Bundle b = ctx.getContentResolver().call(URI, "get", null, null);
                    if (b == null) {
                        dlog("config provider returned null (module process blocked?)");
                        return;
                    }
                    final Config n = Config.fromJson(b.getString("json"));
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            cfg = n;
                            applyHidden();
                            LockWidgetLayer l = layer;
                            if (l != null) l.update(n);
                        }
                    });
                } catch (Throwable t) {
                    dlog("config fetch failed: " + t);
                }
            }
        });
    }
}
