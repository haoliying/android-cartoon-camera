package com.qoder.daiguacamera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Camera2 封装：选择尺寸、打开/关闭预览会话。 */
public class CameraController {
    private static final String TAG = "CameraController";

    public interface Listener {
        void onOpened();

        void onError(String message);
    }

    private final CameraManager manager;
    private final HandlerThread thread;
    private final Handler handler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CameraDevice device;
    private CameraCaptureSession session;
    private int generation;

    // 拍照：一个 JPEG ImageReader 常驻会话，按快门时直接拿原图
    private Surface previewSurface;
    private ImageReader stillReader;
    private Surface stillSurface;
    private int stillRotation = 90;
    private volatile Consumer<byte[]> jpegSink;
    private Runnable jpegTimeout;
    private boolean stillRetried;
    private volatile CaptureParams params = new CaptureParams();
    private Range<Integer> isoRange;
    private Range<Long> expRange;
    private Range<Integer> evRange;
    private float evStep = 1f;
    private float maxDiopter = 10f;
    private boolean afOffSupported = true;

    // 闪光灯：自动 / 常开 / 常闭
    public static final int FLASH_AUTO = 0;
    public static final int FLASH_ON = 1;
    public static final int FLASH_OFF = 2;
    private volatile int flashMode = FLASH_AUTO;
    private volatile boolean videoMode = false;
    private volatile boolean flashAvailable = false;
    private volatile boolean flashKnown = false;

    // 数码变焦：用裁切区域实现，兼容到 API 24；API 30+ 优先用 CONTROL_ZOOM_RATIO
    private volatile float minZoom = 1f;
    private volatile float maxZoom = 1f;
    private volatile float zoom = 1f;
    private volatile boolean zoomKnown = false;
    private volatile boolean zoomRatioSupported = false;
    private volatile float ratioMax = 1f;
    /** 当前打开的具体摄像头 ID（后置主摄 / 后置广角可能是不同 ID）。 */
    private volatile String activeCameraId;
    /** 超广角相对主摄的等效倍数，例如 0.55。 */
    private volatile float wideFovRatio = 0.55f;
    private android.graphics.Rect activeArray;
    private final Runnable applyRepeatingRunnable = this::applyRepeating;

    // 点击对焦：AF/AE 测光区域
    private volatile boolean afAutoSupported = false;
    private volatile boolean afRegionsSupported = false;
    private volatile boolean aeRegionsSupported = false;
    private volatile android.hardware.camera2.params.MeteringRectangle focusRect;

    // 自动场景：根据环境亮度和机身动静挑场景模式
    private volatile float motionSpeed;
    private volatile int lastIso;
    private volatile long lastExposureNs;
    private volatile int currentSceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED;
    private long lastSceneSwitch;
    private boolean autoSceneSupported = false;
    private boolean sceneNight = false;
    private boolean sceneAction = false;

    public CameraController(Context ctx) {
        manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        thread = new HandlerThread("camera2");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public String findCameraId(int facing) {
        try {
            String[] ids = manager.getCameraIdList();
            for (String id : ids) {
                Integer f = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) return id;
            }
            if (ids.length > 0) return ids[0];
        } catch (CameraAccessException e) {
            Log.e(TAG, "findCameraId", e);
        }
        return null;
    }

