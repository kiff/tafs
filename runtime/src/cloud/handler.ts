import { createHash, randomBytes, randomUUID } from "node:crypto";
import { JOBS, jobById } from "../jobs.js";
import { type Agents, AgentsError, type Run, type Schedule, type Session } from "./agents.js";
import { claimDraft, type Fetch, KIFFError, type KIFFIdentity, whoIs } from "./kiff.js";
import { MAX_DEVICES, PushError, type PushStore, receiveAlert, registration, type Sender, signedByKIFF } from "./push.js";
import { setupDefinition } from "./setupdef.js";
import { definition } from "./tafdef.js";

/**
 * tafs cloud: the few routes the phone needs that act for the person signed
 * in. Every taf runs on kiff/agents, which tafs cloud calls with its own AWS
 * role, naming the person's KIFF account. Everything else the phone asks KIFF
 * directly. Every route acts for the KIFF account of the person signed in,
 * and only on that account's tafs.
 *
 *   GET  /v1/jobs
 *   GET  /v1/tafs
 *   POST /v1/tafs                       {name, job, draft_id + claim_secret | agent_key, cron?, timezone?, budget_cents?, notes?}
 *   POST /v1/tafs/{id}/pause | resume | run
 *   GET  /v1/tafs/{id}/runs
 *   GET  /v1/tafs/{id}/runs/{session}   the run's report
 *   GET  /v1/tafs/{id}/conversation     the thread: the owner's messages and the taf's
 *   POST /v1/tafs/{id}/messages         {text}: into the live chat, or a new one
 *   GET  /v1/tafs/{id}/memory           what the taf remembers: its notes
 *   DELETE /v1/tafs/{id}/memory/{note}  forget one note
 *   DELETE /v1/tafs/{id}                remove it: its schedule, runs, chats, notes and key; free its name
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

/** What tafs cloud calls on kiff/agents. */
export type AgentsAPI = Pick<Agents,
  "putAgent" | "deleteAgent" | "startRun" | "sessions" | "session" | "memories" | "forget" |
  "createSchedule" | "getSchedule" | "switchSchedule" | "trigger" | "scheduleRuns" | "cancelRun">;

export interface Deps {
  agents: AgentsAPI;
  /** The tafs' KIFF agent keys, where kiff/agents reads them. */
  keys: Keys;
  /** Each KIFF account's alert secret, where kiff/agents checks KIFF's alerts with it. */
  accounts: Accounts;
  /** What the app shows about each taf. */
  tafs: Tafs;
  /**
   * Claims a taf's name on an account, durably and across containers. False
   * when it is already claimed. Released when starting fails.
   */
  names: Names;
  /** Message slots per taf and 10-minute window, claimed durably (the flood guard). */
  slots: Slots;
  /** Drafts whose key was claimed and kept, so a failed start can resume. */
  claims: Claims;
  /** Phones that get each account's alerts, and the secrets that check KIFF's deliveries. */
  push: PushStore;
  /** Apple push. */
  apns: Sender;
  /** Removals begun and not finished, so the app can finish them after a refresh. */
  removals: Removals;
  /** Hands a KIFF alert tafs cloud has checked to kiff/agents, as KIFF signed it, so a paused run goes on. */
  forward: (signature: string, raw: string) => Promise<void>;
  fetch?: Fetch;
  kiffBase?: string;
  now?: () => Date;
  /** A new taf's id. */
  newID?: () => string;
}

export interface Keys {
  /** Keeps a taf's KIFF agent key, replacing one kept before. */
  put(tenant: string, tafID: string, key: string): Promise<void>;
  /** Deletes it for good. Already gone counts as done. */
  remove(tenant: string, tafID: string): Promise<void>;
}

export interface Accounts {
  /**
   * Keeps the secret KIFF signs `account`'s alerts with, for kiff/agents,
   * naming the tenant it runs them under. Cheap to repeat: the same secret
   * again writes nothing.
   */
  put(account: string, tenant: string, secret: string): Promise<void>;
}

