package com.lockeditor.rs118;

import android.app.Activity;
import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the current lock screen wallpaper (root first, WallpaperManager as fallback).
 * Of the lock and home wallpaper files the most recently modified one wins (a stale lock-only
 * file must not hide a newer wallpaper). The result is cached only while the files are unchanged.
 * The choice is written to the app's Status log.
 */
public final class WallpaperLoader {
    public interface Cb { void done(Bitmap b); }

    private static final String DIR = "/data/system/users/0/";
    private static final String[] FILES = {"wallpaper_lock", "wallpaper", "wallpaper_lock_orig", "wallpaper_orig"};
    /** Where the last wallpaper came from, for the Setup check. */
    public static volatile String lastSource;
    private static Bitmap cached;
    private static String cachedSig;

    private WallpaperLoader() { }

    public static void load(final Activity a, final Cb cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b;
                String sig = signature();
                synchronized (WallpaperLoader.class) {
                    if (cached != null && sig != null && sig.equals(cachedSig)) {
                        b = cached;
                    } else {
                        b = fromRoot(a, sig);
                        if (b == null) {
                            b = fromManager(a);
                            lastSource = "WallpaperManager, root read failed";
                            ConfigStore.appendLog(a, "wallpaper: root read failed, used WallpaperManager (files: " + sig + ")");
                            sig = null; // no signature -> never cache
                        }
                        cached = b;
                        cachedSig = sig;
                    }
                }
                final Bitmap fb = b;
                a.runOnUiThread(new Runnable() { @Override public void run() { cb.done(fb); } });
            }
        }).start();
    }

    /** "path:mtime:size" lines of every wallpaper file that exists, or null if root is not available. */
    private static String signature() {
        try {
            StringBuilder cmd = new StringBuilder("stat -c '%n:%Y:%s'");
            for (String n : FILES) cmd.append(' ').append(DIR).append(n);
            cmd.append(" 2>/dev/null");
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd.toString()});
            InputStream in = p.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            p.waitFor();
            String s = out.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, Long> mtimes(String sig) {
        Map<String, Long> m = new HashMap<>();
        if (sig == null) return m;
        for (String line : sig.split("\n")) {
            String[] p = line.trim().split(":");
            if (p.length < 3) continue;
            try {
                String path = p[0];
                m.put(path.substring(path.lastIndexOf('/') + 1), Long.parseLong(p[1]));
            } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    /** Newest of lock/home cropped file first, then the other one, then the uncropped originals. */
    private static List<String> order(Map<String, Long> mt) {
        long lock = mt.containsKey("wallpaper_lock") ? mt.get("wallpaper_lock") : -1;
        long home = mt.containsKey("wallpaper") ? mt.get("wallpaper") : -1;
        List<String> o = new ArrayList<>();
        if (home > lock) { o.add("wallpaper"); o.add("wallpaper_lock"); }
        else { o.add("wallpaper_lock"); o.add("wallpaper"); }
        o.add("wallpaper_lock_orig");
        o.add("wallpaper_orig");
        return o;
    }

    private static Bitmap fromRoot(Activity a, String sig) {
        Map<String, Long> mt = mtimes(sig);
        for (String name : order(mt)) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "cat " + DIR + name + " 2>/dev/null"});
                InputStream in = p.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                p.waitFor();
                byte[] data = out.toByteArray();
                if (data.length < 2000) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int max = Math.max(o.outWidth, o.outHeight), s = 1;
                while (max / s > 1800) s *= 2;
                int w = o.outWidth, h = o.outHeight;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (b != null) {
                    lastSource = "root: " + name + " " + w + "x" + h;
                    ConfigStore.appendLog(a, "wallpaper: using " + name + " (" + w + "x" + h + "); files: "
                            + (sig == null ? "?" : sig.replace(DIR, "").replace("\n", " | ")));
                    return b;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Bitmap fromManager(Activity a) {
        try {
            Drawable d = WallpaperManager.getInstance(a).getDrawable();
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        } catch (Throwable ignored) { }
        return null;
    }
}
