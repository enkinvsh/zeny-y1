#!/bin/bash
# Сборка debug-APK под arm64 (эмулятор) и установка. Для самого плеера: build-y1.sh (armeabi-v7a).
# Флаг --fresh сбрасывает данные приложения (первый запуск: тема по умолчанию, язык English).
set -euo pipefail
source "$(dirname "$0")/env.sh"
python3 "$Y1_ROOT/scripts/make-zeny-theme.py" > /dev/null
cd "$Y1_ROOT/android"
./gradlew assembleDebug -Py1.abi=arm64-v8a -Py1.vc="$(date +%m%d%H%M)"
adbe install -r app/build/outputs/apk/debug/app-debug.apk
if [ "${1:-}" = "--fresh" ]; then
  adbe shell pm clear com.themoon.y1 > /dev/null
fi
adbe shell pm grant com.themoon.y1 android.permission.READ_EXTERNAL_STORAGE || true
adbe shell pm grant com.themoon.y1 android.permission.WRITE_EXTERNAL_STORAGE || true
# Runtime-разрешения Android 12+ нужны только эмулятору, на Android 4.2 их нет.
for perm in BLUETOOTH_SCAN BLUETOOTH_CONNECT ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION RECORD_AUDIO; do
  adbe shell pm grant com.themoon.y1 android.permission.$perm || true
done
adbe shell appops set com.themoon.y1 MANAGE_EXTERNAL_STORAGE allow || true
adbe shell am force-stop com.themoon.y1
adbe shell am start -n com.themoon.y1/.MainActivity > /dev/null
