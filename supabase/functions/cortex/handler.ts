import {
  type Account, type Network, type ModelProfile, type ModelResponse, Failure, body, chatInput, completion,
  confirmedQuota, failureBody, relay, retryableModelError,
  supportInput, verifyCompletion,
} from "./provider.ts";
import { createControl } from "./control.ts";

const CAPABILITY = /^[A-Za-z0-9_-]{43}$/;
const PROJECT = /^[a-z0-9]{6,63}\.supabase\.co$/;
const PHOTO = new Set(["lh3.googleusercontent.com", "lh4.googleusercontent.com", "lh5.googleusercontent.com", "lh6.googleusercontent.com"]);
const FREE_MODEL = /^[a-z0-9._-]+\/[a-z0-9._-]+:free$/;
const JSON_HEADERS = {
  "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
};

type Env = Record<string, string | undefined>;
type Limit = { bucket: string; seconds: number; maximum: number };
type Login = { state: string; account?: Account };

function configuredKey(env: Env, dictionary: string, legacy: string): string {
  try {
    const current: unknown = JSON.parse(env[dictionary] || "{}").default;
    if (typeof current === "string" && current.trim()) return current.trim();
  } catch { /* The legacy keys remain valid for projects that still use them. */ }
  return env[legacy]?.trim() || "";
}

function origin(value: string | undefined, publicProject = false): string {
  if (!value) return "";
  try {
    const url = new URL(value);
    if (url.username || url.password || url.search || url.hash || url.pathname !== "/") return "";
    if (publicProject) return url.protocol === "https:" && PROJECT.test(url.hostname) && !url.port ? url.origin : "";
    return url.protocol === "https:" || url.protocol === "http:" && ["kong", "localhost", "127.0.0.1"].includes(url.hostname)
      ? url.origin : "";
  } catch { return ""; }
}

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}
function secret(): string { return base64url(crypto.getRandomValues(new Uint8Array(32))); }
async function hash(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  return base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)));
}
function json(value: unknown, status = 200): Response {
  return Response.json(value, { status, headers: JSON_HEADERS });
}
function browserResult(success: boolean): Response {
  const title = success ? "Вход выполнен" : "Вход не завершён";
  return new Response(
    '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<meta name="color-scheme" content="dark"><title>CORTEX</title>' +
    '<main style="max-width:32rem;margin:16vh auto;padding:24px;font:16px system-ui">' +
    "<p>CORTEX</p><h1>" + title + "</h1><p>Вернитесь в приложение CORTEX.</p></main></html>",
    { headers: {
      ...JSON_HEADERS, "Content-Type": "text/html; charset=utf-8",
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'",
    } },
  );
}

function bearer(request: Request): string {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") && CAPABILITY.test(value.slice(7)) ? value.slice(7) : "";
}

function apiHeaders(key: string): Headers {
  const headers = new Headers({ apikey: key, "Content-Type": "application/json" });
  if (key.split(".").length === 3) headers.set("Authorization", "Bearer " + key);
  return headers;
}

async function picture(network: Network, raw: unknown): Promise<string> {
  if (typeof raw !== "string" || raw.length > 1024) return "";
  try {
    const url = new URL(raw);
    if (url.protocol !== "https:" || !PHOTO.has(url.hostname) || url.username || url.password || url.port) return "";
    const result = await network(url, { redirect: "error", signal: AbortSignal.timeout(4000) });
    const mime = result.headers.get("content-type")?.split(";")[0];
    if (!result.ok || !mime || !["image/png", "image/jpeg", "image/webp"].includes(mime)) {
      await result.body?.cancel();
      return "";
    }
    const reader = result.body?.getReader();
    if (!reader) return "";
    const data = new Uint8Array(65_536);
    let offset = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        if (offset + value.byteLength > data.byteLength) return "";
        data.set(value, offset);
        offset += value.byteLength;
      }
    } finally {
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
    return offset ? "data:" + mime + ";base64," + btoa(String.fromCharCode(...data.subarray(0, offset))) : "";
  } catch { return ""; }
}

