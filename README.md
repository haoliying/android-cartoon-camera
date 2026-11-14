# 呆瓜相机

Android 实时卡通相机：Camera2 + OpenGL ES 预览/录像实时滤镜，支持拍照、相册图片与视频卡通化，以及可互动的小精灵。

- 包名：`com.qoder.daiguacamera`
- 最低系统：Android 7.0（API 24）
- 当前版本：v1.11（versionCode 12）

## 下载

<https://github.com/haoliying/daigua-camera/releases/latest>

## 文档

- [操作手册](USER_GUIDE.md)
- [版本更新说明](CHANGELOG.md)

修改文档后运行 `python3 docs/build_apk_docs.py`（需要 pandoc），会把它们重新生成到 `app/src/main/assets/docs/` 并随 APK 打包。

## 功能

- 7 种风格：正常（默认）、卡通、漫画、水彩、素描、像素、霓虹
- 录像、拍照；两种拍摄方式都含普通 / 专业 / 傻瓜三种模式，内置 25 个场景
- 专业参数可调：ISO、快门、曝光补偿、白平衡、对焦、手动焦距；录像快门自动限制在 1/30 秒内
- 场景参数可编辑、可保存、可恢复默认，支持新增自定义场景
- 底部操作区按「拍摄方式 / 模式 / 风格」三层排列，层级清晰
- 相册图片 / 视频卡通化，视频保留原音轨
- 小精灵默认关闭，可在预览、拍照、相册处理中开启
- 操作手册与版本更新说明内置在 APK 中，顶部「说明」按钮离线查看

## 构建

无需 Gradle，依赖 Android SDK build-tools 与 platform：

```bash
./build.sh
```

脚本依次执行 aapt2 资源编译与链接、javac、d8、APK 重打包、zipalign、apksigner，输出 `daigua-camera.apk`。
