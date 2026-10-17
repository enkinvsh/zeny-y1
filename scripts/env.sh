# Общее окружение стенда Y1: JDK 17 для Gradle 8.7/AGP 8.6, SDK из brew, адреса adb.
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ADB="$ANDROID_HOME/platform-tools/adb"
# На Mac одновременно виден Pixel владельца по Wi-Fi - всегда адресуем эмулятор явно.
adbe() { "$ADB" -s emulator-5554 "$@"; }
export Y1_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
