package com.qoder.daiguacamera;

/**
 * 构图分析器：输入一帧降采样的预览画面（ARGB，行序从上到下）、对焦点和人脸框，
 * 输出一条构图提示，以及叠加层要用的主体框、地平线等信息。
 *
 * 全部是传统图像处理（显著性、边缘、霍夫直线、左右对称、天空/地面分区），
 * 不依赖模型文件，任何机型都能跑。
 */
public final class CompositionAdvisor {

    public static final int GRID_THIRDS = 0;
    public static final int GRID_CENTER = 1;
    public static final int GRID_DIAGONAL = 2;

    public static final int SUBJECT_AUTO = 0;
    public static final int SUBJECT_PORTRAIT = 1;
    public static final int SUBJECT_LANDSCAPE = 2;

    public static final int SEV_GOOD = 0;
    public static final int SEV_INFO = 1;
    public static final int SEV_WARN = 2;

    /** 一次分析的结果：提示 + 叠加层几何。 */
    public static final class Result {
        /** 提示文字，可能为 null（分析失败或没有建议）。 */
        public String hint;
        /** 第二行小字：主体占比、天空占比、倾斜角。 */
        public String detail;
        public int severity = SEV_GOOD;
        /** 可执行动作，目前只有 "scene:backlight"。 */
        public String action;
        public String actionLabel;
        /** 箭头方向（-1/0/1），提示用户往哪边挪画面。 */
        public float arrowX;
        public float arrowY;
        /** 主体框 x,y,w,h（0..1，y 向下）。 */
        public float[] subject;
        /** 人脸框，每个是 x,y,w,h（0..1）。 */
        public float[][] faces = new float[0][];
        /** 地平线在画面中心的高度（0..1），-1 表示没检测到。 */
        public float horizonY = -1f;
        /** 地平线倾角，正数表示右边更低（画面顺时针歪）。 */
        public float horizonTilt;
        public float horizonConf;
        public float skyRatio;
        public float symmetry;
        public boolean leadingLine;
        public boolean faceSubject;

        boolean sameAs(Result o) {
            if (o == null) return false;
            return severity == o.severity
                    && (hint == null ? o.hint == null : hint.equals(o.hint));
        }
    }

    private CompositionAdvisor() {
    }

