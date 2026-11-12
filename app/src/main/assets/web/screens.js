"use strict";

const CortexUI = (() => {
  const M = Cortex;
  const icon = CortexIcons;
  const version = "0.9.1";
  const tabs = [
    ["chat", "Чат", "message"], ["phone-tools", "Вызов", "bolt"], ["home", "Кортекс", ""],
    ["notes", "Заметки", "note"], ["profile", "Профиль", "user"]
  ];
  const parent = {
    settings: "profile", motion: "settings", voice: "settings", models: "settings", language: "settings",
    notifications: "settings", data: "profile", memory: "profile", permissions: "profile",
    support: "profile", about: "profile", privacy: "about", guide: "home", "tool-list": "phone-tools", instructions: "memory", personality: "memory"
  };
  const esc = value => String(value ?? "").replace(/[&<>"']/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const sign = '<img class="brand-mark" src="art/mark.svg" alt="">';
  const attributes = (action, value = "") => `data-action="${action}" data-value="${esc(value)}"`;

  function iconButton(name, label, action, value = "", extra = "") {
    return `<button type="button" class="icon-btn ${extra}" aria-label="${esc(label)}" title="${esc(label)}" ${attributes(action, value)}>${icon(name, 19)}</button>`;
  }
  function button(label, name, action, value = "", extra = "") {
    return `<button type="button" class="action ${extra}" ${attributes(action, value)}>${name ? icon(name, 17) : ""}<span>${esc(label)}</span></button>`;
  }
  function tile(title, description, name, action, value = "", extra = "") {
    return `<button type="button" class="tile ${extra}" ${attributes(action, value)}>
      <span class="tile-glyph">${icon(name, 20)}</span>
      <span class="tile-copy"><strong>${esc(title)}</strong>${description ? `<small>${esc(description)}</small>` : ""}</span>
      ${icon("chevron", 15)}
    </button>`;
  }
  function switchRow(label, description, on, value) {
    return `<button type="button" class="switch-row" role="switch" aria-checked="${on}" aria-label="${esc(label)}" ${attributes("toggle", value)}>
      <span class="tile-copy"><strong>${esc(label)}</strong><small>${esc(description)}</small></span>
      <span class="switch-track" aria-hidden="true"><i></i></span></button>`;
  }
  function pill(label, action, value, on, name = "") {
    return `<button type="button" class="pill" aria-pressed="${on}" ${attributes(action, value)}>${name ? icon(name, 14) : ""}${esc(label)}</button>`;
  }
  function choices(values, selected, action) {
    return `<div class="filters">${Object.entries(values).map(([value, label]) => pill(label, action, value, selected === value)).join("")}</div>`;
  }
  function heading(title, subtitle, actions = "") {
    return `<header class="section-header">
      ${iconButton("back", "Назад", "back", "", "section-back")}
      <div class="section-heading"><h1>${esc(title)}</h1>${subtitle ? `<p>${esc(subtitle)}</p>` : ""}</div>${actions}
    </header>`;
  }
  function detail(title, subtitle, content, actions = "") {
    return heading(title, subtitle, actions) + `<div class="scroll detail-scroll stack">${content}</div>`;
  }
  const label = text => `<h2 class="group-label">${esc(text)}</h2>`;
  const group = content => `<section class="tile-group">${content}</section>`;
  function portrait(account, size = "") {
    const name = account?.name || account?.email || "";
    const initials = name.trim().split(/\s+/u).slice(0, 2).map(word => Array.from(word)[0] || "").join("").toLocaleUpperCase("ru");
    return `<span class="portrait ${size}" aria-hidden="true">${account?.avatar ?
      `<img src="${esc(account.avatar)}" alt="">` : initials ? esc(initials) : icon("user", size === "large" ? 29 : 21)}</span>`;
  }
  function composer() {
    return `<form class="composer" id="composer" autocomplete="off">
      <div id="screen-context" class="screen-context" hidden>
        <details><summary data-screen-title></summary><p data-screen-preview></p></details>
        ${iconButton("link", "Приложить текст экрана", "attach-screen", "", "attach-screen")}
        ${iconButton("close", "Удалить текст экрана", "drop-screen", "", "voice-close")}
      </div>
      <div id="voice-inline" class="voice-inline" hidden>
        <span class="voice-meter" aria-hidden="true"><i class="voice-lens"></i><span class="voice-bars">
          ${Array.from({length: 7}, (_, i) => `<i style="--i:${i};--rise:${1.4 + (3 - Math.abs(3 - i)) * .4}"></i>`).join("")}</span></span>
        <div class="voice-copy"><p data-voice-status role="status"></p><p data-voice-text hidden></p></div>
        ${iconButton("stop", "Завершить запись", "voice-turn", "", "voice-control")}
        ${iconButton("close", "Закончить голосовой режим", "voice-close", "", "voice-close")}
      </div>
      <textarea id="composer-input" name="message" aria-label="Сообщение" enterkeyhint="enter" rows="1" maxlength="6000"
        enterkeyhint="send"></textarea>
      <div class="composer-actions">
        <button class="composer-button mic-button" type="button" aria-label="Диктовать сообщение" ${attributes("dictate")}>${icon("mic", 21)}</button>
        <button class="composer-button voice-button" type="button" aria-label="Голосовой разговор" ${attributes("voice-chat")}>${icon("wave", 22)}</button>
        <button class="composer-button send-button" type="submit" aria-label="Отправить сообщение" hidden disabled>${icon("sendUp", 23)}</button>
        <button class="composer-button stop-button" type="button" aria-label="Остановить генерацию" ${attributes("stop")} hidden>${icon("stop", 19)}</button>
      </div>
    </form>`;
  }

  function home() {
    return `<header class="home-header"><div class="brand">${sign}<span>CORTEX</span></div>
        <span class="home-header-quiet" aria-hidden="true"><i></i><i></i><i></i></span></header>
      <section class="home-hero" aria-labelledby="home-title">
        <div class="hero-core" data-home-core aria-hidden="true"><i class="core-halo"></i>
          <i class="core-orbit"></i><i class="core-orbit"></i><i class="core-orbit"></i>
          <i class="core-glass"></i>${sign}</div>
        <div class="hero-copy"><h1 id="home-title">Чем помочь?</h1><p class="hero-subtitle">Опишите задачу своими словами. Остальное — за CORTEX.</p></div>
      </section>`;
  }

  function thinking() {
    return `<div class="thinking cortex-processing" role="status" aria-label="CORTEX обрабатывает запрос">
      <span class="thinking-core" aria-hidden="true">${sign}</span>
      <span class="thinking-label">CORTEX <span class="thinking-dots" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></span></span>
      </div>`;
  }

  function actionProgress(job) {
    return `<div class="action-progress" role="status"><span class="action-pulse">${icon(job.waiting ? "history" : "bolt", 18)}</span>
      <span><strong>${job.waiting ? "План на паузе" : "Выполняю…"}</strong>
        <small>${esc(job.waiting ? "Вернитесь в чат, чтобы продолжить" : job.action)}</small></span></div>`;
  }

  function chat(ctx) {
    const item = ctx.chat;
    const messages = (item?.messages || []).map((message, index) => message.role === "user"
      ? `<article class="message user${ctx.messageEnter && index === item.messages.length - 1 ? " new-turn" : ""}" aria-label="Вы">${esc(message.text)}</article>`
      : `<article class="message assistant${ctx.messageEnter && index === item.messages.length - 1 ? " new-response" : ""}" aria-label="Ответ CORTEX">
        <div class="message-body markdown">${CortexMarkdown.render(message.text)}</div>
        <div class="message-actions">${iconButton("copy", "Копировать ответ", "copy", index)}
          ${ctx.native ? iconButton("speaker", "Прочитать ответ вслух", "speak", index) : ""}</div></article>`).join("");
    const pending = ctx.pending ? `<article class="message assistant pending" aria-label="Ответ CORTEX">
      <div class="message-body markdown stream-text" data-stream>${ctx.pending.text ? CortexMarkdown.render(ctx.pending.text) :
      (ctx.pending.action || ctx.pending.waiting ? actionProgress(ctx.pending) : thinking())}</div></article>` : "";
    const error = ctx.error ? `<div class="notice error" role="alert">${icon("alert", 19)}<div><p>${esc(ctx.error.message)}</p>
      <div class="actions">
        ${ctx.error.code === "sign_in" && ctx.connection.google ? button("Войти", "user", "go", "profile", "primary") : ""}
        ${button("Повторить", "refresh", "retry")}
      </div></div></div>` : "";
    return `<header class="chat-header">
      <button type="button" class="chat-control" aria-label="Новый чат" ${attributes("new-chat")}>${icon("newChat", 19)}<span>Новый чат</span></button>
      <div class="chat-brand" data-chat-logo>${sign}<span>CORTEX</span></div>
      <button type="button" class="chat-control" aria-label="История чатов" ${attributes("history")}>${icon("history", 18)}<span>История<span class="history-long"> чатов</span></span></button>
    </header>
    <div class="scroll conversation" aria-label="Переписка">
      ${messages || pending || error ? `<div class="message-list${ctx.messageEnter ? " entering" : ""}">${messages}${pending}${error}</div>` :
        `<section class="chat-empty"><div class="chat-empty-signal" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></div>${sign}<h1>На связи.</h1><p>Любая задача начинается с вашего сообщения.</p></section>`}
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
    const query = ctx.search.trim().toLocaleLowerCase("ru");
    const items = M.state.chats.filter(item => item.messages.length && (!ctx.onlySaved || item.saved) &&
      (!query || item.title.toLocaleLowerCase("ru").includes(query) ||
        item.messages.some(message => message.text.toLocaleLowerCase("ru").includes(query))))
      .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updated.localeCompare(a.updated));
    if (!items.length) return `<div class="empty-panel">${icon("message", 30)}
      <h3>${query ? "Совпадений нет" : ctx.onlySaved ? "Сохранённых чатов пока нет" : "История пока пуста"}</h3>
      <p>${query ? "Попробуйте другой запрос." : "Диалоги появятся здесь после отправки сообщения."}</p></div>`;
    return items.map(item => `<div class="history-row">
      <button class="history-open" type="button" ${attributes("open-chat", item.id)}>
        <span class="history-glyph">${icon(item.pinned ? "pin" : "message", 17)}</span>
        <span class="tile-copy"><strong>${esc(item.title)}</strong><small>${esc(dateLabel(item.updated))}${item.saved ? " · Сохранён" : " · На эту сессию"}</small></span>
      </button>${iconButton("dots", "Действия с чатом", "chat-menu", item.id)}</div>`).join("");
  }
  function history(ctx) {
    return `<header class="drawer-head"><h2 id="history-title">История чатов</h2>
      ${iconButton("close", "Закрыть историю", "close-history")}</header>
      <label class="search-box">${icon("search", 19)}<input id="history-search" type="search" aria-label="Поиск по чатам"
        placeholder="Поиск по чатам" value="${esc(ctx.search)}"></label>
      <div class="filters">${pill("Все", "history-filter", "all", !ctx.onlySaved)}
        ${pill("Сохранённые", "history-filter", "saved", ctx.onlySaved, "bookmark")}</div>
      <div class="drawer-scroll" id="history-items">${chatItems(ctx)}</div>`;
  }

  function profile(ctx) {
    const account = M.state.account;
    return `<header class="profile-header"><h1>Профиль</h1></header>
      <div class="scroll profile-scroll stack">
        <section class="profile-identity">
          ${portrait(account, "large")}
          <div class="account-copy"><strong>${esc(account?.name || "Гостевой режим")}</strong>
            <p>${esc(account?.email || "Чаты и настройки на этом устройстве")}</p>
          </div>
          ${!account && ctx.connection.google ? `<button type="button" class="action primary profile-signin" ${attributes("login")} ${ctx.login ? "disabled" : ""}>
            <span class="google-letter" aria-hidden="true">G</span><span>${ctx.login ? "Ожидаю вход" : "Войти через Google"}</span></button>` : ""}
        </section>
        ${group(tile("Настройки", "Голос, телефон и уведомления", "sliders", "go", "settings") +
          tile("Чаты и данные", "История, экспорт и заметки", "folder", "go", "data") +
          tile("Память и персонализация", "Факты, контекст и стиль ответа", "memory", "go", "memory"))}
        ${group(tile("Разрешения Android", "Управляйте доступами по отдельности", "shield", "go", "permissions") +
          tile("Помощь и поддержка", "", "help", "go", "support") +
          tile("О приложении", "Версия и конфиденциальность", "info", "go", "about"))}
        ${account ? button("Выйти из аккаунта", "logout", "logout", "", "quiet") : ""}
      </div>`;
  }
  function settings() {
    return detail("Настройки", "", group(
      tile("Голос", "Диктовка, разговор и активация", "mic", "go", "voice") +
      tile("Способ вызова CORTEX", "Островок, голос или без плавающего окна", "focus", "go", "phone-tools") +
      tile("Модели ИИ", "Бесплатные профили OpenRouter", "spark", "go", "models") +
      tile("Язык", "Язык ответа и распознавания", "globe", "go", "language") +
      tile("Анимации и отклик", "Движение интерфейса и вибрация", "wave", "go", "motion") +
      tile("Уведомления", "Оповещения о готовом ответе", "bell", "go", "notifications")
    ));
  }
  function motion() {
    const p = M.state.preferences;
    return detail("Анимации и отклик", "", `<section class="panel stack"><h2>Фоновая анимация</h2>
      ${choices(M.motion, p.quality, "quality")}
      ${switchRow("Уменьшить движение", "Убрать движение точек и переходы", p.reduceMotion, "reduceMotion")}
      ${switchRow("Тактильный отклик", "Лёгкий импульс при переходе, отправке и записи", p.haptics, "haptics")}
    </section>`);
  }
  function voice() {
    const p = M.state.preferences;
    return detail("Голос", "", `<section class="panel stack">
      ${switchRow("Читать текстовые ответы вслух", "Использовать системный голос Android", p.readAloud, "readAloud")}
      ${switchRow("Продолжать голосовой разговор", "Слушать следующий вопрос после озвучивания ответа", p.voiceContinue, "voiceContinue")}
      <div class="capability-note"><span class="capability-signal" aria-hidden="true"><i></i><i></i><i></i></span><span>Обращение «Кортекс» вне приложения включается на экране «Вызов». Голосовой разговор внутри приложения — через кнопку рядом с полем ввода.</span></div>
      <h2>Скорость речи</h2>${choices({"0.85": "Медленнее", "1": "Обычная", "1.15": "Быстрее"}, p.speechSpeed, "speech-speed")}
    </section>`);
  }
  function phoneTools(ctx) {
    const p = M.state.preferences;
    const native = Boolean(window.CortexIslandControl);
    const perm = (ctx && ctx.permissions) || {};
    let status = "Островок и обращение «Кортекс» работают только в Android-приложении CORTEX.", fix = "";
    if (native && !perm.overlay) {
      status = "Разрешите показ поверх приложений — тогда островок появится у камеры.";
      fix = tile("Показ поверх приложений", "Откроется системная настройка Android", "focus", "overlay-permission");
    } else if (native) {
      status = p.overlayMode === "off" ? "Выберите режим ниже, чтобы включить островок." : "Нажмите на островок, чтобы написать Кортексу и получить ответ.";
    }
    const voiceRow = native
      ? switchRow("Обращение «Кортекс»", perm.microphone ? (p.hotwordEnabled && ctx?.wakeState || "Скажите «Кортекс», затем команду. Ответ появится в островке.") : "Сначала выдайте доступ к микрофону.", p.hotwordEnabled, "hotwordEnabled") +
        (perm.microphone ? "" : tile("Доступ к микрофону", "Нужен для обращения «Кортекс»", "mic", "microphone-permission"))
      : `<div class="feature-detail"><strong>Голосовое обращение</strong><small>Доступно в Android-приложении CORTEX.</small></div>`;
    const options = [
      ["always", "Постоянный островок", "Кортекс виден поверх приложений. В горизонтальном режиме его можно скрывать."],
      ["wake", "Появляется по вызову", "Островок появляется только при активации. Слово «Кортекс» включается ниже, в блоке «Голосовое обращение»."],
      ["off", "Без островка", "Управление из приложения и системных способов запуска."]
    ];
    return `<header class="profile-header"><h1>Вызов CORTEX</h1><p class="section-subtitle">Вы сами решаете, как помощник присутствует на телефоне.</p></header>
      <div class="scroll detail-scroll stack phone-tools-scroll">
        <div class="capability-note"><span class="capability-signal" aria-hidden="true"><i></i><i></i><i></i></span><span>${esc(status)}</span></div>
        ${fix}
        <div class="island-demo" data-camera="${esc(ctx.islandCamera || "drop")}" aria-hidden="true">
          <div class="demo-pill"><span class="demo-rim"></span>
            <div class="demo-body">
              <div class="demo-state"><span class="demo-wave"><i></i><i></i><i></i><i></i><i></i></span>
                <span class="demo-text"><b>Слушаю…</b><b>Выполняю…</b><b>Готово</b></span>
              </div>
              <div class="demo-composer">
                <div class="demo-brand">CORTEX <span class="demo-wave"><i></i><i></i><i></i><i></i><i></i></span>
                  <span class="demo-actions"><i class="demo-open"></i><i class="demo-close"></i></span></div>
                <div class="demo-entry">Напиши Кортексу… <span><i class="demo-send"></i></span></div>
              </div>
            </div>
          </div>
          <div class="island-demo-hood"><span class="island-demo-cam"></span></div>
        </div>
        <section class="surface-choices" aria-label="Режим отображения">
        ${options.map(([value, title, hint]) => `<button type="button" class="surface-option" role="radio"
          aria-checked="${p.overlayMode === value}" ${attributes("surface-mode", value)}>
          <span class="surface-radio" aria-hidden="true"></span><span><strong>${esc(title)}</strong><small>${esc(hint)}</small></span>
          <span class="surface-preview ${value}" aria-hidden="true"><i></i><i></i></span>
        </button>`).join("")}
        </section>
        ${group(switchRow("Не показывать в горизонтальных приложениях", "Например, во время игр и просмотра видео", p.hideLandscape, "hideLandscape") +
          voiceRow)}
        ${group(tile("Разрешения и доступы", "Покажем назначение каждого разрешения", "shield", "go", "permissions") +
          tile("Настроить заново", "Повторить пошаговую настройку", "sliders", "open-setup"))}
        <p class="quiet-copy">Островок рисуется поверх приложений и требует разрешения. Фоновый микрофон включается только по вашему переключателю.</p>
      </div>`;
  }
  function toolList() {
    return detail("Действия Android", CortexPhone.operationCount + " вариантов · доступность зависит от устройства",
      `<section class="panel faq">${CortexPhone.sections.map(item => `<details>
        <summary>${esc(item.name)} <small>${item.count}</small>${icon("chevron", 15)}</summary>
        <p>${esc(item.items.join(" · "))}</p></details>`).join("")}</section>`);
  }
  function guide() {
    return detail("Как пользоваться", "Вы управляете — CORTEX помогает", `<section class="guide-steps">
      <div>${icon("message", 22)}<strong>Сформулируйте цель</strong><p>Напишите обычными словами, что нужно сделать. Не требуется запоминать команды.</p></div>
      <div>${icon("mic", 22)}<strong>Говорите голосом</strong><p>Голосовой ввод работает внутри приложения. Фоновое обращение «Кортекс» включается в настройках.</p></div>
      <div>${icon("focus", 22)}<strong>Выберите способ вызова</strong><p>Выберите предпочитаемый режим. Островок у камеры показывает, что CORTEX слушает и выполняет.</p></div>
      <div>${icon("shield", 22)}<strong>Контролируйте доступ</strong><p>Разрешения выдаются для отдельных функций и всегда управляются через Android.</p></div>
      </section>` + button("Настроить CORTEX", "sliders", "go", "phone-tools", "primary"));
  }
  function models(ctx) {
    const model = M.state.preferences.model;
    return detail("Модели ИИ", "Бесплатные варианты OpenRouter", `<section class="panel stack">
      ${choices(M.models, model, "model-profile")}
      <p class="muted">${({
        auto: "Автоматический выбор подходящей бесплатной модели.",
        fast: "Приоритет скорости ответа, если модель доступна.",
        deep: "Приоритет качества для сложных задач."
      })[model]} При перегрузке модели сервер пробует резерв. У бесплатных моделей общая дневная квота OpenRouter.</p>
      ${ctx.connection.profiles ? "" : '<p class="muted">Выбор применяется на сервере CORTEX с поддержкой профилей.</p>'}</section>`);
  }
  function language() {
    return detail("Язык", "Ответы и распознавание речи", `<section class="panel stack"><h2>Язык CORTEX</h2>
      ${choices(M.languages, M.state.preferences.language, "language")}
      <p class="muted">В режиме «Авто» CORTEX отвечает на языке сообщения. Для диктовки в этом режиме используется русский.</p></section>`);
  }
  function notifications(ctx) {
    return detail("Уведомления", "Ответы в фоновом режиме", `<section class="panel stack">
      ${switchRow("Ответ готов", "Показывать уведомление Android, если приложение свёрнуто", M.state.preferences.notifyReplies, "notifyReplies")}
      <p class="muted">${ctx.native && ctx.permissions.notifications ? "Android разрешает уведомления." : "Для оповещений нужен доступ в настройках Android."}</p>
    </section>`);
  }
  function data() {
    const p = M.state.preferences;
    return detail("Чаты и данные", "Переписка на этом устройстве", `<section class="panel stack">
      ${switchRow("Сохранять новые чаты", "Оставлять диалоги после закрытия приложения", p.autoSaveChats, "autoSaveChats")}
      ${switchRow("Помнить сохранённые чаты", "Учитывать подходящие фрагменты бесед в новых ответах", p.rememberSaved, "rememberSaved")}
    </section>` + group(
      tile("Экспортировать чаты", "Скачать сохранённые диалоги в JSON", "download", "export") +
      tile("Заметки", "Локальный блокнот", "note", "go", "notes") +
      tile("Удалить сохранённые чаты", "Очистить историю на этом устройстве", "trash", "clear-chats", "", "danger") +
      tile("Сбросить локальные данные", "Удалить чаты, заметки, память и настройки", "trash", "clear-local", "", "danger")
    ));
  }
  function memory() {
    const data = M.memory();
    return detail("Память и персонализация", "Контекст, который учитывает CORTEX",
      `<section class="panel">${switchRow("Использовать память", "Добавлять факты и уместные фрагменты бесед к запросу", data.enabled, "memory")}</section>` +
      group(tile("Манера общения", M.styles[M.profile().style], "sliders", "go", "personality") +
        tile("Инструкции", "Общие правила ответа CORTEX", "file", "go", "instructions")) +
      label("ФАКТЫ") + (data.entries.length ? group(data.entries.map(entry =>
        tile(entry.title, entry.value, "memory", "memory-edit", entry.id)).join("")) :
        '<div class="empty-panel"><p>Добавьте факт, который CORTEX должен учитывать в ответах.</p></div>') +
      button("Добавить факт", "plus", "memory-edit", "", "primary"));
  }
  function personality() {
    const person = M.profile();
    return detail("Манера общения", "Как CORTEX отвечает", `<section class="panel stack">
      <h2>Длина ответа</h2>${choices(M.styles, person.style, "reply-style")}
      <div class="readout"><strong>Тон</strong><p>${esc(person.tone)}</p></div>
      <div class="readout"><strong>Роль</strong><p>${esc(person.description)}</p></div>
      ${button("Изменить", "note", "personality-edit")}
    </section>`);
  }
  function instructions(ctx) {
    const field = M.instructionFields.find(item => item.id === ctx.rule) || M.instructionFields[0];
    const value = ctx.ruleDraft ?? M.state.instructions.cortex?.[field.id] ?? field.value;
    return detail("Инструкции", "Правила ответов CORTEX", `<div class="filters">${M.instructionFields.map(item =>
      pill(item.label, "rule", item.id, item.id === field.id)).join("")}</div>
      <form class="panel stack" id="rule-form">
        <label class="field"><span>${esc(field.label)}</span><textarea name="rule" rows="6" maxlength="750">${esc(value)}</textarea></label>
        <button type="submit" class="action primary">${icon("check", 17)}Сохранить</button>
      </form>`);
  }
  function notes() {
    return `<header class="profile-header notes-header"><h1>Заметки</h1>
      ${iconButton("plus", "Добавить заметку", "note-edit", "", "accent")}</header>
      <div class="scroll detail-scroll stack">${M.state.notes.length ? group(M.state.notes.map(item =>
        tile(item.title, item.body, "note", "note-edit", item.id)).join("")) :
        `<div class="empty-panel">${icon("note", 29)}<h3>Заметок пока нет</h3>
          ${button("Создать заметку", "plus", "note-edit", "", "primary")}</div>`}</div>`;
  }
  function permissions(ctx) {
    const a = ctx.permissions;
    const item = (glyph, title, desc, enabled, action, required = false) =>
      `<div class="access-row"><span class="access-symbol">${icon(glyph, 21)}</span><div class="tile-copy"><strong>${esc(title)}</strong>
        <small>${esc(desc)}</small><span class="access-state">${enabled ? "Разрешено" : required ? "Требуется разрешение" : "Необязательно"}</span></div>
        <button class="access-open" type="button" ${attributes(action)}>${enabled ? "Проверить" : "Настроить"}</button></div>`;
    return detail("Доступы Android", "Давайте только те разрешения, которые нужны вам", `<section class="access-list">
      ${item("mic", "Микрофон", "Речь и активация по имени. Фоновое прослушивание отдельно включается в настройках.", a.microphone, "microphone-permission")}
      ${item("focus", "Поверх приложений", "Показывать островок поверх других окон. Не даёт доступа к содержимому экрана.", a.overlay, "overlay-permission")}
      ${item("bell", "Уведомления", "Уведомление о работающем голосовом режиме и готовности ответа.", a.notifications, "notification-permission")}
      ${item("check", "Использование приложений", "Проверять, какое приложение сейчас открыто, чтобы подтвердить результат.", a.verifyApps, "verification-permission")}
      ${a.assistantSupported ? item("focus", "Цифровой помощник", "Разрешает системный вызов помощника и передачу текста экрана после вашего действия.", a.assistant, "assistant-permission") : ""}
      ${item("shield", "Блокировка экрана", "Нужна только для команды погасить или заблокировать экран.", a.screenLock, "lock-permission")}
      ${item("sliders", "Яркость", "Для изменения яркости экрана по запросу.", a.brightness, "brightness-permission")}
      </section>
      <p class="quiet-copy">Некоторые разрешения Android показывает в отдельных системных меню. Нажатие «Настроить» не означает, что доступ уже выдан.</p>
      ${button("Повторить начальную настройку", "refresh", "open-setup", "", "outline")}`);
  }
  function support() {
    return detail("Помощь и поддержка", "Связь с разработчиком", group(
      tile("Написать разработчику", "Вопрос о работе CORTEX", "message", "support-form", "contact") +
      tile("Сообщить об ошибке", "Опишите, что произошло, и приложите скриншот", "alert", "support-form", "bug") +
      tile("Предложить функцию", "Расскажите, чего не хватает", "spark", "support-form", "idea")
    ) +
    label("ЧАСТЫЕ ВОПРОСЫ") +
    `<section class="panel faq">
      <details><summary>Где хранятся чаты?${icon("chevron", 15)}</summary><p>Сохранённые диалоги остаются на устройстве. Экспорт доступен в разделе «Чаты и данные».</p></details>
      <details><summary>Что делать, если чат не отвечает?${icon("chevron", 15)}</summary><p>Проверьте интернет и нажмите «Повторить» в чате. Если ошибка повторяется, напишите в поддержку.</p></details>
      <details><summary>Чем отличается диктовка от разговора?${icon("chevron", 15)}</summary><p>Микрофон вставляет распознанный текст в поле ввода. Кнопка с волной открывает разговор и отправляет реплику после паузы.</p></details>
    </section>`);
  }
  function about() {
    return detail("О приложении", "CORTEX для Android", `<section class="panel about-card">
      <div class="brand">${sign}<span>CORTEX</span></div><p class="muted">Версия ${version}</p>
      <p>Персональный помощник с текстовым и голосовым вводом.</p>
      <p class="muted">Проект разработчика stasaleksandrov481-netizen.</p>
    </section>` + group(tile("Конфиденциальность", "Какие данные используются", "shield", "go", "privacy") +
      tile("Связь с разработчиком", "Через поддержку CORTEX", "help", "go", "support")));
  }
  function privacy() {
    return detail("Конфиденциальность", "Данные, необходимые для работы", `<section class="panel stack prose">
      <h2>Профиль</h2><p>Для входа CORTEX получает имя, email и фото профиля Google. Имя и фото сохраняются на устройстве для отображения профиля.</p>
      <h2>Сообщения</h2><p>Сохранённые чаты и заметки остаются на устройстве. При отправке CORTEX передаёт серверу запрос и контекст, выбранный настройками памяти. Сервер обращается к OpenRouter.</p>
      <h2>Голос и команды</h2><p>Речь распознаёт служба Android. В голосовом разговоре распознанный текст отправляется как сообщение. ИИ выбирает инструменты по смыслу запроса. Android выполняет действие и передаёт результат. Текст экрана отправляется модели только когда вы приложите его к сообщению.</p>
      <h2>Поддержка</h2><p>Разработчик получает текст обращения и приложенные вами email, скриншот и версию приложения.</p>
    </section>`);
  }

  function page(route, ctx) {
    const pages = { home, chat, profile, settings, motion, voice, "phone-tools": phoneTools, language, notifications,
      data, memory, personality, instructions, notes, permissions, support, about, privacy, guide, models, "tool-list": toolList };
    return pages[route](ctx);
  }
  function navigation() {
    return '<span class="dock-glass" aria-hidden="true"></span><span class="nav-indicator" aria-hidden="true"></span>' +
      tabs.map(([key, title, name]) => key === "home" ?
        `<button type="button" class="dock-core" data-tab="home" aria-label="Кортекс" ${attributes("tab", "home")}>
          <span class="dock-emblem">${sign}</span></button>` :
        `<button type="button" class="dock-tab" data-tab="${key}" ${attributes("tab", key)}>
          <span class="nav-icon">${icon(name, 20)}</span><span>${title}</span></button>`).join("");
  }
  return { esc, icon, iconButton, button, tile, version, tabs, parent, page, composer, navigation, history, chatItems };
})();
