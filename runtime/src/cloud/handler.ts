import { createHash, randomBytes, randomUUID } from "node:crypto";
import { JOBS, jobById } from "../jobs.js";
import { aiFor, type AIView, base, periodName, planOf, refusedForAI, view as aiView } from "./aiuse.js";
import { type AgentDefinition, type AgentVersion, type Agents, AgentsError, type BlockingRun, type AIBound, type AgentsHalt, type CardRevision, type Notify, type Run, type RunStreamEvent, type Schedule, type Session, type VersionState } from "./agents.js";
import { cardRevisions, claimDraft, draftConfirmed, draftInfo, draftStatus, dropped, type Fetch, haltedInKIFF, haltInKIFF, issued, KIFF_API, KIFFError, type KIFFHalt, type KIFFIdentity, redraft, restoreDraft, whoIs } from "./kiff.js";
import type { ReadyRun } from "./forward.js";
import { alertTarget, MAX_DEVICES, PACK_REQUESTED, PushError, type PushStore, receiveAlert, registration, type Sender, signedByKIFF } from "./push.js";
import { setupDefinition } from "./setupdef.js";
import { definition, digest, keyRef, ownerNotesIn } from "./tafdef.js";
import { byHolder, freeColor, readKIFF, stateOf, summary } from "./home.js";
import { heldCards, improveContext, ownerWords, recentCalls } from "./improve.js";
import { COACH_LINES, type CoachCall, type CoachStore, keys as coachKeys, parseNote, refTenant, serveMCP, type Task, TASK_KEPT_MS, type ToolAnswer, ToolRefusal } from "./coach.js";
import { type Keys as PermitKeys, publishedKeys } from "./permit.js";
import { Steps } from "./steps.js";
import { addDoor, answerKnock, connectLink, DoorError, doorRunEnded, type DoorsContext, type DoorsDeps, keepBound, knocksOf, listDoors, type Owner, pair, parseDoorMessage, removeDoor, removeTafDoors, sentOf, setOpen, unpair } from "./doors/doors.js";
import { inbound, isDoorPath } from "./doors/inbound.js";

/**
 * tafs cloud: the few routes the phone needs that act for the person signed
 * in. Every taf runs on kiff/agents, which tafs cloud calls with its own AWS
 * role, naming the person's KIFF account. Everything else the phone asks KIFF
 * directly. Every route acts for the KIFF account of the person signed in,
 * and only on that account's tafs.
 *
 *   GET  /v1/jobs
 *   GET  /v1/home                       the app's list: each taf's state, colour and Card summary, and the account's other agents with Cards
 *   GET  /v1/tafs
 *   POST /v1/tafs                       {name, job, draft_id + claim_secret | agent_key, cron?, timezone?, budget_cents?, notes?, unscheduled?}
 *   POST /v1/tafs/{id}/pause | resume | run
 *   GET  /v1/tafs/{id}/runs
 *   GET  /v1/tafs/{id}/runs/{session}   the run's report
 *   GET  /v1/tafs/{id}/conversation     the thread: the owner's messages and the taf's
 *   GET  /v1/tafs/{id}/conversation/{session}  one chat of it (kiff/tafs#73)
 *   GET  /v1/tafs/{id}/chats            the taf's chats, newest first, each by what the owner asked (kiff/tafs#73, #82)
 *   POST /v1/tafs/{id}/chats/{session}/archive  hide a chat from the list and the thread (kiff/tafs#82)
 *   GET  /v1/tafs/{id}/chats/archived   the chats the owner archived, to find and reopen one (kiff/tafs#97)
 *   POST /v1/tafs/{id}/chats/{session}/unarchive  put one back in the list (kiff/tafs#97)
 *   POST /v1/tafs/{id}/messages         {text, session?, new_chat?}: into the live chat, a chosen one, or a new one
 *   POST /v1/tafs/{id}/steer            {run, text}: to the run at work, read at its next step (kiff/tafs#64)
 *   POST /v1/tafs/{id}/holds/{exception}/continue  the run that paused on that held call goes on, once KIFF has answered it (kiff/tafs#206)
 *   GET  /v1/tafs/{id}/versions         the taf's versions, oldest first, the live one marked, and a switch under way (kiff/tafs#107)
 *   POST /v1/tafs/{id}/versions         {note, notes?, draft?, from?, agent?}: its next version, as Setup drafted it, once per result (kiff/tafs#107); nothing goes live
 *   POST /v1/tafs/{id}/versions/{n}/switch  {redraft?, stop_blocking?}: begin the switch to version n, with the Card draft it waits for: nothing of the taf runs until it ends. A run in the way is a 409 naming it (blocking); stop_blocking stops it first (kiff/tafs#206)
 *   DELETE /v1/tafs/{id}/switching      drop a switch whose Card isn't confirmed yet
 *   POST /v1/tafs/{id}/versions/{n}/trial  {input?}: a trial of version n, its writes decided by KIFF, nothing sent
 *   GET  /v1/tafs/{id}/trials/{run}     a trial so far: its run and what KIFF decided for each write
 *   POST /v1/tafs/{id}/versions/{n}/live   make version n live, once its Card revision is confirmed in KIFF
 *   POST /v1/tafs/{id}/improve          {text}: a new conversation with the account's Setup, opening on this taf (kiff/tafs#107)
 *   GET  /v1/tafs/{id}/memory           what the taf remembers: its notes
 *   DELETE /v1/tafs/{id}/memory/{note}  forget one note
 *   DELETE /v1/tafs/{id}                remove it: its schedule, runs, chats, notes and key; free its name
 *   POST /v1/push                       {device_token, environment, alert_target?}: this phone gets the account's alerts
 *   DELETE /v1/push/{token}             it stops
 *   POST /v1/alerts                     {alert_target}: KIFF's alert target for the account, with no phone's alerts (kiff/tafs#206)
 *   GET  /v1/alerts                     whether tafs cloud holds KIFF's alert secret for the account
 *   POST /v1/kiff/alerts                KIFF's signed held-call alerts (no sign-in: the signature is the check)
 *   POST /v1/mcp                        Coach's tools, through KIFF's gateway only (no sign-in: each call's KIFF permit is the check; kiff/tafs#206)
 *   POST /v1/runs/ended                 kiff/agents' note that a task Coach handed a taf, or a door's run, paused or ended (no sign-in: an unknown ref is dropped, and the run is read back)
 *   GET  /v1/tafs/{id}/doors            the taf's doors, the apps a door can open on here, and its open knocks
 *   POST /v1/tafs/{id}/doors            {app, code?, phone_number_id?, waba_id?, account?, label?}: open a door
 *   POST /v1/tafs/{id}/doors/connect    {app}: a sign-in link for a connector app
 *   PATCH /v1/tafs/{id}/doors/{door}    {open}
 *   DELETE /v1/tafs/{id}/doors/{door}
 *   POST /v1/tafs/{id}/doors/{door}/keys  {id, name?}: pair a sender
 *   DELETE /v1/tafs/{id}/doors/{door}/keys/{key}
 *   POST /v1/tafs/{id}/doors/knocks/{knock}  {action: do | pair | ignore, say?}
 *   POST /v1/doors/email | /v1/doors/whatsapp | /v1/doors/catalog  the doors' webhooks (no sign-in: each provider's signature is the check)
 *   GET  /doors/whatsapp/connect, /doors/connected  the pages a door's signup opens and comes back through
 */

/** The Lambda function URL event, as much of it as we read. */
export interface HTTPEvent {
  rawPath: string;
  rawQueryString?: string;
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
  "createSchedule" | "getSchedule" | "switchSchedule" | "trigger" | "scheduleRuns" | "cancelRun" | "continueRun" | "continueHeld" | "claimTafDraft" | "getRun" | "streamRun" | "steerRun" | "getAgent" |
  "versions" | "switchVersion" | "startTrial" | "trial" | "beginSwitch" | "replaceSwitchDraft" | "cancelSwitch" | "createVersion" | "getVersion" |
  "halt" | "resume" | "halted" | "eraseTenant" | "aiSpent" | "runs">;

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
  /** The chats each taf's owner archived (kiff/tafs#82). */
  archived: Archived;
  /**
   * What tafs cloud keeps for an account beyond its tafs (its phones, alert
   * secrets, claimed drafts, chat flood slots), erased when the owner
   * deletes the account (kiff/tafs#185). Missing: accounts can't be deleted
   * here.
   */
  accountData?: AccountData;
  /** Hands a KIFF alert tafs cloud has checked to kiff/agents, as KIFF signed it, so a paused run goes on. */
  /** Hands KIFF's alert to kiff/agents; the run its answer left ready, if any (forward.ts). */
  forward: (signature: string, raw: string) => Promise<ReadyRun | undefined | void>;
  /**
   * What Coach's tools keep (kiff/tafs#206): used permits, each operation's
   * answer, the tasks Coach handed over, and the lines it hasn't read yet.
   * Missing: the tools aren't served here.
   */
  coach?: CoachStore;
  /** KIFF's permit keys, by kid; missing, the ones KIFF publishes. */
  permitKeys?: PermitKeys;
  /** A taf's doors: where work reaches it from outside the app. Missing: no doors here. */
  doors?: DoorsDeps;
  fetch?: Fetch;
  kiffBase?: string;
  now?: () => Date;
  /** Waits, between a switch's tries while what blocks it stops (tests pass one that doesn't). */
  sleep?: (ms: number) => Promise<void>;
  /** A new taf's id. */
  newID?: () => string;
}

export interface AccountData {
  /** Deletes everything kept for the account, beyond its tafs. Safe to repeat. */
  erase(tenant: string): Promise<void>;
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
  /**
   * The definition's inputs that only the start knew (kiff/tafs#69): a run's
   * budget and the owner's start notes, so the definition can be built again.
   * Absent on a taf started before they were kept.
   */
  budgetCents?: number;
  ownerNotes?: string;
  /** The digest of the definition kiff/agents holds for it, as last written (`digest`). */
  definition?: string;
  /**
   * Its tools can't find its work by themselves (kiff/tafs#138): it has no
   * running schedule, and works on what the owner tells it in its chat.
   */
  unscheduled?: boolean;
  /** Its colour in the app (`home.COLORS`), kept so a new taf never takes it. Given at its start. */
  color?: number;
  /**
   * The versions made from Setup's results (kiff/tafs#107), by the result
   * they came from: a result makes its version once, however often the app
   * hands it over.
   */
  versionsFrom?: Record<string, number>;
}

