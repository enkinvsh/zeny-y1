# Авторство и лицензии

## Основа приложения

`android/` это локальный форк **InniClassic 1.4.5** (FabianZettl), коммит
`7d9ee95f1f7503ff8e1bc36f3da7d4594c628c72` («1.4.5: Add missing punctuation to the on-screen keyboard»,
2026-09-29). InniClassic построен на **JJ Launcher / MO-ON Launcher**
(github.com/ismileblue/y1_launcher, автор ismileblue): медиа-движок, система тем, веб-сервер и большая
часть экранов принадлежат им.

Лицензия InniClassic (`android/LICENSE`): «Custom License (Non-Commercial Use Only)». Бесплатно для
личного, учебного и некоммерческого использования, включая изменение и распространение; коммерческое
использование требует отдельной платной лицензии автора. Из этого следует:

- код `android/` можно использовать только в некоммерческих целях;
- продавать сборки с кодом `android/` или встраивать его в коммерческие продукты без согласования с автором нельзя;
- изменения, сделанные здесь, никуда выше по цепочке не отправлялись.

Приложение не связано с Apple Inc. и Innioasis; названия устройств упомянуты для совместимости.

## Прошивка и инструменты

- **Rockbox-Y1** stable-v0.5 (rockbox-y1/rockbox, GPL-2.0, форк Rockbox): исходная прошивка плеера. В
  репозиторий не входит, Rockbox с плеера удалён.
- **Innioasis Updater CE** (y1-community/Innioasis-Updater): оттуда берётся вшитый mtkclient 2.0.1
  (`tools/mtk/setup.sh`, GPL-3.0). В репозиторий не входит.
- **baksmali / smali** 2.5.2 (JesusFreke, BSD-3) и **AOSP**: патч SystemUI применяется к SystemUI прошивки;
  подпись публичным тестовым ключом платформы AOSP (`aosp-mirror/platform_build`, Apache-2.0).
  APK, odex и ключи в репозиторий не входят.

## Лицензия своих частей

Скрипты, собственные инструменты в `tools/`, документация и тема: PolyForm Noncommercial License 1.0.0 (`LICENSE`).
Личное, учебное и любое другое некоммерческое использование бесплатно, продажа и коммерческое использование без отдельной
лицензии автора запрещены. `android/` подчиняется лицензии InniClassic (см. выше), шрифты и сторонние компоненты своим.

## Шрифты и графика

- **Onest** и **Unbounded**: SIL Open Font License 1.1 (`theme/zeny/OFL.txt`).
- **Nimbus Sans** (внутри тем iPod Classic в `android/app/src/main/assets/themes`): GUST Font License / URW.
- **Material Icons** (`android/app/src/main/assets/fonts`): Apache-2.0.
- Роботы (`theme/robots`) и карточки: иллюстрации zeny.
- Иконки батареи и громкости в приложении нарисованы кодом по мотивам набора **Hugeicons** (MIT; 24x24, линия 1.5).

## Библиотеки

ExoPlayer, OkHttp, Conscrypt: Apache-2.0; FFmpeg/libopus/libFLAC: расширения ExoPlayer (LGPL/BSD);
libVLC: LGPL (в составе upstream).
