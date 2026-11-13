import test from "node:test";
import assert from "node:assert/strict";
import { createHandler } from "./handler.ts";
import { createControl } from "./control.ts";

const install = "A".repeat(43);
const otherInstall = "B".repeat(43);
const accountId = "98d87a12-240b-4a57-95e0-7bc0bbbd0f42";
const ticketId = "2f343286-09bb-4e8e-9aa3-21ecc664219b";
const replyId = "1f8188b2-9176-4be1-b0cf-8d9ef890eaa0";

function fixture(maintenance = false) {
  let ticket: Record<string, unknown> | null = null;
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
