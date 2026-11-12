"use strict";

/* CORTEX first-run experience. No permission is implied by a completed slide. */
window.CortexSetup = (() => {
  const KEY = "cortex:onboarding:v3";
  const prefs = () => Cortex.state.preferences;
  const reduced = () => prefs().reduceMotion || matchMedia("(prefers-reduced-motion: reduce)").matches;
  const bridge = () => window.CortexDevice;
  const hasOverlayBridge = () => Boolean(window.CortexIslandControl);
  const root = document.querySelector(".app-shell");
  let view = null, page = 0, permissions = {}, busy = false;
  let notice = "";
  const steps = ["Знакомство", "Возможности", "Вызов", "Доступы", "Готово"];
  const esc = str => String(str ?? "").replace(/[&<>\"']/g, s => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"})[s]);
  function send(operation, data = {}) {
    const native = window.CortexIslandControl;
    const special = new Set(["overlay-permission", "microphone-permission", "overlay-config", "wake-config"]);
    if (operation === "permissions" && native) {
      try { native.postMessage(JSON.stringify({ operation })); } catch {}
    }
    // New Android features MUST go through the new native bridge, not the old
    // CortexDevice channel, which silently ignores these messages.
    const target = special.has(operation) ? native : bridge();
    if (!target) return false;
    try { target.postMessage(JSON.stringify({ operation, ...data })); return true; } catch { return false; }
  }
  const permissionsList = [
    { id:"microphone", title:"Микрофон", info:"Нужен для голосовых сообщений. Запрашивается при записи.", action:"microphone-permission", symbol:"mic" },
    { id:"notifications", title:"Уведомления", info:"Для оповещений, если вы их включите.", action:"notification-permission", symbol:"bell" },
    { id:"verifyApps", title:"Проверка приложений", info:"Уточняет, какое приложение открыто.", action:"verification-permission", symbol:"eye" },
    { id:"overlay", title:"Островок", info:"Показ статуса поверх приложений у камеры и вызов по имени «Кортекс».", action:"overlay-permission", symbol:"island" }
  ];
  const icon = name => ({mic:'◉',island:'◐',bell:'◌',eye:'◎',hand:'✦'})[name] || '•';
  const dots = count => `<div class="intro-particles" aria-hidden="true">${Array.from({length: count}, (_,i)=>`<i style="--i:${i};--n:${count}"></i>`).join("")}</div>`;
  const mark = `<img src="art/mark.svg" alt="" class="intro-mark">`;
  function accessCard(item) {
    const granted = Boolean(permissions[item.id]);
    const supported = item.id === "overlay" ? hasOverlayBridge() : Boolean(bridge());
    const label = granted ? "Выдано" : item.id === "overlay" && !supported ? "Скоро" : supported ? "Открыть" : "Инфо";
    return `<div class="intro-permission"><span class="intro-permission-icon">${icon(item.symbol)}</span><div><strong>${item.title}</strong><small>${item.info}</small></div><button type="button" data-intro-action="permission" data-permission="${item.action}" aria-label="${label}: ${item.title}">${label}</button></div>`;
  }
  function modeCard(id, title, desc, selected) {
    return `<button class="intro-mode ${selected === id ? 'selected' : ''}" type="button" data-intro-action="mode" data-mode="${id}" aria-pressed="${selected === id}">
      <span class="intro-mode-art ${id}"><b></b><i></i></span><span class="intro-mode-text"><strong>${title}</strong><small>${desc}</small></span><span class="intro-mode-check"></span></button>`;
  }
  function body() {
    const mode = prefs().overlayMode || "off";
    switch (page) {
      case 0: return `<div class="intro-stage hero-intro">${dots(19)}<div class="intro-orbit"><i></i><i></i><i></i>${mark}</div>
         <div class="intro-eyebrow">ПРИВЕТСТВУЕМ В CORTEX</div><h1>Ваш телефон.<br>Ваши правила.</h1><p>Умный помощник, который понимает обычную речь и помогает с задачами на Android.</p></div>`;
      case 1: return `<div class="intro-stage capability-intro"><div class="intro-illustration">${dots(12)}
          <span class="intro-prompt">Ваш запрос <i class="intro-signal-dot" aria-hidden="true"></i></span><div class="intro-beam"></div><span class="intro-action"><b class="intro-mini-dots" aria-hidden="true"><i></i><i></i><i></i></b> CORTEX обрабатывает</span></div>
          <div class="intro-eyebrow">БЕЗ СЛОЖНЫХ КОМАНД</div><h1>Говорите<br>естественно.</h1>
          <p>Пишите или используйте голос. CORTEX определит подходящий способ помочь — в пределах доступных функций.</p></div>`;
      case 2: return `<div class="intro-stage modes-intro"><div class="intro-eyebrow">СПОСОБ ВЫЗОВА</div><h1>Всегда рядом.<br>На ваших условиях.</h1><p>Выберите, как CORTEX будет появляться поверх приложений. Позже это можно изменить.</p>
        <div class="intro-mode-list" role="group" aria-label="Видимость CORTEX">
          ${modeCard("always","Постоянный островок","Видим на экране, скрывается в горизонтальном режиме",mode)}
          ${modeCard("wake","Только по обращению","Появляется после обращения «Кортекс»",mode)}
          ${modeCard("off","Без островка","Вызов через приложение или системный способ",mode)}
        </div><p class="intro-mini">Островок рисуется поверх приложений и требует разрешения «Поверх других приложений».</p>
        <div class="intro-feedback" role="status" aria-live="polite">${esc(notice)}</div></div>`;
      case 3: return `<div class="intro-stage permits-intro"><div class="intro-eyebrow">КОНТРОЛЬ И БЕЗОПАСНОСТЬ</div><h1>Вы решаете,<br>что разрешить.</h1>
        <p>Доступы запрашиваются для конкретных действий. Можно настроить позже.</p>
        <div class="intro-permission-list">${permissionsList.map(accessCard).join("")}</div>
        <div class="intro-feedback" role="status" aria-live="polite">${esc(notice)}</div>
        <p class="intro-mini">Разрешения всегда можно отозвать в настройках Android.</p></div>`;
      default: return `<div class="intro-stage finish-intro">${dots(15)}<div class="intro-finish-mark">${mark}<span>✓</span></div><div class="intro-eyebrow">ВСЁ ГОТОВО</div>
        <h1>Готов к вашим<br>задачам.</h1><p>CORTEX действует по вашим запросам. Доступы можно изменить в настройках Android.</p></div>`;
    }
  }
  function draw(direction = 1) {
    if (!view) return;
    const previousScroll = direction === 0 ? view.querySelector(".intro-content")?.scrollTop || 0 : 0;
    view.innerHTML = `<div class="intro-top"><span class="intro-wordmark">CORTEX</span><span class="intro-progress">${String(page+1).padStart(2,"0")} <span>/ ${String(steps.length).padStart(2,"0")}</span></span><button type="button" data-intro-action="close" aria-label="Закрыть инструкцию">×</button></div>
      <section class="intro-content" aria-live="polite" aria-label="Шаг ${page+1} из ${steps.length}">${body()}</section>
      <div class="intro-bottom"><div class="intro-stepper" aria-label="Шаги инструкции">${steps.map((_,i)=>`<i class="${i===page?'active':''}"></i>`).join("")}</div>
      <div class="intro-nav"><button type="button" class="intro-back" data-intro-action="back" ${page===0?'disabled':''}>Назад</button>
      <button type="button" class="intro-next" data-intro-action="next">${page===steps.length-1?'Открыть CORTEX':'Продолжить'} <span>↗</span></button></div></div>`;
    const content = view.querySelector(".intro-content");
    if (content && direction === 0) content.scrollTop = previousScroll;
    if (direction !== 0 && !reduced() && content?.animate) content.animate([
      {opacity:0,transform:`translate3d(${direction*14}px,5px,0) scale(.991)`},
      {opacity:1,transform:'none'}
    ], {duration:370,easing:'cubic-bezier(.2,.8,.2,1)'});
  }
  function start(force = false) {
    if (view || busy || (!force && localStorage.getItem(KEY) === "done")) return;
    page = 0; busy = false; notice = "";
    view = document.createElement("div"); view.className = "cortex-intro"; view.setAttribute("role", "dialog");
    view.setAttribute("aria-modal","true"); view.setAttribute("aria-label","Первое знакомство с CORTEX");
    root.classList.add('in-onboarding'); root.append(view);
    for (const id of ["screen", "navigation", "composition"]) {
      const node = document.getElementById(id);
      if (node) node.inert = true;
    }
    // Do not spend CPU painting the animated background beneath a full-screen tutorial.
    CortexWave.stop();
    draw(); send('permissions');
  }
  function close(complete = false) {
    if (!view) return;
    if (complete) localStorage.setItem(KEY, 'done');
    const el = view; view = null; root.classList.remove('in-onboarding');
    // The splash can still be fading when the user dismisses onboarding.
    // Releasing interaction only after splash.hidden could strand the UI inert.
    for (const id of ["screen", "navigation", "composition"]) {
      const node = document.getElementById(id);
      if (node) node.inert = false;
    }
    dispatchEvent(new Event("cortex-onboarding-end"));
    if (!reduced() && el.animate) el.animate([
      {opacity:1,transform:'translateY(0)'},{opacity:0,transform:'translateY(-15px)'}
    ],{duration:260,fill:'forwards'}).finished.then(()=>el.remove(),()=>el.remove());
    else el.remove();
    if (hasOverlayBridge()) send('overlay-config', {mode:prefs().overlayMode||'off',hideLandscape:prefs().hideLandscape !== false});
  }
  function setMode(mode) {
    if (!['always','wake','off'].includes(mode)) return;
    prefs().overlayMode = mode;
    if (mode === "off") prefs().hotwordEnabled = false;
    Cortex.save();
    try { localStorage.setItem('cortex:surface-mode',mode); } catch {}
    if (!window.CortexIslandControl && mode !== "off") {
      notice = "Выбор запомнен. Системный островок пока не встроен в эту APK-сборку.";
    } else if (mode !== "off" && !permissions.overlay) {
      notice = "Режим сохранён. На следующем шаге разрешите показ поверх приложений.";
    } else {
      send('overlay-config',{mode,hideLandscape:prefs().hideLandscape !== false});
    }
    draw(0);
  }
  root.addEventListener('click', event => {
    const target = event.target.closest('[data-intro-action]');
    if (!target || !view || !view.contains(target)) return;
    switch (target.dataset.introAction) {
      case 'next': if (page===steps.length-1) close(true); else {page++;draw(1);} break;
      case 'back': if (page>0){page--;draw(-1);} break;
      case 'close': close(true); break;
      case 'mode': setMode(target.dataset.mode); break;
      case 'permission': {
        const action = target.dataset.permission;
        if (action === 'overlay-permission' && !hasOverlayBridge()) {
          notice = "В этом APK нет службы островка. Для показа поверх Telegram нужен полноценный нативный модуль.";
        } else if (action === 'microphone-permission' && !hasOverlayBridge()) {
          if (send('open-settings')) notice = "В настройках CORTEX откройте «Разрешения» → «Микрофон». При голосовой записи Android также может запросить доступ.";
          else notice = "Для голосового ввода откройте системные разрешения приложения CORTEX.";
        } else if (!send(action)) {
          notice = "Откройте настройки Android → Приложения → CORTEX → Разрешения.";
        } else {
          notice = "Открываю настройки Android. После выдачи разрешения вернитесь в CORTEX.";
        }
        draw(0);
        break;
      }
    }
  });
  function updatePermissions(value) {
    permissions = {...permissions,...value};
    if (view && page===3) {
      notice = "";
      draw(0);
    }
  }
  function back() {
    if (!view) return false;
    if (page > 0) { page--; notice = ""; draw(-1); }
    else { notice = "Используйте «Продолжить» или «×», чтобы закончить знакомство."; draw(0); }
    return true;
  }
  function autoStart() { start(false); }
  return {start,autoStart,updatePermissions,back};
})();
