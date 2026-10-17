# Innioasis Y1: железо, разделы, прошивка и откат

## Железо

| | |
|---|---|
| SoC | MediaTek MT6572 (hwcode 0x6572), двухъядерный ARM Cortex-A7 |
| Память | 512 МБ ОЗУ (свободно после очистки около 230 МБ), eMMC 3.6 ГБ |
| Система | Android 4.2.2 (API 17), 32-бит `armeabi-v7a` |
| Экран | 480x360, альбомный, сенсор + колесо (кнопки D-pad) |
| Батарея | 1250 мАч (заявлено заводом: 25-35 ч музыки; независимых замеров нет) |
| Музыка | microSD, `/storage/sdcard0` (vfat, до 128 ГБ проверено на 116 ГБ); `/storage/sdcard1`: 395 МБ внутренней FAT |
| Защита загрузки | SBC/SLA/DAA выключены: BROM доступен без авторизации |

## Таблица разделов (Type A)

Снята через `mtk.py printgpt`, совпадает со scatter-файлом прошивки Rockbox-Y1:

| Раздел | Смещение | Размер |
|---|---|---|
| PRELOADER | 0x0 | 0x1400000 |
| MBR / EBR1 | 0x1400000 / 0x1480000 | |
| PRO_INFO | 0x1500000 | 0x300000 |
| NVRAM | 0x1800000 | 0x500000 |
| PROTECT_F / PROTECT_S | 0x1d00000 / 0x2700000 | 0xa00000 |
| SECCFG | 0x3100000 | 0x20000 |
| UBOOT | 0x3120000 | 0x60000 |
| BOOTIMG | 0x3180000 | 0x600000 |
| RECOVERY | 0x3780000 | 0x600000 |
| SEC_RO | 0x3d80000 | 0x40000 |
| MISC | 0x3dc0000 | 0x80000 |
| LOGO | 0x3e40000 | 0x300000 |
| EXPDB | 0x4140000 | 0xa00000 |
| ANDROID (system) | 0x4b40000 | 0x28a00000 |
| CACHE | 0x2d540000 | 0x20000000 |
| USRDATA | 0x4d540000 | 0x80000000 |
| FAT (внутренняя) | 0xcd540000 | 0x18bc0000 |

Type A: плееры с ОС 2.0.0 и новее; Type B: ранние партии (другой `rom_type_b.zip`). Если после прошивки
не работает колесо, это Type B.

## Бэкап стока (делать до любых записей)

Прошивальщик: `tools/mtk/` (README там же). Плеер выключен, кабель вытащен, потом воткнут уже после
запуска команды. Одна команда за сессию:

```sh
cd tools/mtk/upd-repo
rm -f .state hwparam.json
B=~/zeny-y1-backup
../venv/bin/python mtk.py r \
  mbr,ebr1,pro_info,nvram,protect_f,protect_s,seccfg,uboot,bootimg,recovery,sec_ro,misc,logo,expdb,android \
  $B/mbr.bin,$B/ebr1.bin,$B/pro_info.bin,$B/nvram.bin,$B/protect_f.bin,$B/protect_s.bin,$B/seccfg.bin,\
$B/uboot.bin,$B/bootimg.bin,$B/recovery.bin,$B/sec_ro.bin,$B/misc.bin,$B/logo.bin,$B/expdb.bin,$B/android.bin
```

Размер `android.bin` должен получиться 681574400 байт. Чтение идёт около 2.4 МБ/с.

Бэкап лежит вне репозитория (там NVRAM, то есть калибровки и серийные данные конкретного плеера).

## Как плеер был прошит

1. Основа: `rom.zip` Rockbox-Y1 stable-v0.5 (Type A, sha256 `b1298aa62c95e70ffc36d49eb090b7034b62fb1f5d589382dcee6a533ffcf382`).
   Её `boot.img` и `lk.bin` побайтно совпадают с официальной ROM InniClassic, платформа одна.
2. Запись разделов так же, как делает Updater CE: `mtk.py w logo,uboot,bootimg,recovery,android,usrdata ...`.
   MBR, EBR, preloader, secro и cache не трогаются, внутренняя FAT остаётся нетронутой.
3. Дальше всё правится по adb на включённом плеере: [SYSTEM.md](SYSTEM.md).
4. `logo` перезаписывается отдельно образом из `scripts/make-logo.py`.

## Откат на сток

- Любой раздел: записать обратно файл из бэкапа тем же `mtk.py w <раздел> <файл>`.
- Целиком: Innioasis Updater CE (innioasis.app) → Original Software.
- Образы заводских приложений, которые были убраны с плеера, лежат в `backup/system-removed/`
  (в git не входят).

## Выключение, зарядка, комбинации

- Не включается после записи: удерживать центральную кнопку.
- Не выключается: скрепкой в маленькое отверстие сброса.
- В родной прошивке Rockbox-Y1 комбинации Назад+Play 10 с и Влево+Вправо 10 с переключали Rockbox; скрипт
  с этими комбинациями удалён.
