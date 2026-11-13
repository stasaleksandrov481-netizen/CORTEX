import { type DeviceDecision, deviceTools, repairTools } from "./device.ts";

export class Failure extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, status: number, readonly retryAfter = 0) {
    super(code);
    this.code = code;
    this.status = status;
  }
}

export type Network = typeof fetch;
export type Turn = { role: "system" | "user" | "assistant"; content: string };
export type Account = { id: string; name: string; email: string; provider: "Google"; avatar: string };

const LIMITS = { brief: 740, balanced: 1450, deep: 2200 };
const OUTPUT_LIMIT = 20_000;
const SUPPORT_KINDS = new Set(["contact", "bug", "idea", "complaint"]);
const TICKET_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const RETRYABLE_MODEL_ERRORS = new Set(["empty", "model", "provider", "provider_access", "provider_limit", "provider_limit_unknown", "timeout"]);
export const retryableModelError = (code: unknown) => typeof code === "string" && RETRYABLE_MODEL_ERRORS.has(code);

export async function body(request: Request, limit = 770_000): Promise<Record<string, unknown>> {
  if (Number(request.headers.get("content-length") || 0) > limit) throw new Failure("too_large", 413);
  const reader = request.body?.getReader();
  if (!reader) throw new Failure("invalid", 400);
  const decoder = new TextDecoder();
  let bytes = 0, text = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > limit) throw new Failure("too_large", 413);
      text += decoder.decode(value, { stream: true });
    }
    const value: unknown = JSON.parse(text + decoder.decode());
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Failure("invalid", 400);
    return value as Record<string, unknown>;
  } catch (error) {
    await reader.cancel().catch(() => {});
    if (error instanceof Failure) throw error;
    throw new Failure("invalid", 400);
  } finally {
    reader.releaseLock();
  }
}

export type ModelProfile = "auto" | "fast" | "deep";
export function chatInput(input: Record<string, unknown>): { messages: Turn[]; limit: number; device: boolean; repair: boolean; profile?: ModelProfile } {
  const { messages, style = "balanced", device_tools = false, repair_tools = false, profile } = input;
  if (Object.keys(input).some(key => !["messages", "style", "device_tools", "repair_tools", "profile"].includes(key)) ||
    profile !== undefined && profile !== "auto" && profile !== "fast" && profile !== "deep" ||
    typeof device_tools !== "boolean" || typeof repair_tools !== "boolean" || repair_tools && !device_tools || typeof style !== "string" || !Object.hasOwn(LIMITS, style) || !Array.isArray(messages) ||
    messages.length < 1 || messages.length > 29) throw new Failure("invalid", 400);
  let length = 0;
  const turns: Turn[] = messages.map((turn, index) => {
    if (!turn || typeof turn !== "object" || Object.keys(turn).some(key => key !== "role" && key !== "content") ||
      !["system", "user", "assistant"].includes(turn.role) || turn.role === "system" && index !== 0 ||
      typeof turn.content !== "string" || !turn.content.trim()) throw new Failure("invalid", 400);
    length += turn.content.length;
    return { role: turn.role, content: turn.content };
  });
  if (length > 51_000 || turns.at(-1)?.role !== "user") throw new Failure("invalid", 400);
  return { messages: turns, limit: LIMITS[style as keyof typeof LIMITS], device: device_tools, repair: repair_tools, profile };
}

function retrySeconds(headers: Headers): number {
  const retry = headers.get("retry-after");
  const reset = Number(headers.get("x-ratelimit-reset"));
  const now = Date.now();
  const dates = [
    retry ? (Number.isFinite(Number(retry)) ? Number(retry) : (Date.parse(retry) - now) / 1000) : 0,
    reset > 0 ? ((reset > 1e12 ? reset : reset * 1000) - now) / 1000 : 0,
  ];
  return Math.min(86_400, Math.max(0, ...dates.filter(Number.isFinite).map(Math.ceil)));
}

