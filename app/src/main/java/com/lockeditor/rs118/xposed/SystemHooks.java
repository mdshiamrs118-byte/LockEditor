package com.lockeditor.rs118.xposed;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Runs inside system_server: lets SystemUI bind app widgets (what a launcher gets via the bind dialog). */
final class SystemHooks {
    private static final String TAG = "[LockEditor/system] ";

    private SystemHooks() { }

    static void install(ClassLoader cl) {
        try {
            Class<?> sp = XposedHelpers.findClassIfExists(
                    "com.android.server.appwidget.AppWidgetServiceImpl$SecurityPolicy", cl);
            if (sp == null) {
                XposedBridge.log(TAG + "AppWidgetServiceImpl$SecurityPolicy not found");
                return;
            }
            XC_MethodHook allow = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args != null && p.args.length > 0 && "com.android.systemui".equals(p.args[0])) {
                        p.setResult(Boolean.TRUE);
                    }
                }
            };
            Set<XC_MethodHook.Unhook> a = XposedBridge.hookAllMethods(sp, "hasCallerBindPermissionOrBindWhiteListedLocked", allow);
            Set<XC_MethodHook.Unhook> b = XposedBridge.hookAllMethods(sp, "isCallerBindAppWidgetWhiteListedLocked", allow);
            XposedBridge.log(TAG + "bind-permission hooks installed (" + a.size() + "+" + b.size() + ")");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
