#!/bin/bash
# Собирает нативное ядро DSP (android/app/src/main/cpp/zenydsp.c).
#  - ARM: android/app/src/main/jniLibs/armeabi-v7a/libzenydsp.so (подхватывается Gradle, не коммитится)
#  - хост:  work/dsp-test/libzenydsp.dylib для проверки на Mac тем же Harness.java
# Файл намеренно без libc/libm: плеер на Android 4.2, а NDK 28 собирает под API 21+, и любой импорт
# из libc сломал бы загрузку. Проверка `nm -u` в конце падает, если в библиотеке появились импорты.
set -euo pipefail
source "$(dirname "$0")/env.sh"

SRC="$Y1_ROOT/android/app/src/main/cpp/zenydsp.c"
NDK="$(ls -d "$ANDROID_HOME"/ndk/* | sort | tail -n1)"
BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
OUT_ARM="$Y1_ROOT/android/app/src/main/jniLibs/armeabi-v7a"
OUT_HOST="$Y1_ROOT/work/dsp-test"
mkdir -p "$OUT_ARM" "$OUT_HOST"

# -ffp-contract=off: без слитых умножений-сложений результат совпадает с Java побитово-близко.
# -fno-builtin/-ffreestanding: компилятор не превращает циклы в memset/memcpy из libc.
# -mfpu=neon: достаточно VFPv3-D32, FMA (vfpv4) не нужен и не используется.
"$BIN/armv7a-linux-androideabi21-clang" -O3 -shared -fPIC -nostdlib -ffreestanding -fno-builtin \
  -march=armv7-a -mfpu=neon -mfloat-abi=softfp -ffp-contract=off -fno-math-errno -fno-unwind-tables \
  -fno-asynchronous-unwind-tables -Wall -Wextra -Wl,-z,noexecstack -Wl,--hash-style=both -Wl,--build-id=none \
  -o "$OUT_ARM/libzenydsp.so" "$SRC"

UNDEF="$("$BIN/llvm-nm" -u "$OUT_ARM/libzenydsp.so" || true)"
if [ -n "$UNDEF" ]; then
  echo "в libzenydsp.so появились внешние символы:"; echo "$UNDEF"; exit 1
fi

# Хостовая сборка нужна только для сравнения с Java; JNI-заголовки берём у установленного JDK
clang -O2 -shared -fPIC -ffp-contract=off -fno-math-errno \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  -o "$OUT_HOST/libzenydsp.dylib" "$SRC"

echo "готово: $OUT_ARM/libzenydsp.so ($(wc -c < "$OUT_ARM/libzenydsp.so") байт), $OUT_HOST/libzenydsp.dylib"
