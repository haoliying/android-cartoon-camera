package com.qoder.daiguacamera;

/**
 * 相册调色的一个“调整模式”：亮度、对比、饱和、色温、色相、色差、暗角、颗粒、
 * 底片反转与自动优化。内置模式固定，用户调好后可以另存为自定义模式。
 */
public class AdjustPreset {
    public String id;
    public String name;
    public boolean auto;
    public float bright;      // -1..1
    public float contrast = 1f;
    public float sat = 1f;
    public float temp;        // -1 冷 .. 1 暖
    public float hue;         // -180..180 度
    public float aberration;  // 0..1 色差
    public float vignette;    // 0..1 暗角
    public float grain;       // 0..1 颗粒
    public float invert;      // 0..1 底片转正
    public boolean builtin;
    public boolean custom;

    public AdjustPreset(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public static AdjustPreset make(String id, String name, boolean auto, float bright,
                                    float contrast, float sat, float temp, float hue,
                                    float aberration, float vignette, float grain, float invert) {
        AdjustPreset p = new AdjustPreset(id, name);
        p.auto = auto;
        p.bright = bright;
        p.contrast = contrast;
        p.sat = sat;
        p.temp = temp;
        p.hue = hue;
        p.aberration = aberration;
        p.vignette = vignette;
        p.grain = grain;
        p.invert = invert;
        p.builtin = true;
        return p;
    }

    public static final AdjustPreset[] ALL = {
            make("none", "原图", false, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
            make("auto", "自动优化", true, 0.02f, 1.08f, 1.12f, 0f, 0f, 0f, 0f, 0f, 0f),
            make("vivid", "鲜明", false, 0.02f, 1.14f, 1.30f, 0.05f, 0f, 0f, 0f, 0f, 0f),
            make("soft", "柔和", false, 0.04f, 0.92f, 0.90f, 0.08f, 0f, 0f, 0.04f, 0.03f, 0f),
            make("mono", "黑白", false, 0f, 1.10f, 0f, 0f, 0f, 0f, 0.10f, 0.03f, 0f),
            make("retro", "复古", false, 0.02f, 0.96f, 0.82f, 0.34f, 0f, 0f, 0.28f, 0.10f, 0f),
            make("film", "胶片", false, 0.01f, 1.05f, 1.04f, 0.14f, 0f, 0f, 0.16f, 0.14f, 0f),
            make("cool", "冷调", false, 0f, 1.04f, 1.06f, -0.36f, 0f, 0f, 0f, 0f, 0f),
            make("warm", "暖调", false, 0.02f, 1.04f, 1.06f, 0.36f, 0f, 0f, 0f, 0f, 0f),
            make("teal", "青橙", false, 0f, 1.12f, 1.16f, 0.10f, -8f, 0f, 0.08f, 0.02f, 0f),
            make("negative", "底片转成片", false, 0.02f, 1.06f, 1.08f, -0.46f, 0f, 0f, 0f, 0f, 1f),
    };

    public static AdjustPreset byId(String id) {
        for (AdjustPreset p : ALL) {
            if (p.id.equals(id)) return p;
        }
        return ALL[0];
    }

    public AdjustPreset copy() {
        AdjustPreset p = new AdjustPreset(id, name);
        p.auto = auto;
        p.bright = bright;
        p.contrast = contrast;
        p.sat = sat;
        p.temp = temp;
        p.hue = hue;
        p.aberration = aberration;
        p.vignette = vignette;
        p.grain = grain;
        p.invert = invert;
        p.builtin = builtin;
        p.custom = custom;
        return p;
    }

    public boolean isNeutral() {
        return !auto && Math.abs(bright) < 0.001f && Math.abs(contrast - 1f) < 0.001f
                && Math.abs(sat - 1f) < 0.001f && Math.abs(temp) < 0.001f
                && Math.abs(hue) < 0.01f && aberration < 0.001f && vignette < 0.001f
                && grain < 0.001f && invert < 0.001f;
    }

    public String summary() {
        if (auto) return "自动优化 · 自动色阶与饱和度";
        StringBuilder b = new StringBuilder();
        if (invert > 0.5f) b.append("底片转正");
        if (Math.abs(bright) > 0.01f) {
            if (b.length() > 0) b.append(" · ");
            b.append("亮度 ").append(signed(bright * 100, 0));
        }
        if (Math.abs(contrast - 1f) > 0.01f) {
            if (b.length() > 0) b.append(" · ");
            b.append("对比 ").append(Math.round(contrast * 100)).append("%");
        }
        if (Math.abs(sat - 1f) > 0.01f) {
            if (b.length() > 0) b.append(" · ");
            b.append("饱和 ").append(Math.round(sat * 100)).append("%");
        }
        if (Math.abs(temp) > 0.01f) {
            if (b.length() > 0) b.append(" · ");
            b.append(temp > 0 ? "偏暖" : "偏冷");
        }
        if (Math.abs(hue) > 0.1f) {
            if (b.length() > 0) b.append(" · ");
            b.append("色相 ").append(signed(hue, 0)).append("°");
        }
        if (aberration > 0.01f) {
            if (b.length() > 0) b.append(" · ");
            b.append("色差 ").append(Math.round(aberration * 100)).append("%");
        }
        if (vignette > 0.01f) {
            if (b.length() > 0) b.append(" · ");
            b.append("暗角 ").append(Math.round(vignette * 100)).append("%");
        }
        if (grain > 0.01f) {
            if (b.length() > 0) b.append(" · ");
            b.append("颗粒 ").append(Math.round(grain * 100)).append("%");
        }
        return b.length() == 0 ? "原图" : b.toString();
    }

    private static String signed(float v, int digits) {
        return String.format(java.util.Locale.US, "%+." + digits + "f", v);
    }
}
