import { networkTargets, panelTargets, settingsTargets, stateTargets, utilityTargets } from "./tool_targets.ts";

type Property = {
  type: "string" | "integer";
  description?: string;
  enum?: string[];
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
};

const choice = (...values: string[]): Property => ({ type: "string", enum: values });
const text = (maxLength: number, description?: string, minLength = 1): Property =>
  ({ type: "string", minLength, maxLength, ...(description ? { description } : {}) });
const integer = (minimum: number, maximum: number): Property => ({ type: "integer", minimum, maximum });
const percent = integer(0, 100);
const number: Property = { ...text(16, "Phone number in digits with optional leading +"), pattern: "^\\+?[0-9]{3,15}$" };
const next = choice("answer", "decide");
const date = text(35, "ISO 8601 date and time with explicit timezone offset");

function define(
  name: string, kind: string, description: string, properties: Record<string, Property> = {},
  required = Object.keys(properties),
) {
  return {
    kind,
    tool: {
      type: "function",
      function: { name, description, parameters: { type: "object", properties, required, additionalProperties: false } },
    },
  };
}

const definitions = [
  define("open_app", "app", "Open an Android app named or implied by the user. тг and телега mean telegram; тт means tiktok. Ask which service for ambiguous reels.", {
    target: text(95, "Canonical short name such as telegram, tiktok, instagram, youtube, camera, clock, calculator, browser, gallery, files, maps, or the installed app's name"),
    section: { ...choice("reels", "shorts"),
      description: "reels only for target instagram, shorts only for target youtube. Omit for TikTok and all other apps." },
  }, ["target"]),
  define("open_android_settings", "settings", "Open a specific Android settings page.", {
    target: choice(...settingsTargets),
  }),
  define("change_connectivity", "connectivity", "Handle a user's explicit request to enable or disable an Android connectivity or system toggle. Android may open its control panel.", {
    target: choice(...networkTargets), mode: choice("on", "off"),
  }),
  define("adjust_volume", "volume", "Change, mute, or check Android audio volume. Use media by default and for music or video. Level is percentage, or optional change in percentage points.", {
    stream: choice("media", "ring", "alarm", "notification", "call"),
    mode: choice("set", "up", "down", "mute", "unmute", "status"), level: percent,
  }, ["stream", "mode"]),
  define("set_ringer_mode", "ringer", "Switch the phone's ringer between normal, vibration, and silent mode.", {
    mode: choice("sound", "vibrate", "silent"),
  }),
  define("adjust_brightness", "brightness", "Change display brightness, toggle automatic brightness, or report the current level. The level is a percent.", {
    mode: choice("set", "up", "down", "auto", "manual", "status"), level: percent,
  }, ["mode"]),
  define("flashlight", "torch", "Turn the phone flashlight on or off, or flip its state.", { mode: choice("on", "off", "toggle", "status") }),
  define("lock_screen", "lock_screen", "Turn off and lock the Android screen when explicitly requested."),
  define("inspect_phone", "read_device", "Read a verified phone value. If the user asked to decide or continue based on the result, set next=decide.", {
    target: choice(...stateTargets), next,
  }, ["target"]),
  define("find_installed_apps", "app_list", "Read available Android launcher apps. Omit query for a list. Use next=decide when the request explicitly depends on the result.", {
    query: text(95), next,
  }, []),
  define("open_app_info", "app_info", "Open Android app info for an installed app.", {
    target: text(95),
  }),
  define("open_system_panel", "panel", "Show an Android quick control panel.", { target: choice(...panelTargets) }),
  define("open_system_utility", "utility", "Open the camera in photo or video mode, the downloads view, or the keyboard picker.", { target: choice(...utilityTargets) }),
  define("share_text", "share_text", "Open Android's share chooser with text when the user asks to share it.", { text: text(1500) }),
  define("copy_text", "copy_text", "Copy text to the clipboard on explicit request.", { text: text(1500) }),
  define("go_to_android_home", "home", "Return to the Android home screen or launcher."),
  define("set_timer", "timer", "Open the clock with a timer specified as a total number of seconds.", { seconds: integer(1, 86_400) }),
  define("set_alarm", "alarm", "Open the clock with an alarm at the requested local time, in 24-hour format.", {
    hour: integer(0, 23), minute: integer(0, 59),
  }),
  define("open_dialer", "dial", "Open Android's dialer with the supplied phone number.", { number }),
  define("draft_sms", "sms", "Open an SMS draft for the supplied number. The user sends it from Android.", {
    number, text: text(600, "Text of the SMS draft", 0),
  }, ["number"]),
  define("search_web", "search", "Open a web search when the user asks to search or find something in a browser.", { query: text(220) }),
  define("search_maps", "maps", "Open a location search in the Android maps app.", { query: text(220) }),
  define("draft_calendar_event", "calendar", "Open a calendar draft for the user's event. Start and end are optional.", {
    title: text(100), start: date, end: date,
  }, ["title"]),
];

