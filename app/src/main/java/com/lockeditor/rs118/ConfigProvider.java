package com.lockeditor.rs118;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Lets SystemUI (only) pull the config and report allocated widget ids. */
public class ConfigProvider extends ContentProvider {

    private boolean allowed() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        String[] pk = getContext().getPackageManager().getPackagesForUid(uid);
        if (pk != null) for (String p : pk) if ("com.android.systemui".equals(p)) return true;
        return false;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!allowed()) return null;
        Context c = getContext();
        Bundle out = new Bundle();
        if ("get".equals(method)) {
            Config cfg = ConfigStore.load(c);
            cfg.orphans.addAll(ConfigStore.popOrphans(c, cfg));
            out.putString("json", cfg.toJson(true).toString());
        } else if ("set_id".equals(method) && extras != null) {
            ConfigStore.setRuntime(c, arg, extras.getInt("id", -1), extras.getBoolean("configured"));
        }
        return out;
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
