"use strict";

window.CortexControl = (() => {
  const api = CortexApi;
  if (!api?.available) return { back: () => false, refresh() {} };
  const { esc, icon } = CortexUI;
  const device = window.CortexDevice;
  const visible = () => !document.hidden && window.cortexAppVisible !== false;
  const send = (operation, fields = {}) => device?.postMessage(JSON.stringify({ operation, ...fields }));
  const layer = document.createElement("section");
  layer.className = "cortex-guard";
  layer.hidden = true;
  layer.innerHTML = '<div class="cortex-guard-halo"><img src="art/mark.svg" alt=""></div><h1></h1><p class="cortex-guard-copy"></p>' +
    '<div class="cortex-guard-actions"><button type="button" data-gate-retry>Проверить снова</button>' +
    '<button type="button" data-gate-update hidden>Обновить CORTEX</button><button type="button" data-gate-login hidden>Войти в профиль</button><button type="button" data-gate-admin>Панель управления</button></div>';
  document.body.append(layer);
  const update = layer.querySelector("[data-gate-update]");
  let gate = { mode: "open" }, checking = false, again = false;
  function applyGate(value, google) {
    if (!["open", "maintenance", "update"].includes(value?.mode)) return;
    gate = value;
    layer.hidden = gate.mode === "open";
    send("gate-state", { mode: gate.mode });
    if (layer.hidden) return;
    layer.querySelector("h1").textContent = gate.mode === "maintenance" ? "Технические работы" : "Требуется обновление";
    layer.querySelector(".cortex-guard-copy").textContent = String(gate.message || "CORTEX временно недоступен.").slice(0, 400);
    layer.querySelector("[data-gate-login]").hidden = !google;
    update.hidden = gate.mode !== "update" || !/^https:\/\//.test(gate.update_url || "");
  }

  async function check() {
    if (!visible()) return;
    if (checking) { again = true; return; }
    checking = true;
    try { const status = await api.request("status"); applyGate(status.gate, status.google === true); }
    catch { /* Keep the last verified state until the connection returns. */ }
    finally {
      checking = false;
      if (again) { again = false; void check(); }
    }
  }
  layer.querySelector("[data-gate-retry]").addEventListener("click", check);
  layer.querySelector("[data-gate-login]").addEventListener("click", () => window.cortexAdminLogin?.());
  layer.querySelector("[data-gate-admin]").addEventListener("click", () => send("open-link", { url: "https://cortex-control-phi.vercel.app/" }));
  update.addEventListener("click", () => {
    if (/^https:\/\//.test(gate.update_url || "")) send("open-link", { url: gate.update_url });
  });

  const session = crypto.randomUUID();
  let lastHeartbeat = 0;
  async function heartbeat() {
    if (!visible() || Date.now() - lastHeartbeat < 42_000) return;
    lastHeartbeat = Date.now();
    try { await api.request("presence", { payload: { session_id: session, screen: document.querySelector("#screen")?.dataset.route || "home" } }); }
    catch { /* Presence is retried on the next interval. */ }
  }

  const inbox = document.createElement("dialog");
  inbox.className = "cortex-inbox";
  inbox.innerHTML = '<header><h2>Мои обращения</h2><button type="button" data-close aria-label="Закрыть">×</button></header>' +
    '<main class="cortex-inbox-main"></main>';
  document.body.append(inbox);
  inbox.querySelector("[data-close]").addEventListener("click", () => inbox.close());
  const host = inbox.querySelector(".cortex-inbox-main");
  const statusLabels = {
    new: "Новое", open: "Открыто", received: "Получено", pending: "Ожидает", in_progress: "В работе",
    answered: "Есть ответ", resolved: "Решено", closed: "Закрыто"
  };
  let inboxRequest;
  const repliesOf = data => Array.isArray(data?.replies) ? data.replies : [];
  const replyIds = data => repliesOf(data).map(reply => reply.id).filter(id => typeof id === "string");
  function fetchInbox() {
    const owner = Cortex.state.account?.id || "";
    if (inboxRequest?.owner === owner) return inboxRequest.result;
    inboxRequest?.controller.abort();
    const pending = { owner, controller: new AbortController() };
    pending.result = api.request("support-inbox", {}, { signal: pending.controller.signal }).then(data => {
      if ((Cortex.state.account?.id || "") !== owner) throw api.failure("sign_in");
      return data;
    }).finally(() => { if (inboxRequest === pending) inboxRequest = null; });
    inboxRequest = pending;
    return pending.result;
  }
  function renderInbox(data) {
    const replies = repliesOf(data);
    if (!data?.tickets?.length) { host.textContent = "Обращений пока нет."; return; }
    host.innerHTML = data.tickets.map(ticket => {
      const thread = replies.filter(reply => reply.ticket_id === ticket.id).sort((a, b) => a.created_at.localeCompare(b.created_at));
      const date = new Date(ticket.created_at).toLocaleDateString("ru-RU");
      return '<article><header><small>' + esc(date) + ' · № ' + esc(String(ticket.id).slice(0, 8).toUpperCase()) +
        '</small><span class="ticket-status">' + esc(statusLabels[ticket.status] || ticket.status) + '</span></header><p>' + esc(ticket.message) + '</p>' +
        thread.map(reply => '<aside><small>Поддержка CORTEX · ' + esc(new Date(reply.created_at).toLocaleString("ru-RU")) +
          '</small><p>' + esc(reply.message) + '</p></aside>').join("") + '</article>';
    }).join("");
  }
  async function openInbox() {
    if (!inbox.open) inbox.showModal();
    host.textContent = "Загружаю обращения…";
    try {
      const data = await fetchInbox();
      if (!inbox.open) return;
      renderInbox(data);
      send("support-notification", { ids: replyIds(data), seen: true });
    } catch {
      if (!inbox.open) return;
      host.innerHTML = '<p>Не удалось загрузить обращения.</p><button type="button" data-inbox-retry>Повторить</button>';
      host.querySelector("[data-inbox-retry]").addEventListener("click", openInbox);
    }
  }
  let lastInboxCheck = 0;
  async function checkReplies() {
    if (!visible() || !Cortex.state.preferences.notifyReplies || Date.now() - lastInboxCheck < 45_000) return;
    lastInboxCheck = Date.now();
    try {
      const data = await fetchInbox();
      if (inbox.open) renderInbox(data);
      send("support-notification", { ids: replyIds(data), seen: inbox.open });
    } catch { /* Retry at the next interval. */ }
  }
  function refresh() {
    if (!visible()) return;
    void check();
    void heartbeat();
    void checkReplies();
  }
  const screen = document.getElementById("screen");
  function attachInbox() {
    const group = document.querySelector("#screen.support-screen .tile-group");
    if (!group || group.querySelector("[data-my-tickets]")) return;
    const button = document.createElement("button");
    button.type = "button"; button.className = "tile"; button.dataset.myTickets = "true";
    button.innerHTML = '<span class="tile-glyph">' + icon("message", 20) +
      '</span><span class="tile-copy"><strong>Мои обращения</strong><small>Статусы и ответы поддержки</small></span><span aria-hidden="true">›</span>';
    button.addEventListener("click", openInbox);
    group.prepend(button);
  }
  new MutationObserver(attachInbox).observe(screen, { childList: true });
  attachInbox();
  document.addEventListener("visibilitychange", refresh);
  window.addEventListener("cortex-app-visibility", refresh);
  window.addEventListener("cortex-account-changed", () => {
    inboxRequest?.controller.abort();
    inboxRequest = null;
    if (inbox.open) inbox.close();
    host.textContent = "";
    lastInboxCheck = 0;
    refresh();
  });
  setTimeout(refresh, 1100);
  setInterval(refresh, 45_000);

  return { refresh, back() { if (!inbox.open) return false; inbox.close(); return true; } };
})();
