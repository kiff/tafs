import type { Run, SessionEvent, VersionState } from "./agents.js";
import type { Knob } from "./knob.js";
import type { Rule } from "./home.js";
import { amountWords, type ActivityCall, activityMatches } from "./activity.js";
import { vendorName } from "./steps.js";
import { type Fetch } from "./kiff.js";

export interface GrantView {
  id: string; vendor?: string; status: string; status_words?: string; signed_in?: boolean; entry?: string;
  request: { agent: string; server?: string; tools?: Array<{ tool: string; name?: string; title?: string; setup_reads?: boolean }> };
}

/** The phone signs this read; tafs cloud forwards the proof unchanged and signs nothing. */
export async function setupGrants(base: string, tenant: string, proof: string, net: Fetch = fetch): Promise<GrantView[]> {
  if (!proof || proof.length > 4096) throw new Error("Connect this phone to read its connected apps");
  const res = await net(`${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/grants`, {
    headers: { accept: "application/json", "Planque-Device-Signature": proof },
  });
  if (!res.ok) throw new Error("Connected apps could not be read; try again");
  const out = await res.json() as { grants?: GrantView[] };
  if (!Array.isArray(out.grants)) throw new Error("Connected apps returned an unreadable list");
  return out.grants;
}

/** App names are resolved here. Server ids never become labels on either phone. */
export function appDisplayName(grant: GrantView): string {
  const vendor = grant.vendor ?? "";
  if (/[A-Z]/.test(vendor) && !/[-_]mcp(?:[-_]|$)/i.test(vendor)) return vendor;
  const fromHost = vendorName(grant.request.server);
  if (fromHost) return fromHost;
  const name = vendor.replace(/(?:[-_ ]mcp(?:[-_ ]server)?|[-_ ]server)$/i, "").replace(/[_-]+/g, " ").trim();
  return name ? name.replace(/\b\p{L}/gu, (c) => c.toUpperCase()) : "Connected app";
}

