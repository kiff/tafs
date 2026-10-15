import { createHmac, generateKeyPairSync, verify } from "node:crypto";
import { describe, expect, it } from "vitest";
import type Anthropic from "@anthropic-ai/sdk";
import { providerToken } from "../src/cloud/apns.js";
import { handle, type HTTPEvent, type Slots } from "../src/cloud/handler.js";
import { type Device, type Notification, type PushStore, signedByKIFF } from "../src/cloud/push.js";

const NOW = new Date("2026-10-15T09:00:00Z");
const T = Math.floor(NOW.getTime() / 1000);
const SECRET = "whsec_" + "a".repeat(43);
const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const VIEWER = { ...ME, subject: "u_vic", capabilities: ["read"] };
const TOKEN_A = "a".repeat(64);
const TOKEN_B = "b".repeat(64);

function sig(body: string, secret = SECRET, t = T): string {
  return `t=${t},v1=${createHmac("sha256", secret).update(`${t}.${body}`).digest("hex")}`;
}

function memoryPush(): PushStore & { devices: Map<string, Device[]>; secrets: Map<string, string[]> } {
  const devices = new Map<string, Device[]>();
  const secrets = new Map<string, string[]>();
  return {
    devices, secrets,
    async get(t) { return { devices: [...(devices.get(t) ?? [])], secrets: [...(secrets.get(t) ?? [])] }; },
    async addDevice(t, d) { devices.set(t, [...(devices.get(t) ?? []).filter((x) => x.token !== d.token), d]); },
    async removeDevice(t, token) { devices.set(t, (devices.get(t) ?? []).filter((x) => x.token !== token)); },
    async addSecret(t, _id, s) { secrets.set(t, [s, ...(secrets.get(t) ?? [])]); },
  };
}

function memorySlots(): Slots & { held: Set<string> } {
  const held = new Set<string>();
  return { held, async claim(k) { if (held.has(k)) return false; held.add(k); return true; }, async release(k) { held.delete(k); } };
}

/** APNs that records what it was asked, answering per token. */
function fakeAPNs(answer: (token: string) => "sent" | "gone" | "failed" = () => "sent") {
  const sent: Array<{ token: string; n: Notification }> = [];
  return { sent, send: async (d: Device, n: Notification) => { sent.push({ token: d.token, n }); return answer(d.token); } };
}

