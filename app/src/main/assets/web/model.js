"use strict";

const Cortex = (() => {
  const key = "cortex.interface.v1";
  const colors = [
    { hue: 186, label: "Лёд" },
    { hue: 224, label: "Кобальт" },
    { hue: 265, label: "Аметист" },
    { hue: 300, label: "Орхидея" },
    { hue: 33, label: "Янтарь" },
    { hue: 162, label: "Мята" }
  ];
  const voices = ["Естественный", "Мягкий", "Низкий"];
  const tones = ["Спокойный и деловой", "Дружелюбный", "Лаконичный"];
  const styles = ["Подробно, с примерами", "Кратко и по делу", "По шагам"];
  const presets = [
    { id: "cortex", name: "CORTEX", art: "cortex", tag: "Универсальный", description: "Персональный ассистент для Android. Повседневные задачи и быстрые команды." },
    { id: "business", name: "Деловой", art: "business", tag: "Продуктивность", description: "Задачи, документы, планирование и чёткие ответы по делу." },
    { id: "orbit", name: "Спутник", art: "orbit", tone: tones[1], tag: "На каждый день", description: "Простой и быстрый помощник для повседневных задач." },
    { id: "night", name: "Ночной", art: "night", tone: tones[2], style: styles[1], tag: "Спокойный режим", description: "Короткие ответы, спокойный тон и минимум отвлечений." }
  ];
  const categories = [
    { id: "personal", label: "Личное", icon: "user" },
    { id: "habits", label: "Привычки", icon: "wave" },
    { id: "important", label: "Важное", icon: "star" }
  ];
  const instructionFields = [
    { id: "goal", label: "Цель помощника", icon: "target", value: "Помогай мне с повседневными задачами, организуй информацию и объясняй сложное простыми словами." },
    { id: "reply", label: "Как отвечать", icon: "message", value: "Отвечай понятно и по делу. Для сложных задач предложи короткий план и объясни шаги." },
    { id: "actions", label: "Действия без подтверждения", icon: "bolt", value: "Показывай безопасные шаги, предлагай поиск и помогай составлять черновики." },
    { id: "confirm", label: "Когда спрашивать меня", icon: "shield", value: "Перед отправкой сообщений, удалением файлов и передачей данных уточняй мой выбор." }
  ];
  const defaults = {
    hue: colors[0].hue, reduceMotion: false, reduceGlass: false, powerSave: false,
    microphone: false, voiceOutput: false, overlay: false, notifications: true,
    keepHistory: true, askFirst: true
  };
  const commands = {
    telegram: {
      icon: "telegram", title: "Открываю Telegram…", result: "Сценарий Telegram завершён",
      description: "Показаны этапы подготовки, запуска и проверки приложения.",
      type: "actions", steps: [
        ["Анализирую запрос", "Распознаю приложение и намерение"],
        ["Запускаю приложение", "Показываю запуск Telegram"],
        ["Проверяю результат", "Показываю проверку открытия"]
      ]
    },
    file: {
      icon: "file", title: "Ищу файл…", result: "Найден пример документа",
      description: "Пример результата поиска · Отчёт_январь.pdf",
      type: "files", steps: [
        ["Уточняю параметры", "Разбираю тип и название файла"],
        ["Ищу совпадения", "Просматриваю демонстрационный каталог"],
        ["Готовлю результат", "Показываю пример найденного файла"]
      ]
    },
    request: {
      icon: "message", title: "Разбираю запрос…", result: "Запрос обработан в деморежиме",
      description: "Интерфейс показал ход обработки текстового запроса.",
      type: "requests", steps: [
        ["Читаю запрос", "Выделяю цель команды"],
        ["Подбираю шаги", "Формирую демонстрационный план"],
        ["Показываю результат", "Добавляю запись в журнал"]
      ]
    }
  };

  function dateAt(hours, minutes, daysAgo = 0) {
    const date = new Date();
    date.setDate(date.getDate() - daysAgo);
    date.setHours(hours, minutes, 0, 0);
    return date.toISOString();
  }

  const examples = [
    { id: "demo-1", type: "actions", icon: "telegram", title: "Telegram открыт", description: "Пример запуска приложения", query: "Открой Telegram", date: dateAt(12, 28), result: "demo" },
    { id: "demo-2", type: "files", icon: "file", title: "Найден документ", description: "Отчёт_январь.pdf", query: "Найди январский отчёт", date: dateAt(11, 47), result: "demo" },
    { id: "demo-3", type: "requests", icon: "message", title: "Помощь с математикой", description: "Пример разбора уравнения", query: "Помоги решить уравнение", date: dateAt(10, 32), result: "demo" },
    { id: "demo-4", type: "requests", icon: "search", title: "Поиск в интернете", description: "Погода в Москве", query: "Какая погода в Москве?", date: dateAt(19, 14, 1), result: "demo" }
  ];

  function read() {
    try {
      const saved = JSON.parse(localStorage.getItem(key));
      return saved && typeof saved === "object" ? saved : {};
    } catch {
      return {};
    }
  }

  const stored = read();
  const state = {
    preferences: { ...defaults, ...stored.preferences },
    custom: Array.isArray(stored.custom) ? stored.custom : [],
    edits: stored.edits || {},
    activeId: stored.activeId || "cortex",
    recent: Array.isArray(stored.recent) ? stored.recent : ["cortex"],
    history: Array.isArray(stored.history) ? stored.history : examples,
    notes: Array.isArray(stored.notes) ? stored.notes : [],
    memories: stored.memories || {},
    instructions: stored.instructions || {}
  };

  function save() {
    try {
      localStorage.setItem(key, JSON.stringify(state));
      return true;
    } catch {
      return false;
    }
  }

  function id(prefix) {
    return prefix + "-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 8);
  }

  function profiles() {
    return [...presets, ...state.custom].map(profile => ({
      voice: voices[0], tone: tones[0], style: styles[0], ...profile, ...state.edits[profile.id]
    }));
  }

  function profile(profileId = state.activeId) {
    return profiles().find(item => item.id === profileId);
  }

  function activate(profileId) {
    if (!profile(profileId)) return;
    state.activeId = profileId;
    state.recent = [profileId, ...state.recent.filter(item => item !== profileId)].slice(0, 8);
  }

  function memory() {
    if (!state.memories[state.activeId]) {
      const starter = state.activeId === "cortex" ? [
        { id: "mem-language", title: "Предпочитаемый язык", value: "Русский", category: "personal", enabled: true, example: true, icon: "globe" },
        { id: "mem-apps", title: "Частые приложения", value: "Telegram, Заметки", category: "habits", enabled: true, example: true, icon: "grid" },
        { id: "mem-style", title: "Стиль работы", value: "По шагам, с коротким итогом", category: "important", enabled: true, example: true, icon: "star" }
      ] : [];
      state.memories[state.activeId] = { enabled: true, entries: starter };
    }
    return state.memories[state.activeId];
  }

  function addHistory(entry) {
    if (!state.preferences.keepHistory) return;
    state.history.unshift({ id: id("event"), date: new Date().toISOString(), ...entry });
    state.history = state.history.slice(0, 100);
  }

  if (!profile()) state.activeId = "cortex";

  return { state, defaults, presets, colors, voices, tones, styles, categories, instructionFields, commands,
    save, id, profiles, profile, activate, memory, addHistory };
})();
