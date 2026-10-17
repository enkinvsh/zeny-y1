#!/bin/bash
# Офлайн-проверка аудиодвижка на Mac без устройства: тот же ZenyAudioProcessor, что в приложении.
#   Harness - АЧХ, ресемплер, клиппер, Sound Check, AGC, форматы (запускается дважды: Java и нативное ядро)
#   Parity  - побитовая близость нативного ядра и Java-эталона (допуск 3 младших бита)
# Для замера на самом плеере: `run.sh device` (нужен adb и подключённый Y1).
set -euo pipefail
source "$(dirname "$0")/../../scripts/env.sh"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$Y1_ROOT/work/dsp-test"
SRC="$Y1_ROOT/android/app/src/main/java/com/themoon/y1/managers"
"$Y1_ROOT/scripts/build-native.sh" > /dev/null

CORE="$(find ~/.gradle -name classes.jar -path '*exoplayer-core-2.13.3*' | head -n1)"
COMMON="$(find ~/.gradle -name classes.jar -path '*exoplayer-common-2.13.3*' | head -n1)"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
CP="$CORE:$COMMON:$ANDROID_JAR"

rm -rf "$WORK/out" && mkdir -p "$WORK/out"
"$JAVA_HOME/bin/javac" -nowarn -d "$WORK/out" -cp "$CP" \
  "$SRC/ZenyNative.java" "$SRC/ZenyAudioProcessor.java" "$HERE/Harness.java" "$HERE/Parity.java" "$HERE/Bench.java"

if [ "${1:-}" = "device" ]; then
  # Dalvik-вариант: d8 с min-api 17, запуск через app_process (голый dalvikvm не видит android-классов)
  D8="$(ls -d "$ANDROID_HOME"/build-tools/*/d8 | sort | tail -n1)"
  ady() { "$ADB" -s 0123456789ABCDEF "$@"; }
  "$JAVA_HOME/bin/jar" cf "$WORK/bench-classes.jar" -C "$WORK/out" .
  "$D8" --min-api 17 --output "$WORK/bench.zip" "$WORK/bench-classes.jar" "$CORE" "$COMMON" --lib "$ANDROID_JAR" 2>/dev/null
  ady push "$WORK/bench.zip" /data/local/tmp/bench.zip > /dev/null
  ady push "$Y1_ROOT/android/app/src/main/jniLibs/armeabi-v7a/libzenydsp.so" /data/local/tmp/libzenydsp.so > /dev/null
  # Приложение глушим: иначе его собственный DSP искажает замер
  ady shell 'am force-stop com.themoon.y1'
  for mode in native java; do
    echo "== $mode"
    ady shell "export CLASSPATH=/data/local/tmp/bench.zip LD_LIBRARY_PATH=/data/local/tmp; app_process /data/local/tmp Bench $mode"
  done
  ady shell 'rm -f /data/local/tmp/bench.zip /data/local/tmp/libzenydsp.so'
  exit 0
fi

for mode in 0 1; do
  echo "== Harness, нативное ядро: $mode"
  "$JAVA_HOME/bin/java" -Dzeny.native=$mode -Djava.library.path="$WORK" -cp "$WORK/out:$CP" Harness | tail -n 3
done
"$JAVA_HOME/bin/java" -Djava.library.path="$WORK" -cp "$WORK/out:$CP" Parity
