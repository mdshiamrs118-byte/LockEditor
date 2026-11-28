package com.lockeditor.rs118;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Named layout backups, kept as JSON files in the app's private storage (files/backups/<id>.json). */
public final class BackupStore {
    public static final class Backup {
        public String id, name, configJson;
        public long created;
        public int widgets, rows, cols;
    }

    private BackupStore() { }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "backups");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9-]{1,64}"); }

    public static List<Backup> list(Context c) {
        List<Backup> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (!n.endsWith(".json")) continue;
            try {
                out.add(parse(n.substring(0, n.length() - 5), new JSONObject(readAll(new FileInputStream(f)))));
            } catch (Exception ignored) { }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.created, a.created));
        return out;
    }

    public static Backup get(Context c, String id) {
        for (Backup b : list(c)) if (b.id.equals(id)) return b;
        return null;
    }

    public static Backup create(Context c, String name, Config cfg) {
        try {
            Backup b = new Backup();
            b.id = UUID.randomUUID().toString();
            b.name = cleanName(name);
            b.created = System.currentTimeMillis();
            b.configJson = cfg.toJson(false).toString();
            fill(b);
            write(c, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean rename(Context c, String id, String name) {
        Backup b = get(c, id);
        if (b == null) return false;
        b.name = cleanName(name);
        try { write(c, b); return true; } catch (Exception e) { return false; }
    }

    public static void delete(Context c, String id) {
        if (validId(id)) new File(dir(c), id + ".json").delete();
    }

    /** Applies a backup: replaces the current layout and settings. */
    public static void restore(Context c, Backup b) {
        ConfigStore.saveLayout(c, Config.fromJson(b.configJson));
    }

    /** The text written to an exported file (importable again). */
    public static String exportJson(Backup b) {
        try { return fileJson(b).toString(2); } catch (JSONException e) { return "{}"; }
    }

    /** Adds a backup from exported text. Returns null if it is not a Lock Editor backup. */
    public static Backup importJson(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cfg = o.optJSONObject("config");
            if (cfg == null || !(cfg.has("widgets") || cfg.has("rows"))) return null;
            Backup b = parse(UUID.randomUUID().toString(), o);
            if (b.created <= 0) b.created = System.currentTimeMillis();
            write(c, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- internals ----
    private static String cleanName(String n) {
        n = n == null ? "" : n.trim();
        if (n.isEmpty()) n = "Backup";
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    private static Backup parse(String id, JSONObject o) throws JSONException {
        Backup b = new Backup();
        b.id = id;
        b.name = cleanName(o.optString("name", "Backup"));
        b.created = o.optLong("created", 0);
        b.configJson = o.getJSONObject("config").toString();
        fill(b);
        return b;
    }

    private static void fill(Backup b) {
        Config c = Config.fromJson(b.configJson);
        b.widgets = c.widgets.size();
        b.rows = c.rows;
        b.cols = c.cols;
    }

    private static JSONObject fileJson(Backup b) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("app", "LockEditor");
        o.put("version", 1);
        o.put("name", b.name);
        o.put("created", b.created);
        o.put("config", new JSONObject(b.configJson));
        return o;
    }

    private static void write(Context c, Backup b) throws IOException, JSONException {
        if (!validId(b.id)) throw new IOException("bad id");
        try (FileOutputStream out = new FileOutputStream(new File(dir(c), b.id + ".json"))) {
            out.write(fileJson(b).toString().getBytes("UTF-8"));
        }
    }

    public static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > 4 * 1024 * 1024) throw new IOException("file too large");
            }
            return o.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
