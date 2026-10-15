import { createHash } from "node:crypto";
import type Anthropic from "@anthropic-ai/sdk";
import { META, memoryResource } from "../config.js";
import { JOBS, jobById } from "../jobs.js";
import { ensureMemory, keepKey, listTafs, type Setup, startTaf } from "../runtime.js";
import { claimDraft, type Fetch, KIFFError, type KIFFIdentity, whoIs } from "./kiff.js";
import { MAX_DEVICES, PushError, type PushStore, receiveAlert, registration, type Sender } from "./push.js";

/**
 * tafs cloud: the few routes the phone needs that hold the Anthropic key.
 * Everything else the phone asks KIFF directly. Every route acts for the
 * KIFF account of the person signed in, and only on that account's tafs.
 *
 *   GET  /v1/jobs
 *   GET  /v1/tafs
 *   POST /v1/tafs                       {name, job, draft_id + claim_secret | agent_key, cron?, timezone?, budget_cents?, notes?}
 *   POST /v1/tafs/{id}/pause | resume | run
 *   GET  /v1/tafs/{id}/runs
 *   GET  /v1/tafs/{id}/runs/{session}   the run's report
 *   GET  /v1/tafs/{id}/conversation     the thread: the owner's messages and the taf's
 *   POST /v1/tafs/{id}/messages         {text}: into the live session, or a new one
 *   GET  /v1/tafs/{id}/memory           what the taf remembers: its notes
 *   DELETE /v1/tafs/{id}/memory/{note}  forget one note
 *   DELETE /v1/tafs/{id}                remove it: stop it, delete its memory and vault, free its name
 *   POST /v1/push                       {device_token, environment, alert_target?}: this phone gets the account's alerts
 *   DELETE /v1/push/{token}             it stops
 *   POST /v1/kiff/alerts                KIFF's signed held-call alerts (no sign-in: the signature is the check)
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
  /** Message slots per taf and 10-minute window, claimed durably (the flood guard). */
  slots: Slots;
  /** Drafts whose key was claimed and kept in a vault, so a failed start can resume. */
  claims: Claims;
  /** Phones that get each account's alerts, and the secrets that check KIFF's deliveries. */
  push: PushStore;
  /** Apple push. */
  apns: Sender;
  /** Removals begun and not finished, so the app can finish them after a refresh. */
  removals: Removals;
  fetch?: Fetch;
  kiffBase?: string;
  now?: () => Date;
}

export interface Names {
  /** Claims (tenant, name). True only when nobody holds it. */
  claim(tenant: string, name: string): Promise<boolean>;
  release(tenant: string, name: string): Promise<void>;
}

export interface Slots {
  /** Claims `key` until `expiresAt`. True only when nobody holds it, or its claim has expired. */
  claim(key: string, expiresAt: Date): Promise<boolean>;
  /** Holds a claimed `key` until a new time. */
  keep(key: string, expiresAt: Date): Promise<void>;
  /** Whether `key` is held and unexpired. */
  held(key: string): Promise<boolean>;
  release(key: string): Promise<void>;
}

/**
 * A KIFF draft whose key tafs cloud claimed (a claim works once) and put in a
 * vault. Never the key itself: the vault holds it.
 */
export interface Claim {
  /** sha256 of the draft's claim secret: only whoever holds it may resume. */
  secret: string;
  /** The taf the draft is starting; a resume must be for the same name. */
  name: string;
  agentID: string;
  vaultID: string;
}

/** A taf whose removal began: kept until every step is done. */
export interface Removal {
  id: string;
  name: string;
}

export interface Removals {
  list(tenant: string): Promise<Removal[]>;
  add(tenant: string, removal: Removal): Promise<void>;
  done(tenant: string, id: string): Promise<void>;
}

export interface Claims {
  get(tenant: string, draftID: string): Promise<Claim | undefined>;
  put(tenant: string, draftID: string, claim: Claim): Promise<void>;
  remove(tenant: string, draftID: string): Promise<void>;
}

/** Most one run of a taf started from the phone may cost: $10. */
export const MAX_BUDGET_CENTS = 1000;

