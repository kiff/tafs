import type Anthropic from "@anthropic-ai/sdk";
import { META } from "../config.js";
import { JOBS, jobById } from "../jobs.js";
import { listTafs, type Setup, startTaf } from "../runtime.js";
import { type Fetch, KIFFError, type KIFFIdentity, whoIs } from "./kiff.js";

/**
 * tafs cloud: the few routes the phone needs that hold the Anthropic key.
 * Everything else the phone asks KIFF directly. Every route acts for the
 * KIFF account of the person signed in, and only on that account's tafs.
 *
 *   GET  /v1/jobs
 *   GET  /v1/tafs
 *   POST /v1/tafs                       {name, job, agent_key, cron?, timezone?, budget_cents?}
 *   POST /v1/tafs/{id}/pause | resume | run
 *   GET  /v1/tafs/{id}/runs
 *   GET  /v1/tafs/{id}/runs/{session}   the run's report
 */

/** The Lambda function URL event, as much of it as we read. */
export interface HTTPEvent {
  rawPath: string;
  headers?: Record<string, string | undefined>;
  body?: string | null;
  isBase64Encoded?: boolean;
  requestContext: { http: { method: string } };
}

export interface HTTPResult {
  statusCode: number;
  headers: Record<string, string>;
  body: string;
}

export interface Deps {
  anthropic: () => Promise<Anthropic>;
  setup: () => Promise<Setup>;
  /**
   * Claims a taf's name on an account, durably and across containers. False
   * when it is already claimed. Released when starting fails.
   */
  names: Names;
  fetch?: Fetch;
  kiffBase?: string;
}

export interface Names {
  /** Claims (tenant, name). True only when nobody holds it. */
  claim(tenant: string, name: string): Promise<boolean>;
  release(tenant: string, name: string): Promise<void>;
}

/** Most one run of a taf started from the phone may cost: $10. */
export const MAX_BUDGET_CENTS = 1000;

class HTTPError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export async function handle(event: HTTPEvent, deps: Deps): Promise<HTTPResult> {
  try {
    return json(200, await route(event, deps));
  } catch (err) {
    if (err instanceof HTTPError || err instanceof KIFFError) return json(err.status, { error: err.message });
    console.error(err);
    return json(500, { error: "something went wrong on tafs cloud" });
  }
}

async function route(event: HTTPEvent, deps: Deps): Promise<unknown> {
  const method = event.requestContext.http.method.toUpperCase();
  const parts = event.rawPath.split("/").filter(Boolean);
  if (parts[0] !== "v1") throw new HTTPError(404, "not found");

  if (method === "GET" && parts.length === 2 && parts[1] === "jobs") {
    return { jobs: JOBS.map((j) => ({ id: j.id, name: j.name, summary: j.summary, cron: j.cron })) };
  }
  if (parts[1] !== "tafs") throw new HTTPError(404, "not found");

  const me = await person(event, deps);
  const client = await deps.anthropic();

  if (parts.length === 2 && method === "GET") {
    const tafs = (await listTafs(client)).filter((d) => d.metadata?.[META.tenant] === me.tenantID);
    return { tafs: tafs.map(view) };
  }
  if (parts.length === 2 && method === "POST") {
    need(me, "cards.manage");
    return start(event, me, client, deps);
  }

  const taf = await owned(client, me, parts[2] ?? "");
  const action = parts[3];
  if (parts.length === 4 && method === "POST" && (action === "pause" || action === "resume")) {
    need(me, "cards.manage");
    const d = action === "pause" ? await client.beta.deployments.pause(taf.id) : await client.beta.deployments.unpause(taf.id);
    return { taf: view(d) };
  }
  if (parts.length === 4 && method === "POST" && action === "run") {
    need(me, "cards.manage");
    const run = await client.beta.deployments.run(taf.id);
    if (!run.session_id) throw new HTTPError(503, `the run did not start${run.error ? `: ${run.error.message}` : ""}`);
    return { session: run.session_id };
  }
  if (parts.length === 4 && method === "GET" && action === "runs") {
    return { runs: await runs(client, taf.id) };
  }
  if (parts.length === 5 && method === "GET" && action === "runs") {
    const session = parts[4] ?? "";
    if (!(await runs(client, taf.id)).some((r) => r.session === session)) throw new HTTPError(404, "no such run");
    return { session, report: await report(client, session) };
  }
  throw new HTTPError(404, "not found");
}