type ProviderError = { code?: unknown; message?: string; metadata?: Record<string, unknown> };
function providerFailure(status: number, error?: ProviderError, headers = new Headers()): Failure {
  const metadata = error?.metadata;
  const type = String(metadata?.error_type ?? "");
  const message = typeof error?.message === "string" ? error.message : "";
  const retry = retrySeconds(headers);
  if (status === 429) {
    if (metadata?.provider_name || metadata?.provider_code || /upstream rate.limit|temporarily rate.limited upstream/i.test(message)) {
      return new Failure("provider_limit", 429, retry);
    }
    if (/free.model.{0,50}(?:per.day|daily)|(?:daily|per.day).{0,50}free.model/i.test(message)) {
      return new Failure("provider_quota_daily", 429, retry);
    }
    if (headers.has("x-ratelimit-limit") || /free.model.{0,50}(?:per.minute|minute)/i.test(message)) {
      return new Failure("provider_quota", 429, retry);
    }
    return new Failure("provider_limit_unknown", 429, retry);
  }
  if (status === 403 && (/budget|spend.limit/i.test(type) || /budget limit exceeded/i.test(message))) {
    return new Failure("provider_budget", 403, retry);
  }
  const codes: Record<number, string> = {
    400: "model", 401: "provider_key", 402: "provider_balance", 403: "provider_access",
    404: "model", 408: "timeout", 504: "timeout",
  };
  return new Failure(codes[status] || "provider", status === 408 || status === 504 ? 504 : 502, retry);
}

export async function confirmedQuota(network: Network, key: string, signal: AbortSignal, failure: Failure): Promise<Failure> {
  if (failure.code !== "provider_quota" && failure.code !== "provider_limit_unknown") return failure;
  try {
    const response = await network("https://openrouter.ai/api/v1/key", {
      headers: { Authorization: "Bearer " + key }, redirect: "error",
      signal: AbortSignal.any([signal, AbortSignal.timeout(3000)]),
    });
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      return failure;
    }
    const data = await response.json();
    const daily = data?.data?.free_model_daily_requests;
    if (daily?.remaining === 0 && daily.limit > 0) {
      const midnight = new Date();
      midnight.setUTCHours(24, 0, 0, 0);
      return new Failure("provider_quota_daily", 429, Math.ceil((midnight.getTime() - Date.now()) / 1000));
    }
  } catch { /* Keep the upstream error if the optional quota check is unavailable. */ }
  return failure;
}

export function failureBody(failure: Failure): { error: string; retry_after?: number } {
  return { error: failure.code === "provider_limit_unknown" ? "provider_limit" : failure.code,
    ...(failure.retryAfter ? { retry_after: failure.retryAfter } : {}) };
}

function output(data: unknown, field: "delta" | "message"): string {
  if (!data || typeof data !== "object") throw new Failure("provider", 502);
  const value = data as { error?: ProviderError; choices?: Array<Record<string, unknown>> };
  if (value.error) throw providerFailure(Number(value.error.code), value.error);
  const choice = value.choices?.[0];
  if (choice?.finish_reason === "error") throw new Failure("provider", 502);
  const text = (choice?.[field] as { content?: unknown } | undefined)?.content;
  return typeof text === "string" ? text : "";
}

function connectionFailure(error: unknown): Failure {
  if (error instanceof Failure) return error;
  if (error instanceof Error && (error.name === "TimeoutError" || error.name === "AbortError")) {
    return new Failure("timeout", 504);
  }
  return new Failure("provider", 502);
}

export type ModelResponse = { response: Response; releaseDeadline: () => void };

