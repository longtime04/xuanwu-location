#!/bin/bash
# 手动流水线构建 玄武定位 APK（无 Gradle）：aapt2 → javac → d8 → zipalign → apksigner
# 需要：JDK 21、Android SDK build-tools 34（路径按你本机情况改 JAVA_HOME / SDK 两行）
set -e
cd "$(dirname "$0")"

export JAVA_HOME=${JAVA_HOME:-$HOME/workspace/jdk-21.0.12.1+1}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_SDK:-$HOME/workspace/android-sdk}
BT=$SDK/build-tools/34.0.0
ANDROID_JAR=$SDK/platforms/android-34/android.jar

rm -rf build && mkdir -p build/classes build/dex

echo "== 1. aapt2 编译资源并链接 =="
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/app-unsigned.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    -A assets \
    --no-compress \
    --auto-add-overlay

echo "== 2. javac 编译 =="
javac --release 8 -cp "$ANDROID_JAR" -d build/classes \
    src/com/muse/xuanwu/*.java

echo "== 3. d8 转 dex =="
$BT/d8 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name "*.class")

echo "== 4. 塞入 dex =="
(cd build/dex && zip -q ../app-unsigned.apk classes.dex)

echo "== 5. 对齐 + 签名 =="
$BT/zipalign -f 4 build/app-unsigned.apk build/app-aligned.apk
if [ -f keystore.jks ]; then
    # 把自己的 keystore.jks 放在根目录；密码通过交互输入（或自行加 --ks-pass）
    $BT/apksigner sign --ks keystore.jks --out build/xuanwu.apk build/app-aligned.apk
    echo "== 6. 验收 =="
    $BT/apksigner verify --print-certs build/xuanwu.apk | head -3
else
    cp build/app-aligned.apk build/xuanwu.apk
    echo "⚠ 未找到 keystore.jks：build/xuanwu.apk 为未签名包，放好自己的 keystore 后重跑即可签名。"
fi
$BT/aapt2 dump badging build/xuanwu.apk | head -4
ls -la build/xuanwu.apk
echo "BUILD OK"
