"use strict";

const CortexApi = (() => {
  const bridge = window.CortexServer;
  const android = new URLSearchParams(location.search).has("android");
  const requests = new Map();
  let sequence = 0;
  const errors = {
    preview: "Этот экран открыт в предпросмотре. Чат подключается из Android-приложения.",
    webview: "Для подключения обновите Android System WebView.",
    endpoint_missing: "Чат временно недоступен.",
    endpoint: "Чат временно недоступен. Попробуйте позже.",
    unconfigured: "ИИ временно недоступен. Попробуйте позже.",
    account_config: "Вход через Google пока недоступен.",
    client_version: "Для работы чата обновите CORTEX.",
    auth_expired: "Время входа истекло. Начните вход ещё раз.",
    auth_denied: "Google не подтвердил вход. Попробуйте снова.",
    account_forbidden: "У этого аккаунта пока нет доступа к CORTEX.",
    storage_config: "Сервис CORTEX временно недоступен.",
    sign_in: "Для этого действия нужен вход в профиль.",
    provider_key: "OpenRouter отклонил серверный ключ. Нужна проверка подключения.",
    provider_budget: "Исчерпан бюджет серверного ключа OpenRouter.",
    provider_quota_daily: "Дневная квота бесплатных запросов OpenRouter исчерпана. Она обновится в полночь UTC.",
    provider_quota: "OpenRouter временно ограничил бесплатные запросы.",
    provider_access: "Провайдер ограничил доступ к выбранной модели.",
    provider_balance: "На стороне провайдера исчерпан баланс.",
    provider_limit: "Бесплатные модели сейчас недоступны. Попробуйте чуть позже.",
    rate_limit: "Слишком много запросов. Попробуйте чуть позже.",
    model: "Бесплатные модели сейчас заняты. Попробуйте чуть позже.",
    provider: "Провайдер прервал ответ. Попробуйте ещё раз.",
    timeout: "Ответ занял слишком много времени. Попробуйте ещё раз.",
    offline: "Связь с сервером потеряна. Проверьте интернет.",
    empty: "Модель вернула пустой ответ. Попробуйте ещё раз.",
    invalid: "Сервер не смог прочитать запрос.",
    too_large: "Запрос слишком большой.",
    busy: "Подключение занято. Попробуйте через несколько секунд.",
    device: "Устройство не смогло завершить операцию.",
    support_unconfigured: "Канал связи с разработчиком пока не настроен.",
    support_delivery: "Обращение не удалось отправить. Попробуйте ещё раз.",
    attachment: "Скриншот должен быть в формате JPG или PNG, до 500 КБ.",
    server: "Сервер CORTEX временно недоступен."
  };

  function failure(code, retryAfter) {
    const seconds = Number(retryAfter);
    const delay = ["provider_quota", "provider_limit"].includes(code) && seconds > 0 && seconds <= 3600
      ? " Повторите через " + Math.ceil(seconds / 60) + " мин." : "";
    const error = new Error((errors[code] || errors.server) + delay);
    error.code = code;
    return error;
  }

  if (bridge) bridge.onmessage = event => {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    const entry = requests.get(message.id);
    if (!entry) return;
    if (typeof message.delta === "string") { entry.onDelta?.(message.delta); return; }
    if (message.status === "fallback") { entry.onStatus?.(message.status); return; }
    entry.cleanup();
    if (message.error) entry.reject(failure(message.error, message.retry_after));
    else entry.resolve(message.result ?? {});
  };

  function request(operation, fields = {}, { signal, onDelta, onStatus } = {}) {
    if (!bridge) return Promise.reject(failure(android ? "webview" : "preview"));
    if (signal?.aborted) return Promise.reject(new DOMException("Остановлено", "AbortError"));
    const id = "request-" + Date.now() + "-" + (++sequence);
    return new Promise((resolve, reject) => {
      function cleanup() {
        clearTimeout(timer);
        requests.delete(id);
        signal?.removeEventListener("abort", abort);
      }
      function cancel() {
        cleanup();
        try { bridge.postMessage(JSON.stringify({ id, operation: "cancel" })); } catch {}
      }
      function abort() {
        cancel();
        reject(new DOMException("Остановлено", "AbortError"));
      }
      const timer = setTimeout(() => {
        cancel();
        reject(failure("timeout"));
      }, operation === "chat" ? 145000 : 72000);
      requests.set(id, { resolve, reject, cleanup, onDelta, onStatus });
      signal?.addEventListener("abort", abort, { once: true });
      try { bridge.postMessage(JSON.stringify({ id, operation, ...fields })); }
      catch { cleanup(); reject(failure("device")); }
    });
  }

  return { request, failure, available: Boolean(bridge), android };
})();
