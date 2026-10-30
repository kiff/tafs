import { createHash, randomBytes, randomUUID } from "node:crypto";
import { JOBS, jobById } from "../jobs.js";
import { aiFor, type AIView, base, periodName, planOf, view as aiView } from "./aiuse.js";
import { type AgentDefinition, type AgentVersion, type Agents, AgentsError, refused, type AttachmentRef, type BlockingRun, type AIBound, type AgentsHalt, type CardRevision, type Notify, type Requester, type ResultOutcome, type Run, type RunAt, type RunStreamEvent, type Schedule, type Session, type Trial, type VersionState } from "./agents.js";
import { activeCards, cardRevisions, claimDraft, draftConfirmed, draftInfo, draftStatus, dropped, endTrialIn, type Fetch, haltedInKIFF, haltInKIFF, issued, KIFF_API, KIFFError, type KIFFHalt, type KIFFIdentity, redraft, restoreDraft, gatewayConnections, previewDraft, runGrant, toolTitles, trialOf, whoIs } from "./kiff.js";
import type { ReadyRun } from "./forward.js";
import { alertTarget, type Change, type Device, MAX_DEVICES, tellChange, type Notification, PACK_REQUESTED, PushError, type PushStore, receiveAlert, registration, type Sender, signedByKIFF } from "./push.js";
import { backoffMs, checkIntent, type Deletion, type DeletionPeer, type Deletions, IntentError, parseIntent, PeerError, STEPS, type Step } from "./deletion.js";
import { setupDefinition } from "./setupdef.js";
import { apsFor, levelOf, presenceKey, presentUntil } from "./levels.js";
import { definition, digest, keyRef, ownerNotesIn } from "./tafdef.js";
import { byHolder, freeColor, listable, readAccess, readKIFF, stateOf, summary } from "./home.js";
import { heldCards, improveContext, ownerWords, recentCalls } from "./improve.js";
import { skippedOf, summaryOf, workOf } from "./work.js";
import { COACH_CARDS, COACH_LINES, coachCardsDraft, coachConnect, coachToolsPlan, type CoachCall, type CoachStore, keys as coachKeys, newRef, parseNote, refTenant, serveMCP, type Task, TASK_KEPT_MS, type ToolAnswer, ToolRefusal } from "./coach.js";
import { type Keys as PermitKeys, publishedKeys } from "./permit.js";
import { stepWords, Steps, type ToolTitles } from "./steps.js";
import { addDoor, answerKnock, DoorError, doorLights, doorRunEnded, placesOf, sendAgain, settlePending, unsentOf, sweepDoorReplies, type DoorsContext, type DoorsDeps, keepBound, knocksOf, listDoors, offered, type Owner, pair, parseDoorMessage, removeDoor, removeTafDoors, sentOf, setOpen, unpair } from "./doors/doors.js";
import { inbound, isDoorPath } from "./doors/inbound.js";
import { type AccountEvent, type EventLog, logEvent } from "./events.js";

/**
 * tafs cloud: the few routes the phone needs that act for the person signed
 * in. Every taf runs on kiff/agents, which tafs cloud calls with its own AWS
 * role, naming the person's KIFF account. Everything else the phone asks KIFF
 * directly. Every route acts for the KIFF account of the person signed in,
 * and only on that account's tafs.
 *
 * docs/api.md documents every route, who may call it and what it takes; a route
 * added or changed here changes it too.
 *
 *   GET  /v1/jobs
 *   GET  /v1/home                       the app's list: each taf's state, colour, Card summary, doors and open trial, and the account's other agents with Cards
 *   GET  /v1/events                     halts, resumes and door events, newest first, for Activity (kiff/tafs#236)
 *   GET  /v1/usage/ai                   the period's AI use against its bound (kiff/tafs#193)
 *   POST /v1/account/delete             erase what tafs cloud and kiff/agents keep for the account (kiff/tafs#185)
 *   GET | POST | DELETE /v1/halt        the whole account's halt: read, halt, resume once KIFF runs again
 *   GET  /v1/setup                      the account's Setup (Coach), or null
 *   POST /v1/setup                      {draft_id, claim_secret}: start it once (kiff/agents#91)
 *   GET  /v1/tafs
 *   POST /v1/tafs                       {name, job, draft_id + claim_secret | from_setup + draft_id | agent_key, cron?, timezone?, budget_cents?, notes?, unscheduled?}
 *   POST /v1/tafs/{id}/pause | resume | run
 *   GET | POST | DELETE /v1/tafs/{id}/halt  one taf's halt
 *   GET  /v1/tafs/{id}/runs
 *   GET  /v1/tafs/{id}/runs/{session}   the run's report
 *   GET  /v1/tafs/{id}/conversation     the thread: the owner's messages and the taf's
 *   GET  /v1/tafs/{id}/conversation/{session}  one chat of it (kiff/tafs#73)
 *   GET  /v1/tafs/{id}/chats            the taf's chats, newest first, each by what the owner asked (kiff/tafs#73, #82)
 *   POST /v1/tafs/{id}/chats/{session}/archive  hide a chat from the list and the thread (kiff/tafs#82)
 *   GET  /v1/tafs/{id}/chats/archived   the chats the owner archived, to find and reopen one (kiff/tafs#97)
 *   POST /v1/tafs/{id}/chats/{session}/unarchive  put one back in the list (kiff/tafs#97)
 *   POST /v1/tafs/{id}/messages         {text, attachments?, session?, new_chat?}: into the live chat, a chosen one, or a new one
 *   POST /v1/tafs/{id}/messages/stream  the same, its answer streamed (handleStream)
 *   GET  /v1/tafs/{id}/runs/{run}/stream?after=N  the person's own run picked back up (handleStream)
 *   POST /v1/tafs/{id}/stop             {session}: its latest run is cancelled
 *   POST /v1/tafs/{id}/attachments      {media_type, name, size, sha256}: a file the person will attach, and where to upload it (kiff/tafs#209)
 *   GET  /v1/tafs/{id}/attachments/{att}  a file the person attached, with a download URL for a minute: only their own
 *   POST /v1/tafs/{id}/steer            {run, text}: to the run at work, read at its next step (kiff/tafs#64)
 *   POST /v1/tafs/{id}/holds/{exception}/continue  the run that paused on that held call goes on, once KIFF has answered it (kiff/tafs#206)
 *   POST /v1/tafs/{id}/components/{component}/answer  {answer, session?}: the person's answer to a card that waits for it, as data
 *   GET  /v1/tafs/{id}/versions         the taf's versions, oldest first, the live one marked, and a switch under way (kiff/tafs#107)
 *   POST /v1/tafs/{id}/versions         {note, notes?, draft?, from?, agent?}: its next version, as Setup drafted it, once per result (kiff/tafs#107); nothing goes live
 *   POST /v1/tafs/{id}/versions/{n}/switch  {redraft?, stop_blocking?}: begin the switch to version n, with the Card draft it waits for: nothing of the taf runs until it ends. A run in the way is a 409 naming it (blocking); stop_blocking stops it first (kiff/tafs#206)
 *   DELETE /v1/tafs/{id}/switching      drop a switch whose Card isn't confirmed yet
 *   POST /v1/tafs/{id}/versions/{n}/trial  {input?}: a trial of version n, its writes decided by KIFF, nothing sent
 *   GET  /v1/tafs/{id}/trials/{run}     a trial so far: its run and what KIFF decided for each write
 *   POST /v1/tafs/{id}/versions/{n}/live   make version n live, once its Card revision is confirmed in KIFF
 *   POST /v1/tafs/{id}/versions/{n}/try    the KIFF draft to confirm as version n's trial Card (kiff/tafs#173)
 *   PUT  /v1/tafs/{id}/trial            {n, draft}: open the trial, once KIFF has that draft trying
 *   GET  /v1/tafs/{id}/trial            the open trial, or none
 *   GET  /v1/tafs/{id}/trial/conversation  its chat
 *   POST /v1/tafs/{id}/trial/messages   {text}: one turn of the version being tried
 *   POST /v1/tafs/{id}/trial/install    once KIFF has confirmed its draft live: that version goes live, the trial ends
 *   DELETE /v1/tafs/{id}/trial          Go back: KIFF ends its trial Cards, the trial ends, the live version stays
 *   POST /v1/tafs/{id}/improve          {text}: a new conversation with the account's Setup, opening on this taf (kiff/tafs#107)
 *   GET  /v1/tafs/{id}/memory           what the taf remembers: its notes
 *   GET  /v1/tafs/{id}/calls            what its calls through KIFF acted on (kiff/tafs#167)
 *   DELETE /v1/tafs/{id}/memory/{note}  forget one note
 *   DELETE /v1/tafs/{id}                remove it: its schedule, runs, chats, notes and key; free its name
 *   POST /v1/push                       {device_token, platform?, environment, sound?, alert_target?}: this phone gets the account's alerts (platform ios, the default, or android)
 *   DELETE /v1/push/{token}             it stops
 *   POST /v1/alerts                     {alert_target}: KIFF's alert target for the account, with no phone's alerts (kiff/tafs#206)
 *   GET  /v1/alerts                     whether tafs cloud holds KIFF's alert secret for the account
 *   POST /v1/kiff/alerts                KIFF's signed held-call alerts (no sign-in: the signature is the check)
 *   POST /v1/mcp                        Coach's tools, through KIFF's gateway only (no sign-in: each call's KIFF permit is the check; kiff/tafs#206)
 *   POST /v1/runs/ended                 kiff/agents' note that a task Coach handed a taf, or a door's run, paused or ended (no sign-in: an unknown ref is dropped, and the run is read back)
 *   GET  /v1/tafs/{id}/doors            the taf's doors, the apps a door can open on here, and its open knocks
 *   POST /v1/tafs/{id}/doors            {app, code?, phone_number_id?, waba_id?}: turn a door on (a taf has none until then)
 *   PATCH /v1/tafs/{id}/doors/{door}    {open}
 *   DELETE /v1/tafs/{id}/doors/{door}
 *   POST /v1/tafs/{id}/doors/{door}/keys  {id, name?}: pair a sender
 *   DELETE /v1/tafs/{id}/doors/{door}/keys/{key}
 *   POST /v1/tafs/{id}/doors/knocks/{knock}  {action: do | pair | ignore, say?}
 *   POST /v1/doors/email | /v1/doors/whatsapp  the doors' webhooks (no sign-in: each provider's signature is the check)
 *   GET  /doors/whatsapp/connect        the page WhatsApp's signup runs in
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
  "putAgent" | "deleteAgent" | "startRun" | "sessions" | "session" | "archive" | "memories" | "forget" |
  "createSchedule" | "getSchedule" | "switchSchedule" | "trigger" | "scheduleRuns" | "scheduleFirings" | "cancelRun" | "continueRun" | "continueHeld" | "answerCard" | "answerResult" | "requesterAccess" | "claimTafDraft" | "getRun" | "streamRun" | "resumeRun" | "steerRun" | "getAgent" |
  "versions" | "switchVersion" | "startTrial" | "trial" | "beginSwitch" | "replaceSwitchDraft" | "cancelSwitch" | "createVersion" | "getVersion" |
  "createAttachment" | "readAttachment" |
  "halt" | "resume" | "halted" | "eraseTenant" | "aiSpent" | "runs" |
  "openTrial" | "openedTrial" | "trialMessage" | "endTrial">;

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
  /** Push: Apple's for an iPhone, Google's for an Android phone (see `routing`). */
  apns: Sender;
  /** Whether Android phones can be alerted here: false when Google push isn't set up, and an Android phone's registration is refused. Left out, they can. */
  androidPush?: boolean;
  /** Removals begun and not finished, so the app can finish them after a refresh. */
  removals: Removals;
  /** The chats each taf's owner archived before kiff/agents kept archives, to move there. */
  archived: Archived;
  /**
   * What tafs cloud keeps for an account beyond its tafs (its phones, alert
   * secrets, claimed drafts, chat flood slots), erased when the owner
   * deletes the account (kiff/tafs#185). Missing: accounts can't be deleted
   * here.
   */
  accountData?: AccountData;
  /**
   * Deletions driven by tafs cloud (deletion.ts): those not finished yet, with their signed intent,
   * and planque and KIFF, which each accept and finish one. Without them, an intent is refused.
   */
  deletions?: Deletions;
  deletionPeers?: { planque: DeletionPeer; kiff: DeletionPeer };
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
  /** What happens in tafs itself, for the app's Activity (kiff/tafs#236). Missing: not kept. */
  events?: EventLog;
  fetch?: Fetch;
  kiffBase?: string;
  /** KIFF's gateway, where the account's tool connections are (default mcp.kiff.dev). */
  kiffGateway?: string;
  /**
   * tafs cloud's own address as its callers name it, e.g. https://cloud.tafs.kiff.dev.
   * Behind CloudFront the request's host is the function's, so Coach's MCP audience
   * and the run notices use this. Unset: the request's host.
   */
  publicURL?: string;
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
  /**
   * The task to try each version with, as Setup suggested it in the owner's
   * words (kiff/tafs#247), by version: shown in the version's trial.
   */
  tries?: Record<string, string>;
  /**
   * Where its results go (docs/reliability.md): through the door the work came in on, a line
   * in the app, an email to the owner. Absent: the door's reply alone, as before.
   */
  results?: ResultPlace[];
}

/** A place a taf's results go. */
export type ResultPlace = "door_reply" | "app" | "owner_email";

/** Every place a result can go, in the order they're sent. */
export const RESULT_PLACES: readonly ResultPlace[] = ["door_reply", "app", "owner_email"];