/** A taf, as tafs cloud keeps it. Its agent, schedule, runs and notes are kiff/agents', under the same id. */
export interface Taf {
  id: string;
  name: string;
  job: string;
  /** The KIFF agent the taf acts as: the holder of its Cards. */
  kiffAgent: string;
  /** The start that claimed its name; only its removal frees it. */
  nameClaim: string;
  created: string;
  /** Its removal began: gone for everything but finishing it. */
  removing?: boolean;
}

export interface Tafs {
  list(tenant: string): Promise<Taf[]>;
  get(tenant: string, id: string): Promise<Taf | undefined>;
  put(tenant: string, taf: Taf): Promise<void>;
  remove(tenant: string, id: string): Promise<void>;
}

export interface Names {
  /** Claims (tenant, name) for `owner`, the start that will make the taf. True only when nobody holds it. */
  claim(tenant: string, name: string, owner: string): Promise<boolean>;
  /**
   * Frees (tenant, name) only while `owner` holds it, or nobody named one (a
   * claim from before owners). A newer start's claim on the same name stays.
   */
  release(tenant: string, name: string, owner: string): Promise<void>;
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
 * A KIFF draft whose key tafs cloud claimed (a claim works once) and kept for
 * the taf it is starting. Never the key itself: Keys holds it.
 */
export interface Claim {
  /** sha256 of the draft's claim secret: only whoever holds it may resume. */
  secret: string;
  /** The taf the draft is starting; a resume must be for the same name. */
  name: string;
  agentID: string;
  /** The taf's id, which its key is kept under. */
  tafID: string;
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
  /** Sessions listed to find them. */
  sessionsScanned: 50,
  /** A chat active this recently takes the message instead of a new one. */
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
    return alert(event, deps);
  }
  if (parts[1] === "push") return push(event, method, parts, deps);
  if (parts[1] !== "tafs" && parts[1] !== "setup") throw new HTTPError(404, "not found");

  const me = await person(event, deps);

  if (parts[1] === "setup") {
    // The account's Setup (kiff/agents#91): its chat uses the taf routes, by its id.
    if (parts.length === 2 && method === "GET") {
      const setup = await setupOf(me, deps);
      return { setup: setup ? await view(setup, me, deps) : null };
    }
    if (parts.length === 2 && method === "POST") {
      need(me, "cards.manage");
      return startSetup(event, me, deps);
    }
    throw new HTTPError(404, "not found");
  }

  if (parts.length === 2 && method === "GET") {
    const tafs = (await deps.tafs.list(me.tenantID)).filter((t) => !t.removing && t.job !== SETUP_JOB);
    // Removed tafs aren't listed; ones whose removal didn't finish are, so it can be finished.
    return { tafs: await Promise.all(tafs.map((t) => view(t, me, deps))), removing: await deps.removals.list(me.tenantID) };
  }
  if (parts.length === 2 && method === "POST") {
    need(me, "cards.manage");
    return start(event, me, deps);
  }

  if (parts.length === 3 && method === "DELETE") {
    need(me, "cards.manage");
    const id = parts[2] ?? "";
    const taf = await owned(me, id, deps, { removing: true }).catch((err) => {
      if (err instanceof HTTPError && err.status === 404) return undefined;
      throw err;
    });
    if (taf) return remove(taf, me, deps);
    // The record goes only once every other step is done, so a removal left
    // without it lacks only its own entry: finishing is clearing that. Only a
    // kiff/agents taf's: a removal begun on Managed Agents (a depl_ id) still
    // holds its deployment, vaults and name there, which retiring Managed
    // Agents (#42) deletes along with its entry.
    const left = TAF_ID.test(id) ? (await deps.removals.list(me.tenantID)).find((r) => r.id === id) : undefined;
    if (!left) throw new HTTPError(404, "no such taf");
    await deps.removals.done(me.tenantID, id);
    return { taf: id, name: left.name, kiff_agent: null, done: true, removed: [], failed: [] };
  }