export interface Tafs {
  list(tenant: string): Promise<Taf[]>;
  get(tenant: string, id: string): Promise<Taf | undefined>;
  put(tenant: string, taf: Taf): Promise<void>;
  /**
   * Sets `taf` only while its entry is still exactly `read`: false, and nothing
   * written, when anything changed it since (a removal, an upgrade).
   */
  putIf(tenant: string, taf: Taf, read: Taf): Promise<boolean>;
  remove(tenant: string, id: string): Promise<void>;
  /** Every account with tafs on record: what an upgrade goes through (kiff/tafs#69). */
  tenants(): Promise<string[]>;
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

/**
 * The chats a taf's owner archived (kiff/tafs#82), kept by tafs cloud: kiff/agents
 * keeps the chat itself, and has no say in what the owner's list shows.
 */
export interface Archived {
  /** The taf's archived chats' sessions. */
  list(tenant: string, tafID: string): Promise<string[]>;
  /** Archives one, kept until `until`: past when kiff/agents deletes the chat itself. */
  add(tenant: string, tafID: string, session: string, until: Date): Promise<void>;
  /** Puts one back in the owner's list (kiff/tafs#97). */
  unarchive(tenant: string, tafID: string, session: string): Promise<void>;
  /** Forgets the taf's archive, on its removal. */
  remove(tenant: string, tafID: string): Promise<void>;
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
  /** Chats listed to reopen, and how much of each one's first message names it. */
  chatsListed: 20,
  chatTitle: 80,
  /** A chat active this recently takes the message instead of a new one. */
  liveMinutes: 30,
  /** How long an archive is kept: past the 30 days kiff/agents keeps a chat (docs/data.md). */
  archiveDays: 31,
  floodMessages: 10,
  floodMinutes: 10,
} as const;

/** Memory limits: the owner's first note, and how many notes the app is shown. */
export const MEMORY = { maxNotes: 2000, shown: 100 } as const;

class HTTPError extends Error {
  constructor(readonly status: number, message: string, readonly extra?: Record<string, unknown>) {
    super(message);
  }
}

export async function handle(event: HTTPEvent, deps: Deps): Promise<HTTPResult> {
  try {
    if (event.rawPath === "/v1/mcp") return await mcpRoute(event, deps);
    if (isDoorPath(event.rawPath)) return await doorWebhook(event, deps);
    return json(200, await route(event, deps));
  } catch (err) {
    if (err instanceof HTTPError) return json(err.status, { error: err.message, ...err.extra });
    if (err instanceof DoorError) return json(err.status, { error: err.message });
    if (err instanceof KIFFError || err instanceof PushError) return json(err.status, { error: err.message });
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
  if (method === "POST" && parts.length === 3 && parts[1] === "runs" && parts[2] === "ended") {
    return runEnded(event, deps);
  }
  if (parts[1] === "push") return push(event, method, parts, deps);
  if (parts[1] === "alerts" && parts.length === 2) return alerts(event, method, await person(event, deps), deps);
  if (parts[1] === "home" && parts.length === 2 && method === "GET") return home(event, await person(event, deps), deps);
  if (parts[1] === "halt" && parts.length === 2) return haltRoute(event, method, await person(event, deps), undefined, deps);
  if (parts[1] === "account" && parts.length === 3 && parts[2] === "delete" && method === "POST") return deleteAccount(await person(event, deps), deps);
  if (parts[1] === "usage" && parts.length === 3 && parts[2] === "ai" && method === "GET") return aiUsage(event, await person(event, deps), deps);
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
  if (action === "doors") return doorRoute(event, method, parts, me, taf, deps);
  if (parts.length === 4 && action === "halt") return haltRoute(event, method, me, taf, deps);
  if (parts.length === 4 && method === "POST" && (action === "pause" || action === "resume" || action === "run") && taf.unscheduled) {
    throw new HTTPError(409, `${taf.name} works from its chat: it has no schedule to ${action === "run" ? "run" : action}. Tell it what to do there`);
  }
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
    const { messages, components, sessions } = await conversation(me, taf, deps);
    const latest = await latestRun(me, taf, sessions, deps);
    return { messages, components, working: latest.working, steering: latest.steering, ...(await coachLines(me, taf, deps)), ...(await openKnocks(me, taf, deps)) };
  }
  if (parts.length === 5 && method === "GET" && action === "conversation") {
    const only = await ownChat(me, taf, parts[4] ?? "", deps);
    const { messages, components, sessions } = await conversation(me, taf, deps, only);
    const latest = await latestRun(me, taf, sessions, deps);
    return { messages, components, working: latest.working, steering: latest.steering, ...(await coachLines(me, taf, deps)) };
  }
  if (parts.length === 4 && method === "GET" && action === "chats") {
    return { chats: await chats(me, taf, deps) };
  }
  if (parts.length === 5 && method === "GET" && action === "chats" && parts[4] === "archived") {
    return { chats: await chats(me, taf, deps, true) };
  }
  if (parts.length === 6 && method === "POST" && action === "chats" && parts[5] === "archive") {
    need(me, "cards.manage");
    return archive(me, taf, parts[4] ?? "", deps);
  }
  if (parts.length === 6 && method === "POST" && action === "chats" && parts[5] === "unarchive") {
    need(me, "cards.manage");
    return unarchive(me, taf, parts[4] ?? "", deps);
  }
  if (parts.length === 4 && method === "POST" && action === "stop") {
    need(me, "cards.manage");
    return stop(event, me, taf, deps);
  }
  if (parts.length === 4 && method === "POST" && action === "steer") {
    need(me, "cards.manage");
    return steer(event, me, taf, deps);
  }
  if (parts.length === 6 && method === "POST" && action === "holds" && parts[5] === "continue") {
    if (!me.capabilities.includes("holds.answer")) throw new HTTPError(403, "your role on this KIFF account can't answer held calls");
    return goOn(me, taf, parts[4] ?? "", deps);
  }
  if (parts.length === 4 && method === "POST" && action === "messages") {
    need(me, "cards.manage");
    return message(event, me, taf, deps);
  }
  if (action === "versions" || action === "trials" || action === "switching") return versionRoute(event, method, parts, me, taf, deps);
  if (parts.length === 4 && method === "POST" && action === "improve") {
    need(me, "cards.manage");
    return improve(event, me, taf, deps);
  }
  if (parts.length === 4 && method === "GET" && action === "memory") {
    return { notes: await notes(me, taf, deps) };
  }
  if (parts.length === 4 && method === "GET" && action === "calls") {
    return { calls: await callRefs(me, taf, deps) };
  }
  if (parts.length === 5 && method === "DELETE" && action === "memory") {
    need(me, "cards.manage");
    return forget(me, taf, parts[4] ?? "", deps);
  }
  throw new HTTPError(404, "not found");
}

/**
 * KIFF's alert: handed to kiff/agents as KIFF signed it, then pushed to the
 * account's phones. Only an alert tafs cloud has checked is handed on, and
 * kiff/agents checks it again. The answer goes first, so the owner's wait
 * holds no push to Apple (kiff/tafs#138): when kiff/agents says the answer
 * left a run ready, tafs cloud continues it at once rather than leave it to
 * the sweep. If handing it on fails, the phones are still told, and the answer
 * is 502 so KIFF delivers it again: the phones are told once, however often
 * it comes.
 */
async function alert(event: HTTPEvent, deps: Deps): Promise<unknown> {
  const signature = header(event, "kiff-signature");
  const raw = rawBody(event);
  const now = deps.now?.() ?? new Date();
  let parsed: { tenant_id?: unknown; event_id?: unknown; outcome?: unknown };
  try { parsed = JSON.parse(raw) as typeof parsed; } catch { throw new HTTPError(400, "send KIFF's alert as JSON"); }
  const tenant = str(parsed.tenant_id);
  const { secrets } = tenant ? await deps.push.get(tenant) : { secrets: [] as string[] };
  // kiff/agents checks the alert with the account's secret: the one that
  // signed this alert, which an account registered before kiff/agents, or a
  // phone with an older target, hasn't handed it yet.
  const signer = signature ? secrets.find((secret) => signedByKIFF(signature, raw, [secret], now)) : undefined;
  if (!signer) throw new HTTPError(401, "not signed by KIFF");

  let handedOn = true;
  // A member's request for more tasks is the phones' alone: no run waits on it.
  if (str(parsed.outcome) === PACK_REQUESTED) {
    return receiveAlert(signature, raw, {
      push: deps.push,
      apns: deps.apns,
      claim: (key, until) => deps.slots.claim(key, until),
      held: (key) => deps.slots.held(key),
      keep: (key, until) => deps.slots.keep(key, until),
      release: (key) => deps.slots.release(key),
      now,
    });
  }
  try {
    await deps.accounts.put(tenant, tenant, signer);
    const ready = await deps.forward(signature, raw);
    if (ready) await continueReady(deps, tenant, ready);
  } catch (err) {
    console.error(`alert: kiff/agents: ${(err as Error).message}`);
    handedOn = false;
  }
  const out = await receiveAlert(signature, raw, {
    push: deps.push,
    apns: deps.apns,
    claim: (key, until) => deps.slots.claim(key, until),
    held: (key) => deps.slots.held(key),
    keep: (key, until) => deps.slots.keep(key, until),
    release: (key) => deps.slots.release(key),
    now,
  });
  if (handedOn) return out;
  // Asked again, a few times. An alert kiff/agents keeps not taking (a
  // refusal, a 5xx, no answer, or the secret not written) would otherwise be a
  // 502 until KIFF switches the target off, and the phones' pushes with it:
  // past FORWARD_TRIES, the alert is let go and its run goes on when the hold
  // expires, as without an answer.
  const eventID = str(parsed.event_id);
  if (await forwardTried(deps, eventID, now)) {
    // FORWARD_TRIES deliveries were answered 502; this is the one after them.
    console.error(`alert: kiff/agents didn't take event ${eventID} in ${FORWARD_TRIES + 1} tries; its run goes on when the hold expires`);
    return out;
  }
  throw new HTTPError(502, "the alert didn't reach the tafs' runtime; send it again");
}

/**
 * Continues the run an answer left ready, in the background. Best effort: a
 * 409 is a run the sweep or another delivery already took, and any failure
 * leaves it to the sweep, as before (kiff/tafs#138).
 */
async function continueReady(deps: Deps, tenant: string, ready: ReadyRun): Promise<void> {
  try {
    await deps.agents.continueRun(tenant, ready.agent, ready.id);
  } catch (err) {
    if (err instanceof AgentsError && err.status === 409) return;
    console.error(`alert: continuing run ${ready.id}: ${(err as Error).message}`);
  }
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

function bearer(event: HTTPEvent): string {
  const auth = header(event, "authorization");
  return auth.toLowerCase().startsWith("bearer ") ? auth.slice(7).trim() : "";
}

async function person(event: HTTPEvent, deps: Deps): Promise<KIFFIdentity> {
  const me = await whoIs(bearer(event), deps.fetch, deps.kiffBase);
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
    const addsTasks = me.capabilities.includes("billing.manage");
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
    await deps.push.addDevice(me.tenantID, { token: r.token, environment: r.environment, subject: me.subject, answers, addsTasks, at });
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

/**
 * KIFF's alert target for the account, kept without a phone's alerts
 * (kiff/tafs#206): the phone sets it up on a step the owner already confirms
 * with Face ID, so a paused run hears the owner's answer whether or not a
 * phone turned on alerts. As for a phone's, only someone who may answer held
 * calls hands over the signing secret, and kiff/agents gets it too.
 */
async function alerts(event: HTTPEvent, method: string, me: KIFFIdentity, deps: Deps): Promise<unknown> {
  if (method === "GET") {
    const { secrets } = await deps.push.get(me.tenantID);
    return { alerts: secrets.length > 0 };
  }
  if (method !== "POST") throw new HTTPError(404, "not found");
  if (!me.capabilities.includes("holds.answer")) throw new HTTPError(403, "your role on this KIFF account can't answer held calls");
  const target = alertTarget(parseBody(event).alert_target);
  const at = (deps.now?.() ?? new Date()).toISOString();
  await deps.push.addSecret(me.tenantID, target.id, target.secret, at);
  await deps.accounts.put(me.tenantID, me.tenantID, target.secret);
  return { alerts: true };
}

/**
 * A halt (kiff/kiff-cloud#1061) of one taf or, with none, of the whole account. POST halts it in
 * KIFF and in kiff/agents together: one tap, no signature, since it only takes authority away.
 * DELETE resumes kiff/agents only once KIFF says it runs again: the phone resumes it in KIFF
 * itself, signed with Face ID, so nothing here resumes what the owner didn't. GET reads both.
 */
async function haltRoute(event: HTTPEvent, method: string, me: KIFFIdentity, taf: Taf | undefined, deps: Deps): Promise<unknown> {
  const token = bearer(event);
  if (taf && !taf.kiffAgent) throw new HTTPError(409, `${taf.name} has no KIFF agent yet, so there is nothing to halt`);
  const agent = taf?.kiffAgent;
  const inKIFF = (work: () => Promise<KIFFHalt>) => Promise.allSettled([work()]).then(([r]) => r!);
  if (method === "GET") {
    const [kiff, runtime] = await Promise.all([inKIFF(() => haltedInKIFF(token, agent, deps.fetch, deps.kiffBase)),
      agentsCall(() => deps.agents.halted(me.tenantID, taf?.id))]);
    return { halt: haltView(kiff, runtime) };
  }
  need(me, "cards.manage");
  if (method === "POST") {
    // Both at once. KIFF failing, or not answering yet, leaves it halting: nothing reads it as halted.
    const [kiff, runtime] = await Promise.all([inKIFF(() => haltInKIFF(token, agent, deps.fetch, deps.kiffBase)),
      agentsCall(() => deps.agents.halt(me.tenantID, taf?.id))]);
    return { halt: haltView(kiff, runtime) };
  }
  if (method === "DELETE") {
    let kiff: KIFFHalt;
    try { kiff = await haltedInKIFF(token, agent, deps.fetch, deps.kiffBase); } catch (err) {
      if (err instanceof KIFFError && err.status === 401) throw err;
      throw new HTTPError(502, "KIFF didn't say whether it is resumed; try again");
    }
    if (kiff.state !== "running") throw new HTTPError(409, "resume it in KIFF first: the phone signs that with Face ID");
    const runtime = await agentsCall(() => deps.agents.resume(me.tenantID, taf?.id));
    return { halt: haltView({ status: "fulfilled", value: kiff }, runtime) };
  }
  throw new HTTPError(404, "not found");
}

/**
 * A halt as the app shows it. `halted` only once kiff/agents, KIFF's gateway and planque all hold
 * it; `halting` while any holds it and not all do; `running` when none does.
 */
function haltView(kiff: PromiseSettledResult<KIFFHalt>, runtime: AgentsHalt): Record<string, unknown> {
  const k = kiff.status === "fulfilled" ? kiff.value : undefined;
  const all = runtime.halted && k?.state === "halted" && k.kiff_ack && k.planque_ack;
  const any = runtime.halted || (k !== undefined && k.state !== "running");
  return {
    state: all ? "halted" : any ? "halting" : "running",
    halted_at: k?.halted_at ?? runtime.halted_at ?? null,
    // Who holds it: kiff/agents (no run starts), KIFF's gateway, planque's dispatch.
    runtime: runtime.halted, kiff: k?.kiff_ack ?? false, planque: k?.planque_ack ?? false,
    // Halted because the whole account is, not on its own.
    account: runtime.tenant,
    stopped: runtime.stopped ?? 0,
  };
}

function need(me: KIFFIdentity, capability: string): void {
  if (!me.capabilities.includes(capability)) throw new HTTPError(403, "your role on this KIFF account cannot manage Cards");
}

async function start(event: HTTPEvent, me: KIFFIdentity, deps: Deps): Promise<unknown> {
  const body = parseBody(event);
  let name = str(body.name).trim();
  if (!name || name.length > MAX_NAME) throw new HTTPError(400, `give the taf a name of ${MAX_NAME} characters at most`);
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
  // From the setup chat (kiff/agents#91), Setup submitted the draft, so kiff/agents
  // keeps its claim secret and claims the key itself, into the taf's key reference.
  const fromSetup = body.from_setup === true;
  // Setup's word that the taf's tools can't find its work (kiff/tafs#138): no
  // running schedule and no first run; the owner hands it work in its chat.
  const unscheduled = body.unscheduled === true;
  if (fromSetup && (!draftID || claimSecret || body.agent_key !== undefined)) {
    throw new HTTPError(400, "from_setup takes a draft_id and no claim_secret or agent_key");
  }
  if (!fromSetup && !draftID !== !claimSecret) throw new HTTPError(400, "send both draft_id and claim_secret");
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
  // The name Setup chose, when another taf has it or holds its claim
  // (kiff/tafs#138): the first free "<name> 2", "<name> 3"…, so the owner
  // isn't left at a name no retry can get past. A start already under way
  // keeps its name, and kiff/agents claims a draft into one taf only, so the
  // same draft can't start two.
  const names = fromSetup ? await setupNames(me, name, draftID, deps) : [name];

  // Two taps, or a retry, must not start two tafs: the name is claimed first.
  name = names[0] ?? name;
  const taken = new HTTPError(409, `you already have a taf named ${name}`);
  if ((await deps.tafs.list(me.tenantID)).some((t) => t.name === name && t.job !== SETUP_JOB)) throw taken;
  // This start's claim on the name; the taf carries it, so only its removal frees it.
  const nameClaim = randomUUID();
  let claimed = false;
  for (const candidate of names) {
    name = candidate;
    if ((claimed = await deps.names.claim(me.tenantID, name, nameClaim))) break;
  }
  if (!claimed) {
    // Held by an attempt that is still running, or whose outcome was never
    // learned. Nothing proves such an attempt can't still make the taf, so
    // the name stays taken; another name always works.
    throw new HTTPError(409, `${name} is taken by an earlier start whose outcome isn't known; it may still appear. Choose another name`);
  }

  let tafID = (deps.newID ?? newTafID)();
  try {
    if (fromSetup) ({ agentID, tafID } = await setupDraftKey(me, name, draftID, tafID, deps));
    else if (draftID) ({ agentID, tafID } = await draftKey(me, name, draftID, claimSecret, tafID, deps, check));
    else await deps.keys.put(me.tenantID, tafID, agentKey);
  } catch (err) {
    await deps.names.release(me.tenantID, name, nameClaim);
    if (err instanceof HTTPError) throw err;
    throw new HTTPError(502, `the taf's key couldn't be kept: ${(err as Error).message}`);
  }

  // From the definition on, what this start made is undone if it fails before
  // the taf is on record, and only then is the name freed: a definition or a
  // schedule nobody can see would otherwise run, or block the name for good.
  const def = definition(tafID, job, { budgetCents: budget, ownerNotes: notesText });
  // Its colour: the first no other taf of the account has (kiff/tafs#91).
  const color = freeColor((await deps.tafs.list(me.tenantID)).filter((t) => !t.removing && t.job !== SETUP_JOB && t.color !== undefined).map((t) => t.color!));
  const taf: Taf = {
    id: tafID, name, job: job.id, kiffAgent: agentID, nameClaim, created: (deps.now?.() ?? new Date()).toISOString(),
    budgetCents: def.max_cost_micro_usd / 10_000, ...(notesText ? { ownerNotes: notesText } : {}), definition: digest(def), color,
    ...(unscheduled ? { unscheduled: true } : {}),
  };
  try {
    await deps.agents.putAgent(me.tenantID, tafID, def);
    // Kept off for a taf that works from its chat, so the rest of tafs reads it as any other.
    const schedule: Schedule = { id: tafID, agent: tafID, cron, time_zone: timezone, input: job.kickoff, enabled: !unscheduled };
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

  // It runs once now as well, unless it works from its chat: then it waits for the owner.
  if (unscheduled) return { taf: await view(taf, me, deps), session: null };
  let run: Run;
  try { run = await deps.agents.trigger(me.tenantID, tafID); } catch (err) {
    throw new HTTPError(502, `${name} is started, but its first run didn't start (${(err as Error).message}). Run it now from the app`);
  }
  return { taf: await view(taf, me, deps), session: run.session };
}

/** The longest name a taf may have. */
const MAX_NAME = 60;

/** How many names a Setup start tries before it says the name is taken. */
const SETUP_NAMES = 10;

/**
 * The names a Setup start tries, in order: the one this draft's start began
 * with alone, or name and "<name> 2", "<name> 3"… that no other taf has. The
 * start claims the first it can; a name claimed with no taf behind it (an
 * earlier start whose outcome isn't known) moves it on to the next.
 */
async function setupNames(me: KIFFIdentity, name: string, draftID: string, deps: Deps): Promise<string[]> {
  const held = await deps.claims.get(me.tenantID, draftID);
  if (held) return [held.name];
  const used = new Set((await deps.tafs.list(me.tenantID)).filter((t) => t.job !== SETUP_JOB).map((t) => t.name));
  const out: string[] = [];
  for (let n = 1; n < 100 && out.length < SETUP_NAMES; n++) {
    const suffix = n === 1 ? "" : ` ${n}`;
    const candidate = name.slice(0, MAX_NAME - suffix.length).trimEnd() + suffix;
    if (!used.has(candidate)) out.push(candidate);
  }
  return out.length ? out : [name]; // refused below as taken
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
  const setupDef = setupDefinition(tafID);
  const setup: Taf = {
    id: tafID, name: "Setup", job: SETUP_JOB, kiffAgent: agentID, nameClaim, created: (deps.now?.() ?? new Date()).toISOString(),
    definition: digest(setupDef),
  };
  try {
    await deps.agents.putAgent(me.tenantID, tafID, setupDef);
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

/**
 * A taf's key from a draft Setup submitted (kiff/agents#94): kiff/agents claims it into
 * the taf's key reference, so it never reaches tafs cloud, the phone or the model. A
 * retry resumes with the same taf id, which kiff/agents answers the same.
 */
async function setupDraftKey(me: KIFFIdentity, name: string, draftID: string, tafID: string,
  deps: Deps): Promise<{ agentID: string; tafID: string }> {
  const held = await deps.claims.get(me.tenantID, draftID);
  if (held) {
    if (held.name !== name) throw new HTTPError(409, `this draft is starting the taf ${held.name}; start it with that name`);
    tafID = held.tafID;
  } else {
    // On record before the claim: kiff/agents claims a draft into one key
    // reference only, so a retry must reuse this taf id whatever happens next.
    await deps.claims.put(me.tenantID, draftID, { secret: "", name, agentID: "", tafID })
      .catch((err) => { throw new HTTPError(502, (err as Error).message); });
  }
  let agentID: string;
  try {
    agentID = (await deps.agents.claimTafDraft(me.tenantID, draftID, keyRef(tafID))).agent_id;
  } catch (err) {
    if (err instanceof AgentsError && err.status === 404) {
      // Not Setup's: nothing was claimed, so the record goes and can't stand in the draft's own claim.
      if (!held) await deps.claims.remove(me.tenantID, draftID).catch(() => undefined);
      throw new HTTPError(404, "Setup submitted no draft by that id on this account");
    }
    if (err instanceof AgentsError && err.status === 409) throw new HTTPError(409, err.message);
    throw new HTTPError(502, `the taf's key couldn't be claimed: ${(err as Error).message}`);
  }
  if (!agentID) throw new HTTPError(502, "kiff/agents claimed the key but named no agent");
  // The taf id is already on record, so losing this write only loses the agent id there.
  await deps.claims.put(me.tenantID, draftID, { secret: "", name, agentID, tafID }).catch(() => undefined);
  return { agentID, tafID };
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
  // Its doors' addresses and numbers stop being its, and their sign-ins go.
  if (failed.length === 0 && deps.doors) await step(["doors"], () => removeTafDoors(doorContext(deps), me.tenantID, taf.id));
  // Only this taf's own claim: if a newer start holds the name, it keeps it.
  if (failed.length === 0) await step(["name"], () => deps.names.release(me.tenantID, taf.name, taf.nameClaim));
  if (failed.length === 0) {
    // Only the list's archive is left, and its ttl takes it if this fails.
    await deps.archived.remove(me.tenantID, taf.id).catch((err) => console.error(`remove ${taf.id}: archive: ${(err as Error).message}`));
    await deps.tafs.remove(me.tenantID, taf.id);
    await deps.removals.done(me.tenantID, taf.id);
  }
  return { taf: taf.id, name: taf.name, kiff_agent: taf.kiffAgent || null, done: failed.length === 0, removed, failed };
}

/**
 * The owner deletes the account in the app (kiff/tafs#185, App Store Review
 * Guideline 5.1.1(v)). tafs cloud erases what it and kiff/agents keep for it:
 * every taf (Setup included) as removing it does, then kiff/agents' account
 * erase, then its own records for the account. The app then erases the
 * account in planque and in KIFF itself, each signed by the phone, KIFF last.
 *
 * A step that fails stops the rest, and the answer says which; asking again
 * goes on from there, since every step is safe to repeat.
 */
async function deleteAccount(me: KIFFIdentity, deps: Deps) {
  if (!me.capabilities.includes("account.delete")) throw new HTTPError(403, "your role on this KIFF account cannot delete it");
  if (!deps.accountData) throw new HTTPError(503, "accounts can't be deleted here");
  const tafs = await deps.tafs.list(me.tenantID);
  for (const taf of tafs) {
    const r = await remove(taf, me, deps);
    if (!r.done) return { done: false, failed: "tafs", taf: taf.id, steps: r.failed };
  }
  for (const r of await deps.removals.list(me.tenantID)) await deps.removals.done(me.tenantID, r.id);
  try {
    await deps.agents.eraseTenant(me.tenantID);
  } catch (err) {
    console.error(`delete account: kiff/agents: ${(err as Error).message}`);
    return { done: false, failed: "agents" };
  }
  try {
    await deps.accountData.erase(me.tenantID);
  } catch (err) {
    console.error(`delete account: tafs cloud: ${(err as Error).message}`);
    return { done: false, failed: "tafs cloud" };
  }
  return { done: true, removed: tafs.length };
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
  /**
   * A door's message and its answer: which door, who wrote (and the email's
   * subject); on the answer, whether it went back ("sent"), couldn't
   * ("not_sent", with why), or hasn't yet ("pending").
   */
  door?: { app: string; from?: string; subject?: string; reply?: "sent" | "not_sent" | "pending"; why?: string };
}

/**
 * What the taf's calls through KIFF acted on (kiff/tafs#167): for each, KIFF's key
 * (its activity's entity_id) and the value of the argument its Card scopes on, as
 * the run kept them (`call_ref`, 30 days). The app names a Record line's payment
 * from it, and checks it against KIFF's signed hash. Nothing else of a call is kept.
 */
async function callRefs(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<Array<{ invocation: string; value: string; at: string }>> {
  const sessions = await agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned));
  const read = await Promise.all(sessions.map((s) => deps.agents.session(me.tenantID, s.id).catch(() => undefined)));
  const out: Array<{ invocation: string; value: string; at: string }> = [];
  for (const r of read) {
    for (const e of r?.events ?? []) {
      if (e.kind !== "call_ref") continue;
      const b = e.body as { invocation?: unknown; value?: unknown };
      if (typeof b?.invocation === "string" && typeof b.value === "string") out.push({ invocation: b.invocation, value: b.value, at: e.time });
    }
  }
  return out;
}

/**
 * The taf's thread, oldest first: its latest job runs and its latest chats,
 * from kiff/agents' sessions (newest first, a bounded number), with what each
 * kept: the owner's messages and the taf's. A run's first owner message is
 * the job's instruction, shown as such.
 */
async function conversation(me: KIFFIdentity, taf: Taf, deps: Deps, only?: Session): Promise<{ messages: ThreadMessage[]; components: ThreadComponent[]; sessions: Session[] }> {
  const all = only ? [only] : await agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned));
  // A chat the owner archived is hidden from the thread too (kiff/tafs#82); one they open is shown.
  const archived = only ? new Set<string>() : new Set(await deps.archived.list(me.tenantID, taf.id));
  const picked = only ? [only] : [
    ...all.filter((s) => s.kind === "run").slice(0, CHAT.runsInThread),
    ...all.filter((s) => s.kind === "chat" && !archived.has(s.id)).slice(0, CHAT.chatsInThread),
  ];
  const read = await Promise.all(picked.map((s) => deps.agents.session(me.tenantID, s.id).catch(() => undefined)));
  const messages: ThreadMessage[] = [];
  const components: ThreadComponent[] = [];
  /** Runs a door's message started, by run: their answers go back through it. */
  const doorRuns = new Map<string, string>();
  const sent = new Map((deps.doors ? await sentOf(deps.doors.store, me.tenantID, taf.id).catch(() => []) : []).map((x) => [x.run, x]));
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
        // A message that came through a door is shown as written, marked with its door and sender.
        const door = parseDoorMessage(text);
        if (door) doorRuns.set(e.run, door.app);
        messages.push({
          at: iso(e.time), from: "owner", kind, text: door ? door.text.trim() : ownerWords(text), session: r.session.id,
          ...(door ? { door: { app: door.app, from: door.from, ...(door.subject ? { subject: door.subject } : {}) } } : {}),
        });
      } else {
        const app = doorRuns.get(e.run);
        const reply = app ? sent.get(e.run) : undefined;
        messages.push({
          at: iso(e.time), from: "taf", kind: "message", text, session: r.session.id,
          ...(app ? { door: { app, reply: reply?.status ?? "pending", ...(reply?.why ? { why: reply.why } : {}) } } : {}),
        });
      }
    }
  }
  messages.sort((a, b) => a.at.localeCompare(b.at));
  components.sort((a, b) => a.at.localeCompare(b.at) || a.seq - b.seq);
  return { messages: messages.slice(-CHAT.maxThread), components: components.slice(-CHAT.maxThread), sessions: picked };
}

