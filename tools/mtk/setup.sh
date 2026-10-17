#!/bin/zsh
# Готовит старый mtkclient 2.0.1 (тот, что вшит в Innioasis Updater CE) для записи разделов Y1.
# Новый mtkclient 2.1.x не подходит: на прелоадере Y1 его переписанное рукопожатие не успевает за 2.4 с,
# пока устройство видно на шине как 0e8d:2000.
# Репозиторий Updater только читается (sparse clone), в него ничего не отправляется.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d upd-repo ]; then
  git clone --depth 1 --filter=blob:none --sparse https://github.com/y1-community/Innioasis-Updater.git upd-repo
  git -C upd-repo sparse-checkout set --no-cone /mtk.py /mtkclient /Tools /requirements.txt
  rm -rf upd-repo/.git
fi

[ -d venv ] || uv venv --python 3.12 venv
uv pip install -p venv/bin/python pyusb pycryptodome pycryptodomex colorama pyserial capstone unicorn keystone-engine mfusepy

(cd upd-repo && ../venv/bin/python mtk.py --help | head -1)
