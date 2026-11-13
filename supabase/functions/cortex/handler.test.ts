import test from "node:test";
import assert from "node:assert/strict";
import { createHandler } from "./handler.ts";
import { createControl } from "./control.ts";

const install = "A".repeat(43);
const otherInstall = "B".repeat(43);
const accountId = "98d87a12-240b-4a57-95e0-7bc0bbbd0f42";
const ticketId = "2f343286-09bb-4e8e-9aa3-21ecc664219b";
const replyId = "1f8188b2-9176-4be1-b0cf-8d9ef890eaa0";

function fixture(maintenance = false, streamedReply?: () => Response) {
  let ticket: Record<string, unknown> | null = null;
  let login: Record<string, unknown> | null = null;
  const models: string[] = [];
  const requests: string[] = [];
  const app = createHandler({
    SUPABASE_URL: "https://examplecortex.supabase.co",
    SUPABASE_ANON_KEY: "test-publishable",
    SUPABASE_SERVICE_ROLE_KEY: "test-service",
    OPENROUTER_API_KEY: "test-key",
    OPENROUTER_MODEL: "example/unavailable:free",
    OPENROUTER_FALLBACK_MODEL: "example/working:free",
  }, async (input, init) => {
    const url = new URL(typeof input === "string" || input instanceof URL ? input : input.url);
    const method = init?.method ?? "GET";
    const body = init?.body ? JSON.parse(String(init.body)) : {};
    requests.push(method + " " + url.pathname);

    if (url.hostname === "openrouter.ai" && url.pathname === "/api/v1/chat/completions") {
      assert.equal(body.provider.max_price.prompt, 0);
      assert.equal(body.provider.max_price.completion, 0);
      assert.match(body.model, /:free$/);
      models.push(body.model);
      if (body.model === "example/unavailable:free") {
        return Response.json({ error: { metadata: { provider_name: "upstream" } } }, { status: 429 });
      }
      if (body.stream) {
        if (streamedReply) return streamedReply();
        return new Response('data: {"choices":[{"delta":{"content":"OK"}}]}\n\ndata: [DONE]\n\n',
          { headers: { "content-type": "text/event-stream" } });
      }
      return Response.json({ choices: [{ message: { content: "OK" } }] });
    }
    switch (url.pathname) {
      case "/rest/v1/cortex_control_config":
        return Response.json([{ maintenance_enabled: maintenance, maintenance_message: "Обновление", block_old_versions: false }]);
      case "/rest/v1/cortex_sessions":
        return Response.json(url.searchParams.has("expires_at") ? [
          { account: { id: accountId, email: "admin@example.test", name: "QA", provider: "Google", avatar: "" } },
        ] : []);
      case "/rest/v1/cortex_release_versions": return Response.json([]);
      case "/rest/v1/cortex_admin_roles": return Response.json([{ role: "admin" }]);
      case "/auth/v1/settings": return Response.json({ external: { google: true } });
      case "/rest/v1/rpc/cortex_begin_login":
        login = body;
        return Response.json(null);
      case "/rest/v1/cortex_auth_flows":
        assert.ok(login);
        assert.equal(url.searchParams.get("state_hash"), "eq." + login.p_state_hash);
        return Response.json(body.stage === "exchanging" ? [{ verifier: login.p_verifier }] : [{ stage: body.stage }]);
      case "/auth/v1/token":
        assert.equal(url.searchParams.get("grant_type"), "pkce");
        assert.equal(body.code_verifier, login?.p_verifier);
        assert.equal(body.auth_code, "test-google-code");
        return Response.json({ user: { id: accountId, email: "admin@example.test",
          email_confirmed_at: "2026-11-13T12:00:00.000Z", app_metadata: { provider: "google" } } });
      case "/rest/v1/rpc/cortex_reserve":
      case "/rest/v1/cortex_installs":
      case "/rest/v1/cortex_app_sessions": return Response.json(null);
      case "/rest/v1/cortex_support":
        if (method === "POST") {
          if (!ticket) ticket = body;
          return Response.json(null);
        }
        if (!ticket) return Response.json([]);
        if (url.searchParams.get("device_hash")?.includes(String(ticket.device_hash))) {
          return Response.json([ticket]);
        }
        return Response.json([]);
      case "/rest/v1/cortex_support_replies":
        assert.equal(url.searchParams.get("ticket_id"), "in.(" + ticketId + ")");
        return Response.json([{ id: replyId, ticket_id: ticketId, message: "Получили ваше сообщение." }]);
      default: throw new Error("Unexpected endpoint " + url.pathname);
    }
  });
  function call(path: string, payload?: unknown, headers: Record<string, string> = {}) {
    return app(new Request("https://examplecortex.supabase.co/functions/v1/cortex/v1/" + path, {
      method: payload === undefined ? "GET" : "POST",
      headers: { "x-cortex-install": install, "x-cortex-version-code": "100", "x-cortex-version-name": "1.0.0", ...headers },
      body: payload === undefined ? undefined : JSON.stringify(payload),
    }));
  }
  return { call, models, requests };
}

