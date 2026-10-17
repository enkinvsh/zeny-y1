#!/bin/bash
# Обходит главное меню клавишей вниз и снимает панель справа на каждом пункте: проверка карточек-слэбов.
# Использование: scripts/tour-menu.sh <префикс>
source "$(dirname "$0")/env.sh"
P="${1:-t5}"
adbe shell am force-stop com.themoon.y1
adbe shell am start -n com.themoon.y1/.MainActivity > /dev/null
sleep 5
"$Y1_ROOT/scripts/shot.sh" "$P-00" > /dev/null
for i in $(seq 1 9); do
  "$Y1_ROOT/scripts/key.sh" down
  sleep 0.8
  "$Y1_ROOT/scripts/shot.sh" "$P-0$i" > /dev/null
done
