import { createHmac, timingSafeEqual } from "node:crypto";

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

/** A phone that gets an account's alerts. */
export interface Device {
  /** The APNs device token, hex. */
  token: string;
  /** Which APNs a build's token belongs to: Xcode builds sandbox, TestFlight and the App Store production. */
  environment: "sandbox" | "production";
  /** The KIFF person who registered it. */
  subject: string;
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

export class PushError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

/** How far KIFF's signing time may be from ours: a replay older than this is refused. */
export const SIGNATURE_TOLERANCE_SECONDS = 300;
/** How long a delivered event id is remembered, so KIFF's retries don't alert twice. */
export const DEDUPE_HOURS = 72;
/** Most phones an account keeps alerts for. */
export const MAX_DEVICES = 50;

/** KIFF's alert outcomes a phone acts on: a call held for its owner, and that call answered. */
const HELD = "owner_required";
const ANSWERED = "hold_answered";

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
  hold?: { id?: unknown; expires_at?: unknown } | null;
}

export interface AlertDeps {
  push: PushStore;
  apns: Sender;
  /** Remembers an event id until a time; false when it is already remembered. */
  remember(key: string, until: Date): Promise<boolean>;
  forget(key: string): Promise<void>;
  now: Date;
}

/**
 * Handles one delivery from KIFF. Unsigned or wrongly signed deliveries are
 * refused (401) before anything is sent. A delivery whose pushes all failed
 * answers 502, so KIFF retries it; a repeat of one already sent is a no-op.
 */
export async function receiveAlert(signature: string, raw: string, deps: AlertDeps): Promise<Record<string, unknown>> {
  let body: AlertBody;
  try { body = JSON.parse(raw) as AlertBody; } catch { throw new PushError(400, "send KIFF's alert as JSON"); }
  const tenant = typeof body.tenant_id === "string" ? body.tenant_id : "";
  if (!tenant || !signature) throw new PushError(401, "not signed by KIFF");
  const { devices, secrets } = await deps.push.get(tenant);
  if (!signedByKIFF(signature, raw, secrets, deps.now)) throw new PushError(401, "not signed by KIFF");

  const outcome = typeof body.outcome === "string" ? body.outcome : "";
  if (outcome !== HELD && outcome !== ANSWERED) return { ignored: outcome };
  const eventID = typeof body.event_id === "string" ? body.event_id : "";
  const holdID = typeof body.hold?.id === "string" ? body.hold.id : "";
  if (!eventID || !holdID) throw new PushError(400, "an alert names its event and its held call");
  if (devices.length === 0) return { sent: 0 };

  const key = `alert#${tenant}#${eventID}`;
  if (!(await deps.remember(key, new Date(deps.now.getTime() + DEDUPE_HOURS * 3600_000)))) return { duplicate: true };

  const n = outcome === HELD ? heldNotification(body, holdID, deps.now) : answeredNotification(holdID);
  let sent = 0, failed = 0;
  for (const device of devices) {
    const r = await deps.apns.send(device, n);
    if (r === "sent") sent++;
    else if (r === "gone") await deps.push.removeDevice(tenant, device.token);
    else failed++;
  }
  if (sent === 0 && failed > 0) {
    // Nothing reached a phone and APNs may take it later: KIFF tries again.
    await deps.forget(key);
    throw new PushError(502, "Apple push didn't take the alert; try again");
  }
  return { sent };
}

function heldNotification(body: AlertBody, holdID: string, now: Date): Notification {
  const action = typeof body.action === "string" && body.action ? body.action.replace(/[_.]+/g, " ") : "A call";
  const expires = typeof body.hold?.expires_at === "string" ? new Date(body.hold.expires_at) : undefined;
  return {
    kind: "alert",
    payload: {
      aps: {
        alert: { title: "A taf needs you", body: `${capitalize(action)} waits for your answer.` },
        sound: "default",
        "thread-id": "held",
        "interruption-level": "active",
      },
      open: "needs-you",
      hold_id: holdID,
    },
    collapseID: holdID.slice(0, 64),
    expiresAt: expires && !Number.isNaN(expires.getTime()) && expires > now ? expires : undefined,
  };
}

function answeredNotification(holdID: string): Notification {
  return { kind: "background", payload: { aps: { "content-available": 1 }, hold_id: holdID, answered: true } };
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Validates a phone's registration: `{device_token, environment, alert_target?: {id, signing_secret}}`. */
export function registration(body: Record<string, unknown>): {
  token: string;
  environment: Device["environment"];
  target?: { id: string; secret: string };
} {
  const token = typeof body.device_token === "string" ? body.device_token.trim().toLowerCase() : "";
  if (!/^[0-9a-f]{64,200}$/.test(token)) throw new PushError(400, "send the phone's push token, in hex");
  const environment = body.environment;
  if (environment !== "sandbox" && environment !== "production") throw new PushError(400, "environment is sandbox or production");
  const t = body.alert_target;
  if (t === undefined) return { token, environment };
  const target = t as { id?: unknown; signing_secret?: unknown } | null;
  const id = typeof target?.id === "string" ? target.id.trim() : "";
  const secret = typeof target?.signing_secret === "string" ? target.signing_secret.trim() : "";
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(id) || !/^whsec_[A-Za-z0-9_-]{20,200}$/.test(secret)) {
    throw new PushError(400, "alert_target is KIFF's {id, signing_secret}");
  }
  return { token, environment, target: { id, secret } };
}
