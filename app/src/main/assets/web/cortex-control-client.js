"use strict";
(() => {
  const api = window.CortexApi;
  if (!api?.available) return;
  let gate = {mode:"open"}, active = false;
  const layer = document.createElement("div");
  layer.className = "cortex-guard"; layer.hidden = true;
  layer.innerHTML = '<div class="cortex-guard-halo"><img src="art/mark.svg" alt=""></div><p class="cortex-eyebrow">C O R T E X / S Y S T E M</p><h1></h1><p class="cortex-guard-copy"></p><div class="cortex-guard-actions"><button type="button" data-gate-retry>Проверить снова</button><button type="button" data-gate-update hidden>Обновить CORTEX</button></div><small>CORTEX · На связи</small>';
  document.body.append(layer);
  const button = layer.querySelector("[data-gate-update]");
  function access(data) {
    if (!data || typeof data !== "object") return;
    gate = data;
    const locked = gate.mode === "maintenance" || gate.mode === "update";
    layer.hidden = !locked;
    if (!locked) return;
    layer.querySelector("h1").textContent = gate.mode === "maintenance" ? "Технические работы" : "Требуется обновление";
    layer.querySelector(".cortex-guard-copy").textContent = String(gate.message || "CORTEX временно недоступен.").slice(0,400);
    button.hidden = gate.mode !== "update" || !/^https:\/\//.test(gate.update_url || "");
    for (const selector of ["#sheet","#history"]) {
      const d = document.querySelector(selector);
      if (d?.open) d.close();
    }
  }
  async function check() {
    if (active || document.hidden) return;
    active = true;
    try { const status = await api.request("status"); access(status.gate); } catch {}
    finally { active = false; }
  }
  layer.querySelector("[data-gate-retry]").addEventListener("click",check);
  button.addEventListener("click",() => {
    if (/^https:\/\//.test(gate.update_url || "")) window.CortexDevice?.postMessage(JSON.stringify({operation:"open-link",url:gate.update_url}));
  });
  const session = crypto.randomUUID?.() || "10000000-1000-4000-8000-"+Date.now().toString(16).padStart(12,"0").slice(-12);
  let lastHeartbeat = 0;
  async function heartbeat() {
    if (document.hidden || Date.now()-lastHeartbeat < 35000) return;
    lastHeartbeat = Date.now();
    try {
      const result = await api.request("presence",{payload:{session_id:session,screen:document.querySelector("#screen")?.dataset.route||"home"}});
      access(result.gate);
    } catch {}
  }
  function refresh() { if (!document.hidden) { void check(); void heartbeat(); } }
  document.addEventListener("visibilitychange",refresh);
  window.addEventListener("cortex-app-visibility",refresh);
  const inbox = document.createElement("dialog");
  inbox.className = "cortex-inbox";
  inbox.innerHTML = '<header><h2>Мои обращения</h2><button type="button" data-close aria-label="Закрыть">×</button></header><main class="cortex-inbox-main"></main>';
  document.body.append(inbox);
  inbox.querySelector("[data-close]").addEventListener("click",()=>inbox.close());
  const esc = value => String(value??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  async function openInbox() {
    inbox.showModal();
    const host = inbox.querySelector(".cortex-inbox-main"); host.textContent = "Проверяем ответы поддержки…";
    try {
      const r = await api.request("support-inbox"), replies = r.replies || [];
      if (!r.tickets?.length) { host.textContent = "Обращений пока нет."; return; }
      host.innerHTML = r.tickets.map(t => '<article><small>'+esc(new Date(t.created_at).toLocaleDateString("ru-RU"))+' · '+esc(t.status)+'</small><p>'+esc(t.message)+'</p>'+
        replies.filter(x=>x.ticket_id===t.id).map(x=>'<aside><small>CORTEX SUPPORT</small><p>'+esc(x.message)+'</p></aside>').join("")+'</article>').join("");
    } catch { host.textContent = "Не удалось получить ответы. Проверьте подключение."; }
  }
  let scheduled = false;
  function attachInbox() {
    const group=document.querySelector("#screen.support-screen .tile-group");
    if (!group || group.querySelector("[data-my-tickets]")) return;
    const btn=document.createElement("button");
    btn.type="button";btn.className="tile";btn.dataset.myTickets="true";
    btn.innerHTML='<span class="tile-glyph">✉</span><span class="tile-copy"><strong>Мои обращения</strong><small>Сообщения и ответы команды CORTEX</small></span><span aria-hidden="true">›</span>';
    btn.addEventListener("click",openInbox);group.prepend(btn);
  }
  const screen=document.querySelector("#screen");
  if (screen) new MutationObserver(()=>{
    if (scheduled) return;scheduled=true;
    requestAnimationFrame(()=>{scheduled=false;attachInbox();});
  }).observe(screen,{childList:true});
  attachInbox();
  setTimeout(refresh,1900);
  setInterval(refresh,45000);
})();
