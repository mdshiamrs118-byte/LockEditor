package com.lockeditor.rs118;

import android.appwidget.AppWidgetManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Checks that root, the LSPosed hooks, the widget host and the wallpaper source all work. */
public class SetupActivity extends BaseActivity {
    private static final int OK = 0, WARN = 1, FAIL = 2, UNKNOWN = 3;
    private static final int C_OK = 0xFF2E9E5B, C_WARN = 0xFFE0A100, C_FAIL = 0xFFD64545, C_UNKNOWN = 0xFF8A93A6;

    private static final class Check {
        final String title;
        final int state;
        final String detail;
        String fixLabel;
        Runnable fix;
        Check(String title, int state, String detail) { this.title = title; this.state = state; this.detail = detail; }
        Check fix(String label, Runnable r) { fixLabel = label; fix = r; return this; }
    }

    private LinearLayout listBox;
    private TextView summary;

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

        TextView title = text("Setup check", 28, ui.txt, true);
        title.setPadding(dp(6), 0, 0, dp(4));
        root.addView(title);
        summary = text("Checking…", 13, ui.sub, false);
        summary.setPadding(dp(6), 0, dp(6), dp(12));
        root.addView(summary);

        listBox = card(null);
        listBox.setPadding(dp(CARD_PAD), dp(6), dp(CARD_PAD), dp(6));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.bottomMargin = dp(GAP);
        root.addView(listBox, llp);