/**
 * The taf's latest session's latest run. `working`: whether the taf is at work
 * in it now, so the app shows it thinking and offers Stop (kiff/tafs#58): the
 * run is pending or running. A run waiting on the owner isn't: it waits for
 * them. `steering`: what the owner wrote to it that it hasn't read (#64).
 * Unknown (kiff/agents not answering) reads as neither.
 */
async function latestRun(me: KIFFIdentity, taf: Taf, picked: Session[], deps: Deps): Promise<{ working: { session: string; run: string } | null; steering: Steering | null }> {
  const none = { working: null, steering: null };
  const latest = [...picked].sort((a, b) => b.updated.localeCompare(a.updated))[0];
  if (!latest) return none;
  try {
    const read = await deps.agents.session(me.tenantID, latest.id);
    const run = read.events.at(-1)?.run;
    if (!run) return none;
    const r = await deps.agents.getRun(me.tenantID, taf.id, run);
    // Times as the thread's are (milliseconds): kiff/agents writes nanoseconds, which the app can't read.
    const untaken = (r.steering ?? []).filter((m) => !m.taken).map(({ text, at }) => ({ text, at: iso(at) }));
    return {
      working: r.status === "PENDING" || r.status === "RUNNING" ? { session: latest.id, run } : null,
      steering: untaken.length ? { run, status: r.status, untaken } : null,
    };
  } catch {
    return none;
  }
}

