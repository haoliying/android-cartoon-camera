package com.qoder.cartooncamera;

/** 一组卡通渲染风格参数。 */
public class StylePreset {
    public final String id;
    public final String name;
    public final float blur;       // 平滑半径
    public final float bilateral;  // 保边平滑强度
    public final float detail;     // 混入原始像素的比例（越小越平整）
    public final float levels;     // 亮度色阶数量
    public final float chroma;     // 色度色阶数量
    public final float sat;        // 饱和度
    public final float edge;       // 描边强度
    public final float thresh;     // 描边阈值
    public final float edgeWidth;  // 描边采样宽度
    public final float halftone;   // 网点强度
    public final float gray;       // 灰度
    public final float pixel;      // 像素块大小
    public final float neon;       // 霓虹
    public final float grain;      // 颗粒
    public final float bright;     // 亮度
    public final float sketch;     // 素描

    public StylePreset(String id, String name, float blur, float bilateral, float detail,
                       float levels, float chroma, float sat, float edge, float thresh, float edgeWidth,
                       float halftone, float gray, float pixel, float neon, float grain,
                       float bright, float sketch) {
        this.id = id;
        this.name = name;
        this.blur = blur;
        this.bilateral = bilateral;
        this.detail = detail;
        this.levels = levels;
        this.chroma = chroma;
        this.sat = sat;
        this.edge = edge;
        this.thresh = thresh;
        this.edgeWidth = edgeWidth;
        this.halftone = halftone;
        this.gray = gray;
        this.pixel = pixel;
        this.neon = neon;
        this.grain = grain;
        this.bright = bright;
        this.sketch = sketch;
    }

    public static final StylePreset[] ALL = {
            new StylePreset("cartoon", "卡通", 2.2f, 0.20f, 0.08f, 8f, 16f, 1.25f, 0.80f, 0.30f, 1.2f, 0f, 0f, 0f, 0f, 0f, 1.06f, 0f),
            new StylePreset("comic", "漫画", 1.8f, 0.15f, 0.06f, 5f, 8f, 1.05f, 0.95f, 0.22f, 1.6f, 0.22f, 0f, 0f, 0f, 0f, 1.02f, 0f),
            new StylePreset("water", "水彩", 2.4f, 0.15f, 0.16f, 6f, 7f, 1.50f, 0.38f, 0.26f, 2.4f, 0f, 0f, 0f, 0f, 0.07f, 1.12f, 0f),
            new StylePreset("sketch", "素描", 2.0f, 0.20f, 0.10f, 8f, 8f, 1.00f, 1.00f, 0.18f, 1.4f, 0f, 1f, 0f, 0f, 0.40f, 1.00f, 1f),
            new StylePreset("pixel", "像素", 1.2f, 0.00f, 0.00f, 6f, 8f, 1.30f, 0.60f, 0.30f, 1.0f, 0f, 0f, 8f, 0f, 0f, 1.02f, 0f),
            new StylePreset("neon", "霓虹", 2.0f, 0.30f, 0.10f, 6f, 8f, 1.40f, 1.00f, 0.20f, 1.2f, 0f, 0f, 0f, 1f, 0.10f, 1.05f, 0f),
    };

    public static StylePreset byId(String id) {
        for (StylePreset p : ALL) {
            if (p.id.equals(id)) return p;
        }
        return ALL[0];
    }
}