export async function completion(
  network: Network, key: string, models: string[], messages: Turn[], stream: boolean, limit: number,
  signal: AbortSignal, device = false, repair = false, firstTokenMs = 18_000, quick = false,
): Promise<ModelResponse> {
  if (!key || !models.length || models.some(model => !/^[a-z0-9._-]+\/[a-z0-9._-]+:free$/.test(model))) {
    throw new Failure("unconfigured", 503);
  }
  const deadline = new AbortController();
  const timer = setTimeout(() => deadline.abort(), firstTokenMs);
  const releaseDeadline = () => clearTimeout(timer);
  try {
    const response = await network("https://openrouter.ai/api/v1/chat/completions", {
      method: "POST",
      redirect: "error",
      signal: AbortSignal.any([signal, deadline.signal]),
      headers: {
        Authorization: "Bearer " + key, "Content-Type": "application/json",
        "X-OpenRouter-Title": "CORTEX", Accept: stream ? "text/event-stream" : "application/json",
      },
      body: JSON.stringify({
        ...(models.length === 1 ? { model: models[0] } : { models }), messages, stream,
        max_tokens: limit, temperature: .42, top_p: .9,
        ...(device ? { tools: repair ? repairTools : deviceTools, tool_choice: repair ? "required" : "auto" } : {}),
        provider: {
          max_price: { prompt: 0, completion: 0 },
          ...(device ? { require_parameters: true } : {}), ...(quick ? { sort: "latency" } : {}),
        },
      }),
    });
    if (!response.ok) {
      const data = await response.json().catch(() => undefined);
      throw providerFailure(response.status, data?.error, response.headers);
    }
    return { response, releaseDeadline };
  } catch (error) {
    releaseDeadline();
    throw connectionFailure(error);
  }
}

export async function verifyCompletion(model: ModelResponse): Promise<void> {
  try {
    if (!output(await model.response.json(), "message").trim()) throw new Failure("empty", 502);
  } catch (error) {
    throw connectionFailure(error);
  } finally {
    model.releaseDeadline();
  }
}

type RelayControl = { reader?: ReadableStreamDefaultReader<Uint8Array> };

async function* events(response: Response, control: RelayControl, device: boolean, repair: boolean): AsyncGenerator<Record<string, unknown>> {
  const decision = device ? new DeviceDecision(repair) : undefined;
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  try {
    if (!response.headers.get("content-type")?.includes("text/event-stream")) {
      const data: unknown = await response.json();
      const text = output(data, "message").slice(0, OUTPUT_LIMIT);
      decision?.collect(data, "message");
      const outcome = decision?.outcome();
      if (outcome) yield outcome;
      else if (text.trim()) yield { delta: text };
      else throw new Failure("empty", 502);
      yield { done: true };
      return;
    }
    reader = response.body?.getReader();
    if (!reader) throw new Failure("provider", 502);
    control.reader = reader;
    const decoder = new TextDecoder();
    let buffer = "", prefix = "", length = 0, visible = false, complete = false;
    while (!complete) {
      const { done, value } = await reader.read();
      buffer += done ? decoder.decode() + "\n" : decoder.decode(value, { stream: true });
      if (buffer.length > 512_000) throw new Failure("provider", 502);
      let next: number;
      while ((next = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, next);
        buffer = buffer.slice(next + 1);
        if (!line.startsWith("data:")) continue;
        const event = line.slice(5).trim();
        if (event === "[DONE]") { complete = true; break; }
        if (!event) continue;
        const data: unknown = JSON.parse(event);
        decision?.collect(data, "delta");
        const delta = output(data, "delta").slice(0, OUTPUT_LIMIT - length);
        if (delta) {
          if (!visible && !delta.trim()) prefix = (prefix + delta).slice(0, 256);
          else {
            const visibleDelta = (prefix + delta).slice(0, OUTPUT_LIMIT - length);
            prefix = "";
            length += visibleDelta.length;
            visible = true;
            yield { delta: visibleDelta };
          }
        }
        if (length >= OUTPUT_LIMIT) { complete = true; break; }
      }
      if (done) break;
    }
    if (!complete) throw new Failure("provider", 502);
    const outcome = decision?.outcome();
    if (outcome) yield outcome;
    else if (!visible) throw new Failure("empty", 502);
    yield { done: true };
  } catch (error) {
    const failure = connectionFailure(error);
    yield { error: failure.code, ...(failure.retryAfter ? { retry_after: failure.retryAfter } : {}) };
  } finally {
    if (reader) {
      await reader.cancel().catch(() => {});
      reader.releaseLock();
      control.reader = undefined;
    } else if (!response.bodyUsed) {
      await response.body?.cancel().catch(() => {});
    }
  }
}

