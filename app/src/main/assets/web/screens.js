"use strict";

const CortexUI = (() => {
  const M = Cortex;
  const icon = CortexIcons;
  const esc = value => String(value ?? "").replace(/[&<>"']/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const tabs = [
    ["home", "Главная", "home"], ["chats", "Чаты", "message"],
    ["assistants", "Ассистенты", "layers"], ["settings", "Настройки", "settings"]
  ];
  const parent = {
    memory: "assistants", instructions: "assistants",
    appearance: "settings", connection: "settings", data: "settings", notes: "data", about: "settings"
  };
  const placeholders = ["Задайте вопрос…", "Что хотите обсудить?", "Расскажите, что нужно…", "О чём поговорим?"];
  const suggestions = [
    ["Составить план", "Помоги составить план: ", "target"],
    ["Разобраться в теме", "Помоги разобраться в теме: ", "message"]
  ];
  const styles = { brief: "Кратко", balanced: "По существу", deep: "Подробно" };
  const mark = '<img src="art/mark.svg" alt="">';

  function iconButton(name, label, action, value = "", variant = "") {
    return `<button type="button" class="icon-btn ${variant}" aria-label="${esc(label)}" title="${esc(label)}"
      data-action="${action}" data-value="${esc(value)}">${icon(name, 19)}</button>`;
  }

  function button(label, name, action, value = "", variant = "") {
    return `<button type="button" class="action ${variant}" data-action="${action}" data-value="${esc(value)}">
      ${name ? icon(name, 17) : ""}<span>${esc(label)}</span></button>`;
  }

  function tile(title, subtitle, name, action, value = "") {
    return `<button type="button" class="tile" data-action="${action}" data-value="${esc(value)}">
      <span class="tile-head"><span class="symbol">${icon(name, 20)}</span>${icon("chevron", 14)}</span>
      <strong>${esc(title)}</strong><small>${esc(subtitle)}</small></button>`;
  }

  function avatar(person, variant = "") {
    return `<span class="avatar avatar-${esc(person.art)} ${variant}" aria-hidden="true">
      ${person.id === "cortex" ? mark : icon(person.art === "night" ? "star" : person.art === "business" ? "layers" : "wave", 21)}
    </span>`;
  }

  function heading(title, actions = "", back = false) {
    return `<header class="topbar">${back ? iconButton("back", "Назад", "back", "", "subtle") : ""}
      <h1>${esc(title)}</h1>${actions ? `<div class="title-actions">${actions}</div>` : ""}</header>`;
  }

  function headingSection(title, extra = "") {
    return `<div class="section-title"><h2>${esc(title)}</h2>${extra}</div>`;
  }

  function switchRow(label, description, checked, key) {
    return `<div class="switch-row"><span><strong>${esc(label)}</strong><small>${esc(description)}</small></span>
      <button type="button" class="toggle" role="switch" aria-label="${esc(label)}" aria-checked="${Boolean(checked)}"
        data-action="toggle" data-value="${key}"><i></i></button></div>`;
  }

  function pill(label, action, value, pressed, name = "") {
    return `<button type="button" class="pill" ${pressed === undefined ? "" : `aria-pressed="${pressed}"`}
      data-action="${action}" data-value="${esc(value)}">${name ? icon(name, 14) : ""}<span>${esc(label)}</span></button>`;
  }

  function suggestionsHtml(inChat) {
    return `<div class="suggestions" aria-label="Идеи для начала">${suggestions.map(([label, text, name]) =>
      pill(label, inChat ? "suggest" : "new-chat", text, undefined, name)).join("")}</div>`;
  }

  function home(ctx) {
    const person = M.profile();
    return `<header class="topbar home-top">
      <button class="icon-btn" type="button" aria-label="Активный ассистент" data-action="go" data-value="assistants">
        ${person.id === "cortex" ? '<img class="avatar-mark" src="art/mark.svg" alt="">' : icon("layers", 19)}
      </button>
      <img class="wordmark" src="art/wordmark.svg" alt="CORTEX">
      ${iconButton("settings", "Настройки", "go", "settings", "subtle")}
    </header>
    <section class="home-hero">
      <div class="hero-copy"><h1>С чего начнём?</h1></div>
      <div class="hero-field wave-field" data-wave-host aria-hidden="true"></div>
    </section>
    <div class="home-deck">
      <button class="home-compose" type="button" data-action="new-chat" aria-label="Открыть чат и ввести вопрос">
        <span class="compose-symbol">${icon("spark", 21)}</span>
        <span class="compose-label" id="prompt-line">${placeholders[ctx.placeholder]}</span>
        <span class="compose-button">${icon("sendUp", 23)}</span>
      </button>
      ${suggestionsHtml(false)}
    </div>`;
  }

  function inline(text) {
    return esc(text).replace(/\*\*([^\n*]+)\*\*/gu, "<strong>$1</strong>");
  }

  function answerHtml(text) {
    const fence = String.fromCharCode(96).repeat(3);
    return String(text).split(fence).map((part, index) => {
      if (index % 2) {
        const code = part.replace(/^[a-z0-9_+-]{0,18}\n/i, "").trimEnd();
        return `<pre><code>${esc(code)}</code></pre>`;
      }
      return part.trim().split(/\n{2,}/u).filter(Boolean).map(block => {
        const lines = block.split("\n");
        if (lines.every(line => /^\s*[-*]\s+/.test(line))) {
          return "<ul>" + lines.map(line => "<li>" + inline(line.replace(/^\s*[-*]\s+/, "")) + "</li>").join("") + "</ul>";
        }
        if (lines.every(line => /^\s*\d+[.)]\s+/.test(line))) {
          return "<ol>" + lines.map(line => "<li>" + inline(line.replace(/^\s*\d+[.)]\s+/, "")) + "</li>").join("") + "</ol>";
        }
        return "<p>" + inline(block.replace(/^#{1,4}\s+/gm, "")) + "</p>";
      }).join("");
    }).join("");
  }

  function assistantMessage(name, body, extra = "") {
    return `<article class="message assistant ${extra}">
      <div class="message-author">${mark} <span>${esc(name)}</span></div>
      <div class="message-body">${body}</div></article>`;
  }

  function chat(ctx) {
    const { chat, pending, error, draft, native } = ctx;
    const person = M.profile(chat.assistantId);
    const messages = chat.messages.map(message => message.role === "user"
      ? `<article class="message user" aria-label="Вы">${esc(message.text)}</article>`
      : assistantMessage(person.name, answerHtml(message.text))).join("");
    const pendingBody = `<div data-stream class="stream-text">${pending?.text ? esc(pending.text)
      : '<span class="answer-dots" aria-label="Готовлю ответ"><i></i><i></i><i></i></span>'}</div>`;
    return `<header class="topbar chat-top">
      ${iconButton("back", "Назад", "back", "", "subtle")}
      <div class="chat-identity">${avatar(person, "mini")}
        <span><strong>${esc(person.name)}</strong><small>${chat.saved ? "Чат сохранён" : "Личный диалог"}</small></span>
      </div>
      <button type="button" class="icon-btn ${chat.saved ? "accent" : ""}" data-action="save-chat" aria-pressed="${chat.saved}"
        aria-label="${chat.saved ? "Убрать из сохранённых" : "Сохранить чат"}" title="${chat.saved ? "Убрать из сохранённых" : "Сохранить чат"}">${icon("bookmark", 19)}</button>
    </header>
    <div class="scroll conversation" aria-label="Переписка">
      ${messages || pending ? `<div class="message-list">${messages}
        ${pending ? assistantMessage(person.name, pendingBody, "pending") : ""}
        ${error ? `<div class="notice error" role="alert"><p>${esc(error)}</p>${button("Повторить", "refresh", "retry")}</div>` : ""}
      </div>` : `<section class="chat-empty">${mark}<h2>Я ${esc(person.name)}</h2><p>Задайте вопрос или опишите задачу.</p>${suggestionsHtml(true)}</section>`}
    </div>
    <div class="chat-foot">
      <form class="chat-form" id="chat-form">
        ${native ? iconButton("mic", "Диктовать сообщение", "dictate", "", "subtle") : ""}
        <textarea name="message" id="chat-input" aria-label="Сообщение" placeholder="${placeholders[ctx.placeholder]}"
          maxlength="6000" rows="1" enterkeyhint="send" spellcheck="true">${esc(draft)}</textarea>
        ${pending ? `<button type="button" class="compose-button stop" aria-label="Остановить ответ" data-action="stop">${icon("stop", 20)}</button>`
          : `<button type="submit" class="compose-button" aria-label="Отправить сообщение">${icon("sendUp", 22)}</button>`}
      </form>
    </div>`;
  }

  const date = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short" });
  const time = new Intl.DateTimeFormat("ru-RU", { hour: "2-digit", minute: "2-digit" });
  function dateLabel(value) {
    const stamp = new Date(value);
    if (!Number.isFinite(stamp.valueOf())) return "";
    return stamp.toDateString() === new Date().toDateString() ? time.format(stamp) : date.format(stamp);
  }

  function chatItems(ctx) {
    const query = ctx.search.trim().toLowerCase();
    const items = M.state.chats.filter(item => item.messages.length && (!ctx.onlySaved || item.saved) &&
      (item.title.toLowerCase().includes(query) || (query && item.messages.some(message => message.text.toLowerCase().includes(query)))))
      .sort((a, b) => String(b.updated).localeCompare(String(a.updated)));
    if (!items.length) return `<div class="empty-panel">${icon("message", 32)}
      <h2>${query ? "Совпадений пока нет" : ctx.onlySaved ? "Здесь будут сохранённые чаты" : "Начните первый чат"}</h2>
      <p>${query ? "Попробуйте другое слово." : "Закладка в чате сохраняет разговор на устройстве."}</p>
      ${query ? "" : button("Новый чат", "plus", "new-chat", "", "primary")}</div>`;
    return `<div class="chat-list">${items.map(item => `<div class="chat-list-entry">
      <button type="button" class="chat-row" data-action="open-chat" data-value="${esc(item.id)}">
        ${avatar(M.profile(item.assistantId), "mini")}
        <span class="row-copy"><strong>${esc(item.title)}</strong><small>${esc(M.profile(item.assistantId).name)} · ${dateLabel(item.updated)}</small></span>
        ${item.saved ? icon("bookmark", 15) : ""}
      </button>
      ${iconButton("dots", "Действия с чатом", "chat-menu", item.id, "subtle")}
    </div>`).join("")}</div>`;
  }

  function chats(ctx) {
    return heading("Чаты", iconButton("search", "Искать в чатах", "chat-search", "", "subtle") +
      iconButton("plus", "Новый чат", "new-chat", "", "accent")) +
      `<div class="scroll"><div class="filters">
        ${pill("Все", "chat-filter", "all", !ctx.onlySaved)}${pill("Сохранённые", "chat-filter", "saved", ctx.onlySaved, "bookmark")}
      </div>
      ${ctx.searchOpen ? `<label class="search-box">${icon("search", 17)}
        <input type="search" id="chat-search" placeholder="Поиск по чатам" aria-label="Поиск по чатам" value="${esc(ctx.search)}">
      </label>` : ""}
      <div id="chat-items">${chatItems(ctx)}</div></div>`;
  }

  function assistants() {
    const person = M.profile();
    return heading("Ассистенты", iconButton("plus", "Создать ассистента", "profile-edit")) +
      `<div class="scroll stack">
      <section class="persona-hero">
        ${avatar(person, "large")}
        <div><span class="eyebrow">Активный ассистент</span><h2>${esc(person.name)}</h2><p>${esc(person.description)}</p></div>
        ${iconButton("note", "Редактировать профиль", "profile-edit", person.id, "subtle")}
      </section>
      <div class="card-grid">
        ${tile("Память", "Факты и прошлые беседы", "memory", "go", "memory")}
        ${tile("Инструкции", styles[person.style], "file", "go", "instructions")}
      </div>
      ${headingSection("Выбор ассистента")}
      <div class="persona-rail">${M.allProfiles().map(profile => `
        <button type="button" class="persona-card" data-action="select-profile" data-value="${esc(profile.id)}"
          aria-pressed="${profile.id === person.id}">
          ${avatar(profile)}<strong>${esc(profile.name)}</strong><small>${esc(profile.tag)}</small>
        </button>`).join("")}</div>
      </div>`;
  }

  function settings() {
    return heading("Настройки") + `<div class="scroll stack">
      <div class="card-grid">
        ${tile("Внешний вид", "Цвет и движение", "palette", "go", "appearance")}
        ${tile("Подключение ИИ", "Ключ OpenRouter", "link", "go", "connection")}
        ${tile("Чаты и данные", "Сохранение и заметки", "shield", "go", "data")}
        ${tile("О CORTEX", "Версия 0.2.0", "info", "go", "about")}
      </div></div>`;
  }

  function detail(title, content, actions = "") {
    return heading(title, actions, true) + `<div class="scroll stack">${content}</div>`;
  }

  function appearance() {
    const { hue, reduceMotion } = M.state.preferences;
    return detail("Внешний вид", `<section class="panel stack">
      <h2>Акцентный цвет</h2>
      <div class="palette">${M.colors.map(color => `<button type="button" class="swatch" aria-label="${color.label}" title="${color.label}"
        style="--swatch:${color.hue}" aria-pressed="${Number(hue) === color.hue}" data-action="accent" data-value="${color.hue}">${icon("check", 18)}</button>`).join("")}</div>
      ${switchRow("Уменьшить движение", "Статичная волна и мгновенная смена экранов", reduceMotion, "reduceMotion")}
    </section>`);
  }

  function connection(ctx) {
    const { configured, available, checked, checking } = ctx.connection;
    const label = !available ? "Требуется обновление WebView" : checked ? "Связь проверена" : configured ? "Ключ добавлен" : "Ожидает ключ";
    return detail("Подключение ИИ", `<section class="panel router-panel stack">
      <div class="section-title"><h2>OpenRouter</h2><span class="badge">Бесплатный режим</span></div>
      <p class="router-state"><i class="status-dot ${configured ? "on" : ""}"></i>${label}</p>
      <p class="muted">Для ответа отправляются запрос и контекст разговора. ${ctx.isAndroid
        ? "Android хранит ключ зашифрованным."
        : "В браузере ключ действует до закрытия или обновления вкладки."}</p>
      <div class="actions">${button(configured ? "Заменить ключ" : "Добавить ключ", "link", "key", "", "primary")}
        ${configured ? `<button type="button" class="action" data-action="test-key" ${checking ? "disabled" : ""}>
          ${icon("refresh", 17)}${checking ? "Проверяю…" : "Проверить"}</button>` : ""}
      </div>
      ${configured ? button("Удалить ключ", "trash", "remove-key", "", "quiet") : ""}
    </section>`);
  }

  function data() {
    const p = M.state.preferences;
    return detail("Чаты и данные", `<div class="panel stack">
      ${switchRow("Сохранять новые чаты", "Разговоры останутся после закрытия приложения", p.autoSaveChats, "autoSaveChats")}
      ${switchRow("Помнить сохранённые чаты", "Подбирать относящиеся к вопросу фрагменты бесед", p.rememberSaved, "rememberSaved")}
    </div><div class="card-grid">
      ${tile("Заметки", "Локальный блокнот", "note", "go", "notes")}
      ${tile("Очистить чаты", "Удалить сохранённые беседы", "trash", "clear-chats")}
    </div>`);
  }

  function memory() {
    const person = M.profile();
    const data = M.memory(person.id);
    return detail("Память", `<section class="panel">
      ${switchRow("Использовать память", "Факты и релевантный контекст для " + person.name, data.enabled, "memory")}
    </section>
    ${data.entries.length ? `<div class="card-grid">${data.entries.map(entry =>
      tile(entry.title, entry.value, "memory", "memory-edit", entry.id)).join("")}</div>` :
      '<div class="empty-panel"><h2>Запишите то, что важно помнить</h2><p>Например, как к вам обращаться или над какой задачей вы работаете.</p></div>'}
    ${button("Добавить факт", "plus", "memory-edit", "", "primary")}`);
  }

  function instructions(ctx) {
    const person = M.profile();
    const field = M.instructionFields.find(item => item.id === ctx.rule) || M.instructionFields[0];
    const value = ctx.ruleDraft ?? M.state.instructions[person.id]?.[field.id] ?? field.value;
    return detail("Инструкции", `<div class="filters">${M.instructionFields.map(item =>
      pill(item.label, "rule", item.id, item.id === field.id)).join("")}</div>
      <form class="stack" id="rule-form">
        <label class="field"><span>${field.label} · ${esc(person.name)}</span>
          <textarea name="rule" rows="6" maxlength="750">${esc(value)}</textarea>
        </label>
        <button type="submit" class="action primary">${icon("check", 17)}Сохранить</button>
      </form>`);
  }

  function notes() {
    return detail("Заметки", M.state.notes.length ? `<div class="card-grid">${M.state.notes.map(item =>
      tile(item.title, item.body, "note", "note-edit", item.id)).join("")}</div>` :
      '<div class="empty-panel"><h2>Заметок пока нет</h2><p>Создайте запись для себя.</p></div>',
    iconButton("plus", "Добавить заметку", "note-edit", "", "accent"));
  }

  function about() {
    return detail("О CORTEX", `<section class="panel about-card">
      ${mark}<img src="art/wordmark.svg" alt="CORTEX"><p class="muted">Версия 0.2.0 · Android</p>
      ${button("Посмотреть заставку", "play", "replay")}
    </section>`);
  }

  function page(route, ctx) {
    const pages = { home, chats, chat, assistants, settings, appearance, connection, data, memory, instructions, notes, about };
    return pages[route](ctx);
  }

  function navigation(route) {
    let active = route;
    while (parent[active]) active = parent[active];
    return tabs.map(([key, label, name]) => `<button type="button" data-action="go" data-value="${key}"
      ${key === active ? 'aria-current="page"' : ""}>${icon(name, 20)}<span>${label}</span></button>`).join("");
  }

  return { esc, icon, iconButton, button, tile, avatar, placeholders, tabs, parent, styles, page, navigation, chatItems };
})();
