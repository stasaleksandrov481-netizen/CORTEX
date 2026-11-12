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
    provider_key: "ИИ временно недоступен. Попробуйте позже.",
    provider_access: "Провайдер ограничил доступ к выбранной модели.",
    provider_balance: "На стороне провайдера исчерпан баланс.",
    provider_limit: "Бесплатная модель достигла лимита. Повторите запрос позже — переключение на резерв уже выполняется автоматически.",
    rate_limit: "Слишком много запросов. Попробуйте чуть позже.",
    model: "Свободные модели сейчас заняты. Попробуйте повторить запрос или сменить режим в настройках.",
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

  function failure(code) {
    const error = new Error(errors[code] || errors.server);
    error.code = code;
    return error;
  }

  if (bridge) bridge.onmessage = event => {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    const entry = requests.get(message.id);
    if (!entry) return;
    if (typeof message.delta === "string") { entry.onDelta?.(message.delta); return; }
    entry.cleanup();
    if (message.error) entry.reject(failure(message.error));
    else entry.resolve(message.result ?? {});
  };

  function request(operation, fields = {}, { signal, onDelta } = {}) {
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
      requests.set(id, { resolve, reject, cleanup, onDelta });
      signal?.addEventListener("abort", abort, { once: true });
      try { bridge.postMessage(JSON.stringify({ id, operation, ...fields })); }
      catch { cleanup(); reject(failure("device")); }
    });
  }

  return { request, failure, available: Boolean(bridge), android };
})();
