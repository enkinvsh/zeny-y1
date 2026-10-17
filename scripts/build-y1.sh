#!/bin/bash
# Сборка debug-APK под реальный Y1 (32-бит armeabi-v7a). Результат: $Y1_ROOT/out/zeny-y1.apk
set -euo pipefail
source "$(dirname "$0")/env.sh"
python3 "$Y1_ROOT/scripts/make-zeny-theme.py" > /dev/null
"$Y1_ROOT/scripts/build-native.sh"
cd "$Y1_ROOT/android"
./gradlew assembleDebug -Py1.vc="$(date +%m%d%H%M)"
mkdir -p "$Y1_ROOT/out"
cp app/build/outputs/apk/debug/app-debug.apk "$Y1_ROOT/out/zeny-y1.apk"
