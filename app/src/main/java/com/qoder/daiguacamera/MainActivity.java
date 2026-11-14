package com.qoder.daiguacamera;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "CartoonCamera";
    private static final int REQ_PERMS = 21;
    private static final int REQ_PICK = 31;
    private static final int REQ_WRITE = 32;
    private static final int REQ_WRITE_PHOTO = 33;
    private static final String DOC_GUIDE = "file:///android_asset/docs/user_guide.html";
    private static final String DOC_LOG = "file:///android_asset/docs/changelog.html";
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
    private float intensity = 0.95f;
    private SharedPreferences prefs;

    // 小精灵
    private boolean spritesOn = false;
    private TextView spriteBtn;
    private SensorManager sensorManager;
    private Sensor gyro;
    private long lastMotionPost;
    private long lastTouchPost;

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

    // 拍摄模式：录像 / 拍照（普通·专业·傻瓜）
    private static final int MODE_VIDEO = 0;
    private static final int MODE_PHOTO = 1;
    private static final int SUB_NORMAL = 0;
    private static final int SUB_PRO = 1;
    private static final int SUB_IDIOT = 2;

    private int captureMode = MODE_VIDEO;
    private int photoSub = SUB_NORMAL;
    private int videoSub = SUB_NORMAL;
    private SceneStore sceneStore;
    private java.util.List<ScenePreset> sceneList;
    private ScenePreset scene = ScenePreset.ALL[0];
    private ScenePreset videoScene = ScenePreset.ALL[0];
    private CaptureParams proParams = new CaptureParams();
    private CaptureParams videoProParams = new CaptureParams();
    private boolean capturing;

    /** 录像手动快门上限：1/30 秒，超过会掉帧。 */
    private static final long VIDEO_MAX_EXPOSURE_NS = 1_000_000_000L / 30;

    private TextView videoPill, photoPill;
    private TextView subNormalPill, subProPill, subIdiotPill;
    private LinearLayout extraRow;
    private TextView extraSummary;
    private TextView extraActionBtn;

    // 相册卡通化
    private TextView galleryBtn;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog progressDialog;
    private AtomicBoolean processingCancel;
    private boolean processing;
    private Uri pendingGalleryUri;
    private boolean pendingPhoto;
    private boolean previewFallbackBusy;

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
        intensity = prefs.getFloat("intensity", 0.95f);
        style = StylePreset.byId(prefs.getString("style", "normal"));
        // v1.9 起小精灵默认关闭；升级用户也执行一次，之后尊重用户自己的开关
        if (!prefs.getBoolean("sprites_v19", false)) {
            prefs.edit().putBoolean("sprites", false).putBoolean("sprites_v19", true).apply();
        }
        spritesOn = prefs.getBoolean("sprites", false);
        captureMode = prefs.getInt("captureMode", MODE_VIDEO);
        photoSub = prefs.getInt("photoSub", SUB_NORMAL);
        videoSub = prefs.getInt("videoSub", SUB_NORMAL);
        proParams = loadProParams("pro_", true);
        videoProParams = loadProParams("vpro_", false);
        sceneStore = new SceneStore(this);
        sceneList = sceneStore.scenes();
        scene = sceneStore.find(prefs.getString("scene", null));
        videoScene = sceneStore.find(prefs.getString("video_scene", null));

        renderer = new CartoonRenderer();
        renderer.setErrorListener(msg -> ui.post(() -> toast(msg)));
        renderer.setStyle(style, intensity);
        renderer.setSpritesEnabled(spritesOn);
        cameraController = new CameraController(this);

        buildUi();
        updateModeUi();
        applyCameraParams();

        if (hasPerm(Manifest.permission.RECORD_AUDIO)) micAllowed = true;
        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeStartCamera();
        registerMotion();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterMotion();
        if (recording || recorderStarted) {
            stopRecording();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (processingCancel != null) processingCancel.set(true);
        capturing = false;
        worker.shutdownNow();
        dismissProgress();
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
        title.setText("呆瓜相机");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(dp(5), 0, dp(2), 0xAA000000);
        topBar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        galleryBtn = new TextView(this);
        galleryBtn.setText("相册");
        galleryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        galleryBtn.setTextColor(0xFFFFFFFF);
        galleryBtn.setGravity(Gravity.CENTER);
        GradientDrawable galleryBg = new GradientDrawable();
        galleryBg.setShape(GradientDrawable.OVAL);
        galleryBg.setColor(0x40000000);
        galleryBg.setStroke(dp(1), 0x33FFFFFF);
        galleryBtn.setBackground(galleryBg);
        galleryBtn.setContentDescription("相册卡通化");
        galleryBtn.setOnClickListener(v -> pickFromGallery());
        LinearLayout.LayoutParams galleryLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        galleryLp.rightMargin = dp(8);
        topBar.addView(galleryBtn, galleryLp);

        TextView docsBtn = new TextView(this);
        docsBtn.setText("说明");
        docsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        docsBtn.setTextColor(0xFFFFFFFF);
        docsBtn.setGravity(Gravity.CENTER);
        GradientDrawable docsBg = new GradientDrawable();
        docsBg.setShape(GradientDrawable.OVAL);
        docsBg.setColor(0x40000000);
        docsBg.setStroke(dp(1), 0x33FFFFFF);
        docsBtn.setBackground(docsBg);
        docsBtn.setContentDescription("操作手册与版本更新说明");
        docsBtn.setOnClickListener(v -> showDocsDialog());
        LinearLayout.LayoutParams docsLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        docsLp.rightMargin = dp(8);
        topBar.addView(docsBtn, docsLp);

        spriteBtn = new TextView(this);
        spriteBtn.setText("✦");
        spriteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        spriteBtn.setTextColor(0xFFFFFFFF);
        spriteBtn.setGravity(Gravity.CENTER);
        GradientDrawable spriteBg = new GradientDrawable();
        spriteBg.setShape(GradientDrawable.OVAL);
        spriteBg.setColor(0x40000000);
        spriteBg.setStroke(dp(1), 0x33FFFFFF);
        spriteBtn.setBackground(spriteBg);
        spriteBtn.setAlpha(spritesOn ? 1f : 0.38f);
        spriteBtn.setContentDescription("小精灵开关");
        spriteBtn.setOnClickListener(v -> toggleSprites());
        LinearLayout.LayoutParams spriteLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        spriteLp.rightMargin = dp(8);
        topBar.addView(spriteBtn, spriteLp);

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

        // 第一层：录像 / 拍照（大号分段控件，和下面两层明显区分）
        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        segRow.setGravity(Gravity.CENTER);

        LinearLayout captureSeg = new LinearLayout(this);
        captureSeg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(0x1FFFFFFF);
        segBg.setCornerRadius(dp(23));
        segBg.setStroke(dp(1), 0x3DFFFFFF);
        captureSeg.setBackground(segBg);
        captureSeg.setPadding(dp(4), dp(4), dp(4), dp(4));

        videoPill = segment("录像", true);
        videoPill.setOnClickListener(v -> setCaptureMode(MODE_VIDEO));
        captureSeg.addView(videoPill, new LinearLayout.LayoutParams(0, dp(36), 1f));

        photoPill = segment("拍照", false);
        photoPill.setOnClickListener(v -> setCaptureMode(MODE_PHOTO));
        LinearLayout.LayoutParams photoSegLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        photoSegLp.leftMargin = dp(4);
        captureSeg.addView(photoPill, photoSegLp);

        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(dp(212), WRAP);
        segRow.addView(captureSeg, segLp);
        LinearLayout.LayoutParams segRowLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        segRowLp.bottomMargin = dp(7);
        bottomPanel.addView(segRow, segRowLp);

        // 第二层：模式（普通 / 专业 / 傻瓜），录像和拍照共用
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(dp(20), 0, dp(12), dp(2));

        TextView subLabel = new TextView(this);
        subLabel.setText("模式");
        subLabel.setTextColor(0xFF8F94A6);
        subLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        subLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams subLabelLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        subLabelLp.rightMargin = dp(10);
        subRow.addView(subLabel, subLabelLp);

        subNormalPill = pill("普通", true);
        subNormalPill.setOnClickListener(v -> setSub(SUB_NORMAL));
        addPill(subRow, subNormalPill);

        subProPill = pill("专业", false);
        subProPill.setOnClickListener(v -> setSub(SUB_PRO));
        addPill(subRow, subProPill);

        subIdiotPill = pill("傻瓜", false);
        subIdiotPill.setOnClickListener(v -> setSub(SUB_IDIOT));
        addPill(subRow, subIdiotPill);

        bottomPanel.addView(subRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 第三层：风格（卡通滤镜，独立于拍摄模式和模式）
        LinearLayout styleWrap = new LinearLayout(this);
        styleWrap.setOrientation(LinearLayout.HORIZONTAL);
        styleWrap.setGravity(Gravity.CENTER_VERTICAL);
        styleWrap.setPadding(dp(20), dp(5), 0, dp(2));

        TextView styleLabel = new TextView(this);
        styleLabel.setText("风格");
        styleLabel.setTextColor(0xFF8F94A6);
        styleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        styleLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams styleLabelLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        styleLabelLp.rightMargin = dp(10);
        styleWrap.addView(styleLabel, styleLabelLp);

        HorizontalScrollView styleScroll = new HorizontalScrollView(this);
        styleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.setPadding(0, 0, dp(8), dp(2));
        styleScroll.addView(styleRow, new HorizontalScrollView.LayoutParams(WRAP, WRAP));

        for (final StylePreset p : StylePreset.ALL) {
            TextView chip = new TextView(this);
            chip.setText(p.name);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(7), dp(14), dp(8));
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
        styleWrap.addView(styleScroll, new LinearLayout.LayoutParams(0, WRAP, 1f));
        bottomPanel.addView(styleWrap, new LinearLayout.LayoutParams(MATCH, WRAP));

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
        updateIntensityEnabled();

        // 专业参数摘要 / 傻瓜场景摘要
        extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        extraRow.setGravity(Gravity.CENTER_VERTICAL);
        extraRow.setPadding(dp(20), 0, dp(20), dp(2));
        extraRow.setVisibility(View.GONE);

        extraSummary = new TextView(this);
        extraSummary.setTextColor(0xFFB9BDCC);
        extraSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        extraSummary.setSingleLine(true);
        extraSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        extraRow.addView(extraSummary, new LinearLayout.LayoutParams(0, WRAP, 1f));

        extraActionBtn = pill("调整", false);
        extraActionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        extraActionBtn.setPadding(dp(14), dp(5), dp(14), dp(6));
        extraActionBtn.setOnClickListener(v -> onExtraAction());
        LinearLayout.LayoutParams extraBtnLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        extraBtnLp.leftMargin = dp(10);
        extraRow.addView(extraActionBtn, extraBtnLp);

        bottomPanel.addView(extraRow, new LinearLayout.LayoutParams(MATCH, WRAP));

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
        recordBtn.setOnClickListener(v -> onShutter());
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

        // 触摸：给小精灵和周围卡通景物加反应（按钮区域会先消费事件）
        root.setOnTouchListener((v, e) -> {
            float w = v.getWidth();
            float h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            float u = e.getX() / w;
            float vv = 1f - e.getY() / h;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchPost = SystemClock.elapsedRealtime();
                    renderer.onTouch(u, vv, 0);
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (SystemClock.elapsedRealtime() - lastTouchPost > 40) {
                        lastTouchPost = SystemClock.elapsedRealtime();
                        renderer.onTouch(u, vv, 1);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    renderer.onTouch(u, vv, 2);
                    break;
                default:
                    break;
            }
            return true;
        });

        setContentView(root);
    }

    private void registerMotion() {
        if (gyro != null) return;
        if (sensorManager == null) {
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        }
        if (sensorManager == null) return;
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro != null) {
            sensorManager.registerListener(motionListener, gyro, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void unregisterMotion() {
        if (sensorManager != null && gyro != null) {
            sensorManager.unregisterListener(motionListener);
        }
    }

    private void toggleSprites() {
        spritesOn = !spritesOn;
        prefs.edit().putBoolean("sprites", spritesOn).apply();
        renderer.setSpritesEnabled(spritesOn);
        if (spriteBtn != null) {
            spriteBtn.animate().alpha(spritesOn ? 1f : 0.38f).setDuration(150).start();
        }
        toast(spritesOn ? "小精灵：开（相机/拍照/相册都会出现）" : "小精灵：关");
    }

    private final SensorEventListener motionListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastMotionPost < 16) return;
            lastMotionPost = now;
            float gx = e.values[0];
            float gy = e.values[1];
            float gz = e.values[2];
            float speed = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            // 竖屏下：y 轴为左右转动，x 轴为俯仰，z 轴为滚转
            renderer.setMotion(gy, gx, gz, speed);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

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
        updateIntensityEnabled();
    }

    /** 正常模式不做任何调色，风格强度滑块此时没有意义，置灰。 */
    private void updateIntensityEnabled() {
        boolean on = style == null || !style.normal;
        if (intensityBar != null) {
            intensityBar.setEnabled(on);
            intensityBar.setAlpha(on ? 1f : 0.35f);
        }
        if (intensityValue != null) intensityValue.setAlpha(on ? 1f : 0.35f);
    }

    /** 内置说明：操作手册与版本更新说明，内容打包在 APK 的 assets 里，离线可看。 */
    private void showDocsDialog() {
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF07070B);

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(10), dp(12), dp(6));

        TextView title = new TextView(this);
        title.setText("使用说明");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setTextColor(0xFFE9EAF2);
        close.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.OVAL);
        closeBg.setColor(0x1AFFFFFF);
        closeBg.setStroke(dp(1), 0x26FFFFFF);
        close.setBackground(closeBg);
        close.setOnClickListener(v -> dlg.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        root.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(16), 0, dp(16), dp(8));
        final TextView guideTab = pill("操作手册", true);
        guideTab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        guideTab.setPadding(dp(14), dp(6), dp(14), dp(7));
        final TextView logTab = pill("更新说明", false);
        logTab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        logTab.setPadding(dp(14), dp(6), dp(14), dp(7));
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tabLp.rightMargin = dp(8);
        tabs.addView(guideTab, tabLp);
        tabs.addView(logTab, new LinearLayout.LayoutParams(WRAP, WRAP));
        root.addView(tabs, new LinearLayout.LayoutParams(MATCH, WRAP));

        final WebView web = new WebView(this);
        web.setBackgroundColor(0xFF07070B);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(true);
        ws.setDomStorageEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                if (url.startsWith("file:///android_asset/docs/")) {
                    boolean log = url.contains("changelog.html");
                    view.loadUrl(url);
                    stylePill(guideTab, !log);
                    stylePill(logTab, log);
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable t) {
                    toast("打不开这个链接");
                }
                return true;
            }
        });
        web.loadUrl(DOC_GUIDE);
        root.addView(web, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        guideTab.setOnClickListener(v -> {
            web.loadUrl(DOC_GUIDE);
            stylePill(guideTab, true);
            stylePill(logTab, false);
        });
        logTab.setOnClickListener(v -> {
            web.loadUrl(DOC_LOG);
            stylePill(guideTab, false);
            stylePill(logTab, true);
        });

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            head.setPadding(dp(20), top + dp(8), dp(12), dp(6));
            web.setPadding(0, 0, 0, bottom);
            return insets;
        });

        dlg.setContentView(root);
        dlg.setOnDismissListener(d -> {
            try {
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) {
            }
        });
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0xFF07070B));
            w.setLayout(MATCH, MATCH);
        }
    }

    // ------------------------------------------------------------------ 快门 / 拍照

    private void onShutter() {
        if (captureMode == MODE_PHOTO) {
            takePhoto();
        } else {
            toggleRecording();
        }
    }

    private void takePhoto() {
        if (capturing) return;
        if (processing) {
            toast("正在处理上一张，请稍候");
            return;
        }
        if (!surfaceReady || !cameraOpened) {
            toast("相机还没准备好");
            return;
        }
        // Android 9 及以下写公共目录需要存储权限，否则保存一定会失败
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && !hasPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pendingPhoto = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_PHOTO);
            return;
        }
        capturing = true;
        updateShutterBusy();
        recordInner.animate().scaleX(0.8f).scaleY(0.8f).setDuration(90)
                .withEndAction(() -> recordInner.animate().scaleX(1f).scaleY(1f)
                        .setDuration(120).start()).start();
        cameraController.captureStill(bytes -> ui.post(() -> startPhotoProcess(bytes)),
                msg -> ui.post(() -> {
                    capturing = false;
                    updateShutterBusy();
                    fallbackPreviewPhoto(msg);
                }));
    }

    private void updateShutterBusy() {
        recordBtn.setEnabled(!capturing);
        recordBtn.setAlpha(capturing ? 0.5f : 1f);
    }

    private void startPhotoProcess(byte[] jpeg) {
        final StylePreset st = style;
        final float in = intensity;
        final boolean spr = spritesOn;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        processing = true;
        processingCancel = cancel;

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("照片卡通化")
                .setMessage("处理中…")
                .setNegativeButton("取消", (d, w) -> cancel.set(true))
                .setCancelable(false)
                .create();
        progressDialog = dialog;
        dialog.show();

        worker.execute(() -> GalleryProcessor.processJpegBytes(this, jpeg, st, in, spr, cancel,
                new GalleryProcessor.Listener() {
                    @Override
                    public void onProgress(float p, String label) {
                        ui.post(() -> {
                            if (progressDialog != null && progressDialog.isShowing()) {
                                progressDialog.setMessage(label);
                            }
                        });
                    }

                    @Override
                    public void onDone(String message, Bitmap preview) {
                        ui.post(() -> {
                            dismissProgress();
                            processing = false;
                            processingCancel = null;
                            capturing = false;
                            updateShutterBusy();
                            if (isFinishing() || isDestroyed()) return;
                            if (preview != null) showPreview(message, preview);
                            else toast(message);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        ui.post(() -> {
                            dismissProgress();
                            processing = false;
                            processingCancel = null;
                            capturing = false;
                            updateShutterBusy();
                            if (!isFinishing() && !isDestroyed()) showPhotoError(message);
                        });
                    }
                }));
    }

    /**
     * 相机原图没拿到（超时/被机型拒绝/JPEG 丢失）时的兜底：
     * 直接把此刻已经卡通化的预览画面存进相册，至少不会让用户白按一次快门。
     */
    private void fallbackPreviewPhoto(String reason) {
        if (previewFallbackBusy || processing) {
            toast(reason);
            return;
        }
        if (renderer == null || isFinishing() || isDestroyed()) {
            toast(reason);
            return;
        }
        previewFallbackBusy = true;
        processing = true;
        toast(reason + "，先保存当前预览画面");
        renderer.capturePreviewFrame(new CartoonRenderer.FrameGrabCallback() {
            @Override
            public void onFrame(Bitmap frame) {
                worker.execute(() -> {
                    try {
                        String where = GalleryProcessor.saveToGallery(MainActivity.this, frame);
                        ui.post(() -> {
                            previewFallbackBusy = false;
                            processing = false;
                            if (isFinishing() || isDestroyed()) {
                                frame.recycle();
                                return;
                            }
                            showPreview(where + "（相机原图不可用，已保存预览画面）", frame);
                        });
                    } catch (Throwable t) {
                        frame.recycle();
                        ui.post(() -> {
                            previewFallbackBusy = false;
                            processing = false;
                            toast("保存预览画面失败：" + t.getMessage());
                        });
                    }
                });
            }

            @Override
            public void onFailed() {
                ui.post(() -> {
                    previewFallbackBusy = false;
                    processing = false;
                    toast(reason);
                });
            }
        });
    }

    private void showPhotoError(String message) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("照片卡通化失败")
                    .setMessage(message + "\n\n已自动降级重试过（关闭小精灵、降低分辨率）。"
                            + "如果仍然失败，请把这段提示反馈给我。")
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable t) {
            toast(message);
        }
    }

    // ------------------------------------------------------------------ 拍摄模式

    private TextView pill(String text, boolean active) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(7), dp(16), dp(8));
        t.setBackground(chipBg(active));
        t.setTextColor(active ? 0xFFFFFFFF : 0xFFE9EAF2);
        if (active) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void addPill(LinearLayout row, TextView pill) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = dp(8);
        row.addView(pill, lp);
    }

    private void stylePill(TextView t, boolean active) {
        if (t == null) return;
        t.setBackground(chipBg(active));
        t.setTextColor(active ? 0xFFFFFFFF : 0xFFE9EAF2);
        t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** 第一层的大分段按钮：选中时填充渐变，未选中透明。 */
    private TextView segment(String text, boolean active) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.CENTER);
        t.setBackground(segmentBg(active));
        t.setTextColor(active ? 0xFFFFFFFF : 0xFFB9BDCC);
        if (active) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable segmentBg(boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(19));
        if (active) {
            d.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            d.setColors(new int[]{0xFFFF5C7A, 0xFFFF9A5C});
        } else {
            d.setColor(Color.TRANSPARENT);
        }
        return d;
    }

    private void styleSegment(TextView t, boolean active) {
        if (t == null) return;
        t.setBackground(segmentBg(active));
        t.setTextColor(active ? 0xFFFFFFFF : 0xFFB9BDCC);
        t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void setCaptureMode(int mode) {
        if (capturing || mode == captureMode) return;
        if (recording || recorderStarted) {
            stopRecording();
            toast("先结束录像再切换模式");
            return;
        }
        captureMode = mode;
        prefs.edit().putInt("captureMode", captureMode).apply();
        updateModeUi();
        applyCameraParams();
    }

    private void setSub(int sub) {
        if (capturing) return;
        if (recording || recorderStarted) {
            toast("录制中不能切换模式，先停止录像");
            return;
        }
        if (captureMode == MODE_PHOTO) {
            if (sub == photoSub) return;
            photoSub = sub;
            prefs.edit().putInt("photoSub", photoSub).apply();
        } else {
            if (sub == videoSub) return;
            videoSub = sub;
            prefs.edit().putInt("videoSub", videoSub).apply();
        }
        updateModeUi();
        applyCameraParams();
    }

    private int currentSub() {
        return captureMode == MODE_PHOTO ? photoSub : videoSub;
    }

    private void updateModeUi() {
        boolean photo = captureMode == MODE_PHOTO;
        styleSegment(videoPill, !photo);
        styleSegment(photoPill, photo);

        int sub = currentSub();
        stylePill(subNormalPill, sub == SUB_NORMAL);
        stylePill(subProPill, sub == SUB_PRO);
        stylePill(subIdiotPill, sub == SUB_IDIOT);

        GradientDrawable d = (GradientDrawable) recordInner.getBackground();
        if (d != null) {
            d.setColor(photo ? 0xFFF4F5FA : 0xFFFF3B5C);
            d.setCornerRadius(dp(photo ? 30 : (recording ? 10 : 30)));
        }
        recordBtn.setContentDescription(photo ? "拍照" : "开始录制");
        updateExtraUi();
    }

    private void updateExtraUi() {
        if (extraRow == null) return;
        int sub = currentSub();
        if (sub == SUB_NORMAL) {
            extraRow.setVisibility(View.GONE);
            return;
        }
        extraRow.setVisibility(View.VISIBLE);
        boolean video = captureMode == MODE_VIDEO;
        if (sub == SUB_PRO) {
            extraActionBtn.setText("调整");
            CaptureParams p = video ? videoProParams : proParams;
            String s = p.summary();
            if (video && p.manualExposure && p.exposureNs > VIDEO_MAX_EXPOSURE_NS) {
                s += " · 录像快门限 1/30s";
            }
            extraSummary.setText((video ? "录像 · " : "拍照 · ") + s);
        } else {
            extraActionBtn.setText("换场景");
            ScenePreset sp = curScene();
            String mark = sp.custom ? "（自定义）" : (sp.edited ? "（已调参）" : "");
            extraSummary.setText((video ? "录像场景 · " : "拍照场景 · ")
                    + sp.name + mark + "：" + sp.hint);
        }
    }

    private void onExtraAction() {
        if (currentSub() == SUB_PRO) {
            showProDialog();
        } else {
            showSceneDialog();
        }
    }

    private CaptureParams currentParams() {
        int sub = currentSub();
        if (sub == SUB_PRO) {
            return captureMode == MODE_PHOTO ? proParams : videoProParams;
        }
        if (sub == SUB_IDIOT) {
            return curScene().toParams();
        }
        return new CaptureParams();
    }

    private void applyCameraParams() {
        CaptureParams p = currentParams();
        // 录像时手动快门最长 1/30 秒，更慢会明显掉帧；拍照不受影响。
        if (captureMode == MODE_VIDEO && p.manualExposure && p.exposureNs > VIDEO_MAX_EXPOSURE_NS) {
            p = p.copy();
            p.exposureNs = VIDEO_MAX_EXPOSURE_NS;
        }
        if (cameraController != null) cameraController.setParams(p);
    }

    private ScenePreset curScene() {
        return captureMode == MODE_PHOTO ? scene : videoScene;
    }

    private void setCurScene(ScenePreset sp) {
        if (captureMode == MODE_PHOTO) {
            scene = sp;
            prefs.edit().putString("scene", sp.id).apply();
        } else {
            videoScene = sp;
            prefs.edit().putString("video_scene", sp.id).apply();
        }
    }

    // ------------------------------------------------------------------ 专业参数

    private void showProDialog() {
        final boolean video = captureMode == MODE_VIDEO;
        final CaptureParams p = (video ? videoProParams : proParams).copy();
        final String[] shutterLabels = video ? SHUTTER_LABELS_VIDEO : SHUTTER_LABELS;
        final long[] shutterValues = video ? SHUTTER_VALUES_VIDEO : SHUTTER_VALUES;
        final int[] isoIdx = {isoIndexOf(p.manualExposure ? p.iso : 0)};
        final int[] shutterIdx = {shutterIndexOf(p.manualExposure ? p.exposureNs : 0, shutterValues)};
        final int[] evIdx = {Math.round((p.ev + 2f) / 0.1f)};
        final int[] wbIdx = {p.wbAuto ? 0 : Math.max(1, Math.min(57, (p.kelvin - 2300) / 100 + 1))};
        final int[] focusIdx = {p.focusMode == CaptureParams.FOCUS_AUTO ? 0
                : p.focusMode == CaptureParams.FOCUS_INFINITY ? 1
                : p.focusMode == CaptureParams.FOCUS_MACRO ? 2 : 3};
        final int[] manualIdx = {Math.round(Math.max(0f, Math.min(10f, p.diopter)) * 10f)};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        addSeek(box, "ISO", ISO_LABELS, isoIdx[0], v -> isoIdx[0] = v);
        addSeek(box, "快门", shutterLabels, shutterIdx[0], v -> shutterIdx[0] = v);
        addSeek(box, "曝光补偿", EV_LABELS, evIdx[0], v -> evIdx[0] = v);
        addSeek(box, "白平衡", WB_LABELS, wbIdx[0], v -> wbIdx[0] = v);
        addSeek(box, "对焦", FOCUS_LABELS, focusIdx[0], v -> focusIdx[0] = v);
        addSeek(box, "手动焦距", FOCUS_DIST_LABELS, manualIdx[0], v -> manualIdx[0] = v);

        if (video) {
            TextView note = new TextView(this);
            note.setText("录像时手动快门最长 1/30 秒，保证画面流畅、不掉帧。");
            note.setTextColor(0xFF9AA0B2);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            note.setPadding(0, dp(6), 0, dp(2));
            box.addView(note);
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle((video ? "录像" : "拍照") + " · 专业参数")
                .setView(scroll)
                .setPositiveButton("应用", (d, w) -> {
                    CaptureParams q = new CaptureParams();
                    q.manualExposure = isoIdx[0] > 0 || shutterIdx[0] > 0;
                    if (q.manualExposure) {
                        q.iso = isoIdx[0] > 0 ? ISO_VALUES[isoIdx[0]] : 200;
                        q.exposureNs = shutterIdx[0] > 0 ? shutterValues[shutterIdx[0]]
                                : (video ? 1_000_000_000L / 60 : 1_000_000_000L / 125);
                    } else {
                        q.ev = -2f + evIdx[0] * 0.1f;
                    }
                    q.wbAuto = wbIdx[0] == 0;
                    q.kelvin = q.wbAuto ? 5200 : 2300 + (wbIdx[0] - 1) * 100;
                    q.focusMode = focusIdx[0] == 0 ? CaptureParams.FOCUS_AUTO
                            : focusIdx[0] == 1 ? CaptureParams.FOCUS_INFINITY
                            : focusIdx[0] == 2 ? CaptureParams.FOCUS_MACRO
                            : CaptureParams.FOCUS_MANUAL;
                    q.diopter = manualIdx[0] / 10f;
                    if (video) {
                        videoProParams = q;
                        saveProParams("vpro_", q);
                    } else {
                        proParams = q;
                        saveProParams("pro_", q);
                    }
                    applyCameraParams();
                    updateExtraUi();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface IntSink {
        void set(int value);
    }

    private void addSeek(LinearLayout parent, String label, String[] labels, int index,
                         IntSink sink) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(2));

        TextView title = new TextView(this);
        title.setText(label + "：" + labels[Math.max(0, Math.min(labels.length - 1, index))]);
        title.setTextColor(0xFFE9EAF2);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(title);

        SeekBar bar = new SeekBar(this);
        bar.setMax(labels.length - 1);
        bar.setProgress(Math.max(0, Math.min(labels.length - 1, index)));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFF5C7A));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFF5C7A));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                title.setText(label + "：" + labels[progress]);
                sink.set(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        parent.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private static final int[] ISO_VALUES = {0, 100, 200, 400, 800, 1600, 3200, 6400};
    private static final String[] ISO_LABELS = {"自动", "100", "200", "400", "800", "1600",
            "3200", "6400"};
    private static final long[] SHUTTER_VALUES = {0,
            1_000_000_000L / 1000, 1_000_000_000L / 500, 1_000_000_000L / 250,
            1_000_000_000L / 125, 1_000_000_000L / 60, 1_000_000_000L / 30,
            1_000_000_000L / 15, 1_000_000_000L / 8, 1_000_000_000L / 4,
            500_000_000L, 1_000_000_000L, 2_000_000_000L};
    private static final String[] SHUTTER_LABELS = {"自动", "1/1000s", "1/500s", "1/250s",
            "1/125s", "1/60s", "1/30s", "1/15s", "1/8s", "1/4s", "1/2s", "1s", "2s"};
    /** 录像专用：最长只到 1/30 秒，避免掉帧。 */
    private static final long[] SHUTTER_VALUES_VIDEO = {0,
            1_000_000_000L / 1000, 1_000_000_000L / 500, 1_000_000_000L / 250,
            1_000_000_000L / 125, 1_000_000_000L / 60, 1_000_000_000L / 30};
    private static final String[] SHUTTER_LABELS_VIDEO = {"自动", "1/1000s", "1/500s", "1/250s",
            "1/125s", "1/60s", "1/30s"};
    private static final String[] EV_LABELS = buildEvLabels();
    private static final String[] FOCUS_LABELS = {"自动", "无穷远", "微距", "手动"};
    private static final String[] WB_LABELS = buildWbLabels();
    private static final String[] FOCUS_DIST_LABELS = buildFocusLabels();

    private static String[] buildEvLabels() {
        String[] a = new String[41];
        for (int i = 0; i < a.length; i++) {
            float v = -2f + i * 0.1f;
            a[i] = String.format(Locale.US, "%+.1f", v);
        }
        return a;
    }

    private static String[] buildWbLabels() {
        String[] a = new String[58];
        a[0] = "自动";
        for (int i = 1; i < a.length; i++) a[i] = (2300 + (i - 1) * 100) + "K";
        return a;
    }

    private static String[] buildFocusLabels() {
        String[] a = new String[101];
        for (int i = 0; i < a.length; i++) {
            float d = i / 10f;
            if (d < 0.05f) a[i] = "∞";
            else a[i] = String.format(Locale.US, "%.0fcm", 100f / d);
        }
        return a;
    }

    private static int isoIndexOf(int iso) {
        if (iso <= 0) return 0;
        int best = 1;
        for (int i = 1; i < ISO_VALUES.length; i++) {
            if (Math.abs(ISO_VALUES[i] - iso) < Math.abs(ISO_VALUES[best] - iso)) best = i;
        }
        return best;
    }

    private static int shutterIndexOf(long ns, long[] values) {
        if (ns <= 0) return 0;
        int best = 1;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - ns) < Math.abs(values[best] - ns)) best = i;
        }
        return best;
    }

    private static int shutterIndexOf(long ns) {
        return shutterIndexOf(ns, SHUTTER_VALUES);
    }

    private void saveProParams(String prefix, CaptureParams p) {
        prefs.edit()
                .putBoolean(prefix + "manual", p.manualExposure)
                .putInt(prefix + "iso", p.iso)
                .putLong(prefix + "shutter", p.exposureNs)
                .putFloat(prefix + "ev", p.ev)
                .putBoolean(prefix + "wb_auto", p.wbAuto)
                .putInt(prefix + "kelvin", p.kelvin)
                .putInt(prefix + "focus", p.focusMode)
                .putFloat(prefix + "diopter", p.diopter)
                .apply();
    }

    /**
     * 读取专业参数。拍照首次默认手动 200 / 1/125s（和旧版一致）；
     * 录像首次默认自动曝光 + 自动白平衡，开箱即用更稳。
     */
    private CaptureParams loadProParams(String prefix, boolean photoDefaults) {
        CaptureParams p = new CaptureParams();
        boolean first = !prefs.contains(prefix + "manual");
        p.manualExposure = prefs.getBoolean(prefix + "manual", photoDefaults);
        p.iso = prefs.getInt(prefix + "iso", 200);
        p.exposureNs = prefs.getLong(prefix + "shutter",
                photoDefaults ? 1_000_000_000L / 125 : 1_000_000_000L / 60);
        p.ev = prefs.getFloat(prefix + "ev", 0f);
        p.wbAuto = prefs.getBoolean(prefix + "wb_auto", !photoDefaults);
        p.kelvin = prefs.getInt(prefix + "kelvin", 5200);
        p.focusMode = prefs.getInt(prefix + "focus", CaptureParams.FOCUS_AUTO);
        p.diopter = prefs.getFloat(prefix + "diopter", 0f);
        if (first && photoDefaults) {
            p.manualExposure = true;
            p.iso = 200;
            p.exposureNs = 1_000_000_000L / 125;
            p.wbAuto = false;
            p.kelvin = 5200;
            p.focusMode = CaptureParams.FOCUS_AUTO;
        }
        return p;
    }

    // ------------------------------------------------------------------ 傻瓜场景

    private void showSceneDialog() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView tip = new TextView(this);
        tip.setText("点一下使用 · 长按调整参数 · 改过的会记在本机");
        tip.setTextColor(0xFF9AA0B2);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setPadding(dp(6), 0, dp(6), dp(8));
        list.addView(tip);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView add = pill("＋ 新增场景", false);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        add.setPadding(dp(12), dp(9), dp(12), dp(9));
        add.setOnClickListener(v -> {
            if (sceneDialog != null) sceneDialog.dismiss();
            showSceneEditor(null, true);
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        addLp.setMargins(dp(4), dp(2), dp(4), dp(6));
        addRow.addView(add, addLp);

        TextView edit = pill("调整当前", false);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        edit.setPadding(dp(12), dp(9), dp(12), dp(9));
        edit.setOnClickListener(v -> {
            if (sceneDialog != null) sceneDialog.dismiss();
            showSceneEditor(curScene(), false);
        });
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        editLp.setMargins(dp(4), dp(2), dp(4), dp(6));
        addRow.addView(edit, editLp);

        list.addView(addRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout row = null;
        for (int i = 0; i < sceneList.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            final ScenePreset sp = sceneList.get(i);
            String label = sp.name + (sp.custom ? " ·自" : (sp.edited ? " *" : ""));
            TextView b = pill(label, sp == curScene());
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setPadding(dp(4), dp(9), dp(4), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            b.setOnClickListener(v -> selectScene(sp));
            b.setOnLongClickListener(v -> {
                if (sceneDialog != null) sceneDialog.dismiss();
                showSceneEditor(sp, false);
                return true;
            });
            row.addView(b, lp);
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        sceneDialog = new AlertDialog.Builder(this)
                .setTitle((captureMode == MODE_VIDEO ? "录像" : "拍照") + " · 选择场景")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        sceneDialog.show();
    }

    private void selectScene(ScenePreset sp) {
        setCurScene(sp);
        applyCameraParams();
        updateExtraUi();
        toast(sp.name + " · " + sp.hint);
        if (sceneDialog != null) sceneDialog.dismiss();
    }

    /**
     * 场景参数编辑器：和“专业参数”同一组滑块，另外多了名字与保存/恢复默认。
     * 内置场景不能改名、可以恢复默认；自定义场景可改名、可删除。
     */
    private void showSceneEditor(ScenePreset base, boolean isNew) {
        final boolean custom = base != null && base.custom;
        final boolean builtin = base != null && !base.custom;

        final int[] isoIdx = {base == null ? 2 : (base.manual ? Math.max(1, isoIndexOf(base.iso)) : 0)};
        final int[] shIdx = {base == null ? 4 : (base.manual ? Math.max(1, shutterIndexOf(base.exposureNs)) : 0)};
        final int[] evIdx = {Math.round(((base == null ? 0f : base.ev) + 2f) / 0.1f)};
        final int[] wbIdx = {base == null || base.wbAuto ? 0 : kelvinIndexOf(base.kelvin)};
        final int[] focusIdx = {base == null ? 0
                : base.focusMode == CaptureParams.FOCUS_AUTO ? 0
                : base.focusMode == CaptureParams.FOCUS_INFINITY ? 1
                : base.focusMode == CaptureParams.FOCUS_MACRO ? 2 : 3};
        final int[] distIdx = {base == null ? 0
                : Math.round(Math.max(0f, Math.min(10f, base.diopter)) * 10f)};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        EditText nameEt = new EditText(this);
        nameEt.setHint("场景名称，例如 海边人像");
        nameEt.setSingleLine(true);
        nameEt.setText(base == null ? "" : base.name);
        nameEt.setEnabled(isNew || custom);
        box.addView(nameEt, new LinearLayout.LayoutParams(MATCH, WRAP));

        EditText hintEt = new EditText(this);
        hintEt.setHint("一句话提示（可空）");
        hintEt.setSingleLine(true);
        hintEt.setText(base == null ? "" : base.hint);
        box.addView(hintEt, new LinearLayout.LayoutParams(MATCH, WRAP));

        addSeek(box, "ISO", ISO_LABELS, isoIdx[0], v -> isoIdx[0] = v);
        addSeek(box, "快门", SHUTTER_LABELS, shIdx[0], v -> shIdx[0] = v);
        addSeek(box, "曝光补偿", EV_LABELS, evIdx[0], v -> evIdx[0] = v);
        addSeek(box, "白平衡", WB_LABELS, wbIdx[0], v -> wbIdx[0] = v);
        addSeek(box, "对焦", FOCUS_LABELS, focusIdx[0], v -> focusIdx[0] = v);
        addSeek(box, "手动焦距", FOCUS_DIST_LABELS, distIdx[0], v -> distIdx[0] = v);

        TextView note = new TextView(this);
        String noteText = "ISO 和快门都选“自动”时按自动曝光，只叠加曝光补偿；任一手动即切到手动曝光。";
        if (captureMode == MODE_VIDEO) {
            noteText += "\n录像会把手动快门限制在 1/30 秒以内，保证画面流畅。";
        }
        note.setText(noteText);
        note.setTextColor(0xFF9AA0B2);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setPadding(0, dp(6), 0, dp(2));
        box.addView(note);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle((captureMode == MODE_VIDEO ? "录像 · " : "拍照 · ")
                        + (isNew ? "新增场景"
                        : (custom ? "编辑自定义场景" : "调整场景参数 · " + base.name)))
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    String nm = nameEt.getText().toString().trim();
                    String ht = hintEt.getText().toString().trim();
                    ScenePreset target;
                    if (isNew) {
                        if (nm.isEmpty()) nm = "自定义场景" + (sceneList.size() + 1);
                        target = new ScenePreset("c" + System.currentTimeMillis(), nm,
                                ht.isEmpty() ? "自定义场景" : ht, true, 200,
                                1_000_000_000L / 125, 0f, 5200, true,
                                CaptureParams.FOCUS_AUTO, 0f);
                        target.custom = true;
                    } else {
                        target = base;
                        if (custom) {
                            if (!nm.isEmpty()) target.name = nm;
                            if (!ht.isEmpty()) target.hint = ht;
                        } else if (!ht.isEmpty()) {
                            target.hint = ht;
                        }
                    }
                    target.manual = isoIdx[0] > 0 || shIdx[0] > 0;
                    target.iso = isoIdx[0] > 0 ? ISO_VALUES[isoIdx[0]] : 200;
                    target.exposureNs = shIdx[0] > 0 ? SHUTTER_VALUES[shIdx[0]]
                            : 1_000_000_000L / 125;
                    target.ev = -2f + evIdx[0] * 0.1f;
                    target.wbAuto = wbIdx[0] == 0;
                    target.kelvin = target.wbAuto ? 5200 : 2300 + (wbIdx[0] - 1) * 100;
                    target.focusMode = focusIdx[0] == 0 ? CaptureParams.FOCUS_AUTO
                            : focusIdx[0] == 1 ? CaptureParams.FOCUS_INFINITY
                            : focusIdx[0] == 2 ? CaptureParams.FOCUS_MACRO
                            : CaptureParams.FOCUS_MANUAL;
                    target.diopter = distIdx[0] / 10f;
                    if (builtin) {
                        target.edited = target.differsFrom(ScenePreset.defaultById(target.id));
                    }
                    if (isNew) {
                        sceneStore.add(target);
                    } else {
                        sceneStore.persist();
                    }
                    setCurScene(target);
                    applyCameraParams();
                    updateExtraUi();
                    toast(isNew ? "已新增场景 · " + target.name : "已保存 · " + target.name);
                })
                .setNegativeButton("取消", null);

        if (custom) {
            builder.setNeutralButton("删除", (d, w) -> confirmDeleteScene(base));
        } else if (builtin) {
            builder.setNeutralButton("恢复默认", (d, w) -> {
                sceneStore.reset(base);
                if (curScene() == base) applyCameraParams();
                updateExtraUi();
                toast("已恢复默认参数 · " + base.name);
            });
        }
        builder.show();
    }

    private void confirmDeleteScene(ScenePreset sp) {
        new AlertDialog.Builder(this)
                .setTitle("删除场景")
                .setMessage("确定删除「" + sp.name + "」吗？")
                .setPositiveButton("删除", (d, w) -> {
                    sceneStore.remove(sp);
                    if (curScene() == sp) {
                        setCurScene(sceneList.isEmpty()
                                ? ScenePreset.ALL[0].copy() : sceneList.get(0));
                    }
                    applyCameraParams();
                    updateExtraUi();
                    toast("已删除 · " + sp.name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static int kelvinIndexOf(int kelvin) {
        return Math.max(1, Math.min(57, (kelvin - 2300) / 100 + 1));
    }

    private AlertDialog sceneDialog;

    // ------------------------------------------------------------------ 相册处理

    private void pickFromGallery() {
        if (processing) {
            toast("正在处理，请稍候");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            Log.e(TAG, "pick", t);
            toast("无法打开相册");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        pendingGalleryUri = uri;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && !hasPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        beginGallery(uri);
    }

    private void beginGallery(Uri uri) {
        if (processing || capturing) {
            toast("正在处理，请稍候");
            return;
        }
        final StylePreset st = style;
        final float in = intensity;
        processing = true;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        processingCancel = cancel;
        if (galleryBtn != null) galleryBtn.setAlpha(0.4f);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("卡通化处理中")
                .setMessage("准备中…")
                .setNegativeButton("取消", (d, w) -> cancel.set(true))
                .setCancelable(false)
                .create();
        progressDialog = dialog;
        dialog.show();

        final boolean spr = spritesOn;
        worker.execute(() -> GalleryProcessor.process(this, uri, null, st, in, spr, cancel,
                new GalleryProcessor.Listener() {
                    @Override
                    public void onProgress(float p, String label) {
                        ui.post(() -> {
                            if (progressDialog != null && progressDialog.isShowing()) {
                                progressDialog.setMessage(label);
                            }
                        });
                    }

                    @Override
                    public void onDone(String message, Bitmap preview) {
                        ui.post(() -> {
                            finishProcessing();
                            if (isFinishing() || isDestroyed()) return;
                            if (preview != null) showPreview(message, preview);
                            else toast(message);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        ui.post(() -> {
                            finishProcessing();
                            if (isFinishing() || isDestroyed()) return;
                            toast(message);
                        });
                    }
                }));
    }

    private void finishProcessing() {
        processing = false;
        processingCancel = null;
        dismissProgress();
        if (galleryBtn != null) galleryBtn.setAlpha(1f);
    }

    private void dismissProgress() {
        AlertDialog d = progressDialog;
        progressDialog = null;
        if (d != null) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private void showPreview(String message, Bitmap bmp) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bmp);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("完成")
                .setMessage(message)
                .setView(iv)
                .setPositiveButton("好", (d, w) -> iv.setImageDrawable(null))
                .setOnDismissListener(d -> iv.setImageDrawable(null))
                .show();
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
        if (requestCode == REQ_WRITE_PHOTO) {
            boolean granted = hasPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            boolean go = pendingPhoto;
            pendingPhoto = false;
            if (granted) {
                if (go) takePhoto();
            } else {
                toast("没有存储权限，无法保存照片");
            }
            return;
        }
        if (requestCode == REQ_WRITE) {
            Uri uri = pendingGalleryUri;
            pendingGalleryUri = null;
            if (hasPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                if (uri != null) beginGallery(uri);
            } else {
                toast("没有存储权限，无法保存处理结果");
            }
            return;
        }
        if (requestCode != REQ_PERMS) return;
        micAllowed = hasPerm(Manifest.permission.RECORD_AUDIO);
        if (hasPerm(Manifest.permission.CAMERA)) {
            maybeStartCamera();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("需要相机权限")
                    .setMessage("开启相机权限后才能使用呆瓜相机。")
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
        if (capturing) return;
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
        String name = "Daigua_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        OutputTarget out = new OutputTarget();
        out.name = name;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DaiguaCamera");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("无法创建视频文件");
            out.uri = uri;
            out.pfd = cr.openFileDescriptor(uri, "w");
            if (out.pfd == null) throw new Exception("无法写入视频文件");
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "DaiguaCamera");
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
                toast("已保存到相册 · Movies/DaiguaCamera");
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
