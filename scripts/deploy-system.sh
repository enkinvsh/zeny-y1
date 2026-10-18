#!/bin/zsh
# Выкладывает собранный APK на Y1 как системное приложение вместе с JNI-библиотеками.
# Почему libs отдельно: PackageManager Android 4.2 для приложений из /system/app сам
# не распаковывает нативные библиотеки и чистит /data/app-lib при загрузке, поэтому без
# копий в /system/lib не грузятся flacJNI/opusV2JNI/ffmpegJNI/libvlc и opus играет молча.
# Ставить обновление через `adb install -r` нельзя без нужды: это создаёт data-копию поверх
# системной, а `pm uninstall -k` стирает настройки приложения.
set -euo pipefail
source "$(dirname "$0")/env.sh"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/out/zeny-y1.apk"
SERIAL=0123456789ABCDEF
[ -x "$ADB" ] || { echo "не найден adb: поставьте brew install --cask android-platform-tools"; exit 1; }
ady() { "$ADB" -s "$SERIAL" "$@"; }

[ -f "$APK" ] || { echo "нет $APK, сначала scripts/build-y1.sh"; exit 1; }

LIBS="$ROOT/work/apklibs"
rm -rf "$LIBS" && mkdir -p "$LIBS"
unzip -q -o "$APK" 'lib/armeabi-v7a/*' -d "$LIBS"

ady root >/dev/null 2>&1 || true
sleep 1
ady shell 'mount -o remount,rw /system'
for f in "$LIBS"/lib/armeabi-v7a/*.so; do
  ady push "$f" /system/lib/ >/dev/null
  n="$(basename "$f")"
  ady shell "chown root:root /system/lib/$n; chmod 644 /system/lib/$n"
done
# Два APK одного пакета в /system/app: PackageManager берёт любой из них, и обновление не вступает в силу.
ady shell 'rm -f /system/app/InniClassic.apk /system/app/InniClassic.odex'
ady push "$APK" /system/app/zeny.apk >/dev/null
ady shell 'chown root:root /system/app/zeny.apk; chmod 644 /system/app/zeny.apk; sync'

# Пропатченный SystemUI (tools/systemui) выкладывается тем же заходом, чтобы хватило одной перезагрузки.
# Старый odex к новому dex не подходит, поэтому удаляется.
if [ -n "${ZENY_SYSTEMUI:-}" ]; then
  [ -f "$ZENY_SYSTEMUI" ] || { echo "нет $ZENY_SYSTEMUI"; exit 1; }
  ady shell 'rm -f /system/app/SystemUI.odex'
  ady push "$ZENY_SYSTEMUI" /system/app/SystemUI.apk >/dev/null
  ady shell 'chown root:root /system/app/SystemUI.apk; chmod 644 /system/app/SystemUI.apk; sync'
  echo "SystemUI заменён"
fi
echo "выложено, перезагружаю плеер (первый запуск после смены APK около 20 с)"
ady shell 'reboot'
