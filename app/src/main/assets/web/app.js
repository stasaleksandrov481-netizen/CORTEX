"use strict";

(() => {
  const M = Cortex;
  const U = CortexUI;
  const S = M.state;
  const { esc, icon, button, iconButton } = U;
  const app = document.querySelector("#app");
  const screen = document.querySelector("#screen");
  const nav = document.querySelector("#navigation");
  const splash = document.querySelector("#splash");
  const sheet = document.querySelector("#sheet");
  const toast = document.querySelector("#toast");
  const isAndroid = new URLSearchParams(location.search).has("android");
  const device = window.CortexDevice;
  const prefersReduced = matchMedia("(prefers-reduced-motion: reduce)");
  const connection = { available: true, configured: false, checked: false, checking: false };
  const routes = new Set([...U.tabs.map(([key]) => key), ...Object.keys(U.parent), "chat"]);
  const drafts = new Map();
  const errors = new Map();
  const ruleDrafts = new Map();
  const filters = { onlySaved: false, searchOpen: false, search: "", rule: "goal" };
  let route = "home";
  let chatId = "";
  let chatParent = "home";
  let placeholder = 0;
  let pending;
  let animation;
  let navigationEpoch = 0;
  let toastTimer;
  let splashTimer;
  let onSave;
  let onConfirm;
  let voiceChat = "";

  document.body.classList.toggle("web-preview", !isAndroid);

  const reduce = () => S.preferences.reduceMotion || prefersReduced.matches;
  const activeChat = () => M.chat(chatId);
  const ruleKey = () => S.activeId + "/" + filters.rule;

  function appearance() {
    document.documentElement.style.setProperty("--hue", Number(S.preferences.hue));
    document.body.classList.toggle("reduced-motion", reduce());
  }

  function render({ enter = false, bottom = false } = {}) {
    const previous = screen.querySelector(".scroll");
    const previousScroll = previous?.scrollTop || 0;
    const previousRoute = screen.dataset.route;
    const active = document.activeElement;
    const hadComposerFocus = active?.id === "chat-input";
    const chat = activeChat();
    screen.dataset.route = route;
    screen.className = "screen " + route + "-screen" + (enter ? " route-enter" : "");
    screen.innerHTML = U.page(route, {
      ...filters, isAndroid, native: Boolean(device), chat, placeholder, connection,
      pending: pending?.chatId === chat?.id ? pending : null,
      error: errors.get(chatId)?.message, draft: drafts.get(chatId) || "",
      ruleDraft: ruleDrafts.get(ruleKey())
    });
    nav.hidden = route === "chat" || Object.hasOwn(U.parent, route);
    if (!nav.hidden) nav.innerHTML = U.navigation(route);
    const scroll = screen.querySelector(".scroll");
    if (scroll && (bottom || previousRoute === route)) scroll.scrollTop = bottom ? scroll.scrollHeight : previousScroll;
    if (route === "chat") {
      sizeComposer();
      if (hadComposerFocus && previousRoute === route && !sheet.open) focusComposer();
    }
    if (splash.hidden || splash.classList.contains("leave")) {
      CortexWave.mount(screen.querySelector("[data-wave-host]"), reduce());
    }
  }

  function focusComposer() {
    const field = document.querySelector("#chat-input");
    if (!field || sheet.open) return;
    field.focus({ preventScroll: true });
    field.setSelectionRange(field.value.length, field.value.length);
  }

  function navigate(next, { id = "", replace = false, morph = false, focus = false } = {}) {
    if (!routes.has(next) || (next === "chat" && !M.chat(id))) return;
    closeSheet();
    const old = morph ? screen.querySelector(".home-compose")?.getBoundingClientRect() : null;
    animation?.skipTransition?.();
    const epoch = ++navigationEpoch;
    const swap = () => {
      if (epoch !== navigationEpoch) return;
      route = next;
      chatId = id;
      history[replace ? "replaceState" : "pushState"](null, "", "#" + next + (id ? "/" + id : ""));
      render({ enter: !morph, bottom: next === "chat" });
    };
    if (morph && !reduce() && typeof document.startViewTransition === "function") {
      const transition = document.startViewTransition(swap);
      animation = transition;
      transition.finished.catch(() => {}).then(() => {
        if (animation !== transition) return;
        animation = null;
        if (focus && route === "chat" && chatId === id) focusComposer();
      });
      return;
    }
    swap();
    const composer = screen.querySelector(".chat-form");
    if (old && composer && !reduce() && composer.animate) {
      const now = composer.getBoundingClientRect();
      composer.animate([
        { transform: "translate(" + (old.left - now.left) + "px," + (old.top - now.top) + "px) scale(" +
          (old.width / now.width) + "," + (old.height / now.height) + ")", borderRadius: "25px" },
        { transform: "none", borderRadius: "23px" }
      ], { duration: 520, easing: "cubic-bezier(.2,.72,.16,1)" });
      setTimeout(() => { if (focus && route === "chat" && chatId === id) focusComposer(); }, 510);
    } else if (focus) focusComposer();
  }

  function readRoute() {
    const [next, id] = location.hash.slice(1).split("/");
    route = routes.has(next) && (next !== "chat" || M.chat(id)) ? next : "home";
    chatId = route === "chat" ? id : "";
    render({ bottom: route === "chat" });
  }

  function back() {
    if (sheet.open) { closeSheet(); return true; }
    if (route === "home") return false;
    navigate(route === "chat" ? chatParent : U.parent[route] || "home", { replace: true });
    return true;
  }
  window.cortexBack = back;
  addEventListener("popstate", () => {
    navigationEpoch++;
    animation?.skipTransition?.();
    animation = null;
    closeSheet();
    readRoute();
  });

  function openNew(prefill = "") {
    if (animation) return;
    const item = M.createChat();
    if (prefill) drafts.set(item.id, prefill);
    chatParent = route === "chats" ? "chats" : "home";
    navigate("chat", { id: item.id, morph: route === "home", focus: true });
  }

  function openExisting(id) {
    if (!M.chat(id)) return;
    chatParent = "chats";
    navigate("chat", { id });
  }

  function sizeComposer() {
    const field = document.querySelector("#chat-input");
    if (!field) return;
    field.style.height = "43px";
    field.style.height = Math.min(130, Math.max(43, field.scrollHeight + 1)) + "px";
  }

  function flash(message) {
    toast.textContent = message;
    toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toast.hidden = true; }, 2400);
  }

  function saved(message) {
    if (M.save()) { if (message) flash(message); return true; }
    flash("Память устройства заполнена. Изменения доступны в этой сессии.");
    return false;
  }

  function placeSheet() {
    const bounds = app.getBoundingClientRect();
    sheet.style.setProperty("--sheet-left", bounds.left + bounds.width / 2 + "px");
    sheet.style.setProperty("--sheet-bottom", Math.max(10, innerHeight - bounds.bottom + 10) + "px");
  }
  addEventListener("resize", placeSheet);

  function showSheet(title, body, submit = null, confirm = null) {
    onSave = submit;
    onConfirm = confirm;
    placeSheet();
    sheet.innerHTML = `<header class="sheet-head"><h2 id="sheet-title">${esc(title)}</h2>
      ${iconButton("close", "Закрыть", "close", "", "subtle")}</header>${body}`;
    if (!sheet.open) sheet.showModal();
  }

  function closeSheet() {
    if (!sheet.open) return;
    sheet.close();
    sheet.replaceChildren();
    onSave = onConfirm = null;
  }

  sheet.addEventListener("cancel", event => { event.preventDefault(); closeSheet(); });
  sheet.addEventListener("click", event => {
    if (event.target !== sheet) return;
    const bounds = sheet.getBoundingClientRect();
    if (event.clientY < bounds.top || event.clientY > bounds.bottom ||
        event.clientX < bounds.left || event.clientX > bounds.right) closeSheet();
  });

  function field(name, label, value = "", { max = 90, type = "text", rows = 0, optional = false, choices } = {}) {
    const required = optional ? "" : " required";
    const input = choices ? `<select name="${name}">${Object.entries(choices).map(([key, label]) =>
      `<option value="${esc(key)}" ${key === value ? "selected" : ""}>${esc(label)}</option>`).join("")}</select>` :
      rows ? `<textarea name="${name}" rows="${rows}" maxlength="${max}"${required}>${esc(value)}</textarea>` :
      `<input name="${name}" type="${type}" value="${esc(value)}" maxlength="${max}" autocomplete="off"
        ${type === "password" ? 'spellcheck="false" autocapitalize="none"' : ""}${required}>`;
    return `<label class="field"><span>${esc(label)}</span>${input}</label>`;
  }

  function editor(title, fields, submit, { label = "Сохранить", info = "", extra = "" } = {}) {
    showSheet(title, `<form id="editor" class="stack">
      ${info ? `<p class="muted">${esc(info)}</p>` : ""}
      ${fields}<p id="editor-error" class="form-error" role="alert" hidden></p>
      <div class="actions"><button type="submit" class="action primary">${icon("check", 17)}${esc(label)}</button>${extra}</div>
    </form>`, submit);
  }

  function confirm(title, description, run) {
    showSheet(title, `<div class="stack"><p class="muted">${esc(description)}</p>
      <div class="actions">${button("Отмена", "", "close")}${button("Подтвердить", "check", "confirm", "", "danger")}</div></div>`, null, run);
  }

  function showSplash() {
    clearTimeout(splashTimer);
    const still = reduce();
    splash.hidden = false;
    splash.className = "splash";
    screen.inert = nav.inert = true;
    requestAnimationFrame(() => {
      CortexWave.mount(splash.querySelector("[data-wave-host]"), still);
      splash.classList.add(still ? "still" : "play");
    });
    splashTimer = setTimeout(() => {
      splash.classList.add("leave");
      screen.inert = nav.inert = false;
      render();
      splashTimer = setTimeout(() => { splash.hidden = true; }, still ? 0 : 450);
    }, still ? 380 : 2080);
  }

  async function refreshConnection() {
    try { Object.assign(connection, await OpenRouter.status()); }
    catch { connection.available = false; }
    if (route === "connection") render();
  }

  function keyEditor(afterSave) {
    if (!connection.available) {
      showSheet("Подключение ИИ", '<p class="muted">Обновите Android System WebView, чтобы включить ответы CORTEX.</p>');
      return;
    }
    editor("Подключить OpenRouter", field("key", "API-ключ OpenRouter", "", { type: "password", max: 150 }),
      async ({ key }) => {
        Object.assign(connection, await OpenRouter.saveKey(key), { checked: false });
        closeSheet();
        if (typeof afterSave === "function") afterSave();
        else { render(); flash(isAndroid ? "Ключ сохранён на устройстве" : "Ключ добавлен в эту вкладку"); }
      }, {
        label: "Подключить",
        info: isAndroid ? "Достаточно ввести ключ один раз. Android сохранит его зашифрованным." : "Ключ действует, пока открыта эта страница."
      });
  }

  async function testKey() {
    if (!connection.configured || connection.checking) return;
    connection.checking = true;
    render();
    try {
      await OpenRouter.chat([{ role: "user", content: "Ответь на русском одним словом: готово." }], { check: true });
      connection.checked = true;
      flash("Связь с OpenRouter проверена");
    } catch (error) {
      connection.checked = false;
      flash(error.message);
    } finally {
      connection.checking = false;
      if (route === "connection") render();
    }
  }

  function persistChat(chat) {
    if (chat.saved && !M.save()) flash("Последние сообщения доступны в этой сессии. Место для сохранения закончилось.");
  }

  function saveChat(chat = activeChat()) {
    if (!chat) return;
    chat.saved = !chat.saved;
    if (!saved(chat.saved ? "Чат сохранён" : "Чат убран из сохранённых")) chat.saved = !chat.saved;
    render();
  }

  function drawDelta(job) {
    if (pending !== job || route !== "chat" || chatId !== job.chatId) return;
    const stream = screen.querySelector("[data-stream]");
    const scroll = screen.querySelector(".conversation");
    if (!stream || !scroll) return;
    const pinned = scroll.scrollHeight - scroll.clientHeight - scroll.scrollTop < 92;
    stream.textContent = job.text;
    if (pinned) scroll.scrollTop = scroll.scrollHeight;
  }

  async function answer(chat, text, retry = false) {
    if (!chat || !text.trim()) return;
    if (pending) {
      if (pending.chatId !== chat.id) flash("Дождитесь ответа в другом чате.");
      return;
    }
    const identity = M.identity(text, chat.assistantId);
    if (!identity && (!connection.configured || errors.get(chat.id)?.code === 401)) {
      keyEditor(() => {
        errors.delete(chat.id);
        answer(chat, text, retry);
      });
      return;
    }
    if (!retry) {
      if (!chat.messages.length) chat.title = text.trim().replace(/\s+/g, " ").slice(0, 72);
      chat.messages.push({ role: "user", text });
      drafts.delete(chat.id);
    }
    errors.delete(chat.id);
    M.touch(chat);
    persistChat(chat);
    if (identity) {
      chat.messages.push({ role: "assistant", text: identity });
      persistChat(chat);
      if (route === "chat" && chatId === chat.id) render({ bottom: true });
      return;
    }
    const job = { chatId: chat.id, text: "", frame: 0, controller: new AbortController() };
    pending = job;
    if (route === "chat" && chatId === chat.id) render({ bottom: true });
    try {
      const response = await OpenRouter.chat(M.turns(chat), {
        signal: job.controller.signal, style: M.profile(chat.assistantId).style,
        onDelta(delta) {
          if (pending !== job) return;
          job.text += delta;
          if (job.frame) return;
          job.frame = requestAnimationFrame(() => { job.frame = 0; drawDelta(job); });
        }
      });
      if (pending !== job) return;
      chat.messages.push({ role: "assistant", text: response.text });
      connection.checked = true;
      M.touch(chat);
      persistChat(chat);
    } catch (error) {
      if (pending !== job) return;
      errors.set(chat.id, error);
      if (error.code === 401) connection.checked = false;
    } finally {
      if (pending === job) {
        pending = null;
        cancelAnimationFrame(job.frame);
        if (route === "chat" && chatId === chat.id) {
          const scroll = screen.querySelector(".conversation");
          render({ bottom: !scroll || scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 92 });
        } else if (route === "chats" || route === "connection") render();
      }
    }
  }

  function stop() {
    if (!pending) return;
    const job = pending;
    pending = null;
    job.controller.abort();
    cancelAnimationFrame(job.frame);
    const chat = M.chat(job.chatId);
    if (chat && job.text.trim()) {
      chat.messages.push({ role: "assistant", text: job.text.trim() });
      M.touch(chat);
      persistChat(chat);
    } else if (chat) {
      errors.set(chat.id, new Error("Ответ остановлен."));
    }
    render();
  }