async function person(event: HTTPEvent, deps: Deps): Promise<KIFFIdentity> {
  const auth = header(event, "authorization");
  const token = auth.toLowerCase().startsWith("bearer ") ? auth.slice(7).trim() : "";
  const me = await whoIs(token, deps.fetch, deps.kiffBase);
  if (me.apiKey) throw new HTTPError(403, "sign in as a person, not with a key");
  return me;
}

function need(me: KIFFIdentity, capability: string): void {
  if (!me.capabilities.includes(capability)) throw new HTTPError(403, "your role on this KIFF account cannot manage Cards");
}

async function start(event: HTTPEvent, me: KIFFIdentity, client: Anthropic, deps: Deps): Promise<unknown> {
  const body = parseBody(event);
  const name = str(body.name).trim();
  if (!name || name.length > 60) throw new HTTPError(400, "give the taf a name of 60 characters at most");
  let job;
  try { job = jobById(str(body.job)); } catch (err) { throw new HTTPError(400, (err as Error).message); }
  const cron = body.cron === undefined ? undefined : str(body.cron);
  if (cron !== undefined && !calmEnough(cron)) throw new HTTPError(400, "a taf runs at most every 15 minutes");
  const budget = body.budget_cents === undefined ? undefined : Number(body.budget_cents);
  if (budget !== undefined && (!Number.isInteger(budget) || budget <= 0 || budget > MAX_BUDGET_CENTS)) {
    throw new HTTPError(400, `a run's budget is 1 to ${MAX_BUDGET_CENTS} cents`);
  }

  // The agent key must be an agent's key on this same KIFF account.
  const agentKey = str(body.agent_key).trim();
  let agent: KIFFIdentity;
  try { agent = await whoIs(agentKey, deps.fetch, deps.kiffBase); } catch { throw new HTTPError(400, "KIFF did not accept the agent key"); }
  if (agent.tenantID !== me.tenantID) throw new HTTPError(400, "the agent key belongs to another KIFF account");
  if (!agent.apiKey || !agent.agentID) throw new HTTPError(400, "the key is not bound to an agent");

  // Two taps, or a retry, must not start two tafs: the name is claimed first.
  const taken = new HTTPError(409, `you already have a taf named ${name}`);
  const exists = async () =>
    (await listTafs(client)).some((d) => d.metadata?.[META.tenant] === me.tenantID && d.metadata?.[META.taf] === name);
  if (await exists()) throw taken;
  if (!(await deps.names.claim(me.tenantID, name))) {
    // Held by an attempt that is still running, or whose outcome was never
    // learned. Nothing proves such an attempt can't still make the taf, so
    // the name stays taken; another name always works.
    throw new HTTPError(409, `${name} is taken by an earlier start whose outcome isn't known; it may still appear. Choose another name`);
  }

  let asked = false;
  let created = false;
  try {
    const { deployment, sessionID } = await startTaf(client, await deps.setup(), {
      name,
      job: job.id,
      kiffKey: agentKey,
      cron,
      timezone: body.timezone === undefined ? undefined : str(body.timezone),
      budgetCents: budget,
      metadata: { [META.tenant]: me.tenantID, [META.kiffAgent]: agent.agentID },
      onCreating: () => { asked = true; },
      onCreated: () => { created = true; },
    });
    return { taf: view(deployment), session: sessionID };
  } catch (err) {
    // The name is freed only when no taf can exist: the deployment was never
    // asked for, or Anthropic refused it outright. Once it exists, or when the
    // answer was lost, the claim stays for good.
    if (!created && (!asked || refused(err))) await deps.names.release(me.tenantID, name);
    throw new HTTPError(502, (err as Error).message);
  }
}

/** True for an answer that means the request was refused, so nothing was made. */
function refused(err: unknown): boolean {
  const status = (err as { status?: unknown })?.status;
  return typeof status === "number" && status >= 400 && status < 500 && ![408, 409, 429].includes(status);
}

