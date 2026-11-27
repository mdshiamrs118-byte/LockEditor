package com.lockeditor.rs118;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** User-adjustable min/max for every slider and for rows/columns (Tools > Advanced). */
public final class Limits {
    public static final int ROWS = 0, COLS = 1, NW = 2, NH = 3, NDX = 4, NDY = 5, ITOP = 6, IBOT = 7, N = 8;
    public static final String[] KEYS = {"rows", "cols", "notifW", "notifH", "notifDx", "notifDy", "insetTop", "insetBottom"};
    public static final String[] NAMES = {"Rows", "Columns", "Notification width (%)", "Notification height (%)",
            "Notification left / right (dp)", "Notification up / down (dp)", "Top inset (dp)", "Bottom inset (dp)"};
    private static final int[] DEF_MIN = {1, 1, 30, 30, -300, -400, 0, 0};
    private static final int[] DEF_MAX = {24, 12, 150, 150, 300, 400, 200, 200};

    public final int[] min = DEF_MIN.clone();
    public final int[] max = DEF_MAX.clone();

    public void reset() {
        System.arraycopy(DEF_MIN, 0, min, 0, N);
        System.arraycopy(DEF_MAX, 0, max, 0, N);
    }

    public int clamp(int i, int v) { return Math.max(min[i], Math.min(max[i], v)); }

    public static Limits load(Context c) {
        Limits l = new Limits();
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences("lockeditor_ui", Context.MODE_PRIVATE).getString("limits", "{}"));
            for (int i = 0; i < N; i++) {
                JSONArray a = o.optJSONArray(KEYS[i]);
                if (a != null && a.length() == 2 && a.getInt(0) < a.getInt(1)) {
                    l.min[i] = a.getInt(0);
                    l.max[i] = a.getInt(1);
                }
            }
        } catch (JSONException ignored) { }
        return l;
    }

    public void save(Context c) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < N; i++) o.put(KEYS[i], new JSONArray().put(min[i]).put(max[i]));
        } catch (JSONException ignored) { }
        c.getSharedPreferences("lockeditor_ui", Context.MODE_PRIVATE).edit().putString("limits", o.toString()).apply();
    }
}
