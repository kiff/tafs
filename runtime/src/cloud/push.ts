import { createHmac, timingSafeEqual } from "node:crypto";
import { apsFor, levelOf } from "./levels.js";

/**
 * Alerts that reach a closed app: KIFF tells tafs cloud when a call is held
 * for the owner, and tafs cloud tells the owner's phones through Apple push.
 *
 *   1. The phone registers its account's alert target in KIFF (a signed
 *      setup call, so it needs Face ID), pointed at /v1/kiff/alerts. KIFF
 *      shows the target's signing secret once, to the phone.
 *   2. The phone hands tafs cloud its push token and that secret
 *      (POST /v1/push). The secret checks KIFF's deliveries; the token is
 *      where they go.
 *   3. KIFF posts each held call, signed (KIFF-Signature). tafs cloud checks
 *      the signature against the account's secrets and pushes to its phones:
 *      a visible alert when a call is held, a silent one when it is answered
 *      so the phone can clear that alert.
 *
 * An alert carries no request parameters (KIFF never sends them): the phone
 * reads the held call itself from KIFF, with its own session.
 */

/** Which push service a phone's token belongs to. */
export type Platform = "ios" | "android";

/** A phone that gets an account's alerts. */
export interface Device {
  /** The push token: APNs's, hex, for an iPhone; Firebase Cloud Messaging's registration token for an Android phone. */
  token: string;
  /** Which push service the token belongs to. A phone registered before Android is an iPhone. */
  platform?: Platform;
  /** Which APNs a build's token belongs to: Xcode builds sandbox, TestFlight and the App Store production. An Android phone is always production. */
  environment: "sandbox" | "production";
  /** The KIFF person who registered it. */
  subject: string;
  /** Whether that person may answer held calls (holds.answer): only their phones are alerted. */
  answers: boolean;
  /** Whether that person may add tasks (billing.manage): only their phones hear of a member's request. */
  addsTasks?: boolean;
  /** Whether that person may manage Cards (cards.manage): only their phones hear who wrote to a door. */
  manages?: boolean;
  /** The person turned off Sound for alerts on this phone: each alert arrives quietly (see `silencing`). */
  silent?: boolean;
  at: string;
}

export interface PushStore {
  /** An account's phones, and the signing secrets of the KIFF targets its phones registered. */
  get(tenant: string): Promise<{ devices: Device[]; secrets: string[] }>;
  addDevice(tenant: string, device: Device): Promise<void>;
  removeDevice(tenant: string, token: string): Promise<void>;
  /**
   * Keeps a target's secret. Several are kept (the newest few): two phones
   * setting up at once each get a secret, and KIFF keeps one of the targets,
   * whichever replaced the other last. Every secret here was issued by KIFF
   * for a target at this URL on this account, so any of them may sign.
   */
  addSecret(tenant: string, targetID: string, secret: string, at: string): Promise<void>;
}

/** What tafs cloud asks APNs to deliver. */
export interface Notification {
  /** "alert" shows; "background" wakes the app without showing anything. */
  kind: "alert" | "background";
  payload: Record<string, unknown>;
  /** One alert per held call: a later push with the same id replaces it. */
  collapseID?: string;
  /** APNs stops trying after this; a held call isn't worth showing once it has expired. */
  expiresAt?: Date;
}

export interface Sender {
  /** "gone": APNs says the token is no longer valid, so the phone is forgotten. */
  send(device: Device, n: Notification): Promise<"sent" | "gone" | "failed">;
}

/**
 * A sender that keeps the phones whose Sound for alerts is off quiet: an alert
 * still arrives, in Notification Center and the badge, with no sound, banner or
 * Time Sensitive. Every push goes through it, so no kind of alert is missed.
 */
export function silencing(sender: Sender): Sender {
  return { send: (device, n) => sender.send(device, quietFor(device, n)) };
}

/** `n` as it reaches `device`: as sent, or quiet where the phone asked for no sound. */
export function quietFor(device: Device, n: Notification): Notification {
  const aps = n.payload.aps as Record<string, unknown> | undefined;
  if (!device.silent || n.kind !== "alert" || !aps) return n;
  const { sound: _sound, ...rest } = aps;
  return { ...n, payload: { ...n.payload, aps: { ...rest, ...apsFor("quiet") } } };
}

/**
 * A sender that sends each phone's alert through its own push service: Apple's, or Google's for an Android phone. An
 * Android phone with no Google sender is never alerted ("failed", so KIFF tries again): registration refuses one there.
 */
