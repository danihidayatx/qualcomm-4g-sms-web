#!/bin/bash
# build.sh — compile and package the in-modem SMS/OTP proxy APK (Android 4.4.4 KK / API 19)
#
# Self-contained: auto-downloads the required SDK pieces (android.jar API 19,
# build-tools for aapt/zipalign/apksigner, and dx.jar) into ~/.android-ufi-sdk
# on first run. Only needs: bash, curl, unzip, python3 and a JDK (8+).
#
# Env overrides:
#   SDK_DIR   where SDK pieces live      (default: ~/.android-ufi-sdk)
#   ANDROID_JAR platform android.jar     (default: $SDK_DIR/platform-19/android-4.4.2/android.jar)
#   DX_JAR    dx.jar used for dexing     (default: $SDK_DIR/dx/dx.jar)
set -e
cd "$(dirname "$0")"

SDK_DIR=${SDK_DIR:-$HOME/.android-ufi-sdk}
DL="https://dl.google.com/android/repository"

case "$(uname -s)" in
  Darwin) BT_OS=macosx ;;
  *)      BT_OS=linux ;;
esac

# Homebrew JDK (needed by apksigner wrapper and dx)
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk
  export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "=== [1/4] Toolchain ($SDK_DIR) ==="
mkdir -p "$SDK_DIR"

# android.jar API 19
if [ ! -f "$SDK_DIR/platform-19/android-4.4.2/android.jar" ]; then
  echo "downloading android.jar (API 19)..."
  curl -sSLo "$SDK_DIR/platform-19.zip" "$DL/android-19_r04.zip"
  mkdir -p "$SDK_DIR/platform-19" && unzip -qo "$SDK_DIR/platform-19.zip" -d "$SDK_DIR/platform-19"
  rm -f "$SDK_DIR/platform-19.zip"
fi
ANDROID_JAR=${ANDROID_JAR:-$SDK_DIR/platform-19/android-4.4.2/android.jar}

# build-tools (aapt, zipalign, apksigner) — newest macOS package still hosted by Google
if [ ! -x "$SDK_DIR/android-13/aapt" ]; then
  echo "downloading build-tools r33 ($BT_OS)..."
  curl -sSLo "$SDK_DIR/bt.zip" "$DL/build-tools_r33-$BT_OS.zip"
  unzip -qo "$SDK_DIR/bt.zip" -d "$SDK_DIR"
  rm -f "$SDK_DIR/bt.zip"
  chmod +x "$SDK_DIR/android-13/"{aapt,zipalign,apksigner} 2>/dev/null || true
fi
BT_DIR=${BT_DIR:-$SDK_DIR/android-13}

# dx.jar (pure Java; taken from the linux build-tools r30 package since
# macOS packages >= r31 dropped dx and d8 r83 has an NPE bug with
# anonymous classes compiled by modern javac)
if [ ! -f "$SDK_DIR/dx/dx.jar" ]; then
  echo "downloading dx.jar (build-tools r30, linux)..."
  mkdir -p "$SDK_DIR/dx"
  curl -sSLo "$SDK_DIR/bt30.zip" "$DL/build-tools_r30.0.3-linux.zip"
  unzip -qo "$SDK_DIR/bt30.zip" 'android-11/lib/dx.jar' -d "$SDK_DIR"
  mv "$SDK_DIR/android-11/lib/dx.jar" "$SDK_DIR/dx/dx.jar"
  rm -rf "$SDK_DIR/bt30.zip" "$SDK_DIR/android-11"
fi
DX_JAR=${DX_JAR:-$SDK_DIR/dx/dx.jar}

echo "=== [2/4] Compile + dex ==="
# Regenerate SmsInboxJs.java from ../server/sms-inbox.js (single source of truth for the widget)
python3 - <<'EOF'
js = open('../server/sms-inbox.js', encoding='utf-8').read()
esc = js.replace('\\', '\\\\').replace('"', '\\"').replace('\r', '').replace('\n', '\\n')
with open('src/com/ufi/smsfwd/SmsInboxJs.java', 'w', encoding='utf-8') as f:
    f.write('package com.ufi.smsfwd;\n\n'
            '/** GENERATED from server/sms-inbox.js by build.sh — do not edit. */\n'
            'public class SmsInboxJs {\n'
            '    public static final String JS = "' + esc + '";\n'
            '}\n')
EOF

rm -rf out && mkdir -p out/classes
javac --release 8 -Xlint:none -cp "$ANDROID_JAR" -d out/classes src/com/ufi/smsfwd/*.java
java -cp "$DX_JAR" com.android.dx.command.dexer.Main --no-strict \
  --min-sdk-version 19 --output out/classes.dex out/classes/com/ufi/smsfwd/*.class

echo "=== [3/4] Package + sign ==="
rm -f out/ufi-sms-proxy.apk
"$BT_DIR/aapt" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/base.apk
python3 - <<'EOF'
import zipfile
z = zipfile.ZipFile('out/base.apk', 'a')
z.write('out/classes.dex', 'classes.dex')
z.close()
EOF
"$BT_DIR/zipalign" -f 4 out/base.apk out/aligned.apk
"$BT_DIR/apksigner" sign --ks ks.jks --ks-pass pass:pass123 --out out/ufi-sms-proxy.apk out/aligned.apk
echo "built: $(ls -l out/ufi-sms-proxy.apk | awk '{print $5}') bytes -> app/out/ufi-sms-proxy.apk"

echo "=== [4/4] Install ==="
echo "  adb install -r out/ufi-sms-proxy.apk"
echo "  adb shell am startservice -n com.ufi.smsfwd/.SmsService"
