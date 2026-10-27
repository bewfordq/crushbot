#!/usr/bin/env bash
# Builds Crushbot.apk with the Android SDK's own tools (no Gradle, no downloads).
#   ./build.sh           build Crushbot.apk
#   ./build.sh install   build, then install on the emulator/phone with adb
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)
JAR=$PLATFORM/android.jar
OUT=build/apk

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" assets

echo "resources..."
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml -A assets "$OUT/res.zip" \
    --java "$OUT/gen" --min-sdk-version 30 --target-sdk-version 36 --version-code 1 --version-name 1.0

echo "compiling..."
"${JAVA_BIN}javac" -nowarn --release 17 -cp "$JAR" -d "$OUT/classes" \
    $(find src/core src/android "$OUT/gen" -name '*.java')

echo "dexing..."
"$BT/d8" --release --min-api 30 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f debug.keystore ]; then
    "${JAVA_BIN}keytool" -genkeypair -keystore debug.keystore -storepass android -keypass android \
        -alias crushbot -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Crushbot" >/dev/null
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out Crushbot.apk "$OUT/aligned.apk"
echo "built $(pwd)/Crushbot.apk ($(du -h Crushbot.apk | cut -f1))"

if [ "${1:-}" = "install" ]; then
    adb install -r Crushbot.apk
fi
