package com.qoder.daiguacamera;

/** 拍照参数：普通 / 专业 / 傻瓜 三种模式共用同一份描述，最后交给 Camera2。 */
public class CaptureParams {
    public static final int FOCUS_AUTO = 0;
    public static final int FOCUS_INFINITY = 1;
    public static final int FOCUS_MACRO = 2;
    public static final int FOCUS_MANUAL = 3;

    /** true 表示手动曝光（iso + exposureNs 生效），false 表示自动曝光 + EV 补偿。 */
    public boolean manualExposure;
    public int iso = 200;
    public long exposureNs = 1_000_000_000L / 125;
    public float ev;
    public boolean wbAuto = true;
    public int kelvin = 5200;
    public int focusMode = FOCUS_AUTO;
    /** 手动对焦距离（屈光度，0 = 无穷远，10 ≈ 10 厘米）。 */
    public float diopter;
    /** 自动场景：由相机按光线和动静自动挑选场景模式。 */
    public boolean autoScene;

    public CaptureParams copy() {
        CaptureParams p = new CaptureParams();
        p.manualExposure = manualExposure;
        p.iso = iso;
        p.exposureNs = exposureNs;
        p.ev = ev;
        p.wbAuto = wbAuto;
        p.kelvin = kelvin;
        p.focusMode = focusMode;
        p.diopter = diopter;
        p.autoScene = autoScene;
        return p;
    }

    public static String shutterText(long ns) {
        if (ns <= 0) return "auto";
        double s = ns / 1e9;
        if (s >= 1.0) {
            if (Math.abs(s - Math.rint(s)) < 0.05) return String.valueOf((int) Math.rint(s)) + "s";
            return String.format(java.util.Locale.US, "%.1fs", s);
        }
        long denom = Math.round(1.0 / s);
        return "1/" + denom + "s";
    }

    public String summary() {
        StringBuilder b = new StringBuilder();
        if (manualExposure) {
            b.append("ISO ").append(iso).append(" · ").append(shutterText(exposureNs));
        } else {
            b.append("自动曝光");
            if (Math.abs(ev) > 0.01f) {
                b.append(" EV ").append(ev > 0 ? "+" : "").append(String.format(
                        java.util.Locale.US, "%.1f", ev));
            }
        }
        b.append(" · ");
        b.append(wbAuto ? "自动白平衡" : kelvin + "K");
        b.append(" · ");
        switch (focusMode) {
            case FOCUS_INFINITY:
                b.append("无穷远对焦");
                break;
            case FOCUS_MACRO:
                b.append("微距对焦");
                break;
            case FOCUS_MANUAL:
                b.append("手动对焦");
                break;
            default:
                b.append("自动对焦");
        }
        return b.toString();
    }
}
