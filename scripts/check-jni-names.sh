#!/usr/bin/env bash
# Fails when R8 renamed or removed a name that native code looks up by string:
# every GetMethodID name in receiver_jni.c and every Java_..._NativeBridge_* function
# must exist in the APK's dex. Usage: scripts/check-jni-names.sh path/to/app.apk
set -euo pipefail

apk="${1:?usage: $0 app.apk}"
root="$(cd "$(dirname "$0")/.." && pwd)"
jni="$root/app/src/main/cpp/receiver_jni.c"
dexdump="$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/*/dexdump | sort -V | tail -1)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q "$apk" 'classes*.dex' -d "$work"
for dex in "$work"/classes*.dex; do "$dexdump" "$dex"; done > "$work/dump.txt" 2>/dev/null

names="$( {
    grep -o 'GetMethodID(env, cls, "[A-Za-z0-9_]*"' "$jni" | sed 's/.*"\(.*\)"/\1/'
    grep -o 'Java_io_github_swiderekartmax_seconddisplay_NativeBridge_[A-Za-z0-9]*' "$jni" | sed 's/.*_NativeBridge_//'
} | sort -u )"

missing=0
for name in $names; do
    if grep -q "name *: '$name'" "$work/dump.txt"; then
        echo "ok      $name"
    else
        echo "MISSING $name"
        missing=1
    fi
done
grep -q "Class descriptor *: 'Lio/github/swiderekartmax/seconddisplay/NativeBridge\$Listener;'" "$work/dump.txt" \
    || { echo "MISSING NativeBridge\$Listener class"; missing=1; }
exit $missing
