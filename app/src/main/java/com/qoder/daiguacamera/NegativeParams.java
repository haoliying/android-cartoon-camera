package com.qoder.daiguacamera;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

/**
 * 底片模式的成像参数：把画面反相成负片，再叠加橙色底罩、对比、饱和、颗粒等。
 * 预览与拍照使用同一套数值，保证所见即所得；相册里的“底片转成片”按相反方向处理。
 */
public class NegativeParams {
    /** 反相强度 0..1。 */
    public float invert = 1f;
    /** 橙色底罩 0..1（负片未曝光区域偏橙）。 */
    public float mask = 0.45f;
    /** 对比度 0.6..1.4。 */
    public float contrast = 1f;
    /** 饱和度 0..1.6。 */
    public float saturation = 1f;
    /** 颗粒 0..0.5。 */
    public float grain = 0.08f;
    /** 色相偏移（度）-45..45。 */
    public float hue = 0f;
    /** 亮度偏移 -0.2..0.2。 */
    public float lift = 0f;

    private static final float[] ORANGE = {0.95f, 0.52f, 0.20f};

    public NegativeParams copy() {
        NegativeParams p = new NegativeParams();
        p.invert = invert;
        p.mask = mask;
        p.contrast = contrast;
        p.saturation = saturation;
        p.grain = grain;
        p.hue = hue;
        p.lift = lift;
        return p;
    }

    public String summary() {
        String kind = saturation < 0.05f ? "黑白负片" : "彩色负片";
        return kind + " · 底罩 " + Math.round(mask * 100) + "% · 对比 "
                + Math.round(contrast * 100) + "%";
    }

    public static NegativeParams load(SharedPreferences prefs) {
        NegativeParams p = new NegativeParams();
        p.invert = prefs.getFloat("neg_invert", p.invert);
        p.mask = prefs.getFloat("neg_mask", p.mask);
        p.contrast = prefs.getFloat("neg_contrast", p.contrast);
        p.saturation = prefs.getFloat("neg_sat", p.saturation);
        p.grain = prefs.getFloat("neg_grain", p.grain);
        p.hue = prefs.getFloat("neg_hue", p.hue);
        p.lift = prefs.getFloat("neg_lift", p.lift);
        return p;
    }

    public void save(SharedPreferences prefs) {
        prefs.edit()
                .putFloat("neg_invert", invert)
                .putFloat("neg_mask", mask)
                .putFloat("neg_contrast", contrast)
                .putFloat("neg_sat", saturation)
                .putFloat("neg_grain", grain)
                .putFloat("neg_hue", hue)
                .putFloat("neg_lift", lift)
                .apply();
    }

    /** 给已经拍好的照片套用底片外观（相机 JPEG 走离线管线时使用）。 */
    public Bitmap apply(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        float m = Math.max(0f, Math.min(1f, mask)) * 0.8f;
        float inv = Math.max(0f, Math.min(1f, invert));
        float sat = Math.max(0f, Math.min(2f, saturation));
        float con = Math.max(0.3f, Math.min(2f, contrast));
        float g = Math.max(0f, Math.min(0.8f, grain));
        float[] hueM = hueMatrix(hue);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int c = px[i];
                float r = ((c >> 16) & 0xFF) / 255f;
                float gg = ((c >> 8) & 0xFF) / 255f;
                float b = (c & 0xFF) / 255f;

                // 反相
                r = r + (1f - r - r) * inv;
                gg = gg + (1f - gg - gg) * inv;
                b = b + (1f - b - b) * inv;

                // 橙色底罩：暗部偏橙，亮部保留细节
                if (m > 0.001f) {
                    float rr = r * (1f - m) + ORANGE[0] * (0.35f + 0.65f * r) * m;
                    float rg = gg * (1f - m) + ORANGE[1] * (0.35f + 0.65f * gg) * m;
                    float rb = b * (1f - m) + ORANGE[2] * (0.35f + 0.65f * b) * m;
                    r = rr;
                    gg = rg;
                    b = rb;
                }

                // 饱和度
                float l = 0.299f * r + 0.587f * gg + 0.114f * b;
                r = l + (r - l) * sat;
                gg = l + (gg - l) * sat;
                b = l + (b - l) * sat;

                // 色相
                if (hueM != null) {
                    float rr = hueM[0] * r + hueM[1] * gg + hueM[2] * b;
                    float rg = hueM[3] * r + hueM[4] * gg + hueM[5] * b;
                    float rb = hueM[6] * r + hueM[7] * gg + hueM[8] * b;
                    r = rr;
                    gg = rg;
                    b = rb;
                }

                // 对比与亮度
                r = (r - 0.5f) * con + 0.5f + lift;
                gg = (gg - 0.5f) * con + 0.5f + lift;
                b = (b - 0.5f) * con + 0.5f + lift;

                // 颗粒
                if (g > 0.001f) {
                    float n = noise(x, y) - 0.5f;
                    r += n * g * 0.5f;
                    gg += n * g * 0.5f;
                    b += n * g * 0.5f;
                }

                int rr = (int) (Math.max(0f, Math.min(1f, r)) * 255f + 0.5f);
                int rg = (int) (Math.max(0f, Math.min(1f, gg)) * 255f + 0.5f);
                int rb = (int) (Math.max(0f, Math.min(1f, b)) * 255f + 0.5f);
                px[i] = (c & 0xFF000000) | (rr << 16) | (rg << 8) | rb;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** 与 GLSL 里同一套色相旋转矩阵，保证预览和成片一致。 */
    static float[] hueMatrix(float degrees) {
        if (Math.abs(degrees) < 0.01f) return null;
        double a = Math.toRadians(degrees);
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        return new float[]{
                0.299f + 0.701f * c + 0.168f * s,
                0.587f - 0.587f * c + 0.330f * s,
                0.114f - 0.114f * c - 0.497f * s,
                0.299f - 0.299f * c - 0.328f * s,
                0.587f + 0.413f * c + 0.035f * s,
                0.114f - 0.114f * c + 0.292f * s,
                0.299f - 0.300f * c + 1.250f * s,
                0.587f - 0.588f * c - 1.050f * s,
                0.114f + 0.886f * c - 0.203f * s
        };
    }

    static float noise(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >> 13)) * 1274126177;
        return ((n ^ (n >> 16)) & 0xFFFF) / 65535f;
    }
}