/** The places a request names, each once, in RESULT_PLACES' order: at least one, and only those. */
export function resultPlaces(v: unknown): ResultPlace[] {
  if (!Array.isArray(v) || !v.length || !v.every((p) => typeof p === "string" && (RESULT_PLACES as readonly string[]).includes(p))) {
    throw new HTTPError(400, `places is one or more of ${RESULT_PLACES.join(", ")}`);
  }
  return RESULT_PLACES.filter((p) => v.includes(p));
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
 * The chats a taf's owner archived while tafs cloud kept archives (kiff/tafs#82).
 * kiff/agents keeps them now, per person: each is moved there once, then forgotten.
 */
export interface Archived {
  /** The taf's archived chats' sessions, as tafs cloud kept them. */
  list(tenant: string, tafID: string): Promise<string[]>;
  /** Forgets the taf's archive: moved, or the taf removed. */
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
} as const;

/** Memory limits: the owner's first note, and how many notes the app is shown. */
export const MEMORY = { maxNotes: 2000, shown: 100 } as const;

class HTTPError extends Error {
  constructor(readonly status: number, message: string, readonly extra?: Record<string, unknown>) {
    super(message);
  }
}

export async function handle(event: HTTPEvent, deps: Deps): Promise<HTTPResult> {
  const began = Date.now();
  const r = await answer(event, deps);
  logRequest(event, r.statusCode, Date.now() - began);
  return r;
}

async function answer(event: HTTPEvent, deps: Deps): Promise<HTTPResult> {
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

/** The fixed words of tafs cloud's routes: every other part of a path is the caller's (an id, an address, a name). */
const ROUTE_WORDS = new Set(("v1 account ai alerts answer archive archived attachments calls chats components continue conversation " +
  "delete deletions deliveries doors door email whatsapp ended events halt holds home improve install jobs keys kiff knocks live mcp " +
  "memory messages pause push requests results resume run runs send setup steer stop stream switch switching tafs trial trials try " +
  "unarchive usage versions work webhooks").split(" "));

/**
 * One line per request, so a check can be confirmed from tafs cloud's own logs: the method, the route
 * with every part that isn't one of its fixed words masked, the status and how long it took. Never a
 * body, a token, a query, an id, an address or a name.
 */
export function logRequest(event: HTTPEvent, status: number, ms: number): void {
  const route = event.rawPath.split("/").map((p) => (p === "" || ROUTE_WORDS.has(p) ? p : ":x")).join("/");
  console.log(JSON.stringify({ event: "request", method: event.requestContext.http.method.toUpperCase(), route, status, ms }));
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
  // Halts, resumes and door events, newest first, for Activity (kiff/tafs#236, items 15 and 19).
  if (parts[1] === "events" && parts.length === 2 && method === "GET") {
    const me = await person(event, deps);
    return { events: deps.events ? await doorEventsFor(event, me, await deps.events.list(me.tenantID), deps) : [] };
  }
  if (parts[1] === "account" && parts.length === 3 && parts[2] === "delete" && method === "POST") {
    const me = await person(event, deps, true);
    const body = parseBody(event);
    // The phone's signed intent: tafs cloud drives the whole deletion. Without one, the app's
    // earlier way, where the phone drives planque and KIFF itself.
    return body.intent === undefined ? deleteAccount(me, deps) : deleteByIntent(me, body, bearer(event), deps);
  }
  if (parts[1] === "usage" && parts.length === 3 && parts[2] === "ai" && method === "GET") return aiUsage(event, await person(event, deps), deps);
  if (parts[1] !== "tafs" && parts[1] !== "setup") throw new HTTPError(404, "not found");

  const me = await person(event, deps);

  if (parts[1] === "setup") {
    // The account's Setup (kiff/agents#91): its chat uses the taf routes, by its id.
    if (parts.length === 2 && method === "GET") {
      const setup = await setupOf(me, deps);
      if (!setup) return { setup: null, tools: null };
      const [shown, tools] = await Promise.all([view(setup, me, deps), coachTools(event, setup, deps)]);
      return { setup: shown, tools };
    }
    if (parts.length === 2 && method === "POST") {
      need(me, "cards.manage");
      return startSetup(event, me, deps);
    }
    throw new HTTPError(404, "not found");
  }

  if (parts.length === 2 && method === "GET") {
    const [records, access] = await Promise.all([deps.tafs.list(me.tenantID), readAccess(bearer(event), deps.fetch, deps.kiffBase)]);
    // Removed tafs aren't listed; ones whose removal didn't finish are, so it can be finished.
    // Only the tafs KIFF says the person may use or oversee are.
    const tafs = records.filter((t) => !t.removing && t.job !== SETUP_JOB && listable(access, t.kiffAgent));
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
    if (taf) {
      await needTaf(event, me, taf, deps);
      return remove(taf, me, deps);
    }
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
    await needTaf(event, me, taf, deps);
    const s = await agentsCall(() => deps.agents.switchSchedule(me.tenantID, taf.id, action === "resume"));
    await tellChange(me.tenantID, "taf", taf.id, deps);
    return { taf: await view(taf, me, deps, s) };
  }
  if (parts.length === 4 && method === "GET" && action === "work") {
    // Where its work stands, for its page (docs/reliability.md): every line and time written here.
    const now = deps.now?.() ?? new Date();
    const [runs, v, sent, fired] = await Promise.all([
      agentsCall(() => deps.agents.runs(me.tenantID, taf.id, false, 50)),
      view(taf, me, deps),
      deps.doors ? sentOf(deps.doors.store, me.tenantID, taf.id).catch(() => []) : Promise.resolve([]),
      // A taf with no schedule, or a kiff/agents without the history, has no skipped firings to show.
      taf.unscheduled ? Promise.resolve([]) : deps.agents.scheduleFirings(me.tenantID, taf.id, 20).catch(() => []),
    ]);
    const zone = me.timeZone ?? "UTC";
    const notSent = [...new Set(sent.filter((s) => s.status === "not_sent").map((s) => s.run))];
    const again = deps.doors ? await unsentOf(deps.doors.store, me.tenantID, taf.id, notSent).catch(() => new Set<string>()) : new Set<string>();
    return { taf: taf.id, time_zone: zone, summary: summaryOf(runs, { paused: v.status === "paused", unscheduled: taf.unscheduled === true, next: v.next_run, halted: await deps.agents.halted(me.tenantID, taf.id).then((h) => h.halted, () => false) }, zone, now), next_run: v.next_run, ...workOf(runs, zone, now, sent, again), skipped: skippedOf(fired, zone, now) };
  }
  if (parts.length === 8 && method === "POST" && action === "requests" && parts[5] === "deliveries" && parts[7] === "send") {
    // Send again: what the taf already wrote, through its door, once more. No work is done and nothing is signed.
    await needTaf(event, me, taf, deps);
    if (parts[6] !== "door_reply") throw new HTTPError(400, "only a door's reply is sent again");
    try {
      return await sendAgain(doorContext(deps), me.tenantID, taf.id, parts[4] ?? "");
    } catch (err) {
      if (err instanceof DoorError) throw new HTTPError(err.status, err.message);
      throw err;
    }
  }
  if (parts.length === 4 && method === "PUT" && action === "results") {
    await needTaf(event, me, taf, deps);
    const updated = { ...taf, results: resultPlaces(parseBody(event).places) };
    await deps.tafs.put(me.tenantID, updated);
    await tellChange(me.tenantID, "taf", taf.id, deps);
    return { taf: await view(updated, me, deps) };
  }
  if (parts.length === 4 && method === "POST" && action === "run") {
    await needTaf(event, me, taf, deps);
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
    // A door's reply a lost note left owed goes out now, and shows as sent.
    if (deps.doors) await settlePending(doorContext(deps), me.tenantID, taf.id).catch(() => undefined);
    const [doors, titles] = await Promise.all([managesTaf(event, me, taf, deps), titlesOf(event, me, taf, deps)]);
    const { messages, components, held, sessions } = await conversation(me, taf, deps, undefined, doors, titles);
    await handOverShown(me.tenantID, taf, components, deps);
    const latest = await latestRun(me, taf, sessions, deps);
    return { messages, components, items: threadItems(messages, components, held), working: latest.working, steering: latest.steering, ...(latest.capped ? { capped: latest.capped } : {}), ...(await coachLines(me, taf, deps)), ...(doors ? await openKnocks(me, taf, deps) : {}) };
  }
  if (parts.length === 5 && method === "GET" && action === "conversation") {
    const [only, titles] = await Promise.all([ownChat(me, taf, parts[4] ?? "", deps), titlesOf(event, me, taf, deps)]);
    const { messages, components, held, sessions } = await conversation(me, taf, deps, only, false, titles);
    await handOverShown(me.tenantID, taf, components, deps);
    const latest = await latestRun(me, taf, sessions, deps);
    return { messages, components, items: threadItems(messages, components, held), working: latest.working, steering: latest.steering, ...(latest.capped ? { capped: latest.capped } : {}), ...(await coachLines(me, taf, deps)) };
  }
  if (parts.length === 4 && method === "GET" && action === "chats") {
    return { chats: await chats(me, taf, deps) };
  }
  if (parts.length === 5 && method === "GET" && action === "chats" && parts[4] === "archived") {
    return { chats: await chats(me, taf, deps, true) };
  }
  if (parts.length === 6 && method === "POST" && action === "chats" && parts[5] === "archive") {
    return archive(me, taf, parts[4] ?? "", deps);
  }
  if (parts.length === 6 && method === "POST" && action === "chats" && parts[5] === "unarchive") {
    return unarchive(me, taf, parts[4] ?? "", deps);
  }
  if (parts.length === 4 && method === "POST" && action === "stop") {
    need(me, "cards.manage");
    return stop(event, me, taf, deps);
  }
  if (parts.length === 4 && method === "POST" && action === "steer") {
    // Steering is part of a person's chat: kiff/agents takes it only for their own run.
    return steer(event, me, taf, deps);
  }
  if (parts.length === 6 && method === "POST" && action === "holds" && parts[5] === "continue") {
    if (!me.capabilities.includes("holds.answer")) throw new HTTPError(403, "your role on this KIFF account can't answer held calls");
    return goOn(me, taf, parts[4] ?? "", deps);
  }
  if (parts.length === 6 && method === "POST" && action === "components" && parts[5] === "answer") {
    return answerRoute(event, me, taf, parts[4] ?? "", deps);
  }
  if (parts.length === 4 && method === "POST" && action === "messages") {
    return message(event, me, taf, deps);
  }
  if (parts.length === 4 && method === "POST" && action === "attachments") {
    return attachFile(event, me, taf, deps);
  }
  if (parts.length === 5 && method === "GET" && action === "attachments") {
    return readFile(me, taf, parts[4] ?? "", deps);
  }
  if (action === "versions" || action === "trials" || action === "switching") return versionRoute(event, method, parts, me, taf, deps);
  if (action === "trial") return trialRoute(event, method, parts, me, taf, deps);
  if (parts.length === 4 && method === "POST" && action === "improve") {
    await needTaf(event, me, taf, deps);
    return improve(event, me, taf, deps);
  }
  if (parts.length === 4 && method === "GET" && action === "memory") {
    return { notes: await notes(me, taf, deps) };
  }
  if (parts.length === 4 && method === "GET" && action === "calls") {
    return { calls: await callRefs(me, taf, deps) };
  }
  if (parts.length === 5 && method === "DELETE" && action === "memory") {
    // A person forgets their own notes ("@name"); a shared note is the taf's, so it takes managing it.
    if (!(parts[4] ?? "").startsWith("%40") && !(parts[4] ?? "").startsWith("@")) await needTaf(event, me, taf, deps);
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

async function person(event: HTTPEvent, deps: Deps, deleting = false): Promise<KIFFIdentity> {
  const me = await whoIs(bearer(event), deps.fetch, deps.kiffBase);
  if (me.apiKey) throw new HTTPError(403, "sign in as a person, not with a key");
  // From a deletion's acceptance on, the account can't be used: only the deletion itself goes on.
  if (!deleting && deps.deletions && await deps.deletions.get(me.tenantID)) throw new HTTPError(410, "this account is being deleted");
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
    if (r.platform === "android" && deps.androidPush === false) throw new HTTPError(503, "alerts on Android phones aren't set up on this tafs cloud");
    const answers = me.capabilities.includes("holds.answer");
    const addsTasks = me.capabilities.includes("billing.manage");
    const manages = me.capabilities.includes("cards.manage");
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
    await deps.push.addDevice(me.tenantID, { token: r.token, platform: r.platform, environment: r.environment, subject: me.subject, answers, addsTasks, manages, silent: r.silent, at });
    return { device: true, answers, alerts: Boolean(r.target) || secrets.length > 0 };
  }
  if (method === "DELETE" && parts.length === 3) {
    // The token as the phone wrote it in the path: Firebase's has colons, which come percent-encoded.
    let given = parts[2] ?? "";
    try { given = decodeURIComponent(given); } catch { /* as it is */ }
    const { devices } = await deps.push.get(me.tenantID);
    // A person removes their own phones; someone who manages Cards, any of the account's. An iPhone's token is hex in
    // lower case, whichever case it is written in; an Android phone's keeps its case.
    const allowed = (d: Device) => d.subject === me.subject || me.capabilities.includes("cards.manage");
    const mine = devices.find((d) => allowed(d) && (d.token === given || (d.platform !== "android" && d.token === given.toLowerCase())));
    if (mine) await deps.push.removeDevice(me.tenantID, mine.token);
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
 * A halt of one taf or, with none, of the whole account. POST halts it in
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
    const view = haltView(kiff, runtime);
    // A halt interrupts (kiff/tafs#210): every phone that answers hears it, through a Focus.
    await tellPhones(deps, me.tenantID, haltNotification(taf, view.state === "halted"));
    await logEvent(deps.events, me.tenantID, { kind: "halted", ...(taf ? { taf: taf.id, name: taf.name } : {}) }, deps.now?.());
    await tellChange(me.tenantID, "halted", taf?.id, deps);
    return { halt: view };
  }
  if (method === "DELETE") {
    let kiff: KIFFHalt;
    try { kiff = await haltedInKIFF(token, agent, deps.fetch, deps.kiffBase); } catch (err) {
      if (err instanceof KIFFError && err.status === 401) throw err;
      throw new HTTPError(502, "KIFF didn't say whether it is resumed; try again");
    }
    if (kiff.state !== "running") throw new HTTPError(409, "resume it in KIFF first: the phone signs that with Face ID");
    const runtime = await agentsCall(() => deps.agents.resume(me.tenantID, taf?.id));
    // The halt's notification is replaced, quietly, so none says halted after a resume (kiff/tafs#236, item 21).
    await tellPhones(deps, me.tenantID, resumeNotification(taf));
    await logEvent(deps.events, me.tenantID, { kind: "resumed", ...(taf ? { taf: taf.id, name: taf.name } : {}) }, deps.now?.());
    await tellChange(me.tenantID, "resumed", taf?.id, deps);
    return { halt: haltView({ status: "fulfilled", value: kiff }, runtime) };
  }
  throw new HTTPError(404, "not found");
}

/**
 * A halt, told to the account's phones: one per taf or for all, a later one replacing it.
 * A taf's opens its own halt, not every taf's (kiff/tafs#236, item 21).
 */
function haltNotification(taf: Taf | undefined, halted: boolean): Notification {
  const name = taf?.name;
  const who = name ?? "All your tafs";
  return {
    kind: "alert",
    payload: {
      aps: {
        alert: { title: halted ? `${who} halted` : `Halting ${name ?? "all your tafs"}`, body: "Nothing new is sent until you resume in tafs." },
        "thread-id": "halt",
        category: "halt",
        ...apsFor(levelOf("halt", false)),
      },
      open: taf ? `halt:${taf.id}` : "halt",
    },
    collapseID: haltCollapseID(name),
  };
}

/** A resume, in place of its halt's notification: quiet, since nothing needs the owner. */
function resumeNotification(taf: Taf | undefined): Notification {
  return {
    kind: "alert",
    payload: {
      aps: {
        alert: { title: `${taf?.name ?? "All your tafs"} resumed`, body: "It works again as it did before the halt." },
        "thread-id": "halt",
        ...apsFor("quiet"),
      },
      open: taf ? `halt:${taf.id}` : "halt",
    },
    collapseID: haltCollapseID(taf?.name),
  };
}

const haltCollapseID = (name: string | undefined) => `halt-${name ?? "all"}`.slice(0, 64);

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
  return makeTaf(parseBody(event), me, deps);
}

/** POST /v1/tafs's work, for the person me: from their request, or from a result tafs cloud hands over for them. */
async function makeTaf(body: Record<string, unknown>, me: KIFFIdentity, deps: Deps): Promise<{ taf: unknown; session: string | null }> {
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
      if (refused(err, "schedule_exists") && await deps.agents.getSchedule(me.tenantID, tafID)) return;
      throw err;
    });
    await deps.tafs.put(me.tenantID, taf);
    await tellChange(me.tenantID, "taf", tafID, deps);
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
/**
 * Coach's tools as KIFF has them, for the phone to give in one confirmation:
 * `given` once every tool is connected here as the control server and Coach's Cards are issued;
 * `to_give` with what's left: `connect`, the batch the phone signs and sends as it is, with its
 * lines, and `card`, Coach's Cards' draft to sign and submit (that submit is the confirmation),
 * with KIFF's own words for each Card from its preview; `taken` when a vendor's tool holds
 * one of Coach's names; `unknown` when KIFF didn't answer. Read from KIFF each time, so every
 * phone of the account agrees.
 */
async function coachTools(event: HTTPEvent, setup: Taf, deps: Deps): Promise<Record<string, unknown>> {
  // Production must set publicURL (TAFS_PUBLIC_URL): the Host header is the caller's to send, and
  // the tools are connected at this address.
  const host = event.headers?.host ?? event.headers?.Host;
  const self = deps.publicURL ?? (host ? `https://${host}` : "");
  const mcp = `${self}/v1/mcp`;
  const token = bearer(event);
  try {
    const [there, active] = await Promise.all([
      gatewayConnections(token, deps.fetch, deps.kiffGateway),
      activeCards(token, deps.fetch, deps.kiffBase),
    ]);
    const plan = coachToolsPlan(there, mcp);
    if (plan.taken) return { state: "taken", taken: plan.taken };
    const missingCards = new Set(COACH_CARDS.filter((c) => !active.has(c.id)).map((c) => c.id));
    const issued = missingCards.size === 0;
    if (!plan.missing.length && issued) return { state: "given" };
    // Coach's Cards it doesn't hold in force, with KIFF's own words for them: never tafs cloud's copy.
    let card: Record<string, unknown> | undefined;
    if (!issued) {
      const body = coachCardsDraft(setup.kiffAgent, missingCards);
      card = { body, ...(await previewDraft(token, body, deps.fetch, deps.kiffBase)) };
    }
    return { state: "to_give", ...(plan.missing.length ? { connect: coachConnect(plan.missing, mcp, plan.replace) } : {}), ...(card ? { card } : {}) };
  } catch (err) {
    if (err instanceof KIFFError) {
      // Said in the logs, so "KIFF didn't answer" can be told from a refusal: its status and words.
      console.error(`coach tools: KIFF answered ${err.status}: ${err.message}`);
      return { state: "unknown" };
    }
    throw err;
  }
}

async function setupOf(me: KIFFIdentity, deps: Deps): Promise<Taf | undefined> {
  return (await deps.tafs.list(me.tenantID)).find((t) => t.job === SETUP_JOB && !t.removing);
}

/**
 * Starts the account's Setup, the agent that connects tools in the setup
 * chat (kiff/agents#91), once. Its key comes from a draft the phone
 * submitted with no Cards (no_tools): tafs cloud claims it
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
    if (refused(err, "draft_not_ready") && err.code) throw new HTTPError(409, WAITING_FOR_CONFIRMATION);
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
async function remove(taf: Taf, me: { tenantID: string }, deps: Deps) {
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
  await tellChange(me.tenantID, "taf", taf.id, deps);
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
  return eraseOwn(me.tenantID, deps);
}

/**
 * What tafs cloud and kiff/agents keep for the account, erased: every taf (Setup included) as
 * removing it does, then kiff/agents' account erase, then tafs cloud's own records. A step that
 * fails stops the rest, and the answer says which; asking again goes on from there.
 */
async function eraseOwn(tenant: string, deps: Deps): Promise<{ done: boolean; failed?: string; taf?: string; steps?: string[]; removed?: number }> {
  const tafs = await deps.tafs.list(tenant);
  for (const taf of tafs) {
    const r = await remove(taf, { tenantID: tenant }, deps);
    if (!r.done) return { done: false, failed: "tafs", taf: taf.id, steps: r.failed };
  }
  for (const r of await deps.removals.list(tenant)) await deps.removals.done(tenant, r.id);
  try {
    await deps.agents.eraseTenant(tenant);
  } catch (err) {
    console.error(`delete account: kiff/agents: ${(err as Error).message}`);
    return { done: false, failed: "agents" };
  }
  try {
    await deps.accountData!.erase(tenant);
  } catch (err) {
    console.error(`delete account: tafs cloud: ${(err as Error).message}`);
    return { done: false, failed: "tafs cloud" };
  }
  return { done: true, removed: tafs.length };
}

/** How a deletion stands, as the app shows it: the steps done, those that failed with why, and whether it goes on by itself. */
function deletionView(d: Deletion) {
  const failed = STEPS.filter((s) => !d.done.includes(s)).map((step) => ({ step, why: d.failed?.[step] ?? "not done yet" }));
  return { done: d.done, failed, pending: failed.length > 0 };
}

/**
 * POST /v1/account/delete {intent, signature}: the account deleted from one signed intent. Accepted
 * by KIFF within the intent's 10 minutes, or nothing is deleted; then finished in order: tafs
 * cloud's own, planque's (which accepts on KIFF's word, then erases), KIFF's last. What doesn't finish now, the sweep finishes. An intent
 * already accepted for the account goes on with the deletion kept, never a second one.
 */
async function deleteByIntent(me: KIFFIdentity, body: Record<string, unknown>, token: string, deps: Deps) {
  if (!me.capabilities.includes("account.delete")) throw new HTTPError(403, "your role on this KIFF account cannot delete it");
  if (!deps.accountData || !deps.deletions || !deps.deletionPeers) throw new HTTPError(503, "accounts can't be deleted here");
  const intent = str(body.intent), signature = str(body.signature);
  if (!intent || !signature) throw new HTTPError(400, "send the signed intent: {intent, signature}");
  const now = deps.now?.() ?? new Date();
  const kept = await deps.deletions.get(me.tenantID);
  if (kept) return deletionView(kept.accepting ? kept : await finishDeletion(kept, deps, now));
  try {
    checkIntent(parseIntent(intent), me.tenantID, now);
  } catch (err) {
    if (err instanceof IntentError) throw new HTTPError(err.status, err.message);
    throw err;
  }
  const refused = (step: "planque" | "kiff", err: unknown) => {
    const status = err instanceof PeerError && err.status >= 400 && err.status < 500 ? err.status : 502;
    return new HTTPError(status, `${step === "kiff" ? "KIFF" : "planque"} didn't accept the deletion: ${(err as Error).message}. Nothing was deleted`);
  };
  // KIFF accepts first, with the person's session: only it holds the approval key that signed.
  // planque accepts once KIFF has, taking KIFF's word for it, as part of its own finish step.
  // Kept before KIFF's accept, which signs the phone out: if KIFF's answer is lost, the sweep asks
  // KIFF whether it accepted, and finishes or drops it.
  const d: Deletion = { tenant: me.tenantID, intent, signature, accepted: now.toISOString(), done: [], accepting: true };
  await deps.deletions.put(d);
  try {
    await deps.deletionPeers.kiff.accept(intent, signature, token);
  } catch (err) {
    if (err instanceof PeerError && err.status >= 400 && err.status < 500) {
      await deps.deletions.finished(me.tenantID, now.toISOString(), "dropped");
      throw refused("kiff", err);
    }
    // No answer from KIFF: kept, for the sweep to settle.
    console.error(`delete account: KIFF's accept: ${(err as Error).message}`);
    return deletionView({ ...d, failed: { kiff: "KIFF didn't answer yet" } });
  }
  delete d.accepting;
  await deps.deletions.put(d);
  // Nothing of the account runs from now: no scheduled run, no door's, until it's erased.
  await deps.agents.halt(me.tenantID).catch((err) => console.error(`delete account: halt: ${(err as Error).message}`));
  return deletionView(await finishDeletion(d, deps, now));
}

/**
 * Finishes an accepted deletion as far as it goes now, in order: tafs cloud's own, planque's, then
 * KIFF's. Each step is the same when asked again; one that fails stops the rest, kept for the sweep.
 */
export async function finishDeletion(d: Deletion, deps: Deps, now: Date): Promise<Deletion> {
  const out: Deletion = { ...d, done: [...d.done], failed: {}, tried: now.toISOString() };
  for (const step of STEPS) {
    if (out.done.includes(step)) continue;
    let why: string | undefined;
    try {
      if (step === "tafs") {
        const r = await eraseOwn(d.tenant, deps);
        if (!r.done) why = `${r.failed} didn't finish erasing`;
      } else {
        // planque's accept first: the same intent again answers the same.
        if (step === "planque") await deps.deletionPeers!.planque.accept(d.intent, d.signature);
        await deps.deletionPeers![step].finish(d.intent, d.signature);
      }
    } catch (err) {
      why = (err as Error).message;
    }
    if (why) {
      out.failed![step] = why;
      out.tries = (d.tries ?? 0) + 1;
      await deps.deletions!.put(out);
      return out;
    }
    out.done.push(step);
    await deps.deletions!.put(out);
  }
  delete out.failed;
  await deps.deletions!.finished(d.tenant, now.toISOString(), "deleted");
  return out;
}

/** The sweep: every deletion not finished, tried again once its backoff has passed. */
export async function sweepDeletions(deps: Deps): Promise<Array<{ tenant: string; done: Step[]; pending: boolean }>> {
  if (!deps.deletions || !deps.deletionPeers) return [];
  const now = deps.now?.() ?? new Date();
  const out: Array<{ tenant: string; done: Step[]; pending: boolean }> = [];
  for (let d of await deps.deletions.pending()) {
    if (d.tried && now.getTime() - Date.parse(d.tried) < backoffMs(d.tries ?? 0)) continue;
    if (d.accepting) {
      // KIFF's answer to its accept was lost: KIFF says whether it took it.
      let accepted: boolean;
      try { accepted = await deps.deletionPeers.kiff.accepted(d.intent, d.signature); } catch (err) {
        await deps.deletions.put({ ...d, tried: now.toISOString(), tries: (d.tries ?? 0) + 1, failed: { kiff: (err as Error).message } });
        continue;
      }
      if (!accepted) { await deps.deletions.finished(d.tenant, now.toISOString(), "dropped"); continue; }
      d = { ...d };
      delete d.accepting;
      await deps.agents.halt(d.tenant).catch(() => undefined);
    }
    const r = await finishDeletion(d, deps, now);
    out.push({ tenant: d.tenant, done: r.done, pending: r.done.length < STEPS.length });
  }
  return out;
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
  /**
   * A card's answer, once kiff/agents recorded it: what the owner chose (`answered`), that
   * they wrote a message instead (`wrote`), or that it stopped waiting (`no_answer`).
   */
  answer?: CardAnswer;
}

export interface CardAnswer {
  state: "answered" | "wrote" | "no_answer";
  words: string;
  at: string;
  /** Who answered in the owner's place, when it wasn't them: tafs cloud, for a result. */
  by?: string;
  /** A result's outcome, what came of handing the owner over: the app draws it, nothing more. */
  outcome?: ResultOutcome;
}

export interface ThreadMessage {
  /**
   * The message's stable id: a run's first owner message is `<run>.in`, which is what
   * `POST …/messages` answers for the message it sent; every other is `<run>.<seq>`.
   */
  id: string;
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
  /** The files the person attached to their message (kiff/tafs#209): opened through the attachments route. */
  attachments?: AttachmentRef[];
  /** The card the owner's message answered, by id: they wrote while it waited, and their words were its answer. */
  answers?: string;
  /**
   * A taf's message that called tools first (kiff/tafs#285): how long it worked on it, from the
   * message before, and the tools it called, by name, in order, with the ones that failed. From
   * the run's usage records, which keep each call's tool and time, never its input or result.
   * Each step's `words` are what the app shows for it (KIFF's title for a tool through KIFF),
   * never the tool's name; a step nothing names has none.
   */
  work?: { ms: number; steps: Array<{ tool: string; words?: string; error?: true; not_made?: true }> };
}

/**
 * What the taf's calls through KIFF acted on (kiff/tafs#167): for each, KIFF's key
 * (its activity's entity_id) and the value of the argument its Card scopes on, as
 * the run kept them (`call_ref`, 30 days). The app names a Record line's payment
 * from it, and checks it against KIFF's signed hash. Nothing else of a call is kept.
 */
async function callRefs(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<Array<{ invocation: string; value: string; at: string }>> {
  // The person's own sessions only (and the taf's jobs, which are everyone's who use it).
  const sessions = await agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned, ownerKey(me)));
  const read = await Promise.all(sessions.map((s) => deps.agents.session(me.tenantID, s.id, ownerKey(me)).catch(() => undefined)));
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

/** The files an owner's message names, as kiff/agents recorded them: never their bytes. */
function attachmentsOf(body: unknown): AttachmentRef[] {
  const raw = (body as { attachments?: unknown } | undefined)?.attachments;
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((a) => {
    const x = a as Partial<AttachmentRef>;
    return typeof x?.id === "string" && typeof x.name === "string" && typeof x.media_type === "string" && typeof x.kind === "string"
      ? [{ id: x.id, kind: x.kind, media_type: x.media_type, name: x.name, size: typeof x.size === "number" ? x.size : 0 }] : [];
  });
}

/**
 * The taf's thread, oldest first: its latest job runs and its latest chats,
 * from kiff/agents' sessions (newest first, a bounded number), with what each
 * kept: the owner's messages and the taf's. A run's first owner message is
 * the job's instruction, shown as such.
 */
/** KIFF's titles for the taf's tools, as the person reads its Cards: what its steps say (stepWords). */
function titlesOf(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<Map<string, string>> {
  return toolTitles(bearer(event), me.tenantID, me.subject, taf.kiffAgent, deps.fetch, deps.kiffBase);
}

async function conversation(me: KIFFIdentity, taf: Taf, deps: Deps, only?: Session, doors = false, titles: ToolTitles = new Map()): Promise<{ messages: ThreadMessage[]; components: ThreadComponent[]; held: ThreadHeld[]; sessions: Session[] }> {
  if (!only) await moveArchived(me, taf, deps);
  // The person's own sessions (with the job's runs and those from before owners), read by
  // owner so other people's chats never use up the scan; and, for `doors`, the door senders'
  // chats, which only the people who manage the taf see. A chat the owner archived
  // is hidden from the thread too (kiff/tafs#82); one they open is shown.
  const [own, all] = only ? [[only], []] : await Promise.all([
    agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned, ownerKey(me))),
    doors ? agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned)) : Promise.resolve([]),
  ]);
  const chats = [...own.filter((s) => s.kind === "chat"), ...all.filter((s) => s.kind === "chat" && s.owner?.startsWith("door:"))]
    .filter((s) => !s.archived).sort((a, b) => b.updated.localeCompare(a.updated));
  const picked = only ? [only] : [
    ...own.filter((s) => s.kind === "run").slice(0, CHAT.runsInThread),
    ...chats.slice(0, CHAT.chatsInThread),
  ];
  // Read as the person: their own and the jobs'. A door's chat is read as its own owner says.
  const read = await Promise.all(picked.map((s) => deps.agents.session(me.tenantID, s.id, readerOf(me, s)).catch(() => undefined)));
  const messages: ThreadMessage[] = [];
  const components: ThreadComponent[] = [];
  const held: ThreadHeld[] = [];
  /** Runs a door's message started, by run: their answers go back through it. */
  const doorRuns = new Map<string, string>();
  const sent = new Map((deps.doors ? await sentOf(deps.doors.store, me.tenantID, taf.id).catch(() => []) : []).map((x) => [x.run, x]));
  for (const r of read) {
    if (!r) continue;
    const seenRun = new Set<string>();
    // Each run's work, shown once, on its first message (the 845 test): when the run started,
    // the tools it called, in order, and when it last wrote. The app's live block sits there too,
    // under the owner's message, so it folds in place when the run ends. A run that paused on a
    // held call and went on once the owner answered has its work in parts, each on the part's
    // first message: the time it waited for the owner isn't work, and what it did before the
    // pause isn't shown again after it (the 859 test).
    type Part = { start: number; before: number; seen: number; tools: Array<{ tool: string; words?: string; error?: true; not_made?: true }>; first?: ThreadMessage; last?: number };
    const runs = new Map<string, { parts: Part[]; turns: number }>();
    const runOf = (id: string, at: number) => {
      let w = runs.get(id);
      if (!w) runs.set(id, (w = { parts: [{ start: at, before: 0, seen: at, tools: [] }], turns: 0 }));
      return w;
    };
    for (const e of r.events) {
      if (e.kind === "usage") {
        const at = Date.parse(iso(e.time));
        const w = runOf(e.run, at);
        const turn = turnTiming(e.body);
        if (turn) {
          if (startsPart(turn, w.turns, at)) {
            const part = w.parts.at(-1)!;
            const began = at - turn.ms;
            // A part that wrote nothing goes on in the next, less the wait between them.
            if (part.first) w.parts.push({ start: began, before: 0, seen: began, tools: [] });
            else Object.assign(part, { before: part.before + Math.max(0, part.seen - part.start), start: began });
          }
          w.turns++;
        }
        const part = w.parts.at(-1)!;
        if (!Number.isNaN(at)) part.seen = Math.max(part.seen, at);
        // A call refused back unmade (an act beside a read) isn't failed work: it shows as not made.
        for (const t of turn?.waited ?? []) {
          const words = stepWords(t.tool, titles);
          part.tools.push({ tool: t.tool, ...(words ? { words } : {}), ...(t.notMade ? { not_made: true as const } : t.error ? { error: true as const } : {}) });
        }
        continue;
      }
      if (e.kind === "tool_result") {
        // A call KIFF held, where it was held: the app reads the call itself from KIFF.
        const b = (e.body ?? {}) as { state?: unknown; hold?: { exception?: unknown }; invocation?: unknown };
        if (b.state === "held" && typeof b.hold?.exception === "string" && b.hold.exception) {
          held.push({ at: iso(e.time), session: r.session.id, run: e.run, exception: b.hold.exception, ...(typeof b.invocation === "string" ? { invocation: b.invocation } : {}) });
        }
        continue;
      }
      if (e.kind === "component") {
        components.push({ at: iso(e.time), session: r.session.id, run: e.run, seq: e.seq, component: withLine(e.body) });
        continue;
      }
      if (e.kind === "answered") {
        // The card's answer as kiff/agents recorded it: on the run's latest card with its id.
        const b = (e.body ?? {}) as { id?: unknown; type?: unknown; answer?: unknown; words?: unknown; wrote?: unknown; unanswered?: unknown; answered_by?: unknown };
        const card = components.findLast((c) => c.run === e.run && (c.component as { id?: unknown })?.id === b.id);
        if (card) card.answer = cardAnswer(b, iso(e.time));
        // Words the owner wrote while the card waited answered it: they show as their message, as sent.
        if (typeof b.wrote === "string" && b.wrote.trim()) {
          messages.push({ id: `${e.run}.${e.seq}`, at: iso(e.time), from: "owner", kind: "message", text: b.wrote.trim(), session: r.session.id, answers: str(b.id) });
        }
        continue;
      }
      if (e.kind !== "owner_message" && e.kind !== "agent_message" && e.kind !== "report") continue;
      const text = typeof (e.body as { text?: unknown })?.text === "string" ? ((e.body as { text: string }).text).trim() : "";
      const attached = e.kind === "owner_message" ? attachmentsOf(e.body) : [];
      if (!text && !attached.length) continue;
      const at = Date.parse(iso(e.time));
      const run = runOf(e.run, at);
      if (e.kind === "owner_message") {
        // Each job run opens with the job's instruction: not something the owner typed.
        const first = !seenRun.has(e.run);
        const kind = r.session.kind === "run" && first ? "kickoff" : "message";
        seenRun.add(e.run);
        // A message that came through a door is shown as written, marked with its door and sender.
        const door = parseDoorMessage(text);
        if (door) doorRuns.set(e.run, door.app);
        messages.push({
          id: first ? inputID(e.run) : `${e.run}.${e.seq}`,
          at: iso(e.time), from: "owner", kind, text: door ? door.text.trim() : ownerWords(text), session: r.session.id,
          ...(door ? { door: { app: door.app, from: door.from, ...(door.subject ? { subject: door.subject } : {}) } } : {}),
          ...(attached.length ? { attachments: attached } : {}),
        });
      } else {
        const app = doorRuns.get(e.run);
        const reply = app ? sent.get(e.run) : undefined;
        const said: ThreadMessage = {
          id: `${e.run}.${e.seq}`, at: iso(e.time), from: "taf", kind: "message", text, session: r.session.id,
          ...(app ? { door: { app, reply: reply?.status ?? "pending", ...(reply?.why ? { why: reply.why } : {}) } } : {}),
        };
        messages.push(said);
        const part = run.parts.at(-1)!;
        if (!part.first) part.first = said;
        if (!Number.isNaN(at)) part.last = at;
      }
    }
    for (const w of runs.values()) {
      for (const p of w.parts) {
        if (p.first && p.tools.length > 0 && p.last !== undefined && !Number.isNaN(p.start)) {
          p.first.work = { ms: p.before + Math.max(0, p.last - p.start), steps: p.tools };
        }
      }
    }
  }
  messages.sort((a, b) => a.at.localeCompare(b.at));
  components.sort((a, b) => a.at.localeCompare(b.at) || a.seq - b.seq);
  held.sort((a, b) => a.at.localeCompare(b.at));
  return { messages: messages.slice(-CHAT.maxThread), components: components.slice(-CHAT.maxThread), held: held.slice(-CHAT.maxThread), sessions: picked };
}

