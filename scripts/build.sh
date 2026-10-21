#!/bin/bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [ -d /opt/homebrew/share/android-commandlinetools ]; then
    export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  fi
fi
if [ -z "${SECOND_DISPLAY_VERSION_NAME:-}" ] || [ -z "${SECOND_DISPLAY_VERSION_CODE:-}" ]; then
  eval "$("$repo_dir/scripts/version.sh")"
  export SECOND_DISPLAY_VERSION_NAME SECOND_DISPLAY_VERSION_CODE
fi
cd "$repo_dir"
./gradlew :app:assembleDebug
mkdir -p "$repo_dir/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$repo_dir/dist/second-display.apk"
echo "$repo_dir/dist/second-display.apk"