function kiffMe(byToken: Record<string, object>) {
  return (async (_url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

function call(method: string, path: string, opts: { body?: string; headers?: Record<string, string>; push: ReturnType<typeof memoryPush>; apns?: ReturnType<typeof fakeAPNs>; slots?: ReturnType<typeof memorySlots> }) {
  const event: HTTPEvent = { rawPath: path, requestContext: { http: { method } }, headers: opts.headers ?? {}, body: opts.body };
  return handle(event, {
    anthropic: async () => { throw new Error("alerts never need Anthropic"); },
    setup: async () => { throw new Error("no setup"); },
    names: { claim: async () => true, release: async () => undefined },
    slots: opts.slots ?? memorySlots(),
    claims: { get: async () => undefined, put: async () => undefined, remove: async () => undefined },
    push: opts.push,
    apns: opts.apns ?? fakeAPNs(),
    fetch: kiffMe({ ana: ME, viewer: VIEWER }),
    now: () => NOW,
  } as never).then((r) => ({ status: r.statusCode, body: JSON.parse(r.body) as Record<string, unknown> }));
}

const held = (over: object = {}) => JSON.stringify({
  schema_version: "1", type: "decision", tenant_id: "t_acme", action: "stripe_refund", entity_id: "op_1",
  outcome: "owner_required", hold: { id: "hold_1", status: "pending", expires_at: "2026-10-15T09:10:00Z" },
  decided_at: "2026-10-15T09:00:00Z", event_id: "evt_1", ...over,
});

function account() {
  const push = memoryPush();
  push.secrets.set("t_acme", [SECRET]);
  push.devices.set("t_acme", [
    { token: TOKEN_A, environment: "production", subject: "u_ana", at: "" },
    { token: TOKEN_B, environment: "sandbox", subject: "u_vic", at: "" },
  ]);
  return push;
}

const alert = (body: string, push: ReturnType<typeof memoryPush>, extra: { apns?: ReturnType<typeof fakeAPNs>; slots?: ReturnType<typeof memorySlots>; signature?: string } = {}) =>
  call("POST", "/v1/kiff/alerts", { body, headers: { "KIFF-Signature": extra.signature ?? sig(body) }, push, apns: extra.apns, slots: extra.slots });

describe("KIFF's signature", () => {
  const body = held();
  it("is HMAC-SHA256 of t.body with the target's secret, within five minutes", () => {
    expect(signedByKIFF(sig(body), body, [SECRET], NOW)).toBe(true);
    expect(signedByKIFF(sig(body), body, ["whsec_other", SECRET], NOW)).toBe(true);
    expect(signedByKIFF(sig(body, "whsec_other"), body, [SECRET], NOW)).toBe(false);
    expect(signedByKIFF(sig(body), body.replace("stripe_refund", "stripe_payout"), [SECRET], NOW)).toBe(false);
    expect(signedByKIFF(sig(body, SECRET, T - 301), body, [SECRET], NOW)).toBe(false);
    expect(signedByKIFF(sig(body, SECRET, T + 301), body, [SECRET], NOW)).toBe(false);
    expect(signedByKIFF("", body, [SECRET], NOW)).toBe(false);
    expect(signedByKIFF(`t=${T},v1=zz`, body, [SECRET], NOW)).toBe(false);
    expect(signedByKIFF(sig(body), body, [], NOW)).toBe(false);
  });
});

describe("KIFF's alerts", () => {
  it("pushes a held call to every phone on the account, one alert per hold, gone when it expires", async () => {
    const push = account();
    const apns = fakeAPNs();
    const r = await alert(held(), push, { apns });
    expect(r).toEqual({ status: 200, body: { sent: 2 } });
    expect(apns.sent.map((s) => s.token)).toEqual([TOKEN_A, TOKEN_B]);
    const n = apns.sent[0]!.n;
    expect(n.kind).toBe("alert");
    expect(n.collapseID).toBe("hold_1");
    expect(n.expiresAt?.toISOString()).toBe("2026-10-15T09:10:00.000Z");
    expect(n.payload).toMatchObject({ aps: { alert: { title: "A taf needs you", body: "Stripe refund waits for your answer." } }, open: "needs-you", hold_id: "hold_1" });
  });

  it("refuses anything KIFF didn't sign, before sending a thing", async () => {
    const push = account();
    const apns = fakeAPNs();
    expect((await alert(held(), push, { apns, signature: sig(held(), "whsec_" + "b".repeat(43)) })).status).toBe(401);
    expect((await alert(held(), push, { apns, signature: "" })).status).toBe(401);
    expect((await alert(held(), push, { apns, signature: sig(held(), SECRET, T - 3600) })).status).toBe(401);
    // Signed for one account, posted as another's: that account's secrets don't match.
    const other = held({ tenant_id: "t_other" });
    expect((await alert(other, push, { apns, signature: sig(other) })).status).toBe(401);
    expect((await alert("not json", push, { apns })).status).toBe(400);
    expect(apns.sent).toEqual([]);
  });

  it("alerts once however often KIFF retries the same event", async () => {
    const push = account();
    const apns = fakeAPNs();
    const slots = memorySlots();
    await alert(held(), push, { apns, slots });
    expect((await alert(held(), push, { apns, slots })).body).toEqual({ duplicate: true });
    expect(apns.sent).toHaveLength(2);
  });

  it("answers 502 when no phone got it, so KIFF tries again, and then sends", async () => {
    const push = account();
    const slots = memorySlots();
    let down = true;
    const apns = fakeAPNs(() => (down ? "failed" : "sent"));
    expect((await alert(held(), push, { apns, slots })).status).toBe(502);
    down = false;
    expect((await alert(held(), push, { apns, slots })).body).toEqual({ sent: 2 });
  });

  it("forgets a phone Apple says is gone", async () => {
    const push = account();
    const apns = fakeAPNs((t) => (t === TOKEN_B ? "gone" : "sent"));
    expect((await alert(held(), push, { apns })).body).toEqual({ sent: 1 });
    expect(push.devices.get("t_acme")!.map((d) => d.token)).toEqual([TOKEN_A]);
  });

  it("wakes the phone silently when the call is answered, and ignores other outcomes", async () => {
    const push = account();
    const apns = fakeAPNs();
    const answered = held({ outcome: "hold_answered", event_id: "evt_2" });
    expect((await alert(answered, push, { apns })).body).toEqual({ sent: 2 });
    expect(apns.sent[0]!.n).toMatchObject({ kind: "background", payload: { aps: { "content-available": 1 }, hold_id: "hold_1", answered: true } });
    const blocked = held({ outcome: "blocked", event_id: "evt_3" });
    expect((await alert(blocked, push, { apns })).body).toEqual({ ignored: "blocked" });
    expect(apns.sent).toHaveLength(2);
  });
});

describe("a phone signing up for alerts", () => {
  const signedIn = (who: string) => ({ Authorization: `Bearer ${who}` });
  const register = (who: string, body: object, push: ReturnType<typeof memoryPush>) =>
    call("POST", "/v1/push", { body: JSON.stringify(body), headers: signedIn(who), push });

  it("keeps the phone and KIFF's secret; the secret takes the right to manage Cards", async () => {
    const push = memoryPush();
    const target = { id: "at_1", signing_secret: SECRET };
    expect((await register("viewer", { device_token: TOKEN_B, environment: "production", alert_target: target }, push)).status).toBe(403);
    expect(push.secrets.get("t_acme")).toBeUndefined();
    expect((await register("viewer", { device_token: TOKEN_B, environment: "production" }, push)).body).toEqual({ device: true, alerts: false });
    expect((await register("ana", { device_token: TOKEN_A.toUpperCase(), environment: "sandbox", alert_target: target }, push)).body).toEqual({ device: true, alerts: true });
    expect(push.secrets.get("t_acme")).toEqual([SECRET]);
    expect(push.devices.get("t_acme")!.map((d) => [d.token, d.environment, d.subject])).toEqual([
      [TOKEN_B, "production", "u_vic"], [TOKEN_A, "sandbox", "u_ana"],
    ]);
  });

  it("refuses a token, environment or target that isn't one", async () => {
    const push = memoryPush();
    for (const body of [
      { device_token: "xyz", environment: "production" },
      { device_token: TOKEN_A, environment: "dev" },
      { device_token: TOKEN_A, environment: "production", alert_target: { id: "at_1", signing_secret: "nope" } },
      { device_token: TOKEN_A, environment: "production", alert_target: { id: "../x", signing_secret: SECRET } },
    ]) {
      expect((await register("ana", body, push)).status, JSON.stringify(body)).toBe(400);
    }
    expect((await call("POST", "/v1/push", { body: "{}", push })).status).toBe(401);
  });

  it("lets a person remove their own phone, and a manager any phone", async () => {
    const push = account();
    const remove = (who: string, token: string) => call("DELETE", `/v1/push/${token}`, { headers: signedIn(who), push });
    expect((await remove("viewer", TOKEN_A)).body).toEqual({ removed: false });
    expect((await remove("viewer", TOKEN_B)).body).toEqual({ removed: true });
    expect((await remove("ana", TOKEN_A)).body).toEqual({ removed: true });
    expect(push.devices.get("t_acme")).toEqual([]);
  });
});

describe("the APNs provider token", () => {
  it("is an ES256 JWT naming the key and the team, that the key's public half verifies", () => {
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
    const jwt = providerToken(privateKey, "3THAT9GWCS", "R54YWFRK4B", NOW);
    const [h, c, s] = jwt.split(".");
    expect(JSON.parse(Buffer.from(h!, "base64url").toString())).toEqual({ alg: "ES256", kid: "3THAT9GWCS" });
    expect(JSON.parse(Buffer.from(c!, "base64url").toString())).toEqual({ iss: "R54YWFRK4B", iat: T });
    expect(verify("sha256", Buffer.from(`${h}.${c}`), { key: publicKey, dsaEncoding: "ieee-p1363" }, Buffer.from(s!, "base64url"))).toBe(true);
  });
});