/** One entry of the thread in order: a message, or a card at its place. */
export type ThreadItem = { type: "message"; message: ThreadMessage } | { type: "card"; card: ThreadComponent } | { type: "held"; held: ThreadHeld };

/**
 * A call KIFF held for the owner, at its place in the thread: only KIFF's keys for
 * it. The app reads the call itself, its words and its answer, from KIFF with the person's session.
 */
export interface ThreadHeld {
  at: string;
  session: string;
  run: string;
  exception: string;
  invocation?: string;
}

/**
 * The thread as one ordered list, so neither app places or folds anything: messages and cards by
 * time, a card shown again (the same id in the same chat) replacing its earlier version in place, at
 * the time it first appeared.
 */
export function threadItems(messages: ThreadMessage[], components: ThreadComponent[], held: ThreadHeld[] = []): ThreadItem[] {
  const cards = new Map<string, ThreadComponent & { first: string }>();
  for (const c of components) {
    const id = String((c.component as { id?: unknown })?.id ?? "");
    // The same card shown again in the chat replaces it, in a later run of the chat too.
    const key = `${c.session}#${id}`;
    const seen = cards.get(key);
    cards.set(key, { ...c, first: seen?.first ?? c.at });
  }
  const items: Array<{ at: string; order: number; item: ThreadItem }> = [];
  messages.forEach((m, i) => items.push({ at: m.at, order: i, item: { type: "message", message: m } }));
  [...cards.values()].forEach(({ first, ...c }, i) => items.push({ at: first, order: messages.length + i, item: { type: "card", card: c } }));
  held.forEach((h, i) => items.push({ at: h.at, order: messages.length + cards.size + i, item: { type: "held", held: h } }));
  return items.sort((a, b) => a.at.localeCompare(b.at) || a.order - b.order).map((x) => x.item);
}