export function routing(senders: { ios: Sender; android?: Sender }): Sender {
  return {
    send: (device, n) => device.platform === "android"
      ? (senders.android ? senders.android.send(device, n) : Promise.resolve("failed" as const))
      : senders.ios.send(device, n),
  };
}

export class PushError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

/** How far KIFF's signing time may be from ours: a replay older than this is refused. */
export const SIGNATURE_TOLERANCE_SECONDS = 300;
/** How long a delivered event is remembered per phone, so KIFF's retries don't alert it twice. */
export const DEDUPE_HOURS = 72;
/**
 * How long a phone's delivery is held while it is being sent. If this run dies
 * mid-send, the claim lapses and KIFF's retry sends it.
 */
export const IN_FLIGHT_SECONDS = 120;
/** Most phones an account keeps alerts for. */
export const MAX_DEVICES = 50;

/** KIFF's alert outcomes a phone acts on: a call held for its owner, and that call answered. */
const HELD = "owner_required";
const ANSWERED = "hold_answered";
/** A member asked the account's admins for more tasks. */
export const PACK_REQUESTED = "pack_requested";
/** An admin answered a member's request: approved (a pack bought), declined or withdrawn. */
export const PACK_REQUEST_ANSWERED = "pack_request_answered";
/** A vendor ended the sign-in planque held for a taf's connection: the owner signs in again. */
export const CONNECTION_SIGNED_OUT = "connection_signed_out";

/**
 * Checks `KIFF-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<body>")>`
 * against any of `secrets`, over the body exactly as received.
 */
export function signedByKIFF(header: string, body: string, secrets: string[], now: Date): boolean {
  let t = "";
  const v1: string[] = [];
  for (const part of header.split(",")) {
    const [k, v] = part.trim().split("=", 2);
    if (k === "t" && v) t = v;
    if (k === "v1" && v && /^[0-9a-f]{64}$/.test(v)) v1.push(v);
  }
  if (!/^\d{1,12}$/.test(t) || v1.length === 0) return false;
  if (Math.abs(now.getTime() / 1000 - Number(t)) > SIGNATURE_TOLERANCE_SECONDS) return false;
  for (const secret of secrets) {
    const want = createHmac("sha256", secret).update(`${t}.${body}`).digest();
    for (const got of v1) if (timingSafeEqual(want, Buffer.from(got, "hex"))) return true;
  }
  return false;
}

interface AlertBody {
  tenant_id?: unknown;
  event_id?: unknown;
  outcome?: unknown;
  action?: unknown;
  entity_id?: unknown;
  hold?: { id?: unknown; expires_at?: unknown } | null;
  /** On a pack request's alerts: the request, who asked (their sign-in subject) and an admin's note. */
  request?: { id?: unknown; pack?: unknown; status?: unknown; asked_by?: unknown; note?: unknown } | null;
  /** On a signed-out connection's alert: its grant, the taf's agent and the vendor's name for people. */
  connection?: { grant?: unknown; agent?: unknown; vendor?: unknown } | null;
  /**
   * On a held call's alerts, when KIFF names them: the people to tell, by sign-in
   * subject. KIFF leaves out whoever asked for the call, who may never answer it.
   */
  notify?: unknown;
}

/** The subjects KIFF names in an alert's notify, or undefined when it names none. */
function named(notify: unknown): Set<string> | undefined {
  if (!Array.isArray(notify)) return undefined;
  return new Set(notify.filter((s): s is string => typeof s === "string" && s !== ""));
}

export interface AlertDeps {
  push: PushStore;
  apns: Sender;
  /** Claims a key until a time; false while someone else holds it unexpired. */
  claim(key: string, until: Date): Promise<boolean>;
  /** Whether a key is held and unexpired. */
  held(key: string): Promise<boolean>;
  /** Holds a claimed key until a later time. */
  keep(key: string, until: Date): Promise<void>;
  release(key: string): Promise<void>;
  now: Date;
  /** The taf a KIFF agent runs, by its agent id: its id and name, for a signed-out connection's alert. */
  tafOf?(tenant: string, agent: string): Promise<{ id: string; name: string } | undefined>;
}

/**
 * Handles one delivery from KIFF. Unsigned or wrongly signed deliveries are
 * refused (401) before anything is sent. Each phone's delivery of an event has
 * two states, kept apart: delivered (72 hours: a retry never alerts it again)
 * and being sent (a 2-minute lease). Only when every phone is delivered is the
 * answer 200. A failed push answers 502 and a lease still running answers 503,
 * so KIFF keeps its retry; its next one (1, 5, 15 minutes…) comes after a lease
 * from a run that died has lapsed.
 */