  const taf = await owned(me, parts[2] ?? "", deps);
  const action = parts[3];
  if (parts.length === 4 && method === "POST" && (action === "pause" || action === "resume")) {
    need(me, "cards.manage");
    const s = await agentsCall(() => deps.agents.switchSchedule(me.tenantID, taf.id, action === "resume"));
    return { taf: await view(taf, me, deps, s) };
  }
  if (parts.length === 4 && method === "POST" && action === "run") {
    need(me, "cards.manage");
    const run = await agentsCall(() => deps.agents.trigger(me.tenantID, taf.id));
    return { session: run.session };
  }
  if (parts.length === 4 && method === "GET" && action === "runs") {
    return { runs: (await jobRuns(me, taf, deps)).map((r) => ({ session: r.session, at: iso(r.created), error: r.error || null })) };
  }
  if (parts.length === 5 && method === "GET" && action === "runs") {
    const session = parts[4] ?? "";
    const run = (await jobRuns(me, taf, deps)).find((r) => r.session === session);
    if (!run) throw new HTTPError(404, "no such run");
    return { session, report: report(run) };
  }
  if (parts.length === 4 && method === "GET" && action === "conversation") {
    const { messages, components } = await conversation(me, taf, deps);
    return { messages, components };
  }
  if (parts.length === 4 && method === "POST" && action === "stop") {
    need(me, "cards.manage");
    return stop(event, me, taf, deps);
  }
  if (parts.length === 4 && method === "POST" && action === "messages") {
    need(me, "cards.manage");
    return message(event, me, taf, deps);
  }
  if (parts.length === 4 && method === "GET" && action === "memory") {
    return { notes: await notes(me, taf, deps) };
  }
  if (parts.length === 5 && method === "DELETE" && action === "memory") {
    need(me, "cards.manage");
    return forget(me, taf, parts[4] ?? "", deps);
  }
  throw new HTTPError(404, "not found");
}

/**
 * KIFF's alert: pushed to the account's phones, then handed to kiff/agents as
 * KIFF signed it, so the run it holds goes on once its call is answered.
 * Only an alert tafs cloud has checked is handed on, and kiff/agents checks it
 * again. If handing it on fails, the answer is 502 so KIFF delivers it again:
 * the phones are told once, however often it comes.
 */
async function alert(event: HTTPEvent, deps: Deps): Promise<unknown> {
  const signature = header(event, "kiff-signature");
  const raw = rawBody(event);
  const now = deps.now?.() ?? new Date();
  const out = await receiveAlert(signature, raw, {
    push: deps.push,
    apns: deps.apns,
    claim: (key, until) => deps.slots.claim(key, until),
    held: (key) => deps.slots.held(key),
    keep: (key, until) => deps.slots.keep(key, until),
    release: (key) => deps.slots.release(key),
    now,
  });
  try {
    // kiff/agents checks the alert with the account's secret: the one that
    // signed this alert, which an account registered before kiff/agents, or
    // a phone with an older target, hasn't handed it yet.
    const tenant = str((JSON.parse(raw) as { tenant_id?: unknown }).tenant_id);
    const { secrets } = await deps.push.get(tenant);
    const signer = secrets.find((secret) => signedByKIFF(signature, raw, [secret], now));
    if (signer) await deps.accounts.put(tenant, tenant, signer);
    await deps.forward(signature, raw);
  } catch (err) {
    console.error(`alert: kiff/agents: ${(err as Error).message}`);
    // Asked again, a few times. An alert kiff/agents keeps not taking (a
    // refusal, a 5xx, no answer, or the secret not written) would otherwise
    // be a 502 until KIFF switches the target off, and the phones' pushes
    // with it: past FORWARD_TRIES, the alert is let go and its run goes on
    // when the hold expires, as without an answer.
    const eventID = str((JSON.parse(raw) as { event_id?: unknown }).event_id);
    if (await forwardTried(deps, eventID, now)) {
      // FORWARD_TRIES deliveries were answered 502; this is the one after them.
      console.error(`alert: kiff/agents didn't take event ${eventID} in ${FORWARD_TRIES + 1} tries; its run goes on when the hold expires`);
      return out;
    }
    throw new HTTPError(502, "the alert didn't reach the tafs' runtime; send it again");
  }
  return out;
}

/** How often KIFF is asked to send an alert again that kiff/agents didn't take. */
export const FORWARD_TRIES = 5;

