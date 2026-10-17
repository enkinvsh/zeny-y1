#!/bin/bash
# Скриншот экрана эмулятора: shot.sh <имя> -> shots/<имя>.png (480x360)
set -euo pipefail
source "$(dirname "$0")/env.sh"
mkdir -p "$Y1_ROOT/shots"
adbe exec-out screencap -p > "$Y1_ROOT/shots/${1:-shot}.png"
echo "$Y1_ROOT/shots/${1:-shot}.png"