/**
 * What the owner wrote to the latest run that it hasn't read (kiff/agents#102), as
 * kiff/agents says: while the run goes, it reads them at its next step (a paused one,
 * when it carries on); once it's over, they were never read, and the app offers to
 * send them again.
 */
interface Steering {
  run: string;
  status: string;
  untaken: Array<{ text: string; at: string }>;
}

/**
 * The owner writes to the taf. The message is a run in the taf's latest chat
 * when that chat was active in the last 30 minutes, so the taf answers with
 * the conversation in mind; otherwise it starts a new chat. Either way the
 * taf has only its Card's tools, and remembers what its notes say.
 */
async function message(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  const owner = chatText(event);
  const lines = await takeCoachLines(me, taf, deps);
  try {
    return await startMessage(event, me, taf, lines.text(owner), deps);
  } catch (err) {
    // The message didn't start: Coach reads its lines with the next one.
    await lines.putBack();
    throw err;
  }
}

async function startMessage(event: HTTPEvent, me: KIFFIdentity, taf: Taf, text: string, deps: Deps): Promise<unknown> {
  await claimChatSlot(me, taf, deps);
  const [live, ai] = await Promise.all([chatFor(event, me, taf, deps), aiBound(event, deps)]);
  if (deps.doors) await keepBound(deps.doors, me.tenantID, ai, deps.now?.() ?? new Date());
  if (live) {
    try {
      const run = await deps.agents.startRun(me.tenantID, taf.id, text, live, ai);
      return { session: run.session, new_session: false };
    } catch (err) {
      // Past AI fair use, a new chat is refused too: the owner says go on first.
      if (refusedForAI(err)) throw new HTTPError(409, (err as Error).message);
      // Refused, for example a chat that is gone or busy: a new one takes the message.
      if (!(err instanceof AgentsError && err.status >= 400 && err.status < 500)) throw err;
    }
  }
  const run = await agentsCall(() => deps.agents.startRun(me.tenantID, taf.id, text, undefined, ai));
  return { session: run.session, new_session: true };
}

/**
 * A taf's versions (kiff/tafs#107), as kiff/agents keeps them: listed, tried
 * and made live. Making one that brings a Card revision live is the owner's
 * act in three steps, so nothing ever runs with one version's definition
 * under another's Cards: tafs cloud begins the switch, from which point
 * nothing of the taf runs (kiff/agents#137); the owner confirms the
 * revision in KIFF with the phone's signature, as for any draft; then the
 * switch ends with that version live. If the phone stops after its Face ID,
 * the next read of the versions ends it, and a switch whose draft was
 * discarded is dropped, the live version staying.
 */
async function versionRoute(event: HTTPEvent, method: string, parts: string[], me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  if (taf.job === SETUP_JOB) throw new HTTPError(404, "not found");
  const [, , , action, rawN, sub] = parts;
  if (action === "versions" && parts.length === 4 && method === "GET") {
    const state = await settleSwitch(event, me, taf, deps);
    return { versions: state.versions, ...(state.switching ? { switching: state.switching } : {}) };
  }
  if (action === "versions" && parts.length === 4 && method === "POST") {
    need(me, "cards.manage");
    return createVersion(event, me, taf, deps);
  }
  if (action === "switching" && parts.length === 4 && method === "DELETE") {
    need(me, "cards.manage");
    return cancelSwitch(event, me, taf, deps);
  }
  if (action === "trials" && parts.length === 5 && method === "GET") {
    return agentsCall(() => deps.agents.trial(me.tenantID, taf.id, rawN ?? ""));
  }
  const n = Number(rawN);
  if (action !== "versions" || parts.length !== 6 || method !== "POST" || !Number.isInteger(n) || n < 1) throw new HTTPError(404, "not found");
  need(me, "cards.manage");
  const version = (await agentsCall(() => deps.agents.versions(me.tenantID, taf.id))).versions.find((v) => v.n === n);
  if (!version) throw new HTTPError(404, "no such version");
  if (sub === "trial") {
    // What a trial works on: the owner's words, else what each scheduled run asks of it.
    const asked = str(parseBody(event).input).trim();
    const input = asked || JOBS.find((j) => j.id === taf.job)?.kickoff || "";
    if (!input || input.length > CHAT.maxText) throw new HTTPError(400, `write 1 to ${CHAT.maxText} characters for the trial`);
    return { run: await agentsCall(() => deps.agents.startTrial(me.tenantID, taf.id, n, input)) };
  }
  if (sub === "switch") {
    if (version.live) throw new HTTPError(409, "that version is live already");
    const again = parseBody(event).redraft === true;
    const state = await agentsCall(() => deps.agents.versions(me.tenantID, taf.id));
    const sw = state.switching;
    if (sw && sw.n === n && !again) return { switching: sw };
    // The version it leaves keeps the Cards it ran under, for going back to it.
    await recordLiveCards(event, me, taf, state, deps);
    const draft = await draftFor(event, me, taf, version, again, deps);
    // Drafted again for a switch under way: it waits for the new draft instead,
    // its mark kept, so no run starts in between.
    if (sw && sw.n === n && draft) return { switching: await agentsCall(() => deps.agents.replaceSwitchDraft(me.tenantID, taf.id, n, draft)) };
    if (sw && sw.n === n) await agentsCall(() => deps.agents.cancelSwitch(me.tenantID, taf.id));
    return { switching: await beginSwitch(me, taf, n, draft, parseBody(event).stop_blocking === true, deps) };
  }
  if (sub === "live") {
    // The draft the switch waits for: prepared by switch, or the version's own.
    const sw = (await agentsCall(() => deps.agents.versions(me.tenantID, taf.id))).switching;
    const pending = sw && sw.n === n;
    const draft = pending ? sw.draft : version.draft;
    if (draft && !(await draftConfirmed(bearer(event), draft, deps.fetch, deps.kiffBase))) {
      throw new HTTPError(409, "confirm this version's Card first");
    }
    // Going back without a switch: a version that ran under other Card revisions
    // goes live only once a switch has restored them, never under the Cards in
    // force, whether or not it once went live through a draft of its own.
    if (!pending && version.cards?.length && !sameCards(await liveCards(event, taf, deps), version.cards)) {
      throw new HTTPError(409, "begin the switch: this version's Cards need restoring first");
    }
    await goLive(event, me, taf, n, deps);
    return { live: n };
  }
  throw new HTTPError(404, "not found");
}

/** Most characters a version's note and its instructions from the owner may have. */
const VERSION_NOTE = 2000;
const VERSION_NOTES = 4000;

/**
 * The taf's next version, as Setup drafted it in an improvement (kiff/tafs#107):
 * tafs cloud builds its definition from its job and budget, with the new
 * instructions from the owner or its own, and stores it in kiff/agents with
 * the KIFF draft revising its Cards. Nothing goes live: the owner tries it and
 * switches to it from the taf's Versions. The draft must be this taf's agent's,
 * still waiting for the owner, so a version never names another's Cards.
 */
async function createVersion(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  if (taf.job === SETUP_JOB) throw new HTTPError(404, "not found");
  const body = parseBody(event);
  const note = str(body.note).trim();
  if (!note || note.length > VERSION_NOTE) throw new HTTPError(400, `say what changed in 1 to ${VERSION_NOTE} characters`);
  const notes = body.notes === undefined || body.notes === null ? undefined : str(body.notes).trim();
  if (notes !== undefined && notes.length > VERSION_NOTES) throw new HTTPError(400, `the instructions are at most ${VERSION_NOTES} characters`);
  const draft = str(body.draft).trim() || undefined;
  // The result's agent, when the app sends it: an improvement of another taf
  // never becomes this one's version, with a draft or without.
  const agent = str(body.agent).trim();
  if (agent && agent !== taf.kiffAgent) throw new HTTPError(409, "that improvement is for another taf");
  // The result it comes from: the version it already made, if any.
  const from = str(body.from).trim() || undefined;
  if (from && from.length > 128) throw new HTTPError(400, "from is at most 128 characters");
  const made = from ? taf.versionsFrom?.[from] : undefined;
  if (made !== undefined) return { version: made };
  if (draft) {
    const d = await draftInfo(bearer(event), draft, deps.fetch, deps.kiffBase, (deps.now ?? (() => new Date()))());
    if (!d || d.agent !== taf.kiffAgent) throw new HTTPError(409, "that draft isn't this taf's");
    if (d.status !== "draft") throw new HTTPError(409, `that draft is ${d.status}: draft it again`);
  }
  let def: AgentDefinition;
  try {
    def = definition(taf.id, jobById(taf.job), { budgetCents: taf.budgetCents, ownerNotes: notes === undefined ? taf.ownerNotes : notes || undefined });
  } catch (err) {
    throw new HTTPError(409, `its definition can't be built: ${(err as Error).message}`);
  }
  // The version live now records its Cards first, so going back to it brings them back.
  await recordLiveCards(event, me, taf, await agentsCall(() => deps.agents.versions(me.tenantID, taf.id)), deps);
  const n = await agentsCall(() => deps.agents.createVersion(me.tenantID, taf.id, { definition: def, note, by: "setup", ...(draft ? { draft } : {}) }));
  if (from) {
    // Kept so the same result hands over this version again. A record that
    // changed meanwhile is read again once.
    for (let tries = 0; tries < 2; tries++) {
      const cur = tries === 0 ? taf : await deps.tafs.get(me.tenantID, taf.id);
      if (!cur) break;
      if (await deps.tafs.putIf(me.tenantID, { ...cur, versionsFrom: { ...cur.versionsFrom, [from]: n } }, cur)) break;
    }
  }
  return { version: n };
}

/**
 * The KIFF Cards the taf holds now, each at its revision: what a version runs
 * under. Nothing when KIFF can't say, so a switch never waits on it.
 */
async function liveCards(event: HTTPEvent, taf: Taf, deps: Deps): Promise<CardRevision[]> {
  try {
    const token = bearer(event);
    const { mandates } = await readKIFF(token, deps.fetch, deps.kiffBase);
    return await cardRevisions(token, heldCards(mandates, taf.kiffAgent).map((c) => c.id), deps.fetch, deps.kiffBase);
  } catch (err) {
    console.error(`live cards: ${taf.id}:`, err);
    return [];
  }
}

