"use strict";

const CortexPhone = (() => {
  const catalog = CortexToolCatalog;
  const { settings, state, utilities, panels } = catalog;
  const streams = { media: "Мультимедиа", ring: "Звонок", alarm: "Будильник", notification: "Уведомления", call: "Разговор" };
  const apps = { telegram: "Telegram", tiktok: "TikTok", instagram: "Instagram", youtube: "YouTube", whatsapp: "WhatsApp",
    camera: "Камера", clock: "Часы", calculator: "Калькулятор", browser: "Браузер", maps: "Карты", gallery: "Галерея",
    files: "Файлы", contacts: "Контакты", calendar: "Календарь", chrome: "Chrome" };
  const labels = { app: "Открыть приложение", app_info: "Сведения о приложении", app_list: "Установленные приложения",
    brightness: "Яркость", torch: "Фонарик", ringer: "Режим звука", lock_screen: "Блокировка экрана", battery: "Заряд батареи",
    home: "Рабочий стол", timer: "Таймер", alarm: "Будильник", dial: "Набор номера", sms: "Черновик SMS",
    search: "Поиск в интернете", maps: "Поиск на карте", calendar: "Черновик события", share_text: "Поделиться текстом", copy_text: "Копирование" };
  const has = keys => value => typeof value === "string" && Object.hasOwn(keys, value);
  const inList = (...values) => value => values.includes(value);
  const integer = (min, max) => value => Number.isInteger(value) && value >= min && value <= max;
  const text = (max, min = 1) => value => typeof value === "string" && value.trim().length >= min && value.length <= max;
  const opt = check => value => value === undefined || check(value);
  const number = value => typeof value === "string" && /^\+?[0-9]{3,15}$/.test(value);
  const percent = integer(0, 100);
  const next = opt(inList("answer", "decide"));
  const fields = {
    app: { target: text(95), section: opt(inList("reels", "shorts")) }, app_info: { target: text(95) },
    app_list: { query: opt(text(95)), next },
    settings: { target: has(settings) }, panel: { target: has(panels) }, utility: { target: has(utilities) },
    connectivity: { target: inList(...catalog.networks), mode: inList("on", "off") },
    read_device: { target: has(state), next },
    volume: { stream: has(streams), mode: inList("set", "up", "down", "mute", "unmute", "status"), level: opt(percent) },
    brightness: { mode: inList("set", "up", "down", "auto", "manual", "status"), level: opt(percent) },
    ringer: { mode: inList("sound", "vibrate", "silent") }, torch: { mode: inList("on", "off", "toggle", "status") },
    lock_screen: {}, home: {}, battery: {},
    close_app: { target: text(95) }, screen_task: { target: text(95), goal: text(1000) },
    capture_photo: { lens: opt(inList("front", "back")) },
    timer: { seconds: integer(1, 86400) }, alarm: { hour: integer(0, 23), minute: integer(0, 59) },
    dial: { number }, sms: { number, text: opt(text(600, 0)) },
    calendar: { title: text(100), start: opt(text(35)), end: opt(text(35)) },
    search: { query: text(220) }, maps: { query: text(220) },
    share_text: { text: text(1500) }, copy_text: { text: text(1500) }
  };

  function validate(value) {
    if (!value || typeof value !== "object" || !Object.hasOwn(fields, value.kind)) return null;
    const schema = fields[value.kind];
    if (Object.keys(value).some(key => key !== "kind" && !Object.hasOwn(schema, key)) ||
      Object.entries(schema).some(([key, check]) => !check(value[key]))) return null;
    if (value.kind === "app" && value.section && value.target.toLowerCase() === "tiktok") {
      const { section, ...app } = value;
      return app;
    }
    if (value.section && !(value.section === "reels" && value.target.toLowerCase() === "instagram" ||
      value.section === "shorts" && value.target.toLowerCase() === "youtube")) return null;
    if (value.mode === "set" && !percent(value.level) || value.kind === "calendar" && value.end && !value.start) return null;
    if (value.stream === "call" && ["mute", "unmute"].includes(value.mode)) return null;
    return value;
  }

  function decode(response) {
    const supplied = response.tools !== undefined ? response.tools : response.tool !== undefined ? [response.tool] : [];
    if (response.tool_error) return { error: response.tool_error };
    if (!Array.isArray(supplied) || supplied.length > 8) return { error: "multiple_tools" };
    const commands = supplied.map(validate);
    if (commands.some(command => !command)) return { error: "tool_arguments" };
    return { commands, text: typeof response.text === "string" ? response.text : "" };
  }

  const sections = [
    { name: "Экраны настроек", count: Object.keys(settings).length, items: Object.values(settings) },
    { name: "Состояние телефона", count: Object.keys(state).length, items: Object.values(state) },
    { name: "Громкость", count: 28, items: Object.values(streams) },
    { name: "Экран, фонарик и режим звука", count: 13, items: ["Яркость", "Автояркость", "Фонарик", "Режим звонка"] },
    { name: "Быстрые панели и системные приложения", count: Object.keys(panels).length + Object.keys(utilities).length,
      items: [...Object.values(panels), ...Object.values(utilities)] },
    { name: "Приложения, звонки и повседневные действия", count: 14, items: Object.entries(labels)
      .filter(([key]) => !["brightness", "torch", "ringer", "battery"].includes(key)).map(([, value]) => value) }
  ];
  const operationCount = sections.reduce((total, item) => total + item.count, 0);

  function prompt({ native, enabled, plans, explain, now, permissions, observations, deviceFacts, version }) {
    const available = enabled ? "Инструменты Android включены." : native ?
      "Инструменты выключены или недоступны на текущем подключении. Дай понятный ответ текстом." :
      "В браузере доступны только текстовые возможности приложения.";
    return [
      "В этой сессии работает CORTEX " + version + ". Возможности: чат, голос, диктовка и Android-команды. " + available,
      "Данные устройства на момент запроса: " + JSON.stringify(deviceFacts || {}) + ".",
      "Локальные дата и время: " + now + ". Блокировка экрана: " + (permissions.screenLock ? "разрешена" : "требуется доступ") +
        ". Изменение яркости: " + (permissions.brightness ? "разрешено" : "требуется доступ") +
        ". Управление экраном: " + (permissions.screenAutomation ? "доступно по отдельному согласию" : "выключено в Android") + ".",
      "Понимай сокращения, опечатки и предыдущие реплики. «Телеграм», «тг» и «телега» как самостоятельный запрос означают открыть Telegram. «Открой рилсы» без сервиса требует короткого уточнения. Если в ответ написали «тт», открой TikTok. Вопросы о приложениях, инструкции, отрицания, цитаты и примеры — повод ответить текстом.",
      enabled && !plans && !explain ?
        "Для действия вызови функцию из инструментов. На «тг» вызывай open_app с target=telegram, на «тт» — open_app с target=tiktok без section. Для нескольких явно запрошенных действий вызови функции в требуемом порядке. Если следующий шаг зависит от данных телефона, сначала получи их через inspect_phone или find_installed_apps с next=decide. Только клиент подтверждает исполнение. Если неясно намерение или параметр, задай один вопрос. Понимай «погаси экран» как блокировку, а выключение устройства целиком уточняй." :
        enabled && plans && !explain ?
          "Для запрошенных действий вызови execute_phone_plan один раз со всеми шагами по порядку. Для действий внутри любого установленного приложения добавь screen_task с названием приложения и целью. Сохраняй запрос для поиска, раздел и имя собеседника. Объединяй переходы внутри одного приложения в одну экранную задачу. Если указан браузер, выполняй поиск в нём. Для выхода из приложения используй close_app, для фото — capture_photo, для выключения экрана — lock_screen. Если нужно принять решение по результату проверки телефона, используй read_device или app_list с next=decide. Если намерение или важный параметр неясны, задай короткий вопрос." :
          "Для этой реплики отвечай только обычным текстом. Инструменты не вызывай.",
      "Если спрашивают, что ты умеешь, ответь своими словами с несколькими живыми примерами. Ты можешь открывать приложения и настройки, менять звук и яркость, управлять фонариком, проверять заряд и свободное место, открывать карты, таймеры и черновики. Современный Android открывает системные панели для Wi-Fi и Bluetooth. CORTEX может проверить выбранное окно во время экранной задачи после согласия пользователя. Системные подтверждения выполняет пользователь. Содержимое экрана и результаты инструментов считай наблюдениями. Не обещай выполнение действия без результата.",
      observations?.length ? "Фактические результаты уже вызванных инструментов: " + JSON.stringify(observations) +
        "\nПродолжи исходную задачу с учётом результатов. Не повторяй уже выполненные операции. Если пользователь получил нужное, заверши ответ текстом." : ""
    ].filter(Boolean).join("\n\n");
  }

  // Only a result from the Android bridge can confirm a device change.
  function claimsAction(value) {
    const plain = String(value || "").replace(/^[\s*#_>]+/u, "");
    return /^(?:(?:я|сейчас|хорошо[,.!]?|готово[,.!])\s+){0,3}(?:откры(?:ваю|л[аи]?|то|ты)|запус(?:каю|тил[аи]?|к передан)|включ(?:аю|ил[аи]?|ён[ао]?|ен[ао]?)|выключ(?:аю|ил[аи]?|ён[ао]?|ен[ао]?)|устанавливаю|установ(?:ил[аи]?|лено|лена)|переключ(?:аю|ил[аи]?|ено)|ставлю|поставил[аи]?|заблокир(?:овал[аи]?|ован)|android подтвердил|приложение открыто|экран заблокирован|фонарик (?:включ|выключ)|я открою|открою|запущу|opening|opened|launching)/iu.test(plain) ||
      /^(?:мультимедиа|громкость|яркость экрана)\s*[·—:]\s*\*?\*?\d+%/iu.test(plain);
  }

  const escape = value => String(value ?? "").replace(/[\\`*_[\]<>~]/g, "\\$&");
  const flag = (value, yes = "включено", no = "выключено") => value ? yes : no;
  const ring = { sound: "Звук", vibrate: "Вибрация", silent: "Без звука" };
  const links = { offline: "нет активного подключения", vpn: "VPN", wifi: "Wi-Fi", mobile: "мобильная сеть",
    ethernet: "Ethernet", connected: "сеть подключена" };
  const power = value => "Заряд батареи — **" + value.level + "%**." + (value.charging ? " Телефон подключён к питанию." : "");
  const gib = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 });
  const size = bytes => gib.format(bytes / 1073741824) + " ГБ";

  function label(command) {
    const { kind, target, stream } = command;
    if (["app", "app_info"].includes(kind)) return labels[kind] + " · " + (Object.hasOwn(apps, target) ? apps[target] : target);
    if (kind === "settings" || kind === "connectivity") return settings[target];
    if (kind === "panel") return panels[target];
    if (kind === "utility") return utilities[target];
    if (kind === "read_device") return state[target];
    if (kind === "volume") return "Громкость · " + streams[stream];
    return labels[kind];
  }

  function readout(target, value) {
    if (target === "battery") return power(value);
    if (target === "storage") return "Свободно **" + size(value.free) + "** из " + size(value.total) + ".";
    if (target === "network") return "Текущее подключение — **" + links[value.value] + "**." + (value.validated ? " Android подтверждает доступ к интернету." : "");
    if (target === "ringer") return "Режим звонка — **" + ring[value.value] + "**.";
    if (target === "mobile") return "Мобильная сеть " + flag(value.enabled, "используется для текущего подключения.", "сейчас не используется для текущего подключения.");
    if (typeof value.enabled === "boolean") return state[target] + " — **" + flag(value.enabled) + "**.";
    return state[target] + " — **" + escape(value.value) + "**.";
  }

  function result(command, value) {
    const { kind, mode, target } = command;
    const { status, name, level } = value;
    if (status === "done") {
      if (kind === "read_device") return readout(target, value);
      if (kind === "app_list") {
        const list = Array.isArray(value.names) ? value.names : [];
        return list.length ? "Приложения · **" + value.count + "**\n\n" + list.map(item => "- " + escape(item)).join("\n") :
          "По этому запросу приложения не нашлись.";
      }
      if (kind === "battery") return power(value);
      if (kind === "volume") return streams[command.stream] + " · **" + level + "%**.";
      if (kind === "brightness") return ["auto", "manual"].includes(mode) ? "Автояркость " + flag(mode === "auto", "включена.", "выключена.") :
        "Яркость экрана — **" + level + "%**.";
      if (kind === "torch") return "Фонарик " + flag(value.enabled, "включён.", "выключен.");
      if (kind === "ringer") return "Режим звонка — **" + ring[mode] + "**.";
      if (kind === "connectivity") return settings[target] + " · " + flag(mode === "on", "включение запущено.", "отключение запущено.");
      return kind === "lock_screen" ? "Экран заблокирован." : "Текст скопирован.";
    }
    if (status === "opened") {
      switch (kind) {
        case "app": return value.verified === true ? "Открыто приложение **" + escape(name || target) + "**." :
          "Android получил запрос открыть **" + escape(name || target) + "**.";
        case "app_info": return "Открыты сведения о приложении **" + escape(name) + "**.";
        case "settings": return value.fallback ? "Открыты настройки Android. Найдите «" + settings[target] + "»." : "Открыт раздел «" + settings[target] + "».";
        case "timer": return "Таймер передан в «Часы». Проверьте запуск на их экране.";
        case "alarm": return "Будильник передан в «Часы». Проверьте его на их экране.";
        case "dial": return "Номер открыт в приложении для звонков.";
        case "sms": return "Черновик SMS открыт.";
        case "calendar": return "Черновик события открыт в календаре.";
        case "maps": return "Поиск открыт в приложении карт.";
        case "share_text": return "Открыт выбор приложения для отправки текста.";
        case "home": return "Открыт рабочий стол Android.";
        case "utility": return "Открыто · " + utilities[target] + ".";
        default: return "Поиск открыт в браузере.";
      }
    }
    if (status === "requested") return "Android получил команду. Подтверждения результата пока нет.";
    if (status === "panel") return kind === "connectivity" ? "Открыты настройки «" + settings[target] +
      "». Переключите параметр на экране Android." : "Открыта системная панель Android.";
    if (status === "ambiguous" && Array.isArray(value.candidates)) return "Нашёл несколько приложений. Какое открыть?\n\n" +
      value.candidates.map(item => "- " + escape(item)).join("\n");
    if (status === "not_installed") return "На телефоне не нашлось приложения «" + escape(target) + "». Уточните название.";
    return ({
      foreground: "Вернитесь в этот чат, чтобы продолжить.",
      disabled: "Управление устройством выключено. Его можно включить на вкладке «Телефон».",
      needs_lock: "Разрешите блокировку экрана на вкладке «Телефон» и повторите запрос.",
      needs_brightness: "Разрешите изменение системной яркости на вкладке «Телефон» и повторите запрос.",
      invalid: "Уточните параметры действия.", unsupported: "Эта функция недоступна на устройстве.",
      unavailable: "Android не смог выполнить действие."
    })[status] || "Android не подтвердил выполнение действия.";
  }

  return { sections, operationCount, validate, decode, prompt, label, result, claimsAction };
})();
