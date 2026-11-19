# 呆瓜相机 · 项目上下文

> 这份文档是项目交接/上下文快照，记录当前状态、设计决策、构建发布流程和待办。
> 最近更新：2026-11-19（对应 v1.26，versionCode 27）。

---

## 1. 项目概况

- 仓库：<https://github.com/haoliying/daigua-camera>（公开）
- 包名：`com.qoder.daiguacamera`
- 最低系统：Android 7.0（API 24），targetSdk 34
- 构建方式：**免 Gradle**，直接用 Android SDK 的 aapt2 / javac / d8 / zipalign / apksigner
- 签名：仓库内 `debug.keystore`（debug 签名，仅供安装测试，不能上架）
- 语言：Java + OpenGL ES 2.0（Camera2 预览/录像实时滤镜）

## 2. 技术架构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 全部界面（代码构建，无 XML 布局）、模式/场景/风格状态、拍照与录像流程、相册与调色入口 |
| `CameraController.java` | Camera2 封装：预览会话、JPEG 拍照、手动参数、变焦、对焦、人脸、闪光灯、自动场景 |
| `CartoonRenderer.java` | EGL + GLES 渲染：相机纹理 → 风格着色器 → 预览/编码器 Surface，另含构图分析帧、小精灵、预览帧抓取 |
| `SpriteShaders.java` / `SpriteSystem.java` | 小精灵（可互动的卡通景物）着色器与粒子系统 |
| `CompositionAdvisor.java` / `CompositionOverlay.java` | 构图助手：网格、主体/人脸框、地平线、构图建议、逆光动作；叠加层按取景区域绘制 |
| `ScenePreset.java` / `SceneStore.java` | 傻瓜模式 38 个内置场景 + 用户自定义场景的持久化 |
| `StylePreset.java` | 卡通风格参数表 |
| `CaptureParams.java` / `MediaAdjust.java` / `NegativeParams.java` / `AdjustPreset.java` / `AdjustStore.java` | 拍摄参数、调色参数、底片参数及其持久化 |
| `GalleryProcessor.java` | 相册侧图片/视频处理：卡通化、调色、底片转正、EXIF 方向、软件变焦裁切、保存到系统相册 |
| `ZoomDial.java` | 圆形焦距控制盘（0.1×–100×，点刻度跳转） |

关键渲染细节：

- 预览流优先选 **4:3**（接近 1440×1080），和拍照尺寸一致，保证取景=成片。
- 取景画面按相机实际画幅等比缩放后放进窗口中间，四周留黑边（`CartoonRenderer.setContentRect` + `glViewport` + 全屏 `glClear`），**不裁切画面**。
- 触摸、对焦圈、构图叠加、小精灵都按同一块 `previewL/T/R/B` 区域换算；点在黑边上不触发对焦。
- 构图分析帧和分析用的相机画面保持同一视野（含软件变焦裁切），人脸框/对焦点坐标一致。

## 3. 当前版本状态（v1.26）

- 版本：`versionName 1.26` / `versionCode 27`
- 内置场景：**38 个**（含「日常」「自动场景」两个固定预设）；v1.25 新增 清晨 / 中午 / 傍晚
- APK SHA-256：`c1fb8d128bf48e21e99ab201d09829bbd56ecf63b9baed18bc399d9f4b3cdd30`
- 手册 PDF SHA-256：`037a3f0ec851dc0c93e22d09392dfba0b193e4c7b90663eebbcb57478d582f70`

## 4. 近期版本演进

- **v1.21** 构图助手（P1 网格/主体/人脸/地平线、P2 建议与逆光动作、P3 对称/引导线）
- **v1.22** 傻瓜模式新增 树叶、树（35 个场景）
- **v1.23** 拍照“所见即所得”：成片按取景框比例裁切；相册预览支持全屏查看
- **v1.24** 改为**不裁切**：取景框完整显示相机画面（四周留黑边），预览流切到 4:3，成片不再按预览比例裁切，录像画幅跟随取景框；叠加层/对焦/小精灵随取景区域对齐
- **v1.25** 傻瓜模式新增 清晨 / 中午 / 傍晚（38 个场景）
- **v1.26** 修复“上次停在傻瓜模式，重启 App 取景黑屏”：
  - 预览先用全自动参数起画面，收到第一帧再套用场景参数
  - 场景参数被机型拒收时退回全自动请求（以前这条路径只关旧请求、不恢复画面）
  - 手动曝光/白平衡逐项兜底，避免拼出「AE 自动 + 手动 ISO」这类整条被拒的请求
  - 预览看门狗：3.5 秒无画面先全自动重试，再不行重建预览会话
  - 手动曝光预览补 `SENSOR_FRAME_DURATION`