/**
 * Records the Cards the live version runs under when it has none recorded,
 * as when KIFF couldn't say at the switch to it: read on each look at the
 * versions, and before switching away, so going back to it restores them
 * instead of leaving it under the Cards in force then. True when recorded.
 * A version's cards are the terms it runs under, read when recorded: a Card
 * changed in KIFF directly while it is live is recorded as it is then, not
 * as the version was switched to.
 */
async function recordLiveCards(event: HTTPEvent, me: KIFFIdentity, taf: Taf, state: VersionState, deps: Deps): Promise<boolean> {
  const live = state.versions.find((v) => v.live);
  if (!live || live.cards?.length || state.switching) return false;
  const cards = await liveCards(event, taf, deps);
  if (!cards.length) return false;
  await agentsCall(() => deps.agents.switchVersion(me.tenantID, taf.id, live.n, cards));
  return true;
}

/** Makes version n live, recording the Cards it runs under, and keeps the record in step. */
async function goLive(event: HTTPEvent, me: KIFFIdentity, taf: Taf, n: number, deps: Deps): Promise<void> {
  const cards = await liveCards(event, taf, deps);
  await agentsCall(() => deps.agents.switchVersion(me.tenantID, taf.id, n, cards));
  await keepNotes(me, taf, n, deps);
}

function sameCards(a: CardRevision[], b: CardRevision[]): boolean {
  const key = (cs: CardRevision[]) => cs.map((c) => `${c.id}@${c.revision}`).sort().join(",");
  return key(a) === key(b);
}

/**
 * The KIFF draft a switch to `version` waits for (kiff/tafs#107):
 * - its own draft, while it waits for the owner (once confirmed, that draft
 *   only says how it first went live, so going back goes by its Cards);
 * - its Cards drafted again, when that draft expired or failed, or the owner
 *   asks (`redraft`: KIFF refused it at confirm because a Card moved on);
 * - for a version with no draft of its own that ran under other Card revisions
 *   (going back), a draft that restores those revisions;
 * - none when the Cards in force are the ones it runs under.
 */
async function draftFor(event: HTTPEvent, me: KIFFIdentity, taf: Taf, version: AgentVersion, again: boolean, deps: Deps): Promise<string | undefined> {
  const token = bearer(event);
  const now = (deps.now ?? (() => new Date()))();
  const kiffCall = async <T>(work: () => Promise<T>): Promise<T> => {
    try { return await work(); } catch (err) {
      if (err instanceof KIFFError && (err.status === 404 || err.status === 409)) throw new HTTPError(409, `KIFF can't prepare this version's Card: ${err.message}`);
      throw err;
    }
  };
  if (version.draft) {
    const d = await draftInfo(token, version.draft, deps.fetch, deps.kiffBase, now);
    if (!d || d.status === "discarded") throw new HTTPError(409, "this version's Card draft is gone: improve the taf again");
    if (!issued(d.status)) {
      if (d.status === "draft" && !again) return version.draft;
      return kiffCall(() => redraft(token, version.draft!, deps.fetch, deps.kiffBase));
    }
    // Issued: it says how the version first went live. Going back to it is
    // then as for any version: by the Cards it recorded.
  }
  if (!version.cards?.length) return undefined;
  const now2 = await liveCards(event, taf, deps);
  if (sameCards(now2, version.cards)) return undefined;
  return kiffCall(() => restoreDraft(token, taf.kiffAgent, version.cards!, `${taf.name}, version ${version.n}`, deps.fetch, deps.kiffBase));
}

/**
 * Keeps tafs cloud's record of the taf in step with the version now live:
 * its instructions from the owner, read from that version's definition at
 * kiff/agents, and the digest of what tafs would build from them. An upgrade
 * rebuilds a taf from this record, so it never reverts a version's
 * instructions. A record that changed meanwhile is left for the next switch.
 */
async function keepNotes(me: KIFFIdentity, taf: Taf, n: number, deps: Deps): Promise<void> {
  try {
    const { definition: live } = await deps.agents.getVersion(me.tenantID, taf.id, n);
    const notes = typeof live.instructions === "string" ? ownerNotesIn(live.instructions) : undefined;
    const next: Taf = { ...taf, definition: digest(definition(taf.id, jobById(taf.job), { budgetCents: taf.budgetCents, ownerNotes: notes })) };
    if (notes) next.ownerNotes = notes; else delete next.ownerNotes;
    if (next.ownerNotes !== taf.ownerNotes || next.definition !== taf.definition) await deps.tafs.putIf(me.tenantID, next, taf);
  } catch (err) {
    // The switch stands; the next switch, or the upgrade's read-back, catches the record up.
    console.error(`keep notes: ${taf.id} v${n}:`, err);
  }
}

/**
 * The taf's versions with a switch under way settled as far as KIFF allows:
 * ended once its draft is confirmed, dropped once its draft never will be,
 * (discarded, failed, or past its expiry unconfirmed), and left waiting
 * otherwise. KIFF unreachable leaves it waiting: nothing
 * runs meanwhile, which is the safe side.
 */
async function settleSwitch(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<VersionState> {
  const state = await agentsCall(() => deps.agents.versions(me.tenantID, taf.id));
  const sw = state.switching;
  if (!sw) return (await recordLiveCards(event, me, taf, state, deps)) ? agentsCall(() => deps.agents.versions(me.tenantID, taf.id)) : state;
  if (!sw.draft) return state;
  const status = await draftStatus(bearer(event), sw.draft, deps.fetch, deps.kiffBase, (deps.now ?? (() => new Date()))()).catch(() => "unknown");
  if (issued(status)) {
    await goLive(event, me, taf, sw.n, deps);
  } else if (dropped(status)) {
    await agentsCall(() => deps.agents.cancelSwitch(me.tenantID, taf.id));
  } else {
    return state;
  }
  return agentsCall(() => deps.agents.versions(me.tenantID, taf.id));
}

/**
 * The owner drops a switch before confirming its Card: the live version
 * stays and the taf runs again. Once the Card is confirmed the switch can
 * only end, so it does: the version it was going to goes live.
 */
async function cancelSwitch(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  const { switching: sw } = await agentsCall(() => deps.agents.versions(me.tenantID, taf.id));
  if (!sw) return { cancelled: false };
  if (sw.draft && issued(await draftStatus(bearer(event), sw.draft, deps.fetch, deps.kiffBase))) {
    await goLive(event, me, taf, sw.n, deps);
    return { cancelled: false, live: sw.n };
  }
  return { cancelled: await agentsCall(() => deps.agents.cancelSwitch(me.tenantID, taf.id)) };
}

/**
 * The owner improves a taf (kiff/tafs#107): a new conversation with the
 * account's Setup, whose first message is the owner's words with this taf's
 * context in front: its job, its Card and its latest calls, read from KIFF with
 * the owner's own session. Setup starts from how the taf is doing, not from
 * scratch, and the app assembles nothing.
 */
async function improve(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  if (taf.job === SETUP_JOB) throw new HTTPError(400, "Setup improves tafs, not itself");
  const setup = await setupOf(me, deps);
  if (!setup) throw new HTTPError(409, "this account has no Setup yet");
  const text = chatText(event);
  const token = bearer(event);
  const [kiff, calls] = await Promise.all([
    readKIFF(token, deps.fetch, deps.kiffBase),
    recentCalls(token, taf.kiffAgent, deps.fetch, deps.kiffBase),
  ]);
  const card = summary(byHolder(kiff.mandates, kiff.held).cards.get(taf.kiffAgent) ?? []);
  const input = improveContext({ id: taf.id, name: taf.name, job: taf.job, kiffAgent: taf.kiffAgent, ownerNotes: taf.ownerNotes }, card, calls, text,
    heldCards(kiff.mandates, taf.kiffAgent));
  await claimChatSlot(me, setup, deps);
  const ai = await aiBound(event, deps);
  const run = await agentsCall(() => deps.agents.startRun(me.tenantID, setup.id, input, undefined, ai));
  return { setup: setup.id, session: run.session, new_session: true };
}

/**
 * The owner writes to a run that is at work (kiff/tafs#64): kiff/agents keeps the
 * message on the run, and the run reads it at its next step, before its next turn
 * (kiff/agents#102). The flood guard counts it as a message. A run that has ended,
 * or was asked to stop, is 409: the app sends the message as a new one. The run must
 * be this taf's: kiff/agents answers another agent's as not found.
 */
async function steer(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  const run = str(parseBody(event).run).trim();
  if (!run) throw new HTTPError(400, "name the run to write to");
  const text = chatText(event);
  await claimChatSlot(me, taf, deps);
  try {
    const out = await deps.agents.steerRun(me.tenantID, taf.id, run, text);
    return { run: out.id, status: out.status };
  } catch (err) {
    if (err instanceof AgentsError && err.status === 409) throw new HTTPError(409, "the run has ended: send it as a new message");
    throw agentsFailure(err);
  }
}

/**
 * The app has just answered a held call of the taf's in KIFF: the run that
 * paused on it goes on now, without waiting for KIFF's alert, which an
 * account with no alert target never gets (kiff/tafs#206). kiff/agents asks
 * KIFF for the answer itself, so this vouches for nothing: it only asks
 * sooner. `waiting` when KIFF still holds a call of the run, or the run
 * already went on.
 */
async function goOn(me: KIFFIdentity, taf: Taf, exception: string, deps: Deps): Promise<unknown> {
  if (!exception) throw new HTTPError(404, "not found");
  try {
    const out = await deps.agents.continueHeld(me.tenantID, taf.id, exception);
    return { run: out.id, status: out.status };
  } catch (err) {
    if (err instanceof AgentsError && err.status === 409) return { status: "waiting" };
    if (err instanceof AgentsError && err.status === 404) throw new HTTPError(404, "no run of this taf waits on that held call");
    throw agentsFailure(err);
  }
}

/** The owner's message, 1 to CHAT.maxText characters. */
function chatText(event: HTTPEvent): string {
  const text = str(parseBody(event).text).trim();
  if (!text || text.length > CHAT.maxText) throw new HTTPError(400, `write 1 to ${CHAT.maxText} characters`);
  return text;
}

/**
 * The flood guard: one of 10 slots per taf and 10-minute window, claimed with a
 * conditional write, so it holds however many chats the messages start and
 * whatever the thread shows. A window is fixed, so at most 20 can straddle two.
 */
async function claimChatSlot(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<void> {
  const now = (deps.now?.() ?? new Date()).getTime();
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
}

/**
 * The chat a message goes into (kiff/tafs#73): a new one when the owner asked
 * for it (new_chat), the one they reopened (session, one of this taf's chats),
 * else the live one.
 */
async function chatFor(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<string | undefined> {
  const body = parseBody(event);
  if (body.new_chat === true) return undefined;
  const chosen = str(body.session).trim();
  if (chosen) return (await ownChat(me, taf, chosen, deps)).id;
  return liveChat(me, taf, deps);
}

/** One of the taf's chats, by its id: another taf's, a job run's or none is 404. */
async function ownChat(me: KIFFIdentity, taf: Taf, id: string, deps: Deps): Promise<Session> {
  if (!id) throw new HTTPError(404, "no such chat");
  let read: Awaited<ReturnType<Deps["agents"]["session"]>>;
  try {
    read = await deps.agents.session(me.tenantID, id);
  } catch (err) {
    if (err instanceof AgentsError && err.status === 404) throw new HTTPError(404, "no such chat");
    throw agentsFailure(err);
  }
  if (read.session.agent !== taf.id || read.session.kind !== "chat") throw new HTTPError(404, "no such chat");
  return read.session;
}

/**
 * The taf's chats, newest first, each named by the start of what the owner asked
 * (kiff/tafs#82): their first message that isn't a component's answer. Archived
 * ones are left out, or, with `archivedOnly`, are the only ones (kiff/tafs#97).
 */
async function chats(me: KIFFIdentity, taf: Taf, deps: Deps, archivedOnly = false): Promise<Array<{ session: string; started: string; updated: string; title: string }>> {
  const [all, archived] = await Promise.all([
    agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned)),
    deps.archived.list(me.tenantID, taf.id).then((ids) => new Set(ids)),
  ]);
  const listed = all.filter((s) => s.kind === "chat" && archived.has(s.id) === archivedOnly).slice(0, CHAT.chatsListed);
  const read = await Promise.all(listed.map((s) => deps.agents.session(me.tenantID, s.id).catch(() => undefined)));
  return listed.flatMap((s, i) => {
    const text = chatTitle((read[i]?.events ?? []).filter((e) => e.kind === "owner_message").map((e) => {
      const t = (e.body as { text?: unknown })?.text;
      return typeof t === "string" ? ownerWords(t.trim()) : "";
    }));
    if (!text) return [];
    const title = text.length > CHAT.chatTitle ? text.slice(0, CHAT.chatTitle - 1).trimEnd() + "…" : text;
    return [{ session: s.id, started: iso(s.started), updated: iso(s.updated), title }];
  });
}

/**
 * The owner's answer to a component, as the app writes it (contract 1 on
 * kiff/agents#91): "[<type> <id>] <answer>", such as "[approval connect-stripe] declined".
 */
const COMPONENT_ANSWER = /^\[(?:steps|notice|confirm|approval|sign_in|result) [^\]\s]+\]/;

