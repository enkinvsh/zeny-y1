# mtkclient для Y1 (запись разделов по USB в режиме BROM/preloader)

Нужен только для прошивки разделов, которые нельзя поменять через adb: `logo` (загрузочный логотип и экран
зарядки) и полная прошивка `android` + `usrdata`. Всё остальное делается по adb на включённом плеере.

```sh
tools/mtk/setup.sh        # клонирует Updater CE (только mtk.py, mtkclient, Tools) и ставит venv
```

## Порядок записи (проверен на живом Y1, MT6572)

Старый mtkclient 2.0.1 капризен, порядок важен:

1. Плеер выключить полностью (`adb shell reboot -p`), кабель вытащить.
2. В каталоге `upd-repo`: `rm -f .state hwparam.json` (иначе падает `meid None` или
   «Please disconnect, start mtkclient and reconnect»).
3. Запустить команду, пока кабеля нет:
   `../venv/bin/python mtk.py w logo /путь/к/logo-zeny.bin > flash.log 2>&1 &`
4. Воткнуть кабель. Y1 виден как `0e8d:2000` всего около 2.4 с, клиент ловит его после нескольких
   «Handshake failed, retrying», это нормально.
5. Дождаться `Wrote ...` в логе. Одна команда за сессию: `script` и `multi` у этой версии падают.
6. Включить плеер удержанием центральной кнопки.

Скорость записи около 5.4 МБ/с, чтения около 2.4 МБ/с.

## Что писать

Таблица разделов Type A (совпадает со сканом прошивки Rockbox-Y1): `uboot`, `bootimg`, `recovery`, `logo`,
`android` (system, 0x28a00000), `usrdata`. `preloader`, `nvram`, `protect_*`, `seccfg`, `secro`: не трогать.
Перед любой записью снимите бэкап теми же командами `r`, см. `docs/DEVICE.md`.
