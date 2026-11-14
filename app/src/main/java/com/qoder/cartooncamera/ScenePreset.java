package com.qoder.cartooncamera;

/**
 * 傻瓜模式的场景表：每个场景直接对应一组“专业参数”，
 * 选了场景就自动套用，用户不用碰任何参数。
 */
public class ScenePreset {
    public final String name;
    public final String hint;
    private final boolean manual;
    private final int iso;
    private final long exposureNs;
    private final float ev;
    private final int kelvin;
    private final boolean wbAuto;
    private final int focusMode;
    private final float diopter;

    private ScenePreset(String name, String hint, boolean manual, int iso, long exposureNs,
                        float ev, int kelvin, boolean wbAuto, int focusMode, float diopter) {
        this.name = name;
        this.hint = hint;
        this.manual = manual;
        this.iso = iso;
        this.exposureNs = exposureNs;
        this.ev = ev;
        this.kelvin = kelvin;
        this.wbAuto = wbAuto;
        this.focusMode = focusMode;
        this.diopter = diopter;
    }

    private static long ns(double seconds) {
        return (long) (seconds * 1_000_000_000L);
    }

    /** 自动曝光的场景，只给曝光补偿与白平衡。 */
    private static ScenePreset auto(String name, String hint, float ev, int kelvin) {
        return new ScenePreset(name, hint, false, 200, ns(1 / 125.0), ev, kelvin, false,
                CaptureParams.FOCUS_AUTO, 0f);
    }

    public CaptureParams toParams() {
        CaptureParams p = new CaptureParams();
        p.manualExposure = manual;
        p.iso = iso;
        p.exposureNs = exposureNs;
        p.ev = ev;
        p.wbAuto = wbAuto;
        p.kelvin = kelvin;
        p.focusMode = focusMode;
        p.diopter = diopter;
        return p;
    }

    public static final ScenePreset[] ALL = {
            // 光比大、需要压高光的风光
            new ScenePreset("日出", "低 ISO 压住太阳，暖白平衡", true, 100, ns(1 / 250.0), 0.3f, 4200, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("日落", "保留天空层次，稍欠曝", true, 100, ns(1 / 200.0), 0.5f, 4800, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("晚霞", "提高曝光补偿让云彩更透", true, 100, ns(1 / 160.0), 0.7f, 5600, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("月亮", "长焦+低 ISO 快速门，别过曝", true, 100, ns(1 / 125.0), -1.0f, 4300, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("星空", "高 ISO 长曝光，对焦无穷远", true, 3200, ns(2.0), 0f, 4000, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("夜景", "平衡噪点与快门，略欠曝保灯光", true, 800, ns(1 / 15.0), -0.3f, 3900, false, CaptureParams.FOCUS_AUTO, 0f),
            new ScenePreset("烟花", "慢门拖出轨迹，压暗背景", true, 200, ns(0.25), -0.5f, 5000, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("灯", "慢门收集光点，低 ISO 保干净", true, 100, ns(0.5), -1.0f, 4000, false, CaptureParams.FOCUS_AUTO, 0f),
            // 水面与自然
            new ScenePreset("大海", "高速快门凝固浪花", true, 100, ns(1 / 500.0), 0f, 6500, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("瀑布", "慢门拉出丝绢水感", true, 100, ns(1 / 8.0), -0.7f, 6000, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("远山", "小光圈等效、清晰通透", true, 100, ns(1 / 800.0), 0f, 6800, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("云", "保留云层层次，稍加曝光", true, 100, ns(1 / 800.0), 0.3f, 6200, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("丁达尔", "压曝光让光束更明显", true, 100, ns(1 / 400.0), -0.7f, 5200, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("雾", "加曝光保持雾气发白", true, 100, ns(1 / 250.0), 0.7f, 7000, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("彩虹", "稍加对比与曝光，色彩更饱满", true, 100, ns(1 / 320.0), 0.3f, 6000, false, CaptureParams.FOCUS_INFINITY, 0f),
            // 天气细节
            new ScenePreset("雨滴", "微距+高速快门，抓住水珠", true, 200, ns(1 / 500.0), -0.3f, 6200, false, CaptureParams.FOCUS_MACRO, 8f),
            new ScenePreset("雨天", "中等快门，压住反光", true, 200, ns(1 / 125.0), -0.3f, 6500, false, CaptureParams.FOCUS_AUTO, 0f),
            new ScenePreset("长雨丝", "慢门把雨拉成线", true, 100, ns(1 / 30.0), -0.7f, 6500, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("雪花", "极高速快门冻结雪花", true, 100, ns(1 / 1000.0), 0.7f, 6500, false, CaptureParams.FOCUS_MACRO, 6f),
            new ScenePreset("雪天", "白加，避免雪发灰", true, 100, ns(1 / 500.0), 1.0f, 6800, false, CaptureParams.FOCUS_AUTO, 0f),
            new ScenePreset("雪景", "白加，保住雪的层次", true, 100, ns(1 / 400.0), 1.0f, 6200, false, CaptureParams.FOCUS_INFINITY, 0f),
            // 人像与静物
            auto("人像", "自动曝光+小幅提亮，肤色自然", 0.3f, 5200),
            auto("逆光", "大补偿把脸从暗部拉回来", 1.0f, 5600),
            new ScenePreset("美食", "微距+暖色，突出质感", true, 200, ns(1 / 125.0), 0.3f, 4600, false, CaptureParams.FOCUS_MACRO, 5f),
            new ScenePreset("花", "微距+冷调，花瓣更通透", true, 100, ns(1 / 250.0), 0.3f, 5500, false, CaptureParams.FOCUS_MACRO, 7f),
    };
}