/**
 * A plan (a `steps` component) with its one-line form, `line`: "Step 3 of 6 · Your rules", the step
 * at work or the first still to do, or "Done · 6 of 6". So an app shows the plan in one line, with no
 * counting of its own. Any other component is as it came.
 */
export function withLine(component: unknown): unknown {
  const c = component as { type?: unknown; steps?: unknown } | null | undefined;
  if (!c || c.type !== "steps" || !Array.isArray(c.steps) || !c.steps.length) return component;
  const steps = c.steps as Array<{ label?: unknown; status?: unknown }>;
  const n = steps.length;
  const at = steps.findIndex((s) => s.status === "active");
  const next = at >= 0 ? at : steps.findIndex((s) => s.status !== "done" && s.status !== "skipped");
  if (next < 0) return { ...c, line: `Done · ${n} of ${n}` };
  const label = typeof steps[next]!.label === "string" ? (steps[next]!.label as string).trim() : "";
  return { ...c, line: `Step ${next + 1} of ${n}${label ? ` · ${label}` : ""}` };
}

/** The id of a run's first owner message: what `POST …/messages` answers, so the app matches its send to the thread. */
function inputID(run: string): string {
  return `${run}.in`;
}

/** An `answered` event's body as the app shows it. */
function cardAnswer(b: { type?: unknown; answer?: unknown; words?: unknown; wrote?: unknown; unanswered?: unknown; answered_by?: unknown }, at: string): CardAnswer {
  if (b.unanswered === true) return { state: "no_answer", words: "", at };
  if (typeof b.wrote === "string" && b.wrote) return { state: "wrote", words: b.wrote, at };
  const out: CardAnswer = { state: "answered", words: typeof b.words === "string" ? b.words : "", at };
  if (typeof b.answered_by === "string" && b.answered_by) out.by = b.answered_by;
  const o = b.answer as { taf_id?: unknown; version?: unknown; failed?: unknown } | null | undefined;
  if (b.type === "result" && o && typeof o === "object") {
    if (typeof o.taf_id === "string") out.outcome = { taf_id: o.taf_id };
    else if (typeof o.version === "number") out.outcome = { version: o.version };
    else if (typeof o.failed === "string") out.outcome = { failed: o.failed };
  }
  return out;
}

/**
 * The taf's latest session's latest run. `working`: whether the taf is at work
 * in it now, so the app shows it thinking and offers Stop (kiff/tafs#58): the
 * run is pending or running. A run waiting on the owner isn't: it waits for
 * them. `steering`: what the owner wrote to it that it hasn't read (#64).
 * Unknown (kiff/agents not answering) reads as neither.
 */
async function latestRun(me: KIFFIdentity, taf: Taf, picked: Session[], deps: Deps): Promise<{ working: { session: string; run: string } | null; steering: Steering | null; capped?: Capped }> {
  const none = { working: null, steering: null };
  const latest = [...picked].sort((a, b) => b.updated.localeCompare(a.updated))[0];
  if (!latest) return none;
  try {
    const read = await deps.agents.session(me.tenantID, latest.id, readerOf(me, latest));
    const run = read.events.at(-1)?.run;
    if (!run) return none;
    const r = await deps.agents.getRun(me.tenantID, taf.id, run);
    // Times as the thread's are (milliseconds): kiff/agents writes nanoseconds, which the app can't read.
    const untaken = (r.steering ?? []).filter((m) => !m.taken).map(({ text, at }) => ({ text, at: iso(at) }));
    return {
      working: r.status === "PENDING" || r.status === "RUNNING" ? { session: latest.id, run } : null,
      steering: untaken.length ? { run, status: r.status, untaken } : null,
      ...(r.stop === "cost_cap" || r.stop === "turn_cap" ? { capped: { run, stop: r.stop } } : {}),
    };
  } catch {
    return none;
  }
}

/**
 * The latest run stopped at a cap of its own, with no words for it: the app says
 * why, in the owner's language, and who can raise the limit.
 */
interface Capped {
  run: string;
  stop: "cost_cap" | "turn_cap";
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
  const owner = chatText(event, files(event).length > 0);
  const answered = await oldFormAnswer(event, me, taf, owner, deps);
  if (answered) return { session: answered.session, new_session: false, run: answered.id };
  const lines = await takeCoachLines(me, taf, deps);
  try {
    return await startMessage(event, me, taf, lines.text(owner), deps);
  } catch (err) {
    // The message didn't start: Coach reads its lines with the next one.
    await lines.putBack();
    throw err;
  }
}

/** The card types the person answers, as kiff/agents takes them. */
const ANSWERABLE = ["confirm", "questions", "approval", "sign_in"];

/**
 * POST /v1/tafs/{id}/components/{component}/answer {answer, session?}: the person's answer
 * to a card of theirs that waits for it, as data. kiff/agents checks it against the card
 * and the run goes on with it. 404 when no card of theirs with that id was shown; 409 when
 * it doesn't wait any more (answered, stopped waiting, or the run went on); 400 for an
 * answer the card doesn't take.
 */
async function answerRoute(event: HTTPEvent, me: KIFFIdentity, taf: Taf, component: string, deps: Deps): Promise<unknown> {
  const body = parseBody(event);
  if (!("answer" in body)) throw new HTTPError(400, "send the card's answer");
  // Only tafs cloud answers in its own name (a result's hand-over), never a person's request.
  if ("answered_by" in body) throw new HTTPError(400, "a person's answer is their own: answered_by is tafs cloud's alone");
  const card = await shownCard(me, taf, component, undefined, str(body.session).trim(), deps);
  if (!card) throw new HTTPError(404, "no card of yours by that id");
  try {
    const run = await deps.agents.answerCard(me.tenantID, taf.id, card.run, component, body.answer, member(me));
    return { session: card.session, run: run.id, status: run.status };
  } catch (err) {
    if (refused(err, "card_not_waiting")) throw new HTTPError(409, "the card doesn't wait for an answer any more: read the conversation");
    if (err instanceof AgentsError && err.status === 409) throw new HTTPError(409, err.message);
    if (err instanceof AgentsError && err.status === 404) throw new HTTPError(404, "no card of yours by that id");
    throw agentsFailure(err);
  }
}

/**
 * The card a person's chat showed with this id (and type, when given): the latest, in the
 * chat named, else in the chats the thread shows. Only their own chats are read.
 */
async function shownCard(me: KIFFIdentity, taf: Taf, id: string, type: string | undefined, session: string, deps: Deps): Promise<{ session: string; run: string } | undefined> {
  if (!id) return undefined;
  const picked = session ? [await ownChat(me, taf, session, deps)] :
    (await agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned, ownerKey(me))))
      .filter((s) => s.kind === "chat" && !s.archived).sort((a, b) => b.updated.localeCompare(a.updated)).slice(0, CHAT.chatsInThread);
  let found: { session: string; run: string; time: string } | undefined;
  for (const s of picked) {
    const read = await deps.agents.session(me.tenantID, s.id, ownerKey(me)).catch(() => undefined);
    for (const e of read?.events ?? []) {
      const c = (e.kind === "component" ? e.body : undefined) as { id?: unknown; type?: unknown } | undefined;
      if (c?.id !== id || (type && c.type !== type)) continue;
      if (!found || e.time >= found.time) found = { session: s.id, run: e.run, time: e.time };
    }
  }
  return found && { session: found.session, run: found.run };
}

/**
 * The app's answer to a card in the form it has sent until now, "[<type> <id>] <answer>"
 * (only the person's own message, never a door's), as the answer kiff/agents takes:
 * confirm → its option's value, questions → the answers' JSON, approval → approved,
 * declined or failed: <why>, sign_in → signed in, not now or failed: <why>. Undefined
 * for any other text.
 */
export function oldFormCard(text: string): { type: string; id: string; answer: unknown } | undefined {
  const m = /^\[([a-z_]+) ([^\]\s]+)\] ([\s\S]+)$/.exec(text);
  if (!m || !ANSWERABLE.includes(m[1]!)) return undefined;
  const [, type, id, rest] = m as unknown as [string, string, string, string];
  const failed = rest.startsWith("failed:") ? rest : undefined;
  switch (type) {
    case "confirm":
      return { type, id, answer: rest };
    case "questions":
      try {
        const answer: unknown = JSON.parse(rest);
        return answer && typeof answer === "object" && !Array.isArray(answer) ? { type, id, answer } : undefined;
      } catch {
        return undefined;
      }
    case "approval": {
      const answer = rest === "approved" || rest.startsWith("approved,") ? "approved" : rest === "declined" ? "declined" : failed;
      return answer ? { type, id, answer } : undefined;
    }
    default: {
      const answer = rest === "signed in" || rest === "not now" ? rest : failed;
      return answer ? { type, id, answer } : undefined;
    }
  }
}

/**
 * The person's message, when it is the app's old form of an answer to a card of theirs
 * that waits: answered as data (kiff/agents' answer), and the run it waits in. Undefined
 * when it isn't one, names no such card, or kiff/agents doesn't take it (400, 404, 409):
 * then it goes as a message, as before. A message with files or into a new chat never is.
 */
async function oldFormAnswer(event: HTTPEvent, me: KIFFIdentity, taf: Taf, text: string, deps: Deps): Promise<Run | undefined> {
  const body = parseBody(event);
  if (body.new_chat === true || files(event).length) return undefined;
  const card = oldFormCard(text);
  if (!card) return undefined;
  const shown = await shownCard(me, taf, card.id, card.type, str(body.session).trim(), deps);
  if (!shown) return undefined;
  try {
    return await deps.agents.answerCard(me.tenantID, taf.id, shown.run, card.id, card.answer, member(me));
  } catch (err) {
    if (err instanceof AgentsError && [400, 404, 409].includes(err.status)) return undefined;
    throw agentsFailure(err);
  }
}

