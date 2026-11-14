package com.qoder.cartooncamera;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "CartoonCamera";
    private static final int REQ_PERMS = 21;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private SurfaceView surfaceView;
    private CartoonRenderer renderer;
    private CameraController cameraController;

    private int facing = CameraCharacteristics.LENS_FACING_BACK;
    private boolean mirror = false;
    private Size cameraSize = new Size(1280, 720);
    private Surface cameraSurface;
    private boolean surfaceReady = false;
    private boolean cameraOpened = false;
    private int orientedW = 720, orientedH = 1280;

    private StylePreset style = StylePreset.ALL[0];
    private float intensity = 0.85f;
    private SharedPreferences prefs;

    private LinearLayout topBar;
    private LinearLayout bottomPanel;
    private LinearLayout recBadge;
    private TextView timerText;
    private TextView flipBtn;
    private FrameLayout recordBtn;
    private View recordInner;
    private TextView intensityValue;
    private SeekBar intensityBar;
    private final List<TextView> chips = new ArrayList<>();

    private MediaRecorder recorder;
    private boolean recording = false;
    private boolean recorderStarted = false;
    private long recStartElapsed;
    private OutputTarget pendingOutput;
    private boolean micAllowed = false;

    private final Runnable timerTask = new Runnable() {
        @Override
        public void run() {
            updateTimer();
            if (recording) ui.postDelayed(this, 400);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xFF07070B);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        prefs = getSharedPreferences("cartoon", MODE_PRIVATE);
        intensity = prefs.getFloat("intensity", 0.85f);
        style = StylePreset.byId(prefs.getString("style", "cartoon"));

        renderer = new CartoonRenderer();
        renderer.setErrorListener(msg -> ui.post(() -> toast(msg)));
        renderer.setStyle(style, intensity);
        cameraController = new CameraController(this);

        buildUi();

        if (hasPerm(Manifest.permission.RECORD_AUDIO)) micAllowed = true;
        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeStartCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recording || recorderStarted) {
            stopRecording();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (cameraController != null) cameraController.shutdown();
        if (renderer != null) renderer.release();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07070B);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(MATCH, MATCH));
        surfaceView.getHolder().addCallback(this);

        View topScrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000});
        topScrim.setBackground(scrimBg);
        FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(MATCH, dp(150));
        scrimLp.gravity = Gravity.TOP;
        root.addView(topScrim, scrimLp);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFFFF5C7A);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(8);
        topBar.addView(dot, dotLp);

        TextView title = new TextView(this);
        title.setText("卡通相机");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(dp(5), 0, dp(2), 0xAA000000);
        topBar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        flipBtn = new TextView(this);
        flipBtn.setText("⇄");
        flipBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        flipBtn.setTextColor(0xFFFFFFFF);
        flipBtn.setGravity(Gravity.CENTER);
        GradientDrawable flipBg = new GradientDrawable();
        flipBg.setShape(GradientDrawable.OVAL);
        flipBg.setColor(0x40000000);
        flipBg.setStroke(dp(1), 0x33FFFFFF);
        flipBtn.setBackground(flipBg);
        flipBtn.setOnClickListener(v -> flipCamera());
        topBar.addView(flipBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(MATCH, WRAP);
        topLp.gravity = Gravity.TOP;
        root.addView(topBar, topLp);

        // 录制指示
        recBadge = new LinearLayout(this);
        recBadge.setOrientation(LinearLayout.HORIZONTAL);
        recBadge.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(20));
        badgeBg.setColor(0x66000000);
        recBadge.setBackground(badgeBg);
        recBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        recBadge.setVisibility(View.GONE);

        View recDot = new View(this);
        GradientDrawable recDotBg = new GradientDrawable();
        recDotBg.setShape(GradientDrawable.OVAL);
        recDotBg.setColor(0xFFFF3B5C);
        recDot.setBackground(recDotBg);
        LinearLayout.LayoutParams recDotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        recDotLp.rightMargin = dp(6);
        recBadge.addView(recDot, recDotLp);

        timerText = new TextView(this);
        timerText.setText("00:00");
        timerText.setTextColor(0xFFFFFFFF);
        timerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        timerText.setTypeface(Typeface.MONOSPACE);
        recBadge.addView(timerText);

        ValueAnimator blink = ValueAnimator.ofFloat(1f, 0.2f);
        blink.setDuration(650);
        blink.setRepeatMode(ValueAnimator.REVERSE);
        blink.setRepeatCount(ValueAnimator.INFINITE);
        blink.addUpdateListener(a -> recDot.setAlpha((float) a.getAnimatedValue()));
        blink.start();

        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        badgeLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        badgeLp.topMargin = dp(60);
        root.addView(recBadge, badgeLp);

        // 底部控制面板
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF007070B, 0x0007070B});
        bottomPanel.setBackground(panelBg);
        bottomPanel.setPadding(0, dp(26), 0, dp(10));

        HorizontalScrollView styleScroll = new HorizontalScrollView(this);
        styleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.setPadding(dp(16), 0, dp(8), dp(2));
        styleScroll.addView(styleRow, new HorizontalScrollView.LayoutParams(WRAP, WRAP));

        for (final StylePreset p : StylePreset.ALL) {
            TextView chip = new TextView(this);
            chip.setText(p.name);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(8), dp(16), dp(9));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(WRAP, WRAP);
            chipLp.rightMargin = dp(8);
            chip.setLayoutParams(chipLp);
            chip.setBackground(chipBg(p == style));
            chip.setTextColor(p == style ? 0xFFFFFFFF : 0xFFE9EAF2);
            if (p == style) chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setOnClickListener(v -> selectStyle(p));
            chips.add(chip);
            styleRow.addView(chip);
        }
        bottomPanel.addView(styleScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(dp(20), dp(10), dp(20), dp(2));

        TextView sliderLabel = new TextView(this);
        sliderLabel.setText("风格强度");
        sliderLabel.setTextColor(0xFFC9CCD8);
        sliderLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        sliderRow.addView(sliderLabel, new LinearLayout.LayoutParams(WRAP, WRAP));

        intensityBar = new SeekBar(this);
        intensityBar.setMax(1000);
        intensityBar.setProgress((int) (intensity * 1000));
        intensityBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFF5C7A));
        intensityBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFF5C7A));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        barLp.leftMargin = dp(12);
        barLp.rightMargin = dp(12);
        sliderRow.addView(intensityBar, barLp);

        intensityValue = new TextView(this);
        intensityValue.setText(Math.round(intensity * 100) + "%");
        intensityValue.setTextColor(0xFFC9CCD8);
        intensityValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        intensityValue.setTypeface(Typeface.MONOSPACE);
        sliderRow.addView(intensityValue, new LinearLayout.LayoutParams(dp(38), WRAP));

        intensityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                intensity = progress / 1000f;
                intensityValue.setText(Math.round(intensity * 100) + "%");
                renderer.setStyle(style, intensity);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putFloat("intensity", intensity).apply();
            }
        });
        bottomPanel.addView(sliderRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout recordRow = new LinearLayout(this);
        recordRow.setOrientation(LinearLayout.HORIZONTAL);
        recordRow.setGravity(Gravity.CENTER);
        recordRow.setPadding(0, dp(6), 0, dp(4));

        recordBtn = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(4), 0xF2FFFFFF);
        recordBtn.setBackground(ring);
        recordBtn.setOnClickListener(v -> toggleRecording());
        recordBtn.setContentDescription("开始录制");

        recordInner = new View(this);
        GradientDrawable innerBg = new GradientDrawable();
        innerBg.setShape(GradientDrawable.OVAL);
        innerBg.setColor(0xFFFF3B5C);
        recordInner.setBackground(innerBg);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(dp(60), dp(60));
        innerLp.gravity = Gravity.CENTER;
        recordBtn.addView(recordInner, innerLp);

        recordRow.addView(recordBtn, new LinearLayout.LayoutParams(dp(78), dp(78)));
        bottomPanel.addView(recordRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(MATCH, WRAP);
        panelLp.gravity = Gravity.BOTTOM;
        root.addView(bottomPanel, panelLp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            topBar.setPadding(dp(16), top + dp(8), dp(16), dp(6));
            bottomPanel.setPadding(0, dp(26), 0, bottom + dp(10));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) recBadge.getLayoutParams();
            lp.topMargin = top + dp(56);
            recBadge.setLayoutParams(lp);
            return insets;
        });

        setContentView(root);
    }

    private GradientDrawable chipBg(boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(22));
        if (active) {
            d.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            d.setColors(new int[]{0xFFFF5C7A, 0xFFFF9A5C});
        } else {
            d.setColor(0x16FFFFFF);
            d.setStroke(dp(1), 0x26FFFFFF);
        }
        return d;
    }

    private void selectStyle(StylePreset preset) {
        style = preset;
        renderer.setStyle(style, intensity);
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            boolean active = StylePreset.ALL[i] == preset;
            chip.setBackground(chipBg(active));
            chip.setTextColor(active ? 0xFFFFFFFF : 0xFFE9EAF2);
            chip.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        prefs.edit().putString("style", preset.id).apply();
    }

    // ------------------------------------------------------------------ permissions

    private boolean hasPerm(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissions() {
        List<String> need = new ArrayList<>();
        if (!hasPerm(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        micAllowed = hasPerm(Manifest.permission.RECORD_AUDIO);
        if (hasPerm(Manifest.permission.CAMERA)) {
            maybeStartCamera();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("需要相机权限")
                    .setMessage("开启相机权限后才能使用卡通相机。")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    // ------------------------------------------------------------------ surface / camera

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        int w = holder.getSurfaceFrame().width();
        int h = holder.getSurfaceFrame().height();
        if (w <= 0 || h <= 0) {
            w = surfaceView.getWidth();
            h = surfaceView.getHeight();
        }
        if (w <= 0 || h <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        cameraSize = cameraController.pickPreviewSize(facing);
        boolean swap = getSensorOrientation(facing) % 180 != 0;
        renderer.start(holder.getSurface(), w, h);
        renderer.setMirror(mirror);
        renderer.createCameraSurface(cameraSize, swap, cameraCallback);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        renderer.updatePreviewSize(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        cameraOpened = false;
        cameraController.closeAndWait();
        cameraSurface = null;

        MediaRecorder rec = recorder;
        boolean hadOutput = (rec != null || recorderStarted || recording);
        recorder = null;
        recording = false;
        recorderStarted = false;
        ui.removeCallbacks(timerTask);

        renderer.release();
        boolean stoppedOk = false;
        if (rec != null) {
            try {
                rec.stop();
                stoppedOk = true;
            } catch (Throwable ignored) {
            }
            try {
                rec.reset();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
            if (hadOutput && stoppedOk) publishOutput();
            else if (hadOutput) discardOutput();
        }
    }

    private int getSensorOrientation(int facing) {
        return cameraController.getSensorOrientation(facing);
    }

    private final CartoonRenderer.Callback cameraCallback = new CartoonRenderer.Callback() {
        @Override
        public void onCameraSurfaceReady(Surface surface, int frameWidth, int frameHeight) {
            ui.post(() -> {
                cameraSurface = surface;
                orientedW = frameWidth;
                orientedH = frameHeight;
                maybeStartCamera();
            });
        }

        @Override
        public void onError(String message) {
            ui.post(() -> toast(message));
        }
    };

    private void maybeStartCamera() {
        if (!surfaceReady || cameraSurface == null || cameraOpened) return;
        if (!hasPerm(Manifest.permission.CAMERA)) return;
        cameraOpened = true;
        cameraController.open(facing, cameraSurface, new CameraController.Listener() {
            @Override
            public void onOpened() {
            }

            @Override
            public void onError(String message) {
                cameraOpened = false;
                ui.post(() -> toast(message));
            }
        });
    }

    private void flipCamera() {
        if (recording || recorderStarted) {
            toast("录制中无法切换摄像头");
            return;
        }
        facing = (facing == CameraCharacteristics.LENS_FACING_BACK)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        mirror = facing == CameraCharacteristics.LENS_FACING_FRONT;
        renderer.setMirror(mirror);
        cameraOpened = false;
        cameraController.closeAndWait();
        cameraSize = cameraController.pickPreviewSize(facing);
        boolean swap = getSensorOrientation(facing) % 180 != 0;
        renderer.createCameraSurface(cameraSize, swap, cameraCallback);
    }

    // ------------------------------------------------------------------ recording

    private void toggleRecording() {
        if (recording || recorderStarted) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (recording || recorderStarted) return;
        if (!surfaceReady || cameraSurface == null || !cameraOpened) {
            toast("相机还没准备好");
            return;
        }
        micAllowed = hasPerm(Manifest.permission.RECORD_AUDIO);

        int recW = 720, recH = 1280;
        if (orientedW > orientedH) {
            recW = 1280;
            recH = 720;
        }

        try {
            pendingOutput = createOutput();
            MediaRecorder rec;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rec = new MediaRecorder(this);
            } else {
                rec = new MediaRecorder();
            }
            if (micAllowed) {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (micAllowed) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioSamplingRate(44100);
                rec.setAudioEncodingBitRate(128000);
            }
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            rec.setVideoSize(recW, recH);
            rec.setVideoFrameRate(30);
            rec.setVideoEncodingBitRate(8_000_000);
            rec.setOrientationHint(0);
            if (pendingOutput.pfd != null) {
                rec.setOutputFile(pendingOutput.pfd.getFileDescriptor());
            } else {
                rec.setOutputFile(pendingOutput.file.getAbsolutePath());
            }
            rec.setOnErrorListener((mr, what, extra) -> ui.post(() -> {
                toast("录像出错（" + what + "）");
                stopRecording();
            }));
            rec.prepare();

            Surface s = rec.getSurface();
            recorder = rec;
            final int fw = recW, fh = recH;
            renderer.startEncoder(s, fw, fh, new CartoonRenderer.EncoderCallback() {
                @Override
                public void onEncoderReady() {
                    ui.post(() -> {
                        MediaRecorder r = recorder;
                        if (r == null) return;
                        try {
                            r.start();
                            recorderStarted = true;
                            recording = true;
                            recStartElapsed = SystemClock.elapsedRealtime();
                            setRecordingUi(true);
                        } catch (Throwable t) {
                            Log.e(TAG, "recorder start", t);
                            abortRecording();
                            toast("无法开始录像");
                        }
                    });
                }

                @Override
                public void onEncoderError(String message) {
                    ui.post(() -> {
                        abortRecording();
                        toast(message);
                    });
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "prepare recorder", t);
            abortRecording();
            toast("无法开始录像：" + t.getMessage());
        }
    }

    private void stopRecording() {
        if (!recording && !recorderStarted) return;
        recording = false;
        setRecordingUi(false);
        renderer.stopEncoder(() -> ui.post(this::finishRecording));
    }

    private void finishRecording() {
        MediaRecorder rec = recorder;
        recorder = null;
        if (rec == null && pendingOutput == null) return;
        boolean ok = false;
        if (rec != null) {
            try {
                if (recorderStarted) {
                    rec.stop();
                    ok = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "recorder stop", t);
            }
            try {
                rec.reset();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
        }
        recorderStarted = false;
        if (ok) {
            publishOutput();
        } else {
            discardOutput();
            toast("录像没有保存（没有采集到画面）");
        }
    }

    private void abortRecording() {
        recording = false;
        recorderStarted = false;
        MediaRecorder rec = recorder;
        recorder = null;
        renderer.stopEncoder(null);
        if (rec != null) {
            try {
                rec.reset();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
        }
        discardOutput();
        setRecordingUi(false);
    }

    private void setRecordingUi(boolean rec) {
        recBadge.setVisibility(rec ? View.VISIBLE : View.GONE);
        if (rec) {
            updateTimer();
            ui.post(timerTask);
        } else {
            ui.removeCallbacks(timerTask);
        }
        GradientDrawable d = (GradientDrawable) recordInner.getBackground();
        if (d != null) {
            d.setCornerRadius(dp(rec ? 10 : 30));
        }
        recordInner.animate().cancel();
        recordInner.animate()
                .scaleX(rec ? 0.46f : 1f)
                .scaleY(rec ? 0.46f : 1f)
                .setDuration(180)
                .start();
        recordBtn.setContentDescription(rec ? "停止录制" : "开始录制");
        flipBtn.setEnabled(!rec);
        flipBtn.setAlpha(rec ? 0.35f : 1f);
    }

    private void updateTimer() {
        long seconds = (SystemClock.elapsedRealtime() - recStartElapsed) / 1000;
        timerText.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
    }

    // ------------------------------------------------------------------ output files

    private static class OutputTarget {
        Uri uri;
        ParcelFileDescriptor pfd;
        File file;
        String name;
    }

    private OutputTarget createOutput() throws Exception {
        String name = "Cartoon_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        OutputTarget out = new OutputTarget();
        out.name = name;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CartoonCamera");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("无法创建视频文件");
            out.uri = uri;
            out.pfd = cr.openFileDescriptor(uri, "w");
            if (out.pfd == null) throw new Exception("无法写入视频文件");
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CartoonCamera");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建保存目录");
            out.file = new File(dir, name);
        }
        return out;
    }

    private void publishOutput() {
        OutputTarget out = pendingOutput;
        pendingOutput = null;
        if (out == null) return;
        try {
            if (out.uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(out.uri, v, null, null);
                toast("已保存到相册 · Movies/CartoonCamera");
            } else if (out.file != null) {
                MediaScannerConnection.scanFile(this, new String[]{out.file.getAbsolutePath()}, null, null);
                toast("已保存：" + out.name);
            }
        } catch (Throwable t) {
            Log.e(TAG, "publish", t);
            discardOutput();
        } finally {
            if (out.pfd != null) {
                try {
                    out.pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void discardOutput() {
        OutputTarget out = pendingOutput;
        pendingOutput = null;
        if (out == null) return;
        if (out.pfd != null) {
            try {
                out.pfd.close();
            } catch (Throwable ignored) {
            }
        }
        if (out.uri != null) {
            try {
                getContentResolver().delete(out.uri, null, null);
            } catch (Throwable ignored) {
            }
        }
        if (out.file != null && out.file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            out.file.delete();
        }
    }

    // ------------------------------------------------------------------ misc

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