/**
 * A chat's title (kiff/tafs#82): the owner's first message that isn't a component's
 * answer, which says what they asked. A chat of answers alone is named by the first
 * one's answer, without its component.
 */
export function chatTitle(ownerMessages: string[]): string {
  const said = ownerMessages.filter(Boolean);
  const asked = said.find((t) => !COMPONENT_ANSWER.test(t));
  if (asked) return asked;
  return (said[0] ?? "").replace(COMPONENT_ANSWER, "").trim();
}

/**
 * The owner archives one of the taf's chats (kiff/tafs#82): it leaves their list and
 * the thread, and a message no longer goes on in it as the live chat. It isn't
 * deleted: kiff/agents keeps it its 30 days, and the owner can still open it by its id.
 */
async function archive(me: KIFFIdentity, taf: Taf, session: string, deps: Deps): Promise<unknown> {
  const chat = await ownChat(me, taf, session, deps);
  const until = new Date((deps.now?.() ?? new Date()).getTime() + CHAT.archiveDays * 24 * 3600_000);
  await deps.archived.add(me.tenantID, taf.id, chat.id, until);
  return { session: chat.id, archived: true };
}

/**
 * The owner puts an archived chat back in their list (kiff/tafs#97). It goes back in the
 * thread too; the live chat stays the newest one.
 */
async function unarchive(me: KIFFIdentity, taf: Taf, session: string, deps: Deps): Promise<unknown> {
  const chat = await ownChat(me, taf, session, deps);
  await deps.archived.unarchive(me.tenantID, taf.id, chat.id);
  return { session: chat.id, archived: false };
}

/** The taf's latest chat when it was active in the last 30 minutes and isn't archived: the message goes on in it. */
async function liveChat(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<string | undefined> {
  const now = (deps.now?.() ?? new Date()).getTime();
  const sessions = await agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned));
  const latest = sessions.find((s) => s.kind === "chat");
  // An archived chat is put away (kiff/tafs#82): the next message starts a new one.
  if (latest && (await deps.archived.list(me.tenantID, taf.id)).includes(latest.id)) return undefined;
  return latest && now - Date.parse(latest.updated) < CHAT.liveMinutes * 60_000 ? latest.id : undefined;
}

/** A response streamed as server-sent events (kiff/tafs#60): its status and headers, then its events. */
export interface StreamResult {
  statusCode: number;
  headers: Record<string, string>;
  body: AsyncIterable<string>;
}

/**
 * POST /v1/tafs/{id}/messages/stream: the owner's message, as `messages`
 * takes it (the same checks, flood guard and chat), with its run held in the
 * foreground so the agent's words reach the phone as they are written. What
 * the phone gets is trimmed to what it shows:
 *
 *   event: session    {session, new_session, run}, first
 *   event: taken      {}: the run read a message the owner wrote while it went (steer)
 *   event: text       {text}: more of the answer
 *   event: activity   {tool}: a tool's name as the agent calls it, never its input
 *   event: held       {exception, invocation}: KIFF holds the call just made for the
 *                     owner, at this point of the answer (kiff/tafs#206)
 *   event: component  {component}: a component the agent showed
 *   event: end        {status, stop}: where the run stopped
 *   event: error      {error}: the stream broke; the conversation has the rest
 *
 * Tool inputs and results never reach the phone. The run is kiff/agents'
 * invocation's: a phone that goes away loses nothing, and reads the rest in
 * the conversation.
 */
export async function handleStream(event: HTTPEvent, deps: Deps): Promise<StreamResult> {
  try {
    const method = event.requestContext.http.method.toUpperCase();
    const parts = event.rawPath.split("/").filter(Boolean);
    if (!(method === "POST" && parts.length === 5 && parts[0] === "v1" && parts[1] === "tafs" && parts[3] === "messages" && parts[4] === "stream")) {
      throw new HTTPError(404, "not found");
    }
    const me = await person(event, deps);
    const taf = await owned(me, parts[2] ?? "", deps);
    need(me, "cards.manage");
    const owner = chatText(event);
    const lines = await takeCoachLines(me, taf, deps);
    let run: AsyncGenerator<RunStreamEvent> | undefined;
    let first: IteratorResult<RunStreamEvent> | undefined;
    let newSession: boolean;
    try {
      const text = lines.text(owner);
      await claimChatSlot(me, taf, deps);
      const [live, ai] = await Promise.all([chatFor(event, me, taf, deps), aiBound(event, deps)]);
      if (live) {
        try {
          run = deps.agents.streamRun(me.tenantID, taf.id, text, live, ai);
          first = await run.next();
        } catch (err) {
          if (refusedForAI(err) || !(err instanceof AgentsError && err.status >= 400 && err.status < 500)) throw agentsFailure(err);
          run = undefined;
        }
      }
      newSession = !run;
      if (!run) {
        try {
          run = deps.agents.streamRun(me.tenantID, taf.id, text, undefined, ai);
          first = await run.next();
        } catch (err) {
          throw agentsFailure(err);
        }
      }
    } catch (err) {
      // The message didn't start: Coach reads its lines with the next one.
      await lines.putBack();
      throw err;
    }
    return { statusCode: 200, headers: { "content-type": "text/event-stream", "cache-control": "no-store" }, body: relay(run, first, newSession) };
  } catch (err) {
    const status = err instanceof HTTPError || err instanceof KIFFError || err instanceof PushError ? err.status : 500;
    if (status === 500) console.error(err);
    const message = status === 500 ? "something went wrong on tafs cloud" : (err as Error).message;
    return { statusCode: status, headers: { "content-type": "application/json", "cache-control": "no-store" }, body: once(JSON.stringify({ error: message })) };
  }
}

function agentsFailure(err: unknown): unknown {
  return err instanceof AgentsError ? new HTTPError(err.status >= 500 ? 502 : err.status, err.message) : err;
}

async function* once(s: string): AsyncGenerator<string> {
  yield s;
}

function sse(name: string, data: unknown): string {
  return `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
}

/** How long a switch asked to stop what blocks it waits for a running run's checkpoint, and how often it looks. */
export const STOP_WAIT = { tries: 8, everyMs: 1500 };

/**
 * Begins the switch to version n. A run in the way is named in the 409
 * (`blocking`: run, session, status), so the app says which and offers "Stop
 * it and install" (kiff/tafs#206). With stopBlocking, those runs are stopped
 * first (a paused one's held calls withdrawn in KIFF, a running one at its
 * next checkpoint) and the switch is tried again until they have ended.
 */
async function beginSwitch(me: KIFFIdentity, taf: Taf, n: number, draft: string | undefined, stopBlocking: boolean, deps: Deps): Promise<unknown> {
  const wait = deps.sleep ?? ((ms: number) => new Promise((r) => setTimeout(r, ms)));
  const stopped = new Set<string>();
  for (let tries = 0; ; tries++) {
    let blocking: BlockingRun[];
    try {
      return await deps.agents.beginSwitch(me.tenantID, taf.id, n, draft);
    } catch (err) {
      if (!(err instanceof AgentsError) || err.status !== 409 || !err.blocking?.length) return agentsCall(() => Promise.reject(err));
      blocking = err.blocking;
      if (!stopBlocking || tries >= STOP_WAIT.tries) {
        throw new HTTPError(409, err.message, { blocking: blocking.map((b) => ({ run: b.id, session: b.session, status: b.status, trigger: b.trigger })) });
      }
    }
    for (const b of blocking) {
      if (stopped.has(b.id)) continue;
      try {
        await deps.agents.cancelRun(me.tenantID, taf.id, b.id);
      } catch (err) {
        if (!(err instanceof AgentsError) || err.status !== 409) throw agentsFailure(err); // 409: it ended meanwhile
      }
      stopped.add(b.id);
    }
    await wait(STOP_WAIT.everyMs);
  }
}

/** kiff/agents' run stream, trimmed to what the phone shows. */
async function* relay(run: AsyncGenerator<RunStreamEvent>, first: IteratorResult<RunStreamEvent> | undefined, newSession: boolean): AsyncGenerator<string> {
  const steps = new Steps();
  const out = (e: RunStreamEvent): string | undefined => {
    const d = (e.data ?? {}) as Record<string, unknown>;
    switch (e.event) {
      case "run":
        return sse("session", { session: d.session, new_session: newSession, run: d.id });
      case "delta":
        if (d.kind === "text" && typeof d.text === "string") return sse("text", { text: d.text });
        if (d.kind === "tool_call" && typeof d.tool === "string") {
          // What it's doing, in a few words: only these, never the call's arguments.
          const step = steps.call(d.tool, d.input, typeof d.call_id === "string" ? d.call_id : undefined);
          return sse("activity", step === undefined ? { tool: d.tool } : { tool: d.tool, step });
        }
        if (d.kind === "component" && d.component !== undefined) return sse("component", { component: d.component });
        if (d.kind === "owner_message") return sse("taken", {}); // the app has the text: it wrote it
        if (d.kind === "tool_result") {
          steps.result(d.call_id, (d.result as { text?: unknown } | undefined)?.text);
          // Where KIFF held a call: the app shows the owner's card here in the answer. Only
          // KIFF's keys for it, never what the tool answered.
          const r = (d.result ?? {}) as { state?: unknown; hold?: { exception?: unknown }; invocation?: unknown };
          if (r.state === "held") {
            return sse("held", {
              exception: typeof r.hold?.exception === "string" ? r.hold.exception : "",
              invocation: typeof r.invocation === "string" ? r.invocation : "",
            });
          }
        }
        return undefined; // tool results stay on the server
      case "end":
        return sse("end", { status: d.status ?? "", stop: d.stop ?? "" });
      default:
        return undefined; // recorded events: the conversation has them
    }
  };
  try {
    if (first && !first.done) {
      const s = out(first.value);
      if (s) yield s;
    }
    for await (const e of run) {
      const s = out(e);
      if (s) yield s;
    }
  } catch (err) {
    console.error(err);
    yield sse("error", { error: "the stream broke; the conversation has the rest" });
  }
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

/**
 * What an upgrade did with one taf (kiff/tafs#69): its definition was current,
 * written (or, in a dry run, would be), or the upgrade failed for it, with why.
 */
export interface UpgradeOutcome {
  tenant: string;
  taf: string;
  job: string;
  outcome: "current" | "written" | "would write" | "failed";
  /** Why it failed, by kind only (`upgradeFailure`): the full message goes to the function's own log. */
  error?: string;
}

/** A reason this code gives for not upgrading a taf: its words name no account and no taf. */
class UpgradeError extends Error {}

/**
 * A failure as an upgrade reports it: by kind only, since the report reaches a
 * public log. An AWS error's message names the account (its ARN), and some of
 * kiff/agents' name the tenant, so neither message is kept here.
 */
export function upgradeFailure(err: unknown): string {
  if (err instanceof UpgradeError) return err.message;
  if (err instanceof AgentsError) return `kiff/agents answered ${err.status}`;
  const name = err instanceof Error && err.name && err.name !== "Error" ? err.name : "an error";
  return `AWS or the runtime: ${name}`;
}

/**
 * Brings every taf's definition up to what this code builds (kiff/tafs#69), so
 * a change to the shared instructions, a job, KIFF's skill, the model or its
 * effort reaches the tafs owners already run, from their next run.
 *
 * It runs by hand (the upgrade workflow), after kiff/agents' nightly eval has
 * passed at this tafs commit: evals gate what reaches running tafs. A taf is
 * written only when its built definition's digest differs from the one on its
 * record, since each write costs its next run a fresh prompt cache. One
 * started before the record kept its budget and start notes gets them once,
 * read back from the definition kiff/agents holds, so its budget is never
 * changed by an upgrade. A paused run carries on under the new definition
 * (kiff/agents builds the agent on each execution), at the cost of one fresh
 * prompt cache.
 * The definition is the agent's text, model and caps: its KIFF key and Cards
 * are never touched.
 */
export async function upgradeDefinitions(deps: Deps, opts: { dryRun: boolean }): Promise<UpgradeOutcome[]> {
  const out: UpgradeOutcome[] = [];
  for (const tenant of await deps.tafs.tenants()) {
    for (const taf of await deps.tafs.list(tenant)) {
      if (taf.removing) continue;
      const one: UpgradeOutcome = { tenant, taf: taf.id, job: taf.job, outcome: "current" };
      try {
        let record = taf;
        let stored: AgentDefinition | undefined;
        if (taf.job !== SETUP_JOB && taf.budgetCents === undefined) {
          stored = await deps.agents.getAgent(tenant, taf.id);
          if (!stored) throw new UpgradeError("kiff/agents holds no definition for it");
          if (typeof stored.instructions !== "string" || !Number.isInteger(stored.max_cost_micro_usd / 10_000)) {
            throw new UpgradeError("its stored definition has no budget or instructions to keep");
          }
          const notes = ownerNotesIn(stored.instructions);
          record = { ...record, budgetCents: stored.max_cost_micro_usd / 10_000, ...(notes ? { ownerNotes: notes } : {}) };
        }
        let want: AgentDefinition;
        try {
          want = record.job === SETUP_JOB
            ? setupDefinition(record.id)
            : definition(record.id, jobById(record.job), { budgetCents: record.budgetCents, ownerNotes: record.ownerNotes });
        } catch (err) {
          throw new UpgradeError(`its definition can't be built: ${(err as Error).message}`);
        }
        const wanted = digest(want);
        // A record with no digest yet: compare with what kiff/agents holds, so an
        // unchanged definition isn't written again.
        if (!record.definition) {
          stored ??= await deps.agents.getAgent(tenant, taf.id);
          if (stored && digest(stored) === wanted) record = { ...record, definition: wanted };
        }
        if (record.definition !== wanted) {
          one.outcome = opts.dryRun ? "would write" : "written";
          if (!opts.dryRun) {
            await deps.agents.putAgent(tenant, taf.id, want);
            record = { ...record, definition: wanted };
          }
        }
        if (!opts.dryRun && JSON.stringify(record) !== JSON.stringify(taf)) await deps.tafs.put(tenant, record);
      } catch (err) {
        one.outcome = "failed";
        one.error = upgradeFailure(err);
        console.error(`upgrade: ${tenant} ${taf.id}:`, err); // the function's own log, which is private
      }
      out.push(one);
    }
  }
  return out;
}

