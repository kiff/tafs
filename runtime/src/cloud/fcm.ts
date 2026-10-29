import { createPrivateKey, sign } from "node:crypto";
import type { Device, Notification, Sender } from "./push.js";

/**
 * Alerts to an Android phone, through Firebase Cloud Messaging's HTTP v1 API. The same
 * notifications go to an iPhone through APNs (apns.ts); this turns one into a data
 * message, which the phone builds its notification from, so it controls how it shows.
 *
 * A message carries what an iPhone's alert carries, and no more: the words, where
 * tapping it goes, the call or request it is about, and how loud it is. No amount and no
 * argument ever goes through Google: the phone reads the held call from KIFF with its
 * own session.
 *
 *   kind        "alert" shows a notification; "answered" takes one away
 *   title,body  what an alert says
 *   open        where tapping it goes ("needs-you", "usage")
 *   hold_id, request_id, expires_at   what the alert is about
 *   level       "interrupt" (a hold, a halt) or "quiet" (no sound)
 */

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const FCM_HOST = "https://fcm.googleapis.com";
const TOKEN_URI = "https://oauth2.googleapis.com/token";
/** An access token is good for an hour; a new one is made before it ends. */
const REFRESH_BEFORE_SECONDS = 300;
const TIMEOUT_MS = 10_000;

/** The service account's key file, as Google issues it. */
export interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

export interface FCMConfig {
  /** The Firebase project's id. */
  projectId: string;
  /** The service account's key, read once (from Secrets Manager). */
  credentials: () => Promise<ServiceAccount>;
  /** Google's hosts; a test points them at its own. */
  host?: string;
  /** For a test: the clock and the network. */
  now?: () => Date;
  fetch?: typeof fetch;
}

/** FCM's reasons that mean a token will never work again. */
const GONE = new Set(["UNREGISTERED", "SENDER_ID_MISMATCH"]);

/** `data` values are strings, always. */
function str(v: unknown): string | undefined {
  return typeof v === "string" && v !== "" ? v : undefined;
}

/** How loud a notification is, as its APNs fields say it: a time-sensitive alert interrupts, anything else is quiet. */
function levelOf(aps: Record<string, unknown> | undefined): "interrupt" | "quiet" {
  return aps?.["interruption-level"] === "time-sensitive" ? "interrupt" : "quiet";
}

/**
 * The FCM message for a notification (without its token). An answer to a held call
 * (`answered`) takes the notification away; every other background push is one the
 * phone ignores.
 */
export function fcmMessage(n: Notification, now: Date): { data: Record<string, string>; android: Record<string, unknown> } {
  const p = n.payload;
  const aps = p.aps as Record<string, unknown> | undefined;
  const alert = aps?.alert as { title?: unknown; body?: unknown } | undefined;
  const data: Record<string, string> = {};
  const set = (k: string, v: string | undefined) => { if (v !== undefined) data[k] = v; };
  if (n.kind === "background") {
    data.kind = p.answered === true ? "answered" : "background";
    set("hold_id", str(p.hold_id));
  } else {
    data.kind = "alert";
    set("title", str(alert?.title));
    set("body", str(alert?.body));
    set("open", str(p.open));
    set("hold_id", str(p.hold_id));
    set("request_id", str(p.request_id));
    set("expires_at", str(p.expires_at));
    data.level = levelOf(aps);
  }
  const android: Record<string, unknown> = {
    // A hold and its answer reach the phone at once; the rest may wait for it to wake.
    priority: n.kind === "background" || data.level === "interrupt" ? "HIGH" : "NORMAL",
  };
  if (n.collapseID) android.collapse_key = n.collapseID;
  if (n.expiresAt) android.ttl = `${Math.max(0, Math.floor((n.expiresAt.getTime() - now.getTime()) / 1000))}s`;
  return { data, android };
}

/** Sends through Google's HTTP v1 API with a service account's short-lived access token. */
export function fcmSender(config: FCMConfig): Sender {
  const now = config.now ?? (() => new Date());
  const net = config.fetch ?? fetch;
  const host = config.host ?? FCM_HOST;
  let account: Promise<ServiceAccount> | undefined;
  let cached: { value: string; until: number } | undefined;
  let inFlight: Promise<string> | undefined;

  const accessToken = async (): Promise<string> => {
    const t = now().getTime();
    if (cached && cached.until - t > REFRESH_BEFORE_SECONDS * 1000) return cached.value;
    inFlight ??= (async () => {
      account ??= config.credentials();
      account.catch(() => { account = undefined; });
      const a = await account;
      const aud = a.token_uri ?? TOKEN_URI;
      const iat = Math.floor(now().getTime() / 1000);
      const b64 = (v: object) => Buffer.from(JSON.stringify(v)).toString("base64url");
      const unsigned = `${b64({ alg: "RS256", typ: "JWT" })}.${b64({ iss: a.client_email, scope: SCOPE, aud, iat, exp: iat + 3600 })}`;
      const signature = sign("RSA-SHA256", Buffer.from(unsigned), createPrivateKey(a.private_key)).toString("base64url");
      const res = await net(aud, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${signature}` }),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      const body = await res.json().catch(() => ({})) as { access_token?: string; expires_in?: number };
      if (!res.ok || !body.access_token) throw new Error(`Google's token endpoint answered ${res.status}`);
      cached = { value: body.access_token, until: now().getTime() + (body.expires_in ?? 3600) * 1000 };
      return body.access_token;
    })().finally(() => { inFlight = undefined; });
    return inFlight;
  };

  return {
    async send(device: Device, n: Notification) {
      let bearer: string;
      try { bearer = await accessToken(); } catch (err) {
        console.error(`FCM credentials: ${(err as Error).message}`);
        return "failed";
      }
      const { data, android } = fcmMessage(n, now());
      let res: Response;
      try {
        res = await net(`${host}/v1/projects/${encodeURIComponent(config.projectId)}/messages:send`, {
          method: "POST",
          headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
          body: JSON.stringify({ message: { token: device.token, data, android } }),
          signal: AbortSignal.timeout(TIMEOUT_MS),
        });
      } catch (err) {
        console.error(`FCM: ${(err as Error).message}`);
        return "failed";
      }
      if (res.ok) return "sent";
      const body = await res.json().catch(() => ({})) as { error?: { status?: string; message?: string; details?: { errorCode?: string }[] } };
      const code = body.error?.details?.find((d) => d.errorCode)?.errorCode ?? body.error?.status ?? "";
      // Google refused the access token: a new one is made for the next push.
      if (res.status === 401) cached = undefined;
      // A token Google no longer knows. INVALID_ARGUMENT is also what a malformed message answers, so only the one that
      // names the token counts: a bug in a message must never forget a phone.
      if (GONE.has(code) || (code === "INVALID_ARGUMENT" && /registration token/i.test(body.error?.message ?? ""))) return "gone";
      console.error(`FCM answered ${res.status} ${code}`);
      return "failed";
    },
  };
}