export function createHandler(env: Env, network: Network = fetch): (request: Request) => Promise<Response> {
  const api = origin(env.SUPABASE_URL);
  const publicApi = origin(env.CORTEX_SUPABASE_PUBLIC_URL || env.SUPABASE_URL, true);
  const publicKey = configuredKey(env, "SUPABASE_PUBLISHABLE_KEYS", "SUPABASE_ANON_KEY");
  const adminKey = configuredKey(env, "SUPABASE_SECRET_KEYS", "SUPABASE_SERVICE_ROLE_KEY");
  const environmentModelKey = env.OPENROUTER_API_KEY?.trim() || "";
  const models = [...new Set([
    (env.OPENROUTER_MODEL ?? "google/gemma-4-26b-a4b-it:free").trim(),
    (env.OPENROUTER_FALLBACK_MODEL ?? "nvidia/nemotron-3.5-super-vl:free").trim(),
    "cohere/north-mini-code:free",
  ].filter(model => FREE_MODEL.test(model)))];
  const profiles: Record<ModelProfile, string[]> = {
    auto: ["google/gemma-4-26b-a4b-it:free", "cohere/north-mini-code:free", "nvidia/nemotron-3.5-super-vl:free", "nvidia/nemotron-3-ultra-550b-a55b:free"],
    fast: ["nvidia/nemotron-3.5-lightning:free", "google/gemma-4-26b-a4b-it:free", "cohere/north-mini-code:free"],
    deep: ["nvidia/nemotron-3-ultra-550b-a55b:free", "google/gemma-4-31b-it:free", "cohere/north-mini-code:free", "nvidia/nemotron-3.5-super-vl:free"],
  };
  const daily = Math.max(1, Math.min(10_000, Math.floor(Number(env.CORTEX_DAILY_REQUEST_LIMIT) || 1000)));
  const allow = new Set((env.CORTEX_ALLOWED_EMAILS || "").split(",").map(value => value.trim().toLowerCase()).filter(Boolean));
  const guests = !allow.size && env.CORTEX_GUEST_ACCESS !== "false";
  const allowed = (email: string) => !allow.size || allow.has(email.toLowerCase());

  async function database<T>(path: string, method = "GET", payload?: unknown, preference = ""): Promise<T> {
    if (!api || !adminKey) throw new Failure("storage_config", 503);
    const headers = apiHeaders(adminKey);
    if (preference) headers.set("Prefer", preference);
    const response = await network(api + "/rest/v1/" + path, {
      method, headers, redirect: "error", signal: AbortSignal.timeout(9000),
      body: payload === undefined ? undefined : JSON.stringify(payload),
    });
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      if (response.status === 429) throw new Failure("rate_limit", 429);
      throw new Failure([401, 403, 404].includes(response.status) ? "storage_config" : "server", 503);
    }
    const text = await response.text();
    return (text ? JSON.parse(text) : null) as T;
  }

  let providerCache: { value: Promise<string>; until: number } | undefined;
  function providerKey(): Promise<string> {
    if (environmentModelKey) return Promise.resolve(environmentModelKey);
    if (providerCache && providerCache.until > Date.now()) return providerCache.value;
    const value = database<string | null>("rpc/cortex_provider_key", "POST", {}).then(key => key?.trim() || "");
    providerCache = { value, until: Date.now() + 30_000 };
    void value.catch(() => { if (providerCache?.value === value) providerCache = undefined; });
    return value;
  }

  const control = createControl(database);
  let catalog: { value: Promise<string[]>; until: number } | undefined;
  function freeCatalog(): Promise<string[]> {
    if (catalog && catalog.until > Date.now()) return catalog.value;
    const value = network("https://openrouter.ai/api/v1/models", {
      redirect: "error", signal: AbortSignal.timeout(3500),
    }).then(async response => {
      if (!response.ok) throw new Error("model_catalog");
      const { data } = await response.json();
      if (!Array.isArray(data)) return [];
      return data.filter(item => typeof item.id === "string" && FREE_MODEL.test(item.id) &&
        String(item.pricing?.prompt) === "0" && String(item.pricing?.completion) === "0" &&
        item.architecture?.output_modalities?.includes("text") && item.supported_parameters?.includes("tools"))
        .slice(0, 12).map(item => String(item.id));
    }).catch(() => []);
    catalog = { value, until: Date.now() + 600_000 };
    return value;
  }

  const modelCooldown = new Map<string, number>();
  let quotaBlock: { key: string; failure: Failure; until: number } | undefined;
  function blockQuota(key: string, failure: Failure) {
    if (!["provider_quota_daily", "provider_quota"].includes(failure.code)) return;
    const midnight = new Date();
    midnight.setUTCHours(24, 0, 0, 0);
    const seconds = failure.retryAfter || (failure.code === "provider_quota_daily"
      ? Math.ceil((midnight.getTime() - Date.now()) / 1000) : 30);
    quotaBlock = { key, failure, until: Date.now() + seconds * 1000 };
  }

  let readyCache = { value: false, until: 0 };
  async function ready(): Promise<boolean> {
    if (readyCache.until > Date.now()) return readyCache.value;
    let value = false;
    try { await database("cortex_sessions?select=token_hash&limit=0"); value = true; } catch { /* Report as configuration. */ }
    readyCache = { value, until: Date.now() + 8000 };
    return value;
  }

  async function auth<T>(path: string, payload?: unknown): Promise<T> {
    if (!api || !publicKey) throw new Failure("account_config", 503);
    const response = await network(api + "/auth/v1/" + path, {
      method: payload === undefined ? "GET" : "POST",
      headers: apiHeaders(publicKey), redirect: "error", signal: AbortSignal.timeout(12_000),
      body: payload === undefined ? undefined : JSON.stringify(payload),
    });
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      throw new Failure("auth_denied", 403);
    }
    return await response.json() as T;
  }

  let googleCache = { value: false, until: 0 };
  async function google(): Promise<boolean> {
    if (!publicApi || !publicKey) return false;
    if (googleCache.until > Date.now()) return googleCache.value;
    let value = false;
    try { value = (await auth<{ external?: { google?: boolean } }>("settings")).external?.google === true; } catch { /* Provider is unavailable. */ }
    googleCache = { value, until: Date.now() + 8000 };
    return value;
  }

  const reserve = (...rules: Limit[]) => database("rpc/cortex_reserve", "POST", { p_limits: rules });
  const expires = () => encodeURIComponent(new Date().toISOString());
  async function peer(request: Request): Promise<string> {
    const address = request.headers.get("cf-connecting-ip") || request.headers.get("x-forwarded-for")?.split(",")[0] || "unknown";
    return (await hash(address.trim().slice(0, 64))).slice(0, 18);
  }

  async function lookup(request: Request): Promise<Account | null> {
    const token = bearer(request);
    if (!token) return null;
    const rows = await database<Array<{ account: Account }>>(
      "cortex_sessions?select=account&limit=1&token_hash=eq." + await hash(token) + "&expires_at=gt." + expires(),
    );
    const account = rows[0]?.account;
    return account && allowed(account.email) ? account : null;
  }

  async function actor(request: Request): Promise<{ account: Account | null; bucket: string; ip: string }> {
    const account = await lookup(request);
    if (account) return { account, bucket: "account:" + account.id, ip: "" };
    if (!guests) throw new Failure("sign_in", 401);
    const install = request.headers.get("x-cortex-install") || "";
    if (!CAPABILITY.test(install)) throw new Failure("client_version", 400);
    return { account: null, bucket: "guest:" + await hash(install), ip: await peer(request) };
  }

  async function installIdentity(request: Request): Promise<string | null> {
    const raw = request.headers.get("x-cortex-install") || "";
    return CAPABILITY.test(raw) ? await hash(raw) : null;
  }

  async function ticketOwners(request: Request, account: Account | null): Promise<string[]> {
    const install = await installIdentity(request);
    const owners: string[] = [];
    if (account) owners.push("account_" + await hash(account.id));
    if (install) owners.push("install_" + install);
    return owners;
  }

  function budget(caller: Awaited<ReturnType<typeof actor>>): Promise<unknown> {
    return reserve(
      { bucket: "chat:" + caller.bucket, seconds: 60, maximum: caller.account ? 16 : 12 },
      { bucket: "chat:all", seconds: 86_400, maximum: daily },
      ...(caller.account ? [] : [
        { bucket: "chat:ip:" + caller.ip, seconds: 60, maximum: 20 },
        { bucket: "chat:guest-day:" + caller.bucket, seconds: 86_400, maximum: 200 },
        { bucket: "chat:guest-all", seconds: 86_400, maximum: Math.min(daily, 900) },
      ]),
    );
  }

  async function begin(request: Request): Promise<Response> {
    if (!await ready()) throw new Failure("storage_config", 503);
    if (!await google()) throw new Failure("account_config", 503);
    await reserve(
      { bucket: "login:" + await peer(request), seconds: 300, maximum: 10 },
      { bucket: "login:all", seconds: 60, maximum: 90 },
    );
    const state = secret(), poll = secret(), verifier = secret();
    await database("rpc/cortex_begin_login", "POST", {
      p_state_hash: await hash(state), p_poll_hash: await hash(poll), p_verifier: verifier,
    });
    const url = new URL(publicApi + "/auth/v1/authorize");
    url.search = new URLSearchParams({
      provider: "google",
      redirect_to: publicApi + "/functions/v1/cortex/v1/auth/callback/" + state,
      code_challenge: await hash(verifier),
      code_challenge_method: "s256",
      prompt: "select_account",
    }).toString();
    return json({ url: url.toString(), poll, expires_in: 300 });
  }

  async function callback(state: string, code: string): Promise<Response> {
    if (!CAPABILITY.test(state)) return browserResult(false);
    const filter = "cortex_auth_flows?state_hash=eq." + await hash(state) + "&expires_at=gt." + expires();
    const claims = await database<Array<{ verifier: string }>>(
      filter + "&stage=eq.pending", "PATCH", { stage: "exchanging" }, "return=representation",
    );
    const claim = claims[0];
    if (!claim) return browserResult(false);
    let stage = "denied";
    let account: Account | null = null;
    if (code && code.length <= 2048) {
      try {
        const { user } = await auth<{ user?: {
          id?: string; email?: string; email_confirmed_at?: string;
          app_metadata?: { provider?: string };
          identities?: Array<{ provider?: string; identity_data?: { email?: string } }>;
          user_metadata?: { full_name?: string; name?: string; avatar_url?: string; picture?: string };
        } }>("token?grant_type=pkce", { auth_code: code, code_verifier: claim.verifier });
        const email = user?.email;
        const googleIdentity = user?.app_metadata?.provider === "google" ||
          user?.identities?.some(identity => identity.provider === "google" &&
            identity.identity_data?.email?.toLowerCase() === email?.toLowerCase());
        if (typeof user?.id === "string" && user.id && typeof email === "string" && email &&
          typeof user.email_confirmed_at === "string" && googleIdentity) {
          if (!allowed(email)) stage = "forbidden";
          else {
            const info = user.user_metadata || {};
            account = {
              id: user.id.slice(0, 200), email: email.slice(0, 254), provider: "Google",
              name: String(info.full_name || info.name || email.split("@")[0]).slice(0, 100),
              avatar: await picture(network, info.avatar_url || info.picture),
            };
            stage = "approved";
          }
        }
      } catch { /* The application receives a clean sign-in error. */ }
    }
    const saved = await database<Array<unknown>>(
      filter + "&stage=eq.exchanging", "PATCH", { stage, account, verifier: "" }, "return=representation",
    );
    return browserResult(stage === "approved" && saved.length === 1);
  }

  async function poll(request: Request): Promise<Response> {
    const input = await body(request, 2048);
    if (typeof input.code !== "string" || !CAPABILITY.test(input.code)) throw new Failure("auth_expired", 410);
    await reserve(
      { bucket: "poll:" + await peer(request), seconds: 60, maximum: 90 },
      { bucket: "poll:all", seconds: 60, maximum: 1200 },
    );
    const token = secret();
    const result = await database<Login>("rpc/cortex_finish_login", "POST", {
      p_poll_hash: await hash(input.code), p_token_hash: await hash(token),
    });
    if (result.state === "expired") throw new Failure("auth_expired", 410);
    if (result.state === "denied") throw new Failure("auth_denied", 403);
    if (result.state === "forbidden") throw new Failure("account_forbidden", 403);
    return result.state === "signed_in" ? json({ ...result, token }) : json({ state: "pending" });
  }

  return async request => {
    const url = new URL(request.url);
    const path = url.pathname.replace(/^(?:\/functions\/v1)?\/cortex(?=\/)/, "");
    const method = request.method;
    const callbackPath = "/v1/auth/callback/";
    const isCallback = method === "GET" && path.startsWith(callbackPath);
    try {
      if (method === "GET" && path === "/v1/status") {
        const storage = await ready();
        const [account, googleReady, key] = storage
          ? await Promise.all([lookup(request), google(), providerKey().catch(() => "")])
          : [null, false, ""];
        return json({
          service: "CORTEX", configured: Boolean(storage && key), provider: "openrouter_free",
          google: googleReady, account, guest: guests, support: storage, device_tools: true,
          device_profiles: true, device_repair: true, model_fallback: true,
          ...(storage ? { gate: await control.gate(request, account?.id) } : { fault: "storage_config" }),
        });
      }
      if (method === "GET" && path === "/v1/support/inbox") {
        const account = await lookup(request);
        return json(await control.inbox(await ticketOwners(request, account)));
      }
      if (method === "POST" && path === "/v1/presence") {
        const install = await installIdentity(request);
        if (!install) throw new Failure("client_version", 400);
        const input = await body(request, 2048);
        const account = await lookup(request);
        await reserve({ bucket: "presence:" + install, seconds: 60, maximum: 4 });
        const version = request.headers.get("x-cortex-version-name") || "";
        await control.presence(install, input, /^[A-Za-z0-9.+-]{1,24}$/.test(version) ? version : "unknown");
        return json({ gate: await control.gate(request, account?.id) });
      }
      if (method === "POST" && path === "/v1/auth/start") return await begin(request);
      if (isCallback) return await callback(path.slice(callbackPath.length), url.searchParams.get("code") || "");
      if (method === "POST" && path === "/v1/auth/poll") return await poll(request);
      if (method === "DELETE" && path === "/v1/session") {
        const token = bearer(request);
        if (token) await database("cortex_sessions?token_hash=eq." + await hash(token), "DELETE");
        return json({ ok: true });
      }
      if (method === "POST" && (path === "/v1/chat" || path === "/v1/check")) {
        const caller = await actor(request);
        const isChat = path === "/v1/chat";
        const input = isChat ? chatInput(await body(request, 260_000)) : {
          messages: [{ role: "user", content: "Reply with just OK." }] as const, limit: 512, device: false, repair: false, profile: undefined,
        };
        const gate = await control.gate(request, caller.account?.id);
        if (gate.mode !== "open") throw new Failure(gate.mode, 403);
        const key = await providerKey().catch(() => "");
        if (!key) throw new Failure("unconfigured", 503);
        if (quotaBlock?.key === key && quotaBlock.until > Date.now()) {
          const { failure, until } = quotaBlock;
          throw new Failure(failure.code, failure.status, Math.ceil((until - Date.now()) / 1000));
        }
        const preferred = input.repair ? profiles.auto : input.profile ? profiles[input.profile] : models;
        const candidates = [...new Set([...preferred, ...models])];
        await budget(caller);
        const upstream = new AbortController();
        const quick = input.profile === "fast";
        const firstTokenMs = input.profile === "deep" ? 18_000 : quick ? 9_000 : 12_000;
        const signal = AbortSignal.any([request.signal, upstream.signal, AbortSignal.timeout(quick ? 60_000 : 87_000)]);
        const scope = (input.profile || "default") + (input.device ? ":tools:" : ":chat:");
        const choices = [...new Set(candidates)];
        const ready = choices.filter(model => (modelCooldown.get(scope + model) || 0) <= Date.now());
        const ordered = ready.length ? ready : choices.slice(0, 1);
        let catalogChecked = false;
        const restModel = (model: string, failure: Failure) => {
          if (!["timeout", "provider_limit", "provider_quota", "provider_quota_daily", "provider", "empty", "model", "provider_key", "provider_access"].includes(failure.code)) return;
          const seconds = failure.code === "model" ? 90 : Math.min(60, Math.max(18, failure.retryAfter));
          modelCooldown.set(scope + model, Date.now() + seconds * 1000);
        };
        let lastModel = "";
        let next = 0;
        let checkedDaily = false;
        const examine = async (failure: Failure): Promise<Failure> => {
          if (!checkedDaily && ["provider_quota", "provider_limit_unknown"].includes(failure.code)) {
            checkedDaily = true;
            failure = await confirmedQuota(network, key, signal, failure);
          }
          blockQuota(key, failure);
          return failure;
        };
        const canTryNext = (failure: Failure) => retryableModelError(failure.code);
        const getResponse = async (previous?: Failure): Promise<ModelResponse | undefined> => {
          let failure = previous && await examine(previous);
          if (failure && !canTryNext(failure)) throw failure;
          if (failure && lastModel) restModel(lastModel, failure);
          while (!signal.aborted) {
            if (next >= ordered.length) {
              if (catalogChecked) break;
              catalogChecked = true;
              const live = await freeCatalog();
              ordered.push(...live.filter(model => !choices.includes(model) &&
                (modelCooldown.get(scope + model) || 0) <= Date.now()).slice(0, 3));
              if (next >= ordered.length) break;
            }
            const model = ordered[next++];
            lastModel = model;
            try {
              if (quotaBlock?.key === key && quotaBlock.until > Date.now()) {
                const { failure, until } = quotaBlock;
                throw new Failure(failure.code, failure.status, Math.ceil((until - Date.now()) / 1000));
              }
              const result = await completion(network, key, [model], [...input.messages], isChat, input.limit, signal,
                input.device, input.repair, firstTokenMs, quick);
              modelCooldown.delete(scope + model);
              return result;
            } catch (error) {
              if (!(error instanceof Failure)) throw error;
              failure = await examine(error);
              if (!canTryNext(failure)) throw failure;
              restModel(model, failure);
              console.warn("CORTEX provider candidate rejected:", model, failure.code);
            }
          }
          if (signal.aborted) throw new Failure("timeout", 504);
          if (failure) throw failure;
        };
        let response = await getResponse();
        if (!response) throw new Failure("model", 502);
        if (!isChat) {
          while (response) {
            try { await verifyCompletion(response); return json({ connected: true }); }
            catch (error) {
              if (!(error instanceof Failure)) throw error;
              response = await getResponse(error);
            }
          }
          throw new Failure("model", 502);
        }
        return new Response(relay(response, getResponse, input.device, input.repair, next > 1, () => upstream.abort()), {
          headers: { ...JSON_HEADERS, "Content-Type": "text/event-stream; charset=utf-8", "X-Accel-Buffering": "no" },
        });
      }
      if (method === "POST" && path === "/v1/support") {
        const caller = await actor(request);
        const ticket = supportInput(await body(request), caller.account);
        const [owner] = await ticketOwners(request, caller.account);
        if (!owner) throw new Failure("client_version", 400);
        await reserve(
          { bucket: "support:" + caller.bucket, seconds: 3600, maximum: 5 },
          { bucket: "support:all", seconds: 86_400, maximum: 50 },
          ...(caller.account ? [] : [{ bucket: "support:ip:" + caller.ip, seconds: 3600, maximum: 8 }]),
        );
        await database("cortex_support?on_conflict=id", "POST", { ...ticket, device_hash: owner }, "resolution=ignore-duplicates");
        const receipt = await database<Array<{ id: string }>>(
          "cortex_support?select=id&id=eq." + ticket.id + "&device_hash=eq." + owner + "&limit=1",
        );
        if (!receipt.length) throw new Failure("conflict", 409);
        return json({ sent: true, reference: ticket.id.slice(0, 8).toUpperCase() });
      }
      return json({ error: "endpoint" }, 404);
    } catch (error) {
      if (isCallback) return browserResult(false);
      if (error instanceof Failure) return json(failureBody(error), error.status);
      if (error instanceof Error && ["TimeoutError", "AbortError"].includes(error.name)) return json({ error: "timeout" }, 504);
      console.warn("CORTEX gateway request failed");
      return json({ error: "server" }, 503);
    }
  };
}
