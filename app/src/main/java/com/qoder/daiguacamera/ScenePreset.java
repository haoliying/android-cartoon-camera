package com.qoder.daiguacamera;

/**
 * 傻瓜模式的一个场景：名字 + 一句提示 + 一整套“专业参数”。
 * 内置场景的参数可以在界面上改；改过的存进 SceneStore，也可以新增自定义场景。
 */
public class ScenePreset {
    public String id;
    public String name;
    public String hint;
    public boolean manual;
    public int iso;
    public long exposureNs;
    public float ev;
    public int kelvin;
    public boolean wbAuto;
    public int focusMode;
    public float diopter;
    /** 用户新增的场景（可改名、可删除）。 */
    public boolean custom;
    /** 内置场景被改过参数（* 标记，可恢复默认）。 */
    public boolean edited;
    /** 日常 / 自动场景：固定语义，不参与参数编辑。 */
    public boolean special;
    /** 自动场景：交给相机按光线和动静自动挑参数。 */
    public boolean autoDetect;

    public ScenePreset(String id, String name, String hint, boolean manual, int iso,
                       long exposureNs, float ev, int kelvin, boolean wbAuto,
                       int focusMode, float diopter) {
        this.id = id;
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
    private static ScenePreset auto(String id, String name, String hint, float ev, int kelvin) {
        return new ScenePreset(id, name, hint, false, 200, ns(1 / 125.0), ev, kelvin, false,
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
        p.autoScene = autoDetect;
        return p;
    }

    public ScenePreset copy() {
        ScenePreset s = new ScenePreset(id, name, hint, manual, iso, exposureNs, ev, kelvin,
                wbAuto, focusMode, diopter);
        s.custom = custom;
        s.edited = edited;
        s.special = special;
        s.autoDetect = autoDetect;
        return s;
    }

    /** 把参数复制成另一个场景（用于恢复默认）。 */
    public void copyParamsFrom(ScenePreset s) {
        manual = s.manual;
        iso = s.iso;
        exposureNs = s.exposureNs;
        ev = s.ev;
        kelvin = s.kelvin;
        wbAuto = s.wbAuto;
        focusMode = s.focusMode;
        diopter = s.diopter;
    }

    /** 与内置默认值相比是否改过。 */
    public boolean differsFrom(ScenePreset d) {
        return d == null || manual != d.manual || iso != d.iso || exposureNs != d.exposureNs
                || Math.abs(ev - d.ev) > 0.001f || kelvin != d.kelvin || wbAuto != d.wbAuto
                || focusMode != d.focusMode || Math.abs(diopter - d.diopter) > 0.001f;
    }

    public static ScenePreset defaultById(String id) {
        if (id == null) return null;
        for (ScenePreset s : ALL) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    /** 日常 / 自动场景：固定在前两个，不支持编辑参数。 */
    private static ScenePreset special(String id, String name, String hint, boolean autoDetect) {
        ScenePreset s = new ScenePreset(id, name, hint, false, 200, ns(1 / 125.0), 0f, 5200, true,
                CaptureParams.FOCUS_AUTO, 0f);
        s.special = true;
        s.autoDetect = autoDetect;
        return s;
    }

    public static final ScenePreset[] ALL = {
            special("daily", "日常", "普通拍摄：全自动，拿起就拍", false),
            special("auto_scene", "自动场景", "自动识别光线和动静，匹配最合适的参数", true),
            // 光比大、需要压高光的风光
            new ScenePreset("sunrise", "日出", "低 ISO 压住太阳，暖白平衡", true, 100, ns(1 / 250.0), 0.3f, 4200, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("sunset", "日落", "保留天空层次，稍欠曝", true, 100, ns(1 / 200.0), 0.5f, 4800, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("afterglow", "晚霞", "提高曝光补偿让云彩更透", true, 100, ns(1 / 160.0), 0.7f, 5600, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("moon", "月亮", "长焦+低 ISO 快速门，别过曝", true, 100, ns(1 / 125.0), -1.0f, 4300, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("stars", "星空", "高 ISO 长曝光，对焦无穷远", true, 3200, ns(2.0), 0f, 4000, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("night", "夜景", "平衡噪点与快门，略欠曝保灯光", true, 800, ns(1 / 15.0), -0.3f, 3900, false, CaptureParams.FOCUS_AUTO, 0f),
            new ScenePreset("fireworks", "烟花", "慢门拖出轨迹，压暗背景", true, 200, ns(0.25), -0.5f, 5000, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("lamp", "灯", "慢门收集光点，低 ISO 保干净", true, 100, ns(0.5), -1.0f, 4000, false, CaptureParams.FOCUS_AUTO, 0f),
            // 水面与自然
            new ScenePreset("sea", "大海", "高速快门凝固浪花", true, 100, ns(1 / 500.0), 0f, 6500, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("waterfall", "瀑布", "慢门拉出丝绢水感", true, 100, ns(1 / 8.0), -0.7f, 6000, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("mountain", "远山", "小光圈等效、清晰通透", true, 100, ns(1 / 800.0), 0f, 6800, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("cloud", "云", "保留云层层次，稍加曝光", true, 100, ns(1 / 800.0), 0.3f, 6200, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("tyndall", "丁达尔", "压曝光让光束更明显", true, 100, ns(1 / 400.0), -0.7f, 5200, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("fog", "雾", "加曝光保持雾气发白", true, 100, ns(1 / 250.0), 0.7f, 7000, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("rainbow", "彩虹", "稍加对比与曝光，色彩更饱满", true, 100, ns(1 / 320.0), 0.3f, 6000, false, CaptureParams.FOCUS_INFINITY, 0f),
            // 天气细节
            new ScenePreset("raindrop", "雨滴", "微距+高速快门，抓住水珠", true, 200, ns(1 / 500.0), -0.3f, 6200, false, CaptureParams.FOCUS_MACRO, 8f),
            new ScenePreset("rainy", "雨天", "中等快门，压住反光", true, 200, ns(1 / 125.0), -0.3f, 6500, false, CaptureParams.FOCUS_AUTO, 0f),
            new ScenePreset("rainlines", "长雨丝", "慢门把雨拉成线", true, 100, ns(1 / 30.0), -0.7f, 6500, false, CaptureParams.FOCUS_INFINITY, 0f),
            new ScenePreset("snowflake", "雪花", "极高速快门冻结雪花", true, 100, ns(1 / 1000.0), 0.7f, 6500, false, CaptureParams.FOCUS_MACRO, 6f),
            new ScenePreset("snowday", "雪天", "白加，避免雪发灰", true, 100, ns(1 / 500.0), 1.0f, 6800, false, CaptureParams.FOCUS_AUTO, 0f),
            new ScenePreset("snowscape", "雪景", "白加，保住雪的层次", true, 100, ns(1 / 400.0), 1.0f, 6200, false, CaptureParams.FOCUS_INFINITY, 0f),
            // 人像与静物
            auto("portrait", "人像", "自动曝光+小幅提亮，肤色自然", 0.3f, 5200),
            auto("backlight", "逆光", "大补偿把脸从暗部拉回来", 1.0f, 5600),
            new ScenePreset("food", "美食", "微距+暖色，突出质感", true, 200, ns(1 / 125.0), 0.3f, 4600, false, CaptureParams.FOCUS_MACRO, 5f),
            new ScenePreset("flower", "花", "微距+冷调，花瓣更通透", true, 100, ns(1 / 250.0), 0.3f, 5500, false, CaptureParams.FOCUS_MACRO, 7f),
    };
}
