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
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.media.ExifInterface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
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
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
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
    private static final int REQ_PICK_SCENE = 34;
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
    private TextView extraActionBtn;
    private TextView flashBtn;
    private TextView styleBtn;
    private FrameLayout zoomBox;
    private ZoomDial zoomDial;
    private TextView zoomChip;
    private TextView zoomCollapse;
    private boolean zoomCollapsed;
    private LinearLayout panelContent;
    private TextView panelHandle;
    private boolean panelCollapsed;
    /** 点击预览设置对焦点时，用来判断是点击还是拖动。 */
    private float tapDownX, tapDownY;
    private boolean tapMoved;
    private View focusRing;

    /** 是否在非正常风格下额外保存一份未加风格的原素材。 */
    private boolean saveOriginal;
    /** 本次录像是否把原始画面写进编码器（开启保存原素材且非正常风格）。 */
    private boolean recorderPlain;
    private StylePreset recStyle = StylePreset.ALL[0];
    private float recIntensity = 0.95f;
    private boolean recSprites;

    // 闪光灯：自动 / 常开 / 常闭
    private static final int FLASH_AUTO = 0;
    private static final int FLASH_ON = 1;
    private static final int FLASH_OFF = 2;
    private static final String FLASH_ICON = "\u26A1\uFE0E";
    private int flashMode = FLASH_AUTO;
    private ScaleGestureDetector scaleDetector;
    private float zoomStart = 1f;
    private boolean pinching;

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
        flashMode = Math.max(FLASH_AUTO, Math.min(FLASH_OFF, prefs.getInt("flash", FLASH_AUTO)));
        saveOriginal = prefs.getBoolean("save_original", false);
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
        applyFlashState();
        updateFlashUi();

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
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(10);
        dotLp.leftMargin = dp(2);
        topBar.addView(dot, dotLp);

        flashBtn = circleButton(FLASH_ICON, 17, 40);
        flashBtn.setContentDescription("闪光灯：自动");
        flashBtn.setOnClickListener(v -> cycleFlash());
        LinearLayout.LayoutParams flashLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        topBar.addView(flashBtn, flashLp);

        View topSpacer = new View(this);
        topBar.addView(topSpacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        TextView docsBtn = circleButton("说明", 11, 40);
        docsBtn.setContentDescription("操作手册与版本更新说明");
        docsBtn.setOnClickListener(v -> showDocsDialog());
        LinearLayout.LayoutParams docsLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        docsLp.rightMargin = dp(8);
        topBar.addView(docsBtn, docsLp);

        spriteBtn = circleButton("✦", 17, 40);
        spriteBtn.setAlpha(spritesOn ? 1f : 0.38f);
        spriteBtn.setContentDescription("小精灵开关");
        spriteBtn.setOnClickListener(v -> toggleSprites());
        topBar.addView(spriteBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

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

        // 右侧焦距控制盘：圆盘拖动变焦；可收起成右侧小圆钮，把画面尽量让出来
        zoomBox = new FrameLayout(this);
        zoomDial = new ZoomDial(this);
        zoomDial.setRange(1f, 100f);
        zoomDial.setListener((zoom, fromUser) -> {
            if (cameraController != null) cameraController.setZoom(zoom);
            updateZoomChip();
        });
        zoomBox.addView(zoomDial, new FrameLayout.LayoutParams(dp(128), dp(152)));

        zoomCollapse = circleButton("›", 15, 24);
        zoomCollapse.setContentDescription("收起焦距控制盘");
        zoomCollapse.setOnClickListener(v -> setZoomCollapsed(true));
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        colLp.gravity = Gravity.TOP | Gravity.END;
        colLp.topMargin = dp(2);
        colLp.rightMargin = dp(2);
        zoomBox.addView(zoomCollapse, colLp);

        zoomChip = circleButton("1.0×", 10, 50);
        zoomChip.setContentDescription("展开焦距控制盘");
        zoomChip.setOnClickListener(v -> setZoomCollapsed(false));
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(dp(50), dp(50));
        chipLp.gravity = Gravity.TOP | Gravity.END;
        zoomChip.setVisibility(View.GONE);
        zoomBox.addView(zoomChip, chipLp);

        FrameLayout.LayoutParams zoomLp = new FrameLayout.LayoutParams(dp(128), dp(152));
        zoomLp.gravity = Gravity.TOP | Gravity.END;
        zoomLp.topMargin = dp(64);
        zoomLp.rightMargin = dp(8);
        root.addView(zoomBox, zoomLp);

        // 点击对焦的白色圆环
        focusRing = new View(this);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setColor(Color.TRANSPARENT);
        ringBg.setStroke(Math.max(1, Math.round(dp(1.6f))), 0xEEFFFFFF);
        focusRing.setBackground(ringBg);
        focusRing.setVisibility(View.GONE);
        root.addView(focusRing, new FrameLayout.LayoutParams(dp(74), dp(74)));

        zoomCollapsed = prefs.getBoolean("zoom_collapsed", false);
        applyZoomCollapsed();

        // 底部控制面板：深色圆角卡片，只保留三层核心选择
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xF20B0B10);
        panelBg.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
        panelBg.setStroke(dp(1), 0x1FFFFFFF);
        bottomPanel.setBackground(panelBg);
        bottomPanel.setPadding(dp(16), dp(10), dp(16), dp(8));

        // 收起把手：点一下把整块控制区缩到底部，只留一条细把手
        LinearLayout handleRow = new LinearLayout(this);
        handleRow.setOrientation(LinearLayout.VERTICAL);
        handleRow.setGravity(Gravity.CENTER_HORIZONTAL);
        handleRow.setPadding(0, dp(2), 0, dp(2));
        View handleBar = new View(this);
        GradientDrawable handleBarBg = new GradientDrawable();
        handleBarBg.setCornerRadius(dp(3));
        handleBarBg.setColor(0x66FFFFFF);
        handleBar.setBackground(handleBarBg);
        handleRow.addView(handleBar, new LinearLayout.LayoutParams(dp(40), dp(4)));
        panelHandle = new TextView(this);
        panelHandle.setText("收起");
        panelHandle.setTextColor(0x99FFFFFF);
        panelHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        LinearLayout.LayoutParams handleTextLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        handleTextLp.topMargin = dp(2);
        handleRow.addView(panelHandle, handleTextLp);
        handleRow.setOnClickListener(v -> setPanelCollapsed(!panelCollapsed));
        bottomPanel.addView(handleRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        panelContent = new LinearLayout(this);
        panelContent.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.addView(panelContent, new LinearLayout.LayoutParams(MATCH, WRAP));
        panelCollapsed = prefs.getBoolean("panel_collapsed", false);

        // 第一层：录像 / 拍照（通栏分段控件）
        LinearLayout captureSeg = new LinearLayout(this);
        captureSeg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(0x14FFFFFF);
        segBg.setCornerRadius(dp(21));
        captureSeg.setBackground(segBg);
        captureSeg.setPadding(dp(4), dp(4), dp(4), dp(4));

        videoPill = segment("录像", true);
        videoPill.setOnClickListener(v -> setCaptureMode(MODE_VIDEO));
        captureSeg.addView(videoPill, new LinearLayout.LayoutParams(0, dp(34), 1f));

        photoPill = segment("拍照", false);
        photoPill.setOnClickListener(v -> setCaptureMode(MODE_PHOTO));
        LinearLayout.LayoutParams photoSegLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        photoSegLp.leftMargin = dp(4);
        captureSeg.addView(photoPill, photoSegLp);
        panelContent.addView(captureSeg, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 第二层：普通 / 专业 / 傻瓜（录像和拍照共用）
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setPadding(0, dp(6), 0, 0);

        subNormalPill = modeTab("普通", true);
        subNormalPill.setOnClickListener(v -> setSub(SUB_NORMAL));
        subRow.addView(subNormalPill, new LinearLayout.LayoutParams(0, WRAP, 1f));

        subProPill = modeTab("专业", false);
        subProPill.setOnClickListener(v -> setSub(SUB_PRO));
        subRow.addView(subProPill, new LinearLayout.LayoutParams(0, WRAP, 1f));

        subIdiotPill = modeTab("傻瓜", false);
        subIdiotPill.setOnClickListener(v -> setSub(SUB_IDIOT));
        subRow.addView(subIdiotPill, new LinearLayout.LayoutParams(0, WRAP, 1f));

        panelContent.addView(subRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 第三层：风格 + 参数/场景入口
        extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        extraRow.setGravity(Gravity.CENTER_VERTICAL);
        extraRow.setPadding(0, dp(8), 0, 0);

        styleBtn = pill("风格 · " + style.name + " ▾", false);
        styleBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        styleBtn.setPadding(dp(12), dp(5), dp(12), dp(6));
        styleBtn.setContentDescription("选择卡通风格与强度");
        styleBtn.setSingleLine(true);
        styleBtn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        styleBtn.setMaxWidth(dp(116));
        styleBtn.setOnClickListener(v -> showStyleDialog());
        extraRow.addView(styleBtn, new LinearLayout.LayoutParams(WRAP, WRAP));

        View extraSpacer = new View(this);
        extraRow.addView(extraSpacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        extraActionBtn = pill("参数 ▾", false);
        extraActionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        extraActionBtn.setPadding(dp(12), dp(5), dp(12), dp(6));
        extraActionBtn.setSingleLine(true);
        extraActionBtn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        extraActionBtn.setMaxWidth(dp(168));
        extraActionBtn.setOnClickListener(v -> onExtraAction());
        extraActionBtn.setVisibility(View.GONE);
        extraRow.addView(extraActionBtn, new LinearLayout.LayoutParams(WRAP, WRAP));

        panelContent.addView(extraRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 快门行：相册在左，快门居中，切换镜头在右
        LinearLayout recordRow = new LinearLayout(this);
        recordRow.setOrientation(LinearLayout.HORIZONTAL);
        recordRow.setGravity(Gravity.CENTER_VERTICAL);
        recordRow.setPadding(0, dp(10), 0, 0);

        galleryBtn = circleButton("相册", 10.5f, 46);
        galleryBtn.setContentDescription("相册卡通化");
        galleryBtn.setOnClickListener(v -> pickFromGallery());
        recordRow.addView(galleryBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));

        View leftSpacer = new View(this);
        recordRow.addView(leftSpacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

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
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(dp(58), dp(58));
        innerLp.gravity = Gravity.CENTER;
        recordBtn.addView(recordInner, innerLp);
        recordRow.addView(recordBtn, new LinearLayout.LayoutParams(dp(76), dp(76)));

        View rightSpacer = new View(this);
        recordRow.addView(rightSpacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        flipBtn = circleButton("⇄", 20, 46);
        flipBtn.setContentDescription("切换前后摄像头");
        flipBtn.setOnClickListener(v -> flipCamera());
        recordRow.addView(flipBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));

        panelContent.addView(recordRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        applyPanelCollapsed();

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(MATCH, WRAP);
        panelLp.gravity = Gravity.BOTTOM;
        root.addView(bottomPanel, panelLp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            topBar.setPadding(dp(16), top + dp(8), dp(16), dp(6));
            bottomPanel.setPadding(dp(16), dp(10), dp(16), bottom + dp(10));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) recBadge.getLayoutParams();
            lp.topMargin = top + dp(56);
            recBadge.setLayoutParams(lp);
            if (zoomBox != null) {
                FrameLayout.LayoutParams zlp = (FrameLayout.LayoutParams) zoomBox.getLayoutParams();
                zlp.topMargin = top + dp(64);
                zoomBox.setLayoutParams(zlp);
            }
            return insets;
        });

        // 双指捏合 / 张开调整数码变焦，拍照和录像都生效
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        if (cameraController == null) return false;
                        if (cameraController.maxZoom() <= 1.01f) {
                            if (cameraController.isZoomKnown()) toast("这个摄像头不支持变焦");
                            return false;
                        }
                        zoomStart = cameraController.getZoom();
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (cameraController == null) return true;
                        // getScaleFactor() 是“相对上一次事件”的增量，必须累乘，
                        // 直接拿起始值相乘会导致倍数来回跳、捏合不跟手。
                        zoomStart *= detector.getScaleFactor();
                        cameraController.setZoom(zoomStart);
                        zoomStart = cameraController.getZoom();
                        syncZoomUi();
                        return true;
                    }
                });

        // 触摸：给小精灵和周围卡通景物加反应（按钮区域会先消费事件）
        root.setOnTouchListener((v, e) -> {
            if (scaleDetector != null) scaleDetector.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                pinching = true;
                // 结束单指抚摸，避免小精灵跟着第二根手指漂移
                renderer.onTouch(0.5f, 0.5f, 2);
            }
            if (pinching || e.getPointerCount() > 1) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    pinching = false;
                }
                return true;
            }
            float w = v.getWidth();
            float h = v.getHeight();
            if (w <= 0 || h <= 0) return false;
            float u = e.getX() / w;
            float vv = 1f - e.getY() / h;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchPost = SystemClock.elapsedRealtime();
                    tapDownX = e.getX();
                    tapDownY = e.getY();
                    tapMoved = false;
                    renderer.onTouch(u, vv, 0);
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - tapDownX) + Math.abs(e.getY() - tapDownY) > dp(14)) {
                        tapMoved = true;
                    }
                    if (SystemClock.elapsedRealtime() - lastTouchPost > 40) {
                        lastTouchPost = SystemClock.elapsedRealtime();
                        renderer.onTouch(u, vv, 1);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    renderer.onTouch(u, vv, 2);
                    // 轻点预览：设置对焦点（拖动、捏合都不算）
                    if (!tapMoved && !pinching && cameraController != null
                            && cameraController.focusAt(e.getX() / w, e.getY() / h, mirror)) {
                        showFocusRing(e.getX(), e.getY());
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    renderer.onTouch(u, vv, 2);
                    tapMoved = true;
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

    // ------------------------------------------------------------------ 闪光灯 / 变焦

    private void cycleFlash() {
        if (cameraController != null && cameraController.isFlashKnown()
                && !cameraController.hasFlash()) {
            toast("这台相机没有闪光灯");
            return;
        }
        flashMode = (flashMode + 1) % 3;
        prefs.edit().putInt("flash", flashMode).apply();
        applyFlashState();
        updateFlashUi();
        if (flashMode == FLASH_ON && captureMode == MODE_VIDEO) {
            toast("闪光灯：常开（录像时持续补光）");
        } else if (flashMode == FLASH_ON) {
            toast("闪光灯：常开（拍照时每次都闪）");
        } else if (flashMode == FLASH_AUTO) {
            toast(captureMode == MODE_VIDEO
                    ? "闪光灯：自动（录像不复位，需要补光请选常开）"
                    : "闪光灯：自动（光线不足时自动闪）");
        } else {
            toast("闪光灯：常闭");
        }
    }

    private void applyFlashState() {
        if (cameraController != null) {
            cameraController.setFlash(flashMode, captureMode == MODE_VIDEO);
        }
    }

    private void updateFlashUi() {
        if (flashBtn == null) return;
        boolean known = cameraController != null && cameraController.isFlashKnown();
        boolean supported = !known || cameraController.hasFlash();
        String label = flashMode == FLASH_AUTO ? "自动" : (flashMode == FLASH_ON ? "常开" : "常闭");
        flashBtn.setText(flashMode == FLASH_AUTO ? FLASH_ICON
                : FLASH_ICON + (flashMode == FLASH_ON ? "开" : "关"));
        flashBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, flashMode == FLASH_AUTO ? 17 : 11.5f);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (flashMode == FLASH_ON && supported) {
            bg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            bg.setColors(new int[]{0xFFFF5C7A, 0xFFFF9A5C});
        } else {
            bg.setColor(0x40000000);
            bg.setStroke(dp(1), 0x33FFFFFF);
        }
        flashBtn.setBackground(bg);
        flashBtn.setAlpha(!supported ? 0.25f : (flashMode == FLASH_OFF ? 0.5f : 1f));
        flashBtn.setContentDescription("闪光灯：" + label + (supported ? "" : "（不可用）"));
    }

    /** 把相机当前的变焦值同步到焦距控制盘（捏合、切镜头、相机就绪时调用）。 */
    private void syncZoomUi() {
        if (zoomDial == null || cameraController == null) return;
        float max = Math.min(100f, Math.max(1f, cameraController.maxZoom()));
        zoomDial.setRange(1f, max);
        zoomDial.setZoom(cameraController.getZoom());
        updateZoomChip();
    }

    private void updateZoomChip() {
        if (zoomChip == null || zoomDial == null) return;
        float z = zoomDial.getZoom();
        if (cameraController != null) z = cameraController.getZoom();
        zoomChip.setText(String.format(Locale.US, "%.1f×", z));
    }

    private void setZoomCollapsed(boolean collapsed) {
        zoomCollapsed = collapsed;
        prefs.edit().putBoolean("zoom_collapsed", collapsed).apply();
        applyZoomCollapsed();
    }

    private void applyZoomCollapsed() {
        if (zoomDial == null || zoomBox == null) return;
        zoomDial.setVisibility(zoomCollapsed ? View.GONE : View.VISIBLE);
        zoomCollapse.setVisibility(zoomCollapsed ? View.GONE : View.VISIBLE);
        zoomChip.setVisibility(zoomCollapsed ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) zoomBox.getLayoutParams();
        if (lp != null) {
            lp.width = dp(zoomCollapsed ? 50 : 128);
            lp.height = dp(zoomCollapsed ? 50 : 152);
            zoomBox.setLayoutParams(lp);
        }
        updateZoomChip();
    }

    private void setPanelCollapsed(boolean collapsed) {
        panelCollapsed = collapsed;
        prefs.edit().putBoolean("panel_collapsed", collapsed).apply();
        applyPanelCollapsed();
    }

    private void applyPanelCollapsed() {
        if (panelContent == null) return;
        panelContent.setVisibility(panelCollapsed ? View.GONE : View.VISIBLE);
        if (panelHandle != null) {
            panelHandle.setText(panelCollapsed ? "展开" : "收起");
        }
    }

    /** 轻点预览后显示对焦圈，短暂停留再淡出。 */
    private void showFocusRing(float x, float y) {
        if (focusRing == null) return;
        ui.removeCallbacks(hideFocusRunnable);
        int size = dp(74);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = Math.round(x - size / 2f);
        lp.topMargin = Math.round(y - size / 2f);
        focusRing.setLayoutParams(lp);
        focusRing.animate().cancel();
        focusRing.setAlpha(1f);
        focusRing.setScaleX(1.45f);
        focusRing.setScaleY(1.45f);
        focusRing.setVisibility(View.VISIBLE);
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        ui.postDelayed(hideFocusRunnable, 1500);
    }

    private final Runnable hideFocusRunnable = () -> {
        if (focusRing == null) return;
        focusRing.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (focusRing != null && focusRing.getAlpha() < 0.05f) {
                focusRing.setVisibility(View.GONE);
            }
        }).start();
    };

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
            boolean active = i < StylePreset.ALL.length && StylePreset.ALL[i] == preset;
            chip.setBackground(chipBg(active));
            chip.setTextColor(active ? 0xFFFFFFFF : 0xFFE9EAF2);
            chip.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        prefs.edit().putString("style", preset.id).apply();
        if (styleBtn != null) styleBtn.setText("风格 · " + preset.name + " ▾");
        updateIntensityEnabled();
    }

    /**
     * 风格面板：一屏选完风格 + 强度，选完即生效，点“完成”收起。
     * 底部不再常驻一排风格按钮，界面清爽很多。
     */
    private void showStyleDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(10), dp(18), dp(4));

        TextView tip = new TextView(this);
        tip.setText("点一下即生效 · 拖下面滑块可调卡通强度");
        tip.setTextColor(0xFF9AA0B2);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setPadding(0, 0, 0, dp(8));
        box.addView(tip);

        final List<TextView> local = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < StylePreset.ALL.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            final StylePreset preset = StylePreset.ALL[i];
            TextView b = pill(preset.name, preset == style);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            b.setPadding(dp(4), dp(9), dp(4), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            b.setOnClickListener(v -> selectStyle(preset));
            row.addView(b, lp);
            local.add(b);
        }
        chips.clear();
        chips.addAll(local);

        LinearLayout intRow = new LinearLayout(this);
        intRow.setOrientation(LinearLayout.VERTICAL);
        intRow.setPadding(0, dp(12), 0, 0);

        intensityValue = new TextView(this);
        intensityValue.setTextColor(0xFFE9EAF2);
        intensityValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intensityValue.setText("卡通强度：" + Math.round(intensity * 100) + "%");
        intRow.addView(intensityValue);

        intensityBar = new SeekBar(this);
        intensityBar.setMax(100);
        intensityBar.setProgress(Math.round(intensity * 100));
        intensityBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFF5C7A));
        intensityBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFF5C7A));
        intensityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                intensity = progress / 100f;
                if (intensityValue != null) {
                    intensityValue.setText("卡通强度：" + progress + "%");
                }
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
        intRow.addView(intensityBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        box.addView(intRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout originalRow = new LinearLayout(this);
        originalRow.setOrientation(LinearLayout.VERTICAL);
        originalRow.setPadding(0, dp(12), 0, 0);

        Switch originalSw = new Switch(this);
        originalSw.setText("同时保存原素材");
        originalSw.setTextColor(0xFFE9EAF2);
        originalSw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        originalSw.setChecked(saveOriginal);
        originalSw.setOnCheckedChangeListener((b, checked) -> {
            saveOriginal = checked;
            prefs.edit().putBoolean("save_original", checked).apply();
        });
        originalRow.addView(originalSw);

        TextView originalTip = new TextView(this);
        originalTip.setText("非正常风格时，拍照/录像会各存两份：一份原素材，一份叠加风格。正常风格只存一份。");
        originalTip.setTextColor(0xFF9AA0B2);
        originalTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        originalTip.setPadding(dp(2), 0, dp(2), 0);
        originalRow.addView(originalTip);
        box.addView(originalRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        updateIntensityEnabled();

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("卡通风格")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .create();
        dlg.setOnDismissListener(d -> {
            prefs.edit().putFloat("intensity", intensity).apply();
            chips.clear();
            intensityBar = null;
            intensityValue = null;
            updateExtraUi();
        });
        dlg.show();
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
        // 非正常风格且开启了“保存原素材”：先把相机原始 JPEG 原样存一份
        final boolean saveRaw = saveOriginal && !st.normal;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        processing = true;
        processingCancel = cancel;

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(st.normal ? "保存照片" : "照片卡通化")
                .setMessage("处理中…")
                .setNegativeButton("取消", (d, w) -> cancel.set(true))
                .setCancelable(false)
                .create();
        progressDialog = dialog;
        dialog.show();

        worker.execute(() -> {
            if (saveRaw) {
                try {
                    GalleryProcessor.saveOriginalJpeg(this, jpeg);
                } catch (Throwable t) {
                    Log.e(TAG, "save original jpeg", t);
                }
            }
            GalleryProcessor.processJpegBytes(this, jpeg, st, in, spr, cancel,
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
                        if (preview != null) preview.recycle();
                        ui.post(() -> {
                            dismissProgress();
                            processing = false;
                            processingCancel = null;
                            capturing = false;
                            updateShutterBusy();
                            // 拍完直接进相册，不再弹预览或“已保存”提示
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
                });
        });
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
                        GalleryProcessor.saveToGallery(MainActivity.this, frame);
                        ui.post(() -> {
                            previewFallbackBusy = false;
                            processing = false;
                            frame.recycle();
                            // 兜底也只安静地存进相册，失败时才提示
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

    /** 圆形小按钮：顶栏的闪光灯 / 说明 / 小精灵，以及快门行的相册 / 翻转头。 */
    private TextView circleButton(String text, float sp, int sizeDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFFF4F5FA);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x40000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        t.setBackground(bg);
        return t;
    }

    /** 第二层模式标签：普通 / 专业 / 傻瓜，等分宽度，选中时浅色胶囊。 */
    private TextView modeTab(String text, boolean active) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(7), 0, dp(8));
        t.setBackground(modeTabBg(active));
        t.setTextColor(active ? 0xFFF4F5FA : 0xFF9AA0B2);
        if (active) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable modeTabBg(boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(9));
        if (active) {
            d.setColor(0x1FFFFFFF);
            d.setStroke(dp(1), 0x2EFFFFFF);
        } else {
            d.setColor(Color.TRANSPARENT);
        }
        return d;
    }

    private void styleModeTab(TextView t, boolean active) {
        if (t == null) return;
        t.setBackground(modeTabBg(active));
        t.setTextColor(active ? 0xFFF4F5FA : 0xFF9AA0B2);
        t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
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
        applyFlashState();
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
        styleModeTab(subNormalPill, sub == SUB_NORMAL);
        styleModeTab(subProPill, sub == SUB_PRO);
        styleModeTab(subIdiotPill, sub == SUB_IDIOT);

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
        extraRow.setVisibility(View.VISIBLE);
        if (styleBtn != null) styleBtn.setText("风格 · " + style.name + " ▾");

        int sub = currentSub();
        if (sub == SUB_PRO) {
            extraActionBtn.setVisibility(View.VISIBLE);
            boolean video = captureMode == MODE_VIDEO;
            CaptureParams p = video ? videoProParams : proParams;
            String s = p.summary();
            if (video && p.manualExposure && p.exposureNs > VIDEO_MAX_EXPOSURE_NS) {
                s += " · 快门限 1/30s";
            }
            extraActionBtn.setText("专业参数 · " + s + " ▾");
        } else if (sub == SUB_IDIOT) {
            extraActionBtn.setVisibility(View.VISIBLE);
            ScenePreset sp = curScene();
            String mark = sp.custom ? " ·自" : (sp.edited ? " ·调" : "");
            extraActionBtn.setText(sp.name + mark + " ▾");
        } else {
            extraActionBtn.setVisibility(View.GONE);
        }
    }

    private void onExtraAction() {
        if (currentSub() == SUB_PRO) {
            showProDialog();
        } else if (currentSub() == SUB_IDIOT) {
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

        AlertDialog proDialog = new AlertDialog.Builder(this)
                .setTitle((video ? "录像" : "拍照") + " · 专业参数")
                .setView(scroll)
                .setPositiveButton("应用", (d, w) -> {
                    CaptureParams q = proSlidersToParams(video, isoIdx[0], shutterIdx[0],
                            evIdx[0], wbIdx[0], focusIdx[0], manualIdx[0]);
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
                .setNeutralButton("存为场景", (d, w) -> promptSaveScene(proSlidersToParams(video,
                        isoIdx[0], shutterIdx[0], evIdx[0], wbIdx[0], focusIdx[0], manualIdx[0])))
                .setNegativeButton("取消", null)
                .create();
        proDialog.setOnDismissListener(d -> updateExtraUi());
        proDialog.show();
    }

    /** 把专业参数面板里六个滑块的位置换算成一份完整参数。 */
    private CaptureParams proSlidersToParams(boolean video, int isoIdx, int shutterIdx, int evIdx,
                                             int wbIdx, int focusIdx, int manualIdx) {
        CaptureParams q = new CaptureParams();
        q.manualExposure = isoIdx > 0 || shutterIdx > 0;
        if (q.manualExposure) {
            q.iso = isoIdx > 0 ? ISO_VALUES[isoIdx] : 200;
            q.exposureNs = shutterIdx > 0 ? (video ? SHUTTER_VALUES_VIDEO : SHUTTER_VALUES)[shutterIdx]
                    : (video ? 1_000_000_000L / 60 : 1_000_000_000L / 125);
        } else {
            q.ev = -2f + evIdx * 0.1f;
        }
        q.wbAuto = wbIdx == 0;
        q.kelvin = q.wbAuto ? 5200 : 2300 + (wbIdx - 1) * 100;
        q.focusMode = focusIdx == 0 ? CaptureParams.FOCUS_AUTO
                : focusIdx == 1 ? CaptureParams.FOCUS_INFINITY
                : focusIdx == 2 ? CaptureParams.FOCUS_MACRO
                : CaptureParams.FOCUS_MANUAL;
        q.diopter = manualIdx / 10f;
        return q;
    }

    /** 专业模式调好后，把当前参数存成傻瓜模式里的一个场景。 */
    private void promptSaveScene(CaptureParams p) {
        EditText et = new EditText(this);
        et.setHint("场景名称，例如 我的夜景");
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("保存为场景")
                .setMessage("把这组" + (captureMode == MODE_VIDEO ? "录像" : "拍照")
                        + "参数存进傻瓜模式，之后一键复用。")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String nm = et.getText().toString().trim();
                    if (nm.isEmpty()) nm = "我的参数" + (sceneList.size() + 1);
                    ScenePreset sp = new ScenePreset("c" + System.currentTimeMillis(), nm,
                            p.summary(), p.manualExposure, p.iso, p.exposureNs, p.ev,
                            p.kelvin, p.wbAuto, p.focusMode, p.diopter);
                    sp.custom = true;
                    sceneStore.add(sp);
                    updateExtraUi();
                    toast("已存为场景 · " + nm + "（傻瓜模式里可选）");
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

        TextView fromAlbum = pill("从相册照片 / 视频读取拍摄参数", false);
        fromAlbum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fromAlbum.setPadding(dp(12), dp(10), dp(12), dp(10));
        fromAlbum.setContentDescription("读取相册媒体的 EXIF 拍摄参数并新增为场景");
        fromAlbum.setOnClickListener(v -> {
            if (sceneDialog != null) sceneDialog.dismiss();
            pickSceneSource();
        });
        LinearLayout.LayoutParams albumLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        albumLp.setMargins(dp(4), 0, dp(4), dp(6));
        list.addView(fromAlbum, albumLp);

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
        final boolean builtin = base != null && !base.custom && !isNew;

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

    // ------------------------------------------------------------------ 从相册读取拍摄参数

    private void pickSceneSource() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        try {
            startActivityForResult(i, REQ_PICK_SCENE);
        } catch (Throwable t) {
            Log.e(TAG, "pick scene source", t);
            toast("无法打开相册");
        }
    }

    private void importSceneFromUri(Uri uri) {
        toast("正在读取拍摄参数…");
        worker.execute(() -> {
            ScenePreset imported;
            try {
                imported = readSceneFromMedia(uri);
            } catch (Throwable t) {
                Log.e(TAG, "import scene", t);
                ui.post(() -> toast("读取失败：这张照片/视频没有可用的拍摄参数"));
                return;
            }
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                // 以“新增场景”的方式打开编辑器，参数已按片子的 EXIF 预填好，可再改后保存
                showSceneEditor(imported, true);
                toast("已读取参数，确认后保存到场景");
            });
        });
    }

    private ScenePreset readSceneFromMedia(Uri uri) throws Exception {
        String type = null;
        try {
            type = getContentResolver().getType(uri);
        } catch (Throwable ignored) {
        }
        String name = mediaDisplayName(uri);
        if (type != null && type.startsWith("video/")) {
            return readVideoScene(uri, name);
        }
        if (type != null && type.startsWith("image/")) {
            return readImageScene(uri, name);
        }
        try {
            return readImageScene(uri, name);
        } catch (Throwable t) {
            return readVideoScene(uri, name);
        }
    }

    private String mediaDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Throwable ignored) {
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "相册场景";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.length() > 18 ? name.substring(0, 18) : name;
    }

    private ScenePreset readImageScene(Uri uri, String name) throws Exception {
        ExifInterface exif;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("无法打开照片");
            exif = new ExifInterface(in);
        }
        int iso = exif.getAttributeInt("PhotographicSensitivity", 0);
        if (iso <= 0) iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0);
        double et = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0d);
        double evRaw = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0d);
        int wbManual = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, 0);
        int light = exif.getAttributeInt(ExifInterface.TAG_LIGHT_SOURCE, 0);
        double dist = exif.getAttributeDouble(ExifInterface.TAG_SUBJECT_DISTANCE, 0d);

        boolean manual = iso > 0 || et > 0;
        if (iso <= 0) iso = 200;
        long ns = et > 0 ? Math.round(et * 1e9) : 1_000_000_000L / 125;
        ns = Math.max(1_000_000L, Math.min(2_000_000_000L, ns));
        float ev = Math.max(-2f, Math.min(2f, Math.round(evRaw * 10f) / 10f));

        boolean wbAuto = wbManual != 1 || light <= 0;
        int kelvin = wbAuto ? 5200 : clampKelvin(lightSourceKelvin(light));

        int focusMode = CaptureParams.FOCUS_AUTO;
        float diopter = 0f;
        if (dist > 0.01) {
            if (dist >= 8) {
                focusMode = CaptureParams.FOCUS_INFINITY;
            } else {
                focusMode = CaptureParams.FOCUS_MANUAL;
                diopter = Math.min(10f, (float) (1.0 / dist));
            }
        }

        StringBuilder hint = new StringBuilder("相册照片");
        if (iso > 0 && et > 0) {
            hint.append(" · ISO ").append(iso).append(" · ")
                    .append(CaptureParams.shutterText(ns));
        }
        if (!wbAuto) hint.append(" · ").append(kelvin).append("K");
        if (focusMode == CaptureParams.FOCUS_INFINITY) {
            hint.append(" · 无穷远");
        } else if (focusMode == CaptureParams.FOCUS_MANUAL) {
            hint.append(" · 对焦 ").append(String.format(Locale.US, "%.0fcm", dist * 100));
        }
        return new ScenePreset("c" + System.currentTimeMillis(), name, hint.toString(),
                manual, iso, ns, ev, kelvin, wbAuto, focusMode, diopter);
    }

    private ScenePreset readVideoScene(Uri uri, String name) {
        int fps = 0;
        int iso = 0;
        long ns = 0;
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(this, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = mediaString(f, MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                Integer fr = mediaInt(f, MediaFormat.KEY_FRAME_RATE);
                if (fr != null && fr > fps) fps = fr;
                Integer isoV = mediaInt(f, "iso-speed");
                if (isoV != null && isoV > 0) iso = isoV;
                String etS = mediaString(f, "exposure-time");
                if (etS != null) {
                    try {
                        double seconds;
                        int slash = etS.indexOf('/');
                        if (slash > 0) {
                            seconds = Double.parseDouble(etS.substring(0, slash).trim())
                                    / Double.parseDouble(etS.substring(slash + 1).trim());
                        } else {
                            seconds = Double.parseDouble(etS.trim());
                        }
                        ns = Math.round(seconds * 1e9);
                    } catch (Throwable ignored) {
                        ns = 0;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "video extractor", t);
        } finally {
            if (ex != null) {
                try {
                    ex.release();
                } catch (Throwable ignored) {
                }
            }
        }
        if (fps <= 0) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, uri);
                String f = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                if (f != null) fps = Math.round(Float.parseFloat(f));
            } catch (Throwable ignored) {
            } finally {
                try {
                    mmr.release();
                } catch (Throwable ignored) {
                }
            }
        }
        if (ns <= 0 && fps > 0) ns = 1_000_000_000L / (fps * 2L);
        ns = Math.max(1_000_000L, Math.min(1_000_000_000L / 30, ns));

        boolean manual = iso > 0;
        if (iso <= 0) iso = 200;

        StringBuilder hint = new StringBuilder("相册视频");
        if (fps > 0) hint.append(" · ").append(fps).append("fps");
        if (iso > 0) hint.append(" · ISO ").append(iso);
        return new ScenePreset("c" + System.currentTimeMillis(), name, hint.toString(),
                manual, iso, ns, 0f, 5200, true, CaptureParams.FOCUS_AUTO, 0f);
    }

    private static Integer mediaInt(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String mediaString(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getString(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** EXIF 光源值 → 近似色温。 */
    private static int lightSourceKelvin(int light) {
        switch (light) {
            case 1: return 5500;   // 日光
            case 2: return 4000;   // 荧光灯
            case 3: return 2850;   // 钨丝灯
            case 4: return 5500;   // 闪光灯
            case 9: return 5500;   // 晴天
            case 10: return 6500;  // 阴天
            case 11: return 7500;  // 阴影
            case 12: return 4400;  // 日光色荧光灯
            case 13: return 5000;  // 暖白荧光灯
            case 14: return 4000;  // 冷白荧光灯
            case 15: return 3500;  // 白色荧光灯
            case 16: return 2856;  // 标准 A
            case 17: return 4874;  // 标准 B
            case 18: return 6774;  // 标准 C
            case 19: return 5500;  // D55
            case 20: return 6500;  // D65
            case 21: return 7500;  // D75
            case 22: return 5000;  // D50
            case 23: return 3200;  // 影棚钨丝灯
            default: return 5200;
        }
    }

    private static int clampKelvin(int kelvin) {
        return Math.max(2300, Math.min(7900, kelvin));
    }

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
        if (requestCode == REQ_PICK_SCENE) {
            if (resultCode != RESULT_OK || data == null) return;
            Uri pick = data.getData();
            if (pick == null) return;
            try {
                getContentResolver().takePersistableUriPermission(pick,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            importSceneFromUri(pick);
            return;
        }
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
                ui.post(() -> {
                    updateFlashUi();
                    applyFlashState();
                    syncZoomUi();
                });
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
        if (processing) {
            toast("正在处理上一段，请稍候");
            return;
        }
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
            // 开启“保存原素材”且当前不是正常风格：先用编码器录下未加风格的原片，
            // 停止后再离线生成一份叠加风格的版本，两份都带声音。
            recStyle = style;
            recIntensity = intensity;
            recSprites = spritesOn;
            recorderPlain = saveOriginal && !style.normal;
            renderer.setEncoderPlain(recorderPlain);
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
            OutputTarget out = publishOutput();
            if (out != null && recorderPlain) {
                markOriginalName(out);
                startStyledCopy(out);
            }
        } else {
            discardOutput();
            toast("录像没有保存（没有采集到画面）");
        }
    }

    /** 给双份保存里的原片加个“_原片”后缀，方便和风格版本区分。 */
    private void markOriginalName(OutputTarget out) {
        if (out == null) return;
        String n = out.name == null ? "" : out.name;
        String base = n.toLowerCase(Locale.US).endsWith(".mp4")
                ? n.substring(0, n.length() - 4) : n;
        String newName = (base.isEmpty() ? "Daigua" : base) + "_原片.mp4";
        try {
            if (out.uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, newName);
                getContentResolver().update(out.uri, v, null, null);
                out.name = newName;
            } else if (out.file != null) {
                File dst = new File(out.file.getParentFile(), newName);
                if (out.file.renameTo(dst)) {
                    out.file = dst;
                    out.name = newName;
                    MediaScannerConnection.scanFile(this,
                            new String[]{dst.getAbsolutePath()}, null, null);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "rename original", t);
        }
    }

    /**
     * “保存原素材”开启且为非正常风格时：刚录好的原片已经进相册，
     * 这里用离线管线再生成一份叠加风格的视频，两份都保留。
     */
    private void startStyledCopy(OutputTarget out) {
        if (out == null || isFinishing() || isDestroyed()) return;
        final Uri src = out.uri != null ? out.uri : Uri.fromFile(out.file);
        if (src == null) return;
        final StylePreset st = recStyle;
        final float in = recIntensity;
        final boolean spr = recSprites;
        processing = true;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        processingCancel = cancel;

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("正在生成风格版本")
                .setMessage("原片已保存，正在生成叠加风格的视频…")
                .setNegativeButton("取消", (d, w) -> cancel.set(true))
                .setCancelable(false)
                .create();
        progressDialog = dialog;
        dialog.show();

        worker.execute(() -> GalleryProcessor.process(this, src, "video/mp4", st, in, spr, cancel,
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
                        if (preview != null) preview.recycle();
                        ui.post(() -> {
                            dismissProgress();
                            processing = false;
                            processingCancel = null;
                            // 生成完成也只安静地存进相册
                        });
                    }

                    @Override
                    public void onError(String message) {
                        ui.post(() -> {
                            dismissProgress();
                            processing = false;
                            processingCancel = null;
                            if (!isFinishing() && !isDestroyed()) {
                                toast("原片已保存，风格版本生成失败：" + message);
                            }
                        });
                    }
                }));
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

    /**
     * 把录好的文件正式放进相册。录完不再弹“已保存”提示，返回落盘目标供“生成风格版本”使用。
     */
    private OutputTarget publishOutput() {
        OutputTarget out = pendingOutput;
        pendingOutput = null;
        if (out == null) return null;
        try {
            if (out.uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(out.uri, v, null, null);
            } else if (out.file != null) {
                MediaScannerConnection.scanFile(this, new String[]{out.file.getAbsolutePath()}, null, null);
            }
        } catch (Throwable t) {
            Log.e(TAG, "publish", t);
            discardTarget(out);
            return null;
        } finally {
            if (out.pfd != null) {
                try {
                    out.pfd.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private void discardOutput() {
        OutputTarget out = pendingOutput;
        pendingOutput = null;
        discardTarget(out);
    }

    private void discardTarget(OutputTarget out) {
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
