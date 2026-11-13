"use strict";

(() => {
  const M = Cortex, U = CortexUI, Api = CortexApi, S = M.state, Wave = CortexWave;
  const Markdown = CortexMarkdown, Phone = CortexPhone, Motion = CortexMotion;
  const { esc, icon, button, iconButton } = U;
  const app = document.querySelector("#app");
  const screen = document.querySelector("#screen");
  const ambient = document.querySelector("#ambient");
  const composition = document.querySelector("#composition");
  const nav = document.querySelector("#navigation");
  const sheet = document.querySelector("#sheet");
  const drawer = document.querySelector("#history");
  const splash = document.querySelector("#splash");
  const toast = document.querySelector("#toast");
  const jumpLatest = document.querySelector("#jump-latest");
  const device = window.CortexDevice;
  const systemMotion = matchMedia("(prefers-reduced-motion: reduce)");
  const routes = new Set([...U.tabs.map(([name]) => name), ...Object.keys(U.parent)]);
  const drafts = new Map(), errors = new Map(), ruleDrafts = new Map(), phoneReplies = new Map(), actionReplies = new Map();
  const filters = { search: "", onlySaved: false };
  const connection = { google: false, tools: false, profiles: false, repair: false, checked: false };
  let wakeState = "", islandCamera = "drop", deviceFacts = {};
  const permissions = { microphone: false, notifications: false, overlay: false, screenLock: false, screenLockSupported: false, brightness: false, verifyApps: false, screenAutomation: false, assistant: false, assistantSupported: false };
  let route = "home", chatId = "", rule = "goal";
  let pending, login, voice, readoutId, activeSheet, ghost, pageMotion, composerMotion, headingMotion, clearMorph, screenContext, drawerExit, sheetExit, inlineToast, toastTimer;
  let connectionLoad;
  let keyboardBaseline = 0, keyboardObserved = false, historySearchTimer = 0;
  let statusEpoch = 0, splashFrame = 0, splashTimer = 0, splashPrevious = null;

  document.body.classList.toggle("web-preview", !Api.android);
  composition.innerHTML = U.composer();
  const form = composition.firstElementChild;
  const input = form.querySelector("#composer-input");
  const send = form.querySelector(".send-button");
  const microphone = form.querySelector(".mic-button");
  const voiceButton = form.querySelector(".voice-button");
  const stop = form.querySelector(".stop-button");
  const voicePanel = form.querySelector("#voice-inline");
  const voiceControl = voicePanel.querySelector(".voice-control");
  const voiceText = voicePanel.querySelector("[data-voice-text]");
  const screenPanel = form.querySelector("#screen-context");
  const attachScreen = screenPanel.querySelector(".attach-screen");
  nav.innerHTML = U.navigation();
  const reduced = () => S.preferences.reduceMotion || systemMotion.matches;
  const current = () => M.chat(chatId);
  const draftKey = () => route === "chat" ? chatId : "home";
  const waveMode = () => pending && pending.chatId === chatId ? "thinking" :
    document.activeElement === input ? "focus" : "idle";
  const language = () => S.preferences.language === "en-US" ? "en-US" : "ru-RU";

  function appearance() {
    document.documentElement.dataset.quality = S.preferences.quality;
    document.documentElement.classList.toggle("reduce-motion", reduced());
  }

  function sendDevice(operation, data = {}) {
    const control = window.CortexIslandControl;
    const extended = new Set(["overlay-permission", "microphone-permission",
      "overlay-config", "wake-config", "island-state", "island-options"]);
    if (operation === "permissions" && control) {
      try { control.postMessage(JSON.stringify({ operation })); } catch {}
    }
    // Never fall back to the legacy device bridge for native island operations.
    // It cannot handle them, and postMessage() succeeding is not action success.
    if (extended.has(operation) && !control) return false;
    const receiver = extended.has(operation) ? control : device;
    if (!receiver) return false;
    try { receiver.postMessage(JSON.stringify({ operation, ...data })); return true; }
    catch { return false; }
  }

  function syncIslandOptions() {
    sendDevice("island-options", { profile: S.preferences.model, phoneActions: S.preferences.phoneActions,
      reduceMotion: reduced(), haptics: S.preferences.haptics });
  }

  function haptic() {
    if (S.preferences.haptics) sendDevice("tap");
  }

  function flash(message) {
    clearTimeout(toastTimer);
    inlineToast?.remove();
    const dialog = sheet.open ? sheet : drawer.open ? drawer : null;
    if (dialog) {
      toast.hidden = true;
      inlineToast = document.createElement("p");
      inlineToast.className = "inline-toast";
      inlineToast.setAttribute("role", "status");
      inlineToast.textContent = message;
      dialog.firstElementChild.after(inlineToast);
      toastTimer = setTimeout(() => inlineToast?.remove(), 3200);
      return;
    }
    toast.textContent = message;
    toast.hidden = false;
    toastTimer = setTimeout(() => { toast.hidden = true; }, 3200);
  }

  function saved(message) {
    if (M.save()) {
      if (message) flash(message);
      return true;
    }
    flash("На устройстве закончилось место. Изменения доступны в этой сессии.");
    return false;
  }

  function rootTab(page) {
    while (U.parent[page]) page = U.parent[page];
    return page;
  }

  function renderNav() {
    const tab = rootTab(route);
    nav.style.setProperty("--nav-index", U.tabs.findIndex(([name]) => name === tab));
    nav.dataset.center = String(tab === "home");
    for (const control of nav.querySelectorAll("[data-tab]")) {
      if (control.dataset.tab === tab) control.setAttribute("aria-current", "page");
      else control.removeAttribute("aria-current");
    }
  }

  function mountWave() {
    if (app.classList.contains("in-onboarding")) { Wave.stop(); return; }
    const main = route === "home" || route === "chat" && app.dataset.chatContent === "empty";
    const host = !splash.hidden ? splash.querySelector("[data-splash-host]") :
      main ? ambient : screen.querySelector("[data-wave-host]");
    if (!host) { Wave.stop(); return; }
    const kind = host === ambient ? route : host.dataset.waveHost || "splash";
    Wave.mount(host, {
      kind, quality: S.preferences.quality, still: reduced(), mode: waveMode(),
      populated: route === "chat" && Boolean(current()?.messages.length)
    });
  }

  // A contextual shortcut that appears only if the reader intentionally scrolled away
  // from the latest message. No permanent extra controls in the chat header.
  function updateJumpLatest() {
    const scroll = route === "chat" ? screen.querySelector(".conversation") : null;
    const distance = scroll ? scroll.scrollHeight - scroll.clientHeight - scroll.scrollTop : 0;
    jumpLatest.hidden = !scroll || distance < 170 || app.classList.contains("in-onboarding");
  }

  jumpLatest.addEventListener("click", () => {
    const scroll = screen.querySelector(".conversation");
    if (!scroll) return;
    const remaining = scroll.scrollHeight - scroll.clientHeight - scroll.scrollTop;
    // A very long smooth scroll can take seconds and feel broken: jump instantly.
    scroll.scrollTo({ top: scroll.scrollHeight, behavior: reduced() || remaining > 1600 ? "instant" : "smooth" });
    jumpLatest.hidden = true;
    haptic();
  });
  // Capture-phase listener survives replacing screen.innerHTML on each render.
  screen.addEventListener("scroll", event => {
    if (event.target.classList?.contains("conversation")) updateJumpLatest();
  }, true);

  function render({ bottom = false, messageEnter = false } = {}) {
    const before = screen.querySelector(".scroll");
    const previousTop = before?.scrollTop || 0;
    const previousRoute = screen.dataset.route;
    const previousChat = screen.dataset.chat;
    const same = previousRoute === route && (route !== "chat" || previousChat === chatId);
    app.dataset.route = route;
    app.dataset.chatContent = route === "chat" && (current()?.messages.length || errors.has(chatId)) ? "filled" : "empty";
    screen.dataset.route = route;
    screen.dataset.chat = route === "chat" ? chatId : "";
    screen.className = "screen " + route + "-screen";
    screen.innerHTML = U.page(route, {
      connection, permissions, wakeState, islandCamera, native: Boolean(device), login, chat: current(), messageEnter,
      pending: route === "chat" && pending?.chatId === chatId ? pending : null,
      error: route === "chat" ? errors.get(chatId) : null,
      rule, ruleDraft: ruleDrafts.get(rule)
    });
    renderNav();
    composition.hidden = route !== "home" && route !== "chat";
    if (!composition.hidden) {
      const draft = drafts.get(draftKey()) || "";
      if (input.value !== draft) input.value = draft;
      input.placeholder = route === "home" ? "Напишите Кортексу…" : "Сообщение";
    }
    syncComposer();
    const scroll = screen.querySelector(".scroll");
    if (scroll && (bottom || same)) scroll.scrollTop = bottom ? scroll.scrollHeight : previousTop;
    updateJumpLatest();
    mountWave();
  }

  function localRect(rect) {
    const root = app.getBoundingClientRect();
    return { x: rect.left - root.left, y: rect.top - root.top, w: rect.width, h: rect.height };
  }

  function clearVisuals() {
    pageMotion?.cancel();
    composerMotion?.cancel();
    headingMotion?.cancel();
    clearMorph?.();
    clearMorph = null;
    ghost?.remove();
    ghost = null;
  }

  function fadeOldPage(duration, leavingHome = false) {
    const r = localRect(screen.getBoundingClientRect());
    const old = screen.cloneNode(true);
    old.removeAttribute("id");
    old.removeAttribute("data-route");
    old.querySelectorAll("[id]").forEach(node => node.removeAttribute("id"));
    if (leavingHome) for (const node of old.querySelectorAll(".home-header, .hero-copy")) node.style.visibility = "hidden";
    old.classList.add("page-ghost");
    old.inert = true;
    old.setAttribute("aria-hidden", "true");
    old.style.cssText = "left:" + r.x + "px;top:" + r.y + "px;width:" + r.w + "px;height:" + r.h + "px";
    app.append(old);
    const prevScroll = screen.querySelector(".scroll");
    if (prevScroll) old.querySelector(".scroll").scrollTop = prevScroll.scrollTop;
    ghost = old;
    const remove = () => { old.remove(); if (ghost === old) ghost = null; };
    old.animate([{ opacity: 1, transform: "none" }, { opacity: 0, transform: "translate3d(0,-4px,0)" }],
      { duration: Wave.duration(duration), easing: "cubic-bezier(.27,.68,.22,1)", fill: "forwards" }).finished.then(remove, remove);
  }

  const box = node => node ? localRect(node.getBoundingClientRect()) : null;
  const dockCore = () => nav.querySelector(".dock-emblem");
  const permissionsPage = () => ["phone-tools", "permissions", "notifications"].includes(route);

  function playCore(from) {
    if (reduced()) return;
    const mark = screen.querySelector("[data-chat-logo] .brand-mark");
    clearMorph?.();
    clearMorph = Motion.core(app, from, box(mark));
    if (mark && clearMorph) mark.animate(
      [{ opacity: .16, transform: "scale(.84)" }, { opacity: .35, offset: .55 },
        { opacity: 1, transform: "scale(1)" }],
      { duration: 875, easing: "cubic-bezier(.26,.71,.24,1)" }
    );
  }

  function moveComposer(from, to, cinematic) {
    if (!from || !to || !to.w || !to.h) return;
    const x = from.x - to.x, y = from.y - to.y;
    if (Math.abs(x) + Math.abs(y) < 1 && Math.abs(from.h - to.h) < 1) return;
    composerMotion = form.animate([
      { transform: `translate(${x}px,${y}px) scale(${from.w / to.w},${from.h / to.h})` },
      ...(cinematic ? [{ transform: `translate(${x * .23}px,${y * .23}px) scale(1.014,.92)`, offset: .55 }] : []),
      { transform: "none" }
    ], { duration: cinematic ? 520 : 275, easing: "cubic-bezier(.21,.8,.18,1)" });
  }

  function navigate(next, { id, replace = false, origin, newChat = false, messageEnter = false, cinematic = false } = {}) {
    if (!routes.has(next)) return;
    const previous = route;
    if (next === "chat") id = id && M.chat(id) ? id : current()?.id || M.createChat().id;
    if (previous === next && (next !== "chat" || id === chatId)) return;
    if (pending?.action && !pending.routing && !pending.actionSent || pending?.waiting) pending.interrupted = true;
    if (login && !sheet.open) stopLogin();
    closeVoice();
    closeSheet();
    closeHistory(true);
    clearVisuals();
    const before = !composition.hidden ? box(form) : null;
    const source = box(cinematic ? dockCore() : origin);
    const tabDirection = U.tabs.findIndex(([key]) => key === rootTab(next)) >=
      U.tabs.findIndex(([key]) => key === rootTab(previous)) ? 1 : -1;
    const title = box(screen.querySelector(".hero-copy h1"));
    if (next !== "chat" && document.activeElement === input) input.blur();
    if (next !== "chat") app.classList.remove("composer-active");
    const animated = !reduced() && typeof screen.animate === "function";
    const intoChat = next === "chat" && previous === "home";
    const intoHome = next === "home";
    const switchesTab = rootTab(previous) !== rootTab(next);
    if (animated && (switchesTab || intoChat || intoHome || newChat) && (route !== "chat" || (current()?.messages.length || 0) < 40)) {
      fadeOldPage(switchesTab ? 225 : 190, intoChat);
    }
    route = next;
    if (next === "chat") chatId = id;
    const hash = "#" + next + (next === "chat" ? "/" + encodeURIComponent(chatId) : "");
    history[replace ? "replaceState" : "pushState"](null, "", hash);
    render({ bottom: next === "chat", messageEnter });
    if (animated) {
      const after = !composition.hidden ? box(form) : null;
      if (intoChat || intoHome) moveComposer(before, after, intoChat);
      const heading = screen.querySelector(".chat-empty h1");
      const to = box(heading);
      if (intoChat && title && to) {
        const x = title.x + title.w / 2 - to.x - to.w / 2, y = title.y - to.y;
        headingMotion = heading.animate([
          { transform: `translate(${x}px,${y}px) scale(${title.w / to.w})` }, { transform: "none" }
        ], { duration: 460, easing: "cubic-bezier(.22,.74,.21,1)" });
      }
      pageMotion = screen.animate([{ opacity: .34, transform: "translate3d(0,5px,0)" }, { opacity: 1, transform: "none" }],
        { duration: intoChat || intoHome ? 380 : Wave.duration(295), easing: "cubic-bezier(.23,.68,.24,1)" });
      if (cinematic) playCore(source);
      else if (switchesTab) clearMorph = Motion.wave(app, source, tabDirection);
      if (newChat && origin) {
        const point = box(origin);
        Wave.pulse(point.x + point.w / 2, point.y + point.h / 2);
      }
    }
    if (permissionsPage()) sendDevice("permissions");
  }

  function readRoute() {
    const previous = route;
    if (pending?.action && !pending.routing && !pending.actionSent || pending?.waiting) pending.interrupted = true;
    closeVoice();
    clearVisuals();
    const [name, id] = location.hash.slice(1).split("/");
    const resolved = { appearance: "motion", connection: "settings" }[name] || name;
    route = routes.has(resolved) ? resolved : "home";
    if (route !== "chat" && document.activeElement === input) input.blur();
    if (route !== "chat") app.classList.remove("composer-active");
    if (route === "chat") {
      let decoded;
      try { decoded = decodeURIComponent(id || ""); } catch {}
      chatId = M.chat(decoded)?.id || current()?.id || M.createChat().id;
    }
    render({ bottom: route === "chat" });
    if (rootTab(previous) !== rootTab(route) && !reduced() && splash.hidden) clearMorph = Motion.wave(app);
    if (permissionsPage()) sendDevice("permissions");
  }

  function back() {
    if (window.CortexControl?.back()) return true;
    if (app.classList.contains("in-onboarding")) {
      window.CortexSetup?.back();
      return true;
    }
    if (sheet.open) { dismissSheet(); return true; }
    if (drawer.open) { closeHistory(); return true; }
    if (voice) { closeVoice(); return true; }
    if (route === "home") return false;
    navigate(U.parent[route] || "home", { replace: true });
    return true;
  }
  window.cortexBack = back;
  addEventListener("popstate", () => { closeSheet(); closeHistory(true); readRoute(); });

  function syncComposer() {
    if (composition.hidden) return;
    const filled = Boolean(input.value.trim());
    const working = route === "chat" && pending?.chatId === chatId;
    const scroll = screen.querySelector(".conversation");
    const stick = pinned(scroll);
    if (!CSS.supports("field-sizing", "content")) {
      input.style.height = "43px";
      input.style.height = Math.max(43, Math.min(126, input.scrollHeight + 1)) + "px";
    }
    form.classList.toggle("has-text", filled);
    form.classList.toggle("working", working);
    send.disabled = !filled || working;
    send.hidden = !filled || working;
    microphone.hidden = !device || working || Boolean(voice);
    voiceButton.hidden = !device || filled || working || Boolean(voice);
    stop.hidden = !working || Boolean(pending?.actionSent) || Boolean(voice);
    app.style.setProperty("--composer-h", form.offsetHeight + "px");
    if (scroll && stick) scroll.scrollTop = scroll.scrollHeight;
    updateJumpLatest();
  }

  function historyItems() {
    if (drawer.open) drawer.querySelector("#history-items").innerHTML = U.chatItems(filters);
  }

  function placeDialogs() {
    const r = app.getBoundingClientRect();
    const styles = {
      "--dialog-x": r.left + "px", "--dialog-y": r.top + "px", "--dialog-w": r.width + "px",
      "--dialog-h": r.height + "px", "--dialog-right": Math.max(0, innerWidth - r.right) + "px",
      "--dialog-bottom": Math.max(0, innerHeight - r.bottom) + "px"
    };
    for (const dialog of [sheet, drawer]) {
      for (const [key, value] of Object.entries(styles)) dialog.style.setProperty(key, value);
    }
  }
  addEventListener("resize", placeDialogs);

  function openHistory() {
    if (pending?.action && !pending.routing && !pending.actionSent || pending?.waiting) pending.interrupted = true;
    closeVoice();
    if (drawerExit) {
      drawerExit.cancel();
      drawerExit = null;
    }
    if (drawer.open) return;
    placeDialogs();
    drawer.innerHTML = U.history(filters);
    drawer.showModal();
  }
  function closeHistory(immediate = false) {
    if (!drawer.open) return;
    if (immediate || reduced() || !drawer.animate) {
      drawerExit?.cancel();
      drawerExit = null;
      drawer.close();
      return;
    }
    if (drawerExit) return;
    const exit = drawer.animate([{ opacity: 1, transform: "none" }, { opacity: 0, transform: "translateX(30px)" }],
      { duration: Wave.duration(160), easing: "ease-in", fill: "forwards" });
    drawerExit = exit;
    exit.finished.then(() => {
      if (drawerExit === exit) {
        drawer.close();
        drawerExit = null;
      }
      exit.cancel();
    }, () => {});
  }
  drawer.addEventListener("cancel", event => { event.preventDefault(); closeHistory(); });

  function showSheet(title, body, options = {}) {
    if (pending?.action && !pending.routing && !pending.actionSent || pending?.waiting) pending.interrupted = true;
    closeVoice();
    closeSheet();
    placeDialogs();
    activeSheet = options;
    sheet.innerHTML = `<header class="sheet-head"><h2 id="sheet-title">${esc(title)}</h2>${iconButton("close", "Закрыть", "close")}</header>${body}`;
    sheet.showModal();
  }
  function closeSheet() {
    if (!sheet.open) return;
    sheetExit?.cancel();
    sheetExit = null;
    const cleanup = activeSheet?.cleanup;
    activeSheet = null;
    sheet.close();
    sheet.replaceChildren();
    cleanup?.();
  }

  function dismissSheet() {
    if (!sheet.open || sheetExit) return;
    if (reduced() || !sheet.animate) { closeSheet(); return; }
    const exit = sheet.animate([
      { opacity: 1, transform: "translateX(-50%)" },
      { opacity: 0, transform: "translate(-50%, 19px)" }
    ], { duration: Wave.duration(160), easing: "ease-in", fill: "forwards" });
    sheetExit = exit;
    exit.finished.then(() => { if (sheetExit === exit) closeSheet(); }, () => {});
  }
  sheet.addEventListener("cancel", event => { event.preventDefault(); dismissSheet(); });

  for (const dialog of [drawer, sheet]) dialog.addEventListener("click", event => {
    if (event.target !== dialog) return;
    const bounds = dialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) {
      if (dialog === sheet) dismissSheet(); else closeHistory();
    }
  });

  function field(name, title, value = "", { max = 90, rows = 0, optional = false, type = "text" } = {}) {
    const required = optional ? "" : " required";
    return `<label class="field"><span>${esc(title)}</span>${rows ?
      `<textarea name="${name}" rows="${rows}" maxlength="${max}"${required}>${esc(value)}</textarea>` :
      `<input type="${type}" name="${name}" value="${esc(value)}" maxlength="${max}"${required}
        ${type === "url" ? 'inputmode="url" autocapitalize="none" spellcheck="false"' : ""}>`}</label>`;
  }

  function editor(title, fields, submit, { label = "Сохранить", extra = "" } = {}) {
    showSheet(title, `<form id="editor" class="stack">${fields}<p class="form-error" role="alert" hidden></p>
      <div class="actions"><button type="submit" class="action primary">${icon("check", 17)}${esc(label)}</button>${extra}</div></form>`, { submit });
  }
  function confirm(title, description, run) {
    showSheet(title, `<div class="stack"><p class="muted">${esc(description)}</p><div class="actions">
      ${button("Отмена", "", "close")}${button("Подтвердить", "check", "confirm", "", "danger")}</div></div>`, { confirm: run });
  }

  function showSplash() {
    if (!splash.hidden && splash.classList.contains("playing") && !splash.classList.contains("leaving")) return;
    cancelAnimationFrame(splashFrame);
    clearTimeout(splashTimer);
    closeVoice();
    closeSheet();
    closeHistory(true);
    splash.hidden = false;
    splash.className = "splash";
    splashPrevious = null;
    screen.inert = nav.inert = composition.inert = true;
    // The CSS splash is self-contained; WebGL startup here causes first-frame stutter.
    Wave.stop();
    let visible = 0;
    function play(now) {
      if (document.hidden || window.cortexAppVisible === false) splashPrevious = null;
      else {
        if (splashPrevious === null) splash.classList.add("playing");
        else visible += now - splashPrevious;
        splashPrevious = now;
      }
      if (visible < (reduced() ? 650 : 1650)) {
        splashFrame = requestAnimationFrame(play);
        return;
      }
      // Place onboarding underneath the splash BEFORE fading it out.
      // Otherwise the home screen flashes for one frame.
      window.CortexSetup?.autoStart();
      splash.classList.add("leaving");
      screen.inert = nav.inert = composition.inert = app.classList.contains("in-onboarding");
      splashTimer = setTimeout(() => {
        splash.hidden = true;
        if (!app.classList.contains("in-onboarding")) mountWave();
      }, reduced() ? 0 : 395);
    }
    splashFrame = requestAnimationFrame(() => {
      splashFrame = requestAnimationFrame(play);
    });
  }

  function setAccount(value) {
    const previousId = S.account?.id;
    S.account = M.account(value);
    if (previousId !== S.account?.id) dispatchEvent(new Event("cortex-account-changed"));
    saved();
  }

  async function refreshConnection() {
    if (!Api.available) return;
    const epoch = ++statusEpoch;
    try {
      const data = await Api.request("status");
      if (epoch !== statusEpoch || data.fault) return;
      connection.checked = true;
      connection.google = Boolean(data.google);
      connection.tools = data.device_tools === true;
      connection.profiles = data.device_profiles === true;
      connection.repair = data.device_repair === true;
      if (!login) setAccount(data.account);
      if (["profile", "models"].includes(route)) render();
    } catch {}
  }

  function updateLogin(message) {
    const text = sheet.querySelector("[data-login-status]");
    if (text) text.textContent = message;
  }

  function stopLogin() {
    if (!login) return;
    clearTimeout(login.timer);
    login = null;
    Api.request("auth-cancel").catch(() => {});
    if (route === "profile") render();
  }

  async function pollLogin() {
    const attempt = login;
    if (!attempt || attempt.busy || document.hidden) return;
    if (Date.now() >= attempt.expires) {
      stopLogin();
      closeSheet();
      flash(Api.failure("auth_expired").message);
      return;
    }
    clearTimeout(attempt.timer);
    attempt.busy = true;
    try {
      const result = await Api.request("auth-poll");
      if (login !== attempt) return;
      if (result.state === "signed_in" && M.account(result.account)) {
        login = null;
        ++statusEpoch;
        setAccount(result.account);
        window.CortexControl?.refresh();
        closeSheet();
        render();
        refreshConnection();
        flash("Вход выполнен");
        return;
      }
      updateLogin("После подтверждения в браузере вернитесь в CORTEX.");
    } catch (error) {
      if (login !== attempt) return;
      if (["auth_expired", "auth_denied", "account_forbidden"].includes(error.code)) {
        stopLogin();
        closeSheet();
        flash(error.message);
        return;
      }
      updateLogin(error.message);
    } finally {
      attempt.busy = false;
      if (login === attempt) attempt.timer = setTimeout(pollLogin, 2600);
    }
  }

  async function startLogin() {
    if (login) return;
    if (!Api.available || !device) { flash(Api.failure(Api.android ? "webview" : "preview").message); return; }
    if (!connection.google) { flash(Api.failure("account_config").message); return; }
    const attempt = { busy: true, expires: Infinity, timer: 0 };
    login = attempt;
    render();
    try {
      const result = await Api.request("auth-start");
      if (login !== attempt) return;
      attempt.expires = Date.now() + 1000 * (result.expires_in || 300);
      attempt.busy = false;
      showSheet("Вход через Google", `<div class="stack">
        <p class="muted" data-login-status>После подтверждения в браузере вернитесь в CORTEX.</p>
        <div class="actions">${button("Отмена", "", "close")}</div></div>`, { cleanup: stopLogin });
      if (!sendDevice("open-login", { url: result.url })) updateLogin("Не удалось открыть браузер.");
      attempt.timer = setTimeout(pollLogin, 2700);
    } catch (error) {
      if (login === attempt) {
        login = null;
        if (route === "profile") render();
        flash(error.message);
      }
    }
  }

  window.cortexAdminLogin = startLogin;

  function logout() {
    confirm("Выйти из аккаунта?", "Локальные чаты и заметки останутся на этом устройстве.", async () => {
      if (pending) stopAnswer(false);
      ++statusEpoch;
      try {
        await Api.request("logout");
        setAccount(null);
        window.CortexControl?.refresh();
        render();
        flash("Вы вышли из аккаунта");
      } catch (error) { flash(error.message); }
    });
  }

  function pinned(scroll) {
    return !scroll || scroll.scrollHeight - scroll.clientHeight - scroll.scrollTop < 82;
  }

  function drawDelta(job) {
    if (job !== pending || !job.text) return;
    const node = route === "chat" && chatId === job.chatId ? screen.querySelector("[data-stream]") : null;
    if (!node) return;
    const scroll = screen.querySelector(".conversation");
    const stick = pinned(scroll);
    node.classList.add("live");
    if (!appVisible()) return; // Don't render expensive Markdown while backgrounded.
    node.innerHTML = Markdown.render(job.text);
    if (stick) scroll.scrollTop = scroll.scrollHeight;
    updateJumpLatest();
  }

  function speak(text, id) {
    if (!id) id = readoutId = M.id("speech");
    const locale = S.preferences.language === "auto" && !/[а-яё]/iu.test(text) ? "en-US" : language();
    return sendDevice("speak", {
      id, language: locale, speed: Number(S.preferences.speechSpeed),
      text: Markdown.spoken(text, locale === "en-US" ? "The answer is available in the chat." : "Ответ доступен в чате.")
    });
  }

  function phoneAction(command) {
    const id = M.id("command");
    return new Promise(resolve => {
      const done = result => {
        clearTimeout(timer);
        phoneReplies.delete(id);
        resolve(result);
      };
      const timer = setTimeout(() => done({ status: "unavailable" }), 6500);
      phoneReplies.set(id, done);
      if (!sendDevice("phone-action", { id, command })) done({ status: "unavailable" });
    });
  }

  function nativeAction(job, text) {
    if (!device || !S.preferences.phoneActions || job.explain || job.screen) return Promise.resolve(null);
    const id = M.id("action");
    job.nativeRunning = true;
    return new Promise(resolve => {
      const done = value => {
        clearTimeout(timer);
        actionReplies.delete(id);
        job.controller.signal.removeEventListener("abort", cancel);
        job.nativeRunning = false;
        resolve(value);
      };
      const cancel = () => {
        sendDevice("action-stop");
        done(null);
      };
      const timer = setTimeout(() => {
        sendDevice("action-stop");
        done({ text: "Время задачи истекло. Проверьте последний шаг на экране.", external: !appVisible() });
      }, 124000);
      actionReplies.set(id, value => {
        if (value.phase === "progress") {
          if (pending !== job) return;
          job.action = value.text;
          if (voice && job.voice === voice) voiceStatus("opening", value.text);
          if (activeChat(job) && appVisible()) render({ bottom: true });
        } else if (value.phase === "result") {
          done({ text: value.text, external: !appVisible() });
        } else done(null);
      });
      job.controller.signal.addEventListener("abort", cancel, { once: true });
      if (!sendDevice("action-start", { id, text, profile: S.preferences.model })) done(null);
    });
  }

  const activeChat = job => route === "chat" && chatId === job.chatId;
  const canAct = job => activeChat(job) && appVisible() && !sheet.open && !drawer.open;
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

  async function resumePlan(job) {
    if (canAct(job)) return true;
    if (!activeChat(job) || job.interrupted) return false;
    job.waiting = true;
    render({ bottom: true });
    const until = Date.now() + 45_000;
    while (pending === job && !job.interrupted && activeChat(job) && Date.now() < until && !canAct(job)) await wait(200);
    job.waiting = false;
    return pending === job && !job.interrupted && canAct(job) && Date.now() < until;
  }

  async function runPhone(job, command, step) {
    if (!S.preferences.phoneActions) return { status: "disabled" };
    if (!canAct(job) || job.interrupted) return { status: "foreground" };
    job.action = (step > 1 ? "Шаг " + step + " · " : "") + Phone.label(command);
    if (S.preferences.overlayMode !== "off") sendDevice("island-state", {
      phase: "executing", message: job.action
    });
    job.text = "";
    if (document.activeElement === input) input.blur();
    if (voice && job.voice === voice) voiceStatus("opening", "Выполняю…");
    render({ bottom: true });
    await wait(740);
    if (pending !== job || job.interrupted || !canAct(job)) return { status: "foreground" };
    if (!S.preferences.phoneActions) return { status: "disabled" };
    job.actionSent = true;
    syncComposer();
    try {
      const result = await phoneAction(command);
      if (S.preferences.overlayMode !== "off") sendDevice("island-state", {
        phase: ["opened", "done", "panel"].includes(result.status) ? "done" : "error",
        message: Phone.label(command)
      });
      job.expectExternal = ["opened", "panel"].includes(result.status) || command.kind === "lock_screen" && result.status === "done";
      return result;
    } finally { job.actionSent = false; }
  }

  const clock = new Intl.DateTimeFormat("ru-RU", { dateStyle: "full", timeStyle: "short" });
  async function askModel(chat, job, explain, observations, repair = false) {
    if (device && !connection.checked && connectionLoad) await Promise.race([connectionLoad, wait(2_000)]);
    const enabled = Boolean(device && connection.tools && S.preferences.phoneActions && !job.screen);
    const policy = Phone.prompt({ native: Boolean(device), enabled, explain, permissions, observations, deviceFacts, version: U.version,
      now: clock.format(new Date()) + " (" + Intl.DateTimeFormat().resolvedOptions().timeZone + ")" });
    const screenPolicy = job.screen ? "\n\nПользователь приложил текст экрана как данные для разбора: " + JSON.stringify(job.screen) : "";
    const repairPolicy = repair ? "\n\nПредыдущий ответ описал действие словами без вызова. Проверь запрос пользователя. Вызови функцию для нужного действия или answer_without_action, если нужно пояснение или уточнение." : "";
    const messages = M.turns(chat, policy + screenPolicy + repairPolicy);
    const response = await Api.request("chat", {
      payload: { messages, style: M.profile().style,
        ...(enabled && !explain && { device_tools: true, ...(repair && connection.repair && { repair_tools: true }) }),
        ...(connection.profiles && { profile: S.preferences.model }) },
      notify: S.preferences.notifyReplies
    }, {
      signal: job.controller.signal,
      onStatus() {
        if (pending !== job) return;
        job.routing = true;
        job.action = "Пробую резервную модель…";
        if (S.preferences.overlayMode !== "off") sendDevice("island-state", { phase: "executing", message: job.action });
        if (activeChat(job)) render({ bottom: true });
      },
      onDelta(delta) {
        if (pending !== job) return;
        if (job.routing) {
          job.routing = false;
          job.action = "";
        }
        job.raw = (job.raw || "") + delta;
        if (enabled && !explain && (job.raw.length < 25 || Phone.claimsAction(job.raw))) return;
        job.text = job.raw;
        const cadence = job.raw.length > 12000 ? 260 : job.raw.length > 3500 ? 155 : 78;
        if (!job.renderTimer) job.renderTimer = setTimeout(() => { job.renderTimer = 0; drawDelta(job); }, cadence);
      }
    });
    job.routing = false;
    job.action = "";
    if (!enabled || explain) return { text: typeof response.text === "string" ? response.text : "", commands: [] };
    return Phone.decode(response);
  }

  async function respond(chat, job, explain) {
    const native = await nativeAction(job, chat.messages.at(-1)?.text || "");
    if (native || pending !== job) return native || {};
    const reports = [], observations = [], seen = new Set();
    let external = false, lastResult;
    for (let round = 0; round < 3; round++) {
      if (pending !== job) return {};
      job.action = job.text = "";
      if (activeChat(job)) render({ bottom: true });
      job.raw = "";
      let choice = await askModel(chat, job, explain, observations);
      if (pending !== job) return {};
      if (!explain && !job.screen && !choice.error && !choice.commands.length && Phone.claimsAction(choice.text)) {
        if (connection.tools && canAct(job)) {
          job.raw = job.text = "";
          choice = await askModel(chat, job, explain, observations, true);
        }
        if (!choice.error && !choice.commands.length && Phone.claimsAction(choice.text)) {
          choice = { error: "tool_arguments" };
        }
      }
      if (pending !== job) return {};
      if (choice.error) {
        reports.push(choice.error === "multiple_tools" ? "Уточните, с каких действий начать." : "Уточните параметры нужного действия.");
        break;
      }
      if (!choice.commands.length) {
        return { text: choice.text.trim() || reports.join("\n\n"), external };
      }
      let continueFromResult = false;
      for (const command of choice.commands) {
        const { next, ...action } = command;
        const signature = JSON.stringify(Object.entries(action).sort(([a], [b]) => a.localeCompare(b)));
        if (seen.has(signature) || seen.size >= 6) continue;
        if (external) await wait(270);
        if (external && !await resumePlan(job)) {
          reports.push("План остановлен. Продолжить его можно новым запросом в CORTEX.");
          return { text: reports.join("\n\n"), external };
        }
        job.expectExternal = false;
        seen.add(signature);
        lastResult = await runPhone(job, action, seen.size);
        if (pending !== job) return {};
        reports.push(Phone.result(action, lastResult));
        observations.push({ action, result: lastResult });
        external = ["opened", "panel"].includes(lastResult.status) || action.kind === "lock_screen" && lastResult.status === "done";
        if (!["done", "opened", "panel"].includes(lastResult.status)) return { text: reports.join("\n\n"), external };
        if (next === "decide") { continueFromResult = true; break; }
      }
      if (!continueFromResult || external || seen.size >= 6) break;
    }
    return { text: reports.join("\n\n"), external };
  }

  async function answer(chat, text, { retry = false, from, voiceTurn, explain = false, includeScreen = true } = {}) {
    if (!chat || !text || pending) return;
    if (voiceTurn && voice === voiceTurn) voiceStatus("thinking", "Думаю…");
    if (!retry) {
      if (!chat.messages.length) chat.title = text.replace(/\s+/g, " ").slice(0, 72);
      chat.messages.push({ role: "user", text });
      drafts.delete(chat.id);
    }
    const attachment = includeScreen && screenContext?.attached ? { app: screenContext.app, text: screenContext.text } : null;
    if (attachment) { screenContext = null; refreshScreen(); }
    errors.delete(chat.id);
    M.touch(chat);
    if (chat.saved) saved();
    const job = { chatId: chat.id, text: "", completed: false, renderTimer: 0, controller: new AbortController(), voice: voiceTurn, explain, screen: attachment };
    pending = job;
    if (from) navigate("chat", { id: chat.id, origin: from, messageEnter: true });
    else if (activeChat(job)) render({ bottom: true, messageEnter: !retry });
    historyItems();
    try {
      const outcome = await respond(chat, job, explain);
      if (pending !== job) return;
      if (!outcome.text?.trim()) throw Api.failure("empty");
      chat.messages.push({ role: "assistant", text: outcome.text });
      job.completed = true;
      M.touch(chat);
      if (chat.saved) saved();
      if (voiceTurn && voice === voiceTurn) {
        if (outcome.external) closeVoice();
        else speakInConversation(voiceTurn, outcome.text);
      } else if (!voiceTurn && !outcome.external && S.preferences.readAloud && appVisible() && activeChat(job)) speak(outcome.text);
    } catch (error) {
      if (pending !== job) return;
      errors.set(chat.id, Object.assign(error, { explain }));
      if (error.code === "sign_in" && S.account) setAccount(null);
      if (voiceTurn && voice === voiceTurn) voiceStatus("error", error.message);
    } finally {
      if (pending === job) {
        pending = null;
        clearTimeout(job.renderTimer);
        if (activeChat(job)) render({ bottom: pinned(screen.querySelector(".conversation")), messageEnter: job.completed });
        else if (route === "home") Wave.state(waveMode());
        historyItems();
      }
    }
  }

  function stopAnswer(keep = true) {
    if (!pending || pending.actionSent && keep) return;
    const job = pending;
    pending = null;
    job.controller.abort();
    clearTimeout(job.renderTimer);
    const chat = M.chat(job.chatId);
    if (!chat || !keep) return;
    if (job.text.trim()) {
      chat.messages.push({ role: "assistant", text: job.text.trim() });
      M.touch(chat);
      if (chat.saved) saved();
    } else errors.set(chat.id, Object.assign(new Error("Действие остановлено."), { explain: job.explain }));
    render();
    historyItems();
  }

  function newChat(control) {
    const item = M.createChat();
    navigate("chat", { id: item.id, origin: control, newChat: true });
    haptic();
  }

  function homeChat() {
    const chat = M.createChat();
    if (drafts.get("home")) drafts.set(chat.id, drafts.get("home"));
    drafts.delete("home");
    return chat;
  }

  function submitMessage(text, control) {
    if (!text) return;
    if (pending) { flash("Сначала дождитесь текущего ответа."); return; }
    const home = route === "home";
    const chat = home ? homeChat() : current();
    if (!chat) return;
    closeVoice();
    haptic();
    void answer(chat, text, { from: home ? control : null });
  }

  function submitExample(text, control, explain = false) {
    if (pending) { flash("Сначала дождитесь текущего ответа."); return; }
    const here = route === "chat" && !current()?.messages.length;
    const chat = here ? current() : M.createChat();
    if (!chat) return;
    haptic();
    void answer(chat, text, { from: here ? null : control, explain, includeScreen: false });
  }

  function chatMenu(id) {
    const chat = M.chat(id);
    if (!chat) return;
    showSheet(chat.title, `<div class="stack">
      ${button(chat.pinned ? "Открепить чат" : "Закрепить чат", "pin", "pin-chat", id, "primary")}
      ${button(chat.saved ? "Убрать из сохранённых" : "Сохранить чат", "bookmark", "save-chat", id)}
      ${button("Переименовать", "note", "rename-chat", id)}
      ${button("Удалить чат", "trash", "delete-chat", id, "danger")}</div>`);
  }

  function updateChat(id, pin) {
    const chat = M.chat(id);
    if (!chat) return;
    const before = { saved: chat.saved, pinned: chat.pinned };
    if (pin) {
      chat.pinned = !chat.pinned;
      if (chat.pinned) chat.saved = true;
    } else {
      chat.saved = !chat.saved;
      if (!chat.saved) chat.pinned = false;
    }
    const message = pin ? (chat.pinned ? "Чат закреплён" : "Чат откреплён") :
      (chat.saved ? "Чат сохранён" : "Чат убран из сохранённых");
    closeSheet();
    if (!saved(message)) Object.assign(chat, before);
    historyItems();
  }

  function deleteChats(test, message) {
    const before = S.chats;
    S.chats = before.filter(chat => !test(chat));
    if (!saved(message)) { S.chats = before; return; }
    if (pending && !M.chat(pending.chatId)) stopAnswer(false);
    for (const chat of before) if (!M.chat(chat.id)) { drafts.delete(chat.id); errors.delete(chat.id); }
    if (!current()) {
      chatId = route === "chat" ? M.createChat().id : "";
      if (route === "chat") history.replaceState(null, "", "#chat/" + encodeURIComponent(chatId));
    }
    render();
    historyItems();
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
        closeSheet(); saved("Факт сохранён"); render();
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
        closeSheet(); saved("Заметка сохранена"); render();
      }, { extra: note ? button("Удалить", "trash", "note-delete", note.id, "danger") : "" });
  }

  function exportChats() {
    const chats = S.chats.filter(chat => chat.saved && chat.messages.length);
    if (!chats.length) { flash("Сохранённых чатов пока нет."); return; }
    const text = JSON.stringify({ cortex: U.version, exported: new Date().toISOString(), chats }, null, 2);
    if (device) {
      if (text.length > 1_850_000) { flash("История слишком велика для одного файла."); return; }
      if (!sendDevice("export", { text })) flash("Не удалось открыть сохранение файла.");
      return;
    }
    const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "cortex-chats.json";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 2500);
  }

  function support(kind) {
    const titles = { contact: "Написать разработчику", bug: "Сообщить об ошибке", idea: "Предложить функцию", complaint: "Отправить жалобу" };
    if (!Object.hasOwn(titles, kind)) return;
    const draft = { screenshot: "", id: crypto.randomUUID() };
    editor(titles[kind], field("text", "Сообщение", "", { rows: 4, max: 4000 }) +
      (S.account ? "" : field("email", "Email для ответа (необязательно)", "", { type: "email", max: 254, optional: true })) +
      `<label class="check-row"><input name="diagnostics" type="checkbox"><span>Приложить версию приложения</span></label>
      <div class="attachment">
        ${button("Приложить скриншот", "image", "choose-image")}<span data-attachment class="muted"></span>
        <button type="button" class="action quiet" data-action="remove-image" hidden>Убрать</button>
      </div>`, async ({ text, email, diagnostics }) => {
        const result = await Api.request("support", { payload: {
          id: draft.id, kind, text, email: email || "", diagnostics: Boolean(diagnostics),
          app_version: diagnostics ? U.version : "", screenshot: draft.screenshot
        } });
        if (!result.sent) throw Api.failure("support_delivery");
        if (activeSheet?.support !== draft) { flash("Обращение отправлено"); return; }
        showSheet("Обращение отправлено", `<div class="support-success"><span class="success-check">${icon("check", 26)}</span>
          ${result.reference ? `<p>Номер обращения ${esc(result.reference)}</p>` : ""}${button("Готово", "", "close", "", "primary")}</div>`);
      }, { label: "Отправить" });
    activeSheet.support = draft;
  }

  function attachment(data) {
    if (!activeSheet?.support) return;
    activeSheet.support.screenshot = data;
    sheet.querySelector("[data-attachment]").textContent = data ? "Скриншот прикреплён" : "";
    sheet.querySelector('[data-action="remove-image"]').hidden = !data;
  }

  function appVisible() {
    return !document.hidden && window.cortexAppVisible !== false;
  }

  function refreshScreen() {
    screenPanel.hidden = !screenContext;
    screenPanel.querySelector("[data-screen-title]").textContent = screenContext ?
      (screenContext.attached ? "Приложен текст · " : "Текст экрана · ") + (screenContext.app || "Android") : "";
    screenPanel.querySelector("[data-screen-preview]").textContent = screenContext?.text || "";
    attachScreen.setAttribute("aria-pressed", String(Boolean(screenContext?.attached)));
    syncComposer();
  }

  function acceptScreen(context) {
    if (typeof context?.text !== "string" || !context.text.trim()) {
      flash("Android не передал текст этого экрана.");
      return;
    }
    screenContext = { text: context.text.slice(0, 2600), app: String(context.app || "").slice(0, 90), attached: false };
    if (route !== "chat" && route !== "home") navigate("home");
    screenPanel.querySelector("details").open = false;
    refreshScreen();
  }

  function transcript(text) {
    voiceText.textContent = text || "";
    voiceText.hidden = !voiceText.textContent;
  }

  function voiceStatus(phase, text) {
    if (!voice) return;
    voice.phase = phase;
    voicePanel.dataset.voicePhase = phase;
    voicePanel.querySelector("[data-voice-status]").textContent = text;
    const labels = { starting: "Включаю микрофон", listening: "Завершить запись", hearing: "Завершить запись",
      recognizing: "Распознаю речь", thinking: "Перебить", opening: "Выполняю действие", preparing: "Перебить",
      speaking: "Перебить", waiting: "Говорить", error: "Повторить" };
    voiceControl.innerHTML = icon(["waiting", "error", "starting"].includes(phase) ? "mic" : "stop", 17);
    voiceControl.setAttribute("aria-label", labels[phase]);
    voiceControl.title = labels[phase];
    voiceControl.disabled = ["starting", "recognizing", "opening"].includes(phase);
    if (!["listening", "hearing"].includes(phase)) voicePanel.style.setProperty("--voice-level", "0");
    syncComposer();
  }

  function closeVoice() {
    if (!voice) return;
    const job = voice;
    voice = null;
    clearTimeout(job.timer);
    if (job.recognition) sendDevice("voice-cancel", { id: job.recognition });
    if (job.speech) sendDevice("silence", { id: job.speech });
    voicePanel.hidden = true;
    delete voicePanel.dataset.voicePhase;
    transcript("");
    syncComposer();
  }

  function listen(job) {
    if (voice !== job) return;
    if (!appVisible()) { voiceStatus("waiting", "Разговор приостановлен"); return; }
    clearTimeout(job.timer);
    job.recognition = M.id("voice");
    transcript("");
    voiceStatus("starting", "Включаю микрофон…");
    if (!sendDevice("voice-start", { id: job.recognition, language: language(), dictation: job.dictation })) {
      job.recognition = null;
      voiceStatus("error", "Не удалось запустить микрофон.");
    }
  }

  function startVoice(dictation, control) {
    if (!device || pending) return;
    closeVoice();
    const selection = [input.selectionStart, input.selectionEnd];
    input.blur();
    if (route === "home") navigate("chat", { id: homeChat().id, origin: control });
    voice = { dictation, key: draftKey(), chatId, selection, phase: "starting", timer: 0 };
    voicePanel.hidden = false;
    haptic();
    listen(voice);
  }

  function recognizing(job) {
    if (job.phase === "recognizing") return;
    voiceStatus("recognizing", "Распознаю речь…");
    clearTimeout(job.timer);
    job.timer = setTimeout(() => {
      if (voice !== job || !job.recognition) return;
      sendDevice("voice-cancel", { id: job.recognition });
      job.recognition = null;
      voiceStatus("error", "Распознавание заняло слишком много времени.");
    }, 12000);
  }

  function useVoice() {
    const job = voice;
    if (!job) return;
    if (["listening", "hearing"].includes(job.phase)) {
      recognizing(job);
      sendDevice("voice-finish", { id: job.recognition });
    } else if (["thinking", "preparing", "speaking"].includes(job.phase)) {
      if (pending?.voice === job) stopAnswer();
      if (job.speech) sendDevice("silence", { id: job.speech });
      job.speech = null;
      clearTimeout(job.timer);
      voiceStatus("waiting", "Готовлю микрофон…");
      job.timer = setTimeout(() => listen(job), 300);
    } else if (job.phase === "waiting" || job.phase === "error") listen(job);
  }

  function speakInConversation(job, text) {
    if (!appVisible()) { voiceStatus("waiting", "Ответ готов в чате"); return; }
    job.speech = M.id("speech");
    voiceStatus("preparing", "Готовлю озвучивание…");
    if (!speak(text, job.speech)) { voiceStatus("error", "Озвучивание недоступно."); return; }
    clearTimeout(job.timer);
    job.timer = setTimeout(() => {
      if (voice !== job || job.phase !== "preparing") return;
      sendDevice("silence", { id: job.speech });
      job.speech = null;
      voiceStatus("error", "Голос Android не начал озвучивание.");
    }, 12000);
  }

  function finishDictation(job, text) {
    const previous = drafts.get(job.key) || "";
    const [start, end] = job.selection;
    const left = previous.slice(0, start), right = previous.slice(end);
    const prefix = left && !/\s$/u.test(left) ? " " : "";
    const suffix = right && !/^[\s.,!?;:)]/u.test(right) ? " " : "";
    const value = (left + prefix + text + suffix + right).slice(0, 6000);
    const caret = Math.min(value.length, left.length + prefix.length + text.length);
    drafts.set(job.key, value);
    closeVoice();
    input.value = value;
    input.setSelectionRange(caret, caret);
    syncComposer();
  }

  function recognitionEvent(job, message) {
    switch (message.phase) {
      case "ready": voiceStatus("listening", "Слушаю…"); break;
      case "hearing": voiceStatus("hearing", "Слышу вас"); break;
      case "level":
        if (job.phase === "listening" || job.phase === "hearing") {
          voicePanel.style.setProperty("--voice-level", String(Math.max(0, Math.min(1, Number(message.level) || 0))));
        }
        break;
      case "partial": transcript(message.text); syncComposer(); break;
      case "finishing": recognizing(job); break;
      case "error":
        clearTimeout(job.timer);
        job.recognition = null;
        voiceStatus("error", message.text || "Не удалось распознать речь.");
        break;
      case "result": {
        clearTimeout(job.timer);
        job.recognition = null;
        const text = typeof message.text === "string" ? message.text.trim().slice(0, 6000) : "";
        if (!text) { voiceStatus("error", "Речь не распознана. Попробуйте ещё раз."); return; }
        if (job.dictation) { finishDictation(job, text); return; }
        if (/^(?:(?:кортекс|cortex)[ ,]+)?(?:стоп|закончи разговор|закрой голосовой режим|stop)[.!?]*$/iu.test(text)) {
          closeVoice();
          return;
        }
        const chat = M.chat(job.chatId);
        if (!chat || pending) { voiceStatus("error", "Подождите завершения текущего действия."); return; }
        transcript(text);
        void answer(chat, text, { voiceTurn: job });
        break;
      }
    }
  }

  function conversationSpeech(job, phase) {
    clearTimeout(job.timer);
    if (phase === "started") { voiceStatus("speaking", "Говорю"); return; }
    job.speech = null;
    if (phase === "done") {
      if (S.preferences.voiceContinue && appVisible()) {
        voiceStatus("waiting", "Включаю микрофон…");
        job.timer = setTimeout(() => listen(job), 420);
      } else voiceStatus("waiting", "Нажмите микрофон, чтобы продолжить");
    } else if (phase === "stopped") voiceStatus("waiting", "Озвучивание остановлено");
    else voiceStatus("error", "Голос Android недоступен. Ответ есть в чате.");
  }

  function deviceEvent(event) {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === "voice" && voice?.recognition && message.id === voice.recognition) {
      recognitionEvent(voice, message);
    }
    if (message.type === "speech") {
      if (voice?.speech && message.id === voice.speech) conversationSpeech(voice, message.phase);
      else if (readoutId && message.id === readoutId && message.phase !== "started") {
        readoutId = null;
        if (message.phase === "error") flash("Голос недоступен в настройках Android.");
      }
    }
    if (message.type === "action") actionReplies.get(message.id)?.(message);
    if (message.type === "action-history") {
      const entries = Array.isArray(message.entries) ? message.entries : [];
      showSheet("Журнал действий", entries.length ? '<ol class="action-log">' +
        entries.map(entry => '<li><time>' + esc(new Date(entry.at).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" })) +
        '</time><span>' + esc(entry.message) + '</span></li>').join("") + '</ol>' : '<p class="muted">Действий пока нет.</p>');
    }
    if (message.type === "phone") phoneReplies.get(message.id)?.(message.result || { status: "unavailable" });
    if (message.type === "background") closeVoice();
    if (message.type === "screen-context") acceptScreen(message.context);
    if (message.type === "permissions") {
      if (message.capabilities) deviceFacts = message.capabilities;
      if (["drop", "punch", "left", "right", "none"].includes(message.islandCamera)) islandCamera = message.islandCamera;
      for (const key of Object.keys(permissions)) if (Object.hasOwn(message,key)) permissions[key] = Boolean(message[key]);
      window.CortexSetup?.updatePermissions(permissions);
      if (permissionsPage()) render();
    }
    if (message.type === "wake-state") {
      wakeState = message.message || "";
      if (route === "phone-tools") render();
    }
    if (message.type === "wake-config" && !message.ok) {
      S.preferences.hotwordEnabled = false; saved(); render();
      flash(message.message || "Голосовая активация пока недоступна на этом устройстве.");
    }
    if (message.type === "overlay-mode" && !message.ok) {
      flash(message.message || "Разрешите отображение поверх приложений.");
    }
    if (message.type === "browser" && !message.ok) updateLogin("Не удалось открыть браузер Android.");
    if (message.type === "copy") flash(message.ok ? "Скопировано" : "Не удалось скопировать");
    if (message.type === "link" && !message.ok) flash("Не удалось открыть ссылку.");
    if (message.type === "settings" && !message.ok) flash("Не удалось открыть настройки Android.");
    if (message.type === "image" && !message.cancelled) {
      if (message.ok && typeof message.data === "string") attachment(message.data);
      else flash("Не удалось подготовить скриншот.");
    }
    if (message.type === "export" && !message.cancelled) flash(message.ok ? "Чаты экспортированы" : "Не удалось сохранить файл");
  }
  if (device) device.onmessage = deviceEvent;
  if (window.CortexIslandControl) window.CortexIslandControl.onmessage = deviceEvent;

  async function copyText(text) {
    if (!text) return;
    if (sendDevice("copy", { text })) return;
    try { await navigator.clipboard.writeText(text); flash("Скопировано"); }
    catch { flash("Браузер ограничил доступ к буферу обмена."); }
  }

  function preference(key, value) {
    S.preferences[key] = value;
    saved();
    appearance();
    if (["model", "phoneActions", "reduceMotion", "haptics"].includes(key)) syncIslandOptions();
    render();
  }

  const actions = {
    tab(value, control) {
      // The central CORTEX control always opens the conversation, from any tab.
      if (value === "home") {
        haptic();
        if (route !== "chat") navigate("chat", { id: homeChat().id, origin: control, cinematic: true });
        else if (document.activeElement !== input) {
          playCore(box(dockCore()));
          const effect = clearMorph;
          setTimeout(() => {
            if (route === "chat" && clearMorph === effect && !voice && !sheet.open && !drawer.open) input.focus();
          }, effect ? 780 : 0);
        }
        return;
      }
      if (route === value) return;
      haptic(); navigate(value, { origin: control });
    },
    go: (value, control) => navigate(value, { origin: control }),
    back, close: dismissSheet,
    confirm() {
      const run = activeSheet?.confirm;
      closeSheet();
      Promise.resolve().then(() => run?.()).catch(error => flash(error.message));
    },
    "new-chat": (_, control) => newChat(control),
    history: openHistory,
    "close-history": closeHistory,
    "open-chat": id => { closeHistory(true); navigate("chat", { id }); },
    "history-filter"(value) {
      filters.onlySaved = value === "saved";
      for (const button of drawer.querySelectorAll('[data-action="history-filter"]')) {
        button.setAttribute("aria-pressed", String(button.dataset.value === value));
      }
      historyItems();
    },
    "chat-menu": chatMenu,
    "pin-chat": id => updateChat(id, true),
    "save-chat": id => updateChat(id, false),
    "rename-chat"(id) {
      const chat = M.chat(id);
      if (!chat) return;
      editor("Название чата", field("title", "Название", chat.title, { max: 72 }), ({ title }) => {
        chat.title = title; saved(); closeSheet(); historyItems();
      });
    },
    "delete-chat": id => confirm("Удалить чат?", "Переписка будет удалена с устройства.", () => deleteChats(chat => chat.id === id, "Чат удалён")),
    "clear-chats": () => confirm("Очистить сохранённые чаты?", "Эти беседы исчезнут из истории и памяти.", () => deleteChats(chat => chat.saved, "Сохранённые чаты удалены")),
    "clear-local": () => confirm("Сбросить локальные данные?", "Чаты, заметки, память и настройки будут удалены с устройства.", () => {
      if (!M.clearLocalData()) { flash("Не удалось удалить локальные данные."); return; }
      if (pending) stopAnswer(false);
      sendDevice("silence");
      drafts.clear(); errors.clear(); ruleDrafts.clear();
      chatId = ""; rule = "goal";
      appearance(); render(); flash("Локальные данные сброшены");
    }),
    stop: () => stopAnswer(),
    retry() {
      const chat = current(), last = chat?.messages.at(-1);
      if (last?.role === "user") void answer(chat, last.text, { retry: true, explain: errors.get(chat.id)?.explain });
    },
    copy(index) { return copyText(current()?.messages[Number(index)]?.text); },
    "copy-code"(_, control) { return copyText(control.closest(".code-block")?.querySelector("code")?.textContent); },
    speak(index) {
      const text = current()?.messages[Number(index)]?.text;
      if (text) speak(text);
    },
    dictate: (_, control) => startVoice(true, control),
    "voice-chat": (_, control) => startVoice(false, control),
    "voice-turn": useVoice,
    "voice-close": closeVoice,
    "attach-screen"() {
      if (!screenContext) return;
      screenContext.attached = !screenContext.attached;
      refreshScreen();
    },
    "drop-screen"() { screenContext = null; refreshScreen(); },
    capabilities: (_, control) => submitExample("Что ты умеешь?", control, true),
    "try-tool": (text, control) => submitExample(text, control),
    async "quick-tool"(target, control) {
      if (!device) { flash("Быстрый доступ работает в приложении Android."); return; }
      const command = target === "torch" ? { kind: "torch", mode: "toggle" } : { kind: "panel", target };
      if (!Phone.validate(command)) return;
      control.disabled = true;
      haptic();
      try {
        const result = await phoneAction(command);
        if (!["opened", "panel"].includes(result.status)) flash(Phone.result(command, result));
      } finally { control.disabled = false; }
    },
    "assistant-permission": () => sendDevice("assistant-permission"),
    "verification-permission": () => window.CortexSetup?.openPermission("screenAutomation"),
    "lock-permission": () => sendDevice("lock-permission"),
    "brightness-permission": () => sendDevice("brightness-permission"),
    "microphone-permission": () => {
      if (!window.CortexIslandControl) {
        if (sendDevice("open-settings")) flash("Выберите «Разрешения» → «Микрофон» в настройках CORTEX.");
        else flash("Откройте разрешения приложения CORTEX в Android.");
      } else sendDevice("microphone-permission");
    },
    "notification-permission": () => sendDevice("notification-permission"),
    "overlay-permission": () => {
      if (!window.CortexIslandControl) flash("Островок доступен только в Android-приложении CORTEX.");
      else if (!sendDevice("overlay-permission")) flash("Не удалось открыть разрешение Android.");
    },
    "open-setup": () => window.CortexSetup?.start(true),
    "surface-mode"(mode) {
      if (!["always", "wake", "off"].includes(mode)) return;
      if (mode === "off" && S.preferences.hotwordEnabled) preference("hotwordEnabled", false);
      preference("overlayMode", mode);
      if (!window.CortexIslandControl) {
        flash(mode === "off" ? "Плавающее окно выключено." : "Выбор сохранён. Островок работает только в Android-приложении.");
      } else if (!sendDevice("overlay-config", { mode, hideLandscape: S.preferences.hideLandscape }))
        flash("Не удалось применить режим. Проверьте разрешения Android.");
    },
    "model-profile"(value) { if (Object.hasOwn(M.models, value)) preference("model", value); },
    login: startLogin,
    logout,
    export: exportChats,
    "open-settings": () => { if (!sendDevice("open-settings")) flash("Этот экран доступен в Android."); },
    "support-form": support,
    "action-history": () => { if (!sendDevice("action-history")) flash("Журнал доступен в приложении Android."); },
    "choose-image": () => { if (!sendDevice("choose-image")) flash("Выбор скриншота доступен в Android."); },
    "remove-image": () => attachment(""),
    "memory-edit": memoryEditor,
    "memory-delete": id => confirm("Удалить факт?", "CORTEX перестанет использовать эту запись.", () => {
      const memory = M.memory();
      memory.entries = memory.entries.filter(item => item.id !== id);
      saved("Факт удалён"); render();
    }),
    "note-edit": noteEditor,
    "note-delete": id => confirm("Удалить заметку?", "Запись будет удалена с устройства.", () => {
      S.notes = S.notes.filter(item => item.id !== id);
      saved("Заметка удалена"); render();
    }),
    "personality-edit"() {
      const person = M.profile();
      editor("Манера общения", field("tone", "Тон", person.tone) +
        field("description", "Роль", person.description, { max: 380, rows: 3 }), values => {
        Object.assign(S.edits.cortex, values); saved("Манера общения сохранена"); closeSheet(); render();
      });
    },
    "reply-style"(value) {
      if (!Object.hasOwn(M.styles, value)) return;
      S.edits.cortex.style = value;
      saved(); render();
    },
    rule(value) {
      if (!M.instructionFields.some(item => item.id === value)) return;
      rule = value; render();
    },
    quality(value) { if (Object.hasOwn(M.motion, value)) preference("quality", value); },
    language(value) { if (Object.hasOwn(M.languages, value)) preference("language", value); },
    "speech-speed"(value) { if (["0.85", "1", "1.15"].includes(value)) preference("speechSpeed", value); },
    toggle(key) {
      if (key === "hotwordEnabled" && !window.CortexIslandControl) {
        flash("Активация по слову «Кортекс» недоступна: обновите приложение.");
        return;
      }
      if (key === "memory") { M.memory().enabled = !M.memory().enabled; saved(); render(); return; }
      if (!Object.hasOwn(M.defaults, key) || typeof M.defaults[key] !== "boolean") return;
      preference(key, !S.preferences[key]);
      if (key === "notifyReplies") {
        if (S.preferences[key]) sendDevice("notification-permission");
        sendDevice("support-notify-config", { enabled: S.preferences[key] });
      }
      if (key === "phoneActions" && !S.preferences[key]) sendDevice("action-stop");
      if (key === "readAloud" && !S.preferences[key]) sendDevice("silence");
      if (key === "hideLandscape") sendDevice("overlay-config", {mode:S.preferences.overlayMode,hideLandscape:S.preferences.hideLandscape});
      if (key === "hotwordEnabled") {
        if (S.preferences.hotwordEnabled && S.preferences.overlayMode === "off" && permissions.overlay) {
          preference("overlayMode", "wake");
          sendDevice("overlay-config", {mode:"wake", hideLandscape:S.preferences.hideLandscape});
        }
        if (!sendDevice("wake-config", {enabled:S.preferences.hotwordEnabled})) {
          preference("hotwordEnabled", false);
          flash("Не удалось запустить голосовую службу Android.");
        }
      }
    }
  };

  app.addEventListener("click", event => {
    const link = event.target.closest(".markdown a[href]");
    if (link && device) {
      event.preventDefault();
      const url = Markdown.webUrl(link.getAttribute("href"));
      if (!url || !sendDevice("open-link", { url })) flash("Не удалось открыть ссылку.");
      return;
    }
    const control = event.target.closest("[data-action]");
    if (control && Object.hasOwn(actions, control.dataset.action)) actions[control.dataset.action](control.dataset.value || "", control);
  });

  app.addEventListener("submit", async event => {
    event.preventDefault();
    const form = event.target;
    const data = Object.fromEntries(Array.from(new FormData(form), ([key, value]) => [key, String(value).trim()]));
    if (form.id === "composer") submitMessage(data.message, form.querySelector(".send-button"));
    if (form.id === "rule-form") {
      const rules = S.instructions.cortex ||= {};
      rules[rule] = data.rule; ruleDrafts.delete(rule); saved("Инструкция сохранена");
    }
    if (form.id === "editor" && activeSheet?.submit) {
      const error = form.querySelector(".form-error");
      const submit = form.querySelector('[type="submit"]');
      if (submit.disabled) return;
      error.hidden = true;
      if (Array.from(form.querySelectorAll("[required]")).some(input => !input.value.trim())) {
        error.textContent = "Заполните обязательные поля."; error.hidden = false; return;
      }
      submit.disabled = true;
      try { await activeSheet.submit(data); }
      catch (failure) { error.textContent = failure.message; error.hidden = false; }
      finally { submit.disabled = false; }
    }
  });

  app.addEventListener("input", event => {
    const input = event.target;
    if (input.id === "composer-input") { drafts.set(draftKey(), input.value); syncComposer(); }
    if (input.id === "history-search") {
      filters.search = input.value;
      clearTimeout(historySearchTimer);
      historySearchTimer = setTimeout(historyItems, 160);
    }
    if (input.name === "rule") ruleDrafts.set(rule, input.value);
  });
  function visibleViewportHeight() {
    return Math.round(Math.min(innerHeight, window.visualViewport?.height || innerHeight));
  }
  function updateKeyboardLayout() {
    const height = visibleViewportHeight();
    if (!keyboardBaseline) keyboardBaseline = height;
    if (document.activeElement !== input) {
      // Learn the natural viewport height only while the keyboard is closed.
      if (!keyboardObserved) keyboardBaseline = Math.max(keyboardBaseline, height);
      app.classList.remove("composer-active");
    } else {
      const keyboardOpen = keyboardBaseline - height > 115;
      if (keyboardOpen) keyboardObserved = true;
      // On Android, after observing a real IME resize, restore the dock when
      // the keyboard closes even if the textarea retains focus.
      app.classList.toggle("composer-active", !Api.android || !keyboardObserved || keyboardOpen);
    }
    updateJumpLatest();
  }
  form.addEventListener("focusin", event => {
    if (event.target !== input) return;
    closeVoice();
    if (route === "home") { navigate("chat", { id: homeChat().id, origin: form }); haptic(); }
    updateKeyboardLayout();
    Wave.state(waveMode());
  });
  form.addEventListener("focusout", () => requestAnimationFrame(() => {
    updateKeyboardLayout();
    Wave.state(waveMode());
  }));
  window.visualViewport?.addEventListener("resize", updateKeyboardLayout);
  window.addEventListener("resize", updateKeyboardLayout);
  window.addEventListener("orientationchange", () => {
    keyboardObserved = false;
    keyboardBaseline = 0;
    requestAnimationFrame(updateKeyboardLayout);
  });
  form.addEventListener("keydown", event => {
    // Hardware keyboard: Enter sends and Shift+Enter inserts a line break.
    // On touch phones, Enter should insert a line break; the arrow sends.
    if (event.target === input && event.key === "Enter" && !event.shiftKey &&
        !event.isComposing && event.keyCode !== 229 && !matchMedia("(pointer: coarse)").matches) {
      event.preventDefault();
      if (!pending && event.target.value.trim()) event.target.form.requestSubmit();
    }
  });
  systemMotion.addEventListener("change", () => { clearVisuals(); appearance(); syncIslandOptions(); mountWave(); });
  addEventListener("cortex-onboarding-end", () => { mountWave(); updateJumpLatest(); });
  function visibilityChanged() {
    splashPrevious = null;
    const hidden = document.hidden || window.cortexAppVisible === false;
    document.documentElement.classList.toggle("app-background", hidden);
    if (hidden && pending?.action && !pending.nativeRunning && !pending.routing && !pending.actionSent && !pending.expectExternal && !pending.waiting) pending.interrupted = true;
    if (!hidden) { pollLogin(); sendDevice("screen-context"); }
  }
  document.addEventListener("visibilitychange", visibilityChanged);
  addEventListener("cortex-app-visibility", visibilityChanged);

  appearance();
  keyboardBaseline = visibleViewportHeight();
  readRoute();
  screen.inert = nav.inert = composition.inert = true;
  if (Api.android && device && !window.cortexFrameReady) {
    const begin = () => {
      clearTimeout(fallback);
      removeEventListener("cortex-frame-ready", begin);
      showSplash();
    };
    const fallback = setTimeout(begin, 1400);
    addEventListener("cortex-frame-ready", begin, { once: true });
  } else showSplash();
  sendDevice("permissions");
  syncIslandOptions();
  sendDevice("support-notify-config", { enabled: S.preferences.notifyReplies });
  if (S.preferences.hotwordEnabled && S.preferences.overlayMode !== "off") sendDevice("wake-config", { enabled: true });
  sendDevice("screen-context");
  connectionLoad = refreshConnection();
})();
