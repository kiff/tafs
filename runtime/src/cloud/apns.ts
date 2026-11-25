import { createPrivateKey, type KeyObject, sign } from "node:crypto";
import http2 from "node:http2";
import type { Device, Notification, Sender } from "./push.js";

const HOSTS = { production: "https://api.push.apple.com", sandbox: "https://api.sandbox.push.apple.com" } as const;
/**
 * How long one provider token is used: Apple takes a token for an hour and answers 429 TooManyProviderTokenUpdates to a
 * key whose token changes more often than every 20 minutes. So one token is made for every tafs cloud instance at once
 * (`tokens`), used until it's this old, then replaced by one instance alone.
 */
export const TOKEN_MINUTES = 40;

/** A provider token as kept: the JWT, and when it was made (its `iat`, in milliseconds). */
export interface ProviderToken { value: string; at: number }

/**
 * Where every tafs cloud instance keeps the one provider token, so a new instance (a cold start, a deploy) reuses it
 * instead of minting its own: each instance minting its own was a token update per push, and Apple refused them all.
 */
export interface TokenStore {
  get(): Promise<ProviderToken | undefined>;
  /** Keeps `next` if the kept token is still `prev` (undefined: none kept); false when another instance kept one first. */
  swap(prev: ProviderToken | undefined, next: ProviderToken): Promise<boolean>;
}

/**
 * An APNs provider token: a JWT signed ES256 with the team's APNs key
 * ("tafs push"), `kid` the key's id and `iss` the team.
 */
export function providerToken(key: KeyObject, keyID: string, teamID: string, now: Date): string {
  const b64 = (v: object) => Buffer.from(JSON.stringify(v)).toString("base64url");
  const unsigned = `${b64({ alg: "ES256", kid: keyID })}.${b64({ iss: teamID, iat: Math.floor(now.getTime() / 1000) })}`;
  const sig = sign("sha256", Buffer.from(unsigned), { key, dsaEncoding: "ieee-p1363" });
  return `${unsigned}.${sig.toString("base64url")}`;
}

/** APNs reasons that mean the token will never work again. */
const GONE = new Set(["BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic", "ExpiredToken"]);

export interface APNsConfig {
  /** The .p8 key's PEM, read once (from Secrets Manager). */
  keyPEM: () => Promise<string>;
  keyID: string;
  teamID: string;
  /** The app's bundle id: the iPhone app's, which the iPad and the Mac 13+ app share. */
  topic: string;
  /**
   * macOS 12's own app's bundle id, for a device registered as `macos`. Unset, such a device is never sent to ("failed"):
   * a Mac's token on the iPhone's topic is `DeviceTokenNotForTopic`, which would drop the Mac as gone.
   */
  macTopic?: string;
  /** Apple's host for each environment; a test points both at its own server. */
  host?: string;
  /** The provider token every instance shares (TokenStore). Unset, this instance keeps its own (tests, a local run). */
  tokens?: TokenStore;
  /** The clock, in milliseconds; tests pass their own. */
  now?: () => number;
}

