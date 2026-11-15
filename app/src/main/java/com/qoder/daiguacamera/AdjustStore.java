package com.qoder.daiguacamera;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 用户保存的调色模式（类似傻瓜模式的场景仓库）。 */
public class AdjustStore {
    private static final String PREFS = "daigua_adjust";
    private static final String KEY_CUSTOM = "custom";

    private final SharedPreferences prefs;
    private final List<AdjustPreset> custom = new ArrayList<>();

    public AdjustStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public List<AdjustPreset> custom() {
        return custom;
    }

    public AdjustPreset find(String id) {
        for (AdjustPreset p : custom) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private void load() {
        custom.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_CUSTOM, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String name = o.optString("name", "");
                if (id.isEmpty() || name.isEmpty()) continue;
                AdjustPreset p = new AdjustPreset(id, name);
                p.auto = o.optBoolean("auto", false);
                p.bright = (float) o.optDouble("bright", 0);
                p.contrast = (float) o.optDouble("contrast", 1);
                p.sat = (float) o.optDouble("sat", 1);
                p.temp = (float) o.optDouble("temp", 0);
                p.hue = (float) o.optDouble("hue", 0);
                p.aberration = (float) o.optDouble("aberration", 0);
                p.vignette = (float) o.optDouble("vignette", 0);
                p.grain = (float) o.optDouble("grain", 0);
                p.invert = (float) o.optDouble("invert", 0);
                p.custom = true;
                custom.add(p);
            }
        } catch (Throwable ignored) {
        }
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (AdjustPreset p : custom) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("auto", p.auto);
                o.put("bright", p.bright);
                o.put("contrast", p.contrast);
                o.put("sat", p.sat);
                o.put("temp", p.temp);
                o.put("hue", p.hue);
                o.put("aberration", p.aberration);
                o.put("vignette", p.vignette);
                o.put("grain", p.grain);
                o.put("invert", p.invert);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply();
    }

    public void add(AdjustPreset p) {
        p.id = "u" + System.currentTimeMillis();
        p.custom = true;
        custom.add(p);
        persist();
    }

    public void remove(AdjustPreset p) {
        custom.remove(p);
        persist();
    }

    public void update(AdjustPreset p) {
        for (int i = 0; i < custom.size(); i++) {
            if (custom.get(i).id.equals(p.id)) {
                custom.set(i, p);
                break;
            }
        }
        persist();
    }
}