/** Chat limits: a message's length, the thread's length, and how often the owner may write. */
export const CHAT = {
  maxText: 2000,
  maxThread: 100,
  /** Sessions read for the thread: the latest runs, and the latest chats. */
  runsInThread: 5,
  chatsInThread: 5,
  /** A session active this recently takes the message instead of a new one. */
  liveMinutes: 30,
  floodMessages: 10,
  floodMinutes: 10,
} as const;

/** Memory limits: the owner's first note, and how many notes the app is shown. */
export const MEMORY = { maxNotes: 2000, shown: 100 } as const;

class HTTPError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export async function handle(event: HTTPEvent, deps: Deps): Promise<HTTPResult> {
  try {
    return json(200, await route(event, deps));
  } catch (err) {
    if (err instanceof HTTPError || err instanceof KIFFError || err instanceof PushError) return json(err.status, { error: err.message });
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
  if (method === "POST" && parts.length === 3 && parts[1] === "kiff" && parts[2] === "alerts") {
    return receiveAlert(header(event, "kiff-signature"), rawBody(event), {
      push: deps.push,
      apns: deps.apns,
      claim: (key, until) => deps.slots.claim(key, until),
      held: (key) => deps.slots.held(key),
      keep: (key, until) => deps.slots.keep(key, until),
      release: (key) => deps.slots.release(key),
      now: deps.now?.() ?? new Date(),
    });
  }
  if (parts[1] === "push") return push(event, method, parts, deps);
  if (parts[1] !== "tafs") throw new HTTPError(404, "not found");

  const me = await person(event, deps);
  const client = await deps.anthropic();

  if (parts.length === 2 && method === "GET") {
    const tafs = (await listTafs(client)).filter((d) => d.metadata?.[META.tenant] === me.tenantID);
    // Archived tafs aren't listed; ones whose removal didn't finish are, so it can be finished.
    return { tafs: tafs.map(view), removing: await deps.removals.list(me.tenantID) };
  }
  if (parts.length === 2 && method === "POST") {
    need(me, "cards.manage");
    return start(event, me, client, deps);
  }

  if (parts.length === 3 && method === "DELETE") {
    need(me, "cards.manage");
    return remove(client, await owned(client, me, parts[2] ?? "", { archived: true }), me, deps);
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
  if (parts.length === 4 && method === "GET" && action === "conversation") {
    return { messages: (await conversation(client, taf)).messages };
  }
  if (parts.length === 4 && method === "POST" && action === "messages") {
    need(me, "cards.manage");
    return message(event, me, client, taf, deps);
  }
  if (parts.length === 4 && method === "GET" && action === "memory") {
    return { notes: await notes(client, taf) };
  }
  if (parts.length === 5 && method === "DELETE" && action === "memory") {
    need(me, "cards.manage");
    return forget(client, taf, parts[4] ?? "");
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

/**
 * A phone signs up for its account's alerts, or stops. Any person may add
 * their phone, but only phones of people who may answer held calls
 * (holds.answer) are alerted, and only they may hand over the KIFF target's
 * signing secret.
 */
async function push(event: HTTPEvent, method: string, parts: string[], deps: Deps): Promise<unknown> {
  const me = await person(event, deps);
  if (method === "POST" && parts.length === 2) {
    const r = registration(parseBody(event));
    const answers = me.capabilities.includes("holds.answer");
    if (r.target && !answers) throw new HTTPError(403, "your role on this KIFF account can't answer held calls");
    const at = (deps.now?.() ?? new Date()).toISOString();
    const { devices, secrets } = await deps.push.get(me.tenantID);
    if (!devices.some((d) => d.token === r.token) && devices.length >= MAX_DEVICES) {
      throw new HTTPError(409, `an account gets alerts on ${MAX_DEVICES} phones at most; remove one first`);
    }
    if (r.target) await deps.push.addSecret(me.tenantID, r.target.id, r.target.secret, at);
    await deps.push.addDevice(me.tenantID, { token: r.token, environment: r.environment, subject: me.subject, answers, at });
    return { device: true, answers, alerts: Boolean(r.target) || secrets.length > 0 };
  }
  if (method === "DELETE" && parts.length === 3) {
    const token = (parts[2] ?? "").toLowerCase();
    const { devices } = await deps.push.get(me.tenantID);
    // A person removes their own phones; someone who manages Cards, any of the account's.
    const mine = devices.find((d) => d.token === token && (d.subject === me.subject || me.capabilities.includes("cards.manage")));
    if (mine) await deps.push.removeDevice(me.tenantID, token);
    return { removed: Boolean(mine) };
  }
  throw new HTTPError(404, "not found");
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
  const notesText = body.notes === undefined ? undefined : str(body.notes).trim();
  if (notesText !== undefined && notesText.length > MEMORY.maxNotes) {
    throw new HTTPError(400, `keep the notes to ${MEMORY.maxNotes} characters`);
  }
  const budget = body.budget_cents === undefined ? undefined : Number(body.budget_cents);
  if (budget !== undefined && (!Number.isInteger(budget) || budget <= 0 || budget > MAX_BUDGET_CENTS)) {
    throw new HTTPError(400, `a run's budget is 1 to ${MAX_BUDGET_CENTS} cents`);
  }

  // The agent's key comes one of two ways. From a confirmed KIFF draft
  // (draft_id + claim_secret), tafs cloud claims it itself, so the key never
  // reaches the phone; it is claimed only once the name is ours, below. Or as
  // agent_key, from the CLI and app builds from before drafts.
  const draftID = str(body.draft_id).trim();
  const claimSecret = str(body.claim_secret).trim();
  if (!draftID !== !claimSecret) throw new HTTPError(400, "send both draft_id and claim_secret");
  if (draftID && !/^drf_[A-Za-z0-9]+$/.test(draftID)) throw new HTTPError(400, "that is not a KIFF draft id");
  let agentKey = draftID ? "" : str(body.agent_key).trim();
  if (!draftID && !agentKey) throw new HTTPError(400, "send draft_id and claim_secret, or agent_key");
  const check = async (key: string): Promise<string> => {
    // The agent key must be an agent's key on this same KIFF account.
    let agent: KIFFIdentity;
    try { agent = await whoIs(key, deps.fetch, deps.kiffBase); } catch (err) {
      if (err instanceof KIFFError && err.status === 502) throw new HTTPError(502, "KIFF didn't answer about the agent key; try again");
      throw new HTTPError(400, "KIFF did not accept the agent key");
    }
    if (agent.tenantID !== me.tenantID) throw new HTTPError(400, "the agent key belongs to another KIFF account");
    if (!agent.apiKey || !agent.agentID) throw new HTTPError(400, "the key is not bound to an agent");
    return agent.agentID;
  };
  let agentID = agentKey ? await check(agentKey) : "";

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

  let vaultID: string | undefined;
  if (draftID) {
    try {
      ({ agentID, vaultID } = await draftKey(me, name, job.id, draftID, claimSecret, client, deps, check));
    } catch (err) {
      await deps.names.release(me.tenantID, name);
      throw err;
    }
  }

  let asked = false;
  let created = false;
  try {
    const { deployment, sessionID } = await startTaf(client, await deps.setup(), {
      name,
      job: job.id,
      kiffKey: agentKey || undefined,
      vaultID,
      cron,
      timezone: body.timezone === undefined ? undefined : str(body.timezone),
      budgetCents: budget,
      metadata: { [META.tenant]: me.tenantID, [META.kiffAgent]: agentID },
      notes: notesText,
      onCreating: () => { asked = true; },
      onCreated: () => { created = true; },
    });
    if (draftID) await deps.claims.remove(me.tenantID, draftID).catch(() => undefined);
    return { taf: view(deployment), session: sessionID };
  } catch (err) {
    // The name is freed only when no taf can exist: the deployment was never
    // asked for, or Anthropic refused it outright. Once it exists, or when the
    // answer was lost, the claim stays for good. A draft's vault stays on
    // record either way, so a retry resumes with the key already claimed.
    if (!created && (!asked || refused(err))) await deps.names.release(me.tenantID, name);
    throw new HTTPError(502, (err as Error).message);
  }
}

/**
 * The agent and the vault holding the key of a confirmed KIFF draft. The claim
 * works once, so the key goes straight into a vault, kept on record: a retry
 * after a later failure resumes with that vault instead of claiming again.
 * Made only with the name held, so a second tap or a taken name never spends it.
 */
async function draftKey(me: KIFFIdentity, name: string, job: string, draftID: string, claimSecret: string,
  client: Anthropic, deps: Deps, check: (key: string) => Promise<string>): Promise<{ agentID: string; vaultID: string }> {
  const secret = createHash("sha256").update(claimSecret).digest("hex");
  const held = await deps.claims.get(me.tenantID, draftID);
  if (held) {
    if (held.secret !== secret) throw new HTTPError(401, "the draft's claim secret is required");
    if (held.name !== name) throw new HTTPError(409, `this draft is starting the taf ${held.name}; start it with that name`);
    return { agentID: held.agentID, vaultID: held.vaultID };
  }
  let key: string;
  try {
    key = (await claimDraft(draftID, claimSecret, deps.fetch, deps.kiffBase)).key;
  } catch (err) {
    const status = err instanceof KIFFError ? err.status : 502;
    // Claimed before, and nothing here kept its key: it can't be had again.
    // Gone, so the app takes the draft's Cards back and starts a new one.
    if (status === 409) throw new HTTPError(410, "KIFF already gave out this draft's key, and the start that took it didn't finish. Start again: its Cards are taken back");
    throw new HTTPError(status >= 400 && status < 500 ? status : 502, `KIFF did not give the draft's key: ${(err as Error).message}`);
  }
  // From here the key exists only in this request until it is in a vault on
  // record. A failure in between loses it, and a retry answers 410 above.
  const agentID = await check(key);
  const vaultID = await keepKey(client, { name, job, kiffKey: key, metadata: { [META.tenant]: me.tenantID, [META.kiffAgent]: agentID } })
    .catch((err) => { throw new HTTPError(502, (err as Error).message); });
  await deps.claims.put(me.tenantID, draftID, { secret, name, agentID, vaultID })
    .catch((err) => { throw new HTTPError(502, (err as Error).message); });
  return { agentID, vaultID };
}

/** True for an answer that means the request was refused, so nothing was made. */
function refused(err: unknown): boolean {
  const status = (err as { status?: unknown })?.status;
  return typeof status === "number" && status >= 400 && status < 500 && ![408, 409, 429].includes(status);
}

async function owned(client: Anthropic, me: KIFFIdentity, id: string, opts: { archived?: boolean } = {}) {
  let d;
  try { d = await client.beta.deployments.retrieve(id); } catch { throw new HTTPError(404, "no such taf"); }
  // Someone else's taf answers exactly like a missing one.
  if (d.metadata?.[META.tenant] !== me.tenantID || !d.metadata?.[META.taf]) throw new HTTPError(404, "no such taf");
  // A removed taf is gone for everything but finishing its removal.
  if (d.archived_at && !opts.archived) throw new HTTPError(404, "no such taf");
  return d;
}

/**
 * Removes a taf, each step safe to repeat: a second remove finishes what the
 * first left. Stopping it comes first, and nothing else is deleted unless that
 * worked, so a taf that may still run keeps its memory. Its Cards are KIFF's:
 * the phone revokes them, before calling this.
 */
async function remove(client: Anthropic, taf: Deployment, me: KIFFIdentity, deps: Deps) {
  const removed: string[] = [];
  const failed: string[] = [];
  const name = taf.metadata?.[META.taf] ?? taf.name;
  const step = async (what: string, work: () => Promise<unknown>) => {
    try { await work(); removed.push(what); } catch (err) {
      // Already gone counts as done.
      if ((err as { status?: unknown }).status === 404) { removed.push(what); return; }
      console.error(`remove ${taf.id}: ${what}: ${(err as Error).message}`);
      failed.push(what);
    }
  };

  // On record before anything changes: if this stops anywhere, even with the
  // answer lost, the app sees it listed and finishes it.
  await deps.removals.add(me.tenantID, { id: taf.id, name });
  if (taf.archived_at) removed.push("schedule");
  else await step("schedule", () => client.beta.deployments.archive(taf.id));
  if (failed.length === 0) {
    const stores = new Set<string>(taf.resources?.flatMap((r) => (r.type === "memory_store" ? [r.memory_store_id] : [])) ?? []);
    const noted = taf.metadata?.[META.memory];
    if (noted) stores.add(noted);
    for (const id of stores) await step("memory", () => client.beta.memoryStores.delete(id));
    for (const id of taf.vault_ids ?? []) await step("key", () => client.beta.vaults.delete(id));
    if (failed.length === 0) await step("name", () => deps.names.release(me.tenantID, name));
    if (failed.length === 0) await deps.removals.done(me.tenantID, taf.id);
  }
  return {
    taf: taf.id,
    name,
    kiff_agent: taf.metadata?.[META.kiffAgent] ?? null,
    done: failed.length === 0,
    removed: [...new Set(removed)],
    failed: [...new Set(failed)],
  };
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

type Deployment = Anthropic.Beta.BetaManagedAgentsDeployment;
type Session = Anthropic.Beta.Sessions.BetaManagedAgentsSession;

/** A message in a taf's thread. A run's opening instruction is the owner's, of kind "kickoff". */
export interface ThreadMessage {
  at: string;
  from: "owner" | "taf";
  kind: "kickoff" | "message";
  text: string;
  session: string;
}

/**
 * The taf's sessions the thread reads, oldest first: its latest scheduled or
 * manual runs (they carry the deployment's id) and its latest chats (marked
 * with tafs_chat_for). Sessions are listed by the taf's agent, newest first,
 * a bounded number at most, since one agent serves every taf on its job.
 */
async function tafSessions(client: Anthropic, taf: Deployment): Promise<Session[]> {
  const out: Session[] = [];
  let runs = 0, chats = 0, scanned = 0;
  for await (const s of client.beta.sessions.list({ agent_id: taf.agent.id, order: "desc" })) {
    if (++scanned > 200) break;
    if (s.deployment_id === taf.id && runs < CHAT.runsInThread) { out.push(s); runs++; }
    else if (s.metadata?.[META.chatFor] === taf.id && chats < CHAT.chatsInThread) { out.push(s); chats++; }
    if (runs >= CHAT.runsInThread && chats >= CHAT.chatsInThread) break;
  }
  return out.sort((a, b) => a.created_at.localeCompare(b.created_at));
}

async function conversation(client: Anthropic, taf: Deployment): Promise<{ messages: ThreadMessage[]; sessions: Session[] }> {
  const sessions = await tafSessions(client, taf);
  const messages: ThreadMessage[] = [];
  for (const s of sessions) {
    let first = true;
    for await (const e of client.beta.sessions.events.list(s.id)) {
      if (e.type !== "user.message" && e.type !== "agent.message") continue;
      const text = e.content.filter((b) => b.type === "text").map((b) => (b as { text: string }).text).join("\n").trim();
      const at = e.processed_at ?? s.updated_at;
      if (e.type === "user.message") {
        // A run opens with the job's instruction: shown as such, not as something the owner typed.
        const kind = first && s.deployment_id === taf.id ? "kickoff" : "message";
        first = false;
        if (text) messages.push({ at, from: "owner", kind, text, session: s.id });
      } else if (text) {
        messages.push({ at, from: "taf", kind: "message", text, session: s.id });
      }
    }
  }
  messages.sort((a, b) => a.at.localeCompare(b.at));
  return { messages: messages.slice(-CHAT.maxThread), sessions };
}

/**
 * The owner writes to the taf. The message goes into the taf's latest session
 * when it is still live (idle or running, active in the last 30 minutes);
 * otherwise a new session starts with it, with the taf's agent, environment,
 * vault and budget. Either way the taf has only its Card's tools.
 */
async function message(event: HTTPEvent, me: KIFFIdentity, client: Anthropic, taf: Deployment, deps: Deps): Promise<unknown> {
  const text = str(parseBody(event).text).trim();
  if (!text || text.length > CHAT.maxText) throw new HTTPError(400, `write 1 to ${CHAT.maxText} characters`);
  const now = (deps.now?.() ?? new Date()).getTime();

  // The flood guard: one of 10 slots per taf and 10-minute window, claimed with a
  // conditional write, so it holds however many sessions the messages start and
  // whatever the thread shows. A window is fixed, so at most 20 can straddle two.
  const windowMs = CHAT.floodMinutes * 60_000;
  const bucket = Math.floor(now / windowMs);
  const expires = new Date((bucket + 2) * windowMs);
  let slot = false;
  for (let n = 0; n < CHAT.floodMessages && !slot; n++) {
    slot = await deps.slots.claim(`rate#${me.tenantID}#${taf.id}#${bucket}#${n}`, expires);
  }
  if (!slot) {
    throw new HTTPError(429, `that's ${CHAT.floodMessages} messages in ${CHAT.floodMinutes} minutes; wait a little before writing again`);
  }

  const { sessions } = await conversation(client, taf);

  const userMessage = { type: "user.message" as const, content: [{ type: "text" as const, text }] };
  const latest = sessions.at(-1);
  const live = latest && ["idle", "running", "rescheduling"].includes(latest.status) &&
    now - Date.parse(latest.updated_at) < CHAT.liveMinutes * 60_000;
  if (latest && live) {
    try {
      await client.beta.sessions.events.send(latest.id, { events: [userMessage] });
      return { session: latest.id, new_session: false };
    } catch (err) {
      // Refused, for example a session paused at its budget: a new one takes the message.
      if (!refused(err)) throw err;
    }
  }

  // A chat remembers what the runs learned, and the other way around. A taf
  // started before memory gets its store here, by whichever chat claims the
  // lock: two first chats at once must not make two stores. The other one
  // starts without memory this time.
  let memory = taf.metadata?.[META.memory];
  if (!memory && await deps.slots.claim(`memory#${taf.id}`, new Date(now + 10 * 60_000))) {
    memory = await ensureMemory(client, taf);
  }
  const session = await client.beta.sessions.create({
    agent: { type: "agent", id: taf.agent.id, version: taf.agent.version },
    environment_id: taf.environment_id,
    vault_ids: taf.vault_ids,
    ...(memory ? { resources: [memoryResource(memory)] } : {}),
    ...(taf.budget ? { budget: taf.budget } : {}),
    title: `${taf.metadata?.[META.taf] ?? taf.name}: chat`,
    metadata: { [META.chatFor]: taf.id, [META.tenant]: me.tenantID, [META.taf]: taf.metadata?.[META.taf] ?? taf.name },
    initial_events: [userMessage],
  });
  return { session: session.id, new_session: true };
}

/** A note the taf keeps: one file in its memory store. */
export interface Note {
  id: string;
  path: string;
  content: string;
  updated_at: string;
}

/**
 * The taf's newest notes, newest first. The API doesn't list by date, so every
 * note is read before the newest are picked. A taf without a memory store yet
 * has none.
 */
async function notes(client: Anthropic, taf: Deployment): Promise<Note[]> {
  const store = taf.metadata?.[META.memory];
  if (!store) return [];
  const out: Note[] = [];
  for await (const m of client.beta.memoryStores.memories.list(store, { view: "full" })) {
    if (m.type !== "memory") continue;
    out.push({ id: m.id, path: m.path, content: m.content ?? "", updated_at: m.updated_at });
  }
  return out.sort((a, b) => b.updated_at.localeCompare(a.updated_at)).slice(0, MEMORY.shown);
}

/** Forgets one note. Only a note in this taf's own store can be named. */
async function forget(client: Anthropic, taf: Deployment, noteID: string): Promise<unknown> {
  const store = taf.metadata?.[META.memory];
  if (!store || !/^mem_[A-Za-z0-9]+$/.test(noteID)) throw new HTTPError(404, "no such note");
  try {
    await client.beta.memoryStores.memories.delete(noteID, { memory_store_id: store });
  } catch (err) {
    if ((err as { status?: unknown })?.status === 404) throw new HTTPError(404, "no such note");
    throw err;
  }
  return { forgotten: noteID };
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

/** The body exactly as sent, which is what a signature covers. */
function rawBody(event: HTTPEvent): string {
  return event.isBase64Encoded ? Buffer.from(event.body ?? "", "base64").toString("utf8") : (event.body ?? "");
}

function parseBody(event: HTTPEvent): Record<string, unknown> {
  const raw = rawBody(event);
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
