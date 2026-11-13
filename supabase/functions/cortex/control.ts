import { Failure } from "./provider.ts";

export type Database = <T>(path: string, method?: string, payload?: unknown, preference?: string) => Promise<T>;

type Gate = { mode: "open" | "maintenance" | "update"; message?: string; update_url?: string };
type ControlConfig = {
  maintenance_enabled: boolean;
  maintenance_message: string;
  block_old_versions: boolean;
  min_version_code: number;
  update_url: string;
};

const OPEN: Gate = { mode: "open" };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const VALID_VERSION = /^[1-9][0-9]{0,9}$/;

export function createControl(database: Database) {
  let config: { value: Promise<ControlConfig | undefined>; until: number } | undefined;
  const versions = new Map<number, { value: Promise<boolean>; until: number }>();

  function blocked(code: number): Promise<boolean> {
    if (code <= 0 || code > 2_100_000_000) return Promise.resolve(false);
    const old = versions.get(code);
    if (old && old.until > Date.now()) return old.value;
    const value = database<Array<{ access: string }>>("cortex_release_versions?select=access&version_code=eq." + code + "&limit=1")
      .then(rows => rows[0]?.access === "blocked");
    if (versions.size >= 24) versions.clear();
    versions.set(code, { value, until: Date.now() + 8000 });
    void value.catch(() => { if (versions.get(code)?.value === value) versions.delete(code); });
    return value;
  }

  function configuration() {
    if (config && config.until > Date.now()) return config.value;
    const value = database<ControlConfig[]>(
      "cortex_control_config?select=maintenance_enabled,maintenance_message,block_old_versions,min_version_code,update_url&singleton=eq.true&limit=1",
    ).then(rows => rows[0]);
    config = { value, until: Date.now() + 5000 };
    void value.catch(() => { if (config?.value === value) config = undefined; });
    return value;
  }

  async function gate(request: Request, accountId?: string): Promise<Gate> {
    const settings = await configuration();
    if (!settings) return OPEN;
    const clientVersion = request.headers.get("x-cortex-version-code") || "";
    const versionCode = VALID_VERSION.test(clientVersion) ? Number(clientVersion) : 0;
    const needsUpdate = (settings.block_old_versions && versionCode < settings.min_version_code) ||
      (!settings.maintenance_enabled && await blocked(versionCode));
    if (!settings.maintenance_enabled && !needsUpdate) return OPEN;

    if (accountId && UUID.test(accountId)) {
      const roles = await database<Array<{ role: string }>>(
        "cortex_admin_roles?select=role&user_id=eq." + accountId + "&limit=1",
      );
      if (["owner", "admin", "support"].includes(roles[0]?.role ?? "")) return OPEN;
    }
    if (settings.maintenance_enabled) {
      return { mode: "maintenance", message: settings.maintenance_message || "CORTEX временно недоступен." };
    }
    return { mode: "update", message: "Установите актуальную версию CORTEX.", update_url: httpsUrl(settings.update_url) };
  }

  async function presence(install: string, input: Record<string, unknown>, version: string) {
    const session = input.session_id;
    const screen = input.screen;
    if (typeof session !== "string" || !UUID.test(session) || typeof screen !== "string" ||
        !/^[a-z][a-z-]{0,39}$/.test(screen)) throw new Failure("invalid", 400);
    const time = new Date().toISOString();
    await database("cortex_installs?on_conflict=install_hash", "POST", {
      install_hash: install, last_seen_at: time, app_version: version, platform: "android", last_screen: screen,
    }, "resolution=merge-duplicates");
    await database("cortex_app_sessions?on_conflict=id", "POST", {
      id: session, install_hash: install, last_seen_at: time,
    }, "resolution=ignore-duplicates");
    await database("cortex_app_sessions?id=eq." + session + "&install_hash=eq." + install, "PATCH", { last_seen_at: time });
  }

  async function inbox(owners: string[]) {
    if (!owners.length) throw new Failure("client_version", 400);
    const tickets = await database<Array<{ id: string }>>(
      "cortex_support?select=id,created_at,category,message,status&order=created_at.desc,id.desc&limit=50&device_hash=in.(" +
      owners.map(encodeURIComponent).join(",") + ")",
    );
    const ids = tickets.map(ticket => ticket.id).filter(id => UUID.test(id));
    const replies = ids.length ? await database(
      "cortex_support_replies?select=id,ticket_id,created_at,message&order=created_at.desc,id.desc&limit=500&ticket_id=in.(" + ids.join(",") + ")",
    ) : [];
    return { tickets, replies };
  }

  return { gate, presence, inbox };
}

function httpsUrl(value: string): string | undefined {
  try {
    const url = new URL(value);
    if (url.protocol === "https:" && !url.username && !url.password) return url.toString();
  } catch { /* Invalid update URL. */ }
}
