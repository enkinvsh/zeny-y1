#!/bin/zsh
# Отключает (или возвращает) системные пакеты Y1, которые плееру не нужны: обои, заставки, почта, календарь,
# синтезатор речи, резервное копирование и т.п. Список - в debloat-packages.txt.
# Именно `pm disable-user`, а не удаление файлов: откат одной командой, а загрузка проверена на этом наборе.
# 31 пакет дают минус 8 фоновых процессов и примерно +29 МБ свободной памяти из 494 МБ.
#
# Использование: scripts/debloat.sh [disable|enable]   (по умолчанию disable)
set -euo pipefail
source "$(dirname "$0")/env.sh"

SERIAL=0123456789ABCDEF
[ -x "$ADB" ] || { echo "не найден adb: поставьте brew install --cask android-platform-tools"; exit 1; }
MODE="${1:-disable}"
case "$MODE" in
  disable) CMD="pm disable-user --user 0" ;;
  enable)  CMD="pm enable" ;;
  *) echo "режим: disable|enable"; exit 2 ;;
esac

"$ADB" -s "$SERIAL" root >/dev/null 2>&1 || true
sleep 1
while read -r pkg; do
  [ -z "$pkg" ] && continue
  "$ADB" -s "$SERIAL" shell "$CMD $pkg" | tr -d '\r'
done < "$(dirname "$0")/debloat-packages.txt"