function profileEditor(id) {
    const person = id ? M.allProfiles().find(item => item.id === id) : null;
    const name = person?.id === "cortex" ? "" : field("name", "Имя", person?.name || "", { max: 36 });
    const fields = name + field("description", "Роль", person?.description || "", { rows: 3, max: 380 }) +
      field("tone", "Манера общения", person?.tone || "Спокойный и деловой", { max: 90 }) +
      field("style", "Длина ответа", person?.style || "balanced", { choices: U.styles });
    editor(person ? "Настроить ассистента" : "Новый ассистент", fields, values => {
      if (person) S.edits[person.id] = { ...S.edits[person.id], ...values };
      else {
        const item = { id: M.id("assistant"), art: "custom", tag: "Мой", ...values };
        S.custom.push(item);
        S.activeId = item.id;
      }
      closeSheet();
      saved(person ? "Профиль сохранён" : "Ассистент создан");
      render();
    }, {
      extra: person?.art === "custom" ? button("Удалить", "trash", "profile-delete", person.id, "danger") : ""
    });
  }

  function profileDelete(id) {
    if (pending && M.chat(pending.chatId)?.assistantId === id) {
      flash("Сначала дождитесь текущего ответа.");
      return;
    }
    confirm("Удалить ассистента?", "Его чаты сохранятся и будут доступны у CORTEX.", () => {
      S.custom = S.custom.filter(item => item.id !== id);
      S.chats.forEach(chat => { if (chat.assistantId === id) chat.assistantId = "cortex"; });
      delete S.edits[id];
      delete S.memories[id];
      delete S.instructions[id];
      if (S.activeId === id) S.activeId = "cortex";
      saved("Ассистент удалён");
      render();
    });
  }

  function memoryEditor(id) {
    const memory = M.memory();
    const entry = memory.entries.find(item => item.id === id);
    editor(entry ? "Запись памяти" : "Запомнить факт",
      field("title", "Название", entry?.title || "", { max: 65 }) +
      field("value", "Что запомнить", entry?.value || "", { rows: 3, max: 600 }),
      values => {
        if (entry) Object.assign(entry, values);
        else memory.entries.unshift({ id: M.id("fact"), enabled: true, ...values });
        closeSheet();
        saved("Факт сохранён");
        render();
      }, { extra: entry ? button("Удалить", "trash", "memory-delete", entry.id, "danger") : "" });
  }

  function noteEditor(id) {
    const note = S.notes.find(item => item.id === id);
    editor(note ? "Заметка" : "Новая заметка",
      field("title", "Название", note?.title || "", { max: 65 }) +
      field("body", "Текст", note?.body || "", { rows: 5, max: 5000, optional: true }),
      values => {
        if (note) Object.assign(note, values);
        else S.notes.unshift({ id: M.id("note"), ...values });
        closeSheet();
        saved("Заметка сохранена");
        render();
      }, { extra: note ? button("Удалить", "trash", "note-delete", note.id, "danger") : "" });
  }

  function chatMenu(id) {
    const chat = M.chat(id);
    if (!chat) return;
    showSheet(chat.title, `<div class="stack">
      ${button(chat.saved ? "Убрать из сохранённых" : "Сохранить чат", "bookmark", "save-from-menu", id, "primary")}
      ${button("Переименовать", "note", "rename-chat", id)}
      ${button("Удалить чат", "trash", "delete-chat", id, "danger")}</div>`);
  }

  function deleteChats(predicate, message) {
    if (pending && predicate(M.chat(pending.chatId))) {
      pending.controller.abort();
      cancelAnimationFrame(pending.frame);
      pending = null;
    }
    const before = S.chats;
    S.chats = S.chats.filter(chat => !predicate(chat));
    if (!saved(message)) S.chats = before;
    for (const item of before) {
      if (!M.chat(item.id)) { drafts.delete(item.id); errors.delete(item.id); }
    }
    render();
  }

  if (device) device.onmessage = event => {
    const id = voiceChat;
    voiceChat = "";
    let data;
    try { data = JSON.parse(event.data); } catch { return; }
    if (data.error) { flash(data.error); return; }
    if (!id || !data.text || !M.chat(id)) return;
    const existing = drafts.get(id) || "";
    drafts.set(id, (existing + (existing ? " " : "") + data.text).slice(0, 6000));
    if (chatId === id && route === "chat") { render(); focusComposer(); }
  };

  const actions = {
    go: value => navigate(value),
    back,
    close: closeSheet,
    confirm: () => { const run = onConfirm; closeSheet(); run?.(); },
    "new-chat": openNew,
    "open-chat": openExisting,
    "save-chat": () => saveChat(),
    "save-from-menu": id => { closeSheet(); saveChat(M.chat(id)); },
    "chat-menu": chatMenu,
    "rename-chat": id => {
      const chat = M.chat(id);
      if (!chat) return;
      editor("Название чата", field("title", "Название", chat.title, { max: 72 }), ({ title }) => {
        chat.title = title;
        closeSheet();
        persistChat(chat);
        render();
      });
    },
    "delete-chat": id => confirm("Удалить чат?", "Переписка будет удалена с этого устройства.",
      () => deleteChats(item => item?.id === id, "Чат удалён")),
    "clear-chats": () => confirm("Очистить сохранённые чаты?", "Эти беседы перестанут быть доступны в истории и памяти.",
      () => deleteChats(item => item?.saved, "Сохранённые чаты удалены")),
    "chat-search": () => {
      filters.searchOpen = !filters.searchOpen;
      if (!filters.searchOpen) filters.search = "";
      render();
      screen.querySelector("#chat-search")?.focus();
    },
    "chat-filter": value => { filters.onlySaved = value === "saved"; render(); },
    suggest: value => { drafts.set(chatId, value); render(); focusComposer(); },
    stop,
    retry: () => {
      const chat = activeChat();
      const last = chat?.messages.at(-1);
      if (last?.role === "user") answer(chat, last.text, true);
    },
    dictate: () => {
      if (!device || voiceChat || !activeChat()) return;
      voiceChat = chatId;
      try { device.postMessage("dictate"); }
      catch { voiceChat = ""; flash("Голосовой ввод временно недоступен."); }
    },
    key: () => keyEditor(),
    "test-key": testKey,
    "remove-key": () => confirm("Удалить ключ?", "Чтобы снова получать ответы, добавьте ключ OpenRouter.",
      async () => {
        try {
          Object.assign(connection, await OpenRouter.removeKey(), { checked: false });
          render();
          flash("Ключ удалён");
        } catch (error) { flash(error.message); }
      }),
    "select-profile": id => {
      if (!M.allProfiles().some(item => item.id === id)) return;
      S.activeId = id;
      saved("Выбран " + M.profile().name);
      render();
    },
    "profile-edit": profileEditor,
    "profile-delete": profileDelete,
    "memory-edit": memoryEditor,
    "memory-delete": id => confirm("Удалить факт?", "Запись будет удалена из памяти ассистента.", () => {
      const memory = M.memory();
      memory.entries = memory.entries.filter(item => item.id !== id);
      saved("Факт удалён");
      render();
    }),
    "note-edit": noteEditor,
    "note-delete": id => confirm("Удалить заметку?", "Запись будет удалена с устройства.", () => {
      S.notes = S.notes.filter(item => item.id !== id);
      saved("Заметка удалена");
      render();
    }),
    rule: value => { filters.rule = value; render(); },
    accent: value => {
      const hue = Number(value);
      if (!M.colors.some(color => color.hue === hue)) return;
      S.preferences.hue = hue;
      appearance();
      saved();
      render();
    },
    toggle: key => {
      if (key === "memory") M.memory().enabled = !M.memory().enabled;
      else if (typeof M.defaults[key] === "boolean") S.preferences[key] = !S.preferences[key];
      saved();
      appearance();
      render();
    },
    replay: showSplash
  };

  app.addEventListener("click", event => {
    const control = event.target.closest("[data-action]");
    if (control && Object.hasOwn(actions, control.dataset.action)) actions[control.dataset.action](control.dataset.value || "");
  });

  app.addEventListener("submit", async event => {
    event.preventDefault();
    const form = event.target;
    const data = Object.fromEntries(Array.from(new FormData(form), ([name, value]) => [name, value.trim()]));
    if (form.id === "chat-form") {
      answer(activeChat(), data.message);
    } else if (form.id === "rule-form") {
      const rules = S.instructions[S.activeId] ??= {};
      rules[filters.rule] = data.rule;
      ruleDrafts.delete(ruleKey());
      saved("Инструкция сохранена");
    } else if (form.id === "editor" && onSave) {
      const error = form.querySelector("#editor-error");
      const button = form.querySelector("button[type=submit]");
      error.hidden = true;
      if (Array.from(form.querySelectorAll("[required]")).some(field => !field.value.trim())) {
        error.textContent = "Заполните обязательные поля.";
        error.hidden = false;
        return;
      }
      button.disabled = true;
      try { await onSave(data); }
      catch (failure) { error.textContent = failure.message; error.hidden = false; }
      finally { button.disabled = false; }
    }
  });

  screen.addEventListener("input", event => {
    const input = event.target;
    if (input.id === "chat-input") {
      drafts.set(chatId, input.value);
      sizeComposer();
    }
    if (input.id === "chat-search") {
      filters.search = input.value;
      screen.querySelector("#chat-items").innerHTML = U.chatItems(filters);
    }
    if (input.name === "rule") ruleDrafts.set(ruleKey(), input.value);
  });
  screen.addEventListener("keydown", event => {
    if (event.target.id === "chat-input" && event.key === "Enter" && !event.shiftKey && !event.isComposing && !isAndroid) {
      event.preventDefault();
      if (!pending) screen.querySelector("#chat-form")?.requestSubmit();
    }
  });

  prefersReduced.addEventListener("change", () => { appearance(); render(); });
  setInterval(() => {
    if (route !== "home" || reduce() || !splash.hidden) return;
    placeholder = (placeholder + 1) % U.placeholders.length;
    const label = screen.querySelector("#prompt-line");
    if (!label) return;
    label.classList.remove("roll");
    label.textContent = U.placeholders[placeholder];
    requestAnimationFrame(() => { label.classList.add("roll"); });
  }, 4300);

  appearance();
  readRoute();
  showSplash();
  refreshConnection();
})();
