package com.lockeditor.rs118;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared model: used by the editor app and by the SystemUI hook. */
public class Config {
    public static final String AUTHORITY = "com.lockeditor.rs118.config";
    public static final String URI = "content://" + AUTHORITY + "/config";
    public static final String URI_SNAP_REQ  = URI + "/snap";                           // app -> SystemUI: "take snapshots"
    public static final String URI_SNAP_FILE = "content://" + AUTHORITY + "/snapfile";  // SystemUI writes <uid> here
    public static final String URI_SNAP_DONE = "content://" + AUTHORITY + "/done";      // SystemUI -> app: "new files"

    public static final float MIN_SPAN = 0.25f;

    public int rows = 8, cols = 4;
    public boolean hideClock, hideDate, hideNotifs, hideShortcuts, debug;
    public boolean hideLockIcon, hideHints, hideCarrier, hideStatusBar;
    public int insetTop, insetBottom; // dp, shrinks the widget area
    public int notifW = 100, notifH = 100; // % size of the lock screen notification list
    public int notifDx, notifDy;           // dp offset of the notification list
    public int notifMode;                  // 0 = stretch (scale the list), 1 = reflow (resize the list, notifications keep their size)
    public int notifMax;                   // most notifications shown on the lock screen, 0 = stock behaviour
    /** List order is the layer order: the last widget is drawn on top. */
    public final List<Widget> widgets = new ArrayList<>();
    public final List<Integer> orphans = new ArrayList<>(); // widget ids SystemUI should free

    public static class Widget {
        public String uid, provider; // provider = ComponentName.flattenToString()
        public float row, col, rowSpan = 1, colSpan = 1; // in grid cells, fractions allowed
        public int id = -1;          // appWidgetId allocated by SystemUI's host (runtime state)
        public boolean configured;   // config activity already launched (runtime state)
    }

    public void clamp() {
        for (Widget w : widgets) {
            w.colSpan = Math.max(MIN_SPAN, Math.min(w.colSpan, cols));
            w.rowSpan = Math.max(MIN_SPAN, Math.min(w.rowSpan, rows));
            w.col = Math.max(0, Math.min(w.col, cols - w.colSpan));
            w.row = Math.max(0, Math.min(w.row, rows - w.rowSpan));
        }
    }

    public int[] findFree(float rs, float cs) {
        for (int r = 0; r + rs <= rows; r++)
            for (int c = 0; c + cs <= cols; c++) {
                boolean ok = true;
                for (Widget w : widgets)
                    if (r < w.row + w.rowSpan && r + rs > w.row && c < w.col + w.colSpan && c + cs > w.col) {
                        ok = false;
                        break;
                    }
                if (ok) return new int[]{r, c};
            }
        return new int[]{0, 0};
    }

    /** 1, 2.5, 0.25 ... without trailing zeros. */
    public static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 0.005f) return String.valueOf(Math.round(v));
        String s = String.format(Locale.US, "%.2f", v);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static double r3(float v) { return Math.round(v * 1000f) / 1000.0; }

    public JSONObject toJson(boolean runtime) {
        JSONObject o = new JSONObject();
        try {
            o.put("rows", rows).put("cols", cols)
                    .put("hideClock", hideClock).put("hideDate", hideDate)
                    .put("hideNotifs", hideNotifs).put("hideShortcuts", hideShortcuts)
                    .put("hideLockIcon", hideLockIcon).put("hideHints", hideHints)
                    .put("hideCarrier", hideCarrier).put("hideStatusBar", hideStatusBar)
                    .put("debug", debug).put("notifW", notifW).put("notifH", notifH).put("notifDx", notifDx).put("notifDy", notifDy)
                    .put("notifMode", notifMode).put("notifMax", notifMax)
                    .put("insetTop", insetTop).put("insetBottom", insetBottom);
            JSONArray a = new JSONArray();
            for (Widget w : widgets) {
                JSONObject j = new JSONObject();
                j.put("uid", w.uid).put("provider", w.provider)
                        .put("row", r3(w.row)).put("col", r3(w.col))
                        .put("rowSpan", r3(w.rowSpan)).put("colSpan", r3(w.colSpan));
                if (runtime) j.put("id", w.id).put("configured", w.configured);
                a.put(j);
            }
            o.put("widgets", a);
            JSONArray orph = new JSONArray();
            for (int i : orphans) orph.put(i);
            o.put("orphans", orph);
        } catch (JSONException ignored) { }
        return o;
    }

    public static Config fromJson(String s) {
        Config c = new Config();
        if (s == null) return c;
        try {
            JSONObject o = new JSONObject(s);
            c.rows = Math.max(1, o.optInt("rows", 8));
            c.cols = Math.max(1, o.optInt("cols", 4));
            c.hideClock = o.optBoolean("hideClock");
            c.hideDate = o.optBoolean("hideDate");
            c.hideNotifs = o.optBoolean("hideNotifs");
            c.hideShortcuts = o.optBoolean("hideShortcuts");
            c.hideLockIcon = o.optBoolean("hideLockIcon");
            c.hideHints = o.optBoolean("hideHints");
            c.hideCarrier = o.optBoolean("hideCarrier");
            c.hideStatusBar = o.optBoolean("hideStatusBar");
            c.debug = o.optBoolean("debug");
            c.notifW = Math.max(10, o.optInt("notifW", 100));
            c.notifH = Math.max(10, o.optInt("notifH", 100));
            c.notifDx = o.optInt("notifDx");
            c.notifDy = o.optInt("notifDy");
            c.notifMode = o.optInt("notifMode");
            c.notifMax = Math.max(0, o.optInt("notifMax"));
            c.insetTop = o.optInt("insetTop");
            c.insetBottom = o.optInt("insetBottom");
            JSONArray a = o.optJSONArray("widgets");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Widget w = new Widget();
                w.uid = j.optString("uid");
                w.provider = j.optString("provider");
                w.row = (float) j.optDouble("row", 0);
                w.col = (float) j.optDouble("col", 0);
                w.rowSpan = (float) j.optDouble("rowSpan", 1);
                w.colSpan = (float) j.optDouble("colSpan", 1);
                w.id = j.optInt("id", -1);
                w.configured = j.optBoolean("configured");
                c.widgets.add(w);
            }
            JSONArray orph = o.optJSONArray("orphans");
            if (orph != null) for (int i = 0; i < orph.length(); i++) c.orphans.add(orph.optInt(i));
            c.clamp();
        } catch (JSONException ignored) { }
        return c;
    }
}
