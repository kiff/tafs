import { randomBytes } from "node:crypto";
import type { Notify } from "./agents.js";
import { type Keys, PERMIT_HEADER, type Permit, PermitError, verifyPermit } from "./permit.js";

/**
 * Coach's tools (kiff/tafs#206): one small MCP server, at POST /v1/mcp, that
 * the account's Coach (its Setup agent) reaches only through KIFF's gateway.
 * The connection is in verify mode and tafs cloud holds no credential for it:
 * every call carries KIFF's permit, checked here before anything is done, and
 * the permit's subject must be the account's Coach. KIFF decides each call
 * under Coach's Cards first: installing a version is held for the owner, and
 * answering a held call isn't a tool at all.
 */

/** Opens an owner's message to Coach that carries lines about its tasks before the owner's words. */
export const COACH_LINES = "[tafs coach lines]";

/** The argument KIFF's gateway fills with the call's operation id, bound into its permit. */
export const OP_ARG = "tafs_op";

/** The protocol revisions this server speaks; the newest is offered otherwise. */
const VERSIONS = ["2025-03-26", "2025-06-18", "2025-11-25"];

interface ToolDef {
  name: string;
  description: string;
  properties: Record<string, unknown>;
  required: string[];
  /** Read-only: changes nothing (KIFF declares it so). */
  read?: boolean;
  /** What it lets Coach do, in the owner's words: the line the apps show when the owner gives it. */
  line: string;
}

const TAF = { type: "string", description: "The taf's id, from tafs_state, or its name." };
const RUN = { type: "string", description: "The run's id, from tafs_state or an earlier answer." };

/** The tools, each a whole job in one call (kiff/tafs#206). */
export const TOOLS: ToolDef[] = [
  {
    name: "tafs_state", line: "See your tafs, their versions and their runs", read: true, properties: {}, required: [],
    description: "Everything about the account's tafs in one read: each taf, its live version and versions, a switch under way, " +
      "its schedule or that it works from its chat, and its open runs with what each waits on (the held call, its arguments and deadline). " +
      "Each taf's kiff_agent is its KIFF agent: kiff_card {agent: kiff_agent} reads its live Cards, with what's used and left.",
  },
  {
    name: "taf_run", line: "Read one of a taf's runs", read: true, properties: { taf: TAF, run: RUN }, required: ["taf", "run"],
    description: "One run of a taf so far: its status, its answer if it ended, what it waits on, and for a trial what KIFF decided on each write.",
  },
  {
    name: "taf_task", line: "Give a taf a task or a message",
    properties: {
      taf: TAF,
      text: { type: "string", description: "The task or message, in the owner's words." },
      run: { type: "string", description: "A run at work: the text goes to it, read at its next step. Omit to start a new run." },
      chat: { type: "string", enum: ["live", "new"], description: "Where a new run starts: your live chat with the taf (default) or a new one." },
    },
    required: ["taf", "text"],
    description: "Hands a taf a task or a message. A new run answers at once with its id; its result comes back to you when it ends, so never poll. " +
      "With run, the text goes to that run while it works. The task runs under the taf's own Cards.",
  },
  {
    name: "run_stop", line: "Stop a taf's run", properties: { taf: TAF, run: RUN }, required: ["taf", "run"],
    description: "Stops a run of a taf. Its held calls are withdrawn in KIFF, and nothing more is sent.",
  },
  {
    name: "taf_pause", line: "Pause or resume a taf's schedule",
    properties: { taf: TAF, action: { type: "string", enum: ["pause", "resume"] } },
    required: ["taf", "action"],
    description: "Pauses a taf's schedule, or resumes it. A taf that works from its chat has no schedule to pause.",
  },
  {
    name: "version_try", line: "Try a version of a taf, sending nothing",
    properties: { taf: TAF, n: { type: "integer", minimum: 1 }, input: { type: "string", description: "What the trial works on; omitted, what each scheduled run asks." } },
    required: ["taf", "n"],
    description: "Tries version n of a taf: KIFF decides each of its writes and sends none. It answers at once; the trial's result comes back to you when it ends.",
  },
  {
    name: "version_install", line: "Install a version of a taf",
    properties: { taf: TAF, n: { type: "integer", minimum: 1 } },
    required: ["taf", "n"],
    description: "Installs version n of a taf. KIFF holds this for the owner, who approves it in tafs: the app stops what's in the way, " +
      "confirms the version's Card and makes it live. Say \"Waiting for your approval\" once, and don't call it again for the same version. " +
      "Don't name how the owner approves.",
  },
];

