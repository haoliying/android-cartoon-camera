package com.qoder.daiguacamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 相册图片 / 视频的离线卡通化：
 * 自己建一个离屏 EGL 上下文，用与相机完全相同的着色器管线逐帧处理，
 * 图片读回成 Bitmap 存进相册，视频解码到 SurfaceTexture、处理后直接送编码器写成 MP4。
 */
public class GalleryProcessor {
    private static final String TAG = "GalleryProcessor";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final int MAX_IMAGE_SIDE = 2048;
    private static final int MAX_OUTPUT_SIDE = 1600;
    private static final int MAX_VIDEO_SIDE = 1280;

    public interface Listener {
        void onProgress(float progress, String label);

        void onDone(String message, Bitmap preview);

        void onError(String message);
    }

    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("已取消");
        }
    }

    private GalleryProcessor() {
    }

    // ------------------------------------------------------------------ 入口

    /** 同步处理（调用方放在后台线程）。mime 可为空。 */
    public static void process(Context ctx, Uri uri, String mime, StylePreset style,
                               float intensity, boolean sprites, AtomicBoolean cancel,
                               Listener l) {
        try {
            if (mime == null) {
                try {
                    mime = ctx.getContentResolver().getType(uri);
                } catch (Throwable ignored) {
                }
            }
            if (mime == null) {
                String n = uri.getLastPathSegment();
                if (n != null) {
                    n = n.toLowerCase(java.util.Locale.US);
                    if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".m4v")
                            || n.endsWith(".3gp") || n.endsWith(".mkv") || n.endsWith(".webm")
                            || n.endsWith(".avi")) {
                        mime = "video/*";
                    }
                }
            }
            if (mime != null && mime.startsWith("video/")) {
                doVideo(ctx, uri, style, intensity, sprites, cancel, l);
            } else {
                doImage(ctx, uri, style, intensity, sprites, cancel, l);
            }
        } catch (Cancelled c) {
            l.onError("已取消");
        } catch (Throwable t) {
            Log.e(TAG, "process failed", t);
            String m = t.getMessage();
            l.onError(m == null || m.isEmpty() ? "处理失败" : m);
        }
    }

    /** 相机刚拍的 JPEG（原始传感器图像），同样走这条离线管线。 */
    public static void processJpegBytes(Context ctx, byte[] jpeg, StylePreset style,
                                        float intensity, boolean sprites, AtomicBoolean cancel,
                                        Listener l) {
        try {
            runJpeg(ctx, jpeg, style, intensity, sprites, cancel, l,
                    MAX_IMAGE_SIDE, MAX_OUTPUT_SIDE, true);
            return;
        } catch (Cancelled c) {
            l.onError("已取消");
            return;
        } catch (Throwable t) {
            Log.e(TAG, "photo failed, retrying smaller", t);
        }
        // 兜底：原图数据特殊、显存/内存吃紧或小精灵层出错时，降一档重来（这次不带小精灵）
        try {
            runJpeg(ctx, jpeg, style, intensity, false, cancel, l,
                    MAX_IMAGE_SIDE / 2, MAX_OUTPUT_SIDE / 2, false);
        } catch (Cancelled c) {
            l.onError("已取消");
        } catch (Throwable t) {
            Log.e(TAG, "photo failed again", t);
            String m = t.getMessage();
            l.onError(m == null || m.isEmpty() ? "照片卡通化失败" : m);
        }
    }

    private static void runJpeg(Context ctx, byte[] jpeg, StylePreset style, float intensity,
                                boolean sprites, AtomicBoolean cancel, Listener l,
                                int maxSide, int maxOut, boolean checkBlack) {
        l.onProgress(0.05f, "读取照片…");
        Bitmap src = decodeJpeg(jpeg, maxSide);
        Bitmap out = null;
        try {
            if (cancel.get()) throw new Cancelled();
            l.onProgress(0.3f, "卡通化…");
            out = renderImage(src, style, intensity, sprites, maxOut);
            if (cancel.get()) throw new Cancelled();
            if (checkBlack && isBlack(out)) {
                throw new IllegalStateException("渲染结果是黑屏，正在降级重试");
            }
            l.onProgress(0.85f, "保存到相册…");
            String where = saveBitmap(ctx, out);
            l.onProgress(1f, "完成");
            l.onDone(where, out);
            out = null;     // 交给界面显示预览
        } finally {
            src.recycle();
            if (out != null) out.recycle();
        }
    }

    /** 防止空渲染：整张几乎全黑时视为失败，交给上层降级重试。 */
    private static boolean isBlack(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int sx = Math.max(1, w / 8);
        int sy = Math.max(1, h / 8);
        for (int y = sy / 2; y < h; y += sy) {
            for (int x = sx / 2; x < w; x += sx) {
                if ((b.getPixel(x, y) & 0xFFFFFF) != 0) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 图片

    private static void doImage(Context ctx, Uri uri, StylePreset style, float intensity,
                                boolean sprites, AtomicBoolean cancel, Listener l) {
        l.onProgress(0.05f, "读取图片…");
        Bitmap src = decodeImage(ctx.getContentResolver(), uri);
        if (cancel.get()) throw new Cancelled();
        l.onProgress(0.25f, "卡通化…");
        Bitmap out = renderImage(src, style, intensity, sprites);
        src.recycle();
        if (cancel.get()) throw new Cancelled();
        l.onProgress(0.85f, "保存到相册…");
        String where = saveBitmap(ctx, out);
        l.onProgress(1f, "完成");
        l.onDone(where, out);
    }

    private static Bitmap decodeJpeg(byte[] jpeg) {
        return decodeJpeg(jpeg, MAX_IMAGE_SIDE);
    }

    private static Bitmap decodeJpeg(byte[] jpeg, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IllegalStateException("无法读取这张照片");
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
            if (bmp == null) throw new IllegalStateException("无法读取这张照片");
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                orientation = new ExifInterface(new ByteArrayInputStream(jpeg))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL);
            } catch (Throwable ignored) {
            }
            return applyExif(orientation, bmp);
        } catch (Throwable t) {
            Log.e(TAG, "decodeJpeg", t);
            throw new IllegalStateException(t.getMessage() == null ? "无法读取这张照片" : t.getMessage());
        }
    }

    private static Bitmap decodeImage(ContentResolver cr, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IllegalStateException("无法读取这张图片");
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp;
            try (InputStream in = cr.openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opt);
            }
            if (bmp == null) throw new IllegalStateException("无法读取这张图片");
            return applyExif(cr, uri, bmp);
        } catch (Throwable t) {
            Log.e(TAG, "decodeImage", t);
            throw new IllegalStateException(t.getMessage() == null ? "无法读取这张图片" : t.getMessage());
        }
    }

    private static Bitmap applyExif(ContentResolver cr, Uri uri, Bitmap bmp) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Throwable ignored) {
        }
        return applyExif(orientation, bmp);
    }

    private static Bitmap applyExif(int orientation, Bitmap bmp) {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return bmp;
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return bmp;
        }
        try {
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        } catch (Throwable t) {
            return bmp;
        }
    }

    private static Bitmap renderImage(Bitmap src, StylePreset style, float intensity,
                                     boolean spritesOn) {
        return renderImage(src, style, intensity, spritesOn, MAX_OUTPUT_SIDE);
    }

    private static Bitmap renderImage(Bitmap src, StylePreset style, float intensity,
                                     boolean spritesOn, int maxOut) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        float scale = Math.min(1f, maxOut / (float) Math.max(sw, sh));
        int ow = Math.max(2, Math.round(sw * scale));
        int oh = Math.max(2, Math.round(sh * scale));
        OfflineGl gl = new OfflineGl();
        try {
            gl.init();
            return gl.renderImage(src, ow, oh, style, intensity, spritesOn);
        } finally {
            gl.release();
        }
    }

    // ------------------------------------------------------------------ 视频

    /** 正常模式且不开精灵时，视频原样复制到相册，不重新编码（仅限 mp4）。 */
    private static boolean isMp4(Context ctx, Uri uri) {
        try {
            String mime = ctx.getContentResolver().getType(uri);
            if (mime != null) return "video/mp4".equalsIgnoreCase(mime);
        } catch (Throwable ignored) {
        }
        String n = uri.getLastPathSegment();
        return n == null || n.toLowerCase(java.util.Locale.US).endsWith(".mp4");
    }

    private static void doVideoCopy(Context ctx, Uri uri, AtomicBoolean cancel, Listener l) {
        File tmp = new File(ctx.getCacheDir(), "normal_" + System.currentTimeMillis() + ".mp4");
        try {
            l.onProgress(0.05f, "读取视频…");
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new IllegalStateException("无法读取这个视频");
            try (InputStream src = in; OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[256 * 1024];
                long copied = 0;
                int n;
                while ((n = src.read(buf)) > 0) {
                    if (cancel.get()) throw new Cancelled();
                    out.write(buf, 0, n);
                    copied += n;
                    l.onProgress(0.5f, "复制中… " + (copied / (1024 * 1024)) + " MB");
                }
            }
            l.onProgress(0.9f, "写入相册…");
            String where = saveVideoFile(ctx, tmp, "Daigua_" + timestamp() + ".mp4");
            l.onProgress(1f, "完成");
            l.onDone(where, null);
        } catch (Cancelled c) {
            throw c;
        } catch (Throwable t) {
            Log.e(TAG, "doVideoCopy", t);
            String m = t.getMessage();
            throw new IllegalStateException(m == null || m.isEmpty() ? "视频保存失败" : m);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void doVideo(Context ctx, Uri uri, StylePreset style, float intensity,
                                boolean spritesOn, AtomicBoolean cancel, Listener l) {
        if (style.normal && !spritesOn && isMp4(ctx, uri)) {
            doVideoCopy(ctx, uri, cancel, l);
            return;
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaExtractor audioExtractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        OfflineGl gl = null;
        File tmp = null;
        try {
            extractor.setDataSource(ctx, uri, null);
            int videoTrack = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    videoFormat = f;
                    break;
                }
            }
            if (videoTrack < 0 || videoFormat == null) {
                throw new IllegalStateException("这个文件里没有视频轨道");
            }
            extractor.selectTrack(videoTrack);
            String videoMime = videoFormat.getString(MediaFormat.KEY_MIME);
            int srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            int srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if (srcW <= 0 || srcH <= 0) throw new IllegalStateException("视频尺寸无效");
            int rotation = 0;
            try {
                if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION);
                }
            } catch (Throwable ignored) {
            }
            long durationUs = 0;
            try {
                durationUs = videoFormat.getLong(MediaFormat.KEY_DURATION);
            } catch (Throwable ignored) {
            }

            float scale = Math.min(1f, MAX_VIDEO_SIDE / (float) Math.max(srcW, srcH));
            int outW = Math.max(2, ((int) (srcW * scale)) & ~1);
            int outH = Math.max(2, ((int) (srcH * scale)) & ~1);

            tmp = new File(ctx.getCacheDir(), "cartoon_" + System.currentTimeMillis() + ".mp4");
            muxer = new MediaMuxer(tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) {
                try {
                    muxer.setOrientationHint(rotation);
                } catch (Throwable ignored) {
                }
            }

            // 音轨：原样搬过去
            int audioTrackIndex = -1;
            audioExtractor = new MediaExtractor();
            try {
                audioExtractor.setDataSource(ctx, uri, null);
                for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                    MediaFormat f = audioExtractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioExtractor.selectTrack(i);
                        audioTrackIndex = muxer.addTrack(f);
                        break;
                    }
                }
                if (audioTrackIndex < 0) {
                    audioExtractor.release();
                    audioExtractor = null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "audio track skipped", t);
                if (audioExtractor != null) {
                    try {
                        audioExtractor.release();
                    } catch (Throwable ignored) {
                    }
                    audioExtractor = null;
                }
                audioTrackIndex = -1;
            }

            MuxerTrackHolder tracks = new MuxerTrackHolder();
            tracks.audioTrack = audioTrackIndex;
            tracks.audioReady = audioTrackIndex >= 0;

            decoder = MediaCodec.createDecoderByType(videoMime);
            encoder = MediaCodec.createEncoderByType("video/avc");

            gl = new OfflineGl();
            gl.init();
            SurfaceTextureHolder holder = gl.prepareVideo();
            decoder.configure(videoFormat, holder.surface, null, 0);

            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", outW, outH);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            int fps = 30;
            long bitrate = Math.min(24_000_000L,
                    Math.max(4_000_000L, (long) outW * outH * fps / 6));
            ef.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface encSurface = encoder.createInputSurface();
            gl.makeEncoderSurface(encSurface);
            encoder.start();
            decoder.start();

            l.onProgress(0.01f, "解码并卡通化…");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean decoderEos = false;
            boolean encoderDone = false;
            long lastPts = 0;
            int frames = 0;
            long lastReport = 0;
            float[] texMatrix = new float[16];

            while (!encoderDone) {
                if (cancel.get()) throw new Cancelled();
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIdx, 0, size, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size != 0) {
                        decoder.releaseOutputBuffer(outIdx, true);
                        if (holder.awaitFrame(2000)) {
                            holder.texture.updateTexImage();
                            holder.texture.getTransformMatrix(texMatrix);
                            long ptsUs = Math.max(lastPts, info.presentationTimeUs);
                            float frameDt = lastPts > 0 ? (ptsUs - lastPts) / 1e6f : 1f / 30f;
                            if (frameDt <= 0f || frameDt > 0.2f) frameDt = 1f / 30f;
                            lastPts = ptsUs;
                            gl.renderVideoFrame(outW, outH, srcW, srcH, texMatrix, style, intensity,
                                    ptsUs * 1000L, spritesOn, frameDt);
                            if (audioTrackIndex >= 0 && audioExtractor != null) {
                                try {
                                    copyAudioUpTo(audioExtractor, muxer, audioTrackIndex,
                                            tracks.started, ptsUs);
                                } catch (Throwable t) {
                                    Log.w(TAG, "audio copy stopped", t);
                                    audioExtractor.release();
                                    audioExtractor = null;
                                }
                            }
                            frames++;
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastReport > 120) {
                                lastReport = now;
                                float p = durationUs > 0
                                        ? Math.min(0.99f, 0.02f + 0.96f * ptsUs / (float) durationUs)
                                        : Math.min(0.95f, 0.02f + frames / 900f);
                                l.onProgress(p, "已处理 " + frames + " 帧");
                            }
                        }
                    } else {
                        decoder.releaseOutputBuffer(outIdx, false);
                    }
                    if (eos) decoderEos = true;
                }

                if (decoderEos || inputDone) {
                    encoderDone = drainEncoder(encoder, muxer, tracks, decoderEos);
                }
            }

            // 剩余音频
            if (audioTrackIndex >= 0 && audioExtractor != null && tracks.started) {
                try {
                    copyAudioUpTo(audioExtractor, muxer, audioTrackIndex, true, Long.MAX_VALUE);
                } catch (Throwable t) {
                    Log.w(TAG, "audio flush failed", t);
                }
            }

            if (tracks.started) muxer.stop();
            muxer.release();
            muxer = null;

            l.onProgress(0.98f, "写入相册…");
            String where = saveVideoFile(ctx, tmp, "Daigua_" + timestamp() + ".mp4");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            tmp = null;
            l.onProgress(1f, "完成");
            l.onDone(where, null);
        } catch (Cancelled c) {
            throw c;
        } catch (Throwable t) {
            Log.e(TAG, "doVideo", t);
            String m = t.getMessage();
            throw new IllegalStateException(m == null || m.isEmpty() ? "视频处理失败" : m);
        } finally {
            if (gl != null) {
                try {
                    gl.release();
                } catch (Throwable ignored) {
                }
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Throwable ignored) {
                }
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Throwable ignored) {
                }
                try {
                    decoder.release();
                } catch (Throwable ignored) {
                }
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Throwable ignored) {
                }
                try {
                    encoder.release();
                } catch (Throwable ignored) {
                }
            }
            if (audioExtractor != null) {
                try {
                    audioExtractor.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
            if (tmp != null && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** 音轨搬运：把时间戳不超过 ptsUs 的音频样本写进 muxer。 */
    private static void copyAudioUpTo(MediaExtractor ex, MediaMuxer muxer, int track,
                                      boolean muxerStarted, long ptsUs) {
        if (!muxerStarted) return;
        ByteBuffer buf = ByteBuffer.allocateDirect(256 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            long sampleTime = ex.getSampleTime();
            if (sampleTime < 0 || sampleTime > ptsUs) return;
            int size = ex.readSampleData(buf, 0);
            if (size < 0) return;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = sampleTime;
            info.flags = ex.getSampleFlags();
            buf.position(0);
            buf.limit(size);
            muxer.writeSampleData(track, buf, info);
            ex.advance();
        }
    }

    /** 编码器输出搬运。 */
    private static boolean drainEncoder(MediaCodec encoder, MediaMuxer muxer,
                                        MuxerTrackHolder holder, boolean endOfStream) {
        if (endOfStream && !holder.eosSignaled) {
            holder.eosSignaled = true;
            try {
                encoder.signalEndOfInputStream();
            } catch (Throwable ignoredEx) {
            }
        }
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        int guard = 0;
        while (guard++ < 60) {
            int idx = encoder.dequeueOutputBuffer(bi, endOfStream ? 10_000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!holder.started && holder.videoTrack < 0) {
                    holder.videoTrack = muxer.addTrack(encoder.getOutputFormat());
                    if (holder.audioReady || holder.audioTrack < 0) {
                        muxer.start();
                        holder.started = true;
                    }
                }
            } else if (idx >= 0) {
                ByteBuffer out = encoder.getOutputBuffer(idx);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bi.size = 0;
                }
                if (out != null && bi.size > 0) {
                    if (holder.started) {
                        out.position(bi.offset);
                        out.limit(bi.offset + bi.size);
                        muxer.writeSampleData(holder.videoTrack, out, bi);
                    }
                }
                encoder.releaseOutputBuffer(idx, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** muxer 的轨道状态，供 drainEncoder 使用。 */
    private static final class MuxerTrackHolder {
        int videoTrack = -1;
        int audioTrack = -1;
        boolean audioReady;
        boolean started;
        boolean eosSignaled;
    }

    // ------------------------------------------------------------------ 保存

    /** 已卡通化的画布直接存相册（预览画面兜底用）。 */
    public static String saveToGallery(Context ctx, Bitmap bmp) {
        return saveBitmap(ctx, bmp);
    }

    /**
     * 把相机原图（未叠加任何风格的 JPEG 字节）原样存进相册。
     * “同时保存原素材”开启时，拍照会额外保存这一张。
     */
    public static String saveOriginalJpeg(Context ctx, byte[] jpeg) {
        String name = "Daigua_" + timestamp() + "_原图.jpg";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/DaiguaCamera");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("无法创建原图文件");
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("无法写入原图");
                    out.write(jpeg);
                }
                v.clear();
                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, v, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "DaiguaCamera");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建保存目录");
                File f = new File(dir, name);
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(jpeg);
                }
                MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, null, null);
            }
            return "已保存原图 · Pictures/DaiguaCamera";
        } catch (Throwable t) {
            Log.e(TAG, "saveOriginalJpeg", t);
            throw new IllegalStateException(t.getMessage() == null ? "保存原图失败" : t.getMessage());
        }
    }

    private static String saveBitmap(Context ctx, Bitmap bmp) {
        String name = "Daigua_" + timestamp() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/DaiguaCamera");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("无法创建图片文件");
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("无法写入图片");
                    bmp.compress(Bitmap.CompressFormat.JPEG, 94, out);
                }
                v.clear();
                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, v, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "DaiguaCamera");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建保存目录");
                File f = new File(dir, name);
                try (OutputStream out = new FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 94, out);
                }
                MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, null, null);
            }
            return "已保存到相册 · Pictures/DaiguaCamera";
        } catch (Throwable t) {
            Log.e(TAG, "saveBitmap", t);
            throw new IllegalStateException(t.getMessage() == null ? "保存图片失败" : t.getMessage());
        }
    }

    private static String saveVideoFile(Context ctx, File src, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/DaiguaCamera");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("无法创建视频文件");
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("无法写入视频");
                    copyFile(src, out);
                }
                v.clear();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(uri, v, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), "DaiguaCamera");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建保存目录");
                File dst = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    copyFile(src, out);
                }
                MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, null, null);
            }
            return "已保存到相册 · Movies/DaiguaCamera";
        } catch (Throwable t) {
            Log.e(TAG, "saveVideoFile", t);
            throw new IllegalStateException(t.getMessage() == null ? "保存视频失败" : t.getMessage());
        }
    }

    private static void copyFile(File src, OutputStream out) throws Exception {
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    // ------------------------------------------------------------------ 离屏 GL

    /** SurfaceTexture 持有者：解码器渲染目标 + 帧到达等待。 */
    private static final class SurfaceTextureHolder {
        final android.graphics.SurfaceTexture texture;
        final Surface surface;
        private final Object lock = new Object();
        private boolean frameReady;

        SurfaceTextureHolder(int texId) {
            texture = new android.graphics.SurfaceTexture(texId);
            texture.setOnFrameAvailableListener(st -> {
                synchronized (lock) {
                    frameReady = true;
                    lock.notifyAll();
                }
            }, new Handler(Looper.getMainLooper()));
            surface = new Surface(texture);
        }

        boolean awaitFrame(long timeoutMs) {
            synchronized (lock) {
                long deadline = SystemClock.elapsedRealtime() + timeoutMs;
                while (!frameReady) {
                    long remain = deadline - SystemClock.elapsedRealtime();
                    if (remain <= 0) break;
                    try {
                        lock.wait(remain);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                boolean got = frameReady;
                frameReady = false;
                return got;
            }
        }

        void release() {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
            try {
                texture.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 离屏 GL：pbuffer 上下文、半分辨率 FBO、编码器窗口 Surface、图片/视频两套程序。 */
    private static final class OfflineGl {
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLConfig config;
        private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
        private EGLSurface encoderSurface = EGL14.EGL_NO_SURFACE;

        private int quad;
        private Program pDownOes, pDown2d, pBlur, pFinalOes, pFinal2d, pPlainOes, pPlain2d;

        private int fboA, texA, fboB, texB, fw, fh;
        private int oesTex;
        private SurfaceTextureHolder cameraHolder;

        // 小精灵层（与相机里同一套 SDF 精灵 + 选址逻辑）
        private Program pSprite, pCopy;
        private SpriteSystem sprites;
        private int contentFbo, contentTex;
        private ByteBuffer contentBuf;
        private byte[] contentBytes;
        private int contentTick;
        private float spriteTime;
        private final float[] tint3 = new float[3];

        private final float[] identity = new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };

        void init() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("无法初始化 GPU");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("无法初始化 GPU");
            }
            int[] recordable = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            int[] plain = {
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
            boolean ok = EGL14.eglChooseConfig(display, recordable, 0, configs, 0, 1, num, 0)
                    && num[0] > 0;
            if (!ok) {
                ok = EGL14.eglChooseConfig(display, plain, 0, configs, 0, 1, num, 0) && num[0] > 0;
            }
            if (!ok) throw new IllegalStateException("设备不支持所需的 GPU 配置");
            config = configs[0];

            int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("无法创建 GPU 上下文");

            int[] pb = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, pb, 0);
            if (pbuffer == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("无法创建渲染缓冲");
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                throw new IllegalStateException("无法启用 GPU 上下文");
            }

            quad = createQuad();
            pDownOes = new Program(CartoonRenderer.VS_CAMERA, CartoonRenderer.FS_DOWN);
            pDown2d = new Program(CartoonRenderer.VS_PLAIN, to2d(CartoonRenderer.FS_DOWN));
            pBlur = new Program(CartoonRenderer.VS_PLAIN, CartoonRenderer.FS_BLUR);
            pFinalOes = new Program(CartoonRenderer.VS_FINAL, CartoonRenderer.FS_FINAL);
            pFinal2d = new Program(CartoonRenderer.VS_FINAL, to2d(CartoonRenderer.FS_FINAL));
            pPlainOes = new Program(CartoonRenderer.VS_FINAL, CartoonRenderer.FS_PLAIN_OES);
            pPlain2d = new Program(CartoonRenderer.VS_FINAL, to2d(CartoonRenderer.FS_PLAIN_OES));

            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDisable(GLES20.GL_DITHER);
        }

        private static String to2d(String src) {
            return src.replace("#extension GL_OES_EGL_image_external : require", "")
                    .replace("samplerExternalOES", "sampler2D");
        }

        /** 建好解码用的 OES 纹理 + SurfaceTexture（需要在 GL 线程调用）。 */
        SurfaceTextureHolder prepareVideo() {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            oesTex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            cameraHolder = new SurfaceTextureHolder(oesTex);
            return cameraHolder;
        }

        void makeEncoderSurface(Surface surface) {
            int[] attribs = {EGL14.EGL_NONE};
            encoderSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0);
            if (encoderSurface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("无法创建编码器渲染表面");
            }
        }

        private void ensureFbos(int w, int h) {
            w = Math.max(2, w);
            h = Math.max(2, h);
            if (fboA != 0 && fw == w && fh == h) return;
            deleteFbos();
            fw = w;
            fh = h;
            texA = createTexture(w, h);
            fboA = createFbo(texA);
            texB = createTexture(w, h);
            fboB = createFbo(texB);
        }

        private int createTexture(int w, int h) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return t[0];
        }

        private int createFbo(int tex) {
            int[] f = new int[1];
            GLES20.glGenFramebuffers(1, f, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                throw new IllegalStateException("GPU 缓冲区创建失败：" + status);
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            return f[0];
        }

        private void deleteFbos() {
            if (fboA != 0) GLES20.glDeleteFramebuffers(1, new int[]{fboA}, 0);
            if (fboB != 0) GLES20.glDeleteFramebuffers(1, new int[]{fboB}, 0);
            if (texA != 0) GLES20.glDeleteTextures(1, new int[]{texA}, 0);
            if (texB != 0) GLES20.glDeleteTextures(1, new int[]{texB}, 0);
            fboA = fboB = texA = texB = 0;
            fw = fh = 0;
        }

        // -------------------------------------------------------------- 图片

        Bitmap renderImage(Bitmap src, int ow, int oh, StylePreset st, float intensity,
                           boolean spritesOn) {
            int sw = src.getWidth();
            int sh = src.getHeight();
            if (st.normal && !spritesOn) {
                // 正常模式且不开精灵：完全跳过 GPU 卡通管线，直接拷贝原图
                if (sw == ow && sh == oh) {
                    Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) throw new IllegalStateException("无法复制照片");
                    return copy;
                }
                return Bitmap.createScaledBitmap(src, ow, oh, true);
            }
            int tw = Math.max(2, sw / 2);
            int th = Math.max(2, sh / 2);
            ensureFbos(tw, th);

            int srcTex = uploadTexture(src);
            int outTex = createTexture(ow, oh);
            int outFbo = createFbo(outTex);
            try {
                // 1) 原图 -> 半分辨率
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA);
                GLES20.glViewport(0, 0, tw, th);
                Program pd = pDown2d;
                GLES20.glUseProgram(pd.id);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
                GLES20.glUniform1i(pd.u("uTex"), 0);
                GLES20.glUniform2f(pd.u("uSrcTexel"), 1f / sw, 1f / sh);
                drawQuad(pd);

                // 2) 水平 + 垂直保边平滑
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB);
                GLES20.glViewport(0, 0, tw, th);
                Program pb = pBlur;
                GLES20.glUseProgram(pb.id);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
                GLES20.glUniform1i(pb.u("uTex"), 0);
                GLES20.glUniform2f(pb.u("uTexel"), 1f / tw, 1f / th);
                GLES20.glUniform2f(pb.u("uDir"), 1f, 0f);
                GLES20.glUniform1f(pb.u("uRadius"), st.blur * 2f);
                GLES20.glUniform1f(pb.u("uBilateral"), st.bilateral);
                drawQuad(pb);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA);
                GLES20.glViewport(0, 0, tw, th);
                GLES20.glUseProgram(pb.id);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);
                GLES20.glUniform1i(pb.u("uTex"), 0);
                GLES20.glUniform2f(pb.u("uDir"), 0f, 1f);
                drawQuad(pb);

                // 3) 卡通化到输出尺寸
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo);
                GLES20.glViewport(0, 0, ow, oh);
                Program pf = st.normal ? pPlain2d : pFinal2d;
                GLES20.glUseProgram(pf.id);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
                GLES20.glUniform1i(pf.u("uVideo"), 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
                GLES20.glUniform1i(pf.u("uBlur"), 1);
                bindFinalUniforms(pf, st, intensity, 1f, 1f, 0f, 1f, sw, sh, tw, th, 0f,
                        identity, 0f, 0f);

                drawQuad(pf);

                if (spritesOn) {
                    // 小精灵只是锦上添花：万一这一层出问题，也不能连累整张照片
                    try {
                        drawSpriteOverlay(outFbo, ow, oh);
                    } catch (Throwable t) {
                        Log.w(TAG, "sprite overlay skipped", t);
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo);
                        GLES20.glViewport(0, 0, ow, oh);
                    }
                }
                Bitmap result = readPixels(ow, oh);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                return result;
            } finally {
                GLES20.glDeleteTextures(1, new int[]{srcTex}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{outFbo}, 0);
                GLES20.glDeleteTextures(1, new int[]{outTex}, 0);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            }
        }

        private void drawSpriteOverlay(int outFbo, int ow, int oh) {
            // 先按画面内容选址，再模拟 2.4 秒让它们出场、淡入、站稳
            ensureSpriteLayer();
            readContentGrid(true);   // 会把 FBO 解绑，画完精灵前要重新绑回输出缓冲
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo);
            GLES20.glViewport(0, 0, ow, oh);
            for (int i = 0; i < 72; i++) {
                updateSprites(1f / 30f);
            }
            drawSprites(ow, oh);
        }

        private int uploadTexture(Bitmap bmp) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            return t[0];
        }

        private Bitmap readPixels(int w, int h) {
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
            buf.position(0);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    int r = buf.get(i) & 0xFF;
                    int g = buf.get(i + 1) & 0xFF;
                    int b = buf.get(i + 2) & 0xFF;
                    int a = buf.get(i + 3) & 0xFF;
                    row[x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                out.setPixels(row, 0, w, 0, h - 1 - y, w, 1);
            }
            return out;
        }

        // -------------------------------------------------------------- 视频

        void renderVideoFrame(int outW, int outH, int srcW, int srcH, float[] texMatrix,
                             StylePreset st, float intensity, long ptsNs,
                             boolean spritesOn, float dt) {
            if (!EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, context)) {
                throw new IllegalStateException("编码器表面切换失败");
            }
            int tw = Math.max(2, srcW / 2);
            int th = Math.max(2, srcH / 2);
            ensureFbos(tw, th);

            // 1) OES -> 半分辨率
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA);
            GLES20.glViewport(0, 0, tw, th);
            Program pd = pDownOes;
            GLES20.glUseProgram(pd.id);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glUniform1i(pd.u("uTex"), 0);
            GLES20.glUniformMatrix4fv(pd.u("uTexMatrix"), 1, false, texMatrix, 0);
            GLES20.glUniform2f(pd.u("uSrcTexel"), 1f / srcW, 1f / srcH);
            drawQuad(pd);

            // 2) 保边平滑
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB);
            GLES20.glViewport(0, 0, tw, th);
            Program pb = pBlur;
            GLES20.glUseProgram(pb.id);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
            GLES20.glUniform1i(pb.u("uTex"), 0);
            GLES20.glUniform2f(pb.u("uTexel"), 1f / tw, 1f / th);
            GLES20.glUniform2f(pb.u("uDir"), 1f, 0f);
            GLES20.glUniform1f(pb.u("uRadius"), st.blur * 2f);
            GLES20.glUniform1f(pb.u("uBilateral"), st.bilateral);
            drawQuad(pb);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA);
            GLES20.glViewport(0, 0, tw, th);
            GLES20.glUseProgram(pb.id);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);
            GLES20.glUniform1i(pb.u("uTex"), 0);
            GLES20.glUniform2f(pb.u("uDir"), 0f, 1f);
            drawQuad(pb);

            // 3) 最终合成到编码器表面
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, outW, outH);
            Program pf = st.normal ? pPlainOes : pFinalOes;
            GLES20.glUseProgram(pf.id);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glUniform1i(pf.u("uVideo"), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
            GLES20.glUniform1i(pf.u("uBlur"), 1);

            float targetAspect = outW / (float) outH;
            float sourceAspect = srcW / (float) srcH;
            float cx = 1f, cy = 1f;
            if (targetAspect < sourceAspect) cx = targetAspect / sourceAspect;
            else cy = sourceAspect / targetAspect;
            float snap = st.pixel > 1f ? (st.pixel / srcW) : 0f;
            bindFinalUniforms(pf, st, intensity, cx, cy, 0f, 0f, srcW, srcH, tw, th, snap,
                    texMatrix, 0f, 0f);
            drawQuad(pf);

            if (spritesOn) {
                ensureSpriteLayer();
                if (contentTick % 15 == 0) readContentGrid(false);
                updateSprites(dt);
                drawSprites(outW, outH);
            }

            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, ptsNs);
            EGL14.eglSwapBuffers(display, encoderSurface);
        }

        private void bindFinalUniforms(Program p, StylePreset st, float intensity,
                                       float cx, float cy, float mirror, float flipV,
                                       int srcW, int srcH, int tw, int th, float snap,
                                       float[] texMatrix, float time, float aspect) {
            GLES20.glUniform2f(p.u("uCrop"), cx, cy);
            GLES20.glUniform1f(p.u("uMirror"), mirror);
            GLES20.glUniform1f(p.u("uFlipV"), flipV);
            GLES20.glUniform1f(p.u("uSnap"), snap);
            GLES20.glUniformMatrix4fv(p.u("uTexMatrix"), 1, false, texMatrix, 0);
            GLES20.glUniform2f(p.u("uTexel"), 1f / srcW, 1f / srcH);
            GLES20.glUniform2f(p.u("uBlurTexel"), 1f / tw, 1f / th);
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
            GLES20.glUniform1f(p.u("uTime"), time);
            GLES20.glUniform1f(p.u("uAspect"), aspect);
            GLES20.glUniform4f(p.u("uRip0"), 0f, 0f, 9f, 0f);
            GLES20.glUniform4f(p.u("uRip1"), 0f, 0f, 9f, 0f);
        }

        // -------------------------------------------------------------- 小精灵

        private static final String FS_COPY = "precision mediump float;\n"
                + "uniform sampler2D uTex;\n"
                + "varying vec2 vUv;\n"
                + "void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }";

        private void ensureSpriteLayer() {
            if (sprites != null) return;
            sprites = new SpriteSystem();
            pSprite = new Program(SpriteShaders.VS_SPRITE, SpriteShaders.FS_SPRITE);
            pCopy = new Program(CartoonRenderer.VS_PLAIN, FS_COPY);
            int g = SpriteSystem.GRID;
            contentTex = createTexture(g, g);
            contentFbo = createFbo(contentTex);
            contentBuf = ByteBuffer.allocateDirect(g * g * 4).order(ByteOrder.nativeOrder());
            contentBytes = new byte[g * g * 4];
            contentTick = 0;
            spriteTime = 0f;
        }

        /** 16x16 内容缩略图：判断画面哪块平整、哪块亮，供小精灵选址。 */
        private void readContentGrid(boolean flipRows) {
            ensureSpriteLayer();
            int g = SpriteSystem.GRID;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, contentFbo);
            GLES20.glViewport(0, 0, g, g);
            GLES20.glUseProgram(pCopy.id);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
            GLES20.glUniform1i(pCopy.u("uTex"), 0);
            drawQuad(pCopy);
            GLES20.glReadPixels(0, 0, g, g, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, contentBuf);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            contentBuf.position(0);
            if (flipRows) {
                int rowBytes = g * 4;
                for (int y = 0; y < g; y++) {
                    contentBuf.position((g - 1 - y) * rowBytes);
                    contentBuf.get(contentBytes, y * rowBytes, rowBytes);
                }
            } else {
                contentBuf.get(contentBytes);
            }
            contentBuf.position(0);
            sprites.setContent(contentBytes, g, g, 1f, 1f);
            contentTick++;
        }

        private void updateSprites(float dt) {
            if (sprites == null || dt <= 0f) return;
            spriteTime += dt;
            sprites.update(dt, spriteTime, 0f, 0f, 0f, 0f);
        }

        private void drawSprites(int vw, int vh) {
            if (sprites == null || pSprite == null || vw <= 0 || vh <= 0) return;
            boolean any = false;
            for (SpriteSystem.Sprite s : sprites.sprites) {
                if (s != null && s.fade > 0.01f) {
                    any = true;
                    break;
                }
            }
            if (!any) return;

            Program p = pSprite;
            float k = vh / (float) vw;
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(p.id);
            GLES20.glUniform1f(p.u("uMirror"), 0f);
            GLES20.glUniform1f(p.u("uTime"), spriteTime);

            for (SpriteSystem.Sprite s : sprites.sprites) {
                if (s == null || s.fade <= 0.01f) continue;
                float halfY = s.size * s.fade * s.scale;
                float halfX = halfY * k;
                CartoonRenderer.hueColor(s.hue, s.sat, 1f, tint3);
                GLES20.glUniform2f(p.u("uCenter"), s.u * 2f - 1f, s.v * 2f - 1f);
                GLES20.glUniform2f(p.u("uHalf"), halfX, halfY);
                GLES20.glUniform1f(p.u("uRot"), s.rot);
                GLES20.glUniform1f(p.u("uShape"), 0f);
                GLES20.glUniform3f(p.u("uTint"), tint3[0], tint3[1], tint3[2]);
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
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        // -------------------------------------------------------------- 杂项

        private int createQuad() {
            float[] v = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
            FloatBuffer buf = ByteBuffer.allocateDirect(v.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buf.put(v).position(0);
            int[] id = new int[1];
            GLES20.glGenBuffers(1, id, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, v.length * 4, buf, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            return id[0];
        }

        private void drawQuad(Program p) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quad);
            GLES20.glEnableVertexAttribArray(p.aPos);
            GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(p.aPos);
        }

        void release() {
            if (display == EGL14.EGL_NO_DISPLAY) {
                // init 中途失败：没有有效的 display，直接清标记即可
                context = EGL14.EGL_NO_CONTEXT;
                pbuffer = EGL14.EGL_NO_SURFACE;
                encoderSurface = EGL14.EGL_NO_SURFACE;
                return;
            }
            if (cameraHolder != null) {
                cameraHolder.release();
                cameraHolder = null;
            }
            if (oesTex != 0) {
                GLES20.glDeleteTextures(1, new int[]{oesTex}, 0);
                oesTex = 0;
            }
            if (fboA != 0 || fboB != 0) deleteFbos();
            if (quad != 0) GLES20.glDeleteBuffers(1, new int[]{quad}, 0);
            if (contentFbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{contentFbo}, 0);
            if (contentTex != 0) GLES20.glDeleteTextures(1, new int[]{contentTex}, 0);
            if (pSprite != null) pSprite.delete();
            if (pCopy != null) pCopy.delete();
            sprites = null;
            if (pDownOes != null) pDownOes.delete();
            if (pDown2d != null) pDown2d.delete();
            if (pBlur != null) pBlur.delete();
            if (pFinalOes != null) pFinalOes.delete();
            if (pFinal2d != null) pFinal2d.delete();
            if (pPlainOes != null) pPlainOes.delete();
            if (pPlain2d != null) pPlain2d.delete();
            if (encoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, encoderSurface);
                encoderSurface = EGL14.EGL_NO_SURFACE;
            }
            if (pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, pbuffer);
                pbuffer = EGL14.EGL_NO_SURFACE;
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
                context = EGL14.EGL_NO_CONTEXT;
            }
            // 不要 eglTerminate：EGLDisplay 是进程共享的，相机预览的 GL 线程还在用同一个
            // display，终止它会把预览上下文一起废掉（表现为预览黑屏/卡死）。
            display = EGL14.EGL_NO_DISPLAY;
        }
    }

    /** 简易 shader program。 */
    private static final class Program {
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
                throw new IllegalStateException("滤镜程序链接失败：" + log);
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
            throw new IllegalStateException("着色器编译失败：" + log);
        }
        return shader;
    }
}