## 5. 构建流程

```bash
./build.sh          # aapt2 → javac --release 11 → d8 → 打包 → zipalign -p 4 → apksigner
```

依赖：`/opt/android-sdk`（build-tools 35.0.0、platforms/android-34）。

校验：

```bash
/opt/android-sdk/build-tools/35.0.0/aapt2 dump badging daigua-camera.apk | head -1
/opt/android-sdk/build-tools/35.0.0/zipalign -c -p 4 daigua-camera-vX.Y.apk
/opt/android-sdk/build-tools/35.0.0/apksigner verify daigua-camera-vX.Y.apk
```

## 6. 文档与 PDF

- 内置文档：`python3 docs/build_apk_docs.py`（pandoc）把 `USER_GUIDE.md` / `CHANGELOG.md` 生成到 `app/src/main/assets/docs/`，随 APK 打包。
- 手册 PDF：pandoc gfm → html + `/tmp/manual.css` + wkhtmltopdf A4（Noto Sans CJK SC）。

```bash
pandoc USER_GUIDE.md -f gfm -t html5 --standalone \
  --metadata title="呆瓜相机 vX.YY 使用手册" --css /tmp/manual.css -o /tmp/manual.html
wkhtmltopdf --enable-local-file-access --page-size A4 \
  --margin-top 16mm --margin-bottom 16mm --margin-left 14mm --margin-right 14mm \
  /tmp/manual.html daigua-camera-manual-vX.YY.pdf
```

## 7. 发布流程

1. 改 `app/src/main/AndroidManifest.xml` 的 `versionCode` / `versionName`，同步 `build.sh` 的 `--version-code` / `--version-name`。
2. 更新 `CHANGELOG.md`、`USER_GUIDE.md` 版本号与相关说明、`README.md` 当前版本。
3. `python3 docs/build_apk_docs.py` 重新生成内置文档。
4. `./build.sh`；复制成 `daigua-camera-vX.YY.apk`；生成 PDF。
5. 校验（badging / zipalign / apksigner / 内置文档 md5 与源码一致）。
6. 提交并推送到 `main`。
7. 用 GitHub API 创建 release 并上传 APK + PDF，再重新下载核对 SHA-256。

> 推送与 release 需要 GitHub token，不要把 token 写进仓库或文档；用完及时撤销。

## 8. 已知风险 / 限制

- **所有版本都没有真机或模拟器实测**，只做了编译与静态校验。涉及相机 HAL 的行为（手动曝光、场景模式、编码器尺寸、预览尺寸）存在机型差异风险。
- 预览流若不是 4:3（少数机型），取景框会按照片画幅裁掉一点预览边缘，保证与成片一致。
- 录像尺寸由照片画幅推导（如 960×1280），个别机型编码器可能不接受，需要真机确认。
- 手动参数（ISO/快门/白平衡增益/AF 距离）在部分机型上会被拒收，v1.26 起有逐项兜底 + 全自动回退。
- 软件变焦（超过设备变焦上限）通过裁切实现，倍数越高画面越软。

## 9. 待办 / 未决问题

- **拍摄参数调节“没有数字显示”**：用户反馈后给了三种可能，等确认：
  1. 「专业参数」对话框里滑块上方文字整块看不见（疑似机型强制浅色对话框，白字白底）；
  2. 希望取景画面常驻参数读数（ISO / 快门 / EV / 白平衡 / 焦距）——目前取景画面确实没有；
  3. 其他位置（相册调色、底片参数）没有数字。
- 建议后续补充：真机回归清单（拍照/录像/傻瓜/专业/底片/相册调色/构图助手）。

## 10. 目录速览

```
app/src/main/java/com/qoder/daiguacamera/   源码
app/src/main/res/                           主题与字符串
app/src/main/assets/docs/                   APK 内置的离线手册（生成物）
docs/build_apk_docs.py                      Markdown → HTML
build.sh                                    免 Gradle 构建脚本
USER_GUIDE.md / CHANGELOG.md / README.md    对外文档
debug.keystore                              debug 签名
```
