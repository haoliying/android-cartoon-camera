package com.qoder.daiguacamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 焦距控制盘：仿相机镜头环的圆形刻度盘，上下拖动变焦，点刻度可直接跳到常用倍数。
 * 刻度环 + 高亮进度弧 + 白色指示点，中间显示当前倍数。
 * 量程由调用方设定；盘自身只负责 0.1×–100× 的映射。
 * 低倍区（0.5×–10×）分配了更多行程，拖一点就能明显变化。
 */
public class ZoomDial extends View {

    public interface Listener {
        void onZoom(float zoom, boolean fromUser);
    }

    private static final float HARD_MIN = 0.1f;
    private static final float HARD_MAX = 100f;
    private static final int TICKS = 60;
    /** 进度弧从正上方开始，留出 40° 缺口，最小/最大刻度不会叠在一起。 */
    private static final float ARC_START = -90f;
    private static final float ARC_SWEEP = 320f;

    /** 常用倍数：拖动时的映射锚点，也是盘面上显示的数字。 */
    private static final float[] ANCHOR = {0.1f, 0.5f, 1f, 2f, 3f, 5f, 10f, 30f, 100f};
    /** 每段行程权重：1×–10× 占得最多，广角和长焦段收窄。 */
    private static final float[] WEIGHT = {0.55f, 0.85f, 1.35f, 1.0f, 0.95f, 1.1f, 1.25f, 1.15f};

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float minZoom = 1f;
    private float maxZoom = 100f;
    private float zoom = 1f;
    private Listener listener;

