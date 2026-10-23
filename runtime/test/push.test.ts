import { createHmac, generateKeyPairSync, verify } from "node:crypto";
import { describe, expect, it } from "vitest";
import { AgentsError } from "../src/cloud/agents.js";
import { providerToken } from "../src/cloud/apns.js";
import type { ReadyRun } from "../src/cloud/forward.js";
import { FORWARD_TRIES, handle, type HTTPEvent, type Slots } from "../src/cloud/handler.js";
import { type Device, type Notification, type PushStore, signedByKIFF } from "../src/cloud/push.js";

const NOW = new Date("2026-10-15T09:00:00Z");
const T = Math.floor(NOW.getTime() / 1000);
const SECRET = "whsec_" + "a".repeat(43);
const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage", "holds.answer"] };
const VIEWER = { ...ME, subject: "u_vic", capabilities: ["read"] };
const TOKEN_A = "a".repeat(64);
const TOKEN_B = "b".repeat(64);
const TOKEN_C = "c".repeat(64);

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

/** Claims with expiry, like the DynamoDB conditional put; `clock` is what "now" is to it. */
function memorySlots(clock: () => Date = () => NOW): Slots & { held: Map<string, Date> } {
  const held = new Map<string, Date>();
  return {
    held,
    async claim(k, until) { const h = held.get(k); if (h && h > clock()) return false; held.set(k, until); return true; },
    async keep(k, until) { held.set(k, until); },
    async release(k) { held.delete(k); },
    async held(k) { const h = held.get(k); return Boolean(h && h > clock()); },
  };
}

/** APNs that records what it was asked, answering per token. */
function fakeAPNs(answer: (token: string) => "sent" | "gone" | "failed" | "throw" = () => "sent") {
  const sent: Array<{ token: string; n: Notification }> = [];
  return {
    sent,
    send: async (d: Device, n: Notification) => {
      const a = answer(d.token);
      if (a === "throw") throw new Error("Secrets Manager is down");
      sent.push({ token: d.token, n });
      return a;
    },
  };
}

