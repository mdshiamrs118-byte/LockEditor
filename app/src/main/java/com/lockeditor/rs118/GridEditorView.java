package com.lockeditor.rs118;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Grid canvas: tap to select, drag to move (snaps to cells), drag the corner handle to resize. */
public class GridEditorView extends View {

    public interface Host {
        Drawable preview(String provider);
        String label(String provider);
        void onChanged();
        void onSelected(Config.Widget w);
    }

    private static final int NONE = 0, MOVE = 1, RESIZE = 2;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();
    private final DisplayMetrics real = new DisplayMetrics();
    private final float dp;
    private Config cfg = new Config();
    private Host host;
    private Config.Widget sel;
    private Bitmap wallpaper;
    private boolean light, interactive = true;
    private float marginDp = 8, aspect = 0.5f;
    private int mode;
    private float downX, downY;
    private int oRow, oCol, oRs, oCs;
    private boolean dirty;

    public GridEditorView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        paint.setTextSize(12 * dp);
        paint.setFilterBitmap(true);
        try {
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(real);
        } catch (Throwable t) {
            real.widthPixels = 1080;
            real.density = dp;
        }
    }

    public void setHost(Host h) { host = h; }
    public void setConfig(Config c) { cfg = c; sel = null; invalidate(); }
    public void setAspect(float a) { aspect = a; invalidate(); }
    public void setLight(boolean l) { light = l; invalidate(); }
    public void setInteractive(boolean i) { interactive = i; }
    public void setMarginDp(float m) { marginDp = m; invalidate(); }
    public void setWallpaper(Bitmap b) { wallpaper = b; invalidate(); }
    public Config.Widget getSelected() { return sel; }
    public void setSelected(Config.Widget w) { sel = w; invalidate(); }

    private void computeArea() {
        float m = marginDp * dp;
        float aw = getWidth() - 2 * m, ah = getHeight() - 2 * m, w, h;
        if (aw / ah > aspect) { h = ah; w = h * aspect; } else { w = aw; h = w / aspect; }
        float l = (getWidth() - w) / 2f, t = (getHeight() - h) / 2f;
        area.set(l, t, l + w, t + h);
    }

    private RectF rectOf(Config.Widget w) {
        float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows;
        return new RectF(area.left + w.col * cw, area.top + w.row * ch,
                area.left + (w.col + w.colSpan) * cw, area.top + (w.row + w.rowSpan) * ch);
    }

    private void drawWallpaper(Canvas c) {
        float s = area.width() / Math.max(1, real.widthPixels);
        RectF full = new RectF(area.left, area.top - cfg.insetTop * real.density * s,
                area.right, area.bottom + cfg.insetBottom * real.density * s);
        float bw = wallpaper.getWidth(), bh = wallpaper.getHeight();
        float sc = Math.max(full.width() / bw, full.height() / bh);
        float dw = bw * sc, dh = bh * sc;
        RectF dst = new RectF(full.centerX() - dw / 2, full.centerY() - dh / 2, full.centerX() + dw / 2, full.centerY() + dh / 2);
        c.save();
        c.clipRect(area);
        c.drawBitmap(wallpaper, null, dst, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44000000);
        c.drawRect(area, paint);
        c.restore();
    }

    @Override
    protected void onDraw(Canvas c) {
        computeArea();
        float rad = 6 * dp;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(light ? 0xFFE6EAF4 : 0xFF303134);
        c.drawRect(area, paint);
        if (wallpaper != null) drawWallpaper(c);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(light ? 0x22000000 : (wallpaper != null ? 0x40FFFFFF : 0x55FFFFFF));
        float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows;
        for (int i = 0; i <= cfg.cols; i++) c.drawLine(area.left + i * cw, area.top, area.left + i * cw, area.bottom, paint);
        for (int i = 0; i <= cfg.rows; i++) c.drawLine(area.left, area.top + i * ch, area.right, area.top + i * ch, paint);

        for (Config.Widget w : cfg.widgets) {
            RectF r = rectOf(w);
            r.inset(2 * dp, 2 * dp);
            boolean s = w == sel;
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

            int accent = light ? (s ? 0xFF3B5BDB : 0xFF8A97C7) : (s ? 0xFF8AB4F8 : 0xFF9AA0A6);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((s ? 3 : 1.5f) * dp);
            paint.setColor(accent);
            c.drawRoundRect(r, rad, rad, paint);

            c.save();
            c.clipRect(r);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(light ? 0xFF1B1F29 : 0xFFFFFFFF);
            if (!light) paint.setShadowLayer(2 * dp, 0, 0, 0xFF000000);
            String label = host != null ? host.label(w.provider) : w.provider;
            paint.setTextSize((light ? 9 : 12) * dp);
            c.drawText(label + (light ? "" : "  " + w.colSpan + "×" + w.rowSpan), r.left + 4 * dp, r.top + (light ? 11 : 14) * dp, paint);
            paint.clearShadowLayer();
            c.restore();

            if (s && interactive) {
                paint.setColor(accent);
                c.drawRoundRect(new RectF(r.right - 14 * dp, r.bottom - 14 * dp, r.right, r.bottom), 3 * dp, 3 * dp, paint);
            }
        }
    }

    private void select(Config.Widget w) {
        if (w != sel) {
            sel = w;
            if (host != null) host.onSelected(w);
        }
        invalidate();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                computeArea();
                mode = NONE;
                Config.Widget hit = null;
                if (sel != null) {
                    RectF r = rectOf(sel);
                    float hs = 32 * dp;
                    if (x > r.right - hs && x < r.right + hs / 2 && y > r.bottom - hs && y < r.bottom + hs / 2) {
                        hit = sel;
                        mode = RESIZE;
                    }
                }
                if (hit == null) {
                    for (int i = cfg.widgets.size() - 1; i >= 0; i--) {
                        Config.Widget w = cfg.widgets.get(i);
                        if (rectOf(w).contains(x, y)) { hit = w; mode = MOVE; break; }
                    }
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
                if (sel == null || mode == NONE) return true;
                float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows;
                int dc = Math.round((x - downX) / cw), dr = Math.round((y - downY) / ch);
                if (mode == MOVE) {
                    sel.col = clamp(oCol + dc, 0, cfg.cols - sel.colSpan);
                    sel.row = clamp(oRow + dr, 0, cfg.rows - sel.rowSpan);
                } else {
                    sel.colSpan = clamp(oCs + dc, 1, cfg.cols - sel.col);
                    sel.rowSpan = clamp(oRs + dr, 1, cfg.rows - sel.row);
                }
                if (sel.col != oCol || sel.row != oRow || sel.colSpan != oCs || sel.rowSpan != oRs) dirty = true;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dirty && host != null) host.onChanged();
                dirty = false;
                mode = NONE;
                return true;
        }
        return true;
    }
}
