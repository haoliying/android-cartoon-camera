package com.qoder.cartooncamera;

import java.util.Random;

/**
 * 小精灵系统：内容感知选址（贴在画面里较平整的区域）、相机运动反应、触摸互动。
 * 所有方法都在 GL 线程调用，无需加锁。
 */
public class SpriteSystem {

    public static final int GRID = 16;
    public static final int MAX_SPRITES = 4;
    public static final int MAX_PARTICLES = 32;

    /** 一只小精灵。 */
    public static class Sprite {
        public float u, v;          // 屏幕 uv（0..1，v 向上，未镜像）
        public float size;          // 半高（NDC）
        public float hue;           // 0..1
        public float seed;          // 随机相位
        public float age, ttl;
        public float fade = 1f;
        public float rot, lookX, lookY;
        public float blink;
        public float squashX = 1f, squashY = 1f;
        public float vx, vy;
        public int mood;            // 0 平常 1 惊吓 2 开心 3 挤压 4 好奇
        public float moodT;
        public float blinkTimer;
        public float blinkT;
        public boolean pressed;
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

    /** 在“平整、不太暗、不太亮、离其他精灵远”的格子里加权随机挑一个。 */
    private float[] pickSpot() {
        float[] best = null;
        float bestScore = -1f;
        float total = 0f;
        float[] score = new float[GRID * GRID];
        for (int gy = 1; gy < GRID - 1; gy++) {
            for (int gx = 1; gx < GRID - 1; gx++) {
                int i = gy * GRID + gx;
                float b = bright[i];
                float lumW = 1f - Math.min(1f, Math.abs(b - 0.52f) * 1.7f);
                float sc = (0.25f + 0.75f * flat[i]) * (0.35f + 0.65f * lumW);
                float cu = (gx + 0.5f) / GRID;
                float cv = (gy + 0.5f) / GRID;
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
                    bestScore = score[i];
                    break;
                }
            }
        }
        if (best == null) {
            best = new float[]{0.3f + rnd.nextFloat() * 0.4f, 0.3f + rnd.nextFloat() * 0.4f};
        }
        float u = clamp(best[0], 0.10f, 0.90f);
        float v = clamp(best[1], 0.18f, 0.80f);
        return new float[]{u, v};
    }

    // ------------------------------------------------------------------ 生成 / 更新

    private void spawn() {
        for (int i = 0; i < MAX_SPRITES; i++) {
            if (sprites[i] != null) continue;
            Sprite s = new Sprite();
            float[] p = contentReady ? pickSpot()
                    : new float[]{0.3f + rnd.nextFloat() * 0.4f, 0.3f + rnd.nextFloat() * 0.4f};
            s.u = p[0];
            s.v = p[1];
            s.size = 0.052f + rnd.nextFloat() * 0.026f;
            s.hue = rnd.nextFloat();
            if (rnd.nextFloat() < 0.3f) s.hue = 0.52f + rnd.nextFloat() * 0.14f;
            s.seed = rnd.nextFloat();
            s.ttl = 9f + rnd.nextFloat() * 8f;
            s.vx = (rnd.nextFloat() - 0.5f) * 0.014f;
            s.vy = (rnd.nextFloat() - 0.5f) * 0.010f;
            s.blinkTimer = 1.2f + rnd.nextFloat() * 3f;
            sprites[i] = s;
            // 出场小烟
            for (int k = 0; k < 3; k++) spawnParticle(s.u + rf(-0.03f, 0.03f),
                    s.v + rf(-0.03f, 0.03f), 4, 0.05f, 0.4f);
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

        // 相机转动时反向位移，让小精灵“贴”在景物上
        s.u -= yawS * dt * 0.55f;
        s.v -= pitchS * dt * 0.55f;
        s.u += s.vx * dt;
        s.v += s.vy * dt;
        s.vx *= 0.97f;
        s.vy *= 0.97f;

        if (startled) {
            s.vx += -yawS * 0.012f;
            s.vy += -pitchS * 0.012f + 0.03f;
            if (rnd.nextFloat() < 0.6f) setMood(s, 1, 0.9f);
            if (rnd.nextFloat() < 0.35f) {
                spawnParticle(s.u + rf(-0.02f, 0.02f), s.v + s.size * 0.45f,
                        4, 0.03f, 0.35f);
            }
        }

        s.u = clamp(s.u, 0.08f, 0.92f);
        s.v = clamp(s.v, 0.16f, 0.82f);

        s.rot = -rollS * 0.42f + (float) Math.sin(time * 2.1 + s.seed * 6.283f) * 0.05f;

        // 眨眼
        s.blinkTimer -= dt;
        if (s.blinkTimer <= 0f) {
            s.blinkT = 0.16f;
            s.blinkTimer = 2.0f + rnd.nextFloat() * 3.6f;
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

        // 挤压回弹
        float k = Math.min(1f, dt * 9f);
        s.squashX += (1f - s.squashX) * k;
        s.squashY += (1f - s.squashY) * k;
        if (!s.pressed && s.mood != 4) {
            s.lookX += (0f - s.lookX) * k;
            s.lookY += (0f - s.lookY) * k;
        }

        float in = Math.min(1f, s.age / 0.45f);
        float out = Math.min(1f, Math.max(0f, (s.ttl - s.age) / 0.7f));
        s.fade = Math.min(in, out);
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
            float radius = s.size * 0.95f;
            boolean hit = d < radius;
            if (action == 0 && hit) {
                s.pressed = true;
                hitAny = true;
                setMood(s, 3, 0.18f);
                s.squashX = 1.24f;
                s.squashY = 0.72f;
                s.vy = 0.06f;
                for (int k = 0; k < 3; k++) {
                    spawnParticle(s.u + rf(-0.05f, 0.05f), s.v + rf(-0.02f, 0.04f),
                            1, 0.030f, 1.1f);
                }
                for (int k = 0; k < 2; k++) {
                    spawnParticle(s.u + rf(-0.05f, 0.05f), s.v + rf(0f, 0.05f),
                            2, 0.022f, 0.9f);
                }
            } else if (action == 1 && s.pressed) {
                if (hit) {
                    hitAny = true;
                    setMood(s, 2, 0.6f);
                    s.squashX = 1.06f;
                    s.squashY = 0.94f;
                    if (petCooldown <= 0f) {
                        petCooldown = 0.22f;
                        spawnParticle(s.u + rf(-0.04f, 0.04f), s.v + s.size * 0.4f,
                                1, 0.026f, 1.0f);
                    }
                } else {
                    s.pressed = false;
                }
            } else if (action == 2) {
                s.pressed = false;
            } else if (action == 0 && !hit && d < 0.30f) {
                // 在附近点了一下：好奇地看过去
                setMood(s, 4, 0.9f);
                s.lookX = clamp((u - s.u) * aspect * 6f, -1f, 1f);
                s.lookY = clamp((v - s.v) * 6f, -1f, 1f);
            }
        }
        return hitAny;
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
