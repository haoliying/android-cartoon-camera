package com.qoder.cartooncamera;

import java.util.Random;

/**
 * 小精灵系统：内容感知选址、相机运动反应、触摸互动。
 * <p>
 * 每个精灵有“种族”（决定长相、大小、喜欢的落脚点）和“性格”（决定动作幅度与反应方式）：
 * 好脾气的会害羞卖萌，坏脾气的被碰会炸毛发火，胆小的会缩起来逃走，困倦的反应慢半拍。
 * 所有方法都在 GL 线程调用，无需加锁。
 */
public class SpriteSystem {

    public static final int GRID = 16;
    public static final int MAX_SPRITES = 4;
    public static final int MAX_PARTICLES = 32;

    public static final int SPECIES_COUNT = 8;
    public static final String[] SPECIES_NAMES = {
            "圆团", "胖梨", "猫耳", "水母", "石头", "萝卜苗", "星星仔", "刺球"
    };
    // 0 甜 1 暴躁 2 胆小 3 好奇 4 困 5 活泼
    public static final String[] PERSONA_NAMES = {
            "温顺", "暴躁", "胆小", "好奇", "困倦", "活泼"
    };

    /** 一只小精灵。 */
    public static class Sprite {
        public float u, v;          // 屏幕 uv（0..1，v 向上，未镜像）
        public float size;          // 半高（NDC）
        public float hue;           // 0..1
        public float sat;           // 饱和度
        public float seed;          // 随机相位
        public float age, ttl;
        public float fade = 1f;
        public float rot, lookX, lookY;
        public float blink;
        public float squashX = 1f, squashY = 1f;
        public float vx, vy;
        public int mood;            // 0 平常 1 惊吓 2 开心 3 挤压 4 好奇 5 生气 6 困倦
        public float moodT;
        public float blinkTimer;
        public float blinkT;
        public boolean pressed;
        public int species, persona;
        public float temper;        // 0 好脾气 .. 1 坏脾气
        public float bounce;        // 弹跳幅度
        public float wander;        // 自主游荡幅度
        public float scale = 1f;    // 整体缩放（胆小的会缩起来）
        public float petTime;       // 连续被抚摸的时长
        public float emitTimer;     // 自发小特效的倒计时
    }

    /** 爱心 / 星星 / 涟漪 / 烟雾粒子。 */
    public static class Particle {
        public float u, v, vu, vv, size, age, ttl, hue, rot, spin;
        public int shape;
        public boolean alive;
    }

    public final Sprite[] sprites = new Sprite[MAX_SPRITES];
    public final Particle[] particles = new Particle[MAX_PARTICLES];

    /** 触摸涟漪，供最终着色器使用。age 越大强度越弱。 */
    public final float[] ripU = {-9f, -9f};
    public final float[] ripV = {-9f, -9f};
    public final float[] ripAge = {9f, 9f};
    public final boolean[] ripOn = {false, false};
    private int ripNext;

    private final Random rnd = new Random();
    private final float[] flat = new float[GRID * GRID];
    private final float[] bright = new float[GRID * GRID];
    private boolean contentReady;
    private float cropX = 1f, cropY = 1f;

    private float yawS, pitchS, rollS, speedS;
    private float spawnTimer = 0.5f;
    private float lastStartle = -9f;
    private float petCooldown;

    // ------------------------------------------------------------------ 种族 / 性格表

