"use strict";

const CortexUI = (() => {
  const M = Cortex;
  const state = M.state;
  const icon = CortexIcons;
  const esc = value => String(value ?? "").replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[c]);
  const nav = [
    ["home", "Главная", "home"],
    ["history", "История", "history"],
    ["assistants", "Ассистенты", "layers"],
    ["profile", "Профиль", "user"],
    ["settings", "Настройки", "settings"]
  ];
  const sections = [
    { id: "voice", icon: "mic", title: "Голос", subtitle: "Микрофон и озвучивание", tags: "громкость голосовой вывод звук" },
    { id: "interface", icon: "sliders", title: "Интерфейс", subtitle: "Движение и прозрачность", tags: "анимация эффекты энергосбережение" },
    { id: "access", icon: "shield", title: "Доступы", subtitle: "Разрешения и оверлей", tags: "уведомления специальные возможности файлы" },
    { id: "privacy", icon: "eye", title: "Приватность", subtitle: "Данные и история", tags: "очистка память журнал" },
    { id: "connection", icon: "link", title: "Подключение", subtitle: "OpenRouter и сервисы", tags: "api сеть приложения провайдер" },
    { id: "about", icon: "info", title: "О CORTEX", subtitle: "Версия и быстрый старт", tags: "настройка запуск информация" }
  ];
  const parent = {
    task: "home", notes: "home", memory: "profile", instructions: "profile",
    setup: "settings-about", ...Object.fromEntries(sections.map(item => ["settings-" + item.id, "settings"]))
  };
  const labels = { demo: "Демо", local: "Сохранено", stopped: "Остановлено", ai: "Ответ ИИ" };

  function iconButton(name, label, action, value = "", extra = "") {
    return `<button type="button" class="icon-button ${extra}" aria-label="${esc(label)}" title="${esc(label)}"
      data-action="${action}" data-value="${esc(value)}">${icon(name, 18)}${extra.includes("new") ? '<i class="unread"></i>' : ""}</button>`;
  }

  function button(label, name, action, value = "", extra = "") {
    return `<button type="button" class="button ${extra}" data-action="${action}" data-value="${esc(value)}">
      ${name ? icon(name, 17) : ""}<span>${esc(label)}</span></button>`;
  }

  function heading(title, actions = "", back = false) {
    return `<header class="page-head">${back ? iconButton("back", "Назад", "back") : ""}
      <h1>${esc(title)}</h1>${actions ? `<div class="head-actions">${actions}</div>` : ""}</header>`;
  }

  function symbol(name, extra = "") {
    return `<span class="symbol ${extra}">${icon(name, 18)}</span>`;
  }

  function badge(text, extra = "") {
    return `<span class="status-badge ${extra}">${extra === "stopped" ? icon("stop", 10) : icon("check", 11)}${esc(text)}</span>`;
  }

  function avatar(profile, large = false) {
    return `<span aria-hidden="true" class="avatar avatar-${esc(profile.art)}${large ? " large" : ""}">
      ${profile.art === "custom" ? icon("wave", large ? 36 : 24) : ""}</span>`;
  }

  function empty(name, title, message, cta = "") {
    return `<div class="glass-card empty-state">${symbol(name, "violet")}<h2>${esc(title)}</h2><p class="muted">${esc(message)}</p>${cta}</div>`;
  }

  function tabs(items, selected, action, segmented = false) {
    return `<div class="${segmented ? "segmented" : "filter-bar"}">${items.map(([value, label, name]) =>
      `<button type="button" class="chip" aria-pressed="${value === selected}" data-action="${action}" data-value="${value}">
        ${name ? icon(name, 13) : ""}<span>${label}</span></button>`).join("")}</div>`;
  }

  function row(name, title, subtitle, action, value = "", extra = "") {
    return `<button type="button" class="glass-row ${extra}" data-action="${action}" data-value="${esc(value)}">
      ${symbol(name, "compact")}
      <span class="row-text"><strong>${esc(title)}</strong><small>${esc(subtitle)}</small></span>
      <span class="row-chevron">${icon("chevron", 16)}</span></button>`;
  }

  function toggle(label, checked, action, value) {
    return `<button type="button" class="toggle" role="switch" aria-label="${esc(label)}" aria-checked="${Boolean(checked)}"
      data-action="${action}" data-value="${esc(value)}"><span></span></button>`;
  }

  function pref(name, title, subtitle, key) {
    return `<div class="glass-row">${symbol(name, "compact")}
      <span class="row-text"><strong>${esc(title)}</strong><small>${esc(subtitle)}</small></span>
      ${toggle(title, state.preferences[key], "preference", key)}</div>`;
  }

  function notice(text) {
    return `<div class="glass-card detail-intro">${icon("info", 17)}<p>${esc(text)}</p></div>`;
  }

  function levels() {
    return `<span class="levels" aria-hidden="true">${[8, 14, 21, 12, 17, 9].map((height, index) =>
      `<span style="--height:${height}px;--delay:-${index * 155}ms"></span>`).join("")}</span>`;
  }

  function home(unread) {
    return `<header class="home-bar">
      ${iconButton("focus", "Быстрые команды", "quick-menu", "", "raised")}
      <img class="home-wordmark" src="art/wordmark.svg" alt="CORTEX">
      ${iconButton("bell", "Уведомления", "notifications", "", unread ? "raised new" : "raised")}
    </header>
    <section class="home-core">
      <h1>Ваш AI-ассистент <span>для Android</span></h1>
      <div class="neural-field" aria-hidden="true"><img src="art/wave.svg" alt=""></div>
      <button class="listen-button" type="button" data-action="listen">${levels()}<span>Слушать</span></button>
      <div class="home-status"><i class="status-dot"></i>${esc(M.profile().name)} · готов к команде</div>
    </section>
    <section class="command-deck" aria-label="Команды">
      <form id="command-form" class="command-input">
        <input name="command" type="text" maxlength="400" aria-label="Введите команду" placeholder="Спросите или дайте команду…" autocomplete="off" enterkeyhint="send" required>
        <button type="submit" class="icon-button" aria-label="Отправить команду" title="Отправить команду">${icon("send", 19)}</button>
      </form>
      <div class="shortcuts">
        <button class="chip" type="button" data-action="run" data-value="telegram">${icon("telegram", 14)}<span>Открыть Telegram</span></button>
        <button class="chip" type="button" data-action="run" data-value="file">${icon("folder", 14)}<span>Найти файл</span></button>
        <button class="chip" type="button" data-action="note-edit">${icon("note", 14)}<span>Создать заметку</span></button>
      </div>
    </section>`;
  }

  const dayFormat = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "long" });
  const timeFormat = new Intl.DateTimeFormat("ru-RU", { hour: "2-digit", minute: "2-digit" });

  function dayLabel(date) {
    const today = new Date();
    const yesterday = new Date();
    yesterday.setDate(today.getDate() - 1);
    if (date.toDateString() === today.toDateString()) return "Сегодня";
    if (date.toDateString() === yesterday.toDateString()) return "Вчера";
    return dayFormat.format(date);
  }

  function searchable(value) {
    return value.trim().toLowerCase().replace(/\btelegram\b/gu, "телеграм").replace(/ё/g, "е");
  }

  function historyItems(filters) {
    const query = searchable(filters.historyQuery);
    const entries = state.history.filter(item =>
      (filters.history === "all" || filters.history === item.type) &&
      searchable([item.title, item.description, item.query].join(" ")).includes(query));
    if (!entries.length) return empty("history", "Записей пока нет", query ? "Попробуйте изменить поиск." : "Записи о командах появятся здесь.");
    let previous = "";
    return entries.map(item => {
      const date = new Date(item.date);
      const day = dayLabel(date);
      const section = previous === day ? "" : `<h2 class="section-label">${day}</h2>`;
      previous = day;
      return `${section}<button class="glass-row history-row" type="button" data-action="history-detail" data-value="${esc(item.id)}">
        ${symbol(item.icon)}<span class="row-text"><strong>${esc(item.title)}</strong><small>${esc(item.description)}</small></span>
        <span class="history-meta"><time datetime="${esc(item.date)}">${timeFormat.format(date)}</time>${badge(labels[item.result], item.result)}</span>
      </button>`;
    }).join("");
  }

  function search(label, name, value) {
    return `<label class="search-field">${icon("search", 16)}<input type="search" aria-label="${esc(label)}"
      placeholder="${esc(label)}" data-search="${name}" value="${esc(value)}" maxlength="120"></label>`;
  }

  function history(filters) {
    return heading("История", iconButton("search", "Поиск по истории", "search-history") +
      iconButton("dots", "Управление историей", "history-menu")) +
      (filters.searchHistory ? search("Поиск по истории", "history", filters.historyQuery) : "") +
      tabs([["all", "Все", "history"], ["actions", "Действия", "bolt"], ["requests", "Запросы", "message"], ["files", "Файлы", "file"]],
        filters.history, "filter-history") + `<div class="history-list" id="history-list">${historyItems(filters)}</div>`;
  }

  function assistants(filters) {
    const profiles = M.profiles();
    const list = filters.assistants === "recent" ? state.recent.map(id => M.profile(id)).filter(Boolean) :
      profiles.filter(item => (item.art === "custom") === (filters.assistants === "custom"));
    return `${heading("Ассистенты", iconButton("plus", "Создать ассистента", "assistant-edit"))}
      ${tabs([["system", "Системные"], ["custom", "Мои"], ["recent", "Недавние"]], filters.assistants, "filter-assistants", true)}
      <div class="stack assistant-list">${list.length ? list.map(profile => `
        <button class="glass-row assistant-row" type="button" data-action="assistant-detail" data-value="${esc(profile.id)}">
          ${avatar(profile)}<span class="assistant-copy">
            <span class="assistant-name"><strong>${esc(profile.name)}</strong>${profile.id === state.activeId ? badge("Активен") : ""}</span>
            <small>${esc(profile.description)}</small>
          </span><span class="row-chevron">${icon("chevron", 16)}</span>
        </button>`).join("") : empty("layers", "Здесь будут ваши ассистенты", "Добавьте профиль со своим именем и характером.")}</div>
      <div class="assistant-cta">${button("Создать ассистента", "plus", "assistant-edit", "", "primary full")}</div>`;
  }

  function profile() {
    const profile = M.profile();
    return `${heading("Профиль", iconButton("note", "Редактировать профиль", "assistant-edit", profile.id))}
    <section class="glass-card profile-banner">
      ${avatar(profile, true)}<div><p class="eyebrow">Текущий ассистент</p><h2>${esc(profile.name)}</h2>
      <p class="muted">${esc(profile.tag)}</p>${badge("Активен")}</div>
    </section>
    <h2 class="section-label">Персонализация</h2>
    <div class="stack profile-list">
      ${row("note", "Имя ассистента", profile.name, "assistant-edit", profile.id)}
      ${row("mic", "Голос", profile.voice, "profile-option", "voice")}
      ${row("wave", "Тон общения", profile.tone, "profile-option", "tone")}
      ${row("layers", "Стиль ответов", profile.style, "profile-option", "style")}
      ${row("memory", "Память", "Факты и предпочтения", "go", "memory")}
      ${row("file", "Инструкция для ИИ", "Цель, правила и манера ответа", "go", "instructions")}
      ${row("grid", "Подключённые приложения", "Демонстрационные интеграции", "go", "settings-connection")}
      ${row("shield", "Приватность", "История, память и данные", "go", "settings-privacy")}
    </div>`;
  }

  function memory(filters) {
    const data = M.memory();
    let entries = data.entries.filter(entry => filters.memory === "all" || filters.memory === entry.category || filters.memory === "recent");
    if (filters.memory === "recent") entries = entries.slice(0, 5);
    return `${heading("Память", iconButton("info", "О памяти", "memory-info"), true)}
      <div class="glass-row memory-controls">
        ${symbol("memory")}<span class="row-text"><strong>Разрешить запоминание</strong><small>Управление памятью профиля</small></span>
        ${toggle("Разрешить запоминание", data.enabled, "memory-toggle", "")}
      </div>
      ${tabs([["all", "Все"], ...M.categories.map(x => [x.id, x.label, x.icon]), ["recent", "Недавние", "history"]], filters.memory, "filter-memory")}
      <h2 class="section-label">Запомненная информация</h2>
      <div class="stack">${entries.length ? entries.map(entry => `<div class="glass-row memory-row">
        ${symbol(entry.icon || "memory", "compact")}
        <span class="row-text"><button type="button" data-action="memory-edit" data-value="${esc(entry.id)}">
          <strong>${esc(entry.title)}</strong><small>${esc(entry.value)}${entry.example ? " · пример" : ""}</small>
        </button></span>
        ${toggle(entry.title, entry.enabled, "memory-entry-toggle", entry.id)}
        ${iconButton("dots", "Действия с записью", "memory-edit", entry.id)}
      </div>`).join("") : empty("memory", "В этой категории пока пусто", "Добавьте факт или предпочтение вручную.")}</div>
      <div class="actions memory-actions">
        ${button("Очистить память", "trash", "memory-clear", "", "danger")}
        ${button("Добавить запись", "plus", "memory-edit", "", "primary")}
      </div>`;
  }

  function instructions() {
    const values = state.instructions[state.activeId] || {};
    return `${heading("Инструкция для ИИ", '<button type="submit" form="instruction-form" class="icon-button" aria-label="Сохранить инструкцию" title="Сохранить">' + icon("check", 21) + "</button>", true)}
      <form class="stack spacious" id="instruction-form">
      ${M.instructionFields.map(field => `
        <label class="instruction-card">
          <span>${icon(field.icon, 17)}${field.label}</span>
          <textarea name="${field.id}" rows="3" maxlength="1200">${esc(values[field.id] ?? field.value)}</textarea>
        </label>`).join("")}
      <div class="actions">
        <button type="submit" class="button primary">${icon("check", 17)} Сохранить</button>
        ${button("Вернуть примеры", "refresh", "instruction-reset")}
      </div>
      </form>`;
  }

  function notes() {
    return `${heading("Заметки", iconButton("plus", "Добавить заметку", "note-edit"), true)}
      <div class="stack">${state.notes.length ? state.notes.map(note =>
        row("note", note.title, note.body.slice(0, 96), "note-edit", note.id)).join("") :
        empty("note", "Заметок пока нет", "Текст заметок хранится на этом устройстве.", button("Создать заметку", "plus", "note-edit", "", "primary"))}</div>`;
  }