/** Counts cover the whole seven-day interval, or explicitly say it could not be read in full. */
export async function appCalls(token: string, agent: string, now: Date, net: Fetch, base: string): Promise<{ calls: ActivityCall[]; complete: boolean }> {
  const calls: ActivityCall[] = [];
  const cutoff = now.getTime() - 7 * 86400_000;
  try {
    for (let offset = 0; offset < 10000; offset += 100) {
      const q = new URLSearchParams({ actor: agent, limit: "100", offset: String(offset) });
      const res = await net(`${base}/v1/me/activity?${q}`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
      if (!res.ok) return { calls, complete: false };
      const page = await res.json() as { calls?: ActivityCall[]; shown?: number };
      if (!Array.isArray(page.calls)) return { calls, complete: false };
      calls.push(...page.calls.filter((c) => c.actor_id === agent && Date.parse(c.created_at) >= cutoff));
      if (page.calls.length < 100 || offset + page.calls.length >= (page.shown ?? Infinity) || page.calls.some((c) => Date.parse(c.created_at) < cutoff)) return { calls, complete: true };
    }
  } catch { return { calls, complete: false }; }
  return { calls, complete: false };
}

function limitsOf(rules: Rule[]): string[] {
  return rules.flatMap((r) => {
    if (r.count_per_window === 0) return [r.outside === "hold" ? "Needs your approval for every call" : "Not allowed by this Card"];
    const money = (m: NonNullable<Rule["per_action"]>) => amountWords({ value: String(m.minor / 10 ** m.scale), unit: m.unit });
    return [r.per_action ? `${money(r.per_action)} per action` : undefined,
      r.per_window ? `${money(r.per_window)} ${r.window_words.each}` : undefined,
      r.count_per_window !== null ? `${r.count_per_window} calls ${r.window_words.each}` : undefined].filter((s): s is string => Boolean(s));
  });
}

/** A live tool call with no matching result. Never infer a running step from a finished call. */
export function runningTool(events: SessionEvent[], run: Run | undefined): string | undefined {
  if (run?.status !== "RUNNING") return;
  const active = new Map<string, string>();
  for (const e of events) {
    if (e.run !== run.id) continue;
    const b = e.body as { call_id?: string; tool?: string } | undefined;
    if (e.kind === "tool_call" && b?.call_id && b.tool) active.set(b.call_id, b.tool);
    if (e.kind === "tool_result" && b?.call_id) active.delete(b.call_id);
  }
  return [...active.values()].at(-1);
}

export interface SetupView {
  connections: Array<{ id: string; name: string; status: string; status_words: string; reconnect: boolean; action_count: number; action_words: string;
    actions: Array<{ name: string; title: string; limits: string[]; steps: number[]; step_words: string }>;
    workflow_steps: Array<{ number: number; label: string; words: string; running: boolean }>;
    steps: number[]; running: boolean; stats: { calls: number; done: number; held: number; words: string; complete: boolean; activity_query: string } }>;
  workflow: { title: string; summary: string; steps: Array<{ number: number; label: string; words: string; app?: string; grant?: string; running: boolean }>; running_step?: number };
  memory: { count: number; title: string; latest?: string };
  versions: { title: string; summary: string; live?: number; earlier: number };
}

/** All grouping, names, action/step mappings and counts are made once, at their source. */
/** A step's words that only repeat its label say nothing more: they're sent empty, so no app shows the title twice. */
function repeats(words: string, label: string): boolean {
  const plain = (t: string) => t.toLowerCase().replace(/\s+/g, " ").replace(/[.\s]+$/, "").trim();
  return plain(words) === plain(label);
}

export function setupView(input: { agent: string; grants: GrantView[]; knob: Knob; rules: Rule[];
  notes: Array<{ content: string }>; versions: VersionState; calls: ActivityCall[]; now: Date; running?: string; statsComplete: boolean }): SetupView {
  const grants = input.grants.filter((g) => g.request.agent === input.agent && (["connected", "listed", "signed_in", "needs_reconnect"].includes(g.status) || (g.status === "pending" && g.signed_in === true)));
  const names = new Map(grants.map((g) => [g.id, appDisplayName(g)]));
  const owners = new Map<string, string>();
  for (const g of grants) for (const t of ["connected", "needs_reconnect"].includes(g.status) ? g.request.tools ?? [] : []) if (!t.setup_reads) owners.set(t.name || t.tool, g.id);
  const steps = input.knob.what.filter((s) => s.id !== "what_ask").map((s) => {
    const grant = s.tool ? owners.get(s.tool) : undefined;
    return { number: s.step, label: s.label, words: repeats(s.words, s.label) ? "" : s.words, ...(grant ? { grant, app: names.get(grant) } : {}), running: Boolean(s.tool && s.tool === input.running) };
  });
  const cutoff = input.now.getTime() - 7 * 86400_000;
  const calls = input.calls.filter((c) => c.actor_id === input.agent && Date.parse(c.created_at) >= cutoff);
  const connections = grants.map((g) => {
    const actions = (["connected", "needs_reconnect"].includes(g.status) ? g.request.tools ?? [] : []).filter((t) => !t.setup_reads).map((t) => {
      const name = t.name || t.tool;
      const used = input.knob.what.filter((s) => s.tool === name);
      const limits = limitsOf(input.rules.filter((r) => r.action === name));
      return { name, title: t.title || input.rules.find((r) => r.action === name)?.label || "App action",
        limits: limits.length ? limits : ["See its Card"], steps: used.map((s) => s.step),
        step_words: used.length ? used.map((s) => `Step ${s.step}`).join(" · ") : "Not used by the current workflow" };
    });
    const tools = new Set(actions.map((a) => a.name));
    const ownCalls = calls.filter((c) => tools.has(c.action_name));
    const done = ownCalls.filter((c) => activityMatches(c, "done")).length;
    const held = ownCalls.filter((c) => activityMatches(c, "held")).length;
    return { id: g.id, name: names.get(g.id)!, status: g.status,
      status_words: g.status_words || ({ connected: "Connected", listed: "Signed in · choose its actions", signed_in: "Signed in", needs_reconnect: "Sign in again", pending: "Signed in · actions waiting for approval" } as Record<string, string>)[g.status] || "Read its connection",
      reconnect: g.status === "needs_reconnect", action_count: actions.length,
      action_words: actions.length ? `${actions.length} action${actions.length === 1 ? "" : "s"}` : "Choose actions",
      actions, steps: steps.filter((s) => s.grant === g.id).map((s) => s.number), workflow_steps: steps.filter((s) => s.grant === g.id),
      running: Boolean(input.running && tools.has(input.running)),
      stats: { calls: ownCalls.length, done, held, complete: input.statsComplete,
        activity_query: new URLSearchParams({ tools: [...tools].join(","), from: new Date(cutoff).toISOString() }).toString(),
        words: input.statsComplete ? `${ownCalls.length} calls · ${done} done · ${held} held in the last 7 days` : "The last 7 days could not be read in full; try again" } };
  });
  const live = input.versions.versions.find((v) => v.live)?.n;
  const earlier = input.versions.versions.filter((v) => live !== undefined && v.n < live).length;
  const apps = steps.map((s) => s.app).filter((s): s is string => Boolean(s));
  const order = apps.filter((app, i) => app !== apps[i - 1]);
  const running = steps.find((s) => s.running)?.number;
  return { connections, workflow: { title: "Workflow", summary: steps.length ? `${steps.length} step${steps.length === 1 ? "" : "s"}${order.length ? ` · ${order.join(" → ")}` : ""}` : "Ask Coach to set its workflow",
    steps, ...(running ? { running_step: running } : {}) },
    memory: { count: input.notes.length, title: `${input.notes.length} note${input.notes.length === 1 ? "" : "s"}`, ...(input.notes[0] ? { latest: input.notes[0].content } : {}) },
    versions: { title: "Changes to how it runs", summary: live !== undefined ? `v${live} live · ${earlier} earlier` : "Runs its original definition", ...(live !== undefined ? { live } : {}), earlier } };
}
