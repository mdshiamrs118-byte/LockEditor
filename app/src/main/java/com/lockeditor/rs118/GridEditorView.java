package com.lockeditor.rs118;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

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
    private final float dp;
    private Config cfg = new Config();
    private Host host;
    private Config.Widget sel;
    private float aspect = 0.5f;
    private int mode;
    private float downX, downY;
    private int oRow, oCol, oRs, oCs;
    private boolean dirty;

    public GridEditorView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        paint.setTextSize(12 * dp);
    }

    public void setHost(Host h) { host = h; }
    public void setConfig(Config c) { cfg = c; sel = null; invalidate(); }
    public void setAspect(float a) { aspect = a; invalidate(); }
    public Config.Widget getSelected() { return sel; }
    public void setSelected(Config.Widget w) { sel = w; invalidate(); }

    private void computeArea() {
        float m = 8 * dp;
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

    @Override
    protected void onDraw(Canvas c) {
        computeArea();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF303134);
        c.drawRect(area, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0x55FFFFFF);
        float cw = area.width() / cfg.cols, ch = area.height() / cfg.rows;
        for (int i = 0; i <= cfg.cols; i++) c.drawLine(area.left + i * cw, area.top, area.left + i * cw, area.bottom, paint);
        for (int i = 0; i <= cfg.rows; i++) c.drawLine(area.left, area.top + i * ch, area.right, area.top + i * ch, paint);

        for (Config.Widget w : cfg.widgets) {
            RectF r = rectOf(w);
            r.inset(2 * dp, 2 * dp);
            boolean s = w == sel;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(s ? 0x88448AFF : 0x55448AFF);
            c.drawRect(r, paint);

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

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((s ? 3 : 1.5f) * dp);
            paint.setColor(s ? 0xFF8AB4F8 : 0xFF5F6368);
            c.drawRect(r, paint);

            c.save();
            c.clipRect(r);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            String label = host != null ? host.label(w.provider) : w.provider;
            c.drawText(label + "  " + w.colSpan + "×" + w.rowSpan, r.left + 4 * dp, r.top + 14 * dp, paint);
            c.restore();

            if (s) {
                paint.setColor(0xFF8AB4F8);
                c.drawRect(r.right - 14 * dp, r.bottom - 14 * dp, r.right, r.bottom, paint);
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
