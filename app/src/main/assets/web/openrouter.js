"use strict";

const OpenRouter = (() => {
  const endpoint = "https://openrouter.ai/api/v1/chat/completions";
  const models = ["google/gemma-4-26b-a4b-it:free", "openrouter/free"];
  const lengths = { brief: 740, balanced: 1450, deep: 2200 };
  const bridge = window.CortexOpenRouter;
  const android = new URLSearchParams(location.search).has("android");
  const pending = new Map();
  let serial = 0;
  let browserKey = "";

  const messages = {
    400: "OpenRouter отклонил параметры запроса.",
    401: "Проверьте ключ OpenRouter.",
    402: "OpenRouter ограничил доступ к бесплатному запросу для этого аккаунта.",
    403: "OpenRouter ограничил доступ для этого аккаунта или сети.",
    404: "Бесплатные модели временно недоступны.",
    429: "Лимит бесплатных запросов исчерпан. Попробуйте позже.",
    500: "OpenRouter временно недоступен.",
    502: "Поставщик модели временно недоступен.",
    503: "Бесплатные модели сейчас заняты. Попробуйте позже.",
    empty: "Ответ оказался пустым. Попробуйте повторить запрос.",
    timeout: "Ответ занял слишком много времени. Повторите запрос.",
    offline: "Связь с OpenRouter прервалась. Проверьте интернет.",
    storage: "Хранилище ключа недоступно. Попробуйте добавить ключ заново.",
    invalid: "Введите ключ OpenRouter в формате sk-or-v1-…",
    missing: "Добавьте ключ OpenRouter.",
    unsupported: "Обновите Android System WebView, чтобы подключить ИИ."
  };

  function failure(raw) {
    const code = /^\d+$/.test(String(raw)) ? Number(raw) : raw;
    const error = new Error(messages[code] || "OpenRouter вернул ошибку. Попробуйте повторить запрос.");
    error.code = code;
    return error;
  }

  const abortError = () => new DOMException("Request stopped", "AbortError");

  if (bridge) bridge.onmessage = event => {
    let reply;
    try { reply = JSON.parse(event.data); } catch { return; }
    pending.get(reply.id)?.(reply);
  };

  function nativeRequest(operation, fields = {}, signal, onDelta) {
    return new Promise((resolve, reject) => {
      if (!bridge) return reject(failure("unsupported"));
      if (signal?.aborted) return reject(abortError());
      const id = "request-" + Date.now() + "-" + (++serial);
      const finish = (result, error) => {
        pending.delete(id);
        clearTimeout(timer);
        signal?.removeEventListener("abort", stop);
        if (error) reject(error);
        else resolve(result);
      };
      const cancel = () => {
        try { bridge.postMessage(JSON.stringify({ id, operation: "cancel" })); } catch { /* channel closed */ }
      };
      const stop = () => { cancel(); finish(null, abortError()); };
      const timer = setTimeout(() => { cancel(); finish(null, failure("timeout")); }, operation === "chat" ? 90000 : 7000);
      pending.set(id, reply => {
        if (typeof reply.delta === "string") { onDelta?.(reply.delta); return; }
        finish(reply.result, reply.error ? failure(reply.error) : null);
      });
      signal?.addEventListener("abort", stop, { once: true });
      try { bridge.postMessage(JSON.stringify({ id, operation, ...fields })); }
      catch { finish(null, failure("unsupported")); }
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

  function responseText(data) {
    if (data.error) throw failure(data.error.code);
    const text = data.choices?.[0]?.message?.content;
    if (typeof text !== "string" || !text.trim()) throw failure("empty");
    return { text: text.trim().slice(0, 20000) };
  }

  async function readStream(response, onDelta) {
    if (!response.body) throw failure("empty");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let text = "";
    let finished = false;
    function consume(line) {
      if (!line.startsWith("data:")) return;
      const event = line.slice(5).trim();
      if (event === "[DONE]") { finished = true; return; }
      if (!event) return;
      let data;
      try { data = JSON.parse(event); } catch { return; }
      if (data.error) throw failure(data.error.code);
      const choice = data.choices?.[0];
      if (choice?.finish_reason === "error") throw failure(502);
      const delta = choice?.delta?.content;
      if (typeof delta !== "string" || !delta) return;
      const addition = delta.slice(0, 20000 - text.length);
      text += addition;
      if (addition) onDelta?.(addition);
      if (text.length >= 20000) finished = true;
    }
    try {
      while (!finished) {
        const next = await reader.read();
        if (next.done) {
          consume(buffer + decoder.decode());
          break;
        }
        buffer += decoder.decode(next.value, { stream: true });
        let newline = buffer.indexOf("\n");
        while (newline !== -1 && !finished) {
          consume(buffer.slice(0, newline));
          buffer = buffer.slice(newline + 1);
          newline = buffer.indexOf("\n");
        }
      }
    } finally {
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
    if (!text.trim()) throw failure("empty");
    return { text: text.trim() };
  }

  async function chat(turns, { signal, check = false, style = "balanced", onDelta } = {}) {
    const payload = {
      models, messages: turns, temperature: .42, top_p: .9,
      max_tokens: check ? 256 : lengths[style] || lengths.balanced,
      reasoning: { effort: "low", exclude: true }, stream: !check
    };
    if (android) return nativeRequest("chat", { payload }, signal, onDelta);
    if (!browserKey) throw failure("missing");
    const controller = new AbortController();
    const stop = () => controller.abort();
    if (signal?.aborted) throw abortError();
    signal?.addEventListener("abort", stop, { once: true });
    const timeout = setTimeout(stop, 85000);
    try {
      const response = await fetch(endpoint, {
        method: "POST", credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
        headers: { Authorization: "Bearer " + browserKey, "Content-Type": "application/json" },
        body: JSON.stringify(payload), signal: controller.signal
      });
      if (!response.ok) throw failure(response.status);
      if (check || !response.headers.get("content-type")?.includes("text/event-stream")) {
        return responseText(await response.json());
      }
      return await readStream(response, onDelta);
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