/**
 * Coach's Cards: the reads share one, 500 a day; a task 50 a day and a trial 20, then asked;
 * stopping and pausing 50 a day each, then refused; every install held for the owner. Stable ids,
 * so drafting them again revises the same Cards.
 */
export const COACH_CARDS: Array<{ id: string; actions: string[]; limit: number; asks: boolean }> = [
  { id: "coach-reads", actions: ["tafs_state", "taf_run"], limit: 500, asks: false },
  { id: "coach-taf-task", actions: ["taf_task"], limit: 50, asks: true },
  { id: "coach-version-try", actions: ["version_try"], limit: 20, asks: true },
  { id: "coach-run-stop", actions: ["run_stop"], limit: 50, asks: false },
  { id: "coach-taf-pause", actions: ["taf_pause"], limit: 50, asks: false },
  { id: "coach-version-install", actions: ["version_install"], limit: 0, asks: true },
];

/** A connection in KIFF's gateway, as tafs cloud reads it. */
export interface GatewayConnection { name: string; url?: string; control?: boolean }

/**
 * What giving Coach its tools still needs, from the account's connections: the tools to connect, or
 * the first of Coach's names another connection holds. One there counts as Coach's only when it's
 * this server (`mcp`) as the control server: Coach's Cards grant by name, so any other would put a
 * vendor's tool on them. One that's the control server at another address (connected before tafs
 * cloud moved) is connected again here, replacing it: `replace` names those.
 */
export function coachToolsPlan(there: GatewayConnection[], mcp: string): { missing: ToolDef[]; replace: string[]; taken?: string } {
  const byName = new Map(there.map((c) => [c.name, c]));
  const missing: ToolDef[] = [];
  const replace: string[] = [];
  for (const t of TOOLS) {
    const c = byName.get(t.name);
    if (!c) { missing.push(t); continue; }
    if (c.control !== true) return { missing: [], replace: [], taken: t.name };
    if (c.url !== mcp) { missing.push(t); replace.push(t.name); }
  }
  return { missing, replace };
}

/** What a replaced connection's line adds, so the owner sees the replacement before signing. */
export const REPLACES_LINE = "replaces the earlier connection";

/**
 * The batch that connects the missing tools (KIFF's POST /v1/connections/batch), and its lines. The
 * phone signs and sends the body as it is; a name already connected the same way is left as it is,
 * so sending it again is safe. One in `replace` is marked so: KIFF replaces the account's control
 * server's own connection of that name, and its line says so.
 */
export function coachConnect(missing: ToolDef[], mcp: string, replace: string[] = []): { body: { connections: unknown[] }; lines: string[] } {
  const replacing = new Set(replace);
  return {
    body: {
      connections: missing.map((t) => ({
        name: t.name, url: mcp, tool: t.name, mode: "verify", audience: mcp, control: true,
        idempotency_argument: OP_ARG, ...(t.read ? { read_only: true } : {}), ...(replacing.has(t.name) ? { replace: true } : {}),
      })),
    },
    lines: missing.map((t) => (replacing.has(t.name) ? `${t.line} · ${REPLACES_LINE}` : t.line)),
  };
}

/**
 * The draft of Coach's Cards (KIFF's POST /v1/me/agent-drafts), labelled "Coach", for Coach's KIFF
 * agent: the phone signs and sends it as it is, and a signed submit is the owner's one confirmation.
 */
export function coachCardsDraft(agentID: string): { agent_id: string; label: string; cards: unknown[] } {
  return {
    agent_id: agentID,
    label: "Coach",
    cards: COACH_CARDS.map((c) => ({
      id: c.id, subject: agentID, domain: "gateway", on_exceed: c.asks ? "owner" : "refuse", ...(c.asks ? { hold_expiry: "10m" } : {}),
      grants: c.actions.map((action) => ({ action })), aggregates: [{ quantity: "count", limit: c.limit, window: "calendar_day" }],
    })),
  };
}

/** A tool's answer, as Coach reads it. */
export type ToolAnswer = Record<string, unknown>;

/** What a tool call acts with: the account, Coach, and where a task's end is announced. */
export interface CoachCall {
  tenant: string;
  /** Coach's KIFF agent: the permit's subject. */
  coach: string;
  /** Where kiff/agents announces a run Coach started, with a fresh ref. */
  notify: () => Notify;
  /**
   * The person whose chat with Coach this call is in, as KIFF signed it on the permit,
   * and the permit itself, which asks KIFF what they may do with a taf. Absent: Coach
   * acts as itself, as before.
   */
  person?: { subject: string; permit: string };
}

/** Runs one tool; throws ToolRefusal for an answer Coach should read as a refusal. */
export type RunTool = (name: string, args: Record<string, unknown>, call: CoachCall) => Promise<ToolAnswer>;

