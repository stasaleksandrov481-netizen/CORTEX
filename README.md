# CORTEX

Android-ассистент с объёмной WebGL-волной, переходом с главной в чат и потоковыми ответами. Интерфейс на HTML, CSS и JavaScript работает в Android WebView. Kotlin открывает локальные ресурсы, хранит ключ и выполняет запросы к OpenRouter.

## Установить тестовую сборку

Откройте [Releases](https://github.com/stasaleksandrov481-netizen/CORTEX/releases) и скачайте `CORTEX-debug.apk` в разделе **Assets**. GitHub Actions собирает APK при каждом пуше в `main`. Результат появляется в **Actions → Android APK → Artifacts**. Ручной запуск доступен через **Run workflow**.

## Чат и память

Нажмите поле на главной, чтобы открыть новый чат. При первом запросе CORTEX предложит подключить OpenRouter. Ключ можно добавить или заменить через **Настройки → Подключение ИИ**. На Android ключ шифруется с помощью Android Keystore. В браузере он действует до обновления или закрытия вкладки.

Закладка в чате сохраняет переписку на устройстве. В **Настройки → Чаты и данные** можно включить сохранение новых чатов по умолчанию и использование контекста прошлых бесед. CORTEX передаёт текущий диалог в пределах окна контекста, включённые факты памяти и подходящие фрагменты сохранённых чатов активного ассистента.

Модель по умолчанию — [Gemma 4 26B A4B IT Free](https://openrouter.ai/google/gemma-4-26b-a4b-it:free) с резервным бесплатным маршрутом [openrouter/free](https://openrouter.ai/openrouter/free). Температура 0,42, top-p 0,9. Профиль меняет стиль и допустимую длину ответа. Android передаёт голосовой ввод системному сервису распознавания и вставляет текст в поле чата для проверки перед отправкой.

## Браузерный предпросмотр

Распакуйте исходники и откройте `app/src/main/assets/web/index.html` в Chrome или Edge. При доступном WebGL волна рисуется как трёхмерная геометрия. Настройка **Уменьшить движение** оставляет статичную волну.

## Собрать проект

Требуются JDK 17–25, Android SDK Platform 36 и Build Tools 36.0.0. Для первого запуска Gradle нужен интернет. Минимальная версия устройства — Android 8.0.

В PowerShell из корня проекта укажите путь к JDK и SDK.

```powershell
$env:JAVA_HOME = 'D:\Program Files\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --no-daemon :app:assembleDebug
```

На macOS или Linux установите `JAVA_HOME` и `ANDROID_HOME` и выполните `./gradlew --no-daemon :app:assembleDebug`. Результат сборки появится в `app/build/outputs/apk/debug/`.
