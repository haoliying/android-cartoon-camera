package com.qoder.cartooncamera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 独立的 GL 渲染线程：相机 OES 纹理 -> 保边模糊 -> 卡通化 -> 预览 / 编码器。
 * 预览与 MediaRecorder 的输入 Surface 共用同一个 EGL Context。
 */
public class CartoonRenderer {
    private static final String TAG = "CartoonRenderer";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    public interface Callback {
        void onCameraSurfaceReady(Surface surface, int frameWidth, int frameHeight);
        void onError(String message);
    }

    public interface EncoderCallback {
        void onEncoderReady();
        void onEncoderError(String message);
    }

    public interface ErrorListener {
        void onError(String message);
    }

    private HandlerThread thread;
    private Handler handler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile ErrorListener errorListener;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface previewSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface encoderSurface = EGL14.EGL_NO_SURFACE;

    private int previewW, previewH;
    private int encoderW, encoderH;

    private int cameraTextureId;
    private SurfaceTexture cameraTexture;
    private Surface cameraSurface;

    private int fboA, fboB, texA, texB, fboW, fboH, halfW, halfH;
    private int quadBuf;
    private Program progDown, progBlur, progFinal, progSprite, progCopy;
    private boolean frameScheduled;

    // 小精灵：内容缩略图 + 状态机 + 传感器运动
    private final SpriteSystem sprites = new SpriteSystem();
    private int contentFbo, contentTex, contentTick;
    private ByteBuffer contentBuf;
    private final byte[] contentBytes = new byte[SpriteSystem.GRID * SpriteSystem.GRID * 4];
    private volatile boolean spritesEnabled = true;
    private volatile float motYaw, motPitch, motRoll, motSpeed;
    private long animStartNs;
    private float animTime, lastAnimT;
    private float cropX = 1f, cropY = 1f;

    private final float[] texMatrix = new float[16];
    private volatile StylePreset style = StylePreset.ALL[0];
    private volatile float intensity = 0.95f;
    private volatile boolean mirror = false;

    private final Runnable frameRunnable = this::renderFrame;

    public void setErrorListener(ErrorListener listener) {
        this.errorListener = listener;
    }

    public void setStyle(StylePreset preset, float intensity) {
        this.style = preset;
        this.intensity = intensity;
    }

    public void setMirror(boolean mirror) {
        this.mirror = mirror;
    }

    /** 开关小精灵；关闭时清空现有精灵。 */
    public void setSpritesEnabled(boolean on) {
        spritesEnabled = on;
        post(() -> {
            if (!on) sprites.clear();
        });
    }

    /** 由传感器推送的角速度（弧度/秒）：yaw 左右、pitch 上下、roll 旋转。 */
    public void setMotion(float yaw, float pitch, float roll, float speed) {
        motYaw = yaw;
        motPitch = pitch;
        motRoll = roll;
        motSpeed = speed;
    }

    /** 触摸：u,v 为屏幕比例坐标（v 向上），action 0 按下 1 移动 2 抬起。 */
    public void onTouch(float u, float v, int action) {
        final float uu = mirror ? 1f - u : u;
        final boolean on = spritesEnabled;
        post(() -> {
            float aspect = (previewW > 0 && previewH > 0)
                    ? previewW / (float) previewH : 0.5f;
            sprites.touch(uu, v, action, aspect);
            if (!on) sprites.clear();
        });
    }

    // ------------------------------------------------------------------ lifecycle

    public void start(Surface window, int width, int height) {
        if (thread == null) {
            thread = new HandlerThread("cartoon-gl");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        post(() -> {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                if (!initEgl()) {
                    notifyError("图形渲染初始化失败");
                    return;
                }
            }
            createPreviewSurface(window, width, height);
        });
    }

    public void updatePreviewSize(int width, int height) {
        post(() -> {
            previewW = width;
            previewH = height;
        });
    }

    /** 在 GL 线程创建相机用的 SurfaceTexture / Surface。 */
    public void createCameraSurface(Size cameraSize, boolean swapDims, Callback cb) {
        post(() -> {
            try {
                if (eglDisplay == EGL14.EGL_NO_DISPLAY && !initEgl()) {
                    throw new IllegalStateException("图形渲染未就绪");
                }
                makeCurrent(pbuffer);
                releaseCameraTextureInternal();
                createCameraTextureInternal(cameraSize);
                int ow = swapDims ? cameraSize.getHeight() : cameraSize.getWidth();
                int oh = swapDims ? cameraSize.getWidth() : cameraSize.getHeight();
                ensureFbos(ow, oh);
                if (cb != null) {
                    final Surface s = cameraSurface;
                    final int fw = ow, fh = oh;
                    mainHandler.post(() -> cb.onCameraSurfaceReady(s, fw, fh));
                }
            } catch (Throwable t) {
                Log.e(TAG, "createCameraSurface failed", t);
                if (cb != null) {
                    mainHandler.post(() -> cb.onError("相机画面初始化失败：" + t.getMessage()));
                }
            }
        });
    }

