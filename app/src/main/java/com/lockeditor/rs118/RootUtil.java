package com.lockeditor.rs118;

public final class RootUtil {
    private RootUtil() { }

    public static boolean run(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Lets SystemUI bind app widgets (same thing a launcher gets via the bind-permission dialog). */
    public static boolean grantBind() {
        return run("cmd appwidget grantbind --package com.android.systemui --user 0")
                || run("cmd appwidget grantbind --package com.android.systemui");
    }
}
