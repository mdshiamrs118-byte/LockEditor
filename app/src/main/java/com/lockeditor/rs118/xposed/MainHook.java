package com.lockeditor.rs118.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        try {
            if ("android".equals(lp.packageName)) {
                SystemHooks.install(lp.classLoader);
            } else if ("com.android.systemui".equals(lp.packageName)) {
                KeyguardHooks.install(lp.classLoader);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