test("Android Google callback uses PKCE and returns to the app without a bearer token in the URL", async () => {
  const { call } = fixture();
  const start = await call("auth/start?return=android", {});
  assert.equal(start.status, 200);
  const { url, poll } = await start.json();
  assert.match(poll, /^[A-Za-z0-9_-]{43}$/);
  const authorize = new URL(url);
  assert.equal(authorize.searchParams.get("provider"), "google");
  assert.equal(authorize.searchParams.get("code_challenge_method"), "s256");
  const callback = new URL(String(authorize.searchParams.get("redirect_to")));
  assert.equal(callback.protocol, "https:");
  assert.match(callback.pathname, /\/auth\/callback\/android-[A-Za-z0-9_-]{43}$/);
  const returned = await call("auth/callback/" + callback.pathname.split("/").at(-1) + "?code=test-google-code");
  assert.equal(returned.status, 303);
  assert.equal(returned.headers.get("location"), "app.cortex.assistant://oauth/return");
  assert.match(await returned.text(), /Вход выполнен/);
});

test("Google callback routes to the requesting preview and rejects arbitrary schemes", async () => {
  const { call } = fixture();
  assert.equal((await call("auth/start?return=android&scheme=another.app", {})).status, 400);
  const scheme = "app.cortex.assistant.preview.v103";
  const start = await call("auth/start?return=android&scheme=" + scheme, {});
  assert.equal(start.status, 200);
  const authorize = new URL((await start.json()).url);
  const callback = new URL(String(authorize.searchParams.get("redirect_to")));
  const path = callback.pathname.split("/").at(-1);
  assert.match(path ?? "", /^android-v103-[A-Za-z0-9_-]{43}$/);
  const returned = await call("auth/callback/" + path + "?code=test-google-code");
  assert.equal(returned.headers.get("location"), scheme + "://oauth/return");
});

test("retries a rate-limited provider with a different :free model", async () => {
  const { call, models } = fixture();
  const response = await call("check", {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { connected: true });
  assert.deepEqual(models, ["example/unavailable:free", "example/working:free"]);
});

test("support submission persists its owner and only that installation can read replies", async () => {
  const { call } = fixture();
  const input = { id: ticketId, kind: "complaint", text: "Проверка доставки" };
  assert.equal((await call("support", input)).status, 200);
  assert.equal((await call("support", input)).status, 200);
  assert.equal((await call("support", input, { "x-cortex-install": otherInstall })).status, 409);
  const owner = await (await call("support/inbox")).json();
  assert.equal(owner.tickets[0].id, ticketId);
  assert.equal(owner.replies[0].id, replyId);
  assert.deepEqual(await (await call("support/inbox", undefined, { "x-cortex-install": otherInstall })).json(),
    { tickets: [], replies: [] });
});

test("maintenance blocks ordinary chat and permits an authenticated admin", async () => {
  const { call, models } = fixture(true);
  assert.equal((await (await call("status")).json()).gate.mode, "maintenance");
  assert.equal((await call("check", {})).status, 403);
  assert.equal(models.length, 0);
  const token = { Authorization: "Bearer " + "T".repeat(43) };
  assert.equal((await (await call("status", undefined, token)).json()).gate.mode, "open");
  assert.equal((await call("check", {}, token)).status, 200);
});

test("presence validates identifiers and records both install and session", async () => {
  const { call, requests } = fixture();
  assert.equal((await call("presence", { session_id: "bad", screen: "home" })).status, 400);
  const response = await call("presence", { session_id: replyId, screen: "chat" });
  assert.equal(response.status, 200);
  assert.ok(requests.includes("POST /rest/v1/cortex_installs"));
  assert.ok(requests.includes("PATCH /rest/v1/cortex_app_sessions"));
});

test("an older APK gets a verified HTTPS update link", async () => {
  const control = createControl(async <T>() => [{
    maintenance_enabled: false, block_old_versions: true, min_version_code: 100,
    update_url: "https://github.com/example/CORTEX/releases/latest",
  }] as T);
  const gate = await control.gate(new Request("https://example.test", { headers: { "x-cortex-version-code": "92" } }));
  assert.equal(gate.mode, "update");
  assert.match(gate.update_url!, /^https:\/\//);
});

function chatPayload(text: string, tools = true) {
  return { messages: [{ role: "user", content: text }], style: "brief", device_tools: tools };
}

function sseReply(frames: Array<object | "[DONE]">, keepOpen = false): Response {
  const encoder = new TextEncoder();
  return new Response(new ReadableStream({
    start(controller) {
      for (const frame of frames) {
        controller.enqueue(encoder.encode("data: " + (typeof frame === "string" ? frame : JSON.stringify(frame)) + "\n\n"));
      }
      if (!keepOpen) controller.close();
    },
    cancel() { if (keepOpen) return new Promise<void>(() => {}); },
  }), { headers: { "content-type": "text/event-stream" } });
}

test("ordinary chat completes with device tools enabled", async () => {
  const { call } = fixture();
  const response = await call("chat", chatPayload("Привет"));
  assert.equal(response.status, 200);
  assert.match(await response.text(), /data: {"delta":"OK"}\n\ndata: \[DONE\]\n\n$/);
});

test("streams a validated Android tool call and closes before waiting for upstream cleanup", { timeout: 2000 }, async () => {
  const { call } = fixture(false, () => sseReply([
    { choices: [{ delta: { tool_calls: [{ index: 0, type: "function", function: { name: "inspect_phone", arguments: '{"target":' } }] } }] },
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: '"battery"}' } }] }, finish_reason: "tool_calls" }] },
    "[DONE]",
  ], true));
  const response = await call("chat", chatPayload("Проверь заряд телефона"));
  assert.equal(response.status, 200);
  assert.match(await response.text(), /data: {"tool":{"kind":"read_device","target":"battery"}}\n\ndata: \[DONE\]\n\n$/);
});

