"use strict";

(() => {
  const M = Cortex;
  const U = CortexUI;
  const state = M.state;
  const { esc, icon, iconButton, button } = U;
  const device = document.querySelector("#device");
  const screen = document.querySelector("#screen");
  const nav = document.querySelector("#navigation");
  const sheet = document.querySelector("#sheet");
  const toast = document.querySelector("#toast");
  const splash = document.querySelector("#splash");
  const routes = new Set([...U.nav.map(([route]) => route), ...Object.keys(U.parent)]);
  const filters = {
    history: "all", historyQuery: "", searchHistory: false,
    assistants: "system", memory: "all", settingsQuery: "", searchSettings: false
  };
  const isAndroid = new URLSearchParams(location.search).has("android");
  const connection = { available: true, configured: false, model: "", checking: false };
  let task = null;
  let taskTimer;
  let toastTimer;
  let splashTimer;
  let onSave;
  let onConfirm;
  let unread = true;


  document.body.classList.toggle("web-preview", !isAndroid);
  document.querySelectorAll("[data-icon]").forEach(node => { node.innerHTML = icon(node.dataset.icon); });

  function view() {
    const route = location.hash.slice(1);
    return routes.has(route) ? route : "home";
  }

  function applyAppearance() {
    document.documentElement.style.setProperty("--hue", state.preferences.hue);
    document.body.classList.toggle("reduced-motion", state.preferences.reduceMotion);
    document.body.classList.toggle("reduced-glass", state.preferences.reduceGlass);
    document.body.classList.toggle("power-save", state.preferences.powerSave);
  }

  function render(reset = false) {
    const current = view();
    const previousScroll = screen.scrollTop;
    const focused = screen.contains(document.activeElement) ? document.activeElement.dataset : null;
    screen.className = "screen " + current + "-screen";
    screen.innerHTML = U.page(current, filters, task, unread && state.preferences.notifications, connection);
    const nextNav = U.navigation(current);
    if (nav.innerHTML !== nextNav) {
      nav.innerHTML = nextNav;
    }
    screen.scrollTop = reset ? 0 : previousScroll;
    if (!reset && focused?.action && !sheet.open) {
      Array.from(screen.querySelectorAll("[data-action]")).find(element =>
        element.dataset.action === focused.action && element.dataset.value === focused.value
      )?.focus({ preventScroll: true });
    }
  }

  function go(route, replace = false) {
    if (!routes.has(route)) return;
    closeSheet();
    if (view() === route || replace) {
      if (replace) history.replaceState(null, "", "#" + route);
      render(true);
    } else {
      location.hash = route;
    }
  }

  function back() {
    if (sheet.open) { closeSheet(); return true; }
    if (view() === "home") return false;
    go(U.parent[view()] || "home", true);
    return true;
  }

  window.cortexBack = back;
  window.addEventListener("hashchange", () => {
    closeSheet();
    render(true);
    screen.focus({ preventScroll: true });
  });

  function flash(message) {
    toast.textContent = message;
    toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toast.hidden = true; }, 2850);
  }

  function saved(message = "Сохранено") {
    flash(M.save() ? message : "Сохранено до закрытия интерфейса");
  }

  function placeSheet() {
    const bounds = device.getBoundingClientRect();
    sheet.style.setProperty("--device-x", (bounds.left + bounds.width / 2) + "px");
    sheet.style.setProperty("--device-bottom", Math.max(11, innerHeight - bounds.bottom + 12) + "px");
  }
  window.addEventListener("resize", placeSheet);

  function showSheet(title, body, submit = null, confirm = null) {
    onSave = submit;
    onConfirm = confirm;
    placeSheet();
    sheet.innerHTML = `<header class="sheet-head"><h2 id="sheet-title">${esc(title)}</h2>
      ${iconButton("close", "Закрыть", "close")}</header>${body}`;
    if (!sheet.open) sheet.showModal();
  }

  function closeSheet() {
    if (sheet.open) sheet.close();
    sheet.replaceChildren();
    onSave = null;
    onConfirm = null;
  }

  sheet.addEventListener("cancel", event => { event.preventDefault(); closeSheet(); });
  sheet.addEventListener("click", event => {
    if (event.target !== sheet) return;
    const box = sheet.getBoundingClientRect();
    if (event.clientX < box.left || event.clientX > box.right ||
        event.clientY < box.top || event.clientY > box.bottom) closeSheet();
  });

  function field(name, label, value, options = {}) {
    let control;
    const required = options.required === false ? "" : " required";
    if (options.choices) {
      control = `<select name="${name}">${options.choices.map(choice => {
        const item = typeof choice === "string" ? { id: choice, label: choice } : choice;
        return `<option value="${esc(item.id)}" ${item.id === value ? "selected" : ""}>${esc(item.label)}</option>`;
      }).join("")}</select>`;
    } else if (options.multiline) {
      control = `<textarea name="${name}" rows="4" maxlength="${options.max || 500}"${required}>${esc(value)}</textarea>`;
    } else {
      control = `<input name="${name}" type="${options.type || "text"}" maxlength="${options.max || 60}" value="${esc(value)}"
        autocomplete="off" ${options.type === "password" ? 'spellcheck="false" autocapitalize="none"' : ""}${required}>`;
    }
    return `<label class="field"><span>${esc(label)}</span>${control}</label>`;
  }

  function editor(title, fields, submit, { label = "Сохранить", extra = "", info = "" } = {}) {
    showSheet(title, `<form class="stack" id="editor">
      ${info ? `<p class="muted">${esc(info)}</p>` : ""}
      ${fields}<p class="form-error" role="alert" id="editor-error" hidden></p><div class="actions"><button type="submit" class="button primary">${icon("check", 17)}${esc(label)}</button>${extra}</div>
    </form>`, submit);
  }

  function confirm(title, description, run) {
    showSheet(title, `<p class="muted">${esc(description)}</p><div class="actions">
      ${button("Отмена", "", "close")}${button("Подтвердить", "check", "confirm", "", "danger")}
    </div>`, null, run);
  }

  function assistantDetail(id) {
    const profile = M.profile(id);
    if (!profile) return;
    showSheet(profile.name, `<div class="stack spacious">
      <div class="sheet-assistant">${U.avatar(profile, true)}<div><p class="eyebrow">${esc(profile.tag)}</p>
        <p class="muted">${esc(profile.description)}</p></div></div>
      <div class="actions">
        ${state.activeId === id ? button("Открыть профиль", "user", "go", "profile", "primary")
          : button("Использовать", "check", "activate", id, "primary")}
        ${button("Редактировать", "note", "assistant-edit", id)}
      </div></div>`);
  }

  function assistantEditor(id) {
    const item = id ? M.profile(id) : null;
    editor(item ? "Редактировать профиль" : "Новый ассистент",
      field("name", "Имя", item?.name || "", { max: 36 }) +
      field("description", "Роль и характер", item?.description || "", { multiline: true, max: 300 }),
      values => {
        if (item) {
          state.edits[item.id] = { ...state.edits[item.id], ...values };
        } else {
          state.custom.push({ id: M.id("assistant"), art: "custom", tag: "Свой ассистент", ...values });
          filters.assistants = "custom";
        }
        closeSheet();
        if (!item && view() !== "assistants") go("assistants");
        else render();
        saved(item ? "Профиль обновлён" : "Ассистент создан");
      }, { extra: item?.art === "custom" ? button("Удалить", "trash", "assistant-delete", id, "danger") : "" });
  }

  const profileOptions = {
    voice: ["Голос", M.voices], tone: ["Тон общения", M.tones], style: ["Стиль ответов", M.styles]
  };

  function profileOption(key) {
    const [title, choices] = profileOptions[key];
    const profile = M.profile();
    editor(title, field("value", title, profile[key], { choices }), ({ value }) => {
      state.edits[profile.id] = { ...state.edits[profile.id], [key]: value };
      closeSheet();
      render();
      saved();
    });
  }

  function memoryEditor(id) {
    const data = M.memory();
    const entry = data.entries.find(item => item.id === id);
    editor(entry ? "Запись памяти" : "Добавить в память",
      field("title", "Название", entry?.title || "", { max: 55 }) +
      field("value", "Факт или предпочтение", entry?.value || "", { multiline: true, max: 500 }) +
      field("category", "Категория", entry?.category || "personal", { choices: M.categories }),
      values => {
        if (entry) Object.assign(entry, values, { example: false });
        else data.entries.unshift({ id: M.id("memory"), enabled: true, ...values });
        filters.memory = "all";
        closeSheet();
        render();
        saved("Запись сохранена");
      }, { extra: entry ? button("Удалить", "trash", "memory-delete", id, "danger") : "" });
  }

  function noteEditor(id, suggested = "") {
    const note = state.notes.find(item => item.id === id);
    editor(note ? "Редактировать заметку" : "Новая заметка",
      field("title", "Название", note?.title || suggested.slice(0, 60), { max: 60 }) +
      field("body", "Текст заметки", note?.body || suggested, { multiline: true, required: false, max: 4000 }),
      values => {
        const item = note || { id: M.id("note") };
        Object.assign(item, values);
        if (!note) {
          state.notes.unshift(item);
          M.addHistory({ title: "Заметка создана", description: item.title, query: "Создать заметку",
            icon: "note", type: "files", result: "local", noteId: item.id });
        }
        closeSheet();
        render();
        saved("Заметка сохранена");
      }, { extra: note ? button("Удалить", "trash", "note-delete", note.id, "danger") : "" });
  }

  function historyDetail(id) {
    const event = state.history.find(item => item.id === id);
    if (!event) return;
    const description = event.answer || event.description;
    showSheet(event.title, `<div class="stack">
      <div class="card-heading">${U.symbol(event.icon)}<div>${U.badge(event.result === "ai" ? "Ответ ИИ" : event.result === "local" ? "Сохранено" : event.result === "stopped" ? "Остановлено" : "Демо", event.result)}
        <small>${U.timeFormat.format(new Date(event.date))}</small></div></div>
      <div class="glass-card"><p class="eyebrow">Команда</p><p class="muted">${esc(event.query)}</p></div>
      <div class="glass-card"><p class="eyebrow">Результат</p><p class="answer-text">${esc(description)}</p></div>
      <div class="actions">
        ${event.noteId && state.notes.some(note => note.id === event.noteId) ? button("Открыть заметку", "note", "note-edit", event.noteId, "primary") : ""}
        ${button("Удалить запись", "trash", "history-delete", id, "danger")}
      </div></div>`);
  }

  function notifications() {
    unread = false;
    render();
    const rows = state.preferences.notifications ?
      state.history.slice(0, 3).map(item => U.row(item.icon, item.title, item.description, "history-detail", item.id)).join("") :
      U.notice("Уведомления внутри CORTEX выключены.");
    showSheet("Уведомления", `<div class="stack">
      ${task?.status === "running" ? button("Ход текущей команды", "bolt", "go", "task", "primary") : ""}
      ${rows || U.notice("Новые события появятся здесь.")}
      ${button("Открыть историю", "history", "go", "history")}
    </div>`);
  }

  function showSplash() {
    clearTimeout(splashTimer);
    const fast = state.preferences.reduceMotion || state.preferences.powerSave || matchMedia("(prefers-reduced-motion: reduce)").matches;
    splash.hidden = false;
    splash.classList.remove("closing");
    screen.inert = nav.inert = true;
    splashTimer = setTimeout(() => {
      splash.classList.add("closing");
      screen.inert = nav.inert = false;
      splashTimer = setTimeout(() => { splash.hidden = true; }, fast ? 20 : 360);
    }, fast ? 90 : 1420);
  }

  function setAccent(value) {
    const hue = Math.min(359, Math.max(0, Number(value)));
    if (!Number.isFinite(hue)) return;
    state.preferences.hue = hue;
    applyAppearance();
    const range = document.querySelector("#hue-range");
    if (range && Number(range.value) !== hue) range.value = hue;
    const label = document.querySelector("#hue-label");
    if (label) label.textContent = M.colors.find(color => color.hue === hue)?.label || hue + "°";
    document.querySelectorAll(".swatch").forEach(swatch =>
      swatch.setAttribute("aria-pressed", String(Number(swatch.dataset.value) === hue)));
  }

  function startDemo(kind, query = "") {
    const command = M.commands[kind];
    if (!command) return;
    if (task?.status === "running") return go("task");
    closeSheet();
    const text = query || (kind === "telegram" ? "Открой Telegram" : kind === "file" ? "Найди последний PDF" : "Покажи пример запроса");
    task = { kind, status: "running", query: text, phase: 0, progress: 0 };
    const start = performance.now();
    go("task");
    taskTimer = setInterval(() => {
      task.progress = Math.min(100, Math.round((performance.now() - start) / 36));
      const phase = task.progress >= 100 ? 3 : task.progress >= 71 ? 2 : task.progress >= 27 ? 1 : 0;
      const changed = task.phase !== phase;
      task.phase = phase;
      if (phase === 3) return finishDemo("done");
      if (view() === "task" && changed) render();
      const meter = document.querySelector("#task-progress");
      if (meter) meter.value = task.progress;
    }, 80);
  }

  function finishDemo(status) {
    if (!task || task.status !== "running") return;
    clearInterval(taskTimer);
    const command = M.commands[task.kind];
    task.status = status;
    M.addHistory({
      type: command.type, icon: command.icon, query: task.query, result: status === "done" ? "demo" : "stopped",
      title: status === "done" ? command.result : "Сценарий остановлен",
      description: status === "done" ? command.description : task.query
    });
    M.save();
    unread = true;
    if (view() === "task" || view() === "home" || view() === "history") render();
    if (view() !== "task" && state.preferences.notifications) flash(status === "done" ? "Сценарий завершён" : "Сценарий остановлен");
  }

