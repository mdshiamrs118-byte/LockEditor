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
    }

    private static final int NONE = 0, MOVE = 1, RESIZE = 2, NMOVE = 3, NRESIZE = 4;
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
    private boolean showNotif, notifSel;
    private float[] nRect;   // see setNotifRect
    private Limits limits;
    private int oNW, oNH, oNDx, oNDy;
    private float grabX, grabY; // where the resize handle was grabbed, relative to the box corner

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
    public void setConfig(Config c) { cfg = c; sel = null; notifSel = false; invalidate(); }
    public void setLight(boolean l) { light = l; invalidate(); }
    public void setInteractive(boolean i) { interactive = i; }
    public void setMarginDp(float m) { marginDp = m; invalidate(); }
    public void setCornerDp(float c) { cornerDp = c; invalidate(); }
    public void setWallpaper(Bitmap b) { wallpaper = b; invalidate(); }
    public void setSnap(float s) { snap = s; invalidate(); }
    public void setGuides(boolean g) { guides = g; invalidate(); }
    public Config.Widget getSelected() { return sel; }
    public void setSelected(Config.Widget w) { sel = w; if (w != null) notifSel = false; invalidate(); }

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

        if (showNotif) drawNotif(c, rad); // below the widgets, like on the real lock screen
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

        if (showNotif && notifSel) drawNotifSelection(c, rad);
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

    private void select(Config.Widget w) {
        boolean wasNotif = notifSel;
        notifSel = false;
        if (w != sel || wasNotif) {
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
        if (showNotif && !notifSel && !cfg.hideNotifs) { // the notification list is something to line up with, too
            float[] b = notifCells();
            float p = xAxis ? b[0] : b[1], q = xAxis ? b[2] : b[3];
            t.add(p);
            t.add((p + q) / 2f);
            t.add(q);
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

    // ---------- notification list box ----------
    public void setShowNotif(boolean b) { showNotif = b; if (!b) notifSel = false; invalidate(); }
    public void setLimits(Limits l) { limits = l; }
    public boolean isNotifSelected() { return notifSel; }
    public void setNotifSelected(boolean b) { notifSel = b; if (b) sel = null; invalidate(); }

    /** Default (untransformed) bounds of the stock notification list as reported by SystemUI: l,t,r,b,windowW,windowH. */
    public void setNotifRect(float[] r) {
        if (java.util.Arrays.equals(r, nRect)) return;
        nRect = r;
        invalidate();
    }

    private float[] nRef() {
        if (nRect != null) return nRect;
        float w = real.widthPixels, h = real.heightPixels; // estimate until SystemUI has reported the real one
        return new float[]{0, h * 0.22f, w, h * 0.88f, w, h};
    }

    /** Maps a rect in the stack's default window coordinates to view coordinates, with the notification scale/offset applied. */
    private RectF notifXf(float l, float t, float r, float b, float[] ref) {
        float den = real.density;
        float cx = (ref[0] + ref[2]) / 2f;
        float sx = cfg.notifW / 100f, sy = cfg.notifH / 100f;
        float cxn = cx + cfg.notifDx * den, top = ref[1] + cfg.notifDy * den; // pivot: top center, then translate
        float fx = screen.width() / ref[4], fy = screen.height() / ref[5];
        return new RectF(screen.left + (cxn + (l - cx) * sx) * fx, screen.top + (top + (t - ref[1]) * sy) * fy,
                screen.left + (cxn + (r - cx) * sx) * fx, screen.top + (top + (b - ref[1]) * sy) * fy);
    }

    private RectF notifView() {
        float[] r = nRef();
        return notifXf(r[0], r[1], r[2], r[3], r);
    }

    private void drawNotif(Canvas c, float rad) {
        float[] r = nRef();
        float den = real.density;
        RectF box = notifXf(r[0], r[1], r[2], r[3], r);
        boolean off = cfg.hideNotifs;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(off ? 0x14FFFFFF : 0x24FFC107);
        c.drawRoundRect(box, rad, rad, paint);
        if (!off) { // a few stand-in notification cards, scaled like the real list
            float m = 12 * den, cardH = 64 * den, gap = 8 * den, w = r[2] - r[0];
            for (int i = 0; i < 6; i++) {
                float top = r[1] + m + i * (cardH + gap);
                if (top + cardH > r[3] - m) break;
                paint.setColor(light ? 0x99FFFFFF : 0x55FFFFFF);
                c.drawRoundRect(notifXf(r[0] + m, top, r[2] - m, top + cardH, r), 10 * dp, 10 * dp, paint);
                paint.setColor(0x66000000);
                c.drawRoundRect(notifXf(r[0] + m + 16 * den, top + 14 * den, r[0] + m + 16 * den + w * 0.35f, top + 24 * den, r), 3 * dp, 3 * dp, paint);
                c.drawRoundRect(notifXf(r[0] + m + 16 * den, top + 34 * den, r[0] + m + 16 * den + w * 0.6f, top + 42 * den, r), 3 * dp, 3 * dp, paint);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * dp);
        paint.setColor(0xFFFFC107);
        paint.setPathEffect(new DashPathEffect(new float[]{10 * dp, 6 * dp}, 0));
        c.drawRoundRect(box, rad, rad, paint);
        paint.setPathEffect(null);
        c.save();
        c.clipRect(box);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFC107);
        paint.setTextSize(11 * dp);
        paint.setShadowLayer(2 * dp, 0, 0, 0xFF000000);
        c.drawText("Notifications" + (off ? " (hidden)" : nRect == null ? " (estimated)" : ""), box.left + 6 * dp, box.top + 14 * dp, paint);
        paint.clearShadowLayer();
        c.restore();
    }

    private void drawNotifSelection(Canvas c, float rad) {
        RectF box = notifView();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * dp);
        paint.setColor(0xFFFFC107);
        c.drawRoundRect(box, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(box.right - 14 * dp, box.bottom - 14 * dp, box.right, box.bottom), 3 * dp, 3 * dp, paint);
    }

    /** The notification box in grid cells: left, top, right, bottom. */
    private float[] notifCells() {
        RectF v = notifView();
        float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows;
        return new float[]{(v.left - area.left) / cw, (v.top - area.top) / ch, (v.right - area.left) / cw, (v.bottom - area.top) / ch};
    }

    /** Shift (in cells) that puts either the near edge or the far edge of a span on a snap step, whichever is closer. */
    private float edgeSnap(float p, float size) {
        if (snap <= 0) return 0;
        float a = snapV(p) - p, b = snapV(p + size) - (p + size);
        return Math.abs(a) <= Math.abs(b) ? a : b;
    }

    private void collectNotifGuides() {
        gx.clear();
        gy.clear();
        float[] b = notifCells();
        float[] ex = mode == NMOVE ? new float[]{b[0], (b[0] + b[2]) / 2f, b[2]} : new float[]{b[2]};
        float[] ey = mode == NMOVE ? new float[]{b[1], (b[1] + b[3]) / 2f, b[3]} : new float[]{b[3]};
        // the size is stored in whole percent, so allow a little more slack than for widgets
        for (float t : targets(true)) for (float e : ex) if (Math.abs(t - e) < 0.04f && !gx.contains(t)) gx.add(t);
        for (float t : targets(false)) for (float e : ey) if (Math.abs(t - e) < 0.04f && !gy.contains(t)) gy.add(t);
    }

    /** Moves/resizes the notification box. Edges snap to the grid and align with other widgets, like widgets do. */
    private void moveNotif(float x, float y) {
        float[] r = nRef();
        float den = real.density;
        float fx = screen.width() / r[4], fy = screen.height() / r[5];      // view px per window px
        float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows; // view px per cell
        float ox = screen.left - area.left, oy = screen.top - area.top;
        Limits lim = limits != null ? limits : new Limits();
        float cx = (r[0] + r[2]) / 2f, defW = Math.max(1, r[2] - r[0]), defH = Math.max(1, r[3] - r[1]);
        float oW = defW * oNW / 100f, oH = defH * oNH / 100f;               // box at the start of the drag (window px)
        float oL = cx + oNDx * den - oW / 2f, oT = r[1] + oNDy * den;
        boolean magnet = guides && snap != 1f; // with whole-cell snapping the grid itself is the guide
        float thX = 6 * dp / cw, thY = 6 * dp / ch;
        if (mode == NMOVE) {
            float uL = ((oL + (x - downX) / fx) * fx + ox) / cw, uW = oW * fx / cw;
            float vT = ((oT + (y - downY) / fy) * fy + oy) / ch, vH = oH * fy / ch;
            uL += edgeSnap(uL, uW);
            vT += edgeSnap(vT, vH);
            if (magnet) {
                uL += pull(targets(true), new float[]{uL, uL + uW / 2f, uL + uW}, thX);
                vT += pull(targets(false), new float[]{vT, vT + vH / 2f, vT + vH}, thY);
            }
            float newL = (uL * cw - ox) / fx, newT = (vT * ch - oy) / fy;
            cfg.notifDx = lim.clamp(Limits.NDX, Math.round((newL + oW / 2f - cx) / den));
            cfg.notifDy = lim.clamp(Limits.NDY, Math.round((newT - r[1]) / den));
        } else { // resize: the top-left corner stays put, the bottom-right corner snaps
            float px = (x - grabX - screen.left) / fx, py = (y - grabY - screen.top) / fy;
            float ur = (px * fx + ox) / cw, vb = (py * fy + oy) / ch;
            if (snap > 0) { ur = snapV(ur); vb = snapV(vb); }
            if (magnet) {
                ur += pull(targets(true), new float[]{ur}, thX);
                vb += pull(targets(false), new float[]{vb}, thY);
            }
            float right = (ur * cw - ox) / fx, bottom = (vb * ch - oy) / fy;
            cfg.notifW = lim.clamp(Limits.NW, Math.round((right - oL) / defW * 100f));
            cfg.notifH = lim.clamp(Limits.NH, Math.round((bottom - oT) / defH * 100f));
            float w = defW * cfg.notifW / 100f;
            cfg.notifDx = lim.clamp(Limits.NDX, Math.round((oL + w / 2f - cx) / den)); // keeps the left edge in place
        }
        if (guides) collectNotifGuides();
        if (cfg.notifW != oNW || cfg.notifH != oNH || cfg.notifDx != oNDx || cfg.notifDy != oNDy) dirty = true;
        invalidate();
    }

    private void selectNotif() {
        boolean changed = !notifSel || sel != null;
        sel = null;
        notifSel = true;
        if (changed && host != null) host.onSelected(null);
        invalidate();
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
                boolean nHit = false;
                RectF nv = showNotif ? notifView() : null;
                float hs = 32 * dp;
                if (notifSel && nv != null && x > nv.right - hs && x < nv.right + hs / 2
                        && y > nv.bottom - hs && y < nv.bottom + hs / 2) {
                    nHit = true;
                    mode = NRESIZE;
                    grabX = x - nv.right;
                    grabY = y - nv.bottom;
                } else {
                    if (sel != null) {
                        RectF r = rectOf(sel);
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
                    if (hit == null && nv != null && nv.contains(x, y)) { // widgets sit above the notification list
                        nHit = true;
                        mode = NMOVE;
                    }
                }
                if (nHit) {
                    selectNotif();
                    downX = x; downY = y;
                    oNW = cfg.notifW; oNH = cfg.notifH; oNDx = cfg.notifDx; oNDy = cfg.notifDy;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                select(hit);
                if (hit != null) {
                    downX = x; downY = y;
                    oRow = hit.row; oCol = hit.col; oRs = hit.rowSpan; oCs = hit.colSpan;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == NONE) return true;
                if (mode == NMOVE || mode == NRESIZE) { moveNotif(x, y); return true; }
                if (sel == null) return true;
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