export async function receiveAlert(signature: string, raw: string, deps: AlertDeps): Promise<Record<string, unknown>> {
  let body: AlertBody;
  try { body = JSON.parse(raw) as AlertBody; } catch { throw new PushError(400, "send KIFF's alert as JSON"); }
  const tenant = typeof body.tenant_id === "string" ? body.tenant_id : "";
  if (!tenant || !signature) throw new PushError(401, "not signed by KIFF");
  const { devices, secrets } = await deps.push.get(tenant);
  if (!signedByKIFF(signature, raw, secrets, deps.now)) throw new PushError(401, "not signed by KIFF");

  const outcome = typeof body.outcome === "string" ? body.outcome : "";
  if (outcome !== HELD && outcome !== ANSWERED && outcome !== PACK_REQUESTED && outcome !== PACK_REQUEST_ANSWERED && outcome !== CONNECTION_SIGNED_OUT) return { ignored: outcome };
  const eventID = typeof body.event_id === "string" ? body.event_id : "";
  let n: Notification;
  let to: (d: Device) => boolean;
  if (outcome === CONNECTION_SIGNED_OUT) {
    const c = body.connection;
    const grant = typeof c?.grant === "string" ? c.grant : "";
    const agent = typeof c?.agent === "string" ? c.agent : "";
    if (!eventID || !grant || !agent) throw new PushError(400, "a signed-out connection names its event, its grant and its agent");
    const taf = deps.tafOf ? await deps.tafOf(tenant, agent) : undefined;
    // An agent that no taf runs (removed since): nobody to tell.
    if (!taf) return { ignored: `${outcome}: no taf runs ${agent}` };
    n = signedOutNotification(taf, typeof c?.vendor === "string" ? c.vendor : "", grant);
    // The people who may answer for the account's tafs: they sign in again.
    to = (d) => d.answers;
  } else if (outcome === PACK_REQUEST_ANSWERED) {
    const r = body.request;
    const requestID = typeof r?.id === "string" ? r.id : "";
    const askedBy = typeof r?.asked_by === "string" ? r.asked_by : "";
    if (!eventID || !requestID || !askedBy) throw new PushError(400, "an answer names its event, its request and who asked");
    const answer = answerNotification(requestID, r?.status, r?.pack, r?.note);
    if (!answer) return { ignored: `${outcome}: ${String(r?.status)}` };
    n = answer;
    // Only the member who asked hears the answer.
    to = (d) => d.subject === askedBy;
  } else if (outcome === PACK_REQUESTED) {
    const requestID = typeof body.entity_id === "string" ? body.entity_id : "";
    if (!eventID || !requestID) throw new PushError(400, "an alert names its event and its request");
    n = requestNotification(requestID);
    // Only phones of people who may add tasks: they answer it.
    to = (d) => d.addsTasks === true;
  } else {
    const holdID = typeof body.hold?.id === "string" ? body.hold.id : "";
    if (!eventID || !holdID) throw new PushError(400, "an alert names its event and its held call");
    n = outcome === HELD ? heldNotification(body, holdID, deps.now) : answeredNotification(holdID);
    // The people KIFF names, when it names them: it decides who may answer, and
    // leaves out whoever asked. Otherwise, as before, the phones of people who may answer.
    const people = named(body.notify);
    to = people ? (d) => people.has(d.subject) : (d) => d.answers;
  }
  const inFlight = new Date(deps.now.getTime() + IN_FLIGHT_SECONDS * 1000);
  const done = new Date(deps.now.getTime() + DEDUPE_HOURS * 3600_000);
  let sent = 0, failed = 0, sending = 0, already = 0;
  for (const device of devices.filter(to)) {
    const delivered = `alert#${tenant}#${eventID}#${device.token}`;
    const key = `sending#${tenant}#${eventID}#${device.token}`;
    if (await deps.held(delivered)) { already++; continue; }
    if (!(await deps.claim(key, inFlight))) { sending++; continue; }
    let r: Awaited<ReturnType<Sender["send"]>>;
    try { r = await deps.apns.send(device, n); } catch (err) {
      console.error(`push: ${(err as Error).message}`);
      r = "failed";
    }
    if (r === "failed") {
      await deps.release(key);
      failed++;
      continue;
    }
    if (r === "gone") await deps.push.removeDevice(tenant, device.token);
    else sent++;
    await deps.keep(delivered, done);
    await deps.release(key);
  }
  if (failed > 0) throw new PushError(502, `Apple push didn't take the alert for ${failed} phone${failed === 1 ? "" : "s"}; try again`);
  if (sending > 0) throw new PushError(503, `the alert is still being sent to ${sending} phone${sending === 1 ? "" : "s"}; try again`);
  return already > 0 && sent === 0 ? { duplicate: true } : { sent };
}