/** Sends through Apple's HTTP/2 push API, one connection per environment, kept while it lives. */
export function apnsSender(config: APNsConfig): Sender {
  let key: Promise<KeyObject> | undefined;
  let token: ProviderToken | undefined;
  const sessions: Partial<Record<Device["environment"], http2.ClientHttp2Session>> = {};
  /** Pushes waiting on each connection's answer. */
  const inFlight = new WeakMap<http2.ClientHttp2Session, number>();

  const now = config.now ?? Date.now;
  /** A token still to use: made less than TOKEN_MINUTES ago, and not ahead of this clock by more than a minute. */
  const fresh = (t: ProviderToken | undefined): t is ProviderToken =>
    t !== undefined && now() - t.at < TOKEN_MINUTES * 60_000 && t.at - now() < 60_000;
  /** One token at a time per instance: pushes sent together wait on the same one, never mint one each. */
  let pending: Promise<string> | undefined;

  const mint = async (): Promise<string> => {
    // Another instance's token first: a cold start or a deploy never makes one of its own while the shared one is good.
    const kept = config.tokens ? await config.tokens.get().catch(() => undefined) : undefined;
    if (fresh(kept)) {
      token = kept;
      return kept.value;
    }
    key ??= config.keyPEM().then((pem) => createPrivateKey(pem));
    key.catch(() => { key = undefined; });
    const at = now();
    const next = { value: providerToken(await key, config.keyID, config.teamID, new Date(at)), at };
    // Kept only if no other instance replaced the token meanwhile; else theirs is used, so Apple sees one update.
    if (config.tokens && !(await config.tokens.swap(kept, next).catch(() => true))) {
      const theirs = await config.tokens.get().catch(() => undefined);
      if (fresh(theirs)) {
        token = theirs;
        return theirs.value;
      }
    }
    token = next;
    return next.value;
  };

  const bearer = async (): Promise<string> => {
    if (fresh(token)) return token.value;
    pending ??= mint().finally(() => { pending = undefined; });
    return pending;
  };

  const session = (env: Device["environment"]) => {
    const open = sessions[env];
    if (open && !open.closed && !open.destroyed) return open;
    const s = http2.connect(config.host ?? HOSTS[env]);
    s.on("error", () => { if (sessions[env] === s) delete sessions[env]; });
    s.on("close", () => { if (sessions[env] === s) delete sessions[env]; });
    s.unref();
    sessions[env] = s;
    return s;
  };

  return {
    async send(device, n) {
      const topic = device.platform === "macos" ? config.macTopic : config.topic;
      if (!topic) return "failed";
      let auth: string;
      try { auth = await bearer(); } catch (err) {
        console.error(`APNs key: ${(err as Error).message}`);
        return "failed";
      }
      const headers: http2.OutgoingHttpHeaders = {
        ":method": "POST",
        ":path": `/3/device/${device.token}`,
        authorization: `bearer ${auth}`,
        "apns-topic": topic,
        "apns-push-type": n.kind,
        // A background push must go at priority 5.
        "apns-priority": n.kind === "alert" ? "10" : "5",
        "apns-expiration": String(n.expiresAt ? Math.floor(n.expiresAt.getTime() / 1000) : 0),
        "content-type": "application/json",
      };
      if (n.collapseID) headers["apns-collapse-id"] = n.collapseID;
      // The connection is unref'd so an idle one never holds the process. While
      // a push is in flight it must: in a Lambda nothing else may be pending,
      // and Node would exit before Apple answers (kiff/tafs#138, item 32).
      const s = session(device.environment);
      inFlight.set(s, (inFlight.get(s) ?? 0) + 1);
      s.ref();
      try {
        let { status, reason } = await request(s, headers, JSON.stringify(n.payload));
        // Apple refused this token as one update too many: another instance's is the one to use, once.
        if (status === 429 && reason === "TooManyProviderTokenUpdates" && config.tokens) {
          const kept = await config.tokens.get().catch(() => undefined);
          if (fresh(kept) && `bearer ${kept.value}` !== headers.authorization) {
            token = kept;
            ({ status, reason } = await request(s, { ...headers, authorization: `bearer ${kept.value}` }, JSON.stringify(n.payload)));
          }
        }
        if (status === 200) return "sent";
        if (status === 410 || GONE.has(reason)) return "gone";
        console.error(`APNs answered ${status} ${reason}`);
        return "failed";
      } catch (err) {
        console.error(`APNs: ${(err as Error).message}`);
        return "failed";
      } finally {
        const left = (inFlight.get(s) ?? 1) - 1;
        inFlight.set(s, left);
        if (left === 0 && !s.destroyed) s.unref();
      }
    },
  };
}

function request(s: http2.ClientHttp2Session, headers: http2.OutgoingHttpHeaders, body: string): Promise<{ status: number; reason: string }> {
  return new Promise((resolve, reject) => {
    const req = s.request(headers);
    req.setTimeout(10_000, () => req.close(http2.constants.NGHTTP2_CANCEL));
    let status = 0;
    let data = "";
    req.on("response", (h) => { status = Number(h[":status"]); });
    req.on("data", (chunk: Buffer) => { data += chunk.toString("utf8"); });
    req.on("end", () => {
      let reason = "";
      try { reason = (JSON.parse(data || "{}") as { reason?: string }).reason ?? ""; } catch { /* no body */ }
      resolve({ status, reason });
    });
    req.on("error", reject);
    req.on("close", () => { if (!status) reject(new Error("the request closed without an answer")); });
    req.end(body);
  });
}
