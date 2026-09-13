#!/usr/bin/env bash
# Idempotent toolchain provisioning: safe on fresh creation AND rebuild.
# Set PLATFORM/BT to match app/build.gradle's compileSdk before committing.
set -uo pipefail
PLATFORM="android-34"   # <- set from: grep -nE "compileSdk" app/build.gradle
BT="34.0.0"

# --- Java 17 (Android Gradle plugin requirement) ---
if ! java -version 2>&1 | grep -q '"17\.'; then
  source /usr/local/sdkman/bin/sdkman-init.sh
  yes | sdk install java 17.0.20-tem >/dev/null
  sdk default java 17.0.20-tem >/dev/null
fi

# --- Android SDK ---
SDK="$HOME/android-sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/clt.zip
  unzip -q /tmp/clt.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm /tmp/clt.zip
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;$PLATFORM" "build-tools;$BT" >/dev/null
echo "sdk.dir=$SDK" > local.properties   # cwd is the repo when devcontainer runs this
echo "Toolchain ready: $(java -version 2>&1 | head -1)"
