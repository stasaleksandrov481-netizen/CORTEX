# CORTEX 0.8.8 — Android Studio (полировка + островок)

**Что находится в ZIP:**
- `app/src/main/assets/web/` — веб-ресурсы CORTEX с визуальными доработками поверх 0.8.7 (HTML, CSS, JS, анимации, Markdown, локальный vendor).
- `app/src/main/java/app/cortex/assistant/` — открытый **воссозданный** Kotlin-код Android-оболочки: Activity, WebView, подключение Supabase, интерфейс Android-команд, голосовой ввод и TTS, разрешения и блокировка экрана.
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/` — манифест и исходные ресурсы.
- `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, Gradle-wrapper и `gradlew.bat` — сборка Android Studio.
- `reference/CORTEX_0.8.6_Install_Fixed.apk` — исходный APK для сравнения с пересборкой, **не исходный код**.
- `reference/optional_island_prototype_not_integrated/` — старый исторический прототип, **не использовать**. Нынешний островок находится в `app/src/main/java/app/cortex/assistant/CortexIslandService.kt` и интегрирован в проект.

## Честно о происхождении

В исходном APK **нет Kotlin `.kt` и Gradle-проекта** — Android-компоненты представлены компилированными файлами DEX. Ранний ZIP с оригинальным Android Studio-проектом 0.7.0 в этой сессии не доступен. **Нативная часть здесь воссоздана для редактирования и дальнейшей разработки, а не восстановлена побайтно или автоматически декомпилирована.** Поэтому её поведение может отличаться от установленного APK. В частности, некоторые системные инструменты исходного APK могут быть неполны или не поддерживаться в этом восстановленном проекте. Исходный APK сохранён в `reference/` для сравнения.

На этом окружении нет Android SDK и нет доступа к Android-зависимостям, поэтому **проект 0.8.8 не проходил полный Gradle build и установку на Android**. Проверены структура архива, синтаксис локальных JS-файлов и CSS, исходники нативного островка просмотрены вручную. Исправлен вызов несуществующего Editor.render().


## GitHub Actions: APK с островком

В `.github/workflows/android-apk.yml` добавлена автоматическая сборка Gradle 8.13, Android SDK 36, JDK 17. При успешном обновлении ветки `main` GitHub публикует тестовый релиз v0.8.8 с `CORTEX-0.8.8-Island-debug.apk`. **Сборка ещё не была выполнена на GitHub и её успех не гарантирован.** Подробности: `README_GITHUB_DEPLOY_RU.md`.

## Как открыть

1. Распакуйте ZIP на компьютере.
2. Откройте Android Studio → **Open** → папка `CORTEX_0.8.7_ANDROID_STUDIO_FULL`.
3. Выберите Gradle JDK **17** и дождитесь Sync. Требуются Android SDK Platform 36, Android Build Tools и интернет при первом Sync.
4. В Android Studio выберите Build → **Build APK(s)**. Или на Windows выполните `gradlew.bat :app:assembleDebug`.
5. APK появится в `app/build/outputs/apk/debug/`.

> В архив включён прозрачный минимальный Java-загрузчик Gradle вместо недоступного оригинального Gradle Wrapper JAR. Его исходник в `tools/bootstrap/GradleWrapperMain.java`; он загружает Gradle 8.13 **только с официального HTTPS-сервера** и сверяет SHA-256 из официального файла `.sha256`. После успешного Sync можно сгенерировать штатный Gradle Wrapper командой `gradle wrapper`.

## Островок (актуальный)

- `CortexIslandService.kt` — отображение поверх приложений и визуальные состояния.
- `CortexIslandBridge.kt` — разрешения и управление из WebView.
- `CortexWakeService.kt` — экспериментальная фоновая активация системным Android SpeechRecognizer.
- `ISLAND_README_RU.txt` — краткие шаги включения на телефоне.
- `CHANGELOG_0.8.8_RU.md` — список последней полировки.

## Где редактировать

- Главная, чат, профиль, настройки: `app/src/main/assets/web/screens.js`, `app.js`, `style.css`.
- Первый запуск и разрешения: `app/src/main/assets/web/setup.js`.
- Анимации и переходы: `wave.js`, `transitions.js`.
- Клиент серверного шлюза: `gateway.js` и Kotlin `CortexServerBridge.kt`.
- Native WebView: `MainActivity.kt`.
- Телефонные действия: `CortexDeviceBridge.kt`.
- Голос внутри приложения: `CortexSpeech.kt`.

## Сервер, ключи и безопасность

Код **серверной Supabase Edge Function не извлекался из APK**: backend находится отдельно в Supabase. В `CortexServerBridge.kt` указан публичный адрес проекта Supabase, но **секретные ключи провайдера и персональные токены в ZIP не включены**. Авторизация и хранение сессионных токенов используют Android Keystore. Для работы нужны действующая серверная конфигурация и интернет.

Нативный островок поверх других приложений и экспериментальная голосовая активация «Кортекс» **добавлены в источники 0.8.7**. Они требуют разрешений Android и проверки после сборки на конкретном телефоне. Полная система управления телефоном и универсальный контроль всех сторонних приложений в проекте не реализованы; такие возможности не следует представлять как готовые.

## Не путать с исходным APK

Этот архив **не APK** и не содержит оригинального Kotlin-кода авторов последнего бинарного APK. Он предназначен для продолжения разработки в Android Studio. Чтобы получить подлинные оригинальные Kotlin-исходники конкретной сборки, требуется прежний полный проект исходников, созданный перед компиляцией APK.
