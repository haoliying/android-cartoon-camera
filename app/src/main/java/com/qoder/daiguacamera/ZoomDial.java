package com.qoder.daiguacamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 焦距控制盘：仿相机镜头环的圆形刻度盘，上下拖动变焦。
 * 刻度环 + 高亮进度弧 + 白色指示点，中间显示当前倍数。
 * 量程由调用方设定；盘自身只负责 0.1×–100× 的映射。
 * 控制盘可以被收起成右侧的小圆钮，把画面让出来。
 */
public class ZoomDial extends View {

    public interface Listener {
        void onZoom(float zoom, boolean fromUser);
    }

    private static final float HARD_MIN = 0.1f;
    private static final float HARD_MAX = 100f;
    private static final int TICKS = 60;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float minZoom = 1f;
    private float maxZoom = 100f;
    private float zoom = 1f;
    private Listener listener;

    private float dragStartY;
    private float dragStartFraction;

    public ZoomDial(Context context) {
        super(context);
        setClickable(true);
        bgPaint.setColor(0x66000000);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(0x26FFFFFF);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(dp(1));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(3));
        trackPaint.setColor(0x1FFFFFFF);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(3.5f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        thumbGlowPaint.setColor(0x33FFFFFF);
        thumbPaint.setColor(Color.WHITE);
        valuePaint.setColor(Color.WHITE);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        valuePaint.setTextSize(dp(19.5f));
        labelPaint.setColor(0x80FFFFFF);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(dp(9.5f));
        setContentDescription("焦距控制盘，上下拖动调整");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** 设定可用范围：自动夹在 0.1×–100× 之间，并保证 min <= max。 */
    public void setRange(float min, float max) {
        float lo = clamp(min, HARD_MIN, HARD_MAX);
        float hi = clamp(max, HARD_MIN, HARD_MAX);
        if (hi < lo) hi = lo;
        if (Math.abs(lo - minZoom) > 0.0001f || Math.abs(hi - maxZoom) > 0.0001f) {
            minZoom = lo;
            maxZoom = hi;
            zoom = clamp(zoom, minZoom, maxZoom);
            invalidate();
        }
    }

    public float getZoom() {
        return zoom;
    }

    /** 外部（双指捏合、相机就绪）同步变焦值。 */
    public void setZoom(float z) {
        float v = clamp(z, minZoom, maxZoom);
        if (Math.abs(v - zoom) > 0.0005f) {
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

    /** 对数刻度：低倍数区间更好控制，也方便覆盖 0.5×–100×。 */
    private float fraction() {
        if (maxZoom <= minZoom + 0.0001f) return 0f;
        double lo = Math.log(minZoom);
        double hi = Math.log(maxZoom);
        return (float) ((Math.log(zoom) - lo) / (hi - lo));
    }

    private void setFraction(float f) {
        float t = clamp(f, 0f, 1f);
        double lo = Math.log(minZoom);
        double hi = Math.log(maxZoom);
        applyZoom((float) Math.exp(lo + t * (hi - lo)));
    }

    private String zoomText() {
        float z = zoom;
        if (Math.abs(z - Math.rint(z)) < 0.05f) {
            return String.format(Locale.US, "%.0f×", z);
        }
        return String.format(Locale.US, "%.1f×", z);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        SweepGradient g = new SweepGradient(cx, cy,
                new int[]{0xFFFF5C7A, 0xFFFF9A5C, 0xFFFF5C7A},
                new float[]{0f, 0.55f, 1f});
        Matrix m = new Matrix();
        m.setRotate(-90f, cx, cy);
        g.setLocalMatrix(m);
        arcPaint.setShader(g);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(2);

        c.drawCircle(cx, cy, r, bgPaint);
        c.drawCircle(cx, cy, r, borderPaint);

        // 刻度环：每 6° 一格，每 30° 一个长格
        float tickOuter = r - dp(6);
        for (int i = 0; i < TICKS; i++) {
            double a = Math.toRadians(-90 + i * (360.0 / TICKS));
            boolean major = i % 5 == 0;
            float len = major ? dp(7) : dp(3.5f);
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            tickPaint.setColor(major ? 0x66FFFFFF : 0x38FFFFFF);
            c.drawLine(cx + cos * (tickOuter - len), cy + sin * (tickOuter - len),
                    cx + cos * tickOuter, cy + sin * tickOuter, tickPaint);
        }

        // 进度弧与指示点
        float arcR = r - dp(17);
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
        c.drawArc(arcRect, -90f, 360f, false, trackPaint);
        float f = fraction();
        if (maxZoom > minZoom + 0.0001f) {
            c.drawArc(arcRect, -90f, 360f * f, false, arcPaint);
        }
        double ta = Math.toRadians(-90 + 360.0 * f);
        float tx = cx + (float) Math.cos(ta) * arcR;
        float ty = cy + (float) Math.sin(ta) * arcR;
        c.drawCircle(tx, ty, dp(9), thumbGlowPaint);
        c.drawCircle(tx, ty, dp(5), thumbPaint);

        c.drawText(zoomText(), cx, cy + dp(1), valuePaint);
        c.drawText("焦距", cx, cy + dp(18), labelPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (maxZoom <= minZoom + 0.0001f) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                dragStartY = e.getY();
                dragStartFraction = fraction();
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                // 向上拖放大、向下拖缩小，和相机变焦手势一致
                float span = Math.max(dp(120), getHeight() * 1.15f);
                setFraction(dragStartFraction + (dragStartY - e.getY()) / span);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void applyZoom(float v) {
        float z = clamp(v, minZoom, maxZoom);
        if (Math.abs(z - zoom) > 0.0005f) {
            zoom = z;
            invalidate();
            if (listener != null) listener.onZoom(zoom, true);
        }
    }
}