function kiffMe(byToken: Record<string, object>) {
  return (async (_url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

/** kiff/agents' alerts function, as tafs cloud hands alerts to it: what it got, or a failure. */
function fakeForward(order: string[] = []) {
  const f = {
    got: [] as Array<{ signature: string; raw: string }>,
    down: false,
    tries: 0,
    /** The run kiff/agents names: one the answer left ready. */
    ready: undefined as ReadyRun | undefined,
    forward: async (signature: string, raw: string) => {
      f.tries++;
      order.push("forward");
      if (f.down) throw new Error("kiff/agents answered 503");
      f.got.push({ signature, raw });
      return f.ready;
    },
  };
  return f;
}

/** kiff/agents as the alert path uses it: continuing a run, or refusing to (409). */
function fakeContinue(order: string[] = [], status = 202) {
  const c = {
    got: [] as string[],
    agents: {
      continueRun: async (tenant: string, agent: string, run: string) => {
        order.push("continue");
        c.got.push(`${tenant} ${agent} ${run}`);
        if (status !== 202) throw new AgentsError(status, "the run isn't paused: read it");
        return {};
      },
    },
  };
  return c;
}

function call(method: string, path: string, opts: { body?: string; headers?: Record<string, string>; push: ReturnType<typeof memoryPush>; apns?: ReturnType<typeof fakeAPNs>; slots?: ReturnType<typeof memorySlots>; now?: Date; forward?: (s: string, r: string) => Promise<ReadyRun | void>; accounts?: Map<string, { tenant: string; secret: string }>; agents?: object }) {
  const event: HTTPEvent = { rawPath: path, requestContext: { http: { method } }, headers: opts.headers ?? {}, body: opts.body };
  const accounts = opts.accounts ?? new Map();
  return handle(event, {
    agents: opts.agents ?? new Proxy({}, { get: () => { throw new Error("alerts and phones never need kiff/agents"); } }),
    forward: opts.forward ?? (async () => undefined),
    accounts: { put: async (account: string, tenant: string, secret: string) => { accounts.set(account, { tenant, secret }); } },
    names: { claim: async () => true, release: async () => undefined },
    slots: opts.slots ?? memorySlots(),
    claims: { get: async () => undefined, put: async () => undefined, remove: async () => undefined },
    push: opts.push,
    apns: opts.apns ?? fakeAPNs(),
    fetch: kiffMe({ ana: ME, viewer: VIEWER }),
    now: () => opts.now ?? NOW,
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
    { token: TOKEN_A, environment: "production", subject: "u_ana", answers: true, at: "" },
    { token: TOKEN_B, environment: "sandbox", subject: "u_bo", answers: true, at: "" },
    { token: TOKEN_C, environment: "production", subject: "u_vic", answers: false, at: "" },
  ]);
  return push;
}

const alert = (body: string, push: ReturnType<typeof memoryPush>, extra: { apns?: ReturnType<typeof fakeAPNs>; slots?: ReturnType<typeof memorySlots>; signature?: string; now?: Date; forward?: ReturnType<typeof fakeForward>; accounts?: Map<string, { tenant: string; secret: string }>; agents?: object } = {}) =>
  call("POST", "/v1/kiff/alerts", { body, headers: { "KIFF-Signature": extra.signature ?? sig(body) }, push, apns: extra.apns, slots: extra.slots, now: extra.now, forward: extra.forward?.forward, accounts: extra.accounts, agents: extra.agents });

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
  it("pushes a held call to the phones of people who may answer, one alert per hold, gone when it expires", async () => {
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

  it("hands the alert to kiff/agents as KIFF signed it, so the held run goes on", async () => {
    const push = account();
    const forward = fakeForward();
    const answered = held({ outcome: "hold_answered", hold: { id: "hold_1", status: "approved" } });
    expect((await alert(answered, push, { forward })).status).toBe(200);
    expect(forward.got).toEqual([{ signature: sig(answered), raw: answered }]);
  });

  // The owner's wait holds no push to Apple, and no sweep (kiff/tafs#138, item 32): the
  // answer goes to kiff/agents first, and the run it left ready is continued at once.
  it("hands the answer on before it pushes, and continues the run it left ready", async () => {
    const push = account();
    const order: string[] = [];
    const apns = fakeAPNs(() => { order.push("push"); return "sent"; });
    const forward = fakeForward(order);
    forward.ready = { id: "run_1", agent: "taf-1" };
    const agents = fakeContinue(order);
    const answered = held({ outcome: "hold_answered", hold: { id: "hold_1", status: "refused" } });
    expect((await alert(answered, push, { apns, forward, agents: agents.agents })).status).toBe(200);
    expect(agents.got).toEqual(["t_acme taf-1 run_1"]);
    expect(order).toEqual(["forward", "continue", "push", "push"]);
  });

  it("leaves the run to the sweep when kiff/agents names none, or when continuing it is refused", async () => {
    const push = account();
    const answered = held({ outcome: "hold_answered", hold: { id: "hold_1", status: "approved" } });
    const none = fakeContinue();
    expect((await alert(answered, push, { forward: fakeForward(), agents: none.agents })).status).toBe(200);
    expect(none.got).toEqual([]);
    for (const status of [409, 429, 503]) {
      const forward = fakeForward();
      forward.ready = { id: "run_1", agent: "taf-1" };
      const taken = fakeContinue([], status);
      expect((await alert(answered, account(), { forward, agents: taken.agents })).status).toBe(200);
      expect(taken.got).toHaveLength(1);
    }
  });

  it("still tells the phones when kiff/agents is down, then asks KIFF to send again", async () => {
    const push = account();
    const apns = fakeAPNs();
    const forward = fakeForward();
    forward.down = true;
    expect((await alert(held(), push, { apns, forward })).status).toBe(502);
    expect(apns.sent).toHaveLength(2);
  });

  it("gives kiff/agents the secret that signed the alert before handing it on, for accounts registered before kiff/agents", async () => {
    const OLD = "whsec_" + "o".repeat(43);
    const push = account();
    push.secrets.set("t_acme", [SECRET, OLD]); // newest first: this alert comes from the older target
    const accounts = new Map<string, { tenant: string; secret: string }>();
    const forward = fakeForward();
    const r = await alert(held(), push, { forward, accounts, signature: sig(held(), OLD) });
    expect(r.status).toBe(200);
    expect(accounts.get("t_acme")).toEqual({ tenant: "t_acme", secret: OLD });
    expect(forward.got).toHaveLength(1);
  });

  it("asks KIFF to send again when kiff/agents didn't get it, and tells the phones once", async () => {
    const push = account();
    const apns = fakeAPNs();
    const slots = memorySlots();
    const forward = fakeForward();
    forward.down = true;
    expect((await alert(held(), push, { apns, slots, forward })).status).toBe(502);
    forward.down = false;
    expect((await alert(held(), push, { apns, slots, forward })).body).toEqual({ duplicate: true });
    expect(apns.sent).toHaveLength(2);
    expect(forward.got).toHaveLength(1);
  });

  it("stops asking KIFF again for an alert kiff/agents keeps refusing, so the target and its pushes stay on", async () => {
    const push = account();
    const apns = fakeAPNs();
    const slots = memorySlots();
    const forward = fakeForward();
    forward.down = true;
    for (let i = 0; i < FORWARD_TRIES; i++) expect((await alert(held(), push, { apns, slots, forward })).status).toBe(502);
    expect((await alert(held(), push, { apns, slots, forward })).status).toBe(200);
    // What the give-up log says: kiff/agents was tried FORWARD_TRIES + 1 times.
    expect(forward.tries).toBe(FORWARD_TRIES + 1);
    expect(apns.sent).toHaveLength(2); // the phones were told once, on the first delivery
  });

  it("hands kiff/agents nothing KIFF didn't sign", async () => {
    const push = account();
    const forward = fakeForward();
    expect((await alert(held(), push, { forward, signature: sig(held(), "whsec_" + "b".repeat(43)) })).status).toBe(401);
    expect(forward.got).toEqual([]);
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

  it("retries after the sender throws, as when the APNs key can't be read", async () => {
    const push = account();
    const slots = memorySlots();
    let down = true;
    const apns = fakeAPNs(() => (down ? "throw" : "sent"));
    expect((await alert(held(), push, { apns, slots })).status).toBe(502);
    expect(apns.sent).toEqual([]);
    down = false;
    expect((await alert(held(), push, { apns, slots })).body).toEqual({ sent: 2 });
  });

  it("retries only the phone that failed: one that got it isn't alerted twice", async () => {
    const push = account();
    const slots = memorySlots();
    let bDown = true;
    const apns = fakeAPNs((t) => (t === TOKEN_B && bDown ? "failed" : "sent"));
    expect((await alert(held(), push, { apns, slots })).status).toBe(502);
    bDown = false;
    expect((await alert(held(), push, { apns, slots })).body).toEqual({ sent: 1 });
    expect((await alert(held(), push, { apns, slots })).body).toEqual({ duplicate: true });
    expect(apns.sent.map((s) => s.token)).toEqual([TOKEN_A, TOKEN_B, TOKEN_B]);
  });

  /** KIFF's retry queue (apps/api/internal/alert/retry.go): first try, then these waits, until a 2xx. */
  const KIFF_RETRY_WAITS_S = [60, 300, 900, 3600, 10800, 21600, 43200];

  it("keeps KIFF retrying while a run that died mid-send still holds its lease, then sends", async () => {
    const push = account();
    let now = NOW;
    const slots = memorySlots(() => now);
    // A run claimed both phones' sends at NOW and died before sending anything.
    for (const t of [TOKEN_A, TOKEN_B]) await slots.claim(`sending#t_acme#evt_1#${t}`, new Date(NOW.getTime() + 120_000));
    const apns = fakeAPNs();

    const answers: number[] = [];
    let at = NOW.getTime();
    for (const wait of KIFF_RETRY_WAITS_S) {
      at += wait * 1000;
      now = new Date(at);
      const r = await alert(held(), push, { apns, slots, now, signature: sig(held(), SECRET, Math.floor(at / 1000)) });
      answers.push(r.status);
      if (r.status < 300) { expect(r.body).toEqual({ sent: 2 }); break; }
    }
    // +1 minute: the lease still runs, so not acknowledged; +6 minutes: sent.
    expect(answers).toEqual([503, 200]);
    expect(apns.sent.map((s) => s.token)).toEqual([TOKEN_A, TOKEN_B]);
  });

  it("acknowledges a repeat only once every phone has it", async () => {
    const push = account();
    const slots = memorySlots();
    const apns = fakeAPNs();
    expect((await alert(held(), push, { apns, slots })).body).toEqual({ sent: 2 });
    expect((await alert(held(), push, { apns, slots })).body).toEqual({ duplicate: true });
    expect(apns.sent).toHaveLength(2);
  });

  it("forgets a phone Apple says is gone", async () => {
    const push = account();
    const apns = fakeAPNs((t) => (t === TOKEN_B ? "gone" : "sent"));
    expect((await alert(held(), push, { apns })).body).toEqual({ sent: 1 });
    expect(push.devices.get("t_acme")!.map((d) => d.token)).toEqual([TOKEN_A, TOKEN_C]);
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

describe("KIFF's alert target without a phone (kiff/tafs#206)", () => {
  const signedIn = (who: string) => ({ Authorization: `Bearer ${who}` });

  it("keeps the secret for tafs cloud and kiff/agents, with no device, and says alerts are set up", async () => {
    const push = memoryPush();
    const accounts = new Map<string, { tenant: string; secret: string }>();
    const target = { id: "at_1", signing_secret: SECRET };
    expect((await call("GET", "/v1/alerts", { headers: signedIn("ana"), push, accounts })).body).toEqual({ alerts: false });
    expect((await call("POST", "/v1/alerts", { body: JSON.stringify({ alert_target: target }), headers: signedIn("viewer"), push, accounts })).status).toBe(403);
    expect(accounts.size).toBe(0);
    expect((await call("POST", "/v1/alerts", { body: JSON.stringify({ alert_target: { id: "at_1", signing_secret: "nope" } }), headers: signedIn("ana"), push, accounts })).status).toBe(400);
    expect((await call("POST", "/v1/alerts", { body: JSON.stringify({ alert_target: target }), headers: signedIn("ana"), push, accounts })).body).toEqual({ alerts: true });
    expect(push.secrets.get("t_acme")).toEqual([SECRET]);
    expect(push.devices.get("t_acme") ?? []).toEqual([]);
    expect(accounts.get("t_acme")).toEqual({ tenant: "t_acme", secret: SECRET });
    expect((await call("GET", "/v1/alerts", { headers: signedIn("viewer"), push, accounts })).body).toEqual({ alerts: true });
    expect((await call("GET", "/v1/alerts", { push, accounts })).status).toBe(401);
  });
});

describe("a phone signing up for alerts", () => {
  const signedIn = (who: string) => ({ Authorization: `Bearer ${who}` });
  const accounts = new Map<string, { tenant: string; secret: string }>();
  const register = (who: string, body: object, push: ReturnType<typeof memoryPush>) =>
    call("POST", "/v1/push", { body: JSON.stringify(body), headers: signedIn(who), push, accounts });

  it("keeps the phone and KIFF's secret; only someone who may answer held calls hands over the secret", async () => {
    const push = memoryPush();
    const target = { id: "at_1", signing_secret: SECRET };
    expect((await register("viewer", { device_token: TOKEN_B, environment: "production", alert_target: target }, push)).status).toBe(403);
    expect(push.secrets.get("t_acme")).toBeUndefined();
    expect(accounts.size).toBe(0);
    expect((await register("viewer", { device_token: TOKEN_B, environment: "production" }, push)).body).toEqual({ device: true, answers: false, alerts: false });
    expect((await register("ana", { device_token: TOKEN_A.toUpperCase(), environment: "sandbox", alert_target: target }, push)).body).toEqual({ device: true, answers: true, alerts: true });
    expect(push.secrets.get("t_acme")).toEqual([SECRET]);
    // kiff/agents checks the same alerts with the same secret, under the account's tenant.
    expect(accounts.get("t_acme")).toEqual({ tenant: "t_acme", secret: SECRET });
    expect(push.devices.get("t_acme")!.map((d) => [d.token, d.environment, d.subject, d.answers])).toEqual([
      [TOKEN_B, "production", "u_vic", false], [TOKEN_A, "sandbox", "u_ana", true],
    ]);
  });

  it("gives kiff/agents the secret kept from before, when a phone registers again without one", async () => {
    const push = memoryPush();
    push.secrets.set("t_acme", [SECRET]);
    const kept = new Map<string, { tenant: string; secret: string }>();
    await call("POST", "/v1/push", { body: JSON.stringify({ device_token: TOKEN_B, environment: "production" }), headers: signedIn("viewer"), push, accounts: kept });
    expect(kept.size).toBe(0); // only someone who may answer hands it on
    await call("POST", "/v1/push", { body: JSON.stringify({ device_token: TOKEN_A, environment: "production" }), headers: signedIn("ana"), push, accounts: kept });
    expect(kept.get("t_acme")).toEqual({ tenant: "t_acme", secret: SECRET });
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
    expect((await remove("viewer", TOKEN_C)).body).toEqual({ removed: true });
    expect((await remove("ana", TOKEN_A)).body).toEqual({ removed: true });
    expect((await remove("ana", TOKEN_B)).body).toEqual({ removed: true });
    expect(push.devices.get("t_acme")).toEqual([]);
  });
});

describe("the APNs provider token", () => {
  it("is an ES256 JWT naming the key and the team, that the key's public half verifies", () => {
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
    const jwt = providerToken(privateKey, "KEYID12345", "TEAMID1234", NOW);
    const [h, c, s] = jwt.split(".");
    expect(JSON.parse(Buffer.from(h!, "base64url").toString())).toEqual({ alg: "ES256", kid: "KEYID12345" });
    expect(JSON.parse(Buffer.from(c!, "base64url").toString())).toEqual({ iss: "TEAMID1234", iat: T });
    expect(verify("sha256", Buffer.from(`${h}.${c}`), { key: publicKey, dsaEncoding: "ieee-p1363" }, Buffer.from(s!, "base64url"))).toBe(true);
  });
});
