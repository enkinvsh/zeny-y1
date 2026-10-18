#!/bin/bash
# Цифровой стенд звука одной командой: стимулы -> Opus -> настоящий ZenyAudioProcessor (Java и нативное
# ядро) -> анализ. Результаты в work/audio-lab/results, картинки отчёта копируются в tools/audio-lab/img.
set -euo pipefail
source "$(dirname "$0")/../../scripts/env.sh"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$Y1_ROOT/work/audio-lab"
SRC="$Y1_ROOT/android/app/src/main/java/com/themoon/y1/managers"
PY="$WORK/venv/bin/python"
# Без __pycache__ в tools/: каталог в git, кеш байткода там лишний
export PYTHONDONTWRITEBYTECODE=1

# Хостовая сборка нативного ядра кладёт libzenydsp.dylib в work/dsp-test (как tools/dsp-test/run.sh)
"$Y1_ROOT/scripts/build-native.sh" > /dev/null

if [ ! -x "$PY" ]; then
  uv venv -q --python 3.12 "$WORK/venv"
  VIRTUAL_ENV="$WORK/venv" uv pip install -q numpy scipy matplotlib
fi

CORE="$(find ~/.gradle -name classes.jar -path '*exoplayer-core-2.13.3*' | head -n1)"
COMMON="$(find ~/.gradle -name classes.jar -path '*exoplayer-common-2.13.3*' | head -n1)"
CP="$CORE:$COMMON:$ANDROID_HOME/platforms/android-34/android.jar"

# Всё, кроме venv, пересоздаётся с нуля: прогон не должен зависеть от остатков прошлого
rm -rf "$WORK/sig" "$WORK/opus" "$WORK/dec" "$WORK/out" "$WORK/results" "$WORK/classes"
mkdir -p "$WORK/classes"
"$JAVA_HOME/bin/javac" -nowarn -d "$WORK/classes" -cp "$CP" \
  "$SRC/ZenyNative.java" "$SRC/ZenyAudioProcessor.java" "$HERE/Render.java"

"$PY" "$HERE/gen_signals.py" "$WORK"

# Матрица: T2 = прозрачный режим по умолчанию (Air 0, кроссфид 0), T3 = «Воздух» шаг 3 (+12 дБ, середина
# ряда). AIR1..AIR5 = все шаги «Воздуха» (+6/+9/+12/+15/+18 дБ) на коротком наборе стимулов; худший случай
# по искажениям и запасу пиков - AIR5.
# Суффикс L - вход без потерь (истина), C - вход после Opus. T2S/T3S - Air 0/Air +12 + Sound Check с тегом трека.
for core in java native; do
  JOBS="$WORK/out/jobs-$core.txt"
  mkdir -p "$WORK/out/$core"/{T2L,T3L,T2C,T3C,T2S,T3S,AIR1,AIR2,AIR3,AIR4,AIR5,STAGE1,STAGE2,STAGE3}
  : > "$JOBS"
  O="$WORK/out/$core"
  for f in "$WORK"/sig/*.wav; do
    n="$(basename "$f")"
    echo "$f $O/T2L/$n 0 0 0 -" >> "$JOBS"
    echo "$f $O/T3L/$n 3 0 0 -" >> "$JOBS"
  done
  for f in "$WORK"/dec/*.wav; do
    n="$(basename "$f")"
    echo "$f $O/T2C/$n 0 0 0 -" >> "$JOBS"
    echo "$f $O/T3C/$n 3 0 0 -" >> "$JOBS"
  done
  for g in trap rock soul; do
    tag="$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1]))['music_$g']['tag_db'])" "$WORK/manifest.json")"
    echo "$WORK/dec/music_$g.wav $O/T2S/music_$g.wav 0 0 1 $tag" >> "$JOBS"
    echo "$WORK/dec/music_$g.wav $O/T3S/music_$g.wav 3 0 1 $tag" >> "$JOBS"
  done
  for a in 1 2 3 4 5; do
    for n in pink_mono.wav sine_62_m6.wav sine_1000_m3.wav imd.wav; do
      echo "$WORK/sig/$n $O/AIR$a/$n $a 0 0 -" >> "$JOBS"
    done
  done
  for s in 1 2 3; do
    for n in pink_left.wav pink_right.wav pink_mono.wav pink.wav; do
      echo "$WORK/sig/$n $O/STAGE$s/$n 0 $s 0 -" >> "$JOBS"
    done
  done
  native=0; [ "$core" = native ] && native=1
  "$JAVA_HOME/bin/java" -Dzeny.native=$native -Djava.library.path="$Y1_ROOT/work/dsp-test" \
    -cp "$WORK/classes:$CP" Render "$JOBS"
done

"$PY" "$HERE/analyze.py" --selftest
"$PY" "$HERE/analyze.py" "$WORK"
mkdir -p "$HERE/img"
cp "$WORK"/results/plots/*.png "$HERE/img/"
echo "готово: $WORK/results/summary.json, картинки в $HERE/img"