    /** 各物种偏好的画面位置：目标亮度、平坦度、竖直高度。 */
    private static final float[] SPEC_LUM = {0.55f, 0.62f, 0.50f, 0.78f, 0.24f, 0.46f, 0.82f, 0.34f};
    private static final float[] SPEC_FLAT = {0.90f, 0.85f, 0.75f, 0.55f, 0.10f, 0.95f, 0.70f, 0.15f};
    private static final float[] SPEC_V = {0.45f, 0.56f, 0.60f, 0.80f, 0.24f, 0.34f, 0.82f, 0.50f};
    /** 基础大小，再乘 0.68~1.35 的随机系数，所以有胖有瘦有大有小。 */
    private static final float[] SPEC_SIZE = {0.058f, 0.064f, 0.056f, 0.060f, 0.066f, 0.058f, 0.052f, 0.063f};
    private static final float[] SPEC_HUE = {0.36f, 0.09f, 0.05f, 0.55f, 0.60f, 0.28f, 0.14f, 0.86f};
    private static final float[] SPEC_SAT = {0.62f, 0.60f, 0.55f, 0.42f, 0.18f, 0.52f, 0.76f, 0.66f};
    /** 每种族的性格倾向：甜、暴躁、胆小、好奇、困、活泼。 */
    private static final float[][] PERSONA_W = {
            {4, 1, 1, 2, 1, 2},
            {4, 1, 1, 2, 1, 2},
            {3, 2, 1, 3, 1, 1},
            {5, 0, 2, 2, 2, 1},
            {1, 3, 1, 1, 3, 1},
            {4, 1, 2, 1, 3, 1},
            {5, 0, 1, 2, 0, 3},
            {0, 5, 1, 1, 1, 1},
    };
    private static final float[] PER_TEMPER = {0.10f, 0.92f, 0.55f, 0.15f, 0.45f, 0.08f};
    private static final float[] PER_WANDER = {1.0f, 0.7f, 1.5f, 1.2f, 0.45f, 2.0f};
    private static final float[] PER_GRIP = {0.55f, 0.55f, 0.74f, 0.55f, 0.34f, 0.50f};
    private static final float[] PER_BOUNCE = {1.0f, 0.8f, 1.35f, 1.1f, 0.45f, 2.0f};
    private static final float[] PER_BLINK = {1.0f, 1.15f, 1.5f, 0.85f, 2.6f, 0.7f};

    // ------------------------------------------------------------------ 内容网格

