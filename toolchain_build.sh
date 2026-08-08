#!/usr/bin/env bash
# Build the NAS browser APK. Idempotent (re-run safely). Uses RELATIVE paths under the
# project cwd because the Bash sandbox blocks curl/gradle writes to absolute /c/... paths
# (MSYS path conversion does not apply to the Windows curl.exe there).
set -u
cd /c/Users/excel/WorkBuddy/Claw/nas-browser

TC=toolchain
JDK="$TC/jdk"
GRADLE="$TC/gradle"
SDK="$TC/sdk"
PY="/c/Users/excel/.workbuddy/binaries/python/versions/3.13.12/python.exe"

# Convert a (relative or /c/...) path to a Windows path for Windows executables.
winpath() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    echo "$1" | sed -e 's|^/c/|C:\\|' -e 's|/|\\|g'
  fi
}

mkdir -p "$TC" "$JDK" "$GRADLE" "$SDK"

unzip_py() {
  "$PY" -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$1" "$2"
}

echo "=== start $(date) ==="

# 1. JDK 17 (Eclipse Temurin)
if [ -z "$(find "$JDK" -name java.exe -path '*/bin/java.exe' 2>/dev/null | head -n1)" ]; then
  echo "== download JDK17 =="
  curl -L -C - --retry 5 --retry-delay 3 -o "$TC/jdk.zip" "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
  echo "== unzip JDK =="
  unzip_py "$TC/jdk.zip" "$JDK"
  rm -f "$TC/jdk.zip"
fi
JAVA_BIN=$(find "$JDK" -name java.exe -path '*/bin/java.exe' 2>/dev/null | head -n1)
export JAVA_HOME=$(winpath "$(dirname "$(dirname "$JAVA_BIN")")")
echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_BIN" -version

# 2. Gradle 8.6
if [ ! -f "$GRADLE/gradle-8.6/bin/gradle.bat" ]; then
  echo "== download Gradle 8.6 =="
  curl -L -C - --retry 5 --retry-delay 3 -o "$TC/gradle.zip" "https://services.gradle.org/distributions/gradle-8.6-bin.zip"
  echo "== unzip Gradle =="
  unzip_py "$TC/gradle.zip" "$GRADLE"
  rm -f "$TC/gradle.zip"
fi
GRADLE_BIN="./$GRADLE/gradle-8.6/bin/gradle.bat"

# 3. Android command-line tools
if [ ! -f "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  echo "== download cmdline-tools =="
  curl -L -C - --retry 5 --retry-delay 3 -o "$TC/ct.zip" "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
  echo "== unzip cmdline-tools =="
  rm -rf "$TC/ct"; mkdir -p "$TC/ct"
  unzip_py "$TC/ct.zip" "$TC/ct"
  mkdir -p "$SDK/cmdline-tools/latest"
  mv "$TC/ct/cmdline-tools/"* "$SDK/cmdline-tools/latest/"
  rm -rf "$TC/ct"
  rm -f "$TC/ct.zip"
fi
SDKMANAGER="./$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
export ANDROID_HOME=$(winpath "$SDK")
export ANDROID_SDK_ROOT=$(winpath "$SDK")
export GRADLE_USER_HOME=$(winpath "$TC/.gradle")
echo "ANDROID_HOME=$ANDROID_HOME  GRADLE_USER_HOME=$GRADLE_USER_HOME"

# 4. Accept licenses + install SDK components
if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
  echo "== accept licenses =="
  printf 'y\n%.0s' {1..40} | cmd //c "$SDKMANAGER --licenses" >/dev/null 2>&1 || true
  echo "== install SDK components =="
  "$SDKMANAGER" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
fi

# 5. Point the project at the SDK
printf 'sdk.dir=%s\n' "$(winpath "$SDK")" > local.properties
echo "wrote local.properties"

# 6. Build the debug APK
if [ ! -f app/build/outputs/apk/debug/app-debug.apk ]; then
  echo "== build APK =="
  "$GRADLE_BIN" assembleDebug --no-daemon
  echo "== build exit code: $? =="
fi
APK=$(find app/build/outputs/apk/debug -name '*.apk' 2>/dev/null | head -n1)
echo "APK_READY=$(winpath "$APK")"
echo "=== done $(date) ==="