function heldNotification(body: AlertBody, holdID: string, now: Date): Notification {
  const action = typeof body.action === "string" && body.action ? body.action.replace(/[_.]+/g, " ") : "A call";
  const expires = typeof body.hold?.expires_at === "string" ? new Date(body.hold.expires_at) : undefined;
  return {
    kind: "alert",
    payload: {
      aps: {
        alert: { title: "A taf needs you", body: `${capitalize(action)} waits for your answer. Nothing is sent until you approve.` },
        "thread-id": "held",
        // Approve and Refuse on the notification (kiff/tafs#210, part 2).
        category: "hold",
        // The phone fills in the taf, the amount and the deadline, read from KIFF
        // with its own session: KIFF never sends a call's parameters through Apple.
        "mutable-content": 1,
        ...apsFor(levelOf("hold", false)),
      },
      open: "needs-you",
      hold_id: holdID,
      ...(expires && !Number.isNaN(expires.getTime()) ? { expires_at: expires.toISOString() } : {}),
    },
    collapseID: holdID.slice(0, 64),
    expiresAt: expires && !Number.isNaN(expires.getTime()) && expires > now ? expires : undefined,
  };
}

/** Most of a vendor's or a taf's name an alert shows. */
const MAX_NAME = 60;

/**
 * "Stripe signed Refunds out · sign in again": tapping it opens the taf, where its Card tab offers
 * Sign in again. Neither the reason nor the grant's tools are said: only who, and what to do.
 * It sounds, but isn't Time Sensitive: nothing waits on a deadline, the taf just can't use that vendor.
 */
function signedOutNotification(taf: { id: string; name: string }, vendor: string, grant: string): Notification {
  const clip = (s: string) => { const t = s.replace(/\s+/g, " ").trim(); return t.length > MAX_NAME ? t.slice(0, MAX_NAME - 1) + "…" : t; };
  const who = clip(vendor) || "A provider";
  return {
    kind: "alert",
    payload: {
      aps: { alert: { title: clip(taf.name), body: `${who} signed ${clip(taf.name)} out · sign in again` }, "thread-id": "connections", sound: "default" },
      open: "taf",
      taf_id: taf.id,
    },
    // One alert per connection: a later sign-out of the same one replaces it.
    collapseID: `out-${grant}`.slice(0, 64),
  };
}

function requestNotification(requestID: string): Notification {
  return {
    kind: "alert",
    payload: {
      aps: {
        alert: { title: "More tasks asked for", body: "Someone on your account asks you to add tasks. Open Usage to answer." },
        "thread-id": "tasks",
        ...apsFor(levelOf("tasks_requested", false)),
      },
      open: "usage",
      request_id: requestID,
    },
    collapseID: requestID.slice(0, 64),
  };
}

/** Most of an admin's note a push shows. */
export const MAX_NOTE = 180;

/**
 * The member's answer to their request: "Your admin added Rayo" or "Your admin declined:
 * <note>". A withdrawn or expired request tells nobody (undefined).
 */
function answerNotification(requestID: string, status: unknown, pack: unknown, note: unknown): Notification | undefined {
  const name = typeof pack === "string" && pack ? capitalize(pack) : "tasks";
  let body: string;
  if (status === "approved") {
    body = `Your admin added ${name}.`;
  } else if (status === "declined") {
    const why = typeof note === "string" ? note.replace(/\s+/g, " ").trim() : "";
    body = why ? `Your admin declined: ${why.length > MAX_NOTE ? why.slice(0, MAX_NOTE - 1) + "…" : why}` : "Your admin declined your request for more tasks.";
  } else {
    return undefined;
  }
  return {
    kind: "alert",
    payload: {
      aps: { alert: { title: "Your request for tasks", body }, "thread-id": "tasks", ...apsFor(levelOf("request_answered", false)) },
      open: "usage",
      request_id: requestID,
    },
    collapseID: requestID.slice(0, 64),
  };
}

/** What changed on an account, for its phones to read again (docs/approvals.md, "Live updates"). */
export type Change = "taf" | "halted" | "resumed" | "knock";

