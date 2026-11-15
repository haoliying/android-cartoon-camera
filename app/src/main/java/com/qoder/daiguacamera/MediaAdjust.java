package com.qoder.daiguacamera;

import android.graphics.Bitmap;

/**
 * 照片调色的 CPU 实现：相册编辑器用它做实时预览与成片输出。
 * 视频走 GalleryProcessor 里的同参数 GLSL 管线，两者视觉尽量保持一致。
 */
public class MediaAdjust {

    private MediaAdjust() {
    }

    public static Bitmap apply(Bitmap src, AdjustPreset p) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        float inv = clamp(p.invert, 0f, 1f);
        float[] hueM = NegativeParams.hueMatrix(p.hue);
        float lo = 0f, hi = 1f;
        if (p.auto) {
            float[] lv = autoLevels(px, inv);
            lo = lv[0];
            hi = lv[1];
        }
        float sat = clamp(p.sat, 0f, 2.5f) * (p.auto ? 1.10f : 1f);
        float con = clamp(p.contrast, 0.3f, 2f);
        float temp = clamp(p.temp, -1f, 1f);
        float bright = clamp(p.bright, -1f, 1f);
        float vig = clamp(p.vignette, 0f, 1f);
        float grain = clamp(p.grain, 0f, 1f);
        float ab = clamp(p.aberration, 0f, 1f);
        float cx = w / 2f;
        float cy = h / 2f;
        float maxR = (float) Math.sqrt(cx * cx + cy * cy);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int c = px[i];

                // 色差：红蓝通道沿水平方向朝相反方向偏移，边缘越远越明显
                float r;
                float g;
                float b;
                if (ab > 0.001f) {
                    float nx = (x - cx) / Math.max(1f, cx);
                    int dx = Math.round(ab * 6f * nx);
                    int xr = Math.max(0, Math.min(w - 1, x + dx));
                    int xb = Math.max(0, Math.min(w - 1, x - dx));
                    r = ((px[y * w + xr] >> 16) & 0xFF) / 255f;
                    g = ((c >> 8) & 0xFF) / 255f;
                    b = (px[y * w + xb] & 0xFF) / 255f;
                } else {
                    r = ((c >> 16) & 0xFF) / 255f;
                    g = ((c >> 8) & 0xFF) / 255f;
                    b = (c & 0xFF) / 255f;
                }

                // 底片转正
                if (inv > 0.001f) {
                    r = r + (1f - r - r) * inv;
                    g = g + (1f - g - g) * inv;
                    b = b + (1f - b - b) * inv;
                }

                // 自动色阶
                if (p.auto) {
                    float span = Math.max(0.05f, hi - lo);
                    r = (r - lo) / span;
                    g = (g - lo) / span;
                    b = (b - lo) / span;
                }

                // 色温 / 亮度
                r += temp * 0.12f + bright * 0.35f;
                b -= temp * 0.12f - bright * 0.35f;
                g += bright * 0.28f;

                // 对比
                r = (r - 0.5f) * con + 0.5f;
                g = (g - 0.5f) * con + 0.5f;
                b = (b - 0.5f) * con + 0.5f;

                // 饱和度
                float l = 0.299f * r + 0.587f * g + 0.114f * b;
                r = l + (r - l) * sat;
                g = l + (g - l) * sat;
                b = l + (b - l) * sat;

                // 色相
                if (hueM != null) {
                    float rr = hueM[0] * r + hueM[1] * g + hueM[2] * b;
                    float rg = hueM[3] * r + hueM[4] * g + hueM[5] * b;
                    float rb = hueM[6] * r + hueM[7] * g + hueM[8] * b;
                    r = rr;
                    g = rg;
                    b = rb;
                }

                // 暗角
                if (vig > 0.001f) {
                    float dx = x - cx;
                    float dy = y - cy;
                    float d = (float) Math.sqrt(dx * dx + dy * dy) / maxR;
                    float f = 1f - vig * 0.85f * d * d;
                    r *= f;
                    g *= f;
                    b *= f;
                }

                // 颗粒
                if (grain > 0.001f) {
                    float n = (NegativeParams.noise(x, y) - 0.5f) * grain * 0.35f;
                    r += n;
                    g += n;
                    b += n;
                }

                int rr = (int) (clamp(r, 0f, 1f) * 255f + 0.5f);
                int rg = (int) (clamp(g, 0f, 1f) * 255f + 0.5f);
                int rb = (int) (clamp(b, 0f, 1f) * 255f + 0.5f);
                px[i] = (c & 0xFF000000) | (rr << 16) | (rg << 8) | rb;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * 自动色阶：按亮度直方图取 1% / 99% 分位作为黑白场。
     * 返回归一化后的 {lo, hi}。
     */
    private static float[] autoLevels(int[] px, float inv) {
        int[] hist = new int[256];
        int total = px.length;
        for (int c : px) {
            float r = ((c >> 16) & 0xFF) / 255f;
            float g = ((c >> 8) & 0xFF) / 255f;
            float b = (c & 0xFF) / 255f;
            if (inv > 0.001f) {
                r = r + (1f - r - r) * inv;
                g = g + (1f - g - g) * inv;
                b = b + (1f - b - b) * inv;
            }
            int l = (int) (clamp(0.299f * r + 0.587f * g + 0.114f * b, 0f, 1f) * 255f);
            hist[l]++;
        }
        int lowCut = Math.max(1, total / 100);
        int highCut = Math.max(1, total / 100);
        int acc = 0;
        int lo = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= lowCut) {
                lo = i;
                break;
            }
        }
        acc = 0;
        int hi = 255;
        for (int i = 255; i >= 0; i--) {
            acc += hist[i];
            if (acc >= highCut) {
                hi = i;
                break;
            }
        }
        if (hi - lo < 12) {
            lo = 0;
            hi = 255;
        }
        return new float[]{lo / 255f, hi / 255f};
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