    private float dragStartY;
    private float dragStartFraction;
    private float downX;
    private float downY;
    private long downTime;
    private int lastTick = -1;

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
        stopPaint.setColor(0xCCFFFFFF);
        stopPaint.setTextAlign(Paint.Align.CENTER);
        stopPaint.setTextSize(dp(8.5f));
        setContentDescription("焦距控制盘，上下拖动调整，点刻度跳到常用倍数");
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
            lastTick = -1;
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
            lastTick = nearestAnchorIndex(v, 0.02f);
            invalidate();
        }
    }

    /** 是否支持广角（下限小于 1×）。 */
    public boolean hasWide() {
        return minZoom < 0.99f;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------------------------------------------------------- 非线性映射

    /**
     * 把 [minZoom, maxZoom] 按锚点权重映射到 0–1。
     * 只统计落在量程内的那一段，所以量程变化时旅程依然铺满整圈。
     */
    private float fraction() {
        return fractionOf(zoom);
    }

    private float fractionOf(float z) {
        float v = clamp(z, minZoom, maxZoom);
        float total = totalWeight();
        if (total <= 1e-6f) return 0f;
        float acc = 0f;
        for (int i = 0; i < ANCHOR.length - 1; i++) {
            float a = Math.max(ANCHOR[i], minZoom);
            float b = Math.min(ANCHOR[i + 1], maxZoom);
            if (b <= a) continue;
            float full = (float) (Math.log(ANCHOR[i + 1]) - Math.log(ANCHOR[i]));
            float part = (float) (Math.log(b) - Math.log(a));
            float w = WEIGHT[i] * part / full;
            if (v >= b) {
                acc += w;
            } else if (v > a) {
                float inner = (float) ((Math.log(v) - Math.log(a)) / part);
                return (acc + w * inner) / total;
            }
        }
        return acc / total;
    }

    /** 量程内所有分段的权重总和。 */
    private float totalWeight() {
        float total = 0f;
        for (int i = 0; i < ANCHOR.length - 1; i++) {
            float a = Math.max(ANCHOR[i], minZoom);
            float b = Math.min(ANCHOR[i + 1], maxZoom);
            if (b <= a) continue;
            float full = (float) (Math.log(ANCHOR[i + 1]) - Math.log(ANCHOR[i]));
            float part = (float) (Math.log(b) - Math.log(a));
            total += WEIGHT[i] * part / full;
        }
        return total;
    }

    private float zoomAtFraction(float f) {
        float t = clamp(f, 0f, 1f);
        float total = 0f;
        for (int i = 0; i < ANCHOR.length - 1; i++) {
            float a = Math.max(ANCHOR[i], minZoom);
            float b = Math.min(ANCHOR[i + 1], maxZoom);
            if (b <= a) continue;
            float full = (float) (Math.log(ANCHOR[i + 1]) - Math.log(ANCHOR[i]));
            float part = (float) (Math.log(b) - Math.log(a));
            total += WEIGHT[i] * part / full;
        }
        if (total <= 0f) return minZoom;
        float target = t * total;
        float acc = 0f;
        for (int i = 0; i < ANCHOR.length - 1; i++) {
            float a = Math.max(ANCHOR[i], minZoom);
            float b = Math.min(ANCHOR[i + 1], maxZoom);
            if (b <= a) continue;
            float full = (float) (Math.log(ANCHOR[i + 1]) - Math.log(ANCHOR[i]));
            float part = (float) (Math.log(b) - Math.log(a));
            float w = WEIGHT[i] * part / full;
            if (acc + w >= target) {
                float inner = (target - acc) / w;
                return (float) Math.exp(Math.log(a) + inner * part);
            }
            acc += w;
        }
        return maxZoom;
    }

    /** 量程内可见的锚点；下限不是整数刻度时（例如 0.6×）也把它标出来。 */
    private float[] visibleAnchors() {
        boolean minIsAnchor = false;
        for (float v : ANCHOR) {
            if (Math.abs(v - minZoom) < 0.01f) {
                minIsAnchor = true;
                break;
            }
        }
        boolean extraMin = minZoom < 0.99f && !minIsAnchor;
        float[] out = new float[ANCHOR.length + (extraMin ? 1 : 0)];
        int k = 0;
        if (extraMin) out[k++] = minZoom;
        for (float v : ANCHOR) {
            if (v >= minZoom - 1e-4f && v <= maxZoom + 1e-4f) out[k++] = v;
        }
        if (k == out.length) return out;
        float[] trimmed = new float[k];
        System.arraycopy(out, 0, trimmed, 0, k);
        return trimmed;
    }

    private int nearestAnchorIndex(float v, float toleranceFraction) {
        float f = fractionOf(v);
        float bestD = Float.MAX_VALUE;
        int best = -1;
        float[] vis = visibleAnchors();
        for (int i = 0; i < vis.length; i++) {
            float d = Math.abs(fractionOf(vis[i]) - f);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) return -1;
        return bestD <= toleranceFraction ? best : -1;
    }

    private String zoomText() {
        float z = zoom;
        if (z >= 0.999f && Math.abs(z - Math.rint(z)) < 0.05f) {
            return String.format(Locale.US, "%.0f×", z);
        }
        if (z < 0.999f) {
            String t = String.format(Locale.US, "%.2f", z);
            t = t.replaceAll("0+$", "").replaceAll("\\.$", "");
            return t + "×";
        }
        return String.format(Locale.US, "%.1f×", z);
    }

    private String anchorText(float v) {
        if (Math.abs(v - Math.rint(v)) < 0.05f) {
            return String.format(Locale.US, "%.0f", v);
        }
        return String.format(Locale.US, "%.1f", v);
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

        // 刻度环：每 6° 一格，常用倍数位置加长加亮
        float tickOuter = r - dp(5);
        float[] anchors = visibleAnchors();
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
        c.drawArc(arcRect, ARC_START, ARC_SWEEP, false, trackPaint);
        float f = fraction();
        if (maxZoom > minZoom + 0.0001f) {
            c.drawArc(arcRect, ARC_START, ARC_SWEEP * f, false, arcPaint);
        }

        // 常用倍数刻度数字，点一下就能跳过去（广角 0.5 也会显示出来）
        float labelR = arcR - dp(10);
        for (float v : anchors) {
            double aa = Math.toRadians(ARC_START + ARC_SWEEP * fractionOf(v));
            float lx = cx + (float) Math.cos(aa) * labelR;
            float ly = cy + (float) Math.sin(aa) * labelR + dp(3);
            stopPaint.setColor(0xB3FFFFFF);
            c.drawText(anchorText(v), lx, ly, stopPaint);
        }

        double ta = Math.toRadians(ARC_START + ARC_SWEEP * f);
        float tx = cx + (float) Math.cos(ta) * arcR;
        float ty = cy + (float) Math.sin(ta) * arcR;
        c.drawCircle(tx, ty, dp(9), thumbGlowPaint);
        c.drawCircle(tx, ty, dp(5), thumbPaint);

        c.drawText(zoomText(), cx, cy + dp(1), valuePaint);
        c.drawText(hasWide() ? "焦距 · 可广角" : "焦距", cx, cy + dp(18), labelPaint);
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
                downX = e.getX();
                downY = e.getY();
                downTime = System.currentTimeMillis();
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                // 向上拖放大、向下拖缩小；100dp 左右走完整个量程，低倍区更跟手
                float span = Math.max(dp(84), getHeight() * 0.72f);
                float f = dragStartFraction + (dragStartY - e.getY()) / span;
                setFraction(f);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                float moved = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                long dt = System.currentTimeMillis() - downTime;
                if (moved < dp(8) && dt < 350) {
                    tapAt(e.getX(), e.getY());
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    /** 点刻度数字附近时直接跳到该倍数；点中心区域不处理，避免误触。 */
    private void tapAt(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float dist = (float) Math.hypot(x - cx, y - cy);
        float r = Math.min(getWidth(), getHeight()) / 2f;
        if (dist < r * 0.45f || dist > r) return;
        double deg = Math.toDegrees(Math.atan2(x - cx, cy - y));
        if (deg < 0) deg += 360;
        if (deg > ARC_SWEEP) return;   // 缺口区域不响应
        float f = (float) (deg / ARC_SWEEP);
        float[] vis = visibleAnchors();
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < vis.length; i++) {
            float d = Math.abs(fractionOf(vis[i]) - f);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best >= 0 && bestD <= 0.05f) {
            float z = clamp(vis[best], minZoom, maxZoom);
            if (Math.abs(z - zoom) > 0.0005f) {
                zoom = z;
                invalidate();
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                if (listener != null) listener.onZoom(zoom, true);
            }
        }
    }

    private void setFraction(float f) {
        float z = zoomAtFraction(f);
        if (Math.abs(z - zoom) > 0.0005f) {
            zoom = z;
            invalidate();
            int tick = nearestAnchorIndex(z, 0.012f);
            if (tick != lastTick && tick >= 0) {
                lastTick = tick;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            if (listener != null) listener.onZoom(zoom, true);
        }
    }
}