test("preserves a mixed Android plan in provider order", async () => {
  const steps = [
    { kind: "torch", mode: "on" },
    { kind: "screen_task", target: "Any installed app", goal: "Open the requested page" },
    { kind: "torch", mode: "off" },
    { kind: "close_app", target: "Any installed app" },
    { kind: "capture_photo", lens: "front" },
    { kind: "lock_screen" },
  ];
  const args = JSON.stringify({ steps });
  const { call } = fixture(false, () => sseReply([
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { name: "execute_phone_plan", arguments: args.slice(0, 37) } }] } }] },
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: args.slice(37) } }] } }] },
    "[DONE]",
  ]));
  const response = await call("chat", { ...chatPayload("Выполни цепочку действий"), action_plan: true });
  const data = (await response.text()).split("\n").find(line => line.startsWith('data: {"tools":'));
  assert.ok(data);
  assert.deepEqual(JSON.parse(data.slice(6)).tools, steps);
});

test("older clients cannot receive a screen-task tool", async () => {
  const { call } = fixture(false, () => sseReply([
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { name: "execute_phone_plan", arguments: '{"steps":[{"kind":"screen_task","target":"Example","goal":"Open the list"}]}' } }] } }] },
    "[DONE]",
  ]));
  const response = await call("chat", chatPayload("Open the list"));
  assert.match(await response.text(), /data: {"tool_error":"tool_arguments"}/);
});

test("screen task includes opening its app and preserves following actions", async () => {
  const steps = [
    { kind: "app", target: "Browser" }, { kind: "screen_task", target: "browser", goal: "Find books" },
    { kind: "app", target: "YouTube", section: "shorts" }, { kind: "lock_screen" },
  ];
  const { call } = fixture(false, () => sseReply([
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { name: "execute_phone_plan", arguments: JSON.stringify({ steps }) } }] } }] },
    "[DONE]",
  ]));
  const response = await call("chat", { ...chatPayload("Выполни задачу"), action_plan: true });
  const data = (await response.text()).split("\n").find(line => line.startsWith('data: {"tools":'));
  assert.ok(data);
  assert.deepEqual(JSON.parse(data.slice(6)).tools, [steps[1], { kind: "screen_task", target: "YouTube", goal: "Открой раздел shorts" }, steps[3]]);
});

test("rejects a whole plan with an invalid later step", async () => {
  const { call } = fixture(false, () => sseReply([
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { name: "execute_phone_plan", arguments: '{"steps":[{"kind":"torch","mode":"on"},{"kind":"screen_task","target":"Example"}]}' } }] } }] },
    "[DONE]",
  ]));
  const response = await call("chat", { ...chatPayload("Выполни задачу"), action_plan: true });
  const data = await response.text();
  assert.match(data, /data: {"tool_error":"tool_arguments"}/);
  assert.doesNotMatch(data, /data: {"tools?":/);
});

test("delivers an explicit error after interrupted output and ends the stream", { timeout: 2000 }, async () => {
  const { call } = fixture(false, () => sseReply([
    { choices: [{ delta: { content: "Начало ответа" } }] },
    { error: { code: 502 } },
  ], true));
  const response = await call("chat", chatPayload("Привет", false));
  const stream = await response.text();
  assert.match(stream, /data: {"delta":"Начало ответа"}/);
  assert.match(stream, /data: {"error":"provider"}\n\n$/);
});
