package com.qoder.daiguacamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * 构图叠加层：网格、主体框、人脸框、检测到的地平线，以及一条构图提示。
 * 只负责画，不处理触摸，不会挡住对焦和变焦手势。
 *
 * 取景画面不再铺满屏幕，而是按相机画幅居中显示（四周留黑边），
 * 所以所有归一化坐标都要先映射到 contentRect 这块画面区域。
 */
public class CompositionOverlay extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subjectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean active;
    private int gridMode = CompositionAdvisor.GRID_THIRDS;

    private CompositionAdvisor.Result result;
    private boolean hintReady;
    private final RectF content = new RectF();

    public CompositionOverlay(Context ctx) {
        super(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, d * 0.8f));
        gridPaint.setColor(0x4DFFFFFF);

        subjectPaint.setStyle(Paint.Style.STROKE);
        subjectPaint.setStrokeWidth(Math.max(1.5f, d * 1.6f));
        subjectPaint.setColor(0xCCFFB74D);

        facePaint.setStyle(Paint.Style.STROKE);
        facePaint.setStrokeWidth(Math.max(1f, d));
        facePaint.setColor(0x8CFFFFFF);

        horizonPaint.setStyle(Paint.Style.STROKE);
        horizonPaint.setStrokeWidth(Math.max(1f, d * 1.3f));
        horizonPaint.setColor(0xB34DD0E1);

        hintBgPaint.setStyle(Paint.Style.FILL);
        hintBgPaint.setColor(0xE60B0B10);
        hintStrokePaint.setStyle(Paint.Style.STROKE);
        hintStrokePaint.setStrokeWidth(Math.max(1f, d * 1.2f));

        hintTextPaint.setColor(0xFFF4F5FA);
        hintTextPaint.setTextSize(sp(13f));
        hintTextPaint.setTextAlign(Paint.Align.CENTER);
        hintTextPaint.setFakeBoldText(true);

        detailPaint.setColor(0xB3E9EAF2);
        detailPaint.setTextSize(sp(10.5f));
        detailPaint.setTextAlign(Paint.Align.CENTER);

        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setActive(boolean on) {
        active = on;
        if (!on) {
            result = null;
            hintReady = false;
        }
        invalidate();
    }

    public void setGridMode(int mode) {
        gridMode = mode;
        invalidate();
    }

    /** 取景画面在屏幕上的实际区域（像素），叠加层只画在这块区域里。 */
    public void setContentRect(int left, int top, int right, int bottom) {
        if (right - left < 2 || bottom - top < 2) return;
        if (content.left == left && content.top == top
                && content.right == right && content.bottom == bottom) return;
        content.set(left, top, right, bottom);
        invalidate();
    }

    public void setResult(CompositionAdvisor.Result r, boolean ready) {
        result = r;
        hintReady = ready;
        invalidate();
    }

    public void clearResult() {
        result = null;
        hintReady = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;

        RectF c = new RectF(0, 0, w, h);
        if (content.width() > 2 && content.height() > 2) c.set(content);

        drawGrid(canvas, c, d);

        CompositionAdvisor.Result r = result;
        if (r == null) return;

        if (r.horizonY >= 0f && r.horizonConf > 0.45f) {
            drawHorizon(canvas, c, r);
        }
        if (r.subject != null) {
            subjectPaint.setColor(r.severity == CompositionAdvisor.SEV_GOOD
                    ? 0xCC4ADE80 : 0xCCFFB74D);
            drawBox(canvas, r.subject, c, subjectPaint, d, 8f);
        }
        for (float[] f : r.faces) {
            drawBox(canvas, f, c, facePaint, d, 7f);
        }
        if (hintReady && r.hint != null) {
            drawHint(canvas, c, r, d);
        }
    }

    private void drawGrid(Canvas canvas, RectF c, float d) {
        if (gridMode == CompositionAdvisor.GRID_CENTER) {
            canvas.drawLine(c.centerX(), c.top, c.centerX(), c.bottom, gridPaint);
            canvas.drawLine(c.left, c.centerY(), c.right, c.centerY(), gridPaint);
            canvas.drawCircle(c.centerX(), c.centerY(), d * 26f, gridPaint);
            return;
        }
        if (gridMode == CompositionAdvisor.GRID_DIAGONAL) {
            canvas.drawLine(c.left, c.top, c.right, c.bottom, gridPaint);
            canvas.drawLine(c.right, c.top, c.left, c.bottom, gridPaint);
            canvas.drawCircle(c.centerX(), c.centerY(), d * 22f, gridPaint);
            return;
        }
        for (int i = 1; i <= 2; i++) {
            float x = c.left + c.width() * i / 3f;
            float y = c.top + c.height() * i / 3f;
            canvas.drawLine(x, c.top, x, c.bottom, gridPaint);
            canvas.drawLine(c.left, y, c.right, y, gridPaint);
        }
        // 四角小刻度，强调“把主体放在交叉点附近”
        float len = d * 14f;
        float inset = d * 10f;
        float l = c.left + inset, t = c.top + inset;
        float r = c.right - inset, b = c.bottom - inset;
        canvas.drawLine(l, t, l + len, t, gridPaint);
        canvas.drawLine(l, t, l, t + len, gridPaint);
        canvas.drawLine(r, t, r - len, t, gridPaint);
        canvas.drawLine(r, t, r, t + len, gridPaint);
        canvas.drawLine(l, b, l + len, b, gridPaint);
        canvas.drawLine(l, b, l, b - len, gridPaint);
        canvas.drawLine(r, b, r - len, b, gridPaint);
        canvas.drawLine(r, b, r, b - len, gridPaint);
    }

    private void drawHorizon(Canvas canvas, RectF c, CompositionAdvisor.Result r) {
        float yc = c.top + r.horizonY * c.height();
        float slope = (float) Math.tan(Math.toRadians(r.horizonTilt));
        float y0 = yc - slope * c.width() * 0.5f;
        float y1 = yc + slope * c.width() * 0.5f;
        canvas.drawLine(c.left, y0, c.right, y1, horizonPaint);
    }

    private void drawBox(Canvas canvas, float[] b, RectF c, Paint p, float d, float radius) {
        if (b == null || b.length < 4) return;
        RectF rect = new RectF(c.left + b[0] * c.width(), c.top + b[1] * c.height(),
                c.left + (b[0] + b[2]) * c.width(), c.top + (b[1] + b[3]) * c.height());
        // 四角加一点外扩，看起来像取景框
        rect.inset(-d * 3f, -d * 3f);
        canvas.drawRoundRect(rect, radius * d / 8f, radius * d / 8f, p);
    }

    private void drawHint(Canvas canvas, RectF c, CompositionAdvisor.Result r, float d) {
        String text = r.hint;
        if (r.arrowX > 0.5f) text = "→ " + text;
        else if (r.arrowX < -0.5f) text = "← " + text;
        else if (r.arrowY > 0.5f) text = "↓ " + text;
        else if (r.arrowY < -0.5f) text = "↑ " + text;

        float padH = d * 14f;
        float padV = d * 8f;
        float textW = hintTextPaint.measureText(text);
        float detailW = r.detail == null ? 0 : detailPaint.measureText(r.detail);
        float boxW = Math.min(c.width() - d * 24f, Math.max(textW, detailW) + padH * 2f);
        float line1 = hintTextPaint.getTextSize();
        float line2 = r.detail == null || r.detail.isEmpty() ? 0 : detailPaint.getTextSize();
        float gap = line2 > 0 ? d * 3f : 0;
        float boxH = line1 + line2 + gap + padV * 2f;

        float cx = c.centerX();
        float top = c.bottom - boxH - d * 10f;
        top = Math.max(c.top + d * 8f, top);
        RectF box = new RectF(cx - boxW * 0.5f, top, cx + boxW * 0.5f, top + boxH);
        int color = r.severity == CompositionAdvisor.SEV_WARN ? 0xFFFFB74D
                : r.severity == CompositionAdvisor.SEV_GOOD ? 0xFF4ADE80 : 0xFF6FB6FF;
        hintStrokePaint.setColor(color);
        canvas.drawRoundRect(box, d * 16f, d * 16f, hintBgPaint);
        canvas.drawRoundRect(box, d * 16f, d * 16f, hintStrokePaint);

        Paint.FontMetrics fm = hintTextPaint.getFontMetrics();
        float baseline = top + padV - fm.ascent;
        canvas.drawText(text, cx, baseline, hintTextPaint);
        if (line2 > 0) {
            canvas.drawText(r.detail, cx, baseline + gap + line2, detailPaint);
        }
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}