/** A tool call refused in words Coach can act on (no such taf, a run that ended). */
export class ToolRefusal extends Error {}

/**
 * What the MCP server keeps, in tafs cloud's table, every item under the
 * account's prefix so deleting the account erases it (account.ts).
 */
export interface CoachStore {
  /** Claims key until expiresAt; false when it was claimed already. */
  once(key: string, expiresAt: Date): Promise<boolean>;
  get(key: string): Promise<unknown>;
  put(key: string, value: unknown, expiresAt: Date): Promise<void>;
  /** Adds item to the list at key; the list's ttl moves to expiresAt. */
  append(key: string, item: unknown, expiresAt: Date): Promise<void>;
  /** The list at key, removed. */
  take(key: string): Promise<unknown[]>;
  remove(key: string): Promise<void>;
}

/** A permit's id is single-use for this long (its lifetime is a minute at most, with the clock tolerance). */
const JTI_KEPT_MS = 5 * 60_000;
/** An operation's answer is kept this long, so a retry of the same operation gets it again. */
const OP_KEPT_MS = 60 * 60_000;
/** A task's ref waits this long for its run to end. */
export const TASK_KEPT_MS = 7 * 24 * 3600_000;

export const keys = {
  jti: (tenant: string, jti: string) => `${tenant}#coach-jti#${jti}`,
  /** An operation's answer, kept for the person whose Coach chat asked it (none: Coach itself). */
  op: (tenant: string, op: string, person?: string) => `${tenant}#coach-op2#${person ?? ""}#${op}`,
  task: (tenant: string, ref: string) => `${tenant}#coach-task#${ref}`,
  /** How a person's Coach tasks went, for that person's Coach only. */
  notes: (tenant: string, person: string) => `${tenant}#coach-notes#${person}`,
};

/** A task's ref: the account, so the note finds its record, and a value no one guesses. */
export function newRef(tenant: string): string {
  return `${Buffer.from(tenant).toString("base64url")}.${randomBytes(18).toString("base64url")}`;
}

/** The account a ref names, or undefined for one that isn't a ref. */
export function refTenant(ref: string): string | undefined {
  const [t, r] = ref.split(".");
  if (!t || !r || ref.split(".").length !== 2) return undefined;
  const tenant = Buffer.from(t, "base64url").toString("utf8");
  return tenant && !tenant.includes("#") ? tenant : undefined;
}

export interface MCPConfig {
  /** This server's URL, as the connection in KIFF names it: the permit's audience. */
  audience: string;
  issuer: string;
  keys: Keys;
  store: CoachStore;
  /** Coach's KIFF agent for the account, or undefined when it has no Coach. */
  coachOf: (tenant: string) => Promise<string | undefined>;
  notifyURL: string;
  run: RunTool;
  now: () => Date;
  log?: (msg: string, err?: unknown) => void;
}

interface RPC {
  jsonrpc?: string;
  id?: unknown;
  method?: string;
  params?: { protocolVersion?: string; name?: string; arguments?: unknown };
}

export interface MCPResult {
  status: number;
  body?: unknown;
}

/** One JSON-RPC message to the MCP server: stateless, answered as JSON. */
export async function serveMCP(raw: string, headers: Record<string, string | undefined>, cfg: MCPConfig): Promise<MCPResult> {
  let req: RPC;
  try { req = JSON.parse(raw) as RPC; } catch { return { status: 400, body: rpcError(null, -32700, "parse error") }; }
  if (!req || req.jsonrpc !== "2.0" || typeof req.method !== "string") return { status: 400, body: rpcError(null, -32600, "invalid request") };
  if (req.id === undefined || req.id === null) return { status: 202 }; // a notification
  const ok = (result: unknown): MCPResult => ({ status: 200, body: { jsonrpc: "2.0", id: req.id, result } });
  switch (req.method) {
    case "initialize": {
      const v = req.params?.protocolVersion;
      return ok({ protocolVersion: v && VERSIONS.includes(v) ? v : VERSIONS.at(-1), capabilities: { tools: {} }, serverInfo: { name: "tafs", version: "1" } });
    }
    case "ping":
      return ok({});
    case "tools/list":
      return ok({ tools: TOOLS.map(listed) });
    case "tools/call":
      return ok(await call(req.params?.name, req.params?.arguments, headers[PERMIT_HEADER] ?? headers["KIFF-Permit"], cfg));
    default:
      return { status: 200, body: rpcError(req.id, -32601, "method not found") };
  }
}

