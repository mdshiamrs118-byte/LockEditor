package com.lockeditor.rs118;

import android.app.Activity;
import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Loads the current lock screen wallpaper (root first, WallpaperManager as fallback). */
public final class WallpaperLoader {
    public interface Cb { void done(Bitmap b); }

    private static final String[] FILES = {"wallpaper_lock", "wallpaper_lock_orig", "wallpaper", "wallpaper_orig"};
    private static Bitmap cached;

    private WallpaperLoader() { }

    public static void load(final Activity a, final Cb cb) {
        if (cached != null) { cb.done(cached); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = fromRoot();
                if (b == null) b = fromManager(a);
                cached = b;
                final Bitmap fb = b;
                a.runOnUiThread(new Runnable() { @Override public void run() { cb.done(fb); } });
            }
        }).start();
    }

    private static Bitmap fromRoot() {
        for (String name : FILES) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "cat /data/system/users/0/" + name + " 2>/dev/null"});
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
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (b != null) return b;
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
