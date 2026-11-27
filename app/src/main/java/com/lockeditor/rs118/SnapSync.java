package com.lockeditor.rs118;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** While started: asks SystemUI for fresh widget snapshots every pollMs and serves them to the UI. */
public final class SnapSync {
    public interface Listener { void onSnapshots(); }

    private final Context ctx;
    private final Listener listener;
    private final long pollMs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private ContentObserver obs;
    private boolean on;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!on) return;
            ConfigStore.requestSnapshot(ctx);
            h.postDelayed(this, pollMs);
        }
    };

    public SnapSync(Context c, long pollMs, Listener l) {
        this.ctx = c;
        this.pollMs = pollMs;
        this.listener = l;
    }

    public void start() {
        if (on) return;
        on = true;
        invalidateAll();
        obs = new ContentObserver(h) {
            @Override public void onChange(boolean selfChange) {
                invalidateAll();
                listener.onSnapshots();
            }
        };
        ctx.getContentResolver().registerContentObserver(Uri.parse(Config.URI_SNAP_DONE), false, obs);
        h.post(poll);
        listener.onSnapshots();
    }

    public void stop() {
        on = false;
        h.removeCallbacks(poll);
        if (obs != null) {
            ctx.getContentResolver().unregisterContentObserver(obs);
            obs = null;
        }
    }

    private void invalidateAll() {
        cache.clear();
        missing.clear();
    }

    /** Latest snapshot of the widget with this uid, or null if SystemUI has not delivered one yet. */
    public Bitmap get(String uid) {
        if (uid == null) return null;
        Bitmap b = cache.get(uid);
        if (b != null || missing.contains(uid)) return b;
        File f = new File(ConfigStore.snapDir(ctx), uid + ".png");
        if (f.length() > 0) b = BitmapFactory.decodeFile(f.getPath());
        if (b != null) cache.put(uid, b); else missing.add(uid);
        return b;
    }
}
