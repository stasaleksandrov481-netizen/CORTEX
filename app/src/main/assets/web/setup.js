"use strict";

window.CortexSetup = (() => {
  const key = "cortex:onboarding:v4";
  const root = document.getElementById("app");
  const prefs = () => Cortex.state.preferences;
  const reduced = () => prefs().reduceMotion || matchMedia("(prefers-reduced-motion: reduce)").matches;
  const { icon, esc } = CortexUI;
  const mark = '<img src="art/mark.svg" alt="" class="intro-mark">';
  let view, page = 0, single = false, permissions = {}, notice = "", demo = 0, demoTimer = 0;
  const accesses = [
    { id: "microphone", name: "Микрофон", icon: "mic", action: "microphone-permission",
      description: "Продиктуйте вопрос или задачу. Доступ используется для записи речи и голосового вызова, если вы его включите." },
    { id: "overlay", name: "Островок", icon: "focus", action: "overlay-permission",
      description: "Островок показывает шаги CORTEX над приложениями. Нажмите на него, чтобы открыть мини-чат.",
      steps: ["Откройте системные настройки.", "Найдите CORTEX в списке приложений.", "Включите «Поверх других приложений»."] },
    { id: "screenAutomation", name: "Управление экраном", icon: "check", action: "verification-permission",
      description: "CORTEX читает подписи элементов выбранного приложения, нажимает кнопки, вводит текст и проверяет изменения. Перед передачей подписей и сообщений об ошибках в OpenRouter CORTEX отдельно спросит разрешение для задачи.",
      steps: ["Откройте «Специальные возможности».", "Выберите «Установленные приложения» или «Скачанные службы».", "Включите «CORTEX — управление экраном» и вернитесь сюда."],
      note: "Если доступ ограничен, откройте карточку CORTEX в настройках Android, затем меню ⋮ → «Разрешить ограниченные настройки». Пункт появляется на поддерживаемых устройствах.",
      settings: true },
    { id: "notifications", name: "Уведомления", icon: "bell", action: "notification-permission",
      description: "Уведомления показывают шаг задачи и позволяют её остановить. Также можно получать уведомления о новых ответах поддержки." },
    { id: "brightness", name: "Яркость", icon: "sliders", action: "brightness-permission",
      description: "CORTEX сможет менять системную яркость по вашей команде.",
      steps: ["Откройте системные настройки.", "Разрешите CORTEX изменять системные настройки."] },
    { id: "screenLock", name: "Блокировка экрана", icon: "shield", action: "lock-permission",
      description: "Команда «заблокируй экран» работает через службу управления экраном на совместимых версиях Android. Дополнительный доступ нужен, если эта функция недоступна.",
      steps: ["Откройте системный экран подтверждения.", "Разрешите CORTEX блокировать экран."] }
  ];
  const pages = ["welcome", "demo", ...accesses.map(x => x.id), "finish"];

  function native(operation, data = {}) {
    const island = window.CortexIslandControl;
    const target = ["overlay-permission", "overlay-config"].includes(operation) ? island : window.CortexDevice;
    if (!target) return false;
    try { target.postMessage(JSON.stringify({ operation, ...data })); return true; }
    catch { return false; }
  }

  function prologue(id) {
    if (id === "welcome") return '<div class="intro-stage hero-intro"><div class="intro-orbit"><i></i><i></i><i></i>' +
      mark + '</div><h1>Скажите, что<br>нужно сделать.</h1><p>CORTEX отвечает голосом, помогает с настройками и выполняет задачи на Android.</p></div>';
    return '<div class="intro-stage capability-intro"><div class="intro-illustration intro-demo" data-demo="' + demo + '">' +
      '<span class="intro-prompt">Включи фонарик</span><div class="intro-beam"></div><span class="intro-action">' +
      (demo === 2 ? icon("check", 16) + " Android подтвердил" :
        demo === 1 ? '<b class="intro-mini-dots"><i></i><i></i><i></i></b> Проверяю фонарик…' : "Задача ещё не запущена") +
      '</span></div><h1>Видно каждый шаг.</h1><p>Островок показывает, что делает CORTEX. Задачу можно остановить с экрана или из уведомления.</p>' +
      '<button type="button" class="action outline" data-intro-action="demo">Проиграть пример</button></div>';
  }

  function access(item) {
    const granted = Boolean(permissions[item.id]);
    return '<div class="intro-stage permits-intro"><span class="intro-access-art">' + icon(item.icon, 44) +
      '</span><h1>' + item.name + '</h1><p>' + item.description + '</p>' +
      (item.steps ? '<ol class="intro-checklist">' + item.steps.map(step => '<li>' + step + '</li>').join("") + '</ol>' : "") +
      (item.note ? '<p class="intro-mini">' + item.note + '</p>' : "") +
      '<div class="intro-access-state" role="status">' + (granted ? icon("check", 16) + " Доступ активен" : esc(notice || "Доступ можно выдать позже")) + '</div>' +
      '<div class="actions"><button type="button" class="action outline" data-intro-action="permission">' +
      (granted ? "Открыть настройки" : item.steps ? "Перейти к настройкам" : "Разрешить") + '</button>' +
      (item.settings ? '<button type="button" class="action quiet" data-intro-action="details">Открыть карточку приложения</button>' : "") + '</div></div>';
  }

  function finishStage() {
    return '<div class="intro-stage finish-intro"><div class="intro-finish-mark">' + mark + '</div><h1>Готовы начать.</h1>' +
      '<p>Выберите, когда показывать островок. Режим можно поменять в настройках.</p><div class="intro-mode-list">' +
      [["always", "Всегда", "Видим поверх других приложений"], ["wake", "По обращению", "Появляется во время голосовой задачи"], ["off", "В приложении", "Чат и управление из CORTEX"]].map(([mode, title, detail]) =>
        '<button type="button" class="intro-mode ' + (prefs().overlayMode === mode ? "selected" : "") +
        '" data-intro-action="mode" data-mode="' + mode + '" aria-pressed="' + (prefs().overlayMode === mode) +
        '"><span class="intro-mode-art ' + mode + '"><b></b><i></i></span><span class="intro-mode-text"><strong>' +
        title + '</strong><small>' + detail + '</small></span><span class="intro-mode-check"></span></button>').join("") +
      '</div><p class="intro-feedback" role="status">' + esc(notice) + '</p></div>';
  }

  function draw(direction = 0) {
    if (!view) return;
    const id = pages[page];
    const item = accesses.find(x => x.id === id);
    const content = item ? access(item) : id === "finish" ? finishStage() : prologue(id);
    const oldWidth = view.querySelector(".intro-meter i")?.style.width;
    const previous = direction === 0 ? view.querySelector(".intro-content")?.scrollTop || 0 : 0;
    view.innerHTML = '<div class="intro-top"><span class="intro-wordmark">CORTEX</span>' +
      (!single ? '<span class="intro-progress" aria-label="Шаг ' + (page + 1) + ' из ' + pages.length + '">' + (page + 1) + ' / ' + pages.length + '</span>' : '') +
      '<button type="button" data-intro-action="close" aria-label="Закрыть">×</button></div><section class="intro-content">' + content +
      '</section><div class="intro-bottom">' + (!single ? '<div class="intro-meter"><i style="width:' + (100 * (page + 1) / pages.length) + '%"></i></div>' : '') +
      '<div class="intro-nav"><button type="button" class="intro-back" data-intro-action="back" ' + (page === 0 || single ? "hidden" : "") + '>Назад</button>' +
      '<button type="button" class="intro-next" data-intro-action="next">' +
      (single ? "Готово" : id === "finish" ? "Открыть CORTEX" : item && !permissions[id] ? "Пока пропустить" : "Продолжить") +
      ' <span aria-hidden="true">↗</span></button></div></div>';
    const bar = view.querySelector(".intro-meter i");
    if (bar && oldWidth && direction && !reduced()) {
      bar.animate([{ width: oldWidth }, { width: bar.style.width }], { duration: 370, easing: "cubic-bezier(.16,1,.3,1)" });
    }
    const node = view.querySelector(".intro-content");
    node.scrollTop = previous;
    if (direction && !reduced()) node.animate([
      { opacity: 0, transform: "translate3d(" + direction * 20 + "px, 12px, 0) scale(.975)" },
      { opacity: 1, transform: "none" }
    ], { duration: 380, easing: "cubic-bezier(.16,1,.3,1)" });
  }

  function show(index, one = false) {
    if (view) return;
    page = index; single = one; notice = ""; demo = 0;
    view = document.createElement("section");
    view.className = "cortex-intro";
    view.setAttribute("role", "dialog");
    view.setAttribute("aria-modal", "true");
    view.setAttribute("aria-label", one ? "Разрешение Android" : "Первый запуск CORTEX");
    root.classList.add("in-onboarding");
    root.append(view);
    for (const id of ["screen", "navigation", "composition"]) document.getElementById(id).inert = true;
    CortexWave.stop();
    draw(1);
    native("permissions");
  }

  function close(complete = false) {
    if (!view) return;
    if (!single && complete) localStorage.setItem(key, "done");
    clearTimeout(demoTimer);
    view.remove();
    view = null;
    root.classList.remove("in-onboarding");
    for (const id of ["screen", "navigation", "composition"]) document.getElementById(id).inert = false;
    if (!single && complete) native("overlay-config", { mode: prefs().overlayMode, hideLandscape: prefs().hideLandscape });
    dispatchEvent(new Event("cortex-onboarding-end"));
  }

  root.addEventListener("click", event => {
    const target = event.target.closest("[data-intro-action]");
    if (!view?.contains(target)) return;
    const item = accesses.find(x => x.id === pages[page]);
    switch (target.dataset.introAction) {
      case "next":
        if (single || page === pages.length - 1) close(true);
        else { page++; notice = ""; draw(1); }
        break;
      case "back": back(); break;
      case "close": close(!single); break;
      case "demo":
        demo = 1;
        clearTimeout(demoTimer);
        draw();
        demoTimer = setTimeout(() => { demo = 2; if (pages[page] === "demo") draw(); }, reduced() ? 0 : 860);
        break;
      case "permission":
        if (!item) return;
        notice = native(item.action) ? "После выдачи доступа вернитесь в CORTEX." : "Откройте разрешения приложения в Android.";
        draw();
        break;
      case "details": native("open-settings"); break;
      case "mode": {
        const mode = target.dataset.mode;
        if (!["always", "wake", "off"].includes(mode)) return;
        prefs().overlayMode = mode;
        if (mode === "off") prefs().hotwordEnabled = false;
        Cortex.save();
        notice = mode !== "off" && !permissions.overlay ? "Чтобы показать островок, разрешите отображение поверх приложений." :
          mode === "wake" ? "Голосовой вызов можно включить в разделе «Телефон»." : "";
        draw();
        break;
      }
    }
  });

  function back() {
    if (!view) return false;
    if (single || page === 0) close(false);
    else { page--; notice = ""; draw(-1); }
    return true;
  }

  return {
    start: () => show(0),
    autoStart: () => { if (localStorage.getItem(key) !== "done") show(0); },
    openPermission(id) { const index = pages.indexOf(id); if (index >= 2) show(index, true); },
    updatePermissions(value) {
      const id = pages[page];
      const before = permissions[id];
      permissions = { ...permissions, ...value };
      if (view && before !== permissions[id]) { notice = ""; draw(); }
    },
    back
  };
})();
