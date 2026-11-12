# Публикация CORTEX 0.8.8 в GitHub

Репозиторий: https://github.com/stasaleksandrov481-netizen/CORTEX

**Что загружать:** все файлы и папки этого проекта **в корень ветки `main`**, а не ZIP целиком и не папку `CORTEX_0.8.7_ANDROID_STUDIO_FULL` внутри репозитория. Существующие файлы нужно обновить; лишние от старой версии 0.2.0 (например, `openrouter.js`) лучше убрать после проверки. Исторический APK 0.8.6 и прототипы в `reference/` не требуются.

Файл `.github/workflows/android-apk.yml` запускает GitHub Actions автоматически после загрузки в `main`. При успешной компиляции появится релиз:

https://github.com/stasaleksandrov481-netizen/CORTEX/releases/tag/v0.8.8

Там нужно открыть Assets и скачать `CORTEX-0.8.8-Island-debug.apk`.

**При ошибке сборки** GitHub Release не появится. Смотрите Actions → CORTEX 0.8.8 Android APK and Release → Build CORTEX with Island. Не подставляйте вместо этой сборки старый APK 0.8.6.

**Только для тестирования**: debug-подпись может отличаться от подписи ранее установленных APK. Для постоянных обновлений потребуются отдельный приватный release-keystore и секреты GitHub Actions; никогда не храните его в публичном репозитории.