function listed(t: ToolDef) {
  return {
    name: t.name,
    description: t.description,
    inputSchema: {
      type: "object",
      properties: { ...t.properties, [OP_ARG]: { type: "string", description: "Set by KIFF: the call's operation id." } },
      required: [...t.required, OP_ARG],
      additionalProperties: false,
    },
    ...(t.read ? { annotations: { readOnlyHint: true } } : {}),
  };
}

function rpcError(id: unknown, code: number, message: string) {
  return { jsonrpc: "2.0", id, error: { code, message } };
}

function text(value: unknown, isError = false) {
  return { content: [{ type: "text", text: typeof value === "string" ? value : JSON.stringify(value) }], isError };
}

/**
 * One tool call: KIFF's permit for exactly this call, from the account's
 * Coach, once; then the tool, once per operation, its answer kept so a retry
 * of the same operation gets the same answer.
 */
async function call(name: unknown, rawArgs: unknown, token: string | undefined, cfg: MCPConfig) {
  const refused = (why: string) => text(`Refused by tafs: ${why}. Nothing was done.`, true);
  const tool = TOOLS.find((t) => t.name === name);
  if (!tool) return refused(`there is no tool ${String(name)}`);
  const args = rawArgs === undefined ? {} : rawArgs;
  if (typeof args !== "object" || args === null || Array.isArray(args)) return refused("the arguments are not a JSON object");
  const now = cfg.now();
  let p: Permit;
  try {
    p = await verifyPermit(token, { audience: cfg.audience, tool: tool.name, args }, cfg.keys, cfg.issuer, now);
  } catch (err) {
    if (err instanceof PermitError) return refused(err.message);
    throw err;
  }
  const a = args as Record<string, unknown>;
  if (a[OP_ARG] !== p.op) return refused(`the permit's operation is not this call's ${OP_ARG}`);
  const coach = await cfg.coachOf(p.tenant);
  if (!coach || coach !== p.subject) return refused("only the account's Coach may use these tools");
  // Kept per person: a retry gets the answer only in the chat that asked for it.
  const opKey = keys.op(p.tenant, p.op, p.actor);
  const kept = await cfg.store.get(opKey);
  if (kept !== undefined) return kept;
  if (!(await cfg.store.once(keys.jti(p.tenant, p.id), new Date(now.getTime() + JTI_KEPT_MS)))) return refused("this permit was used already");
  // One run of the operation at a time: a retry while the first is at work waits for its answer.
  if (!(await cfg.store.once(`${opKey}#claim`, new Date(now.getTime() + OP_KEPT_MS)))) {
    return (await cfg.store.get(opKey)) ?? text("This call is already under way: ask again in a moment for its answer.", true);
  }
  const { [OP_ARG]: _op, ...toolArgs } = a;
  let answer;
  try {
    answer = text(await cfg.run(tool.name, toolArgs, {
      tenant: p.tenant, coach, notify: () => ({ url: cfg.notifyURL, ref: newRef(p.tenant) }),
      ...(p.actor && token ? { person: { subject: p.actor, permit: token } } : {}),
    }));
  } catch (err) {
    if (!(err instanceof ToolRefusal)) {
      cfg.log?.(`coach tool ${tool.name} failed`, err);
      // Free the operation, so a retry isn't "already under way" for an hour.
      await cfg.store.remove(`${opKey}#claim`).catch((e) => cfg.log?.("coach claim not freed", e));
      return text(tool.read
        ? "tafs couldn't read that just now: try again in a moment."
        : "tafs couldn't finish that just now, and it may have started before it failed. Check with tafs_state before trying again.", true);
    }
    answer = text(err.message, true);
  }
  await cfg.store.put(opKey, answer, new Date(now.getTime() + OP_KEPT_MS));
  return answer;
}

/** A task Coach handed a taf, kept under its ref until its run ends. */
export interface Task {
  taf: string;
  run: string;
  /** Coach's run that handed it over, if one was at work: told first. */
  coachRun?: string;
  /** What it was, in a few words, for the line Coach reads. */
  what: string;
  trial?: boolean;
  /** The person whose Coach chat handed it over: only they hear how it went. */
  person?: string;
}

/** kiff/agents' note: a run with a Notify paused or ended. */
export interface Note {
  run: string;
  agent: string;
  status: string;
  ref: string;
  /** A paused run's cards that wait, as kiff/agents names them: only which run to read back. */
  cards?: Array<{ id: string; type: string }>;
}

export function parseNote(raw: string): Note | undefined {
  try {
    const n = JSON.parse(raw) as Partial<Note>;
    if (typeof n.run === "string" && typeof n.agent === "string" && typeof n.status === "string" && typeof n.ref === "string" && n.ref.length <= 200) {
      return n as Note;
    }
  } catch { /* not a note */ }
  return undefined;
}
