package com.qoder.daiguacamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 右侧竖直播变焦条：向上拖动放大，双击恢复 1 倍。
 * 和双指捏合共用同一套变焦值，给出一个看得见、摸得着的入口。
 */
public class ZoomSlider extends View {

    public interface Listener {
        void onZoom(float zoom, boolean fromUser);
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private float minZoom = 1f;
    private float maxZoom = 1f;
    private float zoom = 1f;
    private Listener listener;

    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;

    public ZoomSlider(Context context) {
        super(context);
        setClickable(true);
        bgPaint.setColor(0x66000000);
        trackPaint.setColor(0x3DFFFFFF);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setColor(0xFFFF5C7A);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        thumbPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        ringPaint.setColor(0xFFFF5C7A);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(11));
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setMaxZoom(float max) {
        float m = Math.max(1f, max);
        if (Math.abs(m - maxZoom) > 0.001f) {
            maxZoom = m;
            zoom = clamp(zoom, minZoom, maxZoom);
            invalidate();
        }
    }

    public float getZoom() {
        return zoom;
    }

    /** 外部（双指捏合）同步变焦值。 */
    public void setZoom(float z) {
        float v = clamp(z, minZoom, maxZoom);
        if (Math.abs(v - zoom) > 0.001f) {
            zoom = v;
            invalidate();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private float trackTop() {
        return dp(30);
    }

    private float trackBottom() {
        return getHeight() - dp(10);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        // 背景胶囊
        tmp.set(0, 0, w, h);
        c.drawRoundRect(tmp, w / 2f, w / 2f, bgPaint);

        // 当前倍数
        c.drawText(String.format(Locale.US, "%.1f×", zoom), cx, dp(18), textPaint);

        float top = trackTop();
        float bottom = trackBottom();
        float t = maxZoom > minZoom ? (zoom - minZoom) / (maxZoom - minZoom) : 0f;
        float ty = bottom - t * (bottom - top);
        c.drawLine(cx, top, cx, bottom, trackPaint);
        if (maxZoom > minZoom) {
            c.drawLine(cx, ty, cx, bottom, fillPaint);
        }
        c.drawCircle(cx, ty, dp(8), thumbPaint);
        c.drawCircle(cx, ty, dp(8), ringPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (maxZoom <= minZoom + 0.001f) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                long now = System.currentTimeMillis();
                if (now - lastTapTime < 300
                        && Math.abs(e.getX() - lastTapX) < dp(24)
                        && Math.abs(e.getY() - lastTapY) < dp(24)) {
                    applyZoom(minZoom);
                    lastTapTime = 0;
                    return true;
                }
                lastTapTime = now;
                lastTapX = e.getX();
                lastTapY = e.getY();
                applyTouch(e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                applyTouch(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void applyTouch(float y) {
        float top = trackTop();
        float bottom = trackBottom();
        float t = (bottom - y) / Math.max(1f, bottom - top);
        t = clamp(t, 0f, 1f);
        applyZoom(minZoom + t * (maxZoom - minZoom));
    }

    private void applyZoom(float v) {
        float z = clamp(v, minZoom, maxZoom);
        if (Math.abs(z - zoom) > 0.001f) {
            zoom = z;
            invalidate();
            if (listener != null) listener.onZoom(zoom, true);
        }
    }
}
