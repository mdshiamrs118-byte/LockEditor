package com.lockeditor.rs118;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Lets SystemUI (only) pull the config, report widget ids and diagnostics, and write widget snapshots. */
public class ConfigProvider extends ContentProvider {
    private static long lastGrant;

    private boolean allowed() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        String[] pk = getContext().getPackageManager().getPackagesForUid(uid);
        if (pk != null) for (String p : pk) if ("com.android.systemui".equals(p)) return true;
        return false;
    }

    private static synchronized boolean grantDue() {
        long now = SystemClock.uptimeMillis();
        if (now - lastGrant < 30000) return false;
        lastGrant = now;
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!allowed()) return null;
        final Context c = getContext();
        Bundle out = new Bundle();
        if ("get".equals(method)) {
            ConfigStore.setFlag(c, "hook", "ok"); // SystemUI is alive and talking to us
            Config cfg = ConfigStore.load(c);
            cfg.orphans.addAll(ConfigStore.popOrphans(c, cfg));
            out.putString("json", cfg.toJson(true).toString());
        } else if ("set_id".equals(method) && extras != null) {
            ConfigStore.setRuntime(c, arg, extras.getInt("id", -1), extras.getBoolean("configured"));
        } else if ("log".equals(method)) {
            ConfigStore.appendLog(c, arg);
        } else if ("flag".equals(method)) {
            ConfigStore.setFlag(c, arg, extras != null ? extras.getString("v") : "");
        } else if ("snap_done".equals(method)) {
            ConfigStore.notifySnapDone(c);
        } else if ("bind_denied".equals(method) && grantDue()) {
            // SystemUI says it may not bind widgets: grant it via root, then make it retry
            new Thread(new Runnable() {
                @Override public void run() {
                    boolean ok = RootUtil.grantBind();
                    ConfigStore.appendLog(c, "auto grantbind -> " + (ok ? "OK" : "FAILED (root not granted to Lock Editor?)"));
                    ConfigStore.notifyChanged(c);
                }
            }).start();
        }
        return out;
    }

    /** SystemUI (only) writes widget snapshots through here. */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!allowed()) throw new SecurityException("not allowed");
        List<String> seg = uri.getPathSegments();
        if (seg.size() == 2 && "snapfile".equals(seg.get(0)) && seg.get(1).matches("[A-Za-z0-9-]{1,64}")) {
            File f = new File(ConfigStore.snapDir(getContext()), seg.get(1) + ".png");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        throw new FileNotFoundException(uri.toString());
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
