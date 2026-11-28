package com.lockeditor.rs118;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Grid canvas: tap to select, drag to move, drag the corner handle to resize.
 * Positions snap to a step (1, 1/2, 1/4 cell, or none) and, when guides are on, align magnetically
 * with the edges and centers of other widgets and of the grid area.
 */
public class GridEditorView extends View {

    public interface Host {
        Drawable preview(String provider);
        String label(String provider);
        Bitmap snapshot(String uid);
        void onChanged();
        void onSelected(Config.Widget w);
        void onNotifSelected(boolean selected);
    }

    private static final int NONE = 0, MOVE = 1, RESIZE = 2;
    private static final int NOTIF_MOVE = 3, NOTIF_RESIZE = 4;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();   // widget grid area (screen minus top/bottom inset)
    private final RectF screen = new RectF(); // the whole phone screen, same aspect as the real display
    private final DisplayMetrics real = new DisplayMetrics();
    private final float dp;
    private final List<Float> gx = new ArrayList<>(), gy = new ArrayList<>(); // active guide lines, in cells
    private Config cfg = new Config();
    private Host host;
    private Config.Widget sel;
    private Bitmap wallpaper;
    private boolean light, interactive = true, guides = true;
    private float marginDp = 8, cornerDp, snap = 1f; // snap: step in cells, 0 = free
    private int mode;
    private float downX, downY, oRow, oCol, oRs, oCs;
    private boolean dirty;
    private boolean notifSel;
    private Limits lim = new Limits();
    private int oNW, oNH, oNDx, oNDy;

