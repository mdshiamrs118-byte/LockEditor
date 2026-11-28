package com.lockeditor.rs118;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

/** Colors for the app screens, light or dark. Mode is stored in prefs: 0 = system, 1 = light, 2 = dark. */
public final class Ui {
    public static final int SYSTEM = 0, LIGHT = 1, DARK = 2;
    private static final String PREFS = "lockeditor_ui";

    public final boolean dark;
    public final int bg, card, txt, sub, track, acc, line;

    public Ui(Context c) {
        int m = mode(c);
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dark = m == DARK || (m == SYSTEM && night);
        bg = dark ? 0xFF0F1115 : 0xFFEDF0F7;
        card = dark ? 0xFF1C1F27 : 0xFFFFFFFF;
        txt = dark ? 0xFFE8EAF0 : 0xFF1B1F29;
        sub = dark ? 0xFF9AA3B5 : 0xFF5B6475;
        track = dark ? 0xFF2B303B : 0xFFE6EAF4;
        acc = dark ? 0xFF5C7CFA : 0xFF3B5BDB;
        line = dark ? 0xFF343A47 : 0xFFD9DEEA;
    }

    public static int mode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("theme", SYSTEM);
    }

    public static void setMode(Context c, int m) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("theme", m).apply();
    }

    public int dialogTheme() {
        return dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    /** Colors the system bars to match the screen and picks dark or light icons. */
    public void window(Activity a) {
        a.getWindow().setStatusBarColor(bg);
        a.getWindow().setNavigationBarColor(bg);
        View decor = a.getWindow().getDecorView();
        int f = decor.getSystemUiVisibility();
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? (f & ~lightBars) : (f | lightBars));
    }
}
