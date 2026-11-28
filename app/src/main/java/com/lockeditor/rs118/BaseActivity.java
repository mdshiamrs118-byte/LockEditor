package com.lockeditor.rs118;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

/** Shared look for the app screens (cards, rows, light/dark colors). */
public abstract class BaseActivity extends Activity {
    protected static final int PAD = 12, GAP = 10, CARD_PAD = 14;

    protected Ui ui;
    protected float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        d = getResources().getDisplayMetrics().density;
        ui.window(this);
    }

    protected int dp(int v) { return Math.round(v * d); }

    protected GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    protected TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    protected LinearLayout card(String heading) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(CARD_PAD), dp(CARD_PAD), dp(CARD_PAD), dp(CARD_PAD - 4));
        c.setBackground(rounded(ui.card, 24));
        c.setElevation(d * 1.5f);
        if (heading != null) {
            TextView h = text(heading.toUpperCase(Locale.ROOT), 11, ui.sub, true);
            h.setLetterSpacing(0.08f);
            h.setPadding(dp(2), 0, 0, dp(8));
            c.addView(h);
        }
        return c;
    }

    /** Adds a card to a column with the gap below it. */
    protected void put(LinearLayout col, View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(GAP);
        col.addView(card, lp);
    }

    protected LinearLayout rowBase() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(40));
        return r;
    }

    protected View switchRow(String label, boolean init, Consumer<Boolean> cb) {
        LinearLayout r = rowBase();
        TextView t = text(label, 14, ui.txt, false);
        Switch sw = new Switch(this);
        sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ui.acc, ui.dark ? 0xFFB0B6C3 : Color.WHITE}));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{(ui.acc & 0x00FFFFFF) | 0x88000000, ui.dark ? 0xFF454B58 : 0xFFC9CED8}));
        sw.setChecked(init);
        sw.setOnCheckedChangeListener((v, on) -> cb.accept(on));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(sw);
        return r;
    }

    protected View tapRow(String label, View.OnClickListener l) {
        LinearLayout r = rowBase();
        r.addView(text(label, 14, ui.txt, false), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(text("›", 18, ui.sub, false));
        r.setOnClickListener(l);
        return r;
    }

    /** Small rounded button used in cards and lists. */
    protected TextView button(String label, boolean primary, View.OnClickListener l) {
        TextView t = text(label, 13, primary ? Color.WHITE : ui.txt, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(primary ? ui.acc : ui.track, 16));
        t.setOnClickListener(l);
        return t;
    }
}