/** A note the taf keeps. Its name is its id. *//** A note the taf keeps. Its name is its id. */
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
    status: s && !s.enabled && !taf.unscheduled ? "paused" : "active",
    /** It works from its chat: no schedule runs it (kiff/tafs#138). Only said when so. */
    ...(taf.unscheduled ? { unscheduled: true } : {}),
    next_run: s?.enabled && s.next ? iso(s.next) : null,
    last_run: last ? iso(last.created) : null,
  };
}

/**
 * The app's home list (kiff/tafs#91): every taf with its state, colour and
 * Card summary, then the account's other agents with Cards, the ones tafs
 * doesn't run (made through KIFF's API), listed apart. KIFF's Cards and held
 * calls are read with the owner's session; a KIFF that can't be read fails the
 * list rather than showing tafs without their Cards.
 */
async function home(event: HTTPEvent, me: KIFFIdentity, deps: Deps) {
  const [records, removing, kiff] = await Promise.all([
    deps.tafs.list(me.tenantID),
    deps.removals.list(me.tenantID),
    readKIFF(bearer(event), deps.fetch, deps.kiffBase),
  ]);
  const { cards, waiting } = byHolder(kiff.mandates, kiff.held);
  const tafs = records.filter((t) => !t.removing && t.job !== SETUP_JOB)
    .sort((a, b) => a.created.localeCompare(b.created) || a.id.localeCompare(b.id));
  // A taf keeps the colour it got at its start. One started before colours takes
  // the first nobody has, kept only if its record is still what was read here:
  // the list never writes back over a removal or an upgrade.
  const taken = tafs.flatMap((t) => (t.color === undefined ? [] : [t.color]));
  const colors = new Map<string, number>();
  for (const t of tafs) {
    if (t.color !== undefined) continue;
    const color = freeColor(taken);
    taken.push(color);
    colors.set(t.id, color);
    await deps.tafs.putIf(me.tenantID, { ...t, color }, t);
  }
  const views = await Promise.all(tafs.map((t) => view(t, me, deps)));
  const listed = tafs.map((t, i) => {
    const v = views[i]!;
    const card = summary(cards.get(t.kiffAgent) ?? []);
    const held = waiting.get(t.kiffAgent) ?? 0;
    // Its job in words, for the taf's page (kiff/tafs#167), not its Cards' count.
    const words = JOBS.find((j) => j.id === v.job)?.summary;
    return { ...v, ...(words ? { job_summary: words } : {}), color: t.color ?? colors.get(t.id) ?? 0, state: stateOf(card, held, v.status === "paused" ? "paused" : "active"), waiting: held, card };
  });
  // Setup's agent and a taf being removed are tafs cloud's too: never another agent.
  // Another agent's colour isn't kept: it can change when a taf is added.
  const ours = new Set(records.map((t) => t.kiffAgent).filter(Boolean));
  const agents = [...new Set([...cards.keys(), ...waiting.keys()])].filter((a) => !ours.has(a)).sort();
  const others = agents.map((agent) => {
    const card = summary(cards.get(agent) ?? []);
    const held = waiting.get(agent) ?? 0;
    const color = freeColor(taken);
    taken.push(color);
    return { id: null, name: agent, job: null, kiff_agent: agent, status: null, next_run: null, last_run: null, color, state: stateOf(card, held, null), waiting: held, card };
  });
  return { tafs: listed, others, removing };
}

/** The period a chat counts toward and its bound, from KIFF's usage read; none when KIFF gives no plan. */
async function aiBound(event: HTTPEvent, deps: Deps): Promise<AIBound | undefined> {
  return aiFor(await planOf(bearer(event), deps.fetch, deps.kiffBase));
}

/**
 * GET /v1/usage/ai (kiff/tafs#193): the period's AI use as a share of half the
 * plan's price, where the tafs ask next (100%, then 200% after one go on…), and
 * whether they're asking now. The owner's go on is KIFF's signed route, as
 * raising the ceiling is. No bound (the internal plan, or no plan from KIFF): nulls.
 */