    public static Result analyze(int[] px, int w, int h, float focusU, float focusV,
                                 float[][] faces, int subjectPref) {
        Result out = new Result();
        if (px == null || w < 16 || h < 16) return out;
        try {
            new Engine(px, w, h, focusU, focusV, faces, subjectPref, out).run();
        } catch (Throwable ignored) {
            // 构图提示失败不影响拍摄，静默返回空结果
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部实现

    private static final class Engine {
        final int[] px;
        final int w, h;
        final float focusU, focusV;
        final float[][] faces;
        final int subjectPref;
        final Result out;

        final int GW = 56;
        final int GH;
        final int N;

        final float[] lum;
        final float[] rr, gg, bb;
        final float[] edge;
        final float[] sal;

        float meanR, meanG, meanB;
        float maxSal = 0f;

        float[] subject;      // x,y,w,h 归一化
        float subjectConf;
        boolean faceSubject;
        float[] faceRect;     // 最大人脸

        float horizonY = -1f;
        float horizonTilt;
        float horizonConf;
        float skyRatio;
        float symmetry;
        float edgeOutside;
        boolean leadingLine;
        boolean leadingAligned;

        Engine(int[] px, int w, int h, float focusU, float focusV,
               float[][] faces, int subjectPref, Result out) {
            this.px = px;
            this.w = w;
            this.h = h;
            this.focusU = focusU;
            this.focusV = focusV;
            this.faces = faces;
            this.subjectPref = subjectPref;
            this.out = out;
            GH = Math.max(28, Math.round(GW * (float) h / w));
            N = GW * GH;
            lum = new float[N];
            rr = new float[N];
            gg = new float[N];
            bb = new float[N];
            edge = new float[N];
            sal = new float[N];
        }

        void run() {
            sample();
            edges();
            saliency();
            findSubject();
            measureHorizon();
            measureSky();
            measureSymmetry();
            measureLines();
            buildResult();
        }

        void sample() {
            for (int gy = 0; gy < GH; gy++) {
                int py = Math.min(h - 1, (int) ((gy + 0.5f) * h / GH));
                for (int gx = 0; gx < GW; gx++) {
                    int pxx = Math.min(w - 1, (int) ((gx + 0.5f) * w / GW));
                    int c = px[py * w + pxx];
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    int i = gy * GW + gx;
                    rr[i] = r / 255f;
                    gg[i] = g / 255f;
                    bb[i] = b / 255f;
                    lum[i] = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f;
                    meanR += rr[i];
                    meanG += gg[i];
                    meanB += bb[i];
                }
            }
            meanR /= N;
            meanG /= N;
            meanB /= N;
        }

        void edges() {
            for (int y = 0; y < GH; y++) {
                for (int x = 0; x < GW; x++) {
                    int x0 = Math.max(0, x - 1);
                    int x1 = Math.min(GW - 1, x + 1);
                    int y0 = Math.max(0, y - 1);
                    int y1 = Math.min(GH - 1, y + 1);
                    float dx = lum[y * GW + x1] - lum[y * GW + x0];
                    float dy = lum[y1 * GW + x] - lum[y0 * GW + x];
                    edge[y * GW + x] = Math.abs(dx) + Math.abs(dy);
                }
            }
        }

        /** 局部对比度 + 与画面平均色的差异 + 边缘密度，粗略当“显著度”。 */
        void saliency() {
            float[] blur = new float[N];
            int r = 3;
            // 可分离盒式模糊，两次滑动平均，代价 O(N)
            float[] tmp = new float[N];
            for (int y = 0; y < GH; y++) {
                float sum = 0;
                int cnt = 0;
                for (int x = -r; x <= r; x++) {
                    int xx = clampInt(x, 0, GW - 1);
                    sum += lum[y * GW + xx];
                    cnt++;
                }
                for (int x = 0; x < GW; x++) {
                    tmp[y * GW + x] = sum / cnt;
                    int add = clampInt(x + r + 1, 0, GW - 1);
                    int del = clampInt(x - r, 0, GW - 1);
                    sum += lum[y * GW + add] - lum[y * GW + del];
                }
            }
            for (int x = 0; x < GW; x++) {
                float sum = 0;
                int cnt = 0;
                for (int y = -r; y <= r; y++) {
                    int yy = clampInt(y, 0, GH - 1);
                    sum += tmp[yy * GW + x];
                    cnt++;
                }
                for (int y = 0; y < GH; y++) {
                    blur[y * GW + x] = sum / cnt;
                    int add = clampInt(y + r + 1, 0, GH - 1);
                    int del = clampInt(y - r, 0, GH - 1);
                    sum += tmp[add * GW + x] - tmp[del * GW + x];
                }
            }
            for (int i = 0; i < N; i++) {
                float dl = Math.abs(lum[i] - blur[i]);
                float dr = rr[i] - meanR;
                float dg = gg[i] - meanG;
                float db = bb[i] - meanB;
                float dc = (float) Math.sqrt(dr * dr + dg * dg + db * db);
                float v = dl * 1.8f + dc * 0.9f + edge[i] * 0.08f;
                sal[i] = v;
                if (v > maxSal) maxSal = v;
            }
        }

        void findSubject() {
            // 1) 人脸优先
            if (faces != null && faces.length > 0) {
                float best = 0;
                for (float[] f : faces) {
                    if (f == null || f.length < 4) continue;
                    float a = f[2] * f[3];
                    if (a > best) {
                        best = a;
                        faceRect = new float[]{f[0], f[1], f[2], f[3]};
                    }
                }
            }
            if (faceRect != null) {
                subject = expand(faceRect, 0.12f);
                subjectConf = 1f;
                faceSubject = true;
                return;
            }
            // 2) 对焦点：从点处长出主体
            if (focusU >= 0f && focusU <= 1f && focusV >= 0f && focusV <= 1f) {
                int sx = clampInt((int) (focusU * GW), 0, GW - 1);
                int sy = clampInt((int) (focusV * GH), 0, GH - 1);
                float th = Math.max(0.18f * maxSal, 0.055f);
                float[] box = grow(sx, sy, th, true);
                if (box != null && !isLineLike(box)) {
                    subject = box;
                    subjectConf = 0.8f;
                    return;
                }
                // 对焦点周围没有明显边缘时，给一个保守的小框
                subject = clampBox(focusU - 0.11f, focusV - 0.11f, 0.22f, 0.22f);
                subjectConf = 0.5f;
                return;
            }
            // 3) 显著性最高处（带中心加权）
            float best = -1f;
            int bx = GW / 2, by = GH / 2;
            for (int y = 0; y < GH; y++) {
                for (int x = 0; x < GW; x++) {
                    float dx = (x + 0.5f) / GW - 0.5f;
                    float dy = (y + 0.5f) / GH - 0.5f;
                    float d = (float) Math.sqrt(dx * dx + dy * dy) / 0.72f;
                    float cw = 1f - 0.5f * Math.min(1f, d);
                    float v = sal[y * GW + x] * cw;
                    if (v > best) {
                        best = v;
                        bx = x;
                        by = y;
                    }
                }
            }
            if (best < 0.10f) {
                subject = null;
                subjectConf = 0f;
                return;
            }
            float th = Math.max(0.55f * sal[by * GW + bx], 0.10f);
            float[] box = grow(bx, by, th, false);
            if (box != null && box[2] * box[3] >= 0.008f && !isLineLike(box)) {
                subject = box;
                subjectConf = 0.55f;
            } else {
                // 找不到成块的显著区域时宁可不给主体建议
                subject = null;
                subjectConf = 0f;
            }
        }

        /** 从种子点四邻域长出显著区域，返回归一化包围盒；太小返回 null。 */
        float[] grow(int sx, int sy, float th, boolean limited) {
            boolean[] seen = new boolean[N];
            int[] stack = new int[N];
            int sp = 0;
            stack[sp++] = sy * GW + sx;
            seen[sy * GW + sx] = true;
            int minX = sx, maxX = sx, minY = sy, maxY = sy, count = 0;
            float seedR = rr[sy * GW + sx];
            float seedG = gg[sy * GW + sx];
            float seedB = bb[sy * GW + sx];
            float radius = Math.min(GW, GH) * 0.26f;
            float r2 = radius * radius;
            while (sp > 0) {
                int i = stack[--sp];
                int x = i % GW;
                int y = i / GW;
                count++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int k = 0; k < 4; k++) {
                    int nx = x + (k == 0 ? -1 : k == 1 ? 1 : 0);
                    int ny = y + (k == 2 ? -1 : k == 3 ? 1 : 0);
                    if (nx < 0 || nx >= GW || ny < 0 || ny >= GH) continue;
                    int ni = ny * GW + nx;
                    if (seen[ni]) continue;
                    if (limited) {
                        float ddx = nx - sx;
                        float ddy = ny - sy;
                        if (ddx * ddx + ddy * ddy > r2) continue;
                    }
                    if (sal[ni] < th) continue;
                    float cdr = rr[ni] - seedR;
                    float cdg = gg[ni] - seedG;
                    float cdb = bb[ni] - seedB;
                    if (cdr * cdr + cdg * cdg + cdb * cdb > 0.14f) continue;
                    seen[ni] = true;
                    stack[sp++] = ni;
                }
                if (sp >= N - 1) break;
            }
            if (count < 5) return null;
            return new float[]{minX / (float) GW, minY / (float) GH,
                    (maxX - minX + 1) / (float) GW, (maxY - minY + 1) / (float) GH};
        }

        /** 贯通画面的细长亮边多半是地平线/栏杆，不能当主体。 */
        boolean isLineLike(float[] b) {
            float ww = b[2];
            float hh = b[3];
            if (b[0] <= 0.005f && b[0] + ww >= 0.995f) return true;
            if (ww > 0.72f && hh < 0.16f) return true;
            if (hh > 0.90f && ww < 0.14f) return true;
            return ww * hh > 0.75f;
        }

        float[] expand(float[] b, float pad) {
            float x = b[0] - b[2] * pad;
            float y = b[1] - b[3] * pad;
            float ww = b[2] * (1f + pad * 2f);
            float hh = b[3] * (1f + pad * 2f);
            return clampBox(x, y, ww, hh);
        }

        float[] clampBox(float x, float y, float ww, float hh) {
            float l = Math.max(0f, x);
            float t = Math.max(0f, y);
            float r = Math.min(1f, Math.max(l + 0.01f, x + ww));
            float b = Math.min(1f, Math.max(t + 0.01f, y + hh));
            return new float[]{l, t, r - l, b - t};
        }

        void measureHorizon() {
            int y0 = Math.max(2, (int) (GH * 0.12f));
            int y1 = Math.min(GH - 3, (int) (GH * 0.88f));
            if (y1 - y0 < 6) return;
            int x0 = Math.max(2, (int) (GW * 0.10f));
            int x1 = Math.min(GW - 3, (int) (GW * 0.90f));
            float best = 0;
            int bestRow = -1;
            for (int y = y0; y <= y1; y++) {
                float sum = 0;
                for (int x = x0; x <= x1; x++) {
                    sum += Math.abs(lum[(y + 2) * GW + x] - lum[(y - 2) * GW + x]);
                }
                sum /= (x1 - x0 + 1);
                if (sum > best) {
                    best = sum;
                    bestRow = y;
                }
            }
            if (bestRow < 0 || best < 0.035f) return;

            // 在候选行左右各 3 格内找每列最强的水平边缘，做最小二乘拟合
            int cols = x1 - x0 + 1;
            float[] xs = new float[cols];
            float[] ys = new float[cols];
            int n = 0;
            for (int x = x0; x <= x1; x++) {
                int by = bestRow;
                float bv = -1;
                int ry0 = Math.max(2, Math.max(y0 - 2, bestRow - 3));
                int ry1 = Math.min(GH - 3, Math.min(y1 + 2, bestRow + 3));
                for (int y = ry0; y <= ry1; y++) {
                    float v = Math.abs(lum[(y + 2) * GW + x] - lum[(y - 2) * GW + x]);
                    if (v > bv) {
                        bv = v;
                        by = y;
                    }
                }
                if (bv < best * 0.35f) continue;
                xs[n] = x;
                ys[n] = by;
                n++;
            }
            if (n < cols * 0.5f) return;
            float mx = 0, my = 0;
            for (int i = 0; i < n; i++) {
                mx += xs[i];
                my += ys[i];
            }
            mx /= n;
            my /= n;
            float sxy = 0, sxx = 0;
            for (int i = 0; i < n; i++) {
                float dx = xs[i] - mx;
                sxy += dx * (ys[i] - my);
                sxx += dx * dx;
            }
            if (sxx < 1e-3f) return;
            float slope = sxy / sxx;
            float intercept = my - slope * mx;
            if (Math.abs(slope) > 0.45f) return;    // 太斜的多半不是地平线
            float tilt = (float) Math.toDegrees(Math.atan(slope));
            float residual = 0;
            for (int i = 0; i < n; i++) {
                float pred = slope * xs[i] + intercept;
                residual += Math.abs(ys[i] - pred);
            }
            residual /= n;
            if (residual > 1.8f) return;
            horizonY = (slope * (GW * 0.5f) + intercept) / GH;
            if (horizonY < 0.08f || horizonY > 0.92f) return;
            horizonTilt = tilt;
            float strength = Math.min(1f, (best - 0.035f) / 0.10f);
            float fit = Math.max(0f, 1f - residual / 1.8f);
            horizonConf = Math.max(0f, Math.min(1f, strength * 0.65f + fit * 0.45f));
        }

        void measureSky() {
            if (horizonY < 0f) {
                skyRatio = 0f;
                return;
            }
            int hy = clampInt(Math.round(horizonY * GH), 0, GH - 1);
            int sky = 0;
            for (int y = 0; y < hy; y++) {
                for (int x = 0; x < GW; x++) {
                    int i = y * GW + x;
                    boolean blue = bb[i] - rr[i] > 0.02f;
                    boolean bright = lum[i] > 0.68f;
                    if ((blue || bright) && edge[i] < 0.10f) sky++;
                }
            }
            skyRatio = sky / (float) N;
        }

        void measureSymmetry() {
            double diff = 0;
            double mag = 0;
            int cnt = 0;
            for (int y = 0; y < GH; y++) {
                for (int x = 0; x < GW / 2; x++) {
                    float a = edge[y * GW + x];
                    float b = edge[y * GW + (GW - 1 - x)];
                    diff += Math.abs(a - b);
                    mag += 0.5 * (a + b);
                    cnt++;
                }
            }
            if (cnt == 0 || mag < 0.6) {
                symmetry = 0f;
                return;
            }
            symmetry = (float) Math.max(0f, Math.min(1f, 1.0 - diff / (mag * 1.15)));
        }

        /** 边缘点做粗霍夫，找一条贯穿画面的斜线。 */
        void measureLines() {
            int maxPts = 1400;
            int[] pxs = new int[maxPts];
            int[] pys = new int[maxPts];
            int n = 0;
            for (int y = 0; y < GH && n < maxPts; y++) {
                for (int x = 0; x < GW && n < maxPts; x++) {
                    if (edge[y * GW + x] > 0.10f) {
                        pxs[n] = x;
                        pys[n] = y;
                        n++;
                    }
                }
            }
            if (n < 40) return;
            int A = 24;
            int diag = (int) Math.ceil(Math.sqrt(GW * GW + GH * GH));
            int RB = diag * 2 + 1;
            int[] votes = new int[A * RB];
            float[] cosT = new float[A];
            float[] sinT = new float[A];
            for (int a = 0; a < A; a++) {
                double t = Math.PI * a / A;
                cosT[a] = (float) Math.cos(t);
                sinT[a] = (float) Math.sin(t);
            }
            int bestV = 0, bestA = -1, bestR = 0;
            for (int i = 0; i < n; i++) {
                for (int a = 0; a < A; a++) {
                    int rho = Math.round(pxs[i] * cosT[a] + pys[i] * sinT[a]) + diag;
                    if (rho < 0 || rho >= RB) continue;
                    int idx = a * RB + rho;
                    int v = ++votes[idx];
                    if (v > bestV) {
                        bestV = v;
                        bestA = a;
                        bestR = rho;
                    }
                }
            }
            if (bestA < 0 || bestV < 28) return;
            // 方向：法线 (cos,sin)，线方向 (-sin,cos)
            float nx = cosT[bestA];
            float ny = sinT[bestA];
            if (Math.abs(ny) > 0.92f) return;   // 近水平线交给地平线检测
            float rho = bestR - diag;
            // 支持度：落在该线上、且分布够长的边缘点
            float cx = GW * 0.5f, cy = GH * 0.5f;
            float ddx = -ny, ddy = nx;
            float tmin = 1e9f, tmax = -1e9f;
            int support = 0;
            for (int i = 0; i < n; i++) {
                float d = Math.abs(nx * pxs[i] + ny * pys[i] - rho);
                if (d > 1.4f) continue;
                support++;
                float t = (pxs[i] - cx) * ddx + (pys[i] - cy) * ddy;
                if (t < tmin) tmin = t;
                if (t > tmax) tmax = t;
            }
            if (support < 22) return;
            float span = tmax - tmin;
            if (span < Math.min(GW, GH) * 0.45f) return;
            leadingLine = true;
            if (subject != null) {
                float scx = (subject[0] + subject[2] * 0.5f) * GW;
                float scy = (subject[1] + subject[3] * 0.5f) * GH;
                float d = Math.abs(nx * scx + ny * scy - rho);
                leadingAligned = d < 3.2f;
            }
        }

        void buildResult() {
            out.subject = subject;
            out.faces = faces == null ? new float[0][] : faces;
            out.horizonY = horizonY;
            out.horizonTilt = horizonTilt;
            out.horizonConf = horizonConf;
            out.skyRatio = skyRatio;
            out.symmetry = symmetry;
            out.leadingLine = leadingLine;
            out.faceSubject = faceSubject;

            float cx = subject == null ? 0.5f : subject[0] + subject[2] * 0.5f;
            float cy = subject == null ? 0.5f : subject[1] + subject[3] * 0.5f;
            float area = subject == null ? 0f : subject[2] * subject[3];

            // 背景/主体亮度差，判断逆光
            float subL = 0, bgL = 0;
            int subN = 0, bgN = 0;
            float[] meter = faceRect != null ? faceRect : subject;
            if (meter != null) {
                int l = clampInt(Math.round(meter[0] * GW), 0, GW - 1);
                int t = clampInt(Math.round(meter[1] * GH), 0, GH - 1);
                int r = clampInt(Math.round((meter[0] + meter[2]) * GW), 0, GW);
                int b = clampInt(Math.round((meter[1] + meter[3]) * GH), 0, GH);
                // 背景取主体外扩一圈之外的区域，避免把主体边缘算进去
                int padX = Math.max(2, Math.round((r - l) * 0.25f));
                int padY = Math.max(2, Math.round((b - t) * 0.25f));
                for (int y = 0; y < GH; y++) {
                    for (int x = 0; x < GW; x++) {
                        if (x >= l && x < r && y >= t && y < b) {
                            subL += lum[y * GW + x];
                            subN++;
                        } else if (x < l - padX || x >= r + padX || y < t - padY || y >= b + padY) {
                            bgL += lum[y * GW + x];
                            bgN++;
                        }
                    }
                }
            }
            float backlight = 0f;
            if (subN > 0 && bgN > 0) {
                subL /= subN;
                bgL /= bgN;
                backlight = bgL - subL;
            }

            // 主体之外边缘密度 = 背景杂乱度
            double eo = 0;
            int eoN = 0;
            if (subject != null) {
                int l = clampInt(Math.round(subject[0] * GW), 0, GW - 1);
                int t = clampInt(Math.round(subject[1] * GH), 0, GH - 1);
                int r = clampInt(Math.round((subject[0] + subject[2]) * GW), 0, GW);
                int b = clampInt(Math.round((subject[1] + subject[3]) * GH), 0, GH);
                for (int y = 0; y < GH; y++) {
                    for (int x = 0; x < GW; x++) {
                        if (x >= l && x < r && y >= t && y < b) continue;
                        eo += edge[y * GW + x];
                        eoN++;
                    }
                }
                edgeOutside = eoN == 0 ? 0 : (float) (eo / eoN);
            }

            // 三分线距离（只算水平方向；竖屏拍摄时主体左右位置最要紧）
            float off = 1f;
            float target = cx;
            if (subject != null) {
                float l1 = 1f / 3f, l2 = 2f / 3f;
                float d1 = Math.abs(cx - l1), d2 = Math.abs(cx - l2);
                off = Math.min(d1, d2);
                target = d1 <= d2 ? l1 : l2;
            }
            float headroom = faceRect == null ? 1f : faceRect[1];

            boolean portrait = subjectPref == SUBJECT_PORTRAIT || faceSubject;
            float headroomWarn = 0.02f;
            float thirdsWarn = portrait ? 0.13f : 0.10f;

            // 依次挑一条最值得说的
            if (horizonConf > 0.55f && Math.abs(horizonTilt) > 2.0f
                    && subjectPref != SUBJECT_PORTRAIT) {
                warn("地平线歪了 " + one(horizonTilt) + "°，把手机转正一点", 0f);
            } else if (backlight > 0.20f && subjectConf >= 0.5f) {
                warn("逆光：背景比主体亮，点下面套用逆光", 0f);
                out.action = "scene:backlight";
                out.actionLabel = "套用「逆光」场景";
            } else if (subject != null && area < 0.032f && subjectConf >= 0.5f) {
                info("主体偏小，走近一点或放大", 0f);
            } else if (faceRect != null && headroom < headroomWarn) {
                warn("头顶快顶到边了，留一点空间", 0f);
            } else if (subject != null && area > 0.72f) {
                info("主体太满，退后一点", 0f);
            } else if (subject != null && off > thirdsWarn && subjectConf >= 0.5f) {
                float dx = target - cx;
                info("往" + (dx > 0 ? "右" : "左") + "挪一点，放到三分线上",
                        dx > 0 ? 1f : -1f);
            } else if (subject != null && edgeOutside > 0.115f) {
                info("背景有点乱，靠近主体或换个角度", 0f);
            } else if (symmetry > 0.82f && Math.abs(cx - 0.5f) > 0.06f) {
                info("左右很对称，试试居中", 0f);
            } else if (leadingLine && leadingAligned) {
                good("引导线构图 · 线条指向主体");
            } else if (symmetry > 0.82f) {
                good("对称构图 · 画面很稳");
            } else if (subject != null && off <= 0.07f) {
                good("三分构图 · 位置不错");
            } else {
                good("构图可以，按下快门");
            }

            // 第二行小字
            StringBuilder d = new StringBuilder();
            if (subject != null) d.append("主体 ").append(Math.round(area * 100)).append("%");
            if (horizonConf > 0.4f) {
                if (d.length() > 0) d.append(" · ");
                d.append("地平线 ").append(one(horizonTilt)).append("°");
            }
            if (skyRatio > 0.01f) {
                if (d.length() > 0) d.append(" · ");
                d.append("天空 ").append(Math.round(skyRatio * 100)).append("%");
            }
            out.detail = d.toString();
        }

        void warn(String text, float arrow) {
            out.severity = SEV_WARN;
            out.hint = text;
            out.arrowX = arrow;
        }

        void info(String text, float arrow) {
            out.severity = SEV_INFO;
            out.hint = text;
            out.arrowX = arrow;
        }

        void good(String text) {
            out.severity = SEV_GOOD;
            out.hint = text;
        }

        static String one(float v) {
            return String.format(java.util.Locale.US, "%.1f", v);
        }

        static int clampInt(int v, int lo, int hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }
}