    /**
     * 找后置的超广角镜头：视场角明显大于主摄、且能预览和拍照的独立摄像头 ID。
     * 有些机型把广角作为独立 ID 暴露，系统相机的 0.5× 就是切到这颗。
     */
    public String findWideBackId() {
        try {
            String mainId = findCameraId(CameraCharacteristics.LENS_FACING_BACK);
            if (mainId == null) return null;
            float mainFov = horizontalFov(manager.getCameraCharacteristics(mainId));
            if (mainFov <= 0f) return null;
            String best = null;
            // 超广角至少要比主摄广 15°，且水平视场角普遍在 92° 以上，避免误认微距镜头
            float bestFov = Math.max(mainFov + 15f, 92f);
            float bestRatio = 0.55f;
            for (String id : manager.getCameraIdList()) {
                if (id.equals(mainId)) continue;
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f == null || f != CameraCharacteristics.LENS_FACING_BACK) continue;
                StreamConfigurationMap map =
                        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                if (map.getOutputSizes(ImageFormat.JPEG) == null
                        || map.getOutputSizes(SurfaceTexture.class) == null) continue;
                float fov = horizontalFov(c);
                if (fov > bestFov) {
                    bestFov = fov;
                    best = id;
                    float halfMain = (float) Math.tan(Math.toRadians(mainFov / 2.0));
                    float halfWide = (float) Math.tan(Math.toRadians(fov / 2.0));
                    if (halfWide > 1e-4f) {
                        bestRatio = Math.max(0.15f, Math.min(0.9f, halfMain / halfWide));
                    }
                }
            }
            if (best != null) wideFovRatio = bestRatio;
            return best;
        } catch (Throwable t) {
            Log.w(TAG, "findWideBackId", t);
            return null;
        }
    }

    /** 超广角相对主摄的等效倍数（例如 0.55×），用于广角镜头的倍率换算。 */
    public float wideFactor() {
        return wideFovRatio;
    }

    /** 后置主摄是否有闪光灯；广角镜头常常没有，但手机的 LED 仍可借主摄使用。 */
    public boolean hasFlashOnBack() {
        try {
            String id = findCameraId(CameraCharacteristics.LENS_FACING_BACK);
            if (id == null) return false;
            Boolean f = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            return f != null && f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 传感器水平视场角（度），用来区分主摄和超广角。 */
    private float horizontalFov(CameraCharacteristics c) {
        SizeF size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (size == null || fl == null || fl.length == 0 || fl[0] <= 0f) return 0f;
        return (float) Math.toDegrees(2.0 * Math.atan(size.getWidth() / (2.0 * fl[0])));
    }

    public int getSensorOrientation(int facing) {
        return getSensorOrientation(facing, null);
    }

    public int getSensorOrientation(int facing, String idOverride) {
        try {
            String id = idOverride != null ? idOverride : findCameraId(facing);
            if (id == null) return 90;
            Integer o = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION);
            return o == null ? 90 : o;
        } catch (CameraAccessException e) {
            return 90;
        }
    }

    public Size pickPreviewSize(int facing) {
        return pickPreviewSize(facing, null);
    }

    public Size pickPreviewSize(int facing, String idOverride) {
        try {
            String id = idOverride != null ? idOverride : findCameraId(facing);
            if (id == null) return new Size(1280, 720);
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return new Size(1280, 720);
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) return new Size(1280, 720);

            // 首选接近 720p 的 16:9 尺寸
            Size best = null;
            long bestScore = Long.MAX_VALUE;
            for (Size s : sizes) {
                int longSide = Math.max(s.getWidth(), s.getHeight());
                int shortSide = Math.min(s.getWidth(), s.getHeight());
                if (longSide > 1920) continue;
                if (Math.abs(shortSide * 16 - longSide * 9) > longSide * 9 / 20) continue;
                long score = Math.abs(longSide - 1280) * 10L + Math.abs(shortSide - 720);
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            if (best != null) return best;

            // 兜底：面积最接近 720p 的尺寸
            long target = 1280L * 720L;
            long bestDiff = Long.MAX_VALUE;
            for (Size s : sizes) {
                if (s.getWidth() > 1920 || s.getHeight() > 1920) continue;
                long diff = Math.abs((long) s.getWidth() * s.getHeight() - target);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = s;
                }
            }
            return best != null ? best : new Size(1280, 720);
        } catch (CameraAccessException e) {
            Log.e(TAG, "pickPreviewSize", e);
            return new Size(1280, 720);
        }
    }

    /** 挑一个“够用又不撑爆内存”的 JPEG 尺寸：偏好 4:3、长边 2000 左右。 */
    public Size pickStillSize(int facing) {
        return pickStillSize(facing, null);
    }

    public Size pickStillSize(int facing, String idOverride) {
        try {
            String id = idOverride != null ? idOverride : findCameraId(facing);
            if (id == null) return new Size(1920, 1440);
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return new Size(1920, 1440);
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return new Size(1920, 1440);
            Size best = null;
            Size smallest = null;
            double bestScore = Double.MAX_VALUE;
            for (Size s : sizes) {
                if (smallest == null || Math.max(s.getWidth(), s.getHeight())
                        < Math.max(smallest.getWidth(), smallest.getHeight())) {
                    smallest = s;
                }
                int longSide = Math.max(s.getWidth(), s.getHeight());
                int shortSide = Math.min(s.getWidth(), s.getHeight());
                if (longSide > 2600 || shortSide <= 0) continue;
                double aspect = longSide / (double) shortSide;
                double score = Math.abs(longSide - 2048) * 0.06 + Math.abs(aspect - 4.0 / 3.0) * 900;
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            if (best != null) return best;
            return smallest != null && Math.max(smallest.getWidth(), smallest.getHeight()) <= 4096
                    ? smallest : new Size(1920, 1440);
        } catch (Throwable t) {
            return new Size(1920, 1440);
        }
    }

    /** 手动参数只在支持 MANUAL_SENSOR 的设备（LIMITED / FULL）上生效。 */
    public boolean supportsManual() {
        return isoRange != null && expRange != null;
    }

    public void setParams(CaptureParams p) {
        params = p == null ? new CaptureParams() : p.copy();
        handler.removeCallbacks(applyRepeatingRunnable);
        handler.post(applyRepeatingRunnable);
    }

    /** 切换闪光灯策略；video 为 true 时「常开」用常亮补光（手电筒模式）。 */
    public void setFlash(int mode, boolean video) {
        flashMode = mode;
        videoMode = video;
        handler.removeCallbacks(applyRepeatingRunnable);
        handler.post(applyRepeatingRunnable);
    }

    public boolean hasFlash() {
        return flashAvailable;
    }

    /** 相机参数是否已经读取过，用来区分「没有闪光灯」和「还没打开相机」。 */
    public boolean isFlashKnown() {
        return flashKnown;
    }

    public boolean isZoomKnown() {
        return zoomKnown;
    }

    public float maxZoom() {
        return maxZoom;
    }

    /** 设备支持的最小倍数：支持多摄时可能是 0.5× 一类的广角。 */
    public float minZoom() {
        return minZoom;
    }

    public float getZoom() {
        return zoom;
    }

    /** 超过设备数码变焦上限后，由渲染层继续做软件裁切放大。 */
    public float extraZoom() {
        float hz = halZoom();
        return hz > 0.0001f ? Math.max(1f, zoom / hz) : 1f;
    }

    private float halZoom() {
        return Math.min(maxZoom, Math.max(minZoom, zoom));
    }

    /** 设置变焦倍数；界面量程 0.1×–100×，设备上限之外用软件裁切补足。 */
    public void setZoom(float ratio) {
        float z = clamp(ratio, minZoom, 100f);
        if (Math.abs(z - zoom) < 0.005f) return;
        float oldHal = halZoom();
        zoom = z;
        // 只改变软件放大时不必重建相机请求，拖动控制盘更跟手
        if (Math.abs(halZoom() - oldHal) > 0.001f) {
            handler.removeCallbacks(applyRepeatingRunnable);
            handler.post(applyRepeatingRunnable);
        }
    }

    /** 自动场景用来判断画面里有没有明显运动。 */
    public void setMotionSpeed(float speed) {
        motionSpeed = speed;
    }

    /** 当前数码变焦对应的传感器裁切区域。 */
    public android.graphics.Rect currentCropRect() {
        android.graphics.Rect aa = activeArray;
        if (aa == null) return new android.graphics.Rect(0, 0, 1, 1);
        float z = halZoom();
        if (z <= 1.001f) return new android.graphics.Rect(aa);
        int cw = Math.max(1, Math.round(aa.width() / z));
        int ch = Math.max(1, Math.round(aa.height() / z));
        int left = aa.left + (aa.width() - cw) / 2;
        int top = aa.top + (aa.height() - ch) / 2;
        return new android.graphics.Rect(left, top, left + cw, top + ch);
    }

    /**
     * 点击预览设置对焦点。u/v 为显示坐标（左上角为原点，0–1）。
     * 返回 true 表示相机接受了对焦/测光点。
     */
    public boolean focusAt(float u, float v, boolean mirror) {
        if (activeArray == null) return false;
        if (!afAutoSupported && !afRegionsSupported && !aeRegionsSupported) return false;

        float nx = clamp(u, 0f, 1f);
        float ny = clamp(v, 0f, 1f);
        if (mirror) nx = 1f - nx;

        // 显示坐标 -> 传感器坐标（竖屏锁定，按传感器方向旋转）
        float sx;
        float sy;
        int rot = ((stillRotation % 360) + 360) % 360;
        if (rot == 90) {
            sx = ny;
            sy = 1f - nx;
        } else if (rot == 270) {
            sx = 1f - ny;
            sy = nx;
        } else if (rot == 180) {
            sx = 1f - nx;
            sy = 1f - ny;
        } else {
            sx = nx;
            sy = ny;
        }

        android.graphics.Rect crop = currentCropRect();
        // 超过设备上限的软件裁切也要算进可见范围
        float extra = extraZoom();
        if (extra > 1.001f) {
            int ew = Math.max(1, Math.round(crop.width() / extra));
            int eh = Math.max(1, Math.round(crop.height() / extra));
            int el = crop.left + (crop.width() - ew) / 2;
            int et = crop.top + (crop.height() - eh) / 2;
            crop = new android.graphics.Rect(el, et, el + ew, et + eh);
        }
        int px = crop.left + Math.round(sx * crop.width());
        int py = crop.top + Math.round(sy * crop.height());
        int size = Math.max(1, Math.round(Math.min(crop.width(), crop.height()) * 0.18f));
        int left = Math.max(crop.left, Math.min(crop.right - size, px - size / 2));
        int top = Math.max(crop.top, Math.min(crop.bottom - size, py - size / 2));
        android.graphics.Rect r = new android.graphics.Rect(left, top, left + size, top + size);
        focusRect = new android.hardware.camera2.params.MeteringRectangle(
                r, android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX);
        handler.post(this::triggerFocus);
        return true;
    }

    /** 发一次 AF 触发，然后回到连续对焦；测光区域保留到下一次点击。 */
    private void triggerFocus() {
        if (session == null || device == null || previewSurface == null) return;
        // 正在出图时不去打断本次拍照
        if (jpegSink != null) return;
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            applyParams(b, false);
            if (afAutoSupported) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_START);
                try {
                    session.capture(b.build(), null, handler);
                } catch (Throwable t) {
                    session.setRepeatingRequest(b.build(), previewCb, handler);
                }
                handler.postDelayed(this::applyRepeating, 1200);
            } else {
                session.setRepeatingRequest(b.build(), previewCb, handler);
            }
        } catch (Throwable t) {
            Log.w(TAG, "triggerFocus", t);
            applyRepeating();
        }
    }

    private void loadCharacteristics(int facing) {
        isoRange = null;
        expRange = null;
        evRange = null;
        afOffSupported = true;
        flashAvailable = false;
        flashKnown = false;
        minZoom = 1f;
        maxZoom = 1f;
        zoom = 1f;
        zoomKnown = false;
        zoomRatioSupported = false;
        ratioMax = 1f;
        activeArray = null;
        focusRect = null;
        afAutoSupported = false;
        afRegionsSupported = false;
        aeRegionsSupported = false;
        currentSceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED;
        autoSceneSupported = false;
        sceneNight = false;
        sceneAction = false;
        lastIso = 0;
        lastExposureNs = 0;
        try {
            String id = activeCameraId != null ? activeCameraId : findCameraId(facing);
            if (id == null) return;
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flashAvailable = flash != null && flash;
            flashKnown = true;
            // 传统数码变焦上限，任何设备都有
            float sm = 1f;
            Float mz = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (mz != null && mz > 1f) sm = Math.min(100f, mz);
            // API 30+ 的逻辑多摄变焦比例，可覆盖广角（<1×）与长焦
            float rl = 1f;
            float ru = 1f;
            boolean hasRatio = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null) {
                    hasRatio = true;
                    rl = Math.max(0.1f, zr.getLower());
                    ru = Math.max(rl, Math.min(100f, zr.getUpper()));
                }
            }
            ratioMax = ru;
            // 只有确实能改变视野（<1 广角或 >1 变焦）时才用变焦比例
            zoomRatioSupported = hasRatio && (rl < 0.99f || ru > 1.01f);
            if (zoomRatioSupported) {
                minZoom = Math.min(1f, rl);
                maxZoom = Math.max(ru, sm);
            } else {
                minZoom = 1f;
                maxZoom = Math.max(ru, sm);
            }
            if (maxZoom < minZoom) maxZoom = minZoom;
            zoom = Math.min(maxZoom, Math.max(minZoom, 1f));
            zoomKnown = true;
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float md = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (md != null && md > 0f) maxDiopter = md;
            Rational step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (step != null && step.getDenominator() != 0) {
                evStep = Math.max(0.01f, step.floatValue());
            }
            Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            stillRotation = so == null ? 90 : so;
            afOffSupported = false;
            int[] modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (modes != null) {
                for (int m : modes) {
                    if (m == CaptureRequest.CONTROL_AF_MODE_OFF) afOffSupported = true;
                    if (m == CaptureRequest.CONTROL_AF_MODE_AUTO) afAutoSupported = true;
                }
            }
            Integer maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            afRegionsSupported = maxAf != null && maxAf > 0;
            Integer maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            aeRegionsSupported = maxAe != null && maxAe > 0;
            int[] sceneModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
            if (sceneModes != null) {
                for (int m : sceneModes) {
                    if (m == CaptureRequest.CONTROL_SCENE_MODE_NIGHT) sceneNight = true;
                    if (m == CaptureRequest.CONTROL_SCENE_MODE_ACTION) sceneAction = true;
                }
            }
            autoSceneSupported = sceneNight || sceneAction;
        } catch (Throwable t) {
            Log.w(TAG, "characteristics", t);
        }
    }

    public void open(int facing, Surface surface, Listener listener) {
        open(facing, null, surface, listener);
    }

    /** idOverride 为 null 时按 facing 自动选；传广角 ID 可以打开后置超广角镜头。 */
    public void open(int facing, String idOverride, Surface surface, Listener listener) {
        final int gen = ++generation;
        handler.post(() -> {
            closeInternal();
            try {
                final String id = idOverride != null ? idOverride : findCameraId(facing);
                if (id == null) {
                    postError(listener, "未找到可用的摄像头");
                    return;
                }
                activeCameraId = id;
                loadCharacteristics(facing);
                previewSurface = surface;
                createStillReader(facing);
                manager.openCamera(id, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        if (gen != generation) {
                            camera.close();
                            return;
                        }
                        device = camera;
                        createSession(surface, gen, listener);
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        camera.close();
                        if (device == camera) device = null;
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        camera.close();
                        if (device == camera) device = null;
                        postError(listener, "相机出错（" + error + "）");
                    }
                }, handler);
            } catch (SecurityException e) {
                postError(listener, "没有相机权限");
            } catch (Throwable t) {
                Log.e(TAG, "openCamera", t);
                postError(listener, "无法打开相机：" + t.getMessage());
            }
        });
    }

    private void createStillReader(int facing) {
        if (stillReader != null) {
            try {
                stillReader.close();
            } catch (Throwable ignored) {
            }
            stillReader = null;
            stillSurface = null;
        }
        try {
            Size size = pickStillSize(facing, activeCameraId);
            stillReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.JPEG, 2);
            stillReader.setOnImageAvailableListener(this::onJpegReady, handler);
            stillSurface = stillReader.getSurface();
        } catch (Throwable t) {
            Log.w(TAG, "no still capture", t);
            stillReader = null;
            stillSurface = null;
        }
    }

    private void createSession(Surface surface, int gen, Listener listener) {
        if (device == null) return;
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            applyParams(builder, false);
            List<Surface> outputs = new ArrayList<>();
            outputs.add(surface);
            if (stillSurface != null) outputs.add(stillSurface);
            device.createCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (gen != generation) {
                                s.close();
                                return;
                            }
                            session = s;
                            try {
                                s.setRepeatingRequest(builder.build(), previewCb, handler);
                                mainHandler.post(listener::onOpened);
                            } catch (Throwable t) {
                                postError(listener, "预览启动失败：" + t.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            postError(listener, "无法创建预览会话");
                        }
                    }, handler);
        } catch (Throwable t) {
            Log.e(TAG, "createSession", t);
            postError(listener, "无法创建预览：" + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ 拍照

    /** 按快门：停下预览连拍 -> 发一张 STILL_CAPTURE -> JPEG 通过回调返回。 */
    public void captureStill(Consumer<byte[]> onJpeg, Consumer<String> onError) {
        handler.post(() -> {
            if (session == null || device == null || stillSurface == null) {
                mainHandler.post(() -> onError.accept("相机还没准备好，稍后再试"));
                return;
            }
            if (jpegSink != null) {
                mainHandler.post(() -> onError.accept("上一张还没拍完，请稍候"));
                return;
            }
            jpegSink = onJpeg;
            stillRetried = false;
            startJpegTimeout(onError);
            try {
                session.stopRepeating();
                CaptureRequest.Builder b = device.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(stillSurface);
                applyParams(b, true);
                try {
                    b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                } catch (Throwable ignored) {
                }
                try {
                    b.set(CaptureRequest.JPEG_ORIENTATION, stillRotation);
                } catch (Throwable ignored) {
                }
                session.capture(b.build(), new Cb(onError, false), handler);
            } catch (Throwable t) {
                // 某些机型会拒绝手动参数组合，直接退到全自动再拍一次
                Log.w(TAG, "captureStill rejected, retry auto", t);
                retryStillAuto(onError);
            }
        });
    }

    private void startJpegTimeout(Consumer<String> onError) {
        if (jpegTimeout != null) handler.removeCallbacks(jpegTimeout);
        jpegTimeout = () -> {
            if (!stillRetried && jpegSink != null) {
                Log.w(TAG, "still capture timeout, retry auto");
                retryStillAuto(onError);
                return;
            }
            Consumer<byte[]> sink = jpegSink;
            jpegSink = null;
            if (sink != null) mainHandler.post(() -> onError.accept("拍照超时，请重试"));
            applyRepeating();
        };
        handler.postDelayed(jpegTimeout, 12_000);
    }

    /** 手动参数被设备拒绝时的兜底：全自动、无手动项，保证能出图。 */
    private void retryStillAuto(Consumer<String> onError) {
        handler.post(() -> {
            if (jpegSink == null) return;
            stillRetried = true;
            startJpegTimeout(onError);
            try {
                if (session == null || device == null || stillSurface == null) {
                    throw new IllegalStateException("相机未就绪");
                }
                session.stopRepeating();
                CaptureRequest.Builder b = device.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(stillSurface);
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                applyFlashAndZoom(b, true);
                try {
                    b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                    b.set(CaptureRequest.JPEG_ORIENTATION, stillRotation);
                } catch (Throwable ignored) {
                }
                session.capture(b.build(), new Cb(onError, true), handler);
            } catch (Throwable t) {
                Log.e(TAG, "retryStillAuto", t);
                failStill(onError, "拍照失败：" + t.getMessage());
            }
        });
    }

    private void failStill(Consumer<String> onError, String message) {
        handler.post(() -> {
            Consumer<byte[]> sink = jpegSink;
            jpegSink = null;
            if (jpegTimeout != null) {
                handler.removeCallbacks(jpegTimeout);
                jpegTimeout = null;
            }
            if (sink != null) mainHandler.post(() -> onError.accept(message));
            applyRepeating();
        });
    }

    private final class Cb extends CameraCaptureSession.CaptureCallback {
        private final Consumer<String> onError;
        private final boolean auto;

        Cb(Consumer<String> onError, boolean auto) {
            this.onError = onError;
            this.auto = auto;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                       TotalCaptureResult result) {
            handler.post(() -> {
                if (jpegSink == null && jpegTimeout != null) {
                    handler.removeCallbacks(jpegTimeout);
                    jpegTimeout = null;
                }
                applyRepeating();
            });
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request,
                                    CaptureFailure failure) {
            Log.w(TAG, "still capture failed, reason=" + failure.getReason() + " auto=" + auto);
            if (!auto) {
                retryStillAuto(onError);
            } else {
                failStill(onError, "拍照失败，请重试");
            }
        }
    }

    private void onJpegReady(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireNextImage();
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            Log.i(TAG, "jpeg received " + data.length + " bytes");
            Consumer<byte[]> sink = jpegSink;
            jpegSink = null;
            if (jpegTimeout != null) {
                handler.removeCallbacks(jpegTimeout);
                jpegTimeout = null;
            }
            if (sink != null) mainHandler.post(() -> sink.accept(data));
        } catch (Throwable t) {
            Log.e(TAG, "jpeg", t);
        } finally {
            if (img != null) {
                try {
                    img.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 用当前参数重建预览请求（切模式、改参数后调用）。 */
    private void applyRepeating() {
        if (session == null || device == null || previewSurface == null) return;
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            applyParams(b, false);
            session.setRepeatingRequest(b.build(), previewCb, handler);
        } catch (Throwable t) {
            Log.w(TAG, "applyRepeating", t);
        }
    }

    /** 预览结果回调：记录亮度，并在自动场景下按需切换场景模式。 */
    private final CameraCaptureSession.CaptureCallback previewCb =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                               TotalCaptureResult result) {
                    if (result == null) return;
                    try {
                        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                        Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        if (iso != null) lastIso = iso;
                        if (exp != null) lastExposureNs = exp;
                    } catch (Throwable ignored) {
                    }
                    if (!params.autoScene) return;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastSceneSwitch < 1200) return;
                    int want = pickSceneMode();
                    if (want != currentSceneMode) {
                        lastSceneSwitch = now;
                        handler.post(CameraController.this::applyRepeating);
                    }
                }
            };

    /** 暗光优先夜景，明显晃动优先运动，其余交给普通自动。 */
    private int pickSceneMode() {
        if (!autoSceneSupported) return CaptureRequest.CONTROL_SCENE_MODE_DISABLED;
        boolean night = lastIso >= 1200 || lastExposureNs >= 80_000_000L;
        boolean action = motionSpeed >= 3.5f;
        if (night && sceneNight) return CaptureRequest.CONTROL_SCENE_MODE_NIGHT;
        if (action && sceneAction) return CaptureRequest.CONTROL_SCENE_MODE_ACTION;
        return CaptureRequest.CONTROL_SCENE_MODE_DISABLED;
    }

    private void applyParams(CaptureRequest.Builder b, boolean forStill) {
        CaptureParams p = params;
        // 自动场景：按亮度与动静让相机切换场景模式（夜景 / 运动 / 普通）
        try {
            int mode = p.autoScene ? pickSceneMode()
                    : CaptureRequest.CONTROL_SCENE_MODE_DISABLED;
            currentSceneMode = mode;
            b.set(CaptureRequest.CONTROL_SCENE_MODE, mode);
        } catch (Throwable t) {
            try {
                b.set(CaptureRequest.CONTROL_SCENE_MODE,
                        CaptureRequest.CONTROL_SCENE_MODE_DISABLED);
            } catch (Throwable ignored) {
            }
        }
        // 曝光（拍照时按闪光灯策略选择 AE 闪光模式）
        try {
            boolean stillFlash = forStill && flashAvailable;
            int aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
            if (p.manualExposure) {
                aeMode = CaptureRequest.CONTROL_AE_MODE_OFF;
            } else if (stillFlash && flashMode == FLASH_ON) {
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            } else if (stillFlash && flashMode == FLASH_AUTO) {
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            if (p.manualExposure) {
                b.set(CaptureRequest.SENSOR_SENSITIVITY, clampIso(p.iso));
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposure(p.exposureNs, forStill));
            } else {
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampEv(p.ev));
            }
        } catch (Throwable t) {
            try {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            } catch (Throwable ignored) {
            }
        }
        // 白平衡
        try {
            if (p.wbAuto) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            } else {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                b.set(CaptureRequest.COLOR_CORRECTION_MODE,
                        CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, gainsForKelvin(p.kelvin));
            }
        } catch (Throwable t) {
            try {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            } catch (Throwable ignored) {
            }
        }
        // 对焦
        try {
            if (afOffSupported && p.focusMode != CaptureParams.FOCUS_AUTO) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance(p));
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
        } catch (Throwable t) {
            try {
                b.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } catch (Throwable ignored) {
            }
        }
        // 点击对焦：把 AF/AE 的测光权重放到用户点的位置
        android.hardware.camera2.params.MeteringRectangle mr = focusRect;
        if (mr != null) {
            if (afRegionsSupported) {
                try {
                    b.set(CaptureRequest.CONTROL_AF_REGIONS,
                            new android.hardware.camera2.params.MeteringRectangle[]{mr});
                } catch (Throwable ignored) {
                }
            }
            if (aeRegionsSupported) {
                try {
                    b.set(CaptureRequest.CONTROL_AE_REGIONS,
                            new android.hardware.camera2.params.MeteringRectangle[]{mr});
                } catch (Throwable ignored) {
                }
            }
        }
        applyFlashAndZoom(b, forStill);
    }

    /** 闪光灯与数码变焦：两者都用同一个请求重建，预览/录像/拍照保持一致。 */
    private void applyFlashAndZoom(CaptureRequest.Builder b, boolean forStill) {
        try {
            int fm = CaptureRequest.FLASH_MODE_OFF;
            if (flashAvailable && flashMode != FLASH_OFF) {
                if (forStill) {
                    // 手动曝光时「自动」没有测光依据，只有「常开」才闪
                    fm = (flashMode == FLASH_ON || !params.manualExposure)
                            ? CaptureRequest.FLASH_MODE_SINGLE
                            : CaptureRequest.FLASH_MODE_OFF;
                } else if (videoMode && flashMode == FLASH_ON) {
                    // 录像「常开」= 手电筒常亮；「自动」在连续录像里没有意义，保持关闭
                    fm = CaptureRequest.FLASH_MODE_TORCH;
                }
            }
            b.set(CaptureRequest.FLASH_MODE, fm);
        } catch (Throwable ignored) {
        }
        try {
            float hz = halZoom();
            // 变焦比例优先用于广角和比例范围内的变焦；超出比例范围再用传统裁切
            boolean useRatio = zoomRatioSupported
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && (hz < 0.999f || hz <= ratioMax + 0.001f);
            if (useRatio) {
                try {
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, hz);
                } catch (Throwable t) {
                    applyCropZoom(b, hz);
                }
            } else {
                applyCropZoom(b, hz);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 老设备用 SCALER_CROP_REGION 做数码变焦。 */
    private void applyCropZoom(CaptureRequest.Builder b, float z) {
        if (activeArray == null || z <= 1.001f) return;
        try {
            int cw = Math.max(1, Math.round(activeArray.width() / z));
            int ch = Math.max(1, Math.round(activeArray.height() / z));
            int left = activeArray.left + (activeArray.width() - cw) / 2;
            int top = activeArray.top + (activeArray.height() - ch) / 2;
            b.set(CaptureRequest.SCALER_CROP_REGION,
                    new android.graphics.Rect(left, top, left + cw, top + ch));
        } catch (Throwable ignored) {
        }
    }

    private float focusDistance(CaptureParams p) {
        switch (p.focusMode) {
            case CaptureParams.FOCUS_INFINITY:
                return 0f;
            case CaptureParams.FOCUS_MACRO:
                return clamp(p.diopter <= 0f ? Math.min(8f, maxDiopter) : p.diopter,
                        0f, maxDiopter);
            case CaptureParams.FOCUS_MANUAL:
                return clamp(p.diopter, 0f, maxDiopter);
            default:
                return 0f;
        }
    }

    private int clampIso(int iso) {
        if (isoRange == null) return iso;
        return Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso));
    }

    private long clampExposure(long ns, boolean forStill) {
        long v = ns;
        if (!forStill) v = Math.min(v, 100_000_000L);   // 预览最长 1/10 秒，避免卡死
        if (expRange != null) {
            v = Math.max(expRange.getLower(), Math.min(expRange.getUpper(), v));
        }
        return Math.max(1_000L, v);
    }

    /** 把 EV 换算成设备支持的补偿档位（多数机型是 1/3 或 1/10 档）。 */
    private int clampEv(float ev) {
        if (evRange == null) return 0;
        int i = Math.round(ev / evStep);
        return Math.max(evRange.getLower(), Math.min(evRange.getUpper(), i));
    }

    /**
     * 用黑体色温近似估算白平衡增益（RGGB）。设备不支持手动增益时会抛错，调用方兜底。
     */
    private static RggbChannelVector gainsForKelvin(int kelvin) {
        double t = Math.max(1900, Math.min(10000, kelvin)) / 100.0;
        double r;
        double g;
        double bl;
        if (t <= 66) {
            r = 255;
            g = 99.4708025861 * Math.log(t) - 161.1195681661;
        } else {
            r = 329.698727446 * Math.pow(t - 60, -0.1332047592);
            g = 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        }
        if (t >= 66) {
            bl = 255;
        } else if (t <= 19) {
            bl = 0;
        } else {
            bl = 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        }
        r = Math.max(1, Math.min(255, r)) / 255.0;
        g = Math.max(1, Math.min(255, g)) / 255.0;
        bl = Math.max(1, Math.min(255, bl)) / 255.0;
        float rg = (float) Math.max(0.2, Math.min(6.0, g / r));
        float bg = (float) Math.max(0.2, Math.min(6.0, g / bl));
        return new RggbChannelVector(rg, 1f, 1f, bg);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public void close() {
        ++generation;
        handler.post(this::closeInternal);
    }

    /** 阻塞等待相机完全关闭（在 Surface 销毁前调用）。 */
    public void closeAndWait() {
        ++generation;
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> {
            try {
                closeInternal();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(800, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeInternal() {
        jpegSink = null;
        if (stillReader != null) {
            try {
                stillReader.close();
            } catch (Throwable ignored) {
            }
            stillReader = null;
            stillSurface = null;
        }
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
            session = null;
        }
        if (device != null) {
            try {
                device.close();
            } catch (Throwable ignored) {
            }
            device = null;
        }
    }

    public void shutdown() {
        closeAndWait();
        thread.quitSafely();
    }

    private void postError(Listener listener, String message) {
        if (listener != null) mainHandler.post(() -> listener.onError(message));
    }
}