async function* stream(
  initial: ModelResponse, control: RelayControl, device: boolean, repair: boolean,
  fallback: (failure: Failure) => Promise<ModelResponse | undefined>, initialFallback: boolean,
) {
  let current = initial;
  if (initialFallback) yield { status: "fallback" };
  while (true) {
    let visible = false;
    let retry: Failure | undefined;
    try {
      for await (const event of events(current.response, control, device, repair)) {
        if (!visible && typeof event.error === "string" &&
          (retryableModelError(event.error) || event.error === "provider_quota" || event.error === "provider_quota_daily")) {
          retry = new Failure(event.error, 502, Number(event.retry_after) || 0);
          break;
        }
        if (!visible && (typeof event.delta === "string" || Object.hasOwn(event, "tool") ||
          Object.hasOwn(event, "tools") || Object.hasOwn(event, "tool_error"))) {
          visible = true;
          current.releaseDeadline();
        }
        if (event.error === "provider_limit_unknown") event.error = "provider_limit";
        yield event;
      }
    } finally {
      current.releaseDeadline();
    }
    if (!retry) return;
    try {
      const alternative = await fallback(retry);
      if (!alternative) { yield failureBody(retry); return; }
      current = alternative;
      yield { status: "fallback" };
    } catch (error) {
      yield failureBody(connectionFailure(error));
      return;
    }
  }
}

export function relay(
  initial: ModelResponse, fallback: (failure: Failure) => Promise<ModelResponse | undefined>,
  device = false, repair = false, initialFallback = false, abort?: () => void,
): ReadableStream<Uint8Array> {
  const upstream: RelayControl = {};
  const source = stream(initial, upstream, device, repair, fallback, initialFallback);
  const encoder = new TextEncoder();
  return new ReadableStream({
    async pull(controller) {
      const next = await source.next();
      if (next.done) controller.close();
      else controller.enqueue(encoder.encode("data: " + JSON.stringify(next.value) + "\n\n"));
    },
    async cancel() {
      abort?.();
      initial.releaseDeadline();
      await upstream.reader?.cancel().catch(() => {});
      await source.return(undefined);
      if (!initial.response.bodyUsed) await initial.response.body?.cancel().catch(() => {});
    },
  });
}

export function supportInput(payload: Record<string, unknown>, user: Account | null) {
  const { id = crypto.randomUUID(), kind, text, screenshot = "", email = "", diagnostics = false, app_version = "" } = payload;
  if (typeof id !== "string" || !TICKET_ID.test(id) ||
    typeof kind !== "string" || !SUPPORT_KINDS.has(kind) || typeof text !== "string" ||
    !text.trim() || text.length > 4000 || typeof screenshot !== "string" ||
    typeof email !== "string" || email.length > 254 || email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(email) ||
    typeof diagnostics !== "boolean" || typeof app_version !== "string" ||
    !/^[A-Za-z0-9.+-]{0,24}$/.test(app_version)) throw new Failure("invalid", 400);
  if (screenshot) {
    const image = /^data:(image\/(?:png|jpeg));base64,([A-Za-z0-9+/=]{1,685000})$/.exec(screenshot);
    if (!image) throw new Failure("attachment", 400);
    let data: string;
    try { data = atob(image[2]); } catch { throw new Failure("attachment", 400); }
    const prefix = image[1] === "image/png" ? "\x89PNG\r\n\x1a\n" : "\xff\xd8\xff";
    if (!data.startsWith(prefix) || data.length > 512_000) throw new Failure("attachment", 400);
  }
  return {
    id, category: kind, message: text.trim(), reply_to: email || user?.email || null,
    app_version: diagnostics ? app_version || null : null, screenshot: screenshot || null,
  };
}
