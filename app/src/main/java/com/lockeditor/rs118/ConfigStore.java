package com.lockeditor.rs118;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Module-side storage. Layout is written by the editor; runtime ids are written by SystemUI via the provider. */
public final class ConfigStore {
    private static final String PREFS = "lockeditor";

    private ConfigStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject runtime(Context c) {
        try {
            return new JSONObject(prefs(c).getString("runtime", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static synchronized Config load(Context c) {
        Config cfg = Config.fromJson(prefs(c).getString("layout", null));
        JSONObject rt = runtime(c);
        for (Config.Widget w : cfg.widgets) {
            JSONObject r = rt.optJSONObject(w.uid);
            if (r != null) {
                w.id = r.optInt("id", -1);
                w.configured = r.optBoolean("configured");
            }
        }
        return cfg;
    }

    public static synchronized void saveLayout(Context c, Config cfg) {
        prefs(c).edit().putString("layout", cfg.toJson(false).toString()).apply();
        notifyChanged(c);
    }

    public static void notifyChanged(Context c) {
        c.getContentResolver().notifyChange(Uri.parse(Config.URI), null);
    }

    public static synchronized void setRuntime(Context c, String uid, int id, boolean configured) {
        if (uid == null) return;
        JSONObject rt = runtime(c);
        try {
            if (id <= 0) rt.remove(uid);
            else rt.put(uid, new JSONObject().put("id", id).put("configured", configured));
        } catch (JSONException e) {
            return;
        }
        prefs(c).edit().putString("runtime", rt.toString()).apply();
    }

    /** Ids of widgets that were removed in the editor; SystemUI frees them. */
    public static synchronized List<Integer> popOrphans(Context c, Config cfg) {
        JSONObject rt = runtime(c);
        Set<String> live = new HashSet<>();
        for (Config.Widget w : cfg.widgets) live.add(w.uid);
        List<Integer> ids = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        Iterator<String> it = rt.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (live.contains(k)) continue;
            JSONObject r = rt.optJSONObject(k);
            if (r != null && r.optInt("id", -1) > 0) ids.add(r.optInt("id"));
            dead.add(k);
        }
        if (!dead.isEmpty()) {
            for (String k : dead) rt.remove(k);
            prefs(c).edit().putString("runtime", rt.toString()).apply();
        }
        return ids;
    }
}