const planDefinitions = [
  define("run_screen_task", "screen_task", "Carry out a task inside any installed Android app using visible controls. Pass the app name and the user's specific goal, including the exact search text, section, recipient, or item they requested. Combine navigation within the same app into one screen task.", {
    target: text(95, "Name or package of the Android app"), goal: text(1000, "User's exact goal inside that app"),
  }),
  define("leave_app", "close_app", "Leave a named Android app and return to the home screen if that app is in the foreground.", {
    target: text(95),
  }),
  define("capture_photo", "capture_photo", "Take a photo with a visible Android camera preview when the user explicitly requests a picture. Specify the front lens for a selfie. Do not use for opening the camera without taking a photo.", {
    lens: choice("back", "front"),
  }, []),
];
export const MAX_PLAN_STEPS = 8;
export const deviceTools = definitions.map(definition => definition.tool);
const actions = new Map([...definitions, ...planDefinitions].map(definition => [definition.kind, definition]));
const planName = "execute_phone_plan";
const stepProperties: Record<string, Property> = { kind: choice(...actions.keys()) };
for (const { tool } of actions.values()) {
  for (const [key, property] of Object.entries(tool.function.parameters.properties)) {
    stepProperties[key] ??= property.type === "string" ? { type: "string" } : { type: "integer" };
  }
}
export const planGuide = [...actions.values()].map(({ kind, tool }) => {
  const { description, parameters: { properties, required } } = tool.function;
  const fields = Object.entries(properties).map(([key, field]) => {
    const values = field.enum && field.enum.length <= 12 ? "=" + field.enum.join("/") : "";
    return key + (required.includes(key) ? "" : "?") + values;
  });
  return `${kind}(${fields.join(", ")}) — ${description}`;
}).join("\n");
export const planTools = [{
  type: "function",
  function: {
    name: planName,
    description: "Send the complete ordered plan for the user's Android request in one call. Include every requested action, including actions after opening an app. Use screen_task for tasks inside a named app, close_app to leave it, and lock_screen to turn off the screen.",
    parameters: {
      type: "object", required: ["steps"], additionalProperties: false,
      properties: { steps: {
        type: "array", minItems: 1, maxItems: MAX_PLAN_STEPS,
        description: "The steps in the user's requested order. Each step contains a kind and its own arguments.",
        items: { type: "object", required: ["kind"], additionalProperties: false, properties: stepProperties },
      } },
    },
  },
}];
const reply = define("answer_without_action", "_reply", "Answer the user's question or ask for clarification in Markdown when no phone action is appropriate.", { text: text(6500) });
export const repairTools = [...deviceTools, reply.tool];
export const planRepairTools = [...planTools, reply.tool];
const byName = new Map([...definitions, reply].map(definition => [definition.tool.function.name, definition]));
type ObjectValue = Record<string, unknown>;
function object(value: unknown): ObjectValue | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as ObjectValue : undefined;
}

function validate(value: unknown, property: Property): boolean {
  if (property.type === "integer") {
    return typeof value === "number" && Number.isInteger(value) &&
      value >= (property.minimum ?? 0) && value <= (property.maximum ?? Number.MAX_SAFE_INTEGER);
  }
  return typeof value === "string" &&
    (property.enum?.includes(value) ?? true) &&
    value.trim().length >= (property.minLength ?? 0) &&
    value.length <= (property.maxLength ?? 4096) &&
    (!property.pattern || new RegExp(property.pattern).test(value));
}

type PartialCall = { name: string; args: string };

