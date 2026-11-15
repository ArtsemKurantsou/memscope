#!/usr/bin/env bash
# Builds the fixture app and installs it. Usage: build-and-install.sh [adb-serial]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -d "$HOME/Library/Android/sdk" ] && sdk="$HOME/Library/Android/sdk"
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
  adb_path="$(command -v adb || true)"
  [ -n "$adb_path" ] && sdk="$(cd "$(dirname "$adb_path")/.." && pwd)"
fi
if [ -z "$sdk" ] || [ ! -d "$sdk/platforms" ]; then echo "Android SDK not found" >&2; exit 1; fi
printf 'sdk.dir=%s\n' "$sdk" > "$here/local.properties"
"$here/gradlew" -p "$here" --console=plain assembleDebug
adb="$sdk/platform-tools/adb"; [ -x "$adb" ] || adb="adb"
args=()
[ $# -ge 1 ] && args=(-s "$1")
"$adb" ${args[@]+"${args[@]}"} install -r "$here/app/build/outputs/apk/debug/app-debug.apk"
