# Патчи SystemUI: без заводских окон «USB mass storage», «низкий заряд», «неверная зарядка»

Зачем: при подключении кабеля SystemUI Y1 сам открывает английский диалог «USB mass storage» поверх
приложения (`com.android.systemui/.usb.UsbStorageActivity`). Экран подключения рисует теперь zeny
(`android/.../views/UsbPanel.java`), заводское окно мешает.

Нельзя просто отключить активность: `pm disable com.android.systemui/.usb.UsbStorageActivity` роняет
SystemUI на каждом USB-событии (`ActivityNotFoundException` в
`StorageNotification.updateUsbMassStorageNotification`, строка 485). Поэтому правится сам вызов.

## Что меняют патчи

`StorageNotification.patch`: diff smali-файла `com/android/systemui/usb/StorageNotification.smali`:

1. В `updateUsbMassStorageNotification` убран `startActivity(intent)`.
2. `Notification.fullScreenIntent` принудительно `null`: иначе окно всплывает через полноэкранный интент.

`PowerUI.patch`: diff `com/android/systemui/power/PowerUI.smali`:

1. `showLowBatteryWarning`: сразу после заводского broadcast `com.macxen.lowbatterywaring` (его ждёт
   приложение) выход через `goto :goto_78`; белое английское окно «Connect charger» не строится.
   Звук низкого заряда остаётся.
2. `showInvalidChargerDialog`: `return-void` в начале; про неверную зарядку приложение узнаёт само
   по extra `invalid_charger` в `ACTION_BATTERY_CHANGED`.

Обе карточки рисует приложение (`views/BatteryNotice.java`).

## Как собрать (проверено на прошивке Rockbox-Y1 stable-v0.5 / Android 4.2.2, API 17)

Нужны `baksmali`/`smali` 2.5.2 (fat-jar), `zipalign` и `apksigner` из build-tools, публичный AOSP-ключ
платформы (`platform.pk8` и `platform.x509.pem` из `aosp-mirror/platform_build`, `target/product/security`).

```sh
# Оригиналы с плеера: SystemUI.apk, SystemUI.odex и каталог framework (для деодекса)
adb pull /system/app/SystemUI.apk orig/SystemUI.apk
adb pull /system/app/SystemUI.odex orig/SystemUI.odex
adb pull /system/framework framework

java -jar baksmali.jar deodex -a 17 -d framework -o smali-out orig/SystemUI.odex
cp -r smali-out smali-patched
(cd smali-patched && patch -p1 < ../StorageNotification.patch && patch -p1 < ../PowerUI.patch)   # -p1 срезает префикс smali-out/
java -jar smali.jar assemble -a 17 -o classes.dex smali-patched

# APK = оригинал без META-INF + новый classes.dex
cp orig/SystemUI.apk SystemUI-unsigned.apk
zip -d SystemUI-unsigned.apk 'META-INF/*'
zip -j SystemUI-unsigned.apk classes.dex
zipalign -f 4 SystemUI-unsigned.apk SystemUI-aligned.apk
apksigner sign --key platform.pk8 --cert platform.x509.pem \
  --v1-signing-enabled true --v2-signing-enabled false --min-sdk-version 17 \
  --v1-signer-name PLATFORM --out SystemUI-patched.apk SystemUI-aligned.apk
```

## Почему подпись именно этим ключом

Прошивка подписана публичным тестовым ключом AOSP (`test-keys`): SHA1 сертификата
`27196e386b875e76adf700e7ea84e4c6eee33dfa`, он совпадает с подписью оригинального SystemUI.
Подпись любым другим ключом ломает запуск: `SecurityException: INTERACT_ACROSS_USERS_FULL` и SystemUI
не стартует.

## Установка на плеер

```sh
adb root && adb shell 'mount -o remount,rw /system'
adb shell 'rm -f /system/app/SystemUI.odex'      # старый odex не подходит к новому dex
adb push SystemUI-patched.apk /system/app/SystemUI.apk
adb shell 'chown root:root /system/app/SystemUI.apk; chmod 644 /system/app/SystemUI.apk; sync'
adb reboot
```

Замена системных APK на живой системе вызывает разовые `system_server_crash` в WallpaperManagerService и
падения процессов, это нормально, перезагрузка лечит.

Откат: вернуть оригинальные `SystemUI.apk` и `SystemUI.odex` из `backup/` и перезагрузить.
Бинарные файлы (APK, odex, ключи, jar) в репозиторий не кладутся.