/** Takes one of the event's tries; true once all are taken. A day after, they free themselves. */
async function forwardTried(deps: Deps, eventID: string, now: Date): Promise<boolean> {
  if (!eventID) return false;
  const until = new Date(now.getTime() + 24 * 3600_000);
  for (let n = 0; n < FORWARD_TRIES; n++) {
    if (await deps.slots.claim(`forward#${eventID}#${n}`, until)) return false;
  }
  return true;
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
 * signing secret. The secret is kept for kiff/agents too, which checks the
 * same alerts with it before a paused run goes on.
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
    // kiff/agents checks the same alerts: give it the newest secret, the one
    // handed over now or, for a phone that registered before, the one kept.
    const newest = r.target?.secret ?? secrets[0];
    if (newest && answers) await deps.accounts.put(me.tenantID, me.tenantID, newest);
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

async function start(event: HTTPEvent, me: KIFFIdentity, deps: Deps): Promise<unknown> {
  const body = parseBody(event);
  const name = str(body.name).trim();
  if (!name || name.length > 60) throw new HTTPError(400, "give the taf a name of 60 characters at most");
  let job;
  try { job = jobById(str(body.job)); } catch (err) { throw new HTTPError(400, (err as Error).message); }
  const cron = body.cron === undefined ? job.cron : str(body.cron).trim();
  if (!calmEnough(cron)) throw new HTTPError(400, "a taf runs at most every 15 minutes");
  const timezone = body.timezone === undefined ? "" : str(body.timezone).trim();
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
  const agentKey = draftID ? "" : str(body.agent_key).trim();
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
  if ((await deps.tafs.list(me.tenantID)).some((t) => t.name === name && t.job !== SETUP_JOB)) throw taken;
  // This start's claim on the name; the taf carries it, so only its removal frees it.
  const nameClaim = randomUUID();
  if (!(await deps.names.claim(me.tenantID, name, nameClaim))) {
    // Held by an attempt that is still running, or whose outcome was never
    // learned. Nothing proves such an attempt can't still make the taf, so
    // the name stays taken; another name always works.
    throw new HTTPError(409, `${name} is taken by an earlier start whose outcome isn't known; it may still appear. Choose another name`);
  }

  let tafID = (deps.newID ?? newTafID)();
  try {
    if (draftID) ({ agentID, tafID } = await draftKey(me, name, draftID, claimSecret, tafID, deps, check));
    else await deps.keys.put(me.tenantID, tafID, agentKey);
  } catch (err) {
    await deps.names.release(me.tenantID, name, nameClaim);
    if (err instanceof HTTPError) throw err;
    throw new HTTPError(502, `the taf's key couldn't be kept: ${(err as Error).message}`);
  }

  // From the definition on, what this start made is undone if it fails before
  // the taf is on record, and only then is the name freed: a definition or a
  // schedule nobody can see would otherwise run, or block the name for good.
  const taf: Taf = { id: tafID, name, job: job.id, kiffAgent: agentID, nameClaim, created: (deps.now?.() ?? new Date()).toISOString() };
  try {
    await deps.agents.putAgent(me.tenantID, tafID, definition(tafID, job, { budgetCents: budget, ownerNotes: notesText }));
    const schedule: Schedule = { id: tafID, agent: tafID, cron, time_zone: timezone, input: job.kickoff, enabled: true };
    await deps.agents.createSchedule(me.tenantID, schedule).catch(async (err) => {
      // A retry of a start whose schedule was made: the same schedule.
      if (err instanceof AgentsError && err.status === 409 && await deps.agents.getSchedule(me.tenantID, tafID)) return;
      throw err;
    });
    await deps.tafs.put(me.tenantID, taf);
  } catch (err) {
    // A failed write may still have landed, the record's included: all of it
    // is undone, and the name is freed only if every part is gone.
    const recordGone = await deps.tafs.remove(me.tenantID, tafID).then(() => true, () => false);
    const agentGone = await deps.agents.deleteAgent(me.tenantID, tafID).then(() => true, () => false);
    const undone = recordGone && agentGone;
    // A draft's key stays kept, so a retry resumes with it; an agent_key is the caller's to send again.
    if (undone && !draftID) await deps.keys.remove(me.tenantID, tafID).catch(() => undefined);
    if (undone) await deps.names.release(me.tenantID, name, nameClaim);
    throw new HTTPError(err instanceof AgentsError && err.status < 500 ? 400 : 502, `the taf couldn't be started: ${(err as Error).message}`);
  }
  if (draftID) await deps.claims.remove(me.tenantID, draftID).catch(() => undefined);

  // It runs once now as well.
  let run: Run;
  try { run = await deps.agents.trigger(me.tenantID, tafID); } catch (err) {
    throw new HTTPError(502, `${name} is started, but its first run didn't start (${(err as Error).message}). Run it now from the app`);
  }
  return { taf: await view(taf, me, deps), session: run.session };
}

/**
 * The agent and the taf id a confirmed KIFF draft's key is kept under. The
 * claim works once, so the key is kept at once, on record: a retry after a
 * later failure resumes with that key and that id instead of claiming again.
 * Made only with the name held, so a second tap or a taken name never spends it.
 */
async function draftKey(me: KIFFIdentity, name: string, draftID: string, claimSecret: string, tafID: string,
  deps: Deps, check: (key: string) => Promise<string>): Promise<{ agentID: string; tafID: string }> {
  const secret = createHash("sha256").update(claimSecret).digest("hex");
  const held = await deps.claims.get(me.tenantID, draftID);
  if (held) {
    if (held.secret !== secret) throw new HTTPError(401, "the draft's claim secret is required");
    if (held.name !== name) throw new HTTPError(409, `this draft is starting the taf ${held.name}; start it with that name`);
    return { agentID: held.agentID, tafID: held.tafID };
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
  // From here the key exists only in this request until it is kept and on
  // record. A failure in between loses it, and a retry answers 410 above.
  const agentID = await check(key);
  await deps.keys.put(me.tenantID, tafID, key).catch((err) => { throw new HTTPError(502, (err as Error).message); });
  await deps.claims.put(me.tenantID, draftID, { secret, name, agentID, tafID })
    .catch((err) => { throw new HTTPError(502, (err as Error).message); });
  return { agentID, tafID };
}

/** Setup's job, on its record: it has no schedule and isn't listed with the tafs. */
export const SETUP_JOB = "setup";
/** The name Setup claims: no taf can take it, since a taf's name is trimmed. */
const SETUP_NAME_CLAIM = " setup";

/** The account's Setup, if it has one. */
async function setupOf(me: KIFFIdentity, deps: Deps): Promise<Taf | undefined> {
  return (await deps.tafs.list(me.tenantID)).find((t) => t.job === SETUP_JOB && !t.removing);
}

/**
 * Starts the account's Setup, the agent that connects tools in the setup
 * chat (kiff/agents#91), once. Its key comes from a draft the phone
 * submitted with no Cards (no_tools, kiff-cloud#1254): tafs cloud claims it
 * itself, so the key never reaches the phone, as for a taf.
 */
async function startSetup(event: HTTPEvent, me: KIFFIdentity, deps: Deps): Promise<unknown> {
  const body = parseBody(event);
  const draftID = str(body.draft_id).trim();
  const claimSecret = str(body.claim_secret).trim();
  if (!draftID || !claimSecret) throw new HTTPError(400, "send draft_id and claim_secret");
  if (!/^drf_[A-Za-z0-9]+$/.test(draftID)) throw new HTTPError(400, "that is not a KIFF draft id");
  if (await setupOf(me, deps)) throw new HTTPError(409, "this account's Setup is already started");
  const nameClaim = randomUUID();
  if (!(await deps.names.claim(me.tenantID, SETUP_NAME_CLAIM, nameClaim))) {
    throw new HTTPError(409, "Setup is being started already; open it in a moment");
  }
  const check = async (key: string): Promise<string> => {
    let agent: KIFFIdentity;
    try { agent = await whoIs(key, deps.fetch, deps.kiffBase); } catch (err) {
      if (err instanceof KIFFError && err.status === 502) throw new HTTPError(502, "KIFF didn't answer about Setup's key; try again");
      throw new HTTPError(400, "KIFF did not accept Setup's key");
    }
    if (agent.tenantID !== me.tenantID) throw new HTTPError(400, "Setup's key belongs to another KIFF account");
    if (!agent.apiKey || !agent.agentID) throw new HTTPError(400, "Setup's key is not bound to an agent");
    return agent.agentID;
  };
  let tafID = (deps.newID ?? newTafID)();
  let agentID: string;
  try {
    ({ agentID, tafID } = await draftKey(me, SETUP_NAME_CLAIM, draftID, claimSecret, tafID, deps, check));
  } catch (err) {
    await deps.names.release(me.tenantID, SETUP_NAME_CLAIM, nameClaim);
    if (err instanceof HTTPError) throw err;
    throw new HTTPError(502, `Setup's key couldn't be kept: ${(err as Error).message}`);
  }
  const setup: Taf = { id: tafID, name: "Setup", job: SETUP_JOB, kiffAgent: agentID, nameClaim, created: (deps.now?.() ?? new Date()).toISOString() };
  try {
    await deps.agents.putAgent(me.tenantID, tafID, setupDefinition(tafID));
    await deps.tafs.put(me.tenantID, setup);
  } catch (err) {
    const recordGone = await deps.tafs.remove(me.tenantID, tafID).then(() => true, () => false);
    const agentGone = await deps.agents.deleteAgent(me.tenantID, tafID).then(() => true, () => false);
    if (recordGone && agentGone) await deps.names.release(me.tenantID, SETUP_NAME_CLAIM, nameClaim);
    throw new HTTPError(err instanceof AgentsError && err.status < 500 ? 400 : 502, `Setup couldn't be started: ${(err as Error).message}`);
  }
  await deps.claims.remove(me.tenantID, draftID).catch(() => undefined);
  return { setup: await view(setup, me, deps) };
}

/** A taf's id on kiff/agents. */
const TAF_ID = /^taf-[0-9a-f]{20}$/;

function newTafID(): string {
  return `taf-${randomBytes(10).toString("hex")}`;
}

/** The caller's taf. Someone else's answers exactly like a missing one; a taf being removed is gone but for finishing that. */
async function owned(me: KIFFIdentity, id: string, deps: Deps, opts: { removing?: boolean } = {}): Promise<Taf> {
  if (!TAF_ID.test(id)) throw new HTTPError(404, "no such taf");
  const taf = await deps.tafs.get(me.tenantID, id);
  if (!taf || (taf.removing && !opts.removing)) throw new HTTPError(404, "no such taf");
  return taf;
}

/**
 * Removes a taf, each step safe to repeat: a second remove finishes what the
 * first left. kiff/agents removes the agent and everything kept for it,
 * withdrawing its held calls first and its schedule with it; nothing else is
 * deleted unless that worked, so a taf that may still run keeps its key. Its
 * Cards are KIFF's: the phone revokes them, before calling this.
 */
async function remove(taf: Taf, me: KIFFIdentity, deps: Deps) {
  const removed: string[] = [];
  const failed: string[] = [];
  const step = async (what: string[], work: () => Promise<unknown>) => {
    try { await work(); removed.push(...what); } catch (err) {
      console.error(`remove ${taf.id}: ${what.join(", ")}: ${(err as Error).message}`);
      failed.push(...what);
    }
  };

  // On record before anything changes: if this stops anywhere, even with the
  // answer lost, the app sees it listed and finishes it.
  await deps.removals.add(me.tenantID, { id: taf.id, name: taf.name });
  if (!taf.removing) await deps.tafs.put(me.tenantID, { ...taf, removing: true });
  await step(["schedule", "history", "memory"], () => deps.agents.deleteAgent(me.tenantID, taf.id));
  if (failed.length === 0) await step(["key"], () => deps.keys.remove(me.tenantID, taf.id));
  // Only this taf's own claim: if a newer start holds the name, it keeps it.
  if (failed.length === 0) await step(["name"], () => deps.names.release(me.tenantID, taf.name, taf.nameClaim));
  if (failed.length === 0) {
    await deps.tafs.remove(me.tenantID, taf.id);
    await deps.removals.done(me.tenantID, taf.id);
  }
  return { taf: taf.id, name: taf.name, kiff_agent: taf.kiffAgent || null, done: failed.length === 0, removed, failed };
}

/** The taf's job runs, newest first: what its schedule started, by the clock or by hand. */
async function jobRuns(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<Run[]> {
  return agentsCall(() => deps.agents.scheduleRuns(me.tenantID, taf.id, 20));
}

/** The run's report: the agent's last answer, and whether it is still working (a run waiting on the owner is). */
function report(run: Run) {
  const status = run.status === "COMPLETED" ? "idle" : run.status === "ERROR" || run.status === "CANCELLED" ? "terminated" : "running";
  return { status, text: run.output ?? "" };
}

/** A message in a taf's thread. A run's opening instruction is the owner's, of kind "kickoff". */
/**
 * A component the agent showed in the chat (kiff/agents#91, contract 1): the
 * app draws the latest of each id, where it first appeared.
 */
export interface ThreadComponent {
  at: string;
  session: string;
  run: string;
  seq: number;
  component: unknown;
}

export interface ThreadMessage {
  at: string;
  from: "owner" | "taf";
  kind: "kickoff" | "message";
  text: string;
  session: string;
}

/**
 * The taf's thread, oldest first: its latest job runs and its latest chats,
 * from kiff/agents' sessions (newest first, a bounded number), with what each
 * kept: the owner's messages and the taf's. A run's first owner message is
 * the job's instruction, shown as such.
 */
async function conversation(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<{ messages: ThreadMessage[]; components: ThreadComponent[]; sessions: Session[] }> {
  const all = await agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned));
  const picked = [
    ...all.filter((s) => s.kind === "run").slice(0, CHAT.runsInThread),
    ...all.filter((s) => s.kind === "chat").slice(0, CHAT.chatsInThread),
  ];
  const read = await Promise.all(picked.map((s) => deps.agents.session(me.tenantID, s.id).catch(() => undefined)));
  const messages: ThreadMessage[] = [];
  const components: ThreadComponent[] = [];
  for (const r of read) {
    if (!r) continue;
    const seenRun = new Set<string>();
    for (const e of r.events) {
      if (e.kind === "component") {
        components.push({ at: iso(e.time), session: r.session.id, run: e.run, seq: e.seq, component: e.body });
        continue;
      }
      if (e.kind !== "owner_message" && e.kind !== "agent_message" && e.kind !== "report") continue;
      const text = typeof (e.body as { text?: unknown })?.text === "string" ? ((e.body as { text: string }).text).trim() : "";
      if (!text) continue;
      if (e.kind === "owner_message") {
        // Each job run opens with the job's instruction: not something the owner typed.
        const kind = r.session.kind === "run" && !seenRun.has(e.run) ? "kickoff" : "message";
        seenRun.add(e.run);
        messages.push({ at: iso(e.time), from: "owner", kind, text, session: r.session.id });
      } else {
        messages.push({ at: iso(e.time), from: "taf", kind: "message", text, session: r.session.id });
      }
    }
  }
  messages.sort((a, b) => a.at.localeCompare(b.at));
  components.sort((a, b) => a.at.localeCompare(b.at) || a.seq - b.seq);
  return { messages: messages.slice(-CHAT.maxThread), components: components.slice(-CHAT.maxThread), sessions: picked };
}

/**
 * The owner writes to the taf. The message is a run in the taf's latest chat
 * when that chat was active in the last 30 minutes, so the taf answers with
 * the conversation in mind; otherwise it starts a new chat. Either way the
 * taf has only its Card's tools, and remembers what its notes say.
 */
async function message(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  const text = str(parseBody(event).text).trim();
  if (!text || text.length > CHAT.maxText) throw new HTTPError(400, `write 1 to ${CHAT.maxText} characters`);
  const now = (deps.now?.() ?? new Date()).getTime();

  // The flood guard: one of 10 slots per taf and 10-minute window, claimed with a
  // conditional write, so it holds however many chats the messages start and
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

  const sessions = await agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned));
  const latest = sessions.find((s) => s.kind === "chat");
  if (latest && now - Date.parse(latest.updated) < CHAT.liveMinutes * 60_000) {
    try {
      const run = await deps.agents.startRun(me.tenantID, taf.id, text, latest.id);
      return { session: run.session, new_session: false };
    } catch (err) {
      // Refused, for example a chat that is gone or busy: a new one takes the message.
      if (!(err instanceof AgentsError && err.status >= 400 && err.status < 500)) throw err;
    }
  }
  const run = await agentsCall(() => deps.agents.startRun(me.tenantID, taf.id, text));
  return { session: run.session, new_session: true };
}