    /** 由渲染线程回读的 16x16 缩略图（RGBA，GL 行序：v=0 在底部）。 */
    public void setContent(byte[] px, int gw, int gh, float cx, float cy) {
        if (gw < 2 || gh < 2 || px == null || px.length < gw * gh * 4) return;
        cropX = cx <= 0f ? 1f : cx;
        cropY = cy <= 0f ? 1f : cy;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int i = (y * gw + x) * 4;
                float yy = (0.299f * (px[i] & 255) + 0.587f * (px[i + 1] & 255)
                        + 0.114f * (px[i + 2] & 255)) / 255f;
                bright[y * gw + x] = yy;
            }
        }
        int gx0 = gw / GRID, gy0 = gh / GRID;
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                int sx = Math.min(gw - 1, gx * gx0), sy = Math.min(gh - 1, gy * gy0);
                int sx1 = Math.min(gw - 1, sx + Math.max(1, gx0));
                int sy1 = Math.min(gh - 1, sy + Math.max(1, gy0));
                float l = bright[sy * gw + sx];
                float dx = Math.abs(bright[sy * gw + sx1] - l);
                float dy = Math.abs(bright[sy1 * gw + sx] - l);
                // 越平整越接近 1
                flat[gy * GRID + gx] = Math.max(0f, 1f - (dx + dy) * 3.2f);
            }
        }
        contentReady = true;
    }

    /**
     * 按物种的偏好挑落脚点：亮度接近偏好值、纹理平坦度接近偏好、竖直位置合适，
     * 再叠加“离其他精灵远一点”的惩罚，最后加权随机。
     */
    private float[] pickSpot(int species) {
        float pl = SPEC_LUM[species];
        float pf = SPEC_FLAT[species];
        float pv = SPEC_V[species];
        float[] best = null;
        float total = 0f;
        float[] score = new float[GRID * GRID];
        for (int gy = 1; gy < GRID - 1; gy++) {
            for (int gx = 1; gx < GRID - 1; gx++) {
                int i = gy * GRID + gx;
                float b = bright[i];
                float cu = (gx + 0.5f) / GRID;
                float cv = (gy + 0.5f) / GRID;
                float lumW = 1f - Math.min(1f, Math.abs(b - pl) * 2.2f);
                float flatW = 1f - Math.abs(flat[i] - pf);
                float vertW = 1f - Math.min(1f, Math.abs(cv - pv) * 1.8f);
                float sc = (0.15f + 0.85f * lumW) * (0.15f + 0.85f * flatW)
                        * (0.35f + 0.65f * vertW);
                for (Sprite s : sprites) {
                    if (s == null) continue;
                    float du = (cu - s.u) * 1.4f, dv = cv - s.v;
                    if (du * du + dv * dv < 0.045f) sc *= 0.12f;
                }
                score[i] = sc;
                total += sc;
            }
        }
        if (total <= 0f) {
            return new float[]{0.28f + rnd.nextFloat() * 0.44f, 0.28f + rnd.nextFloat() * 0.4f};
        }
        float r = rnd.nextFloat() * total;
        for (int gy = 1; gy < GRID - 1 && best == null; gy++) {
            for (int gx = 1; gx < GRID - 1; gx++) {
                int i = gy * GRID + gx;
                r -= score[i];
                if (r <= 0f) {
                    float cu = (gx + 0.5f) / GRID;
                    float cv = (gy + 0.5f) / GRID;
                    // 缩略图是整幅相机画面，屏幕只显示其中裁切后的部分
                    best = new float[]{(cu - 0.5f) * cropX + 0.5f, (cv - 0.5f) * cropY + 0.5f};
                    break;
                }
            }
        }
        if (best == null) {
            best = new float[]{0.3f + rnd.nextFloat() * 0.4f, 0.3f + rnd.nextFloat() * 0.4f};
        }
        float u = clamp(best[0], 0.10f, 0.90f);
        float v = clamp(best[1], 0.14f, 0.84f);
        return new float[]{u, v};
    }

    // ------------------------------------------------------------------ 生成 / 更新

    private int pickPersona(int species) {
        float[] w = PERSONA_W[species];
        float total = 0f;
        for (float x : w) total += x;
        float r = rnd.nextFloat() * total;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0f) return i;
        }
        return 0;
    }

    private void spawn() {
        for (int i = 0; i < MAX_SPRITES; i++) {
            if (sprites[i] != null) continue;
            Sprite s = new Sprite();
            s.species = rnd.nextInt(SPECIES_COUNT);
            s.persona = pickPersona(s.species);
            float[] p = contentReady ? pickSpot(s.species)
                    : new float[]{0.3f + rnd.nextFloat() * 0.4f, 0.3f + rnd.nextFloat() * 0.4f};
            s.u = p[0];
            s.v = p[1];
            s.size = SPEC_SIZE[s.species] * rf(0.68f, 1.35f);
            s.hue = SPEC_HUE[s.species] + rf(-0.025f, 0.025f);
            s.sat = SPEC_SAT[s.species];
            s.temper = clamp(PER_TEMPER[s.persona] + rf(-0.08f, 0.08f), 0f, 1f);
            s.bounce = PER_BOUNCE[s.persona] * rf(0.85f, 1.15f);
            s.wander = PER_WANDER[s.persona];
            s.seed = rnd.nextFloat();
            s.ttl = 9f + rnd.nextFloat() * 8f;
            s.vx = (rnd.nextFloat() - 0.5f) * 0.014f;
            s.vy = (rnd.nextFloat() - 0.5f) * 0.010f;
            s.blinkTimer = (1.2f + rnd.nextFloat() * 3f) * PER_BLINK[s.persona];
            s.emitTimer = 2.5f + rnd.nextFloat() * 5f;
            sprites[i] = s;
            // 出场小烟
            for (int k = 0; k < 3; k++) {
                spawnParticle(s.u + rf(-0.03f, 0.03f), s.v + rf(-0.03f, 0.03f),
                        4, 0.05f, 0.4f);
            }
            return;
        }
    }

    public void clear() {
        for (int i = 0; i < MAX_SPRITES; i++) sprites[i] = null;
        for (int i = 0; i < MAX_PARTICLES; i++) particles[i] = null;
        ripOn[0] = ripOn[1] = false;
    }

    public void update(float dt, float time, float yaw, float pitch, float roll, float speed) {
        if (dt <= 0f) return;
        float k = Math.min(1f, dt * 10f);
        yawS += (yaw - yawS) * k;
        pitchS += (pitch - pitchS) * k;
        rollS += (roll - rollS) * k;
        speedS += (speed - speedS) * k;

        boolean startled = speedS > 1.2f && time - lastStartle > 1.0f;
        if (startled) lastStartle = time;

        for (int i = 0; i < 2; i++) ripAge[i] = Math.min(9f, ripAge[i] + dt);
        petCooldown -= dt;

        spawnTimer -= dt;
        if (spawnTimer <= 0f) {
            spawnTimer = 1.4f + rnd.nextFloat() * 2.4f;
            int alive = 0;
            for (Sprite s : sprites) if (s != null) alive++;
            if (alive < 3) spawn();
        }

        for (int i = 0; i < MAX_SPRITES; i++) {
            Sprite s = sprites[i];
            if (s == null) continue;
            updateSprite(s, dt, time, startled);
            if (s.age >= s.ttl && s.fade <= 0.02f) sprites[i] = null;
        }
        for (int i = 0; i < MAX_PARTICLES; i++) {
            Particle p = particles[i];
            if (p == null || !p.alive) continue;
            p.age += dt;
            p.u += p.vu * dt;
            p.v += p.vv * dt;
            p.vu *= 0.98f;
            p.vv += 0.02f * dt;
            p.rot += p.spin * dt;
            if (p.age >= p.ttl) {
                p.alive = false;
                particles[i] = null;
            }
        }
    }

    private void updateSprite(Sprite s, float dt, float time, boolean startled) {
        s.age += dt;

        // 相机转动时反向位移，让小精灵“贴”在景物上；胆小的抓得更紧，困的反应迟钝
        float grip = PER_GRIP[s.persona];
        s.u -= yawS * dt * grip;
        s.v -= pitchS * dt * grip;
        // 自己也会闲逛
        s.u += (float) Math.sin(time * 0.9f + s.seed * 6.283f) * 0.012f * s.wander * dt;
        s.v += (float) Math.cos(time * 1.3f + s.seed * 3.1f) * 0.008f * s.wander * dt;
        s.u += s.vx * dt;
        s.v += s.vy * dt;
        s.vx *= 0.97f;
        s.vy *= 0.97f;

        if (startled) reactMotion(s);

        s.u = clamp(s.u, 0.08f, 0.92f);
        s.v = clamp(s.v, 0.14f, 0.86f);

        s.rot = -rollS * 0.42f + (float) Math.sin(time * 2.1 + s.seed * 6.283f) * 0.05f;

        // 眨眼
        s.blinkTimer -= dt;
        if (s.blinkTimer <= 0f) {
            s.blinkT = 0.16f;
            s.blinkTimer = (2.0f + rnd.nextFloat() * 3.6f) * PER_BLINK[s.persona];
        }
        if (s.blinkT > 0f) {
            s.blinkT -= dt;
            float t = clamp(1f - s.blinkT / 0.16f, 0f, 1f);
            s.blink = (float) Math.sin(Math.PI * t);
        } else {
            s.blink = 0f;
        }

        // 情绪计时
        s.moodT -= dt;
        if (s.moodT <= 0f) {
            if (s.mood == 3) {
                setMood(s, 2, 0.9f);   // 被戳之后转为开心
            } else {
                s.mood = 0;
            }
        }

        // 自发小情绪：活泼的冒星星，温顺的冒爱心，暴躁的冒烟
        s.emitTimer -= dt;
        if (s.emitTimer <= 0f) {
            s.emitTimer = 3.2f + rnd.nextFloat() * 4.5f;
            if (s.persona == 5) star(s);
            else if (s.persona == 0 && s.fade > 0.8f) heart(s);
            else if (s.persona == 1) puff(s, 0.5f);
        }

        // 挤压回弹
        float k = Math.min(1f, dt * 9f);
        s.squashX += (1f - s.squashX) * k;
        s.squashY += (1f - s.squashY) * k;
        if (!s.pressed && s.mood != 4) {
            s.lookX += (0f - s.lookX) * k;
            s.lookY += (0f - s.lookY) * k;
        }
        // 缩成一团之后慢慢恢复
        if (!s.pressed) {
            s.scale += (1f - s.scale) * Math.min(1f, dt * 1.2f);
        }

        float in = Math.min(1f, s.age / 0.45f);
        float out = Math.min(1f, Math.max(0f, (s.ttl - s.age) / 0.7f));
        s.fade = Math.min(in, out);
    }

    /** 手机晃动的反应，随性格不同。 */
    private void reactMotion(Sprite s) {
        switch (s.persona) {
            case 1: // 暴躁：觉得被晃到了，生气 + 跺脚冒烟
                setMood(s, 5, 1.2f);
                s.vx -= clamp(yawS, -1.5f, 1.5f) * 0.010f;
                s.vy -= clamp(pitchS, -1.5f, 1.5f) * 0.008f;
                puff(s, 0.9f);
                break;
            case 2: // 胆小：缩起来窜开
                setMood(s, 1, 0.9f);
                s.vx -= clamp(yawS, -2f, 2f) * 0.022f;
                s.vy += 0.035f;
                s.scale = Math.min(s.scale, 0.78f);
                break;
            case 3: // 好奇：张望晃动的方向
                setMood(s, 4, 0.8f);
                s.lookX = clamp(yawS * 0.8f, -1f, 1f);
                s.lookY = clamp(-pitchS * 0.8f, -1f, 1f);
                break;
            case 4: // 困倦：翻个身继续困
                setMood(s, 6, 1.4f);
                break;
            case 5: // 活泼：跟着蹦
                setMood(s, 2, 0.7f);
                s.vy += 0.10f;
                if (rnd.nextFloat() < 0.7f) star(s);
                break;
            default: // 温顺：吓一跳，然后冒爱心自我安慰
                setMood(s, 1, 0.55f);
                if (rnd.nextFloat() < 0.4f) heart(s);
                break;
        }
    }

    // ------------------------------------------------------------------ 交互

    /** action: 0 按下 1 移动 2 抬起。返回是否有精灵被碰到。 */
    public boolean touch(float u, float v, int action, float aspect) {
        addRipple(u, v);
        boolean hitAny = false;
        for (Sprite s : sprites) {
            if (s == null || s.fade < 0.4f) continue;
            float dx = (u - s.u) * aspect;
            float dy = v - s.v;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float radius = s.size * s.scale * 0.95f;
            boolean hit = d < radius;
            if (action == 0) {
                if (hit) {
                    touchHit(s, u, v, aspect);
                    hitAny = true;
                } else if (d < 0.30f) {
                    touchNear(s, u, v, aspect, d);
                }
            } else if (action == 1) {
                if (s.pressed && hit) {
                    touchPet(s, u, v, aspect);
                    hitAny = true;
                } else if (!hit) {
                    s.pressed = false;
                }
            } else {
                s.pressed = false;
            }
        }
        return hitAny;
    }

    /** 手指落到精灵身上：按性格给出完全不同的反应。 */
    private void touchHit(Sprite s, float u, float v, float aspect) {
        s.pressed = true;
        s.petTime = 0f;
        float dir = (s.u - u) * aspect;
        switch (s.persona) {
            case 1: // 暴躁：炸毛、冒烟、被推得往后挪
                setMood(s, 5, 1.1f);
                s.squashX = 1.16f;
                s.squashY = 0.80f;
                s.vx += dir * 0.35f;
                s.vy = 0.05f;
                for (int k = 0; k < 4; k++) puff(s, 0.9f);
                break;
            case 2: // 胆小：缩成一团往后跳
                setMood(s, 1, 0.9f);
                s.scale = 0.66f;
                s.vx += dir * 0.55f;
                s.vy = 0.07f;
                break;
            case 4: // 困倦：被吵醒，懒懒地哼一声
                setMood(s, 6, 1.4f);
                s.squashX = 1.10f;
                s.squashY = 0.90f;
                puff(s, 0.5f);
                break;
            case 5: // 活泼：弹起来撒花
                setMood(s, 3, 0.16f);
                s.squashX = 1.26f;
                s.squashY = 0.70f;
                s.vy = 0.11f;
                for (int k = 0; k < 3; k++) star(s);
                heart(s);
                heart(s);
                break;
            case 3: // 好奇：抬头看你
                setMood(s, 4, 1.2f);
                s.lookX = clamp((u - s.u) * aspect * 6f, -1f, 1f);
                s.lookY = clamp((v - s.v) * 6f, -1f, 1f);
                s.squashX = 1.10f;
                s.squashY = 0.92f;
                heart(s);
                break;
            default: // 温顺：被拍扁，然后开心地冒爱心
                setMood(s, 3, 0.18f);
                s.squashX = 1.24f;
                s.squashY = 0.72f;
                s.vy = 0.06f;
                for (int k = 0; k < 2; k++) heart(s);
                star(s);
                break;
        }
    }

    /** 按住拖动 = 抚摸，不同性格吃不吃这一套完全不同。 */
    private void touchPet(Sprite s, float u, float v, float aspect) {
        float dir = (s.u - u) * aspect;
        switch (s.persona) {
            case 1: // 暴躁：越摸越气，最后甩手走人
                s.petTime += 0.16f;
                if (petCooldown <= 0f) {
                    petCooldown = 0.25f;
                    puff(s, 0.7f);
                }
                setMood(s, 5, 0.9f);
                if (s.petTime > 0.5f) {
                    s.vx += dir * 0.5f;
                    s.vy = 0.06f;
                    s.pressed = false;
                }
                break;
            case 2: // 胆小：僵住、缩起来，摸久了直接逃走
                s.petTime += 0.16f;
                setMood(s, 1, 0.5f);
                s.scale = Math.min(s.scale, 0.72f);
                if (s.petTime > 0.35f) {
                    s.vx += dir * 0.9f;
                    s.vy = 0.09f;
                    s.pressed = false;
                    s.ttl = Math.min(s.ttl, s.age + 1.6f);
                }
                break;
            case 4: // 困倦：舒服得又要睡着了
                setMood(s, 6, 0.9f);
                if (petCooldown <= 0f) {
                    petCooldown = 0.5f;
                    heart(s);
                }
                break;
            default: // 温顺 / 好奇 / 活泼：享受，持续冒爱心
                setMood(s, 2, 0.5f);
                s.squashX = 1.05f;
                s.squashY = 0.95f;
                if (petCooldown <= 0f) {
                    petCooldown = 0.22f;
                    heart(s);
                    if (s.persona == 5) star(s);
                }
                if (s.persona == 3) {
                    s.lookX = clamp((u - s.u) * aspect * 6f, -1f, 1f);
                    s.lookY = clamp((v - s.v) * 6f, -1f, 1f);
                }
                break;
        }
    }

    /** 在精灵附近点了一下（没直接点到）：凑近的坏脾气会不爽。 */
    private void touchNear(Sprite s, float u, float v, float aspect, float d) {
        if (s.persona == 1 && d < 0.22f) {
            setMood(s, 5, 0.8f);
            s.vx += (s.u - u) * aspect * 0.25f;
            puff(s, 0.6f);
        } else if (s.persona == 2 && d < 0.20f) {
            setMood(s, 1, 0.6f);
            s.scale = 0.82f;
            s.vx += (s.u - u) * aspect * 0.30f;
        } else {
            setMood(s, 4, 0.9f);
            s.lookX = clamp((u - s.u) * aspect * 6f, -1f, 1f);
            s.lookY = clamp((v - s.v) * 6f, -1f, 1f);
        }
    }

    private void addRipple(float u, float v) {
        ripU[ripNext] = u;
        ripV[ripNext] = v;
        ripAge[ripNext] = 0f;
        ripOn[ripNext] = true;
        ripNext = (ripNext + 1) % 2;
    }

    private void setMood(Sprite s, int mood, float dur) {
        s.mood = mood;
        s.moodT = dur;
    }

    private void heart(Sprite s) {
        spawnParticle(s.u + rf(-0.05f, 0.05f), s.v + s.size * 0.35f, 1, 0.028f, 1.1f);
    }

    private void star(Sprite s) {
        spawnParticle(s.u + rf(-0.05f, 0.05f), s.v + s.size * 0.4f, 2, 0.022f, 0.9f);
    }

    private void puff(Sprite s, float sizeMul) {
        spawnParticle(s.u + rf(-0.05f, 0.05f), s.v + rf(-0.01f, 0.05f), 4,
                0.035f * sizeMul, 0.5f);
    }

    private void spawnParticle(float u, float v, int shape, float size, float ttl) {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (particles[i] != null) continue;
            Particle p = new Particle();
            p.u = bilerp(u, v);
            p.v = clamp(v, 0.02f, 0.98f);
            p.shape = shape;
            p.size = size;
            p.ttl = ttl;
            p.vu = rf(-0.03f, 0.03f);
            p.vv = rf(0.04f, 0.09f);
            p.hue = size > 0.04f ? 0f : 0.09f;
            p.rot = rnd.nextFloat() * 6.283f;
            p.spin = rf(-2.2f, 2.2f);
            p.alive = true;
            particles[i] = p;
            return;
        }
    }

    /** 在屏幕坐标里做小幅抖动时略微向外扩散，避免粒子完全重合。 */
    private float bilerp(float u, float v) {
        return clamp(u + rf(-0.012f, 0.012f), 0.02f, 0.98f);
    }

    private float rf(float lo, float hi) {
        return lo + rnd.nextFloat() * (hi - lo);
    }

    private static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }
}
