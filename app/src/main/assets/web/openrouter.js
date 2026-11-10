"use strict";

const OpenRouter = (() => {
  const endpoint = "https://openrouter.ai/api/v1/chat/completions";
  const bridge = window.CortexOpenRouter;
  const android = new URLSearchParams(location.search).has("android");
  const pending = new Map();
  let serial = 0;
  let browserKey = "";

  const messages = {
    400: "OpenRouter отклонил параметры запроса.",
    401: "OpenRouter отклонил ключ. Проверьте его в настройках.",
    402: "У аккаунта нет доступа к бесплатному запросу. Проверьте статус аккаунта OpenRouter.",
    403: "OpenRouter ограничил доступ для этого аккаунта или сети.",
    429: "Достигнут лимит бесплатных запросов. Попробуйте позже.",
    500: "Сервис временно недоступен. Попробуйте позже.",
    502: "Поставщик модели временно недоступен. Повторите запрос.",
    503: "Бесплатные модели сейчас заняты. Попробуйте позже.",
    empty: "Модель вернула пустой ответ. Повторите запрос.",
    timeout: "Модель отвечает слишком долго. Попробуйте ещё раз.",
    offline: "Связь с OpenRouter прервалась. Проверьте интернет.",
    storage: "Хранилище ключа недоступно. Попробуйте сохранить ключ заново.",
    invalid: "Введите ключ OpenRouter в формате sk-or-v1-…",
    missing: "Добавьте ключ OpenRouter в настройках.",
    unsupported: "Для подключения обновите Android System WebView."
  };

  function failure(code) {
    const error = new Error(messages[code] || "OpenRouter вернул ошибку. Попробуйте ещё раз.");
    error.code = code;
    return error;
  }

  function abortError() { return new DOMException("Request stopped", "AbortError"); }

  if (bridge) bridge.onmessage = event => {
    let reply;
    try { reply = JSON.parse(event.data); } catch { return; }
    const complete = pending.get(reply.id);
    if (complete) complete(reply);
  };

  function nativeRequest(operation, payload = {}, signal) {
    return new Promise((resolve, reject) => {
      if (!bridge) return reject(failure("unsupported"));
      if (signal?.aborted) return reject(abortError());
      const id = "request-" + Date.now() + "-" + (++serial);
      const finish = (result, error) => {
        pending.delete(id);
        clearTimeout(timeout);
        signal?.removeEventListener("abort", stop);
        if (error) reject(error);
        else resolve(result);
      };
      const cancel = () => bridge.postMessage(JSON.stringify({ operation: "cancel", id }));
      const stop = () => { cancel(); finish(null, abortError()); };
      const timeout = setTimeout(() => { cancel(); finish(null, failure("timeout")); }, 52000);
      pending.set(id, reply => finish(reply.result, reply.error ? failure(reply.error) : null));
      signal?.addEventListener("abort", stop, { once: true });
      bridge.postMessage(JSON.stringify({ id, operation, ...payload }));
    });
  }

  async function status() {
    if (android) return bridge ? nativeRequest("status") : { available: false, configured: false };
    return { available: true, configured: Boolean(browserKey) };
  }

  async function saveKey(value) {
    const key = value.trim();
    if (!/^sk-or-v1-[A-Za-z0-9_-]{24,128}$/.test(key)) throw failure("invalid");
    if (android) return nativeRequest("save-key", { key });
    browserKey = key;
    return { configured: true };
  }

  async function removeKey() {
    if (android) return nativeRequest("remove-key");
    browserKey = "";
    return { configured: false };
  }

  async function chat(turns, signal, check = false) {
    if (android) return nativeRequest("chat", { turns, check }, signal);
    if (!browserKey) throw failure("missing");
    const controller = new AbortController();
    const stop = () => controller.abort();
    if (signal?.aborted) throw abortError();
    signal?.addEventListener("abort", stop, { once: true });
    const timeout = setTimeout(stop, 47000);
    try {
      const response = await fetch(endpoint, {
        method: "POST", credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
        headers: { Authorization: "Bearer " + browserKey, "Content-Type": "application/json" },
        body: JSON.stringify({ model: "openrouter/free", messages: turns, max_tokens: check ? 256 : 1400 }),
        signal: controller.signal
      });
      if (!response.ok) throw failure(response.status);
      const data = await response.json();
      if (data.error) throw failure(data.error.code);
      const text = data.choices?.[0]?.message?.content?.trim();
      if (!text) throw failure("empty");
      return { text: text.slice(0, 16000), model: String(data.model || "openrouter/free") };
    } catch (error) {
      if (signal?.aborted) throw abortError();
      if (controller.signal.aborted) throw failure("timeout");
      throw error.code ? error : failure("offline");
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", stop);
    }
  }

  return { status, saveKey, removeKey, chat };
})();
