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
}

const TAF = { type: "string", description: "The taf's id, from tafs_state, or its name." };
const RUN = { type: "string", description: "The run's id, from tafs_state or an earlier answer." };

/** The tools, each a whole job in one call (kiff/tafs#206). */
export const TOOLS: ToolDef[] = [
  {
    name: "tafs_state", read: true, properties: {}, required: [],
    description: "Everything about the account's tafs in one read: each taf, its live version and versions, a switch under way, " +
      "its schedule or that it works from its chat, and its open runs with what each waits on (the held call, its arguments and deadline). " +
      "Each taf's kiff_agent is its KIFF agent: kiff_card {agent: kiff_agent} reads its live Cards, with what's used and left.",
  },
  {
    name: "taf_run", read: true, properties: { taf: TAF, run: RUN }, required: ["taf", "run"],
    description: "One run of a taf so far: its status, its answer if it ended, what it waits on, and for a trial what KIFF decided on each write.",
  },
  {
    name: "taf_task",
    properties: {
      taf: TAF,
      text: { type: "string", description: "The task or message, in the owner's words." },
      run: { type: "string", description: "A run at work: the text goes to it, read at its next step. Omit to start a new run." },
      chat: { type: "string", enum: ["live", "new"], description: "Where a new run starts: the taf's live chat (default) or a new one." },
    },
    required: ["taf", "text"],
    description: "Hands a taf a task or a message. A new run answers at once with its id; its result comes back to you when it ends, so never poll. " +
      "With run, the text goes to that run while it works. The task runs under the taf's own Cards.",
  },
  {
    name: "run_stop", properties: { taf: TAF, run: RUN }, required: ["taf", "run"],
    description: "Stops a run of a taf. Its held calls are withdrawn in KIFF, and nothing more is sent.",
  },
  {
    name: "taf_pause",
    properties: { taf: TAF, action: { type: "string", enum: ["pause", "resume"] } },
    required: ["taf", "action"],
    description: "Pauses a taf's schedule, or resumes it. A taf that works from its chat has no schedule to pause.",
  },
  {
    name: "version_try",
    properties: { taf: TAF, n: { type: "integer", minimum: 1 }, input: { type: "string", description: "What the trial works on; omitted, what each scheduled run asks." } },
    required: ["taf", "n"],
    description: "Tries version n of a taf: KIFF decides each of its writes and sends none. It answers at once; the trial's result comes back to you when it ends.",
  },
  {
    name: "version_install",
    properties: { taf: TAF, n: { type: "integer", minimum: 1 } },
    required: ["taf", "n"],
    description: "Installs version n of a taf. KIFF holds this for the owner, who approves it in tafs: the app stops what's in the way, " +
      "confirms the version's Card and makes it live. Say \"Waiting for your approval\" once, and don't call it again for the same version. " +
      "Don't name how the owner approves.",
  },
];

/** A tool's answer, as Coach reads it. */
export type ToolAnswer = Record<string, unknown>;

/** What a tool call acts with: the account, Coach, and where a task's end is announced. */
export interface CoachCall {
  tenant: string;
  /** Coach's KIFF agent: the permit's subject. */
  coach: string;
  /** Where kiff/agents announces a run Coach started, with a fresh ref. */
  notify: () => Notify;
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
  op: (tenant: string, op: string) => `${tenant}#coach-op#${op}`,
  task: (tenant: string, ref: string) => `${tenant}#coach-task#${ref}`,
  notes: (tenant: string) => `${tenant}#coach-notes`,
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
  const opKey = keys.op(p.tenant, p.op);
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
}

/** kiff/agents' note: a run with a Notify paused or ended. */
export interface Note {
  run: string;
  agent: string;
  status: string;
  ref: string;
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