        TextView again = button("Run again", true, v -> runChecks());
        root.addView(again, new LinearLayout.LayoutParams(-1, dp(46)));
        TextView note = text("Some checks only get a result after the lock screen has been shown once, "
                + "or after a widget has been added. Lock and unlock the phone, then run the check again.", 12, ui.sub, false);
        note.setPadding(dp(6), dp(12), dp(6), 0);
        root.addView(note);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        runChecks();
    }

    private static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 90) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        if (s < 172800) return (s / 3600) + " h ago";
        return (s / 86400) + " days ago";
    }

    private static String val(JSONObject f, String k) {
        JSONObject o = f.optJSONObject(k);
        return o == null ? null : o.optString("v", "");
    }

    private static long time(JSONObject f, String k) {
        JSONObject o = f.optJSONObject(k);
        return o == null ? 0 : o.optLong("t", 0);
    }

    private void runChecks() {
        summary.setText("Checking…");
        new Thread(() -> {
            final List<Check> out = new ArrayList<>();
            JSONObject f = ConfigStore.flags(this);
            Config cfg = ConfigStore.load(this);

            // 1 root
            boolean root = RootUtil.run("true");
            out.add(root ? new Check("Root access", OK, "Granted to Lock Editor.")
                    : new Check("Root access", FAIL, "Not granted. Open your root manager and allow Lock Editor. "
                    + "Needed for the wallpaper preview, widget permission and restarting SystemUI."));

            // 2 SystemUI hook
            String hook = val(f, "hook");
            if (hook != null) {
                out.add(new Check("SystemUI hook", OK, "SystemUI last contacted the module " + ago(time(f, "hook")) + "."));
            } else {
                out.add(new Check("SystemUI hook", FAIL, "SystemUI has never contacted the module. In LSPosed enable "
                        + "Lock Editor with the System UI scope, then restart SystemUI.")
                        .fix("Restart SystemUI", () -> rootAction("killall com.android.systemui", "Restarting SystemUI")));
            }

            // 3 keyguard view
            String sv = val(f, "statusview");
            out.add(sv != null ? new Check("Lock screen found", OK, "The keyguard view was found " + ago(time(f, "statusview")) + ".")
                    : new Check("Lock screen found", hook != null ? WARN : UNKNOWN,
                    "Not seen yet. Lock the phone and wake it once."));

            // 4 layer
            String layer = val(f, "layer");
            out.add(layer == null ? new Check("Widget layer", UNKNOWN, "Not attached yet (needs the lock screen to be shown).")
                    : "ok".equals(layer) ? new Check("Widget layer", OK, "The widget layer is attached to the lock screen.")
                    : new Check("Widget layer", FAIL, "Could not attach: " + layer));

            // 5 host
            String host = val(f, "host");
            out.add(host == null ? new Check("Widget host", UNKNOWN, "Not started yet.")
                    : "started".equals(host) ? new Check("Widget host", OK, "SystemUI is hosting widgets.")
                    : new Check("Widget host", FAIL, "Could not start the widget host: " + host));

            // 6 bind permission
            String bind = val(f, "bind");
            if ("ok".equals(bind)) {
                out.add(new Check("Widget permission", OK, "SystemUI is allowed to bind widgets."));
            } else if ("denied".equals(bind)) {
                out.add(new Check("Widget permission", FAIL, "SystemUI may not bind widgets. Enable the System Framework "
                        + "scope in LSPosed and reboot, or grant it with root.")
                        .fix("Grant with root", () -> rootAction("cmd appwidget grantbind --package com.android.systemui", "Granted")));
            } else {
                out.add(new Check("Widget permission", cfg.widgets.isEmpty() ? UNKNOWN : WARN,
                        cfg.widgets.isEmpty() ? "Add a widget to test this."
                                : "No result yet. Lock and unlock the phone."));
            }

            // 7 wallpaper
            String ws = WallpaperLoader.lastSource;
            out.add(ws == null ? new Check("Wallpaper preview", UNKNOWN, "Not loaded yet. Open the editor once.")
                    : ws.startsWith("root") ? new Check("Wallpaper preview", OK, "Read from " + ws + ".")
                    : new Check("Wallpaper preview", WARN, "Using the fallback (" + ws + "). Root access gives the exact lock screen wallpaper."));

            // 8 stock elements
            String stock = val(f, "stock");
            out.add(stock == null ? new Check("Stock elements", UNKNOWN, "None detected yet (lock the phone once).")
                    : new Check("Stock elements", OK, "Hideable on this ROM: " + stock.replace(",", ", ") + "."));

            // 9 notification list
            String notif = val(f, "notif");
            out.add(notif == null ? new Check("Notification list", UNKNOWN, "Only checked when you resize or move it.")
                    : "ok".equals(notif) ? new Check("Notification list", OK, "The notification list view was found.")
                    : new Check("Notification list", WARN, "Not found, so its size and position sliders have no effect."));

            // 10 widget providers
            int n = AppWidgetManager.getInstance(this).getInstalledProviders().size();
            out.add(new Check("Widgets available", n > 0 ? OK : WARN, n + " widget" + (n == 1 ? "" : "s") + " installed on this phone."));

            runOnUiThread(() -> show(out));
        }).start();
    }

    private void rootAction(final String cmd, final String okMsg) {
        new Thread(() -> {
            boolean ok = cmd.startsWith("cmd appwidget grantbind") ? RootUtil.grantBind() : RootUtil.run(cmd);
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? okMsg : "Root command failed", Toast.LENGTH_SHORT).show();
                listBox.postDelayed(this::runChecks, 4000);
            });
        }).start();
    }

    private void show(List<Check> checks) {
        listBox.removeAllViews();
        int fails = 0, warns = 0;
        for (int i = 0; i < checks.size(); i++) {
            Check c = checks.get(i);
            if (c.state == FAIL) fails++;
            else if (c.state == WARN) warns++;
            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(ui.line);
                listBox.addView(line, new LinearLayout.LayoutParams(-1, 1));
            }
            listBox.addView(row(c));
        }
        if (fails > 0) {
            summary.setText(fails + (fails == 1 ? " problem" : " problems") + " found" + (warns > 0 ? ", " + warns + " to look at." : "."));
            summary.setTextColor(C_FAIL);
        } else if (warns > 0) {
            summary.setText(warns + (warns == 1 ? " thing" : " things") + " to look at.");
            summary.setTextColor(C_WARN);
        } else {
            summary.setText("Everything that can be checked looks good.");
            summary.setTextColor(C_OK);
        }
    }

    private View row(Check c) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(10), 0, dp(10));
        int color = c.state == OK ? C_OK : c.state == WARN ? C_WARN : c.state == FAIL ? C_FAIL : C_UNKNOWN;
        String glyph = c.state == OK ? "✓" : c.state == WARN ? "!" : c.state == FAIL ? "✕" : "–";
        TextView dot = text(glyph, 14, Color.WHITE, true);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(rounded(color, 14));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(28), dp(28));
        dlp.rightMargin = dp(12);
        dlp.topMargin = dp(2);
        r.addView(dot, dlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(c.title, 14, ui.txt, true));
        TextView detail = text(c.detail, 12, ui.sub, false);
        detail.setPadding(0, dp(2), 0, 0);
        col.addView(detail);
        if (c.fix != null) {
            final Runnable fix = c.fix;
            TextView b = button(c.fixLabel, true, v -> fix.run());
            b.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, dp(34));
            blp.topMargin = dp(8);
            col.addView(b, blp);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }
}