/**
 * Stop: the owner ends what the agent is doing in one of its sessions (the
 * plan's Stop in the setup chat, kiff/agents#91). The session's latest run is
 * cancelled; one that has already ended stays as it ended.
 */
async function stop(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  const session = str(parseBody(event).session).trim();
  if (!session) throw new HTTPError(400, "name the session to stop");
  const read = await agentsCall(() => deps.agents.session(me.tenantID, session));
  if (read.session.agent !== taf.id) throw new HTTPError(404, "no such session");
  const run = read.events.at(-1)?.run;
  if (!run) return { stopped: false };
  try {
    const out = await deps.agents.cancelRun(me.tenantID, taf.id, run);
    return { stopped: true, run: out.id, status: out.status };
  } catch (err) {
    // Already over: nothing is left to stop.
    if (err instanceof AgentsError && err.status === 409) return { stopped: false };
    throw err instanceof AgentsError ? new HTTPError(err.status >= 500 ? 502 : err.status, err.message) : err;
  }
}

/** A note the taf keeps. Its name is its id. */
export interface Note {
  id: string;
  path: string;
  content: string;
  updated_at: string;
}

/** The taf's newest notes, newest first. */
async function notes(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<Note[]> {
  const all = await agentsCall(() => deps.agents.memories(me.tenantID, taf.id));
  return all
    .map((n) => ({ id: n.name, path: n.name, content: n.text, updated_at: iso(n.updated) }))
    .sort((a, b) => b.updated_at.localeCompare(a.updated_at))
    .slice(0, MEMORY.shown);
}

/** Forgets one note, by its name, as the app sends it (escaped). Gone means gone: kiff/agents keeps no versions. */
async function forget(me: KIFFIdentity, taf: Taf, escapedName: string, deps: Deps): Promise<unknown> {
  let name: string;
  try { name = decodeURIComponent(escapedName); } catch { throw new HTTPError(404, "no such note"); }
  if (!name) throw new HTTPError(404, "no such note");
  await agentsCall(() => deps.agents.forget(me.tenantID, taf.id, name));
  return { forgotten: name };
}

/** A taf as the app shows it: paused when its schedule is off, with its next and last runs. */
async function view(taf: Taf, me: KIFFIdentity, deps: Deps, schedule?: Schedule) {
  const [s, last] = await Promise.all([
    schedule ? Promise.resolve(schedule) : deps.agents.getSchedule(me.tenantID, taf.id).catch(() => undefined),
    deps.agents.scheduleRuns(me.tenantID, taf.id, 1).then((r) => r[0], () => undefined),
  ]);
  return {
    id: taf.id,
    name: taf.name,
    job: taf.job,
    /** The KIFF agent the taf acts as: the holder of its Cards. */
    kiff_agent: taf.kiffAgent || null,
    status: s && !s.enabled ? "paused" : "active",
    next_run: s?.enabled && s.next ? iso(s.next) : null,
    last_run: last ? iso(last.created) : null,
  };
}

/** A kiff/agents call a route depends on: its 404 is the taf's, anything else says the runtime didn't answer. */
async function agentsCall<T>(work: () => Promise<T>): Promise<T> {
  try { return await work(); } catch (err) {
    if (err instanceof AgentsError && err.status === 404) throw new HTTPError(404, "not found");
    if (err instanceof AgentsError && err.status < 500) throw new HTTPError(err.status, err.message);
    throw new HTTPError(502, "the tafs' runtime didn't answer; try again");
  }
}

/** A time as the app reads it: RFC 3339 in UTC, milliseconds at most. */
function iso(t: string): string {
  const d = new Date(t);
  return Number.isNaN(d.getTime()) ? t : d.toISOString();
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