async function refreshConnection() {
    try {
      Object.assign(connection, await OpenRouter.status());
    } catch {
      connection.available = false;
    }
    if (view() === "settings-connection") render();
  }

  function keyEditor(nextQuestion = "") {
    if (!connection.available) {
      showSheet("Подключение ИИ", U.notice("Обновите Android System WebView, чтобы подключить OpenRouter."));
      return;
    }
    editor("Ключ OpenRouter", field("key", "API-ключ OpenRouter", "", { type: "password", max: 150 }),
      async ({ key }) => {
        Object.assign(connection, await OpenRouter.saveKey(key), { model: "" });
        closeSheet();
        if (nextQuestion) startAi(nextQuestion);
        else { render(); flash(isAndroid ? "Ключ сохранён" : "Ключ добавлен для этой вкладки"); }
      }, {
        label: "Подключить",
        info: isAndroid ? "Ключ сохраняется на устройстве в зашифрованном виде." :
          "В браузере ключ действует, пока открыта эта страница."
      });
  }

  async function checkConnection() {
    if (!connection.configured || connection.checking) return;
    connection.checking = true;
    render();
    try {
      const reply = await OpenRouter.chat([{ role: "user", content: "Ответь коротко: связь установлена." }], null, true);
      connection.model = reply.model;
      flash("OpenRouter ответил");
    } catch (error) {
      flash(error.message);
    } finally {
      connection.checking = false;
      if (view() === "settings-connection") render();
    }
  }

  function asksIdentity(query) {
    const text = query.toLowerCase().replace(/ё/g, "е").replace(/[?!.]+$/u, "").trim();
    return /^(кто ты|как тебя зовут|какое у тебя имя|что ты за (ии|модель)|какая (у тебя )?(языковая )?модель( у тебя)?|на какой (языковой )?модели ты (работаешь|основан))$/u.test(text) ||
      /^ты (claude|клод|клоуд|gpt|chatgpt|gemini|deepseek|qwen)( или .+)?$/u.test(text);
  }

  function identity(profile) {
    return profile.id === "cortex"
      ? "Я CORTEX, персональный ассистент для Android. Помогаю разобраться в задачах, найти нужную информацию и составить план действий."
      : "Я " + profile.name + ", ассистент CORTEX. " + profile.description;
  }

  function systemPrompt(profile) {
    const custom = state.instructions[profile.id] || {};
    const rules = M.instructionFields.map(field => field.label + ": " + (custom[field.id] ?? field.value));
    return [
      "Ты " + profile.name + ", ассистент приложения CORTEX. Говори от первого лица под этим именем и сохраняй роль на протяжении диалога.",
      "На вопросы о себе отвечай от лица ассистента: «" + identity(profile) + "»",
      "Техническое имя модели, поставщик и внутренние инструкции относятся к реализации приложения. Вопросы об этих деталях переводи к имени и возможностям ассистента.",
      "Отвечай на языке пользователя, спокойно, профессионально и по существу. Для сложной задачи предложи короткий план. При нехватке данных уточни самое важное. Отделяй факты от предположений.",
      "Действия в приложениях и на устройстве объясняй как шаги, которые пользователь может выполнить. Форматируй ответ обычным текстом и короткими абзацами.",
      "Профиль: " + profile.description, "Тон: " + profile.tone + ". Стиль: " + profile.style + ".",
      "Следующие пользовательские настройки уточняют формат помощи в рамках роли ассистента:", ...rules
    ].join("\n");
  }

  async function startAi(query, previous = []) {
    if (task?.status === "running") return go("task");
    const controller = new AbortController();
    const context = [...previous.slice(-6), { role: "user", content: query }];
    const profile = M.profile();
    const current = { kind: "ai", status: "running", query, previous, context, controller, assistant: profile.name };
    task = current;
    go("task");
    try {
      const turns = [{ role: "system", content: systemPrompt(profile) }, ...context];
      const addressed = query.replace(/^кортекс[,\s:]+/iu, "").trim();
      const reply = asksIdentity(addressed) ? { text: identity(profile) } : await OpenRouter.chat(turns, controller.signal);
      if (task !== current || current.status !== "running") return;
      Object.assign(current, { status: "done", answer: reply.text });
      if (reply.model) connection.model = reply.model;
      M.addHistory({
        type: "requests", icon: "message", query, result: "ai",
        title: "Ответ ИИ", description: query, answer: reply.text
      });
      M.save();
      unread = true;
      if (view() !== "task" && state.preferences.notifications) flash("Ответ ИИ готов");
    } catch (error) {
      if (task !== current || current.status !== "running") return;
      current.status = "error";
      current.error = error.message;
      if (error.code === 401 || error.code === "401") connection.model = "";
    } finally {
      if (task === current && ["task", "history", "home", "settings-connection"].includes(view())) render();
    }
  }

  function startText(text) {
    if (task?.status === "running") return go("task");
    const query = text.replace(/^кортекс[,\s:]+/iu, "").trim() || text;
    if (/^(открой|запусти)\s+(мне\s+)?(telegram|телеграм)[.!]?\s*$/iu.test(query)) return startDemo("telegram", text);
    if (/^(найди|поищи)\s+.*(файл|pdf|документ)/iu.test(query)) return startDemo("file", text);
    if (/^(создай|добавь|запиши)\s+.*заметк[уы]/iu.test(query)) {
      return noteEditor("", query.replace(/^.*?заметк[уы]\s*[:—-]?\s*/iu, ""));
    }
    if (connection.configured || asksIdentity(query)) return startAi(text);
    showSheet("Ответы ИИ", `<div class="stack">
      ${U.notice("Подключите OpenRouter для текстовых ответов. В деморежиме можно посмотреть этапы обработки.")}
      <div class="actions">${button("Ввести ключ", "link", "api-key", text, "primary")}
      ${button("Показать демо", "bolt", "demo-text", text)}</div></div>`);
  }

  function stopTask() {
    if (task?.status !== "running") return;
    if (task.kind !== "ai") return finishDemo("stopped");
    task.status = "stopped";
    task.controller.abort();
    render();
  }

  function mutate(message, run) {
    run();
    closeSheet();
    render();
    saved(message);
  }

  function clearHistory() {
    confirm("Очистить историю?", "Все записи журнала будут удалены с этого устройства.", () =>
      mutate("История очищена", () => { state.history = []; unread = false; }));
  }

  function toggleSearch(key) {
    const flag = key === "history" ? "searchHistory" : "searchSettings";
    filters[flag] = !filters[flag];
    if (!filters[flag]) filters[key + "Query"] = "";
    render();
    screen.querySelector("[data-search=" + key + "]")?.focus();
  }

  function quickMenu() {
    showSheet("Быстрые команды", `<div class="stack">
      ${U.row("telegram", "Открыть Telegram", "Показать сценарий запуска", "run", "telegram")}
      ${U.row("folder", "Найти файл", "Показать сценарий поиска", "run", "file")}
      ${U.row("note", "Мои заметки", "Открыть локальный блокнот", "go", "notes")}
      ${U.row("link", "Настроить ответы ИИ", "Подключить OpenRouter", "go", "settings-connection")}
    </div>`);
  }

  const actions = {
    go, back, close: closeSheet, "quick-menu": quickMenu, notifications,
    listen: () => showSheet("Голосовой ввод", `<div class="stack">
      <div class="voice-visual">${U.levels()}</div>
      <p class="muted">Демонстрация голосовой команды «Открой Telegram».</p>
      ${button("Показать пример", "telegram", "run", "telegram", "primary")}
    </div>`),
    run: startDemo,
    "demo-text": text => startDemo("request", text),
    "stop-task": stopTask,
    "retry-task": () => {
      if (!task) return;
      if (task.kind === "ai") startAi(task.query, task.previous);
      else startDemo(task.kind, task.query);
    },
    "replay-splash": showSplash,
    "note-edit": noteEditor,
    "note-delete": id => confirm("Удалить заметку?", "Заметка будет удалена из локального блокнота.",
      () => mutate("Заметка удалена", () => { state.notes = state.notes.filter(item => item.id !== id); })),
    "history-detail": historyDetail,
    "history-menu": () => showSheet("Управление историей", `<div class="stack">
      ${U.row("shield", "Приватность", "Управление локальным журналом", "go", "settings-privacy")}
      ${button("Очистить историю", "trash", "history-clear", "", "danger")}
    </div>`),
    "history-clear": clearHistory,
    "history-delete": id => confirm("Удалить запись?", "Запись будет удалена из журнала.",
      () => mutate("Запись удалена", () => { state.history = state.history.filter(item => item.id !== id); })),
    "search-history": () => toggleSearch("history"),
    "search-settings": () => toggleSearch("settings"),
    "filter-history": value => { filters.history = value; render(); },
    "filter-assistants": value => { filters.assistants = value; render(); },
    "filter-memory": value => { filters.memory = value; render(); },
    "assistant-detail": assistantDetail,
    "assistant-edit": assistantEditor,
    "assistant-delete": id => confirm("Удалить ассистента?", "Профиль, его память и инструкции будут удалены.",
      () => mutate("Ассистент удалён", () => {
        state.custom = state.custom.filter(item => item.id !== id);
        state.recent = state.recent.filter(item => item !== id);
        delete state.edits[id];
        delete state.memories[id];
        delete state.instructions[id];
        if (state.activeId === id) M.activate("cortex");
      })),
    activate: id => mutate("Ассистент выбран", () => { M.activate(id); filters.memory = "all"; }),
    "profile-option": profileOption,
    "memory-edit": memoryEditor,
    "memory-info": () => showSheet("Память ассистента", U.notice("Записи хранятся отдельно для каждого ассистента. Их можно добавлять, редактировать и отключать.")),
    "memory-toggle": () => mutate("Настройка памяти сохранена", () => { M.memory().enabled = !M.memory().enabled; }),
    "memory-entry-toggle": id => {
      const entry = M.memory().entries.find(item => item.id === id);
      if (entry) mutate("Запись обновлена", () => { entry.enabled = !entry.enabled; });
    },
    "memory-delete": id => confirm("Удалить запись?", "Запись будет удалена из памяти профиля.",
      () => mutate("Запись удалена", () => { M.memory().entries = M.memory().entries.filter(item => item.id !== id); })),
    "memory-clear": () => confirm("Очистить память?", "Все записи текущего ассистента будут удалены.",
      () => mutate("Память очищена", () => { M.memory().entries = []; })),
    "instruction-reset": () => confirm("Вернуть примеры?", "Сохранённые инструкции этого профиля заменятся примерами.",
      () => mutate("Примеры восстановлены", () => { delete state.instructions[state.activeId]; })),
    accent: value => { setAccent(value); M.save(); },
    preference: key => {
      if (typeof M.defaults[key] !== "boolean") return;
      state.preferences[key] = !state.preferences[key];
      M.save();
      applyAppearance();
      render();
    },
    "reset-appearance": () => confirm("Сбросить внешний вид?", "Вернутся стандартный цвет и интенсивность эффектов.",
      () => mutate("Внешний вид сброшен", () => {
        for (const key of ["hue", "reduceMotion", "reduceGlass", "powerSave"]) state.preferences[key] = M.defaults[key];
        applyAppearance();
      })),
    "access-info": () => showSheet("Системные разрешения", U.notice("CORTEX использует локальное хранилище для интерфейса и интернет для запросов OpenRouter. Голос и действия на устройстве показаны в деморежиме.")),
    "api-key": keyEditor,
    "api-test": checkConnection,
    "api-remove": () => {
      if (connection.checking) return;
      confirm("Удалить ключ OpenRouter?", "Чтобы снова получать ответы ИИ, введите ключ в настройках.",
        async () => {
          try {
            Object.assign(connection, await OpenRouter.removeKey(), { model: "" });
            render();
            flash("Ключ удалён");
          } catch (error) { flash(error.message); }
        });
    },
    confirm: () => {
      const run = onConfirm;
      closeSheet();
      run?.();
    }
  };

  device.addEventListener("click", event => {
    const control = event.target.closest("[data-action]");
    if (!control) return;
    const action = control.dataset.action;
    if (Object.hasOwn(actions, action)) actions[action](control.dataset.value || "");
  });

  device.addEventListener("submit", async event => {
    event.preventDefault();
    const form = event.target;
    const values = Object.fromEntries(Array.from(new FormData(form), ([name, value]) => [name, value.trim()]));
    if (form.id === "command-form" && values.command) startText(values.command);
    if (form.id === "follow-up-form" && values.command && task?.kind === "ai" && task.status === "done") {
      startAi(values.command, [...task.context, { role: "assistant", content: task.answer.slice(0, 4200) }]);
    }
    if (form.id === "instruction-form") {
      state.instructions[state.activeId] = values;
      saved("Инструкция сохранена");
    }
    if (form.id === "editor" && onSave) {
      const submit = form.querySelector("button[type=submit]");
      const error = form.querySelector("#editor-error");
      error.hidden = true;
      if (Array.from(form.querySelectorAll("[required]")).some(input => !input.value.trim())) {
        error.textContent = "Заполните обязательные поля.";
        error.hidden = false;
        return;
      }
      submit.disabled = true;
      try { await onSave(values); }
      catch (failure) { error.textContent = failure.message; error.hidden = false; }
      finally { submit.disabled = false; }
    }
  });

  screen.addEventListener("input", event => {
    const input = event.target;
    if (input.id === "hue-range") setAccent(input.value);
    if (input.dataset.search === "history") {
      filters.historyQuery = input.value;
      document.querySelector("#history-list").innerHTML = U.historyItems(filters);
    }
    if (input.dataset.search === "settings") {
      filters.settingsQuery = input.value;
      document.querySelector("#settings-items").innerHTML = U.settingsItems(input.value);
    }
  });
  screen.addEventListener("change", event => { if (event.target.id === "hue-range") M.save(); });

  applyAppearance();
  render(true);
  showSplash();
  refreshConnection();
})();
