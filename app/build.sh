#!/bin/bash
# build.sh — compile and package SMS daemon APK (Android 4.4.4 KK API 19)
# Requirements: aapt, dx (build-tools), apksigner, zipalign, javac
set -e
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/tmp/opencode/sdk-platform19/android-4.4.2/android.jar}

rm -rf out && mkdir -p out/classes
javac --release 8 -cp "$ANDROID_JAR" -d out/classes src/com/ufi/smsfwd/*.java 2>&1 | grep -av 'warning\|Xlint' | head -5
java -cp /tmp/opencode/sdk-bt30/android-11/lib/dx.jar \
     com.android.dx.command.dexer.Main --no-strict --output out/classes.dex out/classes/com/ufi/smsfwd/*.class 2>&1 \
  | grep -aE 'error|Exception' | head -3

rm -f out/ufi9.apk
aapt package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/base.apk
python3 - <<'EOF'
import zipfile
z = zipfile.ZipFile('out/base.apk', 'a')
z.write('out/classes.dex', 'classes.dex')
z.close()
EOF
zipalign -f 4 out/base.apk out/aligned.apk
apksigner sign --ks ks.jks --ks-pass pass:pass123 --out out/ufi9.apk out/aligned.apk
echo "built: $(ls -l out/ufi9.apk | awk '{print $5}') bytes"

# Deploy: adb install -r out/ufi9.apk
