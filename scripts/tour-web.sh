#!/bin/bash
# Экран веб-сервера во всех состояниях: выключен, работает, Wi-Fi выключен (красная карточка).
# Использование: scripts/tour-web.sh <префикс>; пункт «Веб-сервер» — девятое нажатие вниз от первого пункта.
source "$(dirname "$0")/env.sh"
P="${1:-tw}"
open_web() {
  adbe shell am force-stop com.themoon.y1
  adbe shell am start -n com.themoon.y1/.MainActivity > /dev/null
  sleep 5
  "$Y1_ROOT/scripts/key.sh" down down down down down down down down down > /dev/null
  "$Y1_ROOT/scripts/key.sh" enter > /dev/null
  sleep 1.5
}
adbe shell svc wifi enable
sleep 2
open_web
"$Y1_ROOT/scripts/shot.sh" "$P-1-stopped" > /dev/null
"$Y1_ROOT/scripts/key.sh" enter > /dev/null
sleep 1.5
"$Y1_ROOT/scripts/shot.sh" "$P-2-running" > /dev/null
"$Y1_ROOT/scripts/key.sh" enter > /dev/null
adbe shell svc wifi disable
sleep 3
open_web
"$Y1_ROOT/scripts/shot.sh" "$P-3-wifioff" > /dev/null
adbe shell svc wifi enable