function parseAction(definition: ReturnType<typeof define>, args: ObjectValue): ObjectValue | null {
  const { properties, required } = definition.tool.function.parameters;
  for (const key of required) if (!Object.hasOwn(args, key)) return null;
  for (const [key, value] of Object.entries(args)) {
    if (!Object.hasOwn(properties, key) || !validate(value, properties[key])) return null;
  }
  if (definition.kind === "app" && typeof args.target === "string" && args.target.toLowerCase() === "tiktok") {
    delete args.section;
  }
  return { kind: definition.kind, ...args };
}

function normalizePlan(steps: ObjectValue[]): ObjectValue[] {
  return steps.map(step => {
    if (step.kind === "app" && typeof step.target === "string") {
      const app = step.target.toLowerCase();
      if (step.section === "shorts" && app === "youtube" || step.section === "reels" && app === "instagram") {
        return { kind: "screen_task", target: step.target, goal: "Открой раздел " + step.section };
      }
    }
    return step;
  }).filter((step, index, normalized) => {
    const next = normalized[index + 1];
    return !(step.kind === "app" && next?.kind === "screen_task" && typeof step.target === "string" &&
      typeof next.target === "string" && step.target.trim().toLowerCase() === next.target.trim().toLowerCase());
  });
}

export class DeviceDecision {
  constructor(private readonly repair = false, private readonly plan = false) {}

  private calls = new Map<number, PartialCall>();
  private malformed = false;
  private tooMany = false;

  collect(data: unknown, field: "delta" | "message") {
    const choices = object(data)?.choices;
    const calls = Array.isArray(choices) ? object(object(choices[0])?.[field])?.tool_calls : undefined;
    if (!Array.isArray(calls) || calls.length === 0) return;
    for (const [position, raw] of calls.entries()) {
      const call = object(raw);
      const index = call?.index ?? position;
      if (typeof index !== "number" || !Number.isInteger(index) || index < 0 || index >= (this.plan ? 1 : 4)) {
        this.tooMany = true;
        continue;
      }
      const fn = object(call?.function);
      if (!fn || call?.type && call.type !== "function") { this.malformed = true; continue; }
      const entry = this.calls.get(index) ?? { name: "", args: "" };
      if (typeof fn.name === "string" && fn.name !== entry.name) entry.name = (entry.name + fn.name).slice(0, 96);
      if (typeof fn.arguments === "string") entry.args = (entry.args + fn.arguments).slice(0, 8192);
      this.calls.set(index, entry);
    }
  }

  private parse({ name, args: raw }: PartialCall): ObjectValue[] | null {
    const definition = byName.get(name);
    let args: ObjectValue | undefined;
    try { args = object(JSON.parse(raw)); } catch { return null; }
    if (!args) return null;
    if (this.plan && name === planName) {
      const steps = args.steps;
      if (Object.keys(args).length !== 1 || !Array.isArray(steps) || steps.length < 1 || steps.length > MAX_PLAN_STEPS) return null;
      const validated: ObjectValue[] = [];
      for (const raw of steps) {
        const step = object(raw);
        if (!step) return null;
        const { kind, ...fields } = step;
        const definition = typeof kind === "string" ? actions.get(kind) : undefined;
        const result = definition && parseAction(definition, fields);
        if (!result) return null;
        validated.push(result);
      }
      return normalizePlan(validated);
    }
    if (!definition || (definition.kind === "_reply" ? !this.repair : this.plan)) return null;
    const parsed = parseAction(definition, args);
    return parsed ? [parsed] : null;
  }

  outcome(): ObjectValue | undefined {
    if (this.tooMany) return { tool_error: "multiple_tools" };
    if (this.malformed) return { tool_error: "tool_arguments" };
    if (!this.calls.size) return undefined;
    const parsed = [...this.calls].sort(([a], [b]) => a - b).map(([, call]) => this.parse(call));
    if (parsed.some(result => !result)) return { tool_error: "tool_arguments" };
    const actions = parsed.flat();
    if (actions[0]?.kind === "_reply" && actions.length === 1) return { delta: actions[0].text };
    if (actions.some(action => action?.kind === "_reply")) return { tool_error: "tool_arguments" };
    return actions.length === 1 ? { tool: actions[0] } : { tools: actions };
  }
}