    /** 释放 GL 线程、EGL 上下文以及所有 Surface（阻塞等待，保证 Surface 失效前清理完毕）。 */
    public void release() {
        final HandlerThread t = thread;
        final Handler h = handler;
        if (h == null) return;
        final CountDownLatch latch = new CountDownLatch(1);
        h.post(() -> {
            try {
                releaseAllInternal();
            } catch (Throwable e) {
                Log.e(TAG, "release failed", e);
            } finally {
                latch.countDown();
            }
            if (t != null) t.quitSafely();
        });
        try {
            latch.await(800, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (thread == t) {
                thread = null;
                handler = null;
            }
        }
    }

    private void post(Runnable r) {
        Handler h = handler;
        if (h != null) h.post(r);
    }

    private void notifyError(String message) {
        Log.e(TAG, message);
        ErrorListener l = errorListener;
        if (l != null) mainHandler.post(() -> l.onError(message));
    }

    // ------------------------------------------------------------------ encoder

    public void startEncoder(Surface surface, int width, int height, EncoderCallback cb) {
        post(() -> {
            try {
                if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglConfig == null) {
                    throw new IllegalStateException("图形渲染未就绪");
                }
                destroyEncoderInternal();
                int[] attribs = {EGL14.EGL_NONE};
                EGLSurface es = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0);
                if (es == EGL14.EGL_NO_SURFACE) {
                    throw new IllegalStateException("无法创建录像渲染表面");
                }
                if (!makeCurrent(es)) {
                    EGL14.eglDestroySurface(eglDisplay, es);
                    throw new IllegalStateException("无法激活录像渲染表面");
                }
                encoderSurface = es;
                encoderW = width;
                encoderH = height;
                if (cb != null) mainHandler.post(cb::onEncoderReady);
            } catch (Throwable t) {
                Log.e(TAG, "startEncoder failed", t);
                if (cb != null) {
                    final String m = t.getMessage() == null ? "录像初始化失败" : t.getMessage();
                    mainHandler.post(() -> cb.onEncoderError(m));
                }
            }
        });
    }

    public void stopEncoder(Runnable done) {
        post(() -> {
            destroyEncoderInternal();
            if (done != null) mainHandler.post(done);
        });
    }

    // ------------------------------------------------------------------ GL init

    private boolean initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "no EGL display");
            return false;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            return false;
        }

        int[] withRecordable = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        int[] withoutRecordable = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        boolean found = EGL14.eglChooseConfig(eglDisplay, withRecordable, 0, configs, 0, 1, num, 0)
                && num[0] > 0;
        if (!found) {
            found = EGL14.eglChooseConfig(eglDisplay, withoutRecordable, 0, configs, 0, 1, num, 0)
                    && num[0] > 0;
        }
        if (!found) {
            Log.e(TAG, "no EGL config");
            return false;
        }
        eglConfig = configs[0];

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "no EGL context");
            return false;
        }

        int[] pbAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };
        pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0);
        if (pbuffer == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "no pbuffer");
            return false;
        }
        if (!makeCurrent(pbuffer)) {
            Log.e(TAG, "makeCurrent failed");
            return false;
        }

        quadBuf = createQuadBuffer();
        if (!buildPrograms()) return false;
        createContentTarget();

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        // 关闭抖动：8 位中间缓冲的抖动噪点会被描边放大成横纹
        GLES20.glDisable(GLES20.GL_DITHER);
        GLES20.glClearColor(0.03f, 0.03f, 0.05f, 1f);
        return true;
    }

    private boolean makeCurrent(EGLSurface surface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return false;
        return EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext);
    }

    private void createPreviewSurface(Surface window, int w, int h) {
        destroyPreviewInternal();
        if (window == null || !window.isValid() || w <= 0 || h <= 0) return;
        int[] attribs = {EGL14.EGL_NONE};
        previewSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, window, attribs, 0);
        if (previewSurface == EGL14.EGL_NO_SURFACE) {
            previewSurface = EGL14.EGL_NO_SURFACE;
            return;
        }
        previewW = w;
        previewH = h;
    }

    private void createCameraTextureInternal(Size size) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        cameraTextureId = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        cameraTexture = new SurfaceTexture(cameraTextureId);
        cameraTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        Handler h = handler;
        if (h != null) {
            cameraTexture.setOnFrameAvailableListener(st -> {
                if (!frameScheduled) {
                    frameScheduled = true;
                    Handler hh = handler;
                    if (hh != null) hh.post(frameRunnable);
                }
            }, h);
        }
        cameraSurface = new Surface(cameraTexture);
    }

    private void releaseCameraTextureInternal() {
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
        if (cameraTexture != null) {
            cameraTexture.setOnFrameAvailableListener(null);
            cameraTexture.release();
            cameraTexture = null;
        }
        if (cameraTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{cameraTextureId}, 0);
            cameraTextureId = 0;
        }
    }

    private void ensureFbos(int w, int h) {
        if (w == fboW && h == fboH && texA != 0) return;
        deleteFbos();
        fboW = w;
        fboH = h;
        halfW = Math.max(2, w / 2);
        halfH = Math.max(2, h / 2);
        int[] tex = new int[2];
        GLES20.glGenTextures(2, tex, 0);
        texA = tex[0];
        texB = tex[1];
        for (int id : tex) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, halfW, halfH, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        int[] fbo = new int[2];
        GLES20.glGenFramebuffers(2, fbo, 0);
        fboA = fbo[0];
        fboB = fbo[1];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texA, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texB, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            notifyError("离屏渲染缓冲创建失败");
        }
    }

    private void deleteFbos() {
        if (fboA != 0 || fboB != 0) {
            GLES20.glDeleteFramebuffers(2, new int[]{fboA, fboB}, 0);
            fboA = 0;
            fboB = 0;
        }
        if (texA != 0 || texB != 0) {
            GLES20.glDeleteTextures(2, new int[]{texA, texB}, 0);
            texA = 0;
            texB = 0;
        }
        fboW = 0;
        fboH = 0;
        halfW = 0;
        halfH = 0;
    }

    private void destroyPreviewInternal() {
        if (previewSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeCurrent(pbuffer);
            EGL14.eglDestroySurface(eglDisplay, previewSurface);
        }
        previewSurface = EGL14.EGL_NO_SURFACE;
        previewW = 0;
        previewH = 0;
    }

    private void destroyEncoderInternal() {
        if (encoderSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeCurrent(pbuffer);
            EGL14.eglDestroySurface(eglDisplay, encoderSurface);
        }
        encoderSurface = EGL14.EGL_NO_SURFACE;
        encoderW = 0;
        encoderH = 0;
    }

    private void destroyEglInternal() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            if (pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbuffer);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglContext = EGL14.EGL_NO_CONTEXT;
        pbuffer = EGL14.EGL_NO_SURFACE;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglConfig = null;
    }

    private void releaseAllInternal() {
        destroyEncoderInternal();
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            makeCurrent(pbuffer);
        }
        releaseCameraTextureInternal();
        destroyPreviewInternal();
        deleteFbos();
        if (quadBuf != 0) {
            GLES20.glDeleteBuffers(1, new int[]{quadBuf}, 0);
            quadBuf = 0;
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            if (progDown != null) progDown.delete();
            if (progBlur != null) progBlur.delete();
            if (progFinal != null) progFinal.delete();
            if (progSprite != null) progSprite.delete();
            if (progCopy != null) progCopy.delete();
        }
        progDown = null;
        progBlur = null;
        progFinal = null;
        progSprite = null;
        progCopy = null;
        deleteContentTarget();
        destroyEglInternal();
    }

    // ------------------------------------------------------------------ rendering

    private void renderFrame() {
        frameScheduled = false;
        if (cameraTexture == null || eglDisplay == EGL14.EGL_NO_DISPLAY) return;
        if (!makeCurrent(pbuffer)) return;
        try {
            cameraTexture.updateTexImage();
            cameraTexture.getTransformMatrix(texMatrix);
        } catch (Throwable t) {
            return;
        }
        updateSprites();
        updateBlurPasses();
        if (previewSurface != EGL14.EGL_NO_SURFACE && previewW > 0 && previewH > 0) {
            blit(previewSurface, previewW, previewH);
        }
        if (encoderSurface != EGL14.EGL_NO_SURFACE && encoderW > 0 && encoderH > 0) {
            blit(encoderSurface, encoderW, encoderH);
        }
    }

    private void updateBlurPasses() {
        if (fboA == 0 || fboB == 0 || cameraTextureId == 0) return;
        StylePreset st = style;

        // 1) 相机纹理 -> 半分辨率（同时做 2x2 盒式降采样，为后续大半径平滑提供基础）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA);
        GLES20.glViewport(0, 0, halfW, halfH);
        Program pd = progDown;
        GLES20.glUseProgram(pd.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(pd.u("uTex"), 0);
        GLES20.glUniformMatrix4fv(pd.u("uTexMatrix"), 1, false, texMatrix, 0);
        GLES20.glUniform2f(pd.u("uSrcTexel"), 1f / fboW, 1f / fboH);
        drawQuad(pd);

        // 2) 水平保边平滑（半分辨率下半径换算为 2 倍，等效全分辨率 2*R 像素）
        float radius = st.blur * 2f;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB);
        GLES20.glViewport(0, 0, halfW, halfH);
        Program pb = progBlur;
        GLES20.glUseProgram(pb.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
        GLES20.glUniform1i(pb.u("uTex"), 0);
        GLES20.glUniform2f(pb.u("uTexel"), 1f / halfW, 1f / halfH);
        GLES20.glUniform2f(pb.u("uDir"), 1f, 0f);
        GLES20.glUniform1f(pb.u("uRadius"), radius);
        GLES20.glUniform1f(pb.u("uBilateral"), st.bilateral);
        drawQuad(pb);

        // 3) 垂直保边平滑 -> texA 为最终平滑结果
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA);
        GLES20.glViewport(0, 0, halfW, halfH);
        GLES20.glUseProgram(pb.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);
        GLES20.glUniform1i(pb.u("uTex"), 0);
        GLES20.glUniform2f(pb.u("uDir"), 0f, 1f);
        drawQuad(pb);

        readContentGrid();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void blit(EGLSurface target, int vw, int vh) {
        if (!makeCurrent(target)) return;
        GLES20.glViewport(0, 0, vw, vh);
        StylePreset st = style;
        Program p = progFinal;
        GLES20.glUseProgram(p.id);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(p.u("uVideo"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);
        GLES20.glUniform1i(p.u("uBlur"), 1);

        float targetAspect = vw / (float) vh;
        float sourceAspect = fboW / (float) fboH;
        float cx = 1f, cy = 1f;
        if (targetAspect < sourceAspect) {
            cx = targetAspect / sourceAspect;
        } else {
            cy = sourceAspect / targetAspect;
        }
        cropX = cx;
        cropY = cy;
        float snap = st.pixel > 1f ? (st.pixel / fboW) : 0f;

        GLES20.glUniform2f(p.u("uCrop"), cx, cy);
        GLES20.glUniform1f(p.u("uMirror"), mirror ? 1f : 0f);
        GLES20.glUniform1f(p.u("uSnap"), snap);
        GLES20.glUniformMatrix4fv(p.u("uTexMatrix"), 1, false, texMatrix, 0);
        GLES20.glUniform2f(p.u("uTexel"), 1f / fboW, 1f / fboH);
        GLES20.glUniform2f(p.u("uBlurTexel"), 1f / halfW, 1f / halfH);
        GLES20.glUniform1f(p.u("uDetail"), st.detail);
        GLES20.glUniform1f(p.u("uLevels"), st.levels);
        GLES20.glUniform1f(p.u("uChroma"), st.chroma);
        GLES20.glUniform1f(p.u("uSat"), st.sat);
        GLES20.glUniform1f(p.u("uEdge"), st.edge);
        GLES20.glUniform1f(p.u("uThresh"), st.thresh);
        GLES20.glUniform1f(p.u("uEdgeWidth"), st.edgeWidth);
        GLES20.glUniform1f(p.u("uHalftone"), st.halftone);
        GLES20.glUniform1f(p.u("uGray"), st.gray);
        GLES20.glUniform1f(p.u("uNeon"), st.neon);
        GLES20.glUniform1f(p.u("uGrain"), st.grain);
        GLES20.glUniform1f(p.u("uBright"), st.bright);
        GLES20.glUniform1f(p.u("uSketch"), st.sketch);
        GLES20.glUniform1f(p.u("uIntensity"), intensity);
        GLES20.glUniform1f(p.u("uTime"), animTime);
        GLES20.glUniform1f(p.u("uAspect"), targetAspect);
        GLES20.glUniform4f(p.u("uRip0"), sprites.ripU[0], sprites.ripV[0],
                sprites.ripAge[0], sprites.ripOn[0] ? 1f : 0f);
        GLES20.glUniform4f(p.u("uRip1"), sprites.ripU[1], sprites.ripV[1],
                sprites.ripAge[1], sprites.ripOn[1] ? 1f : 0f);
        drawQuad(p);

        drawSprites(vw, vh);

        EGL14.eglSwapBuffers(eglDisplay, target);
    }

    // ------------------------------------------------------------------ sprites

    /** 16x16 缩略图，用来判断画面哪块“平整”，供小精灵选址。 */
    private void createContentTarget() {
        if (contentFbo != 0) return;
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        contentTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, contentTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                SpriteSystem.GRID, SpriteSystem.GRID, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        contentFbo = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, contentFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, contentTex, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            contentFbo = 0;
            Log.w(TAG, "content fbo incomplete");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        contentBuf = ByteBuffer.allocateDirect(contentBytes.length).order(ByteOrder.nativeOrder());
    }

    private void deleteContentTarget() {
        if (contentFbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{contentFbo}, 0);
            contentFbo = 0;
        }
        if (contentTex != 0) {
            GLES20.glDeleteTextures(1, new int[]{contentTex}, 0);
            contentTex = 0;
        }
        contentBuf = null;
    }

    /** 每 10 帧回读一次 16x16，开销很小。 */
    private void readContentGrid() {
        if (contentFbo == 0 || progCopy == null || texA == 0) return;
        if ((contentTick++ % 10) != 0) return;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, contentFbo);
        GLES20.glViewport(0, 0, SpriteSystem.GRID, SpriteSystem.GRID);
        Program p = progCopy;
        GLES20.glUseProgram(p.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
        GLES20.glUniform1i(p.u("uTex"), 0);
        drawQuad(p);
        GLES20.glReadPixels(0, 0, SpriteSystem.GRID, SpriteSystem.GRID,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, contentBuf);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        contentBuf.position(0);
        contentBuf.get(contentBytes);
        contentBuf.position(0);
        sprites.setContent(contentBytes, SpriteSystem.GRID, SpriteSystem.GRID, cropX, cropY);
    }

    private void updateSprites() {
        long now = System.nanoTime();
        if (animStartNs == 0) {
            animStartNs = now;
            lastAnimT = 0f;
            animTime = 0f;
            return;
        }
        animTime = (now - animStartNs) / 1e9f;
        float dt = lastAnimT == 0f ? 0.016f : animTime - lastAnimT;
        lastAnimT = animTime;
        if (dt < 0f) dt = 0f;
        if (dt > 0.1f) dt = 0.1f;
        if (spritesEnabled) {
            sprites.update(dt, animTime, motYaw, motPitch, motRoll, motSpeed);
        } else {
            sprites.update(dt, animTime, 0f, 0f, 0f, 0f);
        }
    }

    private final float[] tint = new float[3];

    static void hueColor(float h, float s, float v, float[] out) {
        h = h - (float) Math.floor(h);
        float i = (float) Math.floor(h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        switch ((int) i % 6) {
            case 0: out[0] = v; out[1] = t; out[2] = p; break;
            case 1: out[0] = q; out[1] = v; out[2] = p; break;
            case 2: out[0] = p; out[1] = v; out[2] = t; break;
            case 3: out[0] = p; out[1] = q; out[2] = v; break;
            case 4: out[0] = t; out[1] = p; out[2] = v; break;
            default: out[0] = v; out[1] = p; out[2] = q; break;
        }
    }

    private void drawSprites(int vw, int vh) {
        if (!spritesEnabled || progSprite == null || vw <= 0 || vh <= 0) return;
        boolean any = false;
        for (SpriteSystem.Sprite s : sprites.sprites) {
            if (s != null && s.fade > 0.01f) { any = true; break; }
        }
        if (!any) {
            for (SpriteSystem.Particle q : sprites.particles) {
                if (q != null) { any = true; break; }
            }
        }
        if (!any) return;

        Program p = progSprite;
        float k = vh / (float) vw;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(p.id);
        GLES20.glUniform1f(p.u("uMirror"), mirror ? 1f : 0f);
        GLES20.glUniform1f(p.u("uTime"), animTime);

        for (SpriteSystem.Sprite s : sprites.sprites) {
            if (s == null || s.fade <= 0.01f) continue;
            float halfY = s.size * s.fade * s.scale;
            float halfX = halfY * k;
            float cu = mirror ? 1f - s.u : s.u;
            hueColor(s.hue, s.sat, 1f, tint);
            GLES20.glUniform2f(p.u("uCenter"), cu * 2f - 1f, s.v * 2f - 1f);
            GLES20.glUniform2f(p.u("uHalf"), halfX, halfY);
            GLES20.glUniform1f(p.u("uRot"), s.rot);
            GLES20.glUniform1f(p.u("uShape"), 0f);
            GLES20.glUniform3f(p.u("uTint"), tint[0], tint[1], tint[2]);
            GLES20.glUniform1f(p.u("uMood"), s.mood);
            GLES20.glUniform1f(p.u("uBlink"), s.blink);
            GLES20.glUniform1f(p.u("uSeed"), s.seed);
            GLES20.glUniform2f(p.u("uSquash"), Math.max(0.05f, s.squashX),
                    Math.max(0.05f, s.squashY));
            GLES20.glUniform2f(p.u("uLook"), s.lookX, s.lookY);
            GLES20.glUniform1f(p.u("uSpecies"), s.species);
            GLES20.glUniform1f(p.u("uTemper"), s.temper);
            GLES20.glUniform1f(p.u("uBounce"), s.bounce);
            float halfPix = Math.max(3f, halfY * vh * 0.5f);
            GLES20.glUniform1f(p.u("uAA"), 1.7f / halfPix);
            GLES20.glUniform1f(p.u("uAlpha"), 1f);
            drawQuad(p);
        }

        for (SpriteSystem.Particle q : sprites.particles) {
            if (q == null || !q.alive) continue;
            float t = q.ttl > 0f ? Math.min(1f, q.age / q.ttl) : 1f;
            float alpha = (1f - t) * Math.min(1f, q.age / 0.1f);
            float grow = 1f;
            int shape = q.shape;
            switch (shape) {
                case 1:
                    hueColor(0.96f, 0.72f, 1f, tint);
                    grow = 0.85f + 0.3f * t;
                    break;
                case 2:
                    hueColor(0.13f, 0.85f, 1f, tint);
                    grow = 0.85f + 0.4f * t;
                    break;
                case 3:
                    tint[0] = 1f; tint[1] = 0.94f; tint[2] = 0.75f;
                    grow = 1f + 2.2f * t;
                    break;
                default:
                    tint[0] = 0.94f; tint[1] = 0.94f; tint[2] = 0.9f;
                    grow = 1f + 1.6f * t;
                    alpha *= 0.7f;
                    break;
            }
            float halfY = q.size * grow;
            float halfX = halfY * k;
            float cu = mirror ? 1f - q.u : q.u;
            GLES20.glUniform2f(p.u("uCenter"), cu * 2f - 1f, q.v * 2f - 1f);
            GLES20.glUniform2f(p.u("uHalf"), halfX, halfY);
            GLES20.glUniform1f(p.u("uRot"), q.rot);
            GLES20.glUniform1f(p.u("uShape"), (float) shape);
            GLES20.glUniform3f(p.u("uTint"), tint[0], tint[1], tint[2]);
            GLES20.glUniform1f(p.u("uMood"), 0f);
            GLES20.glUniform1f(p.u("uBlink"), 0f);
            GLES20.glUniform1f(p.u("uSeed"), 0f);
            GLES20.glUniform2f(p.u("uSquash"), 1f, 1f);
            GLES20.glUniform2f(p.u("uLook"), 0f, 0f);
            GLES20.glUniform1f(p.u("uSpecies"), 0f);
            GLES20.glUniform1f(p.u("uTemper"), 0f);
            GLES20.glUniform1f(p.u("uBounce"), 1f);
            float halfPix = Math.max(3f, halfY * vh * 0.5f);
            GLES20.glUniform1f(p.u("uAA"), 1.7f / halfPix);
            GLES20.glUniform1f(p.u("uAlpha"), Math.max(0f, alpha));
            drawQuad(p);
        }

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ------------------------------------------------------------------ GL helpers

    private int createQuadBuffer() {
        float[] v = {
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
        };
        FloatBuffer buf = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v).position(0);
        int[] id = new int[1];
        GLES20.glGenBuffers(1, id, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, v.length * 4, buf, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        return id[0];
    }

    private void drawQuad(Program p) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf);
        GLES20.glEnableVertexAttribArray(p.aPos);
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(p.aPos);
    }

    private boolean buildPrograms() {
        try {
            progDown = new Program(VS_CAMERA, FS_DOWN);
            progBlur = new Program(VS_PLAIN, FS_BLUR);
            progFinal = new Program(VS_FINAL, FS_FINAL);
            progSprite = new Program(SpriteShaders.VS_SPRITE, SpriteShaders.FS_SPRITE);
            progCopy = new Program(VS_PLAIN, SpriteShaders.FS_COPY);
            return true;
        } catch (Throwable t) {
            notifyError("滤镜着色器编译失败：" + t.getMessage());
            return false;
        }
    }

    static String glsl(String... lines) {
        return String.join("\n", lines);
    }

    private final class Program {
        final int id;
        final int aPos;
        private final HashMap<String, Integer> locs = new HashMap<>();

        Program(String vs, String fs) {
            int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
            int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
            int pid = GLES20.glCreateProgram();
            GLES20.glAttachShader(pid, v);
            GLES20.glAttachShader(pid, f);
            GLES20.glLinkProgram(pid);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(pid, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(pid);
                GLES20.glDeleteProgram(pid);
                throw new RuntimeException(log);
            }
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            id = pid;
            aPos = GLES20.glGetAttribLocation(pid, "aPos");
        }

        int u(String name) {
            Integer l = locs.get(name);
            if (l == null) {
                l = GLES20.glGetUniformLocation(id, name);
                locs.put(name, l);
            }
            return l;
        }

        void delete() {
            GLES20.glDeleteProgram(id);
        }
    }

    private static int loadShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("shader compile failed: " + log);
        }
        return shader;
    }

    // ------------------------------------------------------------------ shaders

    static final String VS_CAMERA = glsl(
            "attribute vec2 aPos;",
            "uniform mediump mat4 uTexMatrix;",
            "varying vec2 vUv;",
            "void main() {",
            "  vUv = (uTexMatrix * vec4(aPos, 0.0, 1.0)).xy;",
            "  gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);",
            "}"
    );

    static final String VS_PLAIN = glsl(
            "attribute vec2 aPos;",
            "varying vec2 vUv;",
            "void main() {",
            "  vUv = aPos;",
            "  gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);",
            "}"
    );

    static final String VS_FINAL = glsl(
            "attribute vec2 aPos;",
            "uniform mediump vec2 uCrop;",
            "uniform mediump float uMirror;",
            "uniform mediump float uFlipV;",
            "varying vec2 vUv;",
            "void main() {",
            "  vec2 o = (aPos - 0.5) * uCrop + 0.5;",
            "  if (uMirror > 0.5) { o.x = 1.0 - o.x; }",
            "  if (uFlipV > 0.5) { o.y = 1.0 - o.y; }",
            "  vUv = o;",
            "  gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);",
            "}"
    );

    /** 相机纹理 -> 半分辨率，2x2 盒式降采样。 */
    static final String FS_DOWN = glsl(
            "#extension GL_OES_EGL_image_external : require",
            "precision mediump float;",
            "uniform samplerExternalOES uTex;",
            "uniform vec2 uSrcTexel;",
            "varying vec2 vUv;",
            "void main() {",
            "  vec2 o = uSrcTexel * 0.5;",
            "  vec3 c = texture2D(uTex, vUv + vec2(-o.x, -o.y)).rgb;",
            "  c += texture2D(uTex, vUv + vec2(o.x, -o.y)).rgb;",
            "  c += texture2D(uTex, vUv + vec2(-o.x, o.y)).rgb;",
            "  c += texture2D(uTex, vUv + vec2(o.x, o.y)).rgb;",
            "  gl_FragColor = vec4(c * 0.25, 1.0);",
            "}"
    );

    /** 可分离双边平滑（保边去细节，形成平整色块）。 */
    static final String FS_BLUR = glsl(
            "precision mediump float;",
            "uniform sampler2D uTex;",
            "varying vec2 vUv;",
            "uniform vec2 uTexel;",
            "uniform vec2 uDir;",
            "uniform float uRadius;",
            "uniform float uBilateral;",
            "float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }",
            "void main() {",
            "  vec2 d = uDir * uTexel * uRadius;",
            "  vec3 c0 = texture2D(uTex, vUv).rgb;",
            "  float l0 = luma(c0);",
            "  vec3 sum = c0;",
            "  float ws = 1.0;",
            "  for (int i = 1; i <= 4; i++) {",
            "    float fi = float(i);",
            "    float w = 0.42 * pow(0.62, fi - 1.0);",
            "    vec3 a = texture2D(uTex, vUv + d * fi).rgb;",
            "    vec3 b = texture2D(uTex, vUv - d * fi).rgb;",
            "    float la = luma(a);",
            "    float lb = luma(b);",
            "    float wa = w * exp(-((la - l0) * (la - l0)) * 90.0 * uBilateral);",
            "    float wb = w * exp(-((lb - l0) * (lb - l0)) * 90.0 * uBilateral);",
            "    sum += a * wa + b * wb;",
            "    ws += wa + wb;",
            "  }",
            "  gl_FragColor = vec4(sum / ws, 1.0);",
            "}"
    );

    static final String FS_FINAL = glsl(
            "#extension GL_OES_EGL_image_external : require",
            "precision mediump float;",
            "varying vec2 vUv;",
            "uniform samplerExternalOES uVideo;",
            "uniform sampler2D uBlur;",
            "uniform mat4 uTexMatrix;",
            "uniform float uSnap;",
            "uniform vec2 uTexel;",
            "uniform vec2 uBlurTexel;",
            "uniform float uDetail;",
            "uniform float uLevels;",
            "uniform float uChroma;",
            "uniform float uSat;",
            "uniform float uEdge;",
            "uniform float uThresh;",
            "uniform float uEdgeWidth;",
            "uniform float uHalftone;",
            "uniform float uGray;",
            "uniform float uNeon;",
            "uniform float uGrain;",
            "uniform float uBright;",
            "uniform float uSketch;",
            "uniform float uIntensity;",
            "uniform float uTime;",
            "uniform vec2 uCrop;",
            "uniform float uMirror;",
            "uniform float uAspect;",
            "uniform vec4 uRip0;",
            "uniform vec4 uRip1;",
            "float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }",
            "float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }",
            // 触摸涟漪：以触摸点为中心扩散的水波，把画面轻轻推开并提亮波前
            "vec2 rippleUv(vec2 s, vec4 r, out float wave) {",
            "  wave = 0.0;",
            "  if (r.w < 0.5) { return s; }",
            "  vec2 rc = (r.xy - 0.5) * uCrop + 0.5;",
            "  if (uMirror > 0.5) { rc.x = 1.0 - rc.x; }",
            "  vec2 d = (s - rc) / uCrop * vec2(uAspect, 1.0);",
            "  float t = (length(d) - r.z * 0.72) * 4.2;",
            "  float band = exp(-t * t);",
            "  float life = clamp(1.0 - r.z / 1.15, 0.0, 1.0);",
            "  wave = band * life;",
            "  return s + normalize(d + vec2(0.0001)) * band * life * 0.018 * uCrop;",
            "}",
            "void main() {",
            "  vec2 uv = vUv;",
            "  if (uSnap > 0.0) { uv = (floor(uv / uSnap) + 0.5) * uSnap; }",
            "  float w0 = 0.0;",
            "  float w1 = 0.0;",
            "  uv = rippleUv(uv, uRip0, w0);",
            "  uv = rippleUv(uv, uRip1, w1);",
            "  float wave = w0 + w1;",
            "  vec3 orig = texture2D(uVideo, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;",
            "  vec3 soft = texture2D(uBlur, uv).rgb;",
            "  vec3 base = mix(soft, orig, uDetail);",
            "  vec2 e = uBlurTexel * uEdgeWidth;",
            "  float l1 = luma(texture2D(uBlur, uv + vec2(-e.x, -e.y)).rgb);",
            "  float l2 = luma(texture2D(uBlur, uv + vec2(0.0, -e.y)).rgb);",
            "  float l3 = luma(texture2D(uBlur, uv + vec2(e.x, -e.y)).rgb);",
            "  float l4 = luma(texture2D(uBlur, uv + vec2(-e.x, 0.0)).rgb);",
            "  float l5 = luma(texture2D(uBlur, uv + vec2(e.x, 0.0)).rgb);",
            "  float l6 = luma(texture2D(uBlur, uv + vec2(-e.x, e.y)).rgb);",
            "  float l7 = luma(texture2D(uBlur, uv + vec2(0.0, e.y)).rgb);",
            "  float l8 = luma(texture2D(uBlur, uv + vec2(e.x, e.y)).rgb);",
            "  float gx = (l3 + 2.0 * l5 + l8) - (l1 + 2.0 * l4 + l6);",
            "  float gy = (l6 + 2.0 * l7 + l8) - (l1 + 2.0 * l2 + l3);",
            "  float grad = length(vec2(gx, gy));",
            "  float edge = smoothstep(uThresh, uThresh + 0.10, grad);",
            "  float Y = clamp(luma(base) * uBright, 0.0, 1.0);",
            "  vec3 ch = base - vec3(luma(base));",
            "  float I = dot(ch, vec3(0.596, -0.274, -0.322)) * uSat;",
            "  float Q = dot(ch, vec3(0.211, -0.523, 0.312)) * uSat;",
            "  Y = floor(Y * uLevels + 0.5) / uLevels;",
            "  I = floor(I * uChroma + 0.5) / uChroma;",
            "  Q = floor(Q * uChroma + 0.5) / uChroma;",
            "  base = vec3(Y) + vec3(0.956, -0.272, -1.106) * I + vec3(0.621, -0.647, 1.703) * Q;",
            "  base = clamp(base, 0.0, 1.0);",
            "  if (uGray > 0.5) { base = vec3(luma(base)); }",
            "  if (uSketch > 0.5) {",
            "    float d = 1.0 - luma(base);",
            "    d = smoothstep(0.04, 0.92, d);",
            "    base = mix(vec3(0.97, 0.97, 0.95), vec3(0.10, 0.11, 0.14), d * d * 0.9);",
            "  }",
            "  if (uHalftone > 0.001) {",
            "    vec2 p = uv / uTexel;",
            "    float cs = 7.0;",
            "    vec2 cc = (floor(p / cs) + 0.5) * cs;",
            "    float dist = length(p - cc) / cs;",
            "    float r = (1.0 - luma(base)) * 0.66;",
            "    float dotm = smoothstep(r + 0.08, r - 0.08, dist);",
            "    base = mix(base, vec3(0.04), clamp(dotm * uHalftone * 0.8, 0.0, 1.0));",
            "  }",
            "  base *= (1.0 - edge * uEdge);",
            "  if (uNeon > 0.5) {",
            "    float halo = smoothstep(uThresh + 0.12, uThresh + 0.5, grad);",
            "    vec3 glow = clamp(base * 3.0, 0.0, 1.0);",
            "    vec3 ring = mix(vec3(0.15, 0.85, 1.0), vec3(1.0, 0.25, 0.75), smoothstep(0.2, 0.7, luma(base)));",
            "    base = base * 0.26 + glow * 0.10 + ring * (edge * 0.30 + halo * 1.25);",
            "  }",
            "  if (uGrain > 0.001) {",
            "    float n = hash(uv / uTexel + floor(uTime * 12.0));",
            "    base += (n - 0.5) * uGrain * 0.5;",
            "  }",
            "  vec3 outc = mix(orig, clamp(base, 0.0, 1.0), uIntensity);",
            "  outc += vec3(1.0, 0.94, 0.72) * wave * 0.35;",
            "  gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);",
            "}"
    );
}
