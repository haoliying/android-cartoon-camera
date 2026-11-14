package com.qoder.cartooncamera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
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
    private volatile CaptureParams params = new CaptureParams();
    private Range<Integer> isoRange;
    private Range<Long> expRange;
    private Range<Integer> evRange;
    private boolean afOffSupported = true;

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

    public int getSensorOrientation(int facing) {
        try {
            String id = findCameraId(facing);
            if (id == null) return 90;
            Integer o = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION);
            return o == null ? 90 : o;
        } catch (CameraAccessException e) {
            return 90;
        }
    }

    public Size pickPreviewSize(int facing) {
        try {
            String id = findCameraId(facing);
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
        try {
            String id = findCameraId(facing);
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
        handler.post(this::applyRepeating);
    }

    private void loadCharacteristics(int facing) {
        isoRange = null;
        expRange = null;
        evRange = null;
        afOffSupported = true;
        try {
            String id = findCameraId(facing);
            if (id == null) return;
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            stillRotation = so == null ? 90 : so;
            afOffSupported = false;
            int[] modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (modes != null) {
                for (int m : modes) {
                    if (m == CaptureRequest.CONTROL_AF_MODE_OFF) afOffSupported = true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "characteristics", t);
        }
    }

    public void open(int facing, Surface surface, Listener listener) {
        final int gen = ++generation;
        handler.post(() -> {
            closeInternal();
            try {
                final String id = findCameraId(facing);
                if (id == null) {
                    postError(listener, "未找到可用的摄像头");
                    return;
                }
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
            Size size = pickStillSize(facing);
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
                                s.setRepeatingRequest(builder.build(), null, handler);
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
            try {
                jpegSink = onJpeg;
                if (jpegTimeout != null) handler.removeCallbacks(jpegTimeout);
                jpegTimeout = () -> {
                    Consumer<byte[]> sink = jpegSink;
                    jpegSink = null;
                    if (sink != null) mainHandler.post(() -> onError.accept("拍照超时，请重试"));
                    applyRepeating();
                };
                handler.postDelayed(jpegTimeout, 12_000);

                session.stopRepeating();
                CaptureRequest.Builder b = device.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(stillSurface);
                if (previewSurface != null) b.addTarget(previewSurface);
                applyParams(b, true);
                try {
                    b.set(CaptureRequest.JPEG_ORIENTATION, stillRotation);
                } catch (Throwable ignored) {
                }
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
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
                }, handler);
            } catch (Throwable t) {
                Log.e(TAG, "captureStill", t);
                jpegSink = null;
                if (jpegTimeout != null) handler.removeCallbacks(jpegTimeout);
                jpegTimeout = null;
                mainHandler.post(() -> onError.accept("拍照失败：" + t.getMessage()));
                applyRepeating();
            }
        });
    }

    private void onJpegReady(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireNextImage();
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
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
            session.setRepeatingRequest(b.build(), null, handler);
        } catch (Throwable t) {
            Log.w(TAG, "applyRepeating", t);
        }
    }

    private void applyParams(CaptureRequest.Builder b, boolean forStill) {
        CaptureParams p = params;
        // 曝光
        try {
            if (p.manualExposure) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, clampIso(p.iso));
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposure(p.exposureNs, forStill));
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
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
    }

    private float focusDistance(CaptureParams p) {
        switch (p.focusMode) {
            case CaptureParams.FOCUS_INFINITY:
                return 0f;
            case CaptureParams.FOCUS_MACRO:
                return clamp(p.diopter <= 0f ? 8f : p.diopter, 0f, 10f);
            case CaptureParams.FOCUS_MANUAL:
                return clamp(p.diopter, 0f, 10f);
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

    private int clampEv(float ev) {
        if (evRange == null) return Math.round(ev);
        float step = 1f;
        int i = Math.round(ev);
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