    public GridEditorView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        paint.setTextSize(12 * dp);
        paint.setFilterBitmap(true);
        try {
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(real);
        } catch (Throwable t) {
            real.widthPixels = 1080;
            real.heightPixels = 2340;
            real.density = dp;
        }
    }

    public void setHost(Host h) { host = h; }
    public void setConfig(Config c) { cfg = c; sel = null; invalidate(); }
    public void setLight(boolean l) { light = l; invalidate(); }
    public void setInteractive(boolean i) { interactive = i; }
    public void setMarginDp(float m) { marginDp = m; invalidate(); }
    public void setCornerDp(float c) { cornerDp = c; invalidate(); }
    public void setWallpaper(Bitmap b) { wallpaper = b; invalidate(); }
    public void setSnap(float s) { snap = s; invalidate(); }
    public void setGuides(boolean g) { guides = g; invalidate(); }
    public Config.Widget getSelected() { return sel; }
    public void setSelected(Config.Widget w) { sel = w; if (w != null) notifSel = false; invalidate(); }
    public boolean isNotifSelected() { return notifSel; }
    public void setNotifSelected(boolean b) { notifSel = b; if (b) sel = null; invalidate(); }
    public void setLimits(Limits l) { lim = l; }

    /** The lock screen notification list as the hook shows it: scaled around top-center, then translated. */
    private RectF notifRect() {
        float k = screen.width() / Math.max(1, real.widthPixels); // preview px per real px
        float baseW = screen.width(), baseH = area.height();
        float w = baseW * cfg.notifW / 100f, h = baseH * cfg.notifH / 100f;
        float cx = screen.centerX() + cfg.notifDx * real.density * k;
        float top = area.top + cfg.notifDy * real.density * k;
        return new RectF(cx - w / 2f, top, cx + w / 2f, top + h);
    }

    private void computeArea() {
        float m = marginDp * dp;
        float aw = Math.max(1, getWidth() - 2 * m), ah = Math.max(1, getHeight() - 2 * m), w, h;
        float sa = real.widthPixels / (float) Math.max(1, real.heightPixels); // real screen aspect
        if (aw / ah > sa) { h = ah; w = h * sa; } else { w = aw; h = w / sa; }
        float l = (getWidth() - w) / 2f, t = (getHeight() - h) / 2f;
        screen.set(l, t, l + w, t + h);
        float s = w / Math.max(1, real.widthPixels); // preview px per real px
        float top = screen.top + cfg.insetTop * real.density * s;
        float bottom = screen.bottom - cfg.insetBottom * real.density * s;
        area.set(screen.left, top, screen.right, Math.max(bottom, top + 1));
    }

    private RectF rectOf(Config.Widget w) {
        float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows;
        return new RectF(area.left + w.col * cw, area.top + w.row * ch,
                area.left + (w.col + w.colSpan) * cw, area.top + (w.row + w.rowSpan) * ch);
    }

    /** Same as the lock screen: scale to cover the whole screen, centered. No extra dimming. */
    private void drawWallpaper(Canvas c) {
        float bw = wallpaper.getWidth(), bh = wallpaper.getHeight();
        float sc = Math.max(screen.width() / bw, screen.height() / bh);
        float dw = bw * sc, dh = bh * sc;
        RectF dst = new RectF(screen.centerX() - dw / 2, screen.centerY() - dh / 2,
                screen.centerX() + dw / 2, screen.centerY() + dh / 2);
        c.drawBitmap(wallpaper, null, dst, paint);
    }

    @Override
    protected void onDraw(Canvas c) {
        computeArea();
        float rad = 6 * dp;
        float corner = cornerDp * dp;
        c.save();
        if (corner > 0) {
            Path clip = new Path();
            clip.addRoundRect(screen, corner, corner, Path.Direction.CW);
            c.clipPath(clip);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(light ? 0xFFE6EAF4 : 0xFF303134);
        c.drawRect(screen, paint);
        if (wallpaper != null) drawWallpaper(c);

        float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows;
        paint.setStyle(Paint.Style.STROKE);
        for (int pass = 0; pass < 2; pass++) {
            if (wallpaper != null) { // dark underlay so the grid shows on light wallpapers too
                paint.setStrokeWidth(pass == 0 ? 2f : 1f);
                paint.setColor(pass == 0 ? 0x33000000 : 0x66FFFFFF);
            } else {
                if (pass == 0) continue;
                paint.setStrokeWidth(1f);
                paint.setColor(light ? 0x22000000 : 0x55FFFFFF);
            }
            for (int i = 0; i <= cfg.cols; i++) c.drawLine(area.left + i * cw, area.top, area.left + i * cw, area.bottom, paint);
            for (int i = 0; i <= cfg.rows; i++) c.drawLine(area.left, area.top + i * ch, area.right, area.top + i * ch, paint);
        }

        for (Config.Widget w : cfg.widgets) { // list order = layer order, last is on top
            RectF r = rectOf(w);
            r.inset(2 * dp, 2 * dp);
            boolean s = w == sel;
            Bitmap shot = host != null ? host.snapshot(w.uid) : null;

            if (shot != null) {
                // the real, loaded widget as rendered by SystemUI
                float k = Math.min(r.width() / shot.getWidth(), r.height() / shot.getHeight());
                float pw = shot.getWidth() * k, ph = shot.getHeight() * k;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF); // full alpha so the bitmap is not tinted/faded
                c.drawBitmap(shot, null, new RectF(r.centerX() - pw / 2, r.centerY() - ph / 2,
                        r.centerX() + pw / 2, r.centerY() + ph / 2), paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(light ? (s ? 0xAA8FA6F5 : 0x88B4C4F8) : (s ? 0x99448AFF : 0x66448AFF));
                c.drawRoundRect(r, rad, rad, paint);
                Drawable dr = host != null ? host.preview(w.provider) : null;
                if (dr != null) {
                    int iw = dr.getIntrinsicWidth(), ih = dr.getIntrinsicHeight();
                    if (iw > 0 && ih > 0) {
                        float k = Math.min(r.width() / iw, r.height() / ih);
                        float pw = iw * k, ph = ih * k;
                        dr.setBounds((int) (r.centerX() - pw / 2), (int) (r.centerY() - ph / 2),
                                (int) (r.centerX() + pw / 2), (int) (r.centerY() + ph / 2));
                        dr.setAlpha(190);
                        dr.draw(c);
                    }
                }
            }

            int accent = light ? (s ? 0xFF3B5BDB : 0xFF8A97C7) : (s ? 0xFF8AB4F8 : 0xFF9AA0A6);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((s ? 3 : (shot != null ? 1 : 1.5f)) * dp);
            paint.setColor(accent);
            c.drawRoundRect(r, rad, rad, paint);

            if (shot == null) { // name label only while there is no real render
                c.save();
                c.clipRect(r);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(light ? 0xFF1B1F29 : 0xFFFFFFFF);
                if (!light) paint.setShadowLayer(2 * dp, 0, 0, 0xFF000000);
                String label = host != null ? host.label(w.provider) : w.provider;
                paint.setTextSize((light ? 9 : 12) * dp);
                c.drawText(label + (light ? "" : "  " + Config.fmt(w.colSpan) + "×" + Config.fmt(w.rowSpan)),
                        r.left + 4 * dp, r.top + (light ? 11 : 14) * dp, paint);
                paint.clearShadowLayer();
                c.restore();
            }

            if (s && interactive) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(accent);
                c.drawRoundRect(new RectF(r.right - 14 * dp, r.bottom - 14 * dp, r.right, r.bottom), 3 * dp, 3 * dp, paint);
            }
        }

        { // notification list box (always shown so it can be grabbed)
            RectF r = notifRect();
            boolean s = notifSel;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(light ? (s ? 0x66F4A261 : 0x33F4A261) : (s ? 0x66FFB74D : 0x33FFB74D));
            c.drawRoundRect(r, rad, rad, paint);
            // fake notification cards so it reads as a notification list
            float cardH = Math.min(46 * dp, Math.max(14 * dp, r.height() / 6f)), gap = 6 * dp, pad = 6 * dp;
            paint.setColor(light ? 0x99FFFFFF : 0x55FFFFFF);
            for (float y = r.top + pad; y + cardH <= r.bottom - pad && y < r.top + pad + 3 * (cardH + gap); y += cardH + gap)
                c.drawRoundRect(new RectF(r.left + pad, y, r.right - pad, y + cardH), 5 * dp, 5 * dp, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((s ? 3 : 1.5f) * dp);
            paint.setColor(s ? 0xFFFF9800 : 0xFFFFB74D);
            paint.setPathEffect(new DashPathEffect(new float[]{10 * dp, 5 * dp}, 0));
            c.drawRoundRect(r, rad, rad, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(light ? 0xFF1B1F29 : 0xFFFFFFFF);
            if (!light) paint.setShadowLayer(2 * dp, 0, 0, 0xFF000000);
            paint.setTextSize(11 * dp);
            c.save();
            c.clipRect(r);
            c.drawText("Notifications  " + cfg.notifW + "% × " + cfg.notifH + "%", r.left + 8 * dp, r.top + 14 * dp, paint);
            c.restore();
            paint.clearShadowLayer();
            if (s && interactive) {
                paint.setColor(0xFFFF9800);
                c.drawRoundRect(new RectF(r.right - 14 * dp, r.bottom - 14 * dp, r.right, r.bottom), 3 * dp, 3 * dp, paint);
            }
        }

        if (!gx.isEmpty() || !gy.isEmpty()) { // alignment guides while dragging
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * dp);
            paint.setColor(0xFFFF4081);
            paint.setPathEffect(new DashPathEffect(new float[]{8 * dp, 5 * dp}, 0));
            for (float t : gx) {
                float x = area.left + t * cw;
                c.drawLine(x, screen.top, x, screen.bottom, paint);
            }
            for (float t : gy) {
                float y = area.top + t * ch;
                c.drawLine(screen.left, y, screen.right, y, paint);
            }
            paint.setPathEffect(null);
        }
        c.restore();
        if (corner > 0 && light) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * dp);
            paint.setColor(0x33000000);
            c.drawRoundRect(screen, corner, corner, paint);
        }
    }

    private void selectNotif() {
        boolean was = notifSel;
        notifSel = true;
        sel = null;
        if (host != null) { host.onSelected(null); if (!was) host.onNotifSelected(true); }
        invalidate();
    }

    private void select(Config.Widget w) {
        if (w != null && notifSel) { notifSel = false; if (host != null) host.onNotifSelected(false); }
        if (w != sel) {
            sel = w;
            if (host != null) host.onSelected(w);
        }
        invalidate();
    }

    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(v, hi)); }
    private float snapV(float v) { return snap > 0 ? Math.round(v / snap) * snap : v; }
    private float minSpan() { return snap > 0 ? snap : Config.MIN_SPAN; }

    /** Alignment targets along one axis: grid edges/center and edges/centers of the other widgets. */
    private List<Float> targets(boolean xAxis) {
        List<Float> t = new ArrayList<>();
        float n = xAxis ? cfg.cols : cfg.rows;
        t.add(0f);
        t.add(n / 2f);
        t.add(n);
        for (Config.Widget o : cfg.widgets) {
            if (o == sel) continue;
            float p = xAxis ? o.col : o.row, s = xAxis ? o.colSpan : o.rowSpan;
            t.add(p);
            t.add(p + s / 2f);
            t.add(p + s);
        }
        return t;
    }

    /** Smallest correction that lines one of the edges up with a target (0 if none is within thr). */
    private static float pull(List<Float> targets, float[] edges, float thr) {
        float best = 0, bestAbs = thr;
        for (float t : targets) for (float e : edges) {
            float diff = t - e;
            if (Math.abs(diff) < bestAbs) { bestAbs = Math.abs(diff); best = diff; }
        }
        return best;
    }

    private void collectGuides() {
        gx.clear();
        gy.clear();
        float[] ex = mode == MOVE ? new float[]{sel.col, sel.col + sel.colSpan / 2f, sel.col + sel.colSpan}
                : new float[]{sel.col + sel.colSpan};
        float[] ey = mode == MOVE ? new float[]{sel.row, sel.row + sel.rowSpan / 2f, sel.row + sel.rowSpan}
                : new float[]{sel.row + sel.rowSpan};
        for (float t : targets(true)) for (float e : ex) if (Math.abs(t - e) < 0.01f && !gx.contains(t)) gx.add(t);
        for (float t : targets(false)) for (float e : ey) if (Math.abs(t - e) < 0.01f && !gy.contains(t)) gy.add(t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                computeArea();
                mode = NONE;
                Config.Widget hit = null;
                RectF nr = notifRect();
                float nhs = 32 * dp;
                if (notifSel && x > nr.right - nhs && x < nr.right + nhs / 2 && y > nr.bottom - nhs && y < nr.bottom + nhs / 2) {
                    mode = NOTIF_RESIZE; // handle of the selected notification box wins
                    oNW = cfg.notifW; oNH = cfg.notifH; oNDx = cfg.notifDx; oNDy = cfg.notifDy;
                    downX = x; downY = y;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (sel != null) {
                    RectF r = rectOf(sel);
                    float hs = 32 * dp;
                    if (x > r.right - hs && x < r.right + hs / 2 && y > r.bottom - hs && y < r.bottom + hs / 2) {
                        hit = sel;
                        mode = RESIZE;
                    }
                }
                if (hit == null) {
                    for (int i = cfg.widgets.size() - 1; i >= 0; i--) { // topmost layer first
                        Config.Widget w = cfg.widgets.get(i);
                        if (rectOf(w).contains(x, y)) { hit = w; mode = MOVE; break; }
                    }
                }
                if (hit == null && nr.contains(x, y)) { // empty-grid tap inside the notification box
                    selectNotif();
                    mode = NOTIF_MOVE;
                    oNW = cfg.notifW; oNH = cfg.notifH; oNDx = cfg.notifDx; oNDy = cfg.notifDy;
                    downX = x; downY = y;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (hit == null && notifSel) { notifSel = false; if (host != null) host.onNotifSelected(false); }
                select(hit);
                if (hit != null) {
                    downX = x; downY = y;
                    oRow = hit.row; oCol = hit.col; oRs = hit.rowSpan; oCs = hit.colSpan;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == NOTIF_MOVE || mode == NOTIF_RESIZE) {
                    float k = screen.width() / Math.max(1, real.widthPixels);
                    float pxPerDp = real.density * k; // preview px per dp
                    float baseW = screen.width(), baseH = Math.max(1, area.height());
                    if (mode == NOTIF_MOVE) {
                        cfg.notifDx = lim.clamp(Limits.NDX, Math.round(oNDx + (x - downX) / pxPerDp));
                        cfg.notifDy = lim.clamp(Limits.NDY, Math.round(oNDy + (y - downY) / pxPerDp));
                    } else {
                        // right/bottom handle: the left and top edges stay where they are
                        float wPx = baseW * oNW / 100f + (x - downX), hPx = baseH * oNH / 100f + (y - downY);
                        int nw = lim.clamp(Limits.NW, Math.round(wPx / baseW * 100f));
                        int nh = lim.clamp(Limits.NH, Math.round(hPx / baseH * 100f));
                        cfg.notifW = nw;
                        cfg.notifH = nh;
                        // scaling happens around top-center, so shift X to keep the left edge fixed
                        float shiftPx = baseW * (nw - oNW) / 200f;
                        cfg.notifDx = lim.clamp(Limits.NDX, Math.round(oNDx + shiftPx / pxPerDp));
                    }
                    dirty = dirty || cfg.notifW != oNW || cfg.notifH != oNH || cfg.notifDx != oNDx || cfg.notifDy != oNDy;
                    invalidate();
                    return true;
                }
                if (sel == null || mode == NONE) return true;
                float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows;
                float dc = (x - downX) / cw, dr = (y - downY) / ch;
                boolean magnet = guides && snap != 1f; // with whole-cell snapping the grid itself is the guide
                float thX = 6 * dp / cw, thY = 6 * dp / ch;
                if (mode == MOVE) {
                    float c = clampF(snapV(oCol + dc), 0, cfg.cols - sel.colSpan);
                    float r = clampF(snapV(oRow + dr), 0, cfg.rows - sel.rowSpan);
                    if (magnet) {
                        c = clampF(c + pull(targets(true), new float[]{c, c + sel.colSpan / 2f, c + sel.colSpan}, thX),
                                0, cfg.cols - sel.colSpan);
                        r = clampF(r + pull(targets(false), new float[]{r, r + sel.rowSpan / 2f, r + sel.rowSpan}, thY),
                                0, cfg.rows - sel.rowSpan);
                    }
                    sel.col = c;
                    sel.row = r;
                } else {
                    float cs = clampF(snapV(oCs + dc), minSpan(), cfg.cols - sel.col);
                    float rs = clampF(snapV(oRs + dr), minSpan(), cfg.rows - sel.row);
                    if (magnet) {
                        cs = clampF(cs + pull(targets(true), new float[]{sel.col + cs}, thX), minSpan(), cfg.cols - sel.col);
                        rs = clampF(rs + pull(targets(false), new float[]{sel.row + rs}, thY), minSpan(), cfg.rows - sel.row);
                    }
                    sel.colSpan = cs;
                    sel.rowSpan = rs;
                }
                if (guides) collectGuides();
                if (Math.abs(sel.col - oCol) > 1e-4f || Math.abs(sel.row - oRow) > 1e-4f
                        || Math.abs(sel.colSpan - oCs) > 1e-4f || Math.abs(sel.rowSpan - oRs) > 1e-4f) dirty = true;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gx.clear();
                gy.clear();
                invalidate();
                if (dirty && host != null) host.onChanged();
                dirty = false;
                mode = NONE;
                return true;
        }
        return true;
    }
}
