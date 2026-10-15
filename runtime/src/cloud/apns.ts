import { createPrivateKey, type KeyObject, sign } from "node:crypto";
import http2 from "node:http2";
import type { Device, Notification, Sender } from "./push.js";

const HOSTS = { production: "https://api.push.apple.com", sandbox: "https://api.sandbox.push.apple.com" } as const;
/** APNs accepts a provider token for an hour; a new one is made well before. */
const TOKEN_MINUTES = 50;

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
  /** The app's bundle id. */
  topic: string;
}

/** Sends through Apple's HTTP/2 push API, one connection per environment, kept while it lives. */
export function apnsSender(config: APNsConfig): Sender {
  let key: Promise<KeyObject> | undefined;
  let token: { value: string; at: number } | undefined;
  const sessions: Partial<Record<Device["environment"], http2.ClientHttp2Session>> = {};

  const bearer = async () => {
    key ??= config.keyPEM().then((pem) => createPrivateKey(pem));
    key.catch(() => { key = undefined; });
    const now = Date.now();
    if (!token || now - token.at > TOKEN_MINUTES * 60_000) {
      token = { value: providerToken(await key, config.keyID, config.teamID, new Date(now)), at: now };
    }
    return token.value;
  };

  const session = (env: Device["environment"]) => {
    const open = sessions[env];
    if (open && !open.closed && !open.destroyed) return open;
    const s = http2.connect(HOSTS[env]);
    s.on("error", () => { if (sessions[env] === s) delete sessions[env]; });
    s.on("close", () => { if (sessions[env] === s) delete sessions[env]; });
    s.unref();
    sessions[env] = s;
    return s;
  };

  return {
    async send(device, n) {
      const headers: http2.OutgoingHttpHeaders = {
        ":method": "POST",
        ":path": `/3/device/${device.token}`,
        authorization: `bearer ${await bearer()}`,
        "apns-topic": config.topic,
        "apns-push-type": n.kind,
        // A background push must go at priority 5.
        "apns-priority": n.kind === "alert" ? "10" : "5",
        "apns-expiration": String(n.expiresAt ? Math.floor(n.expiresAt.getTime() / 1000) : 0),
        "content-type": "application/json",
      };
      if (n.collapseID) headers["apns-collapse-id"] = n.collapseID;
      try {
        const { status, reason } = await request(session(device.environment), headers, JSON.stringify(n.payload));
        if (status === 200) return "sent";
        if (status === 410 || GONE.has(reason)) return "gone";
        console.error(`APNs answered ${status} ${reason}`);
        return "failed";
      } catch (err) {
        console.error(`APNs: ${(err as Error).message}`);
        return "failed";
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
