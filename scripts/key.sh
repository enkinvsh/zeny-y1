#!/bin/bash
# Нажатия кнопок в эмуляторе: key.sh down down enter back ... (по имени); пауза после каждой.
source "$(dirname "$0")/env.sh"
for k in "$@"; do
  case "$k" in
    down) c=KEYCODE_DPAD_DOWN;; up) c=KEYCODE_DPAD_UP;; left) c=KEYCODE_DPAD_LEFT;; right) c=KEYCODE_DPAD_RIGHT;;
    enter) c=KEYCODE_DPAD_CENTER;; back) c=KEYCODE_BACK;; menu) c=KEYCODE_MENU;; play) c=KEYCODE_MEDIA_PLAY_PAUSE;;
    *) c="$k";;
  esac
  adbe shell input keyevent "$c"; sleep 0.5
done
