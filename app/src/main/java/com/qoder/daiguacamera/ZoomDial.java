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
 * 焦距控制盘：圆形旋钮，拖动圆环调整变焦。
 * 量程上限 100×、下限 0.1×；相机能力不足时由调用方收窄到 [1, 相机最大]。
 * 控制盘可以被收起成一个右侧的小圆钮，把画面让出来。
 */
public class ZoomDial extends View {

    public interface Listener {
        void onZoom(float zoom, boolean fromUser);
    }

    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private float minZoom = 0.1f;
    private float maxZoom = 100f;
    private float zoom = 1f;
    private Listener listener;

    public ZoomDial(Context context) {
        super(context);
        setClickable(true);
        bgPaint.setColor(0x77000000);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(5));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0x33FFFFFF);
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(dp(5));
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setColor(0xFFFF5C7A);
        thumbPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2.5f));
        ringPaint.setColor(0xFFFF5C7A);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        textPaint.setTextSize(dp(15));
        labelPaint.setColor(0xB3FFFFFF);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(dp(9.5f));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** 设定可用范围：自动夹在 0.1×–100× 之间，并保证 min <= max。 */
    public void setRange(float min, float max) {
        float lo = clamp(min, 0.1f, 100f);
        float hi = clamp(max, 0.1f, 100f);
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

    /** 外部（双指捏合）同步变焦值。 */
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

    /** 对数刻度：低倍数区间更好控制，也方便将来覆盖到 0.1×–100×。 */
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

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(4);
        c.drawCircle(cx, cy, radius, bgPaint);

        float inset = dp(17);
        arc.set(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset);

        c.drawArc(arc, START_ANGLE, SWEEP_ANGLE, false, trackPaint);
        float f = fraction();
        if (maxZoom > minZoom + 0.0001f) {
            c.drawArc(arc, START_ANGLE, SWEEP_ANGLE * f, false, fillPaint);
        }
        float angle = (float) Math.toRadians(START_ANGLE + SWEEP_ANGLE * f);
        float tx = cx + (float) Math.cos(angle) * (arc.width() / 2f);
        float ty = cy + (float) Math.sin(angle) * (arc.height() / 2f);
        c.drawCircle(tx, ty, dp(8), thumbPaint);
        c.drawCircle(tx, ty, dp(8), ringPaint);

        c.drawText(String.format(Locale.US, "%.1f×", zoom), cx, cy + dp(1), textPaint);
        c.drawText("焦距", cx, cy + dp(16), labelPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (maxZoom <= minZoom + 0.0001f) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                applyTouch(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                applyTouch(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void applyTouch(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        double deg = Math.toDegrees(Math.atan2(y - cy, x - cx));
        if (deg < 0) deg += 360;
        float a = (float) deg;
        if (a < START_ANGLE) a += 360;
        // 底部缺口（45°–135°）以外都算有效，落在缺口里就近吸附到两端
        if (a > START_ANGLE + SWEEP_ANGLE) {
            float over = a - (START_ANGLE + SWEEP_ANGLE);
            a = over > 45f ? START_ANGLE : START_ANGLE + SWEEP_ANGLE;
        }
        setFraction((a - START_ANGLE) / SWEEP_ANGLE);
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