async function startMessage(event: HTTPEvent, me: KIFFIdentity, taf: Taf, text: string, deps: Deps): Promise<unknown> {
  const [at, ai] = await Promise.all([chatAt(event, me, taf, deps), aiBound(event, deps)]);
  const attached = files(event);
  if (attached.length) at.attachments = attached;
  if (deps.doors) await keepBound(deps.doors, me.tenantID, ai, deps.now?.() ?? new Date());
  const run = await agentsCall(() => deps.agents.startRun(me.tenantID, taf.id, text, at, ai));
  return { session: run.session, new_session: newSession(at, run), run: run.id, message: { id: inputID(run.id), at: run.created } };
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
    await needTaf(event, me, taf, deps);
    return createVersion(event, me, taf, deps);
  }
  if (action === "switching" && parts.length === 4 && method === "DELETE") {
    await needTaf(event, me, taf, deps);
    return cancelSwitch(event, me, taf, deps);
  }
  if (action === "trials" && parts.length === 5 && method === "GET") {
    // A trial is tried by the people who manage the taf; its chat and results are theirs.
    await needTaf(event, me, taf, deps);
    return agentsCall(() => deps.agents.trial(me.tenantID, taf.id, rawN ?? ""));
  }
  const n = Number(rawN);
  if (action !== "versions" || parts.length !== 6 || method !== "POST" || !Number.isInteger(n) || n < 1) throw new HTTPError(404, "not found");
  await needTaf(event, me, taf, deps);
  const version = (await agentsCall(() => deps.agents.versions(me.tenantID, taf.id))).versions.find((v) => v.n === n);
  if (!version) throw new HTTPError(404, "no such version");
  if (sub === "trial") {
    // What a trial works on: the owner's words, else what each scheduled run asks of it.
    const asked = str(parseBody(event).input).trim();
    const input = asked || JOBS.find((j) => j.id === taf.job)?.kickoff || "";
    if (!input || input.length > CHAT.maxText) throw new HTTPError(400, `write 1 to ${CHAT.maxText} characters for the trial`);
    return { run: await agentsCall(() => deps.agents.startTrial(me.tenantID, taf.id, n, input)) };
  }
  if (sub === "try") {
    if (version.live) throw new HTTPError(409, "that version is live already");
    return { draft: await trialDraftFor(event, me, taf, version, deps) };
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

/**
 * Trying a version (kiff/tafs#173): the owner chats with it under a trial Card
 * of its own, while the live version keeps its schedule, Card and budget. The
 * phone confirms the version's draft as a trial in KIFF, signed, as for any
 * draft; tafs cloud then opens the trial in kiff/agents. It ends on Install
 * (the phone confirms the same draft live, then the version goes live here),
 * on Go back (KIFF revokes the trial Cards), or unused for 7 days (kiff/agents'
 * sweep). Its chat is then deleted, and the version keeps one line with KIFF's
 * counts.
 */
async function trialRoute(event: HTTPEvent, method: string, parts: string[], me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  if (taf.job === SETUP_JOB) throw new HTTPError(404, "not found");
  const sub = parts.length === 5 ? parts[4] : parts.length === 4 ? "" : undefined;
  if (sub === "" && method === "GET") return { trial: await trialFor(me, taf, await agentsCall(() => deps.agents.openedTrial(me.tenantID, taf.id)), deps) };
  if (sub === undefined) throw new HTTPError(404, "not found");
  if (sub === "conversation" && method === "GET") {
    await needTaf(event, me, taf, deps);
    const open = await openTrialOf(me, taf, deps);
    if (!open.session) return { messages: [], components: [], working: false, steering: [] };
    const [only, titles] = await Promise.all([ownChat(me, taf, open.session, deps, "trial"), titlesOf(event, me, taf, deps)]);
    const { messages, components, held, sessions } = await conversation(me, taf, deps, only, false, titles);
    const latest = await latestRun(me, taf, sessions, deps);
    return { messages, components, items: threadItems(messages, components, held), working: latest.working, steering: latest.steering, ...(latest.capped ? { capped: latest.capped } : {}) };
  }
  await needTaf(event, me, taf, deps);
  const token = bearer(event);
  if (sub === "" && method === "PUT") {
    const body = parseBody(event);
    const n = Number(body.n);
    const draft = str(body.draft);
    if (!Number.isInteger(n) || n < 1 || !draft) throw new HTTPError(400, "a trial needs its version (n) and its Card's draft");
    const version = (await agentsCall(() => deps.agents.versions(me.tenantID, taf.id))).versions.find((v) => v.n === n);
    if (!version) throw new HTTPError(404, "no such version");
    if (version.live) throw new HTTPError(409, "that version is live already");
    // Only this taf's own draft, and only once KIFF has it trying: its calls then go under its trial Cards.
    const d = await draftInfo(token, draft, deps.fetch, deps.kiffBase);
    if (!d || d.agent !== taf.kiffAgent) throw new HTTPError(404, "no such draft for this taf");
    if (d.status !== "trying") throw new HTTPError(409, "start trying this version's Card in KIFF first");
    return { trial: await trialFor(me, taf, await agentsCall(() => deps.agents.openTrial(me.tenantID, taf.id, n, draft, member(me))), deps) };
  }
  if (sub === "messages" && method === "POST") {
    const text = chatText(event);
    const { run, trial } = await agentsCall(() => deps.agents.trialMessage(me.tenantID, taf.id, text, member(me)));
    return { session: run.session, run: run.id, trial };
  }
  if (sub === "install" && method === "POST") {
    const open = await openTrialOf(me, taf, deps);
    // The phone confirmed the trial's draft live in KIFF: the version goes live
    // under it, and KIFF ended the trial with it.
    if (!(await draftConfirmed(token, open.draft, deps.fetch, deps.kiffBase))) throw new HTTPError(409, "confirm this version's Card first");
    // A retry after the version went live but the trial didn't end only ends the trial.
    const live = (await agentsCall(() => deps.agents.versions(me.tenantID, taf.id))).versions.find((v) => v.live)?.n;
    if (live !== open.n) await goLive(event, me, taf, open.n, deps);
    const counts = (await trialOf(token, open.draft, deps.fetch, deps.kiffBase)) ?? { calls: 0, held: 0, refused: 0 };
    const ended = await agentsCall(() => deps.agents.endTrial(me.tenantID, taf.id, "installed", counts));
    return { live: open.n, ended };
  }
  if (sub === "" && method === "DELETE") {
    const open = await openTrialOf(me, taf, deps);
    const counts = await endTrialIn(token, open.draft, deps.fetch, deps.kiffBase).catch((err: unknown) => {
      if (err instanceof KIFFError && err.status === 409) throw new HTTPError(409, "KIFF couldn't end this trial's Card yet; try again");
      throw err;
    });
    return { ended: await agentsCall(() => deps.agents.endTrial(me.tenantID, taf.id, "back", counts)) };
  }
  throw new HTTPError(404, "not found");
}

/** The taf's open trial, or 404. */
/** How long a trial lasts unused before kiff/agents ends it (kiff/tafs#173). */
const TRIAL_IDLE_MS = 7 * 24 * 3600_000;

/**
 * A trial as the app shows it (kiff/tafs#247): kiff/agents' trial, with the
 * words that guide the owner through it, so the app renders them as sent:
 * its badge (label) and title, what the version changes, the task to try it
 * with, and when it ends unused.
 */
export function trialView(t: Trial, taf: Taf, note: string | undefined): Trial & { label: string; title: string; changed?: string; try?: string; ends: string; idle_days: number } {
  const tryWith = taf.tries?.[String(t.n)];
  // kiff/agents' own, when it says them; from before it did, the week it kept then.
  const ends = t.ends ?? new Date(Date.parse(t.used) + TRIAL_IDLE_MS).toISOString();
  const idle = t.idle_days ?? TRIAL_IDLE_MS / (24 * 3600_000);
  return { ...t, label: "Trial", title: `Trying version ${t.n}`, ...(note ? { changed: note } : {}), ...(tryWith ? { try: tryWith } : {}), ends, idle_days: idle };
}

/** The trial of a taf as the app shows it, or null: one read of its versions for the note. */
async function trialFor(me: KIFFIdentity, taf: Taf, t: Trial | undefined, deps: Deps) {
  if (!t) return null;
  const note = (await deps.agents.versions(me.tenantID, taf.id).catch(() => undefined))?.versions.find((v) => v.n === t.n)?.note;
  return trialView(t, taf, note);
}

async function openTrialOf(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<Trial> {
  const open = await agentsCall(() => deps.agents.openedTrial(me.tenantID, taf.id));
  if (!open) throw new HTTPError(404, "this taf has no open trial");
  return open;
}

/**
 * The KIFF draft the phone confirms as version n's trial Card: the draft a
 * switch to it would wait for (draftFor), or, for a version that runs under
 * the Cards in force, those Cards drafted as they stand, so the trial still
 * gets Cards of its own and the live ones keep their counters.
 */
async function trialDraftFor(event: HTTPEvent, me: KIFFIdentity, taf: Taf, version: AgentVersion, deps: Deps): Promise<string> {
  const drafted = await draftFor(event, me, taf, version, false, deps);
  if (drafted) return drafted;
  const cards = await liveCards(event, taf, deps);
  if (!cards.length) throw new HTTPError(409, "this taf has no Cards to try a version under");
  try {
    return await restoreDraft(bearer(event), taf.kiffAgent, cards, `${taf.name}, trying version ${version.n}`, deps.fetch, deps.kiffBase);
  } catch (err) {
    if (err instanceof KIFFError && (err.status === 404 || err.status === 409)) throw new HTTPError(409, `KIFF can't prepare this version's Card: ${err.message}`);
    throw err;
  }
}

/** Most characters a version's note and its instructions from the owner may have. */
const VERSION_NOTE = 2000;
/** The longest task to try a version with. */
const VERSION_TRY = 500;
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
  // What to try it with (kiff/tafs#247): kept for its trial.
  const tryWith = body.try === undefined || body.try === null ? undefined : str(body.try).trim() || undefined;
  if (tryWith !== undefined && tryWith.length > VERSION_TRY) throw new HTTPError(400, `what to try is at most ${VERSION_TRY} characters`);
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
  const n = await agentsCall(() => deps.agents.createVersion(me.tenantID, taf.id, { definition: def, note, by: "setup", by_kind: "agent", ...(draft ? { draft } : {}) }));
  if (from || tryWith) {
    // Kept so the same result hands over this version again, with what to
    // try it with. A record that changed meanwhile is read again once.
    for (let attempt = 0; attempt < 2; attempt++) {
      const cur = attempt === 0 ? taf : await deps.tafs.get(me.tenantID, taf.id);
      if (!cur) break;
      const next = { ...cur, ...(from ? { versionsFrom: { ...cur.versionsFrom, [from]: n } } : {}), ...(tryWith ? { tries: { ...cur.tries, [String(n)]: tryWith } } : {}) };
      if (await deps.tafs.putIf(me.tenantID, next, cur)) break;
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
  await tellChange(me.tenantID, "taf", taf.id, deps);
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
  const ai = await aiBound(event, deps);
  const at = await granted(event, setup.kiffAgent, { requester: member(me) }, deps);
  const run = await agentsCall(() => deps.agents.startRun(me.tenantID, setup.id, input, at, ai));
  return { setup: setup.id, session: run.session, new_session: true };
}

/**
 * The owner writes to a run that is at work (kiff/tafs#64): kiff/agents keeps the
 * message on the run, and the run reads it at its next step, before its next turn
 * (kiff/agents#102). A run that has ended,
 * or was asked to stop, is 409: the app sends the message as a new one. The run must
 * be this taf's: kiff/agents answers another agent's as not found.
 */
async function steer(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  const run = str(parseBody(event).run).trim();
  if (!run) throw new HTTPError(400, "name the run to write to");
  const text = chatText(event);
  try {
    const out = await deps.agents.steerRun(me.tenantID, taf.id, run, text, member(me));
    return { run: out.id, status: out.status };
  } catch (err) {
    if (refused(err, "run_ended", "not_paused")) throw new HTTPError(409, "the run has ended: send it as a new message");
    if (err instanceof AgentsError && err.status === 409) throw new HTTPError(409, err.message);
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
    if (refused(err, "waiting_for_kiff", "run_changed")) return { status: "waiting" };
    if (err instanceof AgentsError && err.status === 409) throw new HTTPError(409, err.message);
    if (err instanceof AgentsError && err.status === 404) throw new HTTPError(404, "no run of this taf waits on that held call");
    throw agentsFailure(err);
  }
}

/** The owner's message, 1 to CHAT.maxText characters. */
/** The person's words: 1 to CHAT.maxText characters, or none when the message is files alone. */
function chatText(event: HTTPEvent, withFiles = false): string {
  const text = str(parseBody(event).text).trim();
  if ((!text && !withFiles) || text.length > CHAT.maxText) throw new HTTPError(400, `write 1 to ${CHAT.maxText} characters`);
  return text;
}

/** An attachment's id as kiff/agents makes it. */
const ATTACHMENT_ID = /^att_[0-9a-f]{32}$/;

/** The files attached to the message, by id (kiff/tafs#209): at most five, each named once. */
function files(event: HTTPEvent): string[] {
  const raw = parseBody(event).attachments;
  if (raw === undefined) return [];
  if (!Array.isArray(raw) || raw.length > ATTACHMENTS.perMessage || !raw.every((x) => typeof x === "string" && ATTACHMENT_ID.test(x)) || new Set(raw).size !== raw.length) {
    throw new HTTPError(400, `attach at most ${ATTACHMENTS.perMessage} files, each once, by the id their upload gave`);
  }
  return raw as string[];
}

/** How many files a message carries; kiff/agents keeps the limits per file. */
export const ATTACHMENTS = { perMessage: 5 };

/**
 * POST /v1/tafs/{id}/attachments: a file the person will attach to a message in their own
 * chat (kiff/tafs#209). kiff/agents checks its type and size, keeps it for them alone, and
 * answers where to upload its bytes: the phone sends them there, never through tafs cloud.
 */
async function attachFile(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<unknown> {
  const body = parseBody(event);
  const size = Number(body.size);
  const file = { media_type: str(body.media_type), name: str(body.name).slice(0, 200), size, sha256: str(body.sha256) };
  if (!file.media_type || !Number.isInteger(size) || size < 1 || !/^[0-9a-f]{64}$/.test(file.sha256)) {
    throw new HTTPError(400, "send the file's media_type, name, size in bytes and sha256 (64 lowercase hex digits)");
  }
  return agentsCall(() => deps.agents.createAttachment(me.tenantID, taf.id, ownerKey(me), file));
}

/** GET /v1/tafs/{id}/attachments/{att}: a file the person attached, to download for a minute. Only theirs. */
async function readFile(me: KIFFIdentity, taf: Taf, id: string, deps: Deps): Promise<unknown> {
  if (!ATTACHMENT_ID.test(id)) throw new HTTPError(404, "not found");
  return agentsCall(() => deps.agents.readAttachment(me.tenantID, taf.id, ownerKey(me), id));
}

/** The person as the requester of their runs: their chats are theirs in kiff/agents. */
function member(me: KIFFIdentity): Requester {
  return { kind: "member", id: me.subject };
}

/**
 * Whether the person manages the taf, as KIFF answers it (GET /v1/me/access): only they
 * see its doors' conversations and knocks. A taf with no KIFF agent goes by the account
 * role; a KIFF that can't be read shows none.
 */
async function managesTaf(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<boolean> {
  if (!taf.kiffAgent) return me.capabilities.includes("cards.manage");
  const access = await readAccess(bearer(event), deps.fetch, deps.kiffBase).catch(() => undefined);
  return access?.get(taf.kiffAgent)?.manage === true;
}

/**
 * Refuses a change to a taf the person doesn't manage: their role must manage Cards, and
 * KIFF must say they manage this taf (RFC 050 decision 11: an editor, only the tafs they made).
 */
async function needTaf(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<void> {
  need(me, "cards.manage");
  if (!(await managesTaf(event, me, taf, deps))) throw new HTTPError(403, "you can change only the tafs you manage: an editor, the ones they made");
}

/** Account events as the person sees them: who wrote to a door only for the tafs they manage. */
async function doorEventsFor(event: HTTPEvent, me: KIFFIdentity, events: AccountEvent[], deps: Deps): Promise<AccountEvent[]> {
  if (!events.some((e) => e.from !== undefined)) return events;
  const [tafs, access] = await Promise.all([
    deps.tafs.list(me.tenantID),
    readAccess(bearer(event), deps.fetch, deps.kiffBase).catch(() => undefined),
  ]);
  const agentOf = new Map(tafs.map((t) => [t.id, t.kiffAgent]));
  const manages = (taf?: string) => {
    const agent = taf ? agentOf.get(taf) : undefined;
    return agent ? access?.get(agent)?.manage === true : me.capabilities.includes("cards.manage");
  };
  return events.map((e) => (e.from === undefined || manages(e.taf) ? e : (({ from: _, ...rest }) => rest)(e)));
}

/** Who a session is read as: the person, except a door's chat, which the thread shows as tafs cloud. */
function readerOf(me: KIFFIdentity, s: Session): string | undefined {
  return s.owner?.startsWith("door:") ? undefined : ownerKey(me);
}

/** The person as a session's owner, as kiff/agents writes it. */
function ownerKey(me: KIFFIdentity): string {
  return `member:${me.subject}`;
}

/**
 * The chat a message goes into (kiff/tafs#73): a new one when the owner asked
 * for it (new_chat), the one they reopened (session, one of their chats with
 * this taf), else their live one, which kiff/agents picks. kiff/agents keeps
 * each person's flood guard too: past it, the message is 429.
 */
async function chatAt(event: HTTPEvent, me: KIFFIdentity, taf: Taf, deps: Deps): Promise<RunAt> {
  const body = parseBody(event);
  if (body.new_chat === true) return granted(event, taf.kiffAgent, { requester: member(me) }, deps);
  const chosen = str(body.session).trim();
  if (chosen) return granted(event, taf.kiffAgent, { session: (await ownChat(me, taf, chosen, deps)).id, requester: member(me) }, deps);
  return granted(event, taf.kiffAgent, { requester: member(me), live: true }, deps);
}

/**
 * The person's run, with their run grant for the taf's KIFF agent: KIFF's word that
 * it is theirs, which kiff/agents takes the requester from. KIFF saying they may not
 * use it is 403. KIFF giving none otherwise leaves the run as asserted, as before.
 */
async function granted(event: HTTPEvent, agent: string, at: RunAt, deps: Deps): Promise<RunAt> {
  if (!agent) return at;
  const grant = await runGrant(bearer(event), agent, deps.fetch, deps.kiffBase);
  if (grant === "no_access") throw new HTTPError(403, "this taf isn't shared with you: ask an admin to share it");
  return grant ? { ...at, grant } : at;
}

/** Whether the run opened its chat: a reopened chat never, a new one always, a live one as kiff/agents says. */
function newSession(at: RunAt, run: Run): boolean {
  if (at.session) return false;
  return at.live ? run.new_session === true : true;
}

/** One of the person's chats with the taf, by its id: another taf's, a job run's, another person's or none is 404. */
async function ownChat(me: KIFFIdentity, taf: Taf, id: string, deps: Deps, kind: "chat" | "trial" = "chat"): Promise<Session> {
  if (!id) throw new HTTPError(404, "no such chat");
  let read: Awaited<ReturnType<Deps["agents"]["session"]>>;
  try {
    read = await deps.agents.session(me.tenantID, id, ownerKey(me));
  } catch (err) {
    if (err instanceof AgentsError && err.status === 404) throw new HTTPError(404, "no such chat");
    throw agentsFailure(err);
  }
  if (read.session.agent !== taf.id || read.session.kind !== kind) throw new HTTPError(404, "no such chat");
  return read.session;
}

/**
 * The taf's chats, newest first, each named by the start of what the owner asked
 * (kiff/tafs#82): their first message that isn't a component's answer. Archived
 * ones are left out, or, with `archivedOnly`, are the only ones (kiff/tafs#97).
 */
async function chats(me: KIFFIdentity, taf: Taf, deps: Deps, archivedOnly = false): Promise<Array<{ session: string; started: string; updated: string; title: string }>> {
  await moveArchived(me, taf, deps);
  const all = await agentsCall(() => deps.agents.sessions(me.tenantID, taf.id, CHAT.sessionsScanned, ownerKey(me)));
  const listed = all.filter((s) => s.kind === "chat" && (s.archived === true) === archivedOnly).slice(0, CHAT.chatsListed);
  const read = await Promise.all(listed.map((s) => deps.agents.session(me.tenantID, s.id, ownerKey(me)).catch(() => undefined)));
  return listed.flatMap((s, i) => {
    const events = read[i]?.events ?? [];
    const text = chatTitle(events.filter((e) => e.kind === "owner_message").map((e) => {
      const t = (e.body as { text?: unknown })?.text;
      return typeof t === "string" ? ownerWords(t.trim()) : "";
    }), events.filter((e) => e.kind === "component").map((e) => e.body));
    if (!text) return [];
    const title = text.length > CHAT.chatTitle ? text.slice(0, CHAT.chatTitle - 1).trimEnd() + "…" : text;
    return [{ session: s.id, started: iso(s.started), updated: iso(s.updated), title }];
  });
}

/**
 * The owner's answer to a component, as the app writes it (contract 1 on
 * kiff/agents#91): "[<type> <id>] <answer>", such as "[approval connect-stripe] declined".
 */
const COMPONENT_ANSWER = /^\[(?:steps|notice|confirm|questions|approval|sign_in|result) ([^\]\s]+)\]/;

/**
 * A chat's title (kiff/tafs#82): the owner's first message that isn't a component's
 * answer, which says what they asked. A chat of answers alone is named by the first
 * one's answer, without its component; a questions card's answer by its words, from
 * `shown`, the session's components.
 */
export function chatTitle(ownerMessages: string[], shown: unknown[] = []): string {
  const said = ownerMessages.filter(Boolean);
  const asked = said.find((t) => !COMPONENT_ANSWER.test(t));
  if (asked) return asked;
  const first = said[0] ?? "";
  const id = COMPONENT_ANSWER.exec(first)?.[1];
  const answer = first.replace(COMPONENT_ANSWER, "").trim();
  return (first.startsWith("[questions ") && questionsWords(answer, shown, id)) || answer;
}

type ShownQuestion = { id?: unknown; options?: Array<{ value?: unknown; label?: unknown }> };

/**
 * A questions card's answer, `{"<question>": {"value": …} | {"values": […]} | {"own": "…"}}`,
 * in the owner's words as the apps show it: each choice by its label, in the card's
 * order, "In your words: “…”" for their own. Undefined when it isn't answers.
 */
function questionsWords(answer: string, shown: unknown[], id: string | undefined): string | undefined {
  let answers: Record<string, { value?: unknown; values?: unknown; own?: unknown }>;
  try {
    answers = JSON.parse(answer);
  } catch {
    return undefined;
  }
  if (!answers || typeof answers !== "object" || Array.isArray(answers)) return undefined;
  // The card answered: the latest one shown with its id.
  const card = [...shown].reverse().find((c) => (c as { type?: unknown; id?: unknown })?.type === "questions" && (c as { id?: unknown }).id === id) as { questions?: ShownQuestion[] } | undefined;
  const questions = Array.isArray(card?.questions) ? card.questions : [];
  const ids = questions.map((q) => q.id).filter((q): q is string => typeof q === "string");
  const order = [...ids, ...Object.keys(answers).filter((k) => !ids.includes(k)).sort()];
  const parts = order.flatMap((q) => {
    const a = answers[q];
    if (!a || typeof a !== "object") return [];
    const options = questions.find((x) => x.id === q)?.options ?? [];
    const label = (v: unknown) => String(options.find((o) => o.value === v)?.label ?? v);
    if (typeof a.value === "string") return [label(a.value)];
    if (Array.isArray(a.values)) return [a.values.map(label).join(", ")];
    if (typeof a.own === "string") return [`In your words: “${a.own}”`];
    return [];
  });
  return parts.length ? parts.join(" · ") : undefined;
}

/**
 * The person archives one of their chats with the taf (kiff/tafs#82): it leaves their
 * list and the thread, and a message no longer goes on in it as the live chat. It isn't
 * deleted: kiff/agents keeps it its 30 days, and they can still open it by its id.
 */
async function archive(me: KIFFIdentity, taf: Taf, session: string, deps: Deps): Promise<unknown> {
  const chat = await ownChat(me, taf, session, deps);
  await agentsCall(() => deps.agents.archive(me.tenantID, chat.id, true, ownerKey(me)));
  return { session: chat.id, archived: true };
}

/**
 * The person puts an archived chat back in their list (kiff/tafs#97). It goes back in the
 * thread too; the live chat stays the newest one.
 */
async function unarchive(me: KIFFIdentity, taf: Taf, session: string, deps: Deps): Promise<unknown> {
  const chat = await ownChat(me, taf, session, deps);
  await agentsCall(() => deps.agents.archive(me.tenantID, chat.id, false, ownerKey(me)));
  return { session: chat.id, archived: false };
}

/**
 * Moves the taf's chats archived while tafs cloud kept archives to kiff/agents, once:
 * a chat already gone is skipped. Kept until each has moved, so a failure tries again.
 */
async function moveArchived(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<void> {
  const ids = await deps.archived.list(me.tenantID, taf.id);
  if (ids.length === 0) return;
  for (const id of ids) {
    try {
      await deps.agents.archive(me.tenantID, id, true);
    } catch (err) {
      if (!(err instanceof AgentsError && err.status === 404)) await agentsCall(() => Promise.reject(err));
    }
  }
  await deps.archived.remove(me.tenantID, taf.id);
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
 *   event: thinking   {text}: more of the summary of what the taf is thinking (kiff/tafs#285)
 *   event: text       {text}: more of the answer
 *   event: activity   {tool, step?, call_id}: a tool's name as the agent calls it, and what
 *                     it's doing in a few words, never its input
 *   event: step       {call_id, status}: how that call ended, "done" or "error" (kiff/tafs#285)
 *   event: held       {exception, invocation}: KIFF holds the call just made for the
 *                     owner, at this point of the answer (kiff/tafs#206)
 *   event: component  {component}: a component the agent showed
 *   event: state      {state, words?}: the one line the app shows while the taf works, as tafs
 *                     cloud paces it: "thinking", "working" with the step's words, "waiting_for_you",
 *                     then "done". Sent only when it changes, a line held at least a second
 *                     (STATE_HOLD_MS) unless it waits for the person or ends. The model's thinking
 *                     text is never in it: an app shows this line, and draws no `thinking` text
 *   event: end        {status, stop}: where the run stopped
 *   event: error      {error}: the stream broke; the conversation has the rest
 *
 * GET /v1/tafs/{id}/runs/{run}/stream?after=N picks the person's own run back up
 * (kiff/tafs#285): a phone that left the chat and came back follows it to its
 * end. It sends the steps from the run's record (activity, step, held), `said`
 * {} where the taf wrote a message (the conversation has it), `part` {at} where
 * it went on after a pause (a held call the owner answered: what came before is
 * the conversation's, with its own work), then `end`.
 *
 * Tool inputs and results never reach the phone. The run is kiff/agents'
 * invocation's: a phone that goes away loses nothing, and reads the rest in
 * the conversation.
 */
export async function handleStream(event: HTTPEvent, deps: Deps): Promise<StreamResult> {
  try {
    const method = event.requestContext.http.method.toUpperCase();
    const parts = event.rawPath.split("/").filter(Boolean);
    if (method === "GET" && parts.length === 6 && parts[0] === "v1" && parts[1] === "tafs" && parts[3] === "runs" && parts[5] === "stream") {
      return await resumeStream(event, parts[2] ?? "", parts[4] ?? "", deps);
    }
    if (!(method === "POST" && parts.length === 5 && parts[0] === "v1" && parts[1] === "tafs" && parts[3] === "messages" && parts[4] === "stream")) {
      throw new HTTPError(404, "not found");
    }
    const me = await person(event, deps);
    const taf = await owned(me, parts[2] ?? "", deps);
    const attached = files(event);
    const owner = chatText(event, attached.length > 0);
    const answered = await oldFormAnswer(event, me, taf, owner, deps);
    if (answered) return answeredStream(me, taf, answered, deps, await titlesOf(event, me, taf, deps));
    const lines = await takeCoachLines(me, taf, deps);
    let run: AsyncGenerator<RunStreamEvent> | undefined;
    let first: IteratorResult<RunStreamEvent> | undefined;
    let at: RunAt;
    let titles: ToolTitles = new Map();
    try {
      const text = lines.text(owner);
      const [chosen, ai, known] = await Promise.all([chatAt(event, me, taf, deps), aiBound(event, deps), titlesOf(event, me, taf, deps)]);
      at = chosen;
      titles = known;
      if (attached.length) at.attachments = attached;
      try {
        // Coach's runs are announced when they pause, so a result it shows is
        // handed over by tafs cloud with no phone needed (handOver).
        const notify = taf.job === SETUP_JOB && deps.publicURL ? { url: `${deps.publicURL}/v1/runs/ended`, ref: newRef(me.tenantID) } : undefined;
        run = deps.agents.streamRun(me.tenantID, taf.id, text, at, ai, notify);
        first = await run.next();
      } catch (err) {
        throw agentsFailure(err);
      }
    } catch (err) {
      // The message didn't start: Coach reads its lines with the next one.
      await lines.putBack();
      throw err;
    }
    return { statusCode: 200, headers: { "content-type": "text/event-stream", "cache-control": "no-store" }, body: relay(run, first, at, titles) };
  } catch (err) {
    const status = err instanceof HTTPError || err instanceof KIFFError || err instanceof PushError ? err.status : 500;
    if (status === 500) console.error(err);
    const message = status === 500 ? "something went wrong on tafs cloud" : (err as Error).message;
    return { statusCode: status, headers: { "content-type": "application/json", "cache-control": "no-store" }, body: once(JSON.stringify({ error: message })) };
  }
}

/**
 * A card answered from the old form, streamed as a message would be: its chat, the message
 * taken, then the run that waited going on, as a resumed run's stream shows it.
 */
async function answeredStream(me: KIFFIdentity, taf: Taf, answered: Run, deps: Deps, titles: ToolTitles): Promise<StreamResult> {
  const run = deps.agents.resumeRun(me.tenantID, taf.id, answered.id, 0, ownerKey(me));
  let first: IteratorResult<RunStreamEvent>;
  try {
    first = await run.next();
  } catch (err) {
    throw agentsFailure(err);
  }
  async function* body(): AsyncGenerator<string> {
    yield sse("session", { session: answered.session, new_session: false, run: answered.id });
    yield sse("taken", {});
    yield* relay(run, first, undefined, titles);
  }
  return { statusCode: 200, headers: { "content-type": "text/event-stream", "cache-control": "no-store" }, body: body() };
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

/** A run's id as kiff/agents writes it: letters, digits, '-' and '_'. */
const RUN_ID = /^[A-Za-z0-9_-]{1,128}$/;

/**
 * GET /v1/tafs/{id}/runs/{run}/stream: the person's own run, picked back up
 * from its record (kiff/tafs#285). kiff/agents answers only the requester's run.
 */
async function resumeStream(event: HTTPEvent, tafID: string, runID: string, deps: Deps): Promise<StreamResult> {
  const me = await person(event, deps);
  const taf = await owned(me, tafID, deps);
  if (!RUN_ID.test(runID)) throw new HTTPError(404, "no such run");
  const after = Number(new URLSearchParams(event.rawQueryString ?? "").get("after") ?? "0");
  if (!Number.isInteger(after) || after < 0) throw new HTTPError(400, "after is an event index, 0 or more");
  const titles = await titlesOf(event, me, taf, deps);
  const run = deps.agents.resumeRun(me.tenantID, taf.id, runID, after, ownerKey(me));
  let first: IteratorResult<RunStreamEvent>;
  try {
    first = await run.next();
  } catch (err) {
    throw agentsFailure(err);
  }
  return { statusCode: 200, headers: { "content-type": "text/event-stream", "cache-control": "no-store" }, body: relay(run, first, undefined, titles) };
}

/**
 * kiff/agents' run stream, trimmed to what the phone shows. A started run's
 * (`at` set) shows what the agent does as it does it; a resumed one's, its
 * recorded steps.
 */
/** How long the stream's `state` line holds before it changes, unless it waits for the person or ends. */
export const STATE_HOLD_MS = 1000;

/** The card types a run waits on the person for: while one shows, the line says it waits for them. */
const WAITS_FOR_YOU = new Set(["confirm", "questions", "approval", "sign_in"]);

/**
 * The stream's one calm line (`state`): what the taf does now, sent only on a change, and held at
 * least STATE_HOLD_MS so it doesn't flicker. A line the hold kept back goes with the next event
 * once the hold is over, or is replaced by a newer one.
 */
class Pace {
  private sent?: { key: string; at: number };
  private pending?: { state: string; words?: string };
  constructor(private readonly now: () => number) {}

  /** The `state` event to send now for this line, if any. */
  set(state: string, words?: string): string | undefined {
    this.pending = { state, ...(words ? { words } : {}) };
    return this.flush(state === "waiting_for_you" || state === "done");
  }

  /** The line held back, once its hold is over (or at once, when `now`). */
  flush(now = false): string | undefined {
    const p = this.pending;
    if (!p) return undefined;
    const key = `${p.state}|${p.words ?? ""}`;
    if (this.sent?.key === key) { this.pending = undefined; return undefined; }
    const t = this.now();
    if (!now && this.sent && t - this.sent.at < STATE_HOLD_MS) return undefined;
    this.sent = { key, at: t };
    this.pending = undefined;
    return sse("state", p);
  }
}

async function* relay(run: AsyncGenerator<RunStreamEvent>, first: IteratorResult<RunStreamEvent> | undefined, at: RunAt | undefined, titles: ToolTitles = new Map(), now: () => number = Date.now): AsyncGenerator<string> {
  const steps = new Steps(titles);
  const pace = new Pace(now);
  let turns = 0; // a resumed run's model turns so far
  // What it's doing, in a few words: only these, never the call's arguments.
  const call = (tool: string, input: unknown, callID: unknown): string => {
    const id = typeof callID === "string" ? callID : undefined;
    const step = steps.call(tool, input, id);
    return sse("activity", { tool, ...(step === undefined ? {} : { step }), ...(id ? { call_id: id } : {}) });
  };
  // How a call ended. Where KIFF held it, the app shows the owner's card there in the
  // answer: only KIFF's keys for it, never what the tool answered.
  const ended = (callID: unknown, r: { text?: unknown; is_error?: unknown; state?: unknown; hold?: { exception?: unknown }; invocation?: unknown }, notMade = false): string => {
    steps.result(callID, r.text);
    if (r.state === "held") {
      return sse("held", {
        exception: typeof r.hold?.exception === "string" ? r.hold.exception : "",
        invocation: typeof r.invocation === "string" ? r.invocation : "",
      });
    }
    // Refused back unmade (an act beside a read): nothing reached KIFF or the tool.
    const status = notMade ? "not_made" : r.is_error === true ? "error" : "done";
    return sse("step", { call_id: typeof callID === "string" ? callID : "", status });
  };
  const out = (e: RunStreamEvent): string | undefined => {
    const d = (e.data ?? {}) as Record<string, unknown>;
    switch (e.event) {
      case "run":
        return at ? sse("session", { session: d.session, new_session: newSession(at, d as unknown as Run), run: d.id }) : undefined;
      case "delta":
        if (!at) return undefined;
        if (d.kind === "text" && typeof d.text === "string") return sse("text", { text: d.text });
        if (d.kind === "thinking" && typeof d.text === "string") return sse("thinking", { text: d.text });
        if (d.kind === "tool_call" && typeof d.tool === "string") return call(d.tool, d.input, d.call_id);
        if (d.kind === "component" && d.component !== undefined) return sse("component", { component: withLine(d.component) });
        if (d.kind === "owner_message") return sse("taken", {}); // the app has the text: it wrote it
        if (d.kind === "tool_result") return ended(d.call_id, (d.result ?? {}) as Record<string, unknown>, d.not_made === true);
        return undefined;
      case "event": {
        // A started run's recorded events: the conversation has them. A resumed run's are
        // its steps so far, and where it wrote a message.
        if (at) return undefined;
        const b = (d.body ?? {}) as Record<string, unknown>;
        if (d.kind === "tool_call" && typeof b.tool === "string") return call(b.tool, b.input, b.call_id);
        if (d.kind === "tool_result") return ended(b.call_id, b, b.not_made === true);
        if (d.kind === "agent_message" || d.kind === "report") return sse("said", {});
        if (d.kind === "component") return sse("component", { component: withLine(d.body) });
        if (d.kind === "usage") {
          // The first turn after a pause starts a part: what came before it is in the
          // conversation, with its own work, and isn't shown as this part's (the 859 test).
          const turn = turnTiming(b);
          if (!turn) return undefined;
          const at = Date.parse(typeof d.time === "string" ? d.time : "");
          const resumed = startsPart(turn, turns, at);
          turns++;
          return resumed ? sse("part", Number.isNaN(at) ? {} : { at: new Date(at - turn.ms).toISOString() }) : undefined;
        }
        return undefined;
      }
      case "end":
        return sse("end", { status: d.status ?? "", stop: d.stop ?? "", ...(d.status === "ERROR" ? { done: wentThrough(d.done) } : {}) });
      default:
        return undefined;
    }
  };
  // The line each event puts the taf on: thinking while the model writes or thinks, working on a
  // step in its words, waiting for the person on a card that waits or a held call, done at the end.
  const line = (e: RunStreamEvent): string | undefined => {
    const d = (e.data ?? {}) as Record<string, unknown>;
    const b = (e.event === "event" ? (d.body ?? {}) : d) as Record<string, unknown>;
    const kind = d.kind;
    if (e.event === "end") return pace.set("done");
    if (e.event === "run") return pace.set("thinking");
    if (kind === "thinking" || kind === "text") return pace.set("thinking");
    if (kind === "tool_call" && typeof b.tool === "string") {
      return pace.set("working", steps.call(b.tool, b.input)?.replace(/…$/, ""));
    }
    if (kind === "tool_result") {
      const r = (e.event === "event" ? b : (d.result ?? {})) as { state?: unknown };
      return pace.set(r.state === "held" ? "waiting_for_you" : "thinking");
    }
    if (kind === "component") {
      const c = (e.event === "event" ? d.body : d.component) as { type?: unknown } | undefined;
      return typeof c?.type === "string" && WAITS_FOR_YOU.has(c.type) ? pace.set("waiting_for_you") : undefined;
    }
    return pace.flush();
  };
  try {
    if (first && !first.done) {
      const s = out(first.value);
      if (s) yield s;
      const l = line(first.value);
      if (l) yield l;
    }
    for await (const e of run) {
      const s = out(e);
      if (s) yield s;
      const l = line(e);
      if (l) yield l;
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
  // Only the person's own, or one with no owner (a job's): never another person's run.
  const read = await agentsCall(() => deps.agents.session(me.tenantID, session, ownerKey(me)));
  if (read.session.agent !== taf.id) throw new HTTPError(404, "no such session");
  const run = read.events.at(-1)?.run;
  if (!run) return { stopped: false };
  try {
    const out = await deps.agents.cancelRun(me.tenantID, taf.id, run, member(me));
    return { stopped: true, run: out.id, status: out.status };
  } catch (err) {
    // Already over: nothing is left to stop.
    if (refused(err, "run_ended", "run_changed")) return { stopped: false };
    if (err instanceof AgentsError && err.status === 409) throw new HTTPError(409, err.message);
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

/**
 * A note the taf keeps: shared, read in every conversation, or the person's own, from
 * their conversations. A shared note's id is its name; the person's own is "@" and its
 * name, as no shared note's name starts with "@".
 */
export interface Note {
  id: string;
  path: string;
  content: string;
  updated_at: string;
  scope: "shared" | "mine";
}

/** The taf's newest notes the person reads, newest first: the shared ones and their own, never another person's. */
async function notes(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<Note[]> {
  const all = await agentsCall(() => deps.agents.memories(me.tenantID, taf.id, ownerKey(me)));
  return all
    .map((n): Note => n.scope
      ? { id: `@${n.name}`, path: n.name, content: n.text, updated_at: iso(n.updated), scope: "mine" }
      : { id: n.name, path: n.name, content: n.text, updated_at: iso(n.updated), scope: "shared" })
    .sort((a, b) => b.updated_at.localeCompare(a.updated_at))
    .slice(0, MEMORY.shown);
}

/** Forgets one note, by its name, as the app sends it (escaped). Gone means gone: kiff/agents keeps no versions. */
async function forget(me: KIFFIdentity, taf: Taf, escapedName: string, deps: Deps): Promise<unknown> {
  let name: string;
  try { name = decodeURIComponent(escapedName); } catch { throw new HTTPError(404, "no such note"); }
  if (!name) throw new HTTPError(404, "no such note");
  // The person's own note, by "@" and its name; else the shared one of that name.
  const mine = name.startsWith("@");
  const note = mine ? name.slice(1) : name;
  if (!note) throw new HTTPError(404, "no such note");
  await agentsCall(() => deps.agents.forget(me.tenantID, taf.id, note, mine ? ownerKey(me) : undefined));
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
    /** Where its results go (docs/reliability.md). */
    results: placesOf(taf),
  };
}

/**
 * The app's home list (kiff/tafs#91): every taf with its state, colour and
 * Card summary, then the account's other agents with Cards, the ones tafs
 * doesn't run (made through KIFF's API), listed apart. KIFF's Cards and held
 * calls are read with the owner's session; a KIFF that can't be read fails the
 * list rather than showing tafs without their Cards. It lists only the agents KIFF
 * says the person may use or oversee (GET /v1/me/access): tafs cloud decides nothing.
 */
async function home(event: HTTPEvent, me: KIFFIdentity, deps: Deps) {
  // The owner is around (kiff/tafs#210): what isn't urgent is pushed quietly, not kept for later.
  await deps.slots.keep(presenceKey(me.tenantID), presentUntil(deps.now?.() ?? new Date()))
    .catch((err) => console.error(`presence: ${(err as Error).message}`));
  const [records, removing, kiff, access] = await Promise.all([
    deps.tafs.list(me.tenantID),
    deps.removals.list(me.tenantID),
    readKIFF(bearer(event), deps.fetch, deps.kiffBase),
    readAccess(bearer(event), deps.fetch, deps.kiffBase),
  ]);
  const { cards, waiting } = byHolder(kiff.mandates, kiff.held);
  const all = records.filter((t) => !t.removing && t.job !== SETUP_JOB)
    .sort((a, b) => a.created.localeCompare(b.created) || a.id.localeCompare(b.id));
  // A taf keeps the colour it got at its start. One started before colours takes
  // the first nobody has, kept only if its record is still what was read here:
  // the list never writes back over a removal or an upgrade.
  // Every taf's, shown or not, so none shares one when it's shared later.
  const taken = all.flatMap((t) => (t.color === undefined ? [] : [t.color]));
  const colors = new Map<string, number>();
  for (const t of all) {
    if (t.color !== undefined) continue;
    const color = freeColor(taken);
    taken.push(color);
    colors.set(t.id, color);
    await deps.tafs.putIf(me.tenantID, { ...t, color }, t);
  }
  const tafs = all.filter((t) => listable(access, t.kiffAgent));
  const views = await Promise.all(tafs.map((t) => view(t, me, deps)));
  // Each taf's open trial, its own entry in the app's menu (kiff/tafs#173). One that can't
  // be read leaves that entry out, never the list.
  const trials = await Promise.all(tafs.map((t) => deps.agents.openedTrial(me.tenantID, t.id).then((tr) => trialFor(me, t, tr, deps)).catch(() => null)));
  // A halted taf reads halted everywhere (kiff/tafs#236, item 13): kiff/agents holds it, on
  // its own or with its account. A read that fails leaves its state as the rest says.
  const halts = await Promise.all(tafs.map((t) => deps.agents.halted(me.tenantID, t.id).then((h) => h.halted, () => false)));
  // Each taf's doors, for its avatar's rim: a read that fails shows none.
  const lights = await Promise.all(tafs.map((t) => deps.doors ? doorLights(deps.doors.store, me.tenantID, t.id, deps.now?.() ?? new Date()).catch(() => []) : []));
  // Where each taf's work stands (docs/reliability.md): one read of its recent runs. A read that fails leaves it out.
  const now = deps.now?.() ?? new Date();
  const recent = await Promise.all(tafs.map((t) => deps.agents.runs(me.tenantID, t.id, false, 20).catch(() => undefined)));
  const listed = tafs.map((t, i) => {
    const v = views[i]!;
    const trial = trials[i];
    const card = summary(cards.get(t.kiffAgent) ?? []);
    const held = waiting.get(t.kiffAgent) ?? 0;
    // Its job in words, for the taf's page (kiff/tafs#167), not its Cards' count.
    const words = JOBS.find((j) => j.id === v.job)?.summary;
    const state = halts[i] && card.active ? "halted" : stateOf(card, held, v.status === "paused" ? "paused" : "active");
    const runs = recent[i];
    const work = runs ? summaryOf(runs, { paused: v.status === "paused", halted: halts[i] === true, unscheduled: t.unscheduled === true, next: v.next_run }, me.timeZone ?? "UTC", now) : undefined;
    return { ...v, ...(words ? { job_summary: words } : {}), ...(trial ? { trial } : {}), color: t.color ?? colors.get(t.id) ?? 0, state, waiting: held, card, ...(lights[i]!.length ? { doors: lights[i] } : {}), ...(work ? { work } : {}) };
  });
  // Setup's agent and a taf being removed are tafs cloud's too: never another agent.
  // Another agent's colour isn't kept: it can change when a taf is added.
  // A taf's trial acts in KIFF as its own agent, "<agent>~trial" (kiff/tafs#247):
  // it shows as the taf's trial, never as another agent.
  const ours = new Set(records.flatMap((t) => t.kiffAgent ? [t.kiffAgent, `${t.kiffAgent}~trial`] : []));
  const agents = [...new Set([...cards.keys(), ...waiting.keys()])].filter((a) => !ours.has(a) && listable(access, a)).sort();
  const others = agents.map((agent) => {
    const card = summary(cards.get(agent) ?? []);
    const held = waiting.get(agent) ?? 0;
    const color = freeColor(taken);
    taken.push(color);
    return { id: null, name: agent, job: null, kiff_agent: agent, status: null, next_run: null, last_run: null, color, state: stateOf(card, held, null), waiting: held, card };
  });
  // Which doors this deployment offers (docs/doors.md): none, and the app shows no Doors.
  const doors = deps.doors ? offered(await deps.doors.config()).map((a) => a.id) : [];
  // The limits the apps check before sending: tafs cloud's, never a copy on the phone.
  const limits = { text: CHAT.maxText, trial_idle_days: TRIAL_IDLE_MS / (24 * 3600_000) };
  return { tafs: listed, others, removing, limits, ...(doors.length ? { doors_offered: doors } : {}) };
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

/**
 * What a failed run did before it stopped (kiff/agents#249): each call that acted and went
 * through, by tool, with what it acted on and KIFF's key for it when KIFF named them, so the
 * app shows the amount KIFF recorded for the call. Never a call's input. The stream is the
 * person's own chat, so it goes only to the person whose run it is.
 */
export function wentThrough(done: unknown): Array<{ tool: string; resource?: string; invocation?: string }> {
  if (!Array.isArray(done)) return [];
  const named = (v: unknown) => (typeof v === "string" && v ? v : undefined);
  return done
    .filter((c): c is { tool: string; resource?: unknown; invocation?: unknown } => typeof c?.tool === "string")
    .map((c) => {
      const resource = named(c.resource), invocation = named(c.invocation);
      return { tool: c.tool, ...(resource ? { resource } : {}), ...(invocation ? { invocation } : {}) };
    });
}

/** A time as the app reads it: RFC 3339 in UTC, milliseconds at most. */
/**
 * A model turn's timing, from its usage record: how long it took, whether it is the first
 * after a pause (a call KIFF held, answered by the owner, or a Continue), and the tool calls
 * of the turn before whose results it read, each with whether it failed or was refused back
 * unmade. Undefined for a usage record with no timing (a summary's).
 */
export function turnTiming(body: unknown): { ms: number; resumed: boolean; waited: Array<{ tool: string; error: boolean; notMade: boolean }> } | undefined {
  const t = (body as { timing?: { ms?: unknown; resumed?: unknown; waited?: unknown } } | undefined)?.timing;
  if (!t || typeof t !== "object") return undefined;
  const waited = Array.isArray(t.waited) ? (t.waited as Array<{ tool?: unknown; is_error?: unknown; not_made?: unknown }>) : [];
  return {
    ms: typeof t.ms === "number" && t.ms > 0 ? t.ms : 0,
    resumed: t.resumed === true,
    waited: waited.filter((w) => typeof w?.tool === "string")
      .map((w) => ({ tool: w.tool as string, error: w.is_error === true, notMade: w.not_made === true })),
  };
}

/**
 * From when every turn kiff/agents writes says whether it resumed (`timing.resumed`). It omits
 * the field when false, so only a turn from then on is read by it; one before is read by the
 * rule that came first: a timed turn that waited on no calls, past the run's first. kiff/agents
 * with it was live at 20:26:29 UTC, and a run already going on the code before could write
 * turns without it for up to its longest run, 15 minutes: hence 20:42. The rule that came
 * first holds for turns from either, so a later time costs nothing.
 */
export const RESUMED_SINCE = Date.parse("2026-10-27T20:42:00Z");

/** Whether a timed turn at `at` (ms) starts a part of its run: the first after a pause. */
export function startsPart(turn: { resumed: boolean; waited: unknown[] }, turnsBefore: number, at: number): boolean {
  if (!Number.isNaN(at) && at >= RESUMED_SINCE) return turn.resumed;
  return turnsBefore > 0 && turn.waited.length === 0;
}

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

/** What anyone who may see a taf learns of a run that isn't theirs: when and how it stands, nothing it said or used. */
function runMeta(r: Run) {
  return { run: r.id, status: r.status, trigger: r.trigger, started: iso(r.created), updated: iso(r.updated) };
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

/** The person's own Coach run at work now, if any: the one making this call. Never another person's. */
async function coachRun(call: CoachCall, deps: Deps): Promise<string | undefined> {
  if (!call.person) return undefined;
  const setup = (await deps.tafs.list(call.tenant)).find((t) => t.job === SETUP_JOB && !t.removing);
  if (!setup) return undefined;
  const runs = await deps.agents.runs(call.tenant, setup.id, true, 5).catch(() => []);
  return runs.find((r) => r.status === "RUNNING" && r.requester?.kind === "member" && r.requester.id === call.person?.subject)?.id;
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
  // A run is the person's own when they asked for it in this chat; without a person,
  // Coach's own tasks only. Never another person's, whatever else they may do.
  const theirs = (r: Run) => call.person
    ? r.requester?.kind === "member" && r.requester.id === call.person.subject
    : r.requester?.kind === "agent" && r.requester.id === me.subject;
  // What a run said and used is readable by its own person, and a taf's job (no requester,
  // no trial) by anyone who may use the taf: its reports are everyone's.
  const readable = (r: Run, use: boolean) => theirs(r) || (use && !r.requester && r.trigger !== "trial");
  if (name === "tafs_state") {
    let tafs = (await deps.tafs.list(call.tenant)).filter((t) => !t.removing && t.job !== SETUP_JOB);
    let all: Record<string, { use?: boolean; oversee?: boolean }> | undefined;
    if (call.person) {
      // In a person's chat, only the tafs KIFF says they may use or oversee.
      all = (await coachAct(call, undefined, deps)).agents ?? {};
      const may = all;
      tafs = tafs.filter((t) => may[t.kiffAgent]?.use || may[t.kiffAgent]?.oversee);
    }
    return {
      tafs: await Promise.all(tafs.map(async (t) => {
        const [state, schedule, runs] = await Promise.all([
          agentsCall(() => deps.agents.versions(call.tenant, t.id)),
          t.unscheduled ? Promise.resolve(undefined) : deps.agents.getSchedule(call.tenant, t.id).catch(() => undefined),
          agentsCall(() => deps.agents.runs(call.tenant, t.id, true)),
        ]);
        const live = state.versions.find((v) => v.live);
        return {
          id: t.id, name: t.name, job: t.job, kiff_agent: t.kiffAgent, results: placesOf(t),
          ...(t.unscheduled ? { works_from_chat: true } : schedule
            ? { schedule: { cron: schedule.cron, time_zone: schedule.time_zone || "UTC", on: schedule.enabled, ...(schedule.next ? { next: iso(schedule.next) } : {}) } }
            : {}),
          live_version: live?.n ?? null,
          versions: state.versions.map((v) => ({ n: v.n, note: v.note, live: v.live, created: iso(v.created) })),
          ...(state.switching ? { switching: { to: state.switching.n, waiting_for: state.switching.draft ? "the owner to confirm its Card in tafs" : "its runs to stop" } } : {}),
          // Another person's runs show only how they stand, never what they said or used.
          open_runs: runs.map((r) => readable(r, all ? all[t.kiffAgent]?.use === true : true) ? runSummary(r) : runMeta(r)),
        };
      })),
      cards: "Each taf's Cards aren't here: read them with kiff_card {agent: its kiff_agent}.",
    };
  }
  const taf = await coachTaf(call.tenant, args.taf, deps);
  const n = Number(args.n);
  // In a person's chat, what KIFF says they may do with this taf; tafs cloud only applies it.
  const may = call.person ? await coachAct(call, taf.kiffAgent, deps) : undefined;
  if (name === "taf_run") {
    const runID = str(args.run).trim();
    const r = await agentsCall(() => deps.agents.getRun(call.tenant, taf.id, runID));
    // Reading a run is reading a conversation: only one's own, whatever oversee says.
    if (!readable(r, may ? may.use : true) || (may && !may.use)) throw new ToolRefusal(`that run of ${taf.name} isn't theirs to read`);
    if (r.trigger === "trial") {
      const t = await agentsCall(() => deps.agents.trial(call.tenant, taf.id, runID));
      return { taf: taf.name, ...runSummary(t.run), writes: t.writes };
    }
    return { taf: taf.name, ...runSummary(r) };
  }
  if (name === "taf_task") {
    const text = str(args.text).trim();
    if (!text || text.length > CHAT.maxText) throw new ToolRefusal(`write the task in 1 to ${CHAT.maxText} characters`);
    const running = str(args.run).trim();
    if (running) {
      // Writing into a run is writing into a conversation: only one's own.
      const r = await agentsCall(() => deps.agents.getRun(call.tenant, taf.id, running));
      if (!theirs(r) || (may && !may.use)) throw new ToolRefusal(`that run of ${taf.name} isn't theirs to write into`);
      try {
        // Coach doesn't know yet whose conversation it is in, so it writes as tafs cloud
        // itself: any run of the taf, the way it could before runs had requesters.
        const out = await deps.agents.steerRun(call.tenant, taf.id, running, text);
        return { taf: taf.name, run: out.id, status: out.status, sent: "to the run at work, read at its next step" };
      } catch (err) {
        if (refused(err, "run_ended")) throw new ToolRefusal("that run has ended: hand the task over again without run");
        if (err instanceof AgentsError && err.status === 409) throw new ToolRefusal(err.message);
        throw agentsFailure(err);
      }
    }
    // Coach doesn't know yet whose conversation it runs in, so it asks as itself, an
    // agent: its tasks go on in its own chat with the taf, which no person lists.
    let at: RunAt = { requester: { kind: "agent", id: me.subject }, live: args.chat !== "new" };
    if (may) {
      // In a person's chat, the task is theirs: their own chat with the taf, under the
      // grant KIFF gave for it.
      if (!may.use) throw new ToolRefusal(`${taf.name} isn't shared with them: ask an admin to share it`);
      // KIFF gives one grant per permit and taf: a retried call gets none.
      if (!may.grant) throw new ToolRefusal("KIFF gave no grant for this call: try again");
      at = { grant: may.grant, live: args.chat !== "new" };
    }
    const notify = call.notify();
    const run = await agentsCall(() => deps.agents.startRun(call.tenant, taf.id, text, at, undefined, notify));
    await keepTask(call, notify, { taf: taf.id, run: run.id, coachRun: await coachRun(call, deps), what: text.slice(0, 120), person: call.person?.subject }, deps);
    return { taf: taf.name, run: run.id, chat: run.session, status: run.status, result: "comes back to you when the run ends: don't poll" };
  }
  if (name === "run_stop") {
    if (may) {
      const r = await agentsCall(() => deps.agents.getRun(call.tenant, taf.id, str(args.run).trim()));
      if (!may.oversee && !(may.use && theirs(r))) throw new ToolRefusal(`that run of ${taf.name} isn't theirs to stop`);
    }
    try {
      // As steering: tafs cloud itself stops it, whoever asked for the run.
      const out = await deps.agents.cancelRun(call.tenant, taf.id, str(args.run).trim());
      return { taf: taf.name, run: out.id, status: out.status, withdrawn: "its held calls, in KIFF; nothing more is sent" };
    } catch (err) {
      if (refused(err, "run_ended", "run_changed")) return { taf: taf.name, stopped: false, why: "the run had already ended" };
      if (err instanceof AgentsError && err.status === 409) return { taf: taf.name, stopped: false, why: err.message };
      throw agentsFailure(err);
    }
  }
  if (name === "taf_pause") {
    const on = args.action === "resume";
    if (!on && args.action !== "pause") throw new ToolRefusal("action is pause or resume");
    if (may && !may.oversee && !may.manage) throw new ToolRefusal(`pausing ${taf.name} is for the people who oversee or manage it`);
    if (taf.unscheduled) throw new ToolRefusal(`${taf.name} works from its chat: it has no schedule to ${on ? "resume" : "pause"}`);
    const s = await agentsCall(() => deps.agents.switchSchedule(call.tenant, taf.id, on));
    return { taf: taf.name, schedule: on ? "on" : "paused", ...(s.next ? { next: iso(s.next) } : {}) };
  }
  if (name === "taf_results") {
    if (may && !may.manage) throw new ToolRefusal(`where ${taf.name}'s results go is for the people who manage it`);
    let places: ResultPlace[];
    try { places = resultPlaces(args.places); } catch (err) { throw new ToolRefusal((err as Error).message); }
    await deps.tafs.put(call.tenant, { ...taf, results: places });
    return { taf: taf.name, results: places };
  }
  if (may && !may.manage) throw new ToolRefusal(`changing ${taf.name}'s version is for the people who manage it`);
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
    await keepTask(call, notify, { taf: taf.id, run: run.id, coachRun: await coachRun(call, deps), what: `a trial of version ${n}`, trial: true, person: call.person?.subject }, deps);
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

/** What KIFF says a person may do with an agent; with use, their run grant for it. */
interface CoachMay {
  use: boolean;
  oversee: boolean;
  manage: boolean;
  grant?: string;
}

/**
 * Asks KIFF what the person in Coach's chat may do (POST /v1/coach/act): with an
 * agent, for that agent, and their run grant for it when they may use it; without,
 * for each of the account's agents. The permit KIFF signed for this call is the
 * credential, so tafs cloud needs no key of its own. No answer is no.
 */
async function coachAct(call: CoachCall, agent: string | undefined, deps: Deps): Promise<CoachMay & { agents?: Record<string, CoachMay> }> {
  const none = { use: false, oversee: false, manage: false };
  if (!call.person) return none;
  const doFetch = deps.fetch ?? fetch;
  let res: Response;
  try {
    res = await doFetch(`${deps.kiffBase ?? KIFF_API}/v1/coach/act`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ permit: call.person.permit, ...(agent ? { agent } : {}) }),
    });
  } catch {
    throw new ToolRefusal("KIFF couldn't say what they may do just now: try again in a moment");
  }
  if (!res.ok) throw new ToolRefusal("KIFF couldn't say what they may do just now: try again in a moment");
  const body = await res.json().catch(() => undefined) as Partial<CoachMay> & { agents?: Record<string, Partial<CoachMay>> } | undefined;
  if (!body) return none;
  const one = (b: Partial<CoachMay> | undefined): CoachMay => ({
    use: b?.use === true, oversee: b?.oversee === true, manage: b?.manage === true,
    ...(typeof b?.grant === "string" && b.grant ? { grant: b.grant } : {}),
  });
  return { ...one(body), ...(body.agents ? { agents: Object.fromEntries(Object.entries(body.agents).map(([k, v]) => [k, one(v)])) } : {}) };
}

/** KIFF's published permit keys, fetched once per container. */
let kiffPermitKeys: PermitKeys | undefined;

/**
 * POST /v1/mcp: Coach's tools, reached only through KIFF's gateway, each call
 * with KIFF's permit (coach.ts). The audience is this server as the
 * connection names it: its public address when set, else its own host, which
 * a function URL can't be called under any other.
 */
async function mcpRoute(event: HTTPEvent, deps: Deps): Promise<HTTPResult> {
  const host = event.headers?.host ?? event.headers?.Host;
  const self = deps.publicURL ?? (host ? `https://${host}` : "");
  if (!deps.coach || !self || event.requestContext.http.method.toUpperCase() !== "POST") throw new HTTPError(404, "not found");
  const issuer = deps.kiffBase ?? KIFF_API;
  kiffPermitKeys ??= publishedKeys(`${issuer}/.well-known/kiff-permit-keys.json`, deps.fetch);
  const headers: Record<string, string | undefined> = {};
  for (const [k, v] of Object.entries(event.headers ?? {})) headers[k.toLowerCase()] = v;
  const r = await serveMCP(rawBody(event), headers, {
    audience: `${self}/v1/mcp`, issuer, keys: deps.permitKeys ?? kiffPermitKeys, store: deps.coach,
    coachOf: (tenant) => coachOf(tenant, deps), notifyURL: `${self}/v1/runs/ended`,
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
  if (note.status === "PAUSED" && note.cards?.some((c) => c.type === "result")) return { taken: await handOverOrKeep(tenant, note.agent, note.run, deps) };
  const key = coachKeys.task(tenant, note.ref);
  const task = (await deps.coach.get(key)) as Task | undefined;
  if (!task || task.run !== note.run || task.taf !== note.agent) return { taken: false };
  const r = await agentsCall(() => deps.agents.getRun(tenant, task.taf, task.run));
  const ended = r.status === "COMPLETED" || r.status === "ERROR" || r.status === "CANCELLED";
  if (!ended && r.status !== "PAUSED") return { taken: false };
  const taf = await deps.tafs.get(tenant, task.taf);
  const name = taf?.name ?? task.taf;
  const { line, said } = coachLine(name, task, r);
  // Its level (kiff/tafs#210): quiet while the owner is around, kept for "While you
  // were away" otherwise. A paused run is a hold, which KIFF's alert interrupts for.
  const at = deps.now?.() ?? new Date();
  const level = ended ? levelOf("run_ended", !(await deps.slots.held(presenceKey(tenant)))) : undefined;
  const tafs = await deps.tafs.list(tenant);
  const setup = tafs.find((t) => t.job === SETUP_JOB && !t.removing);
  let told = false;
  if (setup && task.coachRun) {
    told = await deps.agents.steerRun(tenant, setup.id, task.coachRun, line).then(() => true, () => false);
  }
  if (!told && task.person) {
    // The person reads the taf and how it went; their Coach reads the whole line. A task
    // with no person behind it is told to no one's Coach.
    await deps.coach.append(coachKeys.notes(tenant, task.person), { at: at.toISOString(), text: line, taf: name, said, ...(level === "later" ? { away: true } : {}) },
      new Date(at.getTime() + TASK_KEPT_MS));
  }
  if (level === "quiet") await tellPhones(deps, tenant, endedNotification(name, r));
  if (ended) await deps.coach.remove(key);
  return { taken: true };
}

/**
 * The results Coach's chat shows that still wait, handed over now: what a lost note
 * left undone is done when the person next reads the chat. handOver reads each run
 * back and acts once per card, so this never makes a second taf. Best effort: the
 * chat is read whatever happens here.
 */
async function handOverShown(tenant: string, taf: Taf, components: ThreadComponent[], deps: Deps): Promise<void> {
  if (taf.job !== SETUP_JOB || !deps.coach) return;
  const runs = new Set(components.filter((c) => !c.answer && (c.component as { type?: string })?.type === "result").map((c) => c.run));
  for (const run of [...runs].slice(0, 3)) {
    await handOverOrKeep(tenant, taf.id, run, deps).catch((err) => console.error(`handover ${run}: ${(err as Error).message}`));
  }
}

/** What tafs cloud answers a result whose draft the owner hasn't confirmed yet. */
const WAITING_FOR_CONFIRMATION = "waiting for your confirmation";

/** How long a result's hand-over holds its card, so a repeat note or a second phone can't make two. */
const HANDOVER_HELD_MS = 10 * 60_000;

/**
 * Hands the owner over to the taf a Coach `result` waits on, with no phone
 * needed: the note only says which run to read back; what is
 * made comes from the run as kiff/agents keeps it. For each open result, once per
 * card: the taf is made for the person whose run it is, only while KIFF says they
 * may manage Cards now, from the draft they confirmed (kiff/agents claims it, and
 * KIFF gives no key for an unconfirmed one); then tafs cloud answers the card in its
 * own name. A refusal (4xx) is answered as failed, so Coach hands over again; a
 * server error isn't answered, and the next note or the sweep tries again.
 */
async function handOver(tenant: string, agent: string, run: string, deps: Deps): Promise<{ took: boolean; pending: boolean }> {
  const tafs = await deps.tafs.list(tenant);
  const coach = tafs.find((t) => t.job === SETUP_JOB && !t.removing);
  if (!coach || coach.id !== agent || !deps.coach) return { took: false, pending: false };
  const r = await deps.agents.getRun(tenant, coach.id, run);
  if (r.status !== "PAUSED") return { took: false, pending: false };
  let took = false;
  let pending = false;
  for (const q of r.requirements ?? []) {
    const card = q.input as { type?: string; id?: string; taf?: { agent?: string; name?: string; job?: string; draft?: string; improves?: string; unscheduled?: boolean } } | undefined;
    if (q.kind !== "card" || q.answered || card?.type !== "result" || !card.id || !card.taf) continue;
    // Improvements wait for their own credentials question; the app still makes those.
    if (card.taf.improves) continue;
    const held = `handover#${tenant}#${r.id}#${card.id}`;
    if (!(await deps.coach.once(held, new Date((deps.now?.() ?? new Date()).getTime() + HANDOVER_HELD_MS)))) continue;
    took = true;
    const outcome = await newTafFor(tenant, coach.id, r, card.taf, tafs, deps).catch(async (err) => {
      // Not told: it may still pass. The hold goes, and the sweep tries again.
      console.error(`handover ${r.id} ${card.id}: ${(err as Error).message}`);
      await deps.coach!.remove(held).catch(() => undefined);
      pending = true;
      return undefined;
    });
    if (!outcome) continue;
    await deps.agents.answerResult(tenant, coach.id, r.id, card.id, outcome).catch(async (err: unknown) => {
      // Answered meanwhile (another note, or an app from before this): nothing more to do.
      if (refused(err, "card_not_waiting")) return;
      // Not answered, as a failed make: the sweep tries again, and finds the taf made.
      console.error(`handover ${r.id} ${card.id} answer: ${(err as Error).message}`);
      await deps.coach!.remove(held).catch(() => undefined);
      pending = true;
    });
  }
  return { took, pending };
}

/** The door replies a provider didn't take, tried again by the sweep once their wait is over. */
export async function sweepReplies(deps: Deps): Promise<Array<{ ref: string; waiting: boolean }>> {
  if (!deps.doors) return [];
  return sweepDoorReplies(doorContext(deps));
}

/** Where the hand-overs a server error left undone wait for the sweep: one list for all accounts. */
const HANDOVERS_PENDING = "handovers#pending";
/** How long a hand-over is retried before the card is left to Coach's own wait. */
const HANDOVER_KEPT_MS = 7 * 24 * 3600_000;

interface PendingHandOver { tenant: string; agent: string; run: string; tries: number; tried: string }

/** handOver, and when a server error left some undone, kept for the sweep. */
async function handOverOrKeep(tenant: string, agent: string, run: string, deps: Deps): Promise<boolean> {
  const { took, pending } = await handOver(tenant, agent, run, deps);
  if (pending) await keepHandOver({ tenant, agent, run, tries: 0, tried: (deps.now?.() ?? new Date()).toISOString() }, deps);
  return took;
}

async function keepHandOver(p: PendingHandOver, deps: Deps): Promise<void> {
  const now = deps.now?.() ?? new Date();
  await deps.coach!.append(HANDOVERS_PENDING, p, new Date(now.getTime() + HANDOVER_KEPT_MS));
}

/**
 * The hand-overs a server error (tafs cloud's, kiff/agents', KIFF's) left undone, tried
 * again on the deletions' backoff, by the same schedule: each run once per sweep, kept
 * until it is done, its card no longer waits, or a week has passed.
 */
export async function sweepHandOvers(deps: Deps): Promise<Array<{ run: string; pending: boolean }>> {
  if (!deps.coach) return [];
  const now = deps.now?.() ?? new Date();
  const items = (await deps.coach.take(HANDOVERS_PENDING)) as PendingHandOver[];
  const seen = new Set<string>();
  const out: Array<{ run: string; pending: boolean }> = [];
  for (const p of items) {
    const key = `${p.tenant}#${p.run}`;
    if (seen.has(key)) continue;
    seen.add(key);
    if (now.getTime() - Date.parse(p.tried) < backoffMs(p.tries) && p.tries > 0) {
      await keepHandOver(p, deps);
      continue;
    }
    const { pending } = await handOver(p.tenant, p.agent, p.run, deps).catch((err) => {
      console.error(`handover sweep ${p.run}: ${(err as Error).message}`);
      return { took: false, pending: true };
    });
    if (pending) await keepHandOver({ ...p, tries: p.tries + 1, tried: now.toISOString() }, deps);
    out.push({ run: p.run, pending });
  }
  return out;
}

/**
 * The new taf a result hands over to, made for the run's requester: what to answer
 * the card with. Throws only what may pass on a retry (a server error).
 */
async function newTafFor(tenant: string, coach: string, r: Run, taf: { agent?: string; name?: string; job?: string; draft?: string; unscheduled?: boolean },
  tafs: Taf[], deps: Deps): Promise<ResultOutcome> {
  // Made already, by an app from before this or an earlier try: the same taf, never a second.
  const made = tafs.find((t) => t.kiffAgent && t.kiffAgent === taf.agent && !t.removing);
  if (made) return { taf_id: made.id };
  let access: { requester: Requester; cards_manage?: boolean };
  try {
    access = await deps.agents.requesterAccess(tenant, coach, r.id);
  } catch (err) {
    if (refused(err, "no_requester")) return { failed: "no person asked for this chat, so no one can be set up for" };
    // KIFF not answering is a blip, as any server error: not told, and the sweep tries again.
    throw err;
  }
  if (access.cards_manage === undefined) return { failed: "KIFF didn't say whether you may manage Cards now; try again" };
  if (!access.cards_manage) return { failed: "your role on this KIFF account can't manage Cards now" };
  // The person, as KIFF just answered for them: no session of theirs, and no right beyond that answer.
  const me: KIFFIdentity = { tenantID: tenant, subject: access.requester.id, apiKey: false, agentID: "", capabilities: ["cards.manage"] };
  try {
    const out = await makeTaf({ name: taf.name, job: taf.job, from_setup: true, draft_id: taf.draft, ...(taf.unscheduled ? { unscheduled: true } : {}) }, me, deps);
    return { taf_id: (out.taf as { id: string }).id };
  } catch (err) {
    if (err instanceof HTTPError && err.status >= 400 && err.status < 500) {
      // Made meanwhile by an app, under this draft: done, not failed.
      const now = (await deps.tafs.list(tenant)).find((t) => t.kiffAgent && t.kiffAgent === taf.agent && !t.removing);
      if (now) return { taf_id: now.id };
      return { failed: err.message };
    }
    throw err;
  }
}

/** A Coach task's run ended, said quietly: no sound or banner. Only how it ended, never the taf's words. */
function endedNotification(name: string, r: Run): Notification {
  const body = r.status === "ERROR" ? "It failed. Open Coach mode to see why."
    : r.status === "CANCELLED" ? "It was stopped."
    : "It finished. Open Coach mode to read how it went.";
  return {
    kind: "alert",
    payload: { aps: { alert: { title: name, body }, "thread-id": "coach", ...apsFor("quiet") }, open: "coach" },
    collapseID: r.id.slice(0, 64),
  };
}

/**
 * Tells the phones of the account's people who answer holds, best effort: a
 * phone Apple no longer knows is forgotten, and a failed push is only logged.
 */
async function tellPhones(deps: Deps, tenant: string, n: Notification): Promise<void> {
  try {
    const { devices } = await deps.push.get(tenant);
    await Promise.all(devices.filter((d) => d.answers).map(async (d) => {
      const r = await deps.apns.send(d, n).catch(() => "failed" as const);
      if (r === "gone") await deps.push.removeDevice(tenant, d.token);
    }));
  } catch (err) {
    console.error(`push: ${(err as Error).message}`);
  }
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
  const key = coachKeys.notes(me.tenantID, me.subject);
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
async function coachLines(me: KIFFIdentity, taf: Taf, deps: Deps): Promise<{ coach_lines?: Array<{ at: string; taf: string; said: string; away?: true }> }> {
  if (taf.job !== SETUP_JOB || !deps.coach) return {};
  const kept = (await deps.coach.get(coachKeys.notes(me.tenantID, me.subject))) as Array<{ at?: string; taf?: string; said?: string; away?: boolean }> | undefined;
  // Away: kept while the owner was away, so the app gathers it into "While you were away" (kiff/tafs#210).
  const lines = (kept ?? []).filter((l): l is { at: string; taf: string; said: string; away?: boolean } =>
    typeof l.at === "string" && typeof l.taf === "string" && typeof l.said === "string")
    .map(({ at, taf, said, away }) => ({ at, taf, said, ...(away === true ? { away: true as const } : {}) }));
  return lines.length ? { coach_lines: lines } : {};
}

/* A taf's doors (doors/): where work reaches it from outside the app. */

function doorContext(deps: Deps): DoorsContext {
  if (!deps.doors) throw new HTTPError(404, "not found");
  // A door's message starts no run on an account being deleted.
  const agents: DoorsContext["agents"] = {
    getRun: (...a) => deps.agents.getRun(...a),
    startRun: async (tenant, ...rest) => {
      if (deps.deletions && await deps.deletions.get(tenant)) throw new AgentsError(410, "this account is being deleted");
      return deps.agents.startRun(tenant, ...rest);
    },
  };
  return { doors: deps.doors, tafs: deps.tafs, agents, push: deps.push, apns: deps.apns, now: deps.now?.() ?? new Date() };
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
    const list = await listDoors(ctx, owner, taf);
    // Who wrote and what: only for the people who manage the taf.
    return await managesTaf(event, me, taf, deps) ? list : { ...list, knocks: [] };
  }
  await needTaf(event, me, taf, deps);
  const body = parseBody(event);
  if (parts.length === 4 && method === "POST") return addDoor(ctx, owner, taf, body);
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
