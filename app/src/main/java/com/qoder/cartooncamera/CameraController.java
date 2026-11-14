package com.qoder.cartooncamera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    private void createSession(Surface surface, int gen, Listener listener) {
        if (device == null) return;
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            try {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } catch (Throwable ignored) {
                // 部分定焦前摄不支持连续对焦
            }
            try {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            } catch (Throwable ignored) {
            }
            device.createCaptureSession(Collections.singletonList(surface),
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