function appearance() {
    const hue = state.preferences.hue;
    const named = M.colors.find(color => color.hue === hue);
    return `<section class="glass-card appearance-card" aria-labelledby="appearance-title">
      <div class="card-heading">${symbol("palette", "violet")}<div><h2 id="appearance-title">Внешний вид</h2><small>Акцентный цвет интерфейса</small></div></div>
      <div class="palette">${M.colors.map(color => `<button type="button" class="swatch" data-action="accent" data-value="${color.hue}"
        aria-label="${color.label}" title="${color.label}" aria-pressed="${hue === color.hue}" style="--swatch:${color.hue}">${icon("check", 18)}</button>`).join("")}</div>
      <div class="spectrum"><label for="hue-range">Точный оттенок</label><output for="hue-range" id="hue-label">${named?.label || hue + "°"}</output>
        <input id="hue-range" type="range" min="0" max="359" value="${hue}"></div>
    </section>`;
  }

  function settingsItems(query) {
    const filter = query.trim().toLowerCase();
    const matches = sections.filter(item => [item.title, item.subtitle, item.tags].join(" ").toLowerCase().includes(filter));
    const showColor = "внешний вид цвет тема палитра акцент оттенок".includes(filter);
    return `${showColor ? appearance() : ""}
      ${matches.length ? `<h2 class="section-label">Разделы</h2><div class="settings-grid">${matches.map((item, index) =>
        `<button type="button" class="settings-tile" data-action="go" data-value="settings-${item.id}">
          <span>${symbol(item.icon, index % 2 ? "violet" : "cyan")}${icon("chevron", 15)}</span>
          <strong>${item.title}</strong><small>${item.subtitle}</small>
        </button>`).join("")}</div>` : ""}
      ${!showColor && !matches.length ? empty("search", "Разделов не найдено", "Попробуйте поискать по названию настройки.") : ""}`;
  }

  function settings(filters) {
    return heading("Настройки", iconButton("search", "Поиск в настройках", "search-settings")) +
      (filters.searchSettings ? search("Поиск в настройках", "settings", filters.settingsQuery) : "") +
      `<div id="settings-items">${settingsItems(filters.settingsQuery)}</div>`;
  }

  function detail(title, ...cards) {
    return heading(title, "", true) + `<div class="stack">${cards.join("")}</div>`;
  }

  function settingsDetail(view, connection) {
    switch (view) {
      case "settings-voice":
        return detail("Голос",
          notice("Настройки для демонстрации голосового интерфейса. Системный доступ можно будет подключить в следующей версии."),
          pref("mic", "Микрофон", "Предпочтение голосового ввода", "microphone"),
          pref("speaker", "Голосовой вывод", "Предпочтение озвучивания ответов", "voiceOutput"),
          row("wave", "Тембр ассистента", M.profile().voice, "profile-option", "voice"),
          button("Показать голосовой ввод", "mic", "listen", "", "primary"));
      case "settings-interface":
        return detail("Интерфейс",
          pref("wave", "Уменьшить анимации", "Статичные волны и плавные переходы", "reduceMotion"),
          pref("layers", "Уменьшить прозрачность", "Более плотные стеклянные панели", "reduceGlass"),
          pref("bolt", "Экономия энергии", "Упрощает свечение и движение", "powerSave"),
          button("Показать заставку", "play", "replay-splash", "", "primary"));
      case "settings-access":
        return detail("Доступы",
          notice("Переключатели сохраняют предпочтения для деморежима. Интернет используется для текстовых ответов OpenRouter."),
          pref("layers", "Оверлей", "Панель поверх приложений · демо", "overlay"),
          pref("bell", "Уведомления", "Показывать события внутри CORTEX", "notifications"),
          pref("shield", "Спрашивать подтверждение", "Предпочтение для чувствительных действий", "askFirst"),
          row("info", "Системные разрешения", "Микрофон, файлы, спецвозможности", "access-info"));
      case "settings-privacy":
        return detail("Приватность",
          notice("Профили, история, заметки и память хранятся локально в этом интерфейсе."),
          pref("history", "Сохранять историю", "Добавлять новые команды в журнал", "keepHistory"),
          row("history", "Открыть историю", "Просмотр и удаление записей", "go", "history"),
          row("memory", "Память ассистента", "Управлять фактами и предпочтениями", "go", "memory"),
          row("note", "Локальные заметки", "Просмотр и редактирование", "go", "notes"),
          button("Очистить историю", "trash", "history-clear", "", "danger"));
      case "settings-connection":
        return detail("Подключение", routerCard(connection),
          row("telegram", "Telegram", "Демо запуска приложения", "run", "telegram"),
          row("folder", "Файлы", "Демонстрационный поиск", "run", "file"),
          row("note", "Заметки", "Локальный блокнот", "go", "notes"));
      case "settings-about":
        return detail("О CORTEX",
          `<section class="glass-card profile-banner">
            <span class="avatar large"><img width="63" src="art/mark.svg" alt=""></span>
            <div><p class="eyebrow">AI Control Center</p><h2>CORTEX</h2><p class="muted">Версия 0.1.0 · Прототип</p></div>
          </section>`,
          row("focus", "Быстрый старт", "Знакомство с интерфейсом", "go", "setup"),
          button("Сбросить настройки вида", "refresh", "reset-appearance"));
      default:
        return "";
    }
  }


  function routerCard(connection) {
    const { available, configured, model, checking } = connection;
    const label = !available ? "Требуется обновление WebView" : configured ? "Ключ добавлен" : "Ожидает ключ";
    return `<section class="glass-card router-card stack">
      <div class="card-heading">${symbol("link", "cyan")}<div><h2>OpenRouter</h2><small>openrouter/free · бесплатные модели</small></div></div>
      <div class="router-state"><i class="status-dot ${configured ? "" : "idle"}"></i>${model ? "Связь проверена" : label}</div>
      <p class="muted">Сервис получает ваш запрос, инструкции профиля и последние реплики диалога.</p>
      <div class="actions">
        ${button(configured ? "Заменить ключ" : "Добавить ключ", "link", "api-key", "", "primary")}
        ${configured ? `<button type="button" class="button" data-action="api-test" ${checking ? "disabled" : ""}>
          ${icon("refresh", 17)}${checking ? "Проверяю…" : "Проверить"}</button>` : ""}
      </div>
      ${configured ? button("Удалить ключ", "trash", "api-remove", "", "danger") : ""}
    </section>`;
  }

  function aiTask(task) {
    const { status, query, assistant, answer, error } = task;
    const title = { running: "Готовлю ответ…", done: "Ответ ИИ", error: "Ответ не получен", stopped: "Запрос остановлен" }[status];
    return `${heading(title, "", true)}
      <div class="stack">
        <section class="glass-card ai-question"><p class="eyebrow">Ваш запрос</p><p class="answer-text">${esc(query)}</p></section>
        ${status === "running" ? `<section class="glass-card task-card stack" role="status">
          <div class="card-heading"><span class="step-mark current"></span><div><h2>Запрос к OpenRouter</h2><small>Бесплатная модель формирует ответ</small></div></div>
          <div class="ai-loading"><i></i></div>
        </section>${button("Остановить", "stop", "stop-task", "", "danger full")}` : ""}
        ${status === "done" ? `<section class="glass-card ai-answer">
          <div class="card-heading">${symbol("message", "cyan")}<div><h2>${esc(assistant)}</h2><small>Персональный ассистент</small></div></div>
          <p class="answer-text">${esc(answer)}</p>
        </section>
        <form id="follow-up-form" class="command-input">
          <input name="command" type="text" aria-label="Уточнить ответ" placeholder="Уточнить ответ…" maxlength="400" autocomplete="off" enterkeyhint="send" required>
          <button type="submit" class="icon-button" aria-label="Отправить уточнение">${icon("send", 19)}</button>
        </form>
        <div class="actions">${button("К истории", "history", "go", "history")}${button("На главную", "home", "go", "home")}</div>` : ""}
        ${status === "error" || status === "stopped" ? `
          ${status === "error" ? notice(error) : ""}
          <div class="actions">${button("Повторить", "refresh", "retry-task", "", "primary")}
            ${button("Подключение", "link", "go", "settings-connection")}</div>` : ""}
      </div>`;
  }

  function setup() {

    return detail("Быстрый старт",
      notice("Введите команду на главной или откройте готовый сценарий. Для работы с заметками доступен локальный блокнот."),
      row("layers", "Выбрать ассистента", "Системные профили и свои помощники", "go", "assistants"),
      row("palette", "Настроить цвет", "Шесть тем и выбор оттенка", "go", "settings"),
      button("На главную", "home", "go", "home", "primary"));
  }

  function taskView(task) {
    if (task?.kind === "ai") return aiTask(task);
    if (!task) return heading("Ход команды", "", true) +
      empty("bolt", "Активных команд пока нет", "Запустите демонстрацию с главной.", button("На главную", "home", "go", "home", "primary"));
    const command = M.commands[task.kind];
    const stopped = task.status === "stopped";
    const done = task.status === "done";
    const title = stopped ? "Сценарий остановлен" : done ? command.result : command.title;
    const subtitle = stopped ? "Можно повторить команду" : done ? command.description : "Показываю выполнение вашей команды";
    return `<header class="home-bar">${iconButton("back", "Назад", "back")}
        <img class="home-wordmark" src="art/wordmark.svg" alt="CORTEX"><span aria-hidden="true" class="icon-button"></span>
      </header>
      <section class="task-head">
        <div class="task-icon">${icon(done ? "check" : stopped ? "stop" : command.icon, 32)}</div>
        <span class="eyebrow">Демонстрация</span>
        <h1>${esc(title)}</h1><p class="muted">${esc(subtitle)}</p>
      </section>
      <section class="glass-card task-card" aria-label="Этапы выполнения">
        <progress class="task-meter" id="task-progress" max="100" value="${task.progress}" aria-label="Ход демонстрации"></progress>
        <ol class="task-steps">
          ${command.steps.map(([step, description], i) => {
            const status = i < task.phase ? "done" : i === task.phase && task.status === "running" ? "current" : "pending";
            return `<li class="task-step ${status}" ${status === "current" ? 'aria-current="step"' : ""}>
              <span class="step-mark ${status}">${status === "done" ? icon("check", 15) : ""}</span>
              <span class="row-text"><strong>${step}</strong><small>${description}</small></span>
              <span class="step-index">0${i + 1}</span></li>`;
          }).join("")}
        </ol>
      </section>
      <div class="actions task-bottom">
        ${task.status === "running" ? button("Остановить", "stop", "stop-task", "", "danger") : ""}
        ${stopped ? button("Повторить", "refresh", "retry-task", "", "primary") : ""}
        ${done ? button("К истории", "history", "go", "history") : ""}
        ${task.status !== "running" ? button("Готово", "check", "go", "home", done ? "primary" : "") : ""}
      </div>`;
  }

  function page(view, filters, task, unread, connection) {
    const pages = { home: () => home(unread), history: () => history(filters), assistants: () => assistants(filters),
      profile, memory: () => memory(filters), instructions, settings: () => settings(filters),
      task: () => taskView(task), notes, setup };
    return pages[view] ? pages[view]() : settingsDetail(view, connection);
  }

  function navigation(view) {
    let active = view;
    while (parent[active]) active = parent[active];
    return nav.map(([route, label, name]) => `<button type="button" data-action="go" data-value="${route}"
      ${route === active ? 'aria-current="page"' : ""}>${icon(name, 21)}<span>${label}</span></button>`).join("");
  }

  return { esc, icon, iconButton, button, symbol, badge, avatar, row, notice, levels, empty, parent, nav,
    page, navigation, historyItems, settingsItems, timeFormat };
})();
