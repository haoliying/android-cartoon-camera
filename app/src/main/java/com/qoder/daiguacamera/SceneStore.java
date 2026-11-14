package com.qoder.daiguacamera;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 傻瓜模式的场景仓库：内置 25 个场景 + 用户改过的参数 + 用户新增的场景。
 * 只存“改过的”内置场景和自定义场景，没动过的内置场景永远跟代码里的默认值一致。
 */
public class SceneStore {
    private static final String PREFS = "daigua_scenes";
    private static final String KEY_OVERRIDES = "overrides";
    private static final String KEY_CUSTOM = "custom";

    private final SharedPreferences prefs;
    private final List<ScenePreset> scenes = new ArrayList<>();

    public SceneStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public List<ScenePreset> scenes() {
        return scenes;
    }

    public ScenePreset find(String id) {
        for (ScenePreset s : scenes) {
            if (s.id.equals(id)) return s;
        }
        return scenes.isEmpty() ? null : scenes.get(0);
    }

    private void load() {
        scenes.clear();
        JSONObject overrides = readObject(KEY_OVERRIDES);
        for (ScenePreset d : ScenePreset.ALL) {
            ScenePreset s = d.copy();
            JSONObject o = overrides.optJSONObject(d.id);
            if (o != null) {
                readParams(o, s);
                s.edited = s.differsFrom(d);
            }
            scenes.add(s);
        }
        JSONArray arr = readArray(KEY_CUSTOM);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            if (id.isEmpty() || name.isEmpty()) continue;
            ScenePreset s = new ScenePreset(id, name,
                    o.optString("hint", "自定义场景"), true,
                    200, 1_000_000_000L / 125, 0f, 5200, true,
                    CaptureParams.FOCUS_AUTO, 0f);
            readParams(o, s);
            s.custom = true;
            scenes.add(s);
        }
        if (scenes.isEmpty()) scenes.add(ScenePreset.ALL[0].copy());
    }

    /** 把当前场景表写回本地；内置场景只存改过参数的，自定义场景全存。 */
    public void persist() {
        JSONObject overrides = new JSONObject();
        JSONArray custom = new JSONArray();
        for (ScenePreset s : scenes) {
            try {
                JSONObject o = toJson(s);
                if (s.custom) {
                    custom.put(o);
                } else if (s.edited) {
                    overrides.put(s.id, o);
                }
            } catch (Throwable ignored) {
            }
        }
        prefs.edit()
                .putString(KEY_OVERRIDES, overrides.toString())
                .putString(KEY_CUSTOM, custom.toString())
                .apply();
    }

    public void add(ScenePreset s) {
        scenes.add(s);
        persist();
    }

    public void remove(ScenePreset s) {
        scenes.remove(s);
        persist();
    }

    /** 内置场景恢复出厂参数；自定义场景直接删除。 */
    public void reset(ScenePreset s) {
        if (s.custom) {
            scenes.remove(s);
            persist();
            return;
        }
        ScenePreset d = ScenePreset.defaultById(s.id);
        if (d != null) {
            s.copyParamsFrom(d);
            s.edited = false;
        }
        persist();
    }

    private static JSONObject toJson(ScenePreset s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", s.id);
        o.put("name", s.name);
        o.put("hint", s.hint);
        o.put("manual", s.manual);
        o.put("iso", s.iso);
        o.put("exp", s.exposureNs);
        o.put("ev", s.ev);
        o.put("kelvin", s.kelvin);
        o.put("wbAuto", s.wbAuto);
        o.put("focus", s.focusMode);
        o.put("diopter", s.diopter);
        return o;
    }

    private static void readParams(JSONObject o, ScenePreset s) {
        s.manual = o.optBoolean("manual", s.manual);
        s.iso = o.optInt("iso", s.iso);
        s.exposureNs = o.optLong("exp", s.exposureNs);
        s.ev = (float) o.optDouble("ev", s.ev);
        s.kelvin = o.optInt("kelvin", s.kelvin);
        s.wbAuto = o.optBoolean("wbAuto", s.wbAuto);
        s.focusMode = o.optInt("focus", s.focusMode);
        s.diopter = (float) o.optDouble("diopter", s.diopter);
    }

    private JSONObject readObject(String key) {
        try {
            return new JSONObject(prefs.getString(key, "{}"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private JSONArray readArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }
}
