#!/usr/bin/env bash
# 免 Gradle 的 APK 构建脚本：aapt2 -> javac -> d8 -> 打包 -> 对齐 -> 签名
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"

echo "[1/7] 编译资源"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"

echo "[2/7] 链接资源与清单"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 3 --version-name 1.2 \
  "$OUT/res.zip"

echo "[3/7] 编译 Java"
find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.class' -delete 2>/dev/null || true
find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/7] dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/7] 打包"
cp "$OUT/base.apk" "$OUT/withdex.apk"
(cd "$OUT/dex" && zip -q "$OUT/withdex.apk" classes.dex)
python3 - "$OUT/withdex.apk" "$OUT/repacked.apk" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        info.external_attr = item.external_attr
        info.create_system = item.create_system
        info.compress_type = (zipfile.ZIP_STORED
                              if item.filename in ('classes.dex', 'resources.arsc')
                              else zipfile.ZIP_DEFLATED)
        zout.writestr(info, data)
PY

echo "[6/7] 对齐"
"$BT/zipalign" -f -p 4 "$OUT/repacked.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/cartoon-camera.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$ROOT/cartoon-camera.apk" | head -3

echo "APK: $ROOT/cartoon-camera.apk"