async function aiUsage(event: HTTPEvent, me: KIFFIdentity, deps: Deps): Promise<AIView> {
  const plan = await planOf(bearer(event), deps.fetch, deps.kiffBase);
  if (!plan || base(plan) <= 0) return aiView(plan, 0);
  return aiView(plan, await agentsCall(() => deps.agents.aiSpent(me.tenantID, periodName(plan.start))));
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

// Coach's tools (kiff/tafs#206): coach.ts serves the MCP protocol and checks
// KIFF's permit; these do each tool's job for the permit's account, through
// kiff/agents only. tafs cloud holds no KIFF credential for Coach, so nothing
// here reads or drafts in KIFF: installing a version is the app's, with the
// owner's own session, in the step where they approve the held call.

/** The account as Coach acts on it: its agent, with cards.manage, never a person. */
function coachIdentity(call: CoachCall): KIFFIdentity {
  return { tenantID: call.tenant, subject: call.coach, apiKey: true, agentID: call.coach, capabilities: ["cards.manage"] };
}

/** The account's Coach: its Setup's KIFF agent, or none. */
async function coachOf(tenant: string, deps: Deps): Promise<string | undefined> {
  const setup = (await deps.tafs.list(tenant)).find((t) => t.job === SETUP_JOB && !t.removing);
  return setup?.kiffAgent || undefined;
}

/** A taf of the account by id, or by its name, ignoring case; never Setup. */
async function coachTaf(tenant: string, ref: unknown, deps: Deps): Promise<Taf> {
  const want = str(ref).trim();
  const tafs = (await deps.tafs.list(tenant)).filter((t) => !t.removing && t.job !== SETUP_JOB);
  const taf = tafs.find((t) => t.id === want) ?? tafs.find((t) => t.name.toLowerCase() === want.toLowerCase());
  if (!taf) throw new ToolRefusal(`there is no taf "${want}" on this account: tafs_state lists them`);
  return taf;
}

/** What a run waits on, as Coach reads it: each held call, its arguments and deadline. */
function waitsOn(r: Run) {
  return (r.requirements ?? []).filter((q) => !q.answered).map((q) => ({
    tool: q.tool, arguments: q.input, deadline: iso(q.expires), ...(q.exception ? { held_call: q.exception } : {}),
  }));
}

function runSummary(r: Run) {
  return {
    run: r.id, status: r.status, trigger: r.trigger, started: iso(r.created), updated: iso(r.updated),
    ...(r.output ? { answer: r.output } : {}), ...(r.error ? { error: r.error } : {}),
    ...(r.status === "PAUSED" ? { waits_on: waitsOn(r) } : {}),
  };
}

/** Keeps the task under its ref, so its run's end finds Coach (`runEnded`). */
async function keepTask(call: CoachCall, notify: Notify, task: Task, deps: Deps): Promise<void> {
  if (!deps.coach) return;
  const at = (deps.now?.() ?? new Date()).getTime();
  await deps.coach.put(coachKeys.task(call.tenant, notify.ref), task, new Date(at + TASK_KEPT_MS));
}

/** Coach's run at work now, if any: the one making this call. */
async function coachRun(call: CoachCall, deps: Deps): Promise<string | undefined> {
  const setup = (await deps.tafs.list(call.tenant)).find((t) => t.job === SETUP_JOB && !t.removing);
  if (!setup) return undefined;
  const runs = await deps.agents.runs(call.tenant, setup.id, true, 5).catch(() => []);
  return runs.find((r) => r.status === "RUNNING")?.id;
}

export async function coachTool(name: string, args: Record<string, unknown>, call: CoachCall, deps: Deps): Promise<ToolAnswer> {
  try {
    return await coachToolOf(name, args, call, deps);
  } catch (err) {
    if (err instanceof HTTPError && err.status < 500) throw new ToolRefusal(err.message);
    throw err;
  }
}

async function coachToolOf(name: string, args: Record<string, unknown>, call: CoachCall, deps: Deps): Promise<ToolAnswer> {
  const me = coachIdentity(call);
  if (name === "tafs_state") {
    const tafs = (await deps.tafs.list(call.tenant)).filter((t) => !t.removing && t.job !== SETUP_JOB);
    return {
      tafs: await Promise.all(tafs.map(async (t) => {
        const [state, schedule, runs] = await Promise.all([
          agentsCall(() => deps.agents.versions(call.tenant, t.id)),
          t.unscheduled ? Promise.resolve(undefined) : deps.agents.getSchedule(call.tenant, t.id).catch(() => undefined),
          agentsCall(() => deps.agents.runs(call.tenant, t.id, true)),
        ]);
        const live = state.versions.find((v) => v.live);
        return {
          id: t.id, name: t.name, job: t.job, kiff_agent: t.kiffAgent,
          ...(t.unscheduled ? { works_from_chat: true } : schedule
            ? { schedule: { cron: schedule.cron, time_zone: schedule.time_zone || "UTC", on: schedule.enabled, ...(schedule.next ? { next: iso(schedule.next) } : {}) } }
            : {}),
          live_version: live?.n ?? null,
          versions: state.versions.map((v) => ({ n: v.n, note: v.note, live: v.live, created: iso(v.created) })),
          ...(state.switching ? { switching: { to: state.switching.n, waiting_for: state.switching.draft ? "the owner to confirm its Card in tafs" : "its runs to stop" } } : {}),
          open_runs: runs.map(runSummary),
        };
      })),
      cards: "Each taf's Cards aren't here: read them with kiff_card {agent: its kiff_agent}.",
    };
  }
  const taf = await coachTaf(call.tenant, args.taf, deps);
  const n = Number(args.n);
  if (name === "taf_run") {
    const runID = str(args.run).trim();
    const r = await agentsCall(() => deps.agents.getRun(call.tenant, taf.id, runID));
    if (r.trigger === "trial") {
      const t = await agentsCall(() => deps.agents.trial(call.tenant, taf.id, runID));
      return { taf: taf.name, ...runSummary(t.run), writes: t.writes };
    }
    return { taf: taf.name, ...runSummary(r) };
  }
  if (name === "taf_task") {
    const text = str(args.text).trim();
    if (!text || text.length > CHAT.maxText) throw new ToolRefusal(`write the task in 1 to ${CHAT.maxText} characters`);
    await claimChatSlot(me, taf, deps);
    const running = str(args.run).trim();
    if (running) {
      try {
        const out = await deps.agents.steerRun(call.tenant, taf.id, running, text);
        return { taf: taf.name, run: out.id, status: out.status, sent: "to the run at work, read at its next step" };
      } catch (err) {
        if (err instanceof AgentsError && err.status === 409) throw new ToolRefusal("that run has ended: hand the task over again without run");
        throw agentsFailure(err);
      }
    }
    const session = args.chat === "new" ? undefined : await liveChat(me, taf, deps);
    const notify = call.notify();
    const run = await agentsCall(() => deps.agents.startRun(call.tenant, taf.id, text, session, undefined, notify));
    await keepTask(call, notify, { taf: taf.id, run: run.id, coachRun: await coachRun(call, deps), what: text.slice(0, 120) }, deps);
    return { taf: taf.name, run: run.id, chat: run.session, status: run.status, result: "comes back to you when the run ends: don't poll" };
  }
  if (name === "run_stop") {
    try {
      const out = await deps.agents.cancelRun(call.tenant, taf.id, str(args.run).trim());
      return { taf: taf.name, run: out.id, status: out.status, withdrawn: "its held calls, in KIFF; nothing more is sent" };
    } catch (err) {
      if (err instanceof AgentsError && err.status === 409) return { taf: taf.name, stopped: false, why: "the run had already ended" };
      throw agentsFailure(err);
    }
  }
  if (name === "taf_pause") {
    const on = args.action === "resume";
    if (!on && args.action !== "pause") throw new ToolRefusal("action is pause or resume");
    if (taf.unscheduled) throw new ToolRefusal(`${taf.name} works from its chat: it has no schedule to ${on ? "resume" : "pause"}`);
    const s = await agentsCall(() => deps.agents.switchSchedule(call.tenant, taf.id, on));
    return { taf: taf.name, schedule: on ? "on" : "paused", ...(s.next ? { next: iso(s.next) } : {}) };
  }
  if (!Number.isInteger(n) || n < 1) throw new ToolRefusal("n is a version number, from tafs_state");
  const state = await agentsCall(() => deps.agents.versions(call.tenant, taf.id));
  const version = state.versions.find((v) => v.n === n);
  if (!version) throw new ToolRefusal(`${taf.name} has no version ${n}`);
  if (name === "version_try") {
    const asked = str(args.input).trim();
    const input = asked || JOBS.find((j) => j.id === taf.job)?.kickoff || "";
    if (!input || input.length > CHAT.maxText) throw new ToolRefusal(`give the trial 1 to ${CHAT.maxText} characters to work on`);
    const notify = call.notify();
    const run = await agentsCall(() => deps.agents.startTrial(call.tenant, taf.id, n, input, notify));
    await keepTask(call, notify, { taf: taf.id, run: run.id, coachRun: await coachRun(call, deps), what: `a trial of version ${n}`, trial: true }, deps);
    return { taf: taf.name, trial: run.id, status: run.status, result: "comes back to you when the trial ends: don't poll" };
  }
  if (name === "version_install") {
    // The owner approved it in tafs: the app began the switch and confirmed the
    // Card first, with their own session. This only says where it stands.
    if (version.live) return { taf: taf.name, version: n, installed: true };
    if (state.switching?.n === n) {
      return { taf: taf.name, version: n, installed: false, waiting_for: state.switching.draft ? "the owner to confirm its Card in tafs" : "its runs to stop" };
    }
    return { taf: taf.name, version: n, installed: false, say: `Open ${taf.name} in tafs to finish installing version ${n}.` };
  }
  throw new ToolRefusal(`there is no tool ${name}`);
}

/** KIFF's published permit keys, fetched once per container. */
let kiffPermitKeys: PermitKeys | undefined;

/**
 * POST /v1/mcp: Coach's tools, reached only through KIFF's gateway, each call
 * with KIFF's permit (coach.ts). The audience is this server as the
 * connection names it: its own host, which a function URL can't be called
 * under any other.
 */
async function mcpRoute(event: HTTPEvent, deps: Deps): Promise<HTTPResult> {
  const host = event.headers?.host ?? event.headers?.Host;
  if (!deps.coach || !host || event.requestContext.http.method.toUpperCase() !== "POST") throw new HTTPError(404, "not found");
  const issuer = deps.kiffBase ?? KIFF_API;
  kiffPermitKeys ??= publishedKeys(`${issuer}/.well-known/kiff-permit-keys.json`, deps.fetch);
  const headers: Record<string, string | undefined> = {};
  for (const [k, v] of Object.entries(event.headers ?? {})) headers[k.toLowerCase()] = v;
  const r = await serveMCP(rawBody(event), headers, {
    audience: `https://${host}/v1/mcp`, issuer, keys: deps.permitKeys ?? kiffPermitKeys, store: deps.coach,
    coachOf: (tenant) => coachOf(tenant, deps), notifyURL: `https://${host}/v1/runs/ended`,
    run: (name, args, call) => coachTool(name, args, call, deps),
    now: deps.now ?? (() => new Date()), log: (msg, err) => console.error(`${msg}:`, err),
  });
  return r.body === undefined ? { statusCode: r.status, headers: { "cache-control": "no-store" }, body: "" } : json(r.status, r.body);
}

/**
 * POST /v1/runs/ended: kiff/agents' note that a run Coach handed a taf paused
 * or ended (kiff/tafs#206). The note is unsigned: one whose ref tafs cloud
 * doesn't hold is dropped, and what Coach is told is read back from
 * kiff/agents, never taken from the note. Coach hears it in its run at work,
 * if the one that handed the task over still is; otherwise it waits for
 * Coach's next turn, and the app shows it in Coach's chat at once.
 */
async function runEnded(event: HTTPEvent, deps: Deps): Promise<unknown> {
  const note = parseNote(rawBody(event));
  if (note && deps.doors && await doorRunEnded(note, doorContext(deps))) return { taken: true };
  const tenant = note && refTenant(note.ref);
  if (!note || !tenant || !deps.coach) return { taken: false };
  const key = coachKeys.task(tenant, note.ref);
  const task = (await deps.coach.get(key)) as Task | undefined;
  if (!task || task.run !== note.run || task.taf !== note.agent) return { taken: false };
  const r = await agentsCall(() => deps.agents.getRun(tenant, task.taf, task.run));
  const ended = r.status === "COMPLETED" || r.status === "ERROR" || r.status === "CANCELLED";
  if (!ended && r.status !== "PAUSED") return { taken: false };
  const taf = await deps.tafs.get(tenant, task.taf);
  const name = taf?.name ?? task.taf;
  const { line, said } = coachLine(name, task, r);
  const tafs = await deps.tafs.list(tenant);
  const setup = tafs.find((t) => t.job === SETUP_JOB && !t.removing);
  let told = false;
  if (setup && task.coachRun) {
    told = await deps.agents.steerRun(tenant, setup.id, task.coachRun, line).then(() => true, () => false);
  }
  if (!told) {
    const at = deps.now?.() ?? new Date();
    // The owner reads the taf and how it went; Coach reads the whole line.
    await deps.coach.append(coachKeys.notes(tenant), { at: at.toISOString(), text: line, taf: name, said }, new Date(at.getTime() + TASK_KEPT_MS));
  }
  if (ended) await deps.coach.remove(key);
  return { taken: true };
}

/**
 * How a task's run went: the line Coach reads (what it was, how it went, in two
 * sentences at most), and what the owner sees of it in Coach's chat meanwhile.
 */
function coachLine(name: string, task: Task, r: Run): { line: string; said: string } {
  const what = task.trial ? "trial" : "run";
  const head = `[taf ${name} ${what} ${r.id} ${r.status === "PAUSED" ? "paused" : "ended"}: ${task.what}]`;
  if (r.status === "PAUSED") {
    const waits = waitsOn(r).map((w) => w.tool).join(", ");
    return { line: `${head} It waits for the owner to answer a held call${waits ? ` (${waits})` : ""}. Never answer it yourself.`, said: "Waits for your answer." };
  }
  if (r.status === "ERROR") {
    const why = twoSentences(r.error || "no reason given");
    return { line: `${head} It failed. error: ${JSON.stringify(why)}`, said: `It failed: ${why}` };
  }
  if (r.status === "CANCELLED") return { line: `${head} It was stopped.`, said: "It was stopped." };
  if (!r.output) return { line: `${head} It finished with no answer.`, said: "It finished with no answer." };
  // The taf's own words, quoted: what it read can be in them, so they are relayed, never followed.
  const out = twoSentences(r.output);
  return { line: `${head} It finished. answer: ${JSON.stringify(out)}${task.trial ? " Read what KIFF decided on each write with taf_run." : ""}`, said: out };
}

function twoSentences(text: string): string {
  const flat = text.replace(/\s+/g, " ").trim();
  const m = flat.match(/^(.+?[.!?])\s+(.+?[.!?])(\s|$)/);
  const two = m ? `${m[1]} ${m[2]}` : flat;
  return two.length > 400 ? `${two.slice(0, 399)}…` : two;
}

/**
 * The lines about Coach's tasks it hasn't read, taken for the owner's message
 * to Setup (kiff/tafs#206): `text` puts them before the owner's words, and
 * `putBack` returns them when the message doesn't start, so none is lost. The
 * thread shows only the owner's words (ownerWords). Another taf's message is
 * as written.
 */
async function takeCoachLines(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<{ text: (owner: string) => string; putBack: () => Promise<void> }> {
  if (taf.job !== SETUP_JOB || !deps.coach) return { text: (owner) => owner, putBack: async () => {} };
  const store = deps.coach;
  const key = coachKeys.notes(me.tenantID);
  const taken = await store.take(key);
  const said = (taken as Array<{ text?: unknown }>).map((l) => l?.text).filter((t): t is string => typeof t === "string");
  return {
    text: (owner) => said.length ? `${COACH_LINES}\n${said.join("\n")}\nThe owner says: ${owner}` : owner,
    putBack: async () => {
      const at = (deps.now?.() ?? new Date()).getTime();
      for (const line of taken) {
        await store.append(key, line, new Date(at + TASK_KEPT_MS)).catch((err) => console.error("coach lines not put back:", err));
      }
    },
  };
}

/** The lines about Coach's tasks it hasn't read, for the app to show in Coach's chat. */
async function coachLines(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<{ coach_lines?: Array<{ at: string; taf: string; said: string }> }> {
  if (taf.job !== SETUP_JOB || !deps.coach) return {};
  const kept = (await deps.coach.get(coachKeys.notes(me.tenantID))) as Array<{ at?: string; taf?: string; said?: string }> | undefined;
  const lines = (kept ?? []).filter((l): l is { at: string; taf: string; said: string } =>
    typeof l.at === "string" && typeof l.taf === "string" && typeof l.said === "string").map(({ at, taf, said }) => ({ at, taf, said }));
  return lines.length ? { coach_lines: lines } : {};
}

/* A taf's doors (doors/): where work reaches it from outside the app. */

function doorContext(deps: Deps): DoorsContext {
  if (!deps.doors) throw new HTTPError(404, "not found");
  return { doors: deps.doors, tafs: deps.tafs, agents: deps.agents, push: deps.push, apns: deps.apns, now: deps.now?.() ?? new Date() };
}

/** A door's webhook or page: no sign-in, its provider's signature is the check (doors/inbound.ts). */
async function doorWebhook(event: HTTPEvent, deps: Deps): Promise<HTTPResult> {
  if (!deps.doors) return json(404, { error: "not found" });
  const r = await inbound({
    method: event.requestContext.http.method.toUpperCase(),
    path: event.rawPath,
    query: new URLSearchParams(event.rawQueryString ?? ""),
    header: (name) => header(event, name),
    body: rawBody(event),
  }, doorContext(deps));
  const type = r.type === "html" ? "text/html; charset=utf-8" : r.type === "text" ? "text/plain; charset=utf-8" : "application/json";
  return { statusCode: r.status, headers: { "content-type": type, "cache-control": "no-store" }, body: r.body };
}

/** The owner's door routes, under /v1/tafs/{id}/doors. Reading needs a session; changing, cards.manage. */
async function doorRoute(event: HTTPEvent, method: string, parts: string[], me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  const ctx = doorContext(deps);
  const owner: Owner = {
    tenant: me.tenantID,
    ...(me.email ? { email: me.email } : {}),
    say: (text) => startMessage(event, me, taf, text, deps),
  };
  const [, , , , a, b, c] = parts;
  if (parts.length === 4 && method === "GET") {
    // The bound the owner's session can read now, kept for the door's runs.
    await keepBound(ctx.doors, me.tenantID, await aiBound(event, deps).catch(() => undefined), ctx.now);
    return listDoors(ctx, owner, taf);
  }
  need(me, "cards.manage");
  const body = parseBody(event);
  if (parts.length === 4 && method === "POST") return addDoor(ctx, owner, taf, body);
  if (parts.length === 5 && method === "POST" && a === "connect") return connectLink(ctx, owner, taf, body);
  if (parts.length === 6 && method === "POST" && a === "knocks") return answerKnock(ctx, owner, taf, b ?? "", body);
  if (parts.length === 5 && method === "PATCH" && a) {
    if (typeof body.open !== "boolean") throw new HTTPError(400, "open is true or false");
    return setOpen(ctx, owner, taf, a, body.open);
  }
  if (parts.length === 5 && method === "DELETE" && a) return removeDoor(ctx, owner, taf, a);
  if (parts.length === 6 && method === "POST" && a && b === "keys") return pair(ctx, owner, taf, a, body);
  if (parts.length === 7 && method === "DELETE" && a && b === "keys" && c) return unpair(ctx, owner, taf, a, decodeURIComponent(c));
  throw new HTTPError(404, "not found");
}

/** The taf's open knocks, for its thread: someone without a key wrote, and waits for the owner. */
async function openKnocks(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<{ knocks?: unknown[] }> {
  if (!deps.doors) return {};
  const knocks = (await knocksOf(deps.doors.store, me.tenantID, taf.id).catch(() => [])).filter((k) => k.state === "open");
  return knocks.length ? { knocks } : {};
}