/**
 * A silent push saying what changed: only its kind and the taf, never words or amounts. The phone
 * reads again what the kind names, with its own session, and trusts nothing from the push itself.
 */
export function changeNotification(change: Change, taf?: string): Notification {
  return { kind: "background", payload: { aps: { "content-available": 1 }, change, ...(taf ? { taf_id: taf } : {}) } };
}

function answeredNotification(holdID: string): Notification {
  return { kind: "background", payload: { aps: { "content-available": 1 }, hold_id: holdID, answered: true } };
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * Validates a phone's registration: `{device_token, platform?, environment, sound?, alert_target?: {id, signing_secret}}`.
 * `platform` is "ios" (the default) or "android". An iPhone's token is APNs's, in hex, with its environment; an Android
 * phone's is Firebase's registration token, case kept, and its environment is production (or left out).
 * `sound: false` is the phone's Sound for alerts turned off; left out, alerts sound as before.
 */
export function registration(body: Record<string, unknown>): {
  token: string;
  platform: Platform;
  environment: Device["environment"];
  silent: boolean;
  target?: { id: string; secret: string };
} {
  const platform = body.platform === undefined ? "ios" : body.platform;
  if (platform !== "ios" && platform !== "android") throw new PushError(400, "platform is ios or android");
  let token: string;
  let environment: Device["environment"];
  if (platform === "android") {
    token = typeof body.device_token === "string" ? body.device_token.trim() : "";
    if (!/^[A-Za-z0-9_:-]{20,4096}$/.test(token)) throw new PushError(400, "send the phone's Firebase registration token");
    if (body.environment !== undefined && body.environment !== "production") throw new PushError(400, "an Android phone's environment is production");
    environment = "production";
  } else {
    token = typeof body.device_token === "string" ? body.device_token.trim().toLowerCase() : "";
    if (!/^[0-9a-f]{64,200}$/.test(token)) throw new PushError(400, "send the phone's push token, in hex");
    if (body.environment !== "sandbox" && body.environment !== "production") throw new PushError(400, "environment is sandbox or production");
    environment = body.environment;
  }
  if (body.sound !== undefined && typeof body.sound !== "boolean") throw new PushError(400, "sound is true or false");
  const silent = body.sound === false;
  const t = body.alert_target;
  if (t === undefined) return { token, platform, environment, silent };
  return { token, platform, environment, silent, target: alertTarget(t) };
}

/** Validates KIFF's alert target as the phone hands it over: `{id, signing_secret}`. */
export function alertTarget(t: unknown): { id: string; secret: string } {
  const target = t as { id?: unknown; signing_secret?: unknown } | null;
  const id = typeof target?.id === "string" ? target.id.trim() : "";
  const secret = typeof target?.signing_secret === "string" ? target.signing_secret.trim() : "";
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(id) || !/^whsec_[A-Za-z0-9_-]{20,200}$/.test(secret)) {
    throw new PushError(400, "alert_target is KIFF's {id, signing_secret}");
  }
  return { id, secret };
}

/** Changes told to an account's phones in the last few seconds, by account, kind and taf: told once. */
const told = new Map<string, number>();
const TELL_EVERY_MS = 3000;

/**
 * A silent push to every phone of the account saying what changed (docs/approvals.md, "Live updates"):
 * each phone reads it again with its own session, so a person who may not see the taf learns only that
 * something changed on a taf of the account, never what.
 * Told at most once every few seconds per account, kind and taf. A push that fails is logged, never
 * in the way of the change itself.
 */
export async function tellChange(tenant: string, change: Change, taf: string | undefined, deps: { push: PushStore; apns: Sender }): Promise<void> {
  const key = `${tenant}|${change}|${taf ?? ""}`;
  const now = Date.now();
  if ((told.get(key) ?? 0) > now - TELL_EVERY_MS) return;
  told.set(key, now);
  if (told.size > 5000) told.delete(told.keys().next().value!);
  try {
    const { devices } = await deps.push.get(tenant);
    const n = changeNotification(change, taf);
    // Never in the way of the answer: the sends get a second and a half, then the request goes on.
    let timer: ReturnType<typeof setTimeout> | undefined;
    await Promise.race([
      Promise.all(devices.map((d) => deps.apns.send(d, n).catch(() => "failed"))),
      new Promise((done) => { timer = setTimeout(done, 1500); }),
    ]).finally(() => clearTimeout(timer));
  } catch (err) {
    console.error(`change ${change} not told: ${(err as Error).message}`);
  }
}
