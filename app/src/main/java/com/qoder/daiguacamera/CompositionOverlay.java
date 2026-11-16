package com.qoder.daiguacamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * 构图叠加层：网格、主体框、人脸框、检测到的地平线，以及一条构图提示。
 * 只负责画，不处理触摸，不会挡住对焦和变焦手势。
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
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean active;
    private int gridMode = CompositionAdvisor.GRID_THIRDS;

    private CompositionAdvisor.Result result;
    private boolean hintReady;
    private float bottomInsetPx;

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

        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(0xFFF4F5FA);

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

    /** 底部被面板遮住的高度，提示条会排在它上面，避免压到变焦盘和快门区。 */
    public void setBottomInset(float px) {
        if (Math.abs(px - bottomInsetPx) < 1f) return;
        bottomInsetPx = px;
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

        drawGrid(canvas, w, h, d);

        CompositionAdvisor.Result r = result;
        if (r == null) return;

        if (r.horizonY >= 0f && r.horizonConf > 0.45f) {
            drawHorizon(canvas, w, h, r);
        }
        if (r.subject != null) {
            subjectPaint.setColor(r.severity == CompositionAdvisor.SEV_GOOD
                    ? 0xCC4ADE80 : 0xCCFFB74D);
            drawBox(canvas, r.subject, w, h, subjectPaint, d, 8f);
        }
        for (float[] f : r.faces) {
            drawBox(canvas, f, w, h, facePaint, d, 7f);
        }
        if (hintReady && r.hint != null) {
            drawHint(canvas, w, h, r, d);
        }
    }

    private void drawGrid(Canvas canvas, int w, int h, float d) {
        if (gridMode == CompositionAdvisor.GRID_CENTER) {
            canvas.drawLine(w * 0.5f, 0, w * 0.5f, h, gridPaint);
            canvas.drawLine(0, h * 0.5f, w, h * 0.5f, gridPaint);
            canvas.drawCircle(w * 0.5f, h * 0.5f, d * 26f, gridPaint);
            return;
        }
        if (gridMode == CompositionAdvisor.GRID_DIAGONAL) {
            canvas.drawLine(0, 0, w, h, gridPaint);
            canvas.drawLine(w, 0, 0, h, gridPaint);
            canvas.drawCircle(w * 0.5f, h * 0.5f, d * 22f, gridPaint);
            return;
        }
        for (int i = 1; i <= 2; i++) {
            float x = w * i / 3f;
            float y = h * i / 3f;
            canvas.drawLine(x, 0, x, h, gridPaint);
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        // 四角小刻度，强调“把主体放在交叉点附近”
        float len = d * 14f;
        float inset = d * 10f;
        canvas.drawLine(inset, inset, inset + len, inset, gridPaint);
        canvas.drawLine(inset, inset, inset, inset + len, gridPaint);
        canvas.drawLine(w - inset, inset, w - inset - len, inset, gridPaint);
        canvas.drawLine(w - inset, inset, w - inset, inset + len, gridPaint);
        canvas.drawLine(inset, h - inset, inset + len, h - inset, gridPaint);
        canvas.drawLine(inset, h - inset, inset, h - inset - len, gridPaint);
        canvas.drawLine(w - inset, h - inset, w - inset - len, h - inset, gridPaint);
        canvas.drawLine(w - inset, h - inset, w - inset, h - inset - len, gridPaint);
    }

    private void drawHorizon(Canvas canvas, int w, int h, CompositionAdvisor.Result r) {
        float yc = r.horizonY * h;
        float slope = (float) Math.tan(Math.toRadians(r.horizonTilt));
        float y0 = yc - slope * w * 0.5f;
        float y1 = yc + slope * w * 0.5f;
        canvas.drawLine(0, y0, w, y1, horizonPaint);
    }

    private void drawBox(Canvas canvas, float[] b, int w, int h, Paint p, float d, float radius) {
        if (b == null || b.length < 4) return;
        RectF rect = new RectF(b[0] * w, b[1] * h,
                (b[0] + b[2]) * w, (b[1] + b[3]) * h);
        // 四角加一点外扩，看起来像取景框
        rect.inset(-d * 3f, -d * 3f);
        canvas.drawRoundRect(rect, radius * d / 8f, radius * d / 8f, p);
    }

    private void drawHint(Canvas canvas, int w, int h, CompositionAdvisor.Result r, float d) {
        String text = r.hint;
        if (r.arrowX > 0.5f) text = "→ " + text;
        else if (r.arrowX < -0.5f) text = "← " + text;
        else if (r.arrowY > 0.5f) text = "↓ " + text;
        else if (r.arrowY < -0.5f) text = "↑ " + text;

        float padH = d * 14f;
        float padV = d * 8f;
        float textW = hintTextPaint.measureText(text);
        float detailW = r.detail == null ? 0 : detailPaint.measureText(r.detail);
        float boxW = Math.min(w - d * 24f, Math.max(textW, detailW) + padH * 2f);
        float line1 = hintTextPaint.getTextSize();
        float line2 = r.detail == null || r.detail.isEmpty() ? 0 : detailPaint.getTextSize();
        float gap = line2 > 0 ? d * 3f : 0;
        float boxH = line1 + line2 + gap + padV * 2f;

        float cx = w * 0.5f;
        float top = h - bottomInsetPx - boxH - d * 12f;
        top = Math.max(d * 76f, top);
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