async function owned(client: Anthropic, me: KIFFIdentity, id: string) {
  let d;
  try { d = await client.beta.deployments.retrieve(id); } catch { throw new HTTPError(404, "no such taf"); }
  // Someone else's taf answers exactly like a missing one.
  if (d.metadata?.[META.tenant] !== me.tenantID || !d.metadata?.[META.taf]) throw new HTTPError(404, "no such taf");
  return d;
}

async function runs(client: Anthropic, deploymentID: string) {
  const out: Array<{ session: string | null; at: string; error: string | null }> = [];
  for await (const r of client.beta.deploymentRuns.list({ deployment_id: deploymentID })) {
    out.push({ session: r.session_id ?? null, at: r.created_at, error: r.error ? r.error.message : null });
    if (out.length >= 20) break;
  }
  return out;
}

/** The run's report: the agent's last message, and whether it is still working. */
async function report(client: Anthropic, sessionID: string) {
  const session = await client.beta.sessions.retrieve(sessionID);
  let text = "";
  for await (const event of client.beta.sessions.events.list(sessionID)) {
    if (event.type === "agent.message") {
      const t = event.content.filter((b) => b.type === "text").map((b) => (b as { text: string }).text).join("\n");
      if (t) text = t;
    }
  }
  return { status: session.status, text };
}

function view(d: Anthropic.Beta.BetaManagedAgentsDeployment) {
  return {
    id: d.id,
    name: d.metadata?.[META.taf] ?? d.name,
    job: d.metadata?.[META.job] ?? "",
    /** The KIFF agent the taf acts as: the holder of its Cards. */
    kiff_agent: d.metadata?.[META.kiffAgent] ?? null,
    status: d.status,
    next_run: d.schedule?.upcoming_runs_at?.[0] ?? null,
    last_run: d.schedule?.last_run_at ?? null,
  };
}

/**
 * True when a cron expression fires at most every 15 minutes: every gap
 * between the minutes it fires at, including the one across the hour, is at
 * least 15. Steps and lists are expanded first, so equivalent forms agree.
 */
export function calmEnough(cron: string): boolean {
  const fields = cron.trim().split(/\s+/);
  if (fields.length !== 5) return false;
  const mins = minutes(fields[0] ?? "");
  if (!mins || mins.length === 0) return false;
  if (mins.length === 1) return true;
  for (let i = 1; i < mins.length; i++) if ((mins[i] ?? 0) - (mins[i - 1] ?? 0) < 15) return false;
  return 60 - (mins.at(-1) ?? 0) + (mins[0] ?? 0) >= 15;
}

/** The minutes a cron minute field fires at, sorted; undefined when not understood. */
function minutes(field: string): number[] | undefined {
  const out = new Set<number>();
  for (const part of field.split(",")) {
    const step = /^(\*|\d+)\/(\d+)$/.exec(part);
    if (step) {
      const from = step[1] === "*" ? 0 : Number(step[1]);
      const by = Number(step[2]);
      if (by < 1 || from > 59) return undefined;
      for (let m = from; m <= 59; m += by) out.add(m);
    } else if (/^\d+$/.test(part) && Number(part) <= 59) {
      out.add(Number(part));
    } else {
      return undefined; // "*", ranges and names fire too often or aren't worth reading
    }
  }
  return [...out].sort((a, b) => a - b);
}

function header(event: HTTPEvent, name: string): string {
  for (const [k, v] of Object.entries(event.headers ?? {})) if (k.toLowerCase() === name) return v ?? "";
  return "";
}

function parseBody(event: HTTPEvent): Record<string, unknown> {
  const raw = event.isBase64Encoded ? Buffer.from(event.body ?? "", "base64").toString("utf8") : (event.body ?? "");
  try {
    const v = JSON.parse(raw || "{}");
    if (v && typeof v === "object" && !Array.isArray(v)) return v as Record<string, unknown>;
  } catch { /* fall through */ }
  throw new HTTPError(400, "send a JSON object");
}

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

function json(status: number, body: unknown): HTTPResult {
  return { statusCode: status, headers: { "content-type": "application/json", "cache-control": "no-store" }, body: JSON.stringify(body) };
}
