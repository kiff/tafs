import { generateKeyPairSync, verify } from "node:crypto";
import { describe, expect, it } from "vitest";
import { fcmMessage, fcmSender, type ServiceAccount } from "../src/cloud/fcm.js";
import { apsFor } from "../src/cloud/levels.js";
import { type Device, type Notification, quietFor, silencing } from "../src/cloud/push.js";

const NOW = new Date("2026-10-15T09:00:00Z");
const FCM_TOKEN = "dXyZ1_abC:APA91bHqLz-9fWk0Qe3mNoPQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrStUvWxYz";
const phone: Device = { token: FCM_TOKEN, platform: "android", environment: "production", subject: "u_ana", answers: true, at: "" };

const hold: Notification = {
  kind: "alert",
  payload: {
    aps: { alert: { title: "A taf needs you", body: "Stripe refund waits for your answer. Nothing is sent until you approve." }, "thread-id": "held", category: "hold", "mutable-content": 1, ...apsFor("interrupt") },
    open: "needs-you", hold_id: "hold_1", expires_at: "2026-10-15T09:10:00.000Z",
  },
  collapseID: "hold_1",
  expiresAt: new Date("2026-10-15T09:10:00.000Z"),
};
const answered: Notification = { kind: "background", payload: { aps: { "content-available": 1 }, hold_id: "hold_1", answered: true } };
const request: Notification = {
  kind: "alert",
  payload: { aps: { alert: { title: "More tasks asked for", body: "Someone on your account asks you to add tasks. Open Usage to answer." }, "thread-id": "tasks", ...apsFor("quiet") }, open: "usage", request_id: "req_1" },
  collapseID: "req_1",
};

describe("the message an Android phone gets", () => {
  it("is a data message that a held call becomes: its words, where it opens, what it is about, how loud", () => {
    const m = fcmMessage(hold, NOW);
    expect(m.data).toEqual({
      kind: "alert", title: "A taf needs you", body: "Stripe refund waits for your answer. Nothing is sent until you approve.",
      open: "needs-you", hold_id: "hold_1", expires_at: "2026-10-15T09:10:00.000Z", level: "interrupt",
    });
    expect(m.android).toEqual({ priority: "HIGH", collapse_key: "hold_1", ttl: "600s" });
  });

  it("carries no call parameters: nothing but what the phone reads the held call by", () => {
    const keys = Object.keys(fcmMessage(hold, NOW).data).sort();
    expect(keys).toEqual(["body", "expires_at", "hold_id", "kind", "level", "open", "title"]);
    for (const v of Object.values(fcmMessage(hold, NOW).data)) expect(typeof v).toBe("string");
  });

  it("is quiet for a request for tasks, and waits at normal priority", () => {
    const m = fcmMessage(request, NOW);
    expect(m.data).toEqual({ kind: "alert", title: "More tasks asked for", body: "Someone on your account asks you to add tasks. Open Usage to answer.", open: "usage", request_id: "req_1", level: "quiet" });
    expect(m.android).toEqual({ priority: "NORMAL", collapse_key: "req_1" });
  });

  it("is quiet for a phone whose Sound for alerts is off, as it is for an iPhone", () => {
    const quiet = quietFor({ ...phone, silent: true }, hold);
    expect(fcmMessage(quiet, NOW).data.level).toBe("quiet");
    expect(fcmMessage(quiet, NOW).android.priority).toBe("NORMAL");
    expect(fcmMessage(quietFor({ ...phone, silent: false }, hold), NOW).data.level).toBe("interrupt");
  });

  it("takes a notification away when its call is answered", () => {
    const m = fcmMessage(answered, NOW);
    expect(m.data).toEqual({ kind: "answered", hold_id: "hold_1" });
    expect(m.android).toEqual({ priority: "HIGH" });
  });

  it("is one the phone ignores for any other background push", () => {
    expect(fcmMessage({ kind: "background", payload: { aps: { "content-available": 1 } } }, NOW).data.kind).toBe("background");
  });

  it("is not held past its call's expiry: the time to live runs out with it", () => {
    expect(fcmMessage({ ...hold, expiresAt: new Date("2026-10-15T08:00:00Z") }, NOW).android.ttl).toBe("0s");
    expect(fcmMessage({ ...hold, expiresAt: undefined }, NOW).android).toEqual({ priority: "HIGH", collapse_key: "hold_1" });
  });

  it("leaves out a word the alert doesn't have", () => {
    const m = fcmMessage({ kind: "alert", payload: { aps: { alert: { title: "tafs" } }, open: 5 as never } }, NOW);
    expect(m.data).toEqual({ kind: "alert", title: "tafs", level: "quiet" });
  });
});

/** Google, as the sender meets it: a token endpoint and the FCM API, each answering as told. */
function google(opts: { tokenStatus?: number; fcm?: () => { status: number; body: object } }) {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  let tokens = 0;
  const f = (async (url: string | URL, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    if (String(url) === "https://oauth2.example/token") {
      tokens++;
      if ((opts.tokenStatus ?? 200) !== 200) return new Response("{}", { status: opts.tokenStatus });
      return new Response(JSON.stringify({ access_token: `at_${tokens}`, expires_in: 3600 }), { status: 200 });
    }
    const r = opts.fcm?.() ?? { status: 200, body: { name: "projects/p/messages/1" } };
    return new Response(JSON.stringify(r.body), { status: r.status });
  }) as typeof fetch;
  return { calls, fetch: f, tokens: () => tokens };
}

function account() {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const service: ServiceAccount = {
    client_email: "push@tafs-test.iam.gserviceaccount.com",
    private_key: privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
    token_uri: "https://oauth2.example/token",
  };
  return { service, publicKey };
}

describe("fcmSender", () => {
  it("asks Google for an access token with a JWT the service account signed, then sends the message with it", async () => {
    const a = account();
    const g = google({});
    const sender = fcmSender({ projectId: "tafs-test", credentials: async () => a.service, now: () => NOW, fetch: g.fetch });
    expect(await sender.send(phone, hold)).toBe("sent");
    const [tokenCall, sendCall] = g.calls;
    // The token request: a JWT bearer grant, signed RS256 by the account's key, naming its scope.
    const form = new URLSearchParams(String(tokenCall!.init.body));
    expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");
    const [h, c, sig] = form.get("assertion")!.split(".");
    expect(JSON.parse(Buffer.from(h!, "base64url").toString())).toEqual({ alg: "RS256", typ: "JWT" });
    const claims = JSON.parse(Buffer.from(c!, "base64url").toString());
    expect(claims).toEqual({
      iss: a.service.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud: "https://oauth2.example/token",
      iat: Math.floor(NOW.getTime() / 1000), exp: Math.floor(NOW.getTime() / 1000) + 3600,
    });
    expect(verify("RSA-SHA256", Buffer.from(`${h}.${c}`), a.publicKey, Buffer.from(sig!, "base64url"))).toBe(true);
    // The message: to this token, through the project, as a data message.
    expect(sendCall!.url).toBe("https://fcm.googleapis.com/v1/projects/tafs-test/messages:send");
    expect((sendCall!.init.headers as Record<string, string>).authorization).toBe("Bearer at_1");
    const sent = JSON.parse(String(sendCall!.init.body)).message;
    expect(sent.token).toBe(FCM_TOKEN);
    expect(sent.data.kind).toBe("alert");
    expect(sent.android).toEqual({ priority: "HIGH", collapse_key: "hold_1", ttl: "600s" });
    expect(sent.notification, "the phone builds the notification itself").toBeUndefined();
  });

  it("uses one access token for the next pushes, and asks for a new one before it ends", async () => {
    const a = account();
    const g = google({});
    let t = NOW;
    const sender = fcmSender({ projectId: "p", credentials: async () => a.service, now: () => t, fetch: g.fetch });
    await sender.send(phone, hold);
    await sender.send(phone, answered);
    expect(g.tokens()).toBe(1);
    t = new Date(NOW.getTime() + 3600_000 - 200_000); // 200 s left: within the five minutes
    await sender.send(phone, hold);
    expect(g.tokens()).toBe(2);
  });

  it("asks for one access token when several pushes begin together", async () => {
    const a = account();
    const g = google({});
    const sender = fcmSender({ projectId: "p", credentials: async () => a.service, now: () => NOW, fetch: g.fetch });
    expect(await Promise.all([sender.send(phone, hold), sender.send(phone, hold), sender.send(phone, answered)])).toEqual(["sent", "sent", "sent"]);
    expect(g.tokens()).toBe(1);
  });

  it("reads the service account's key once", async () => {
    const a = account();
    const g = google({});
    let reads = 0;
    let t = NOW;
    const sender = fcmSender({ projectId: "p", credentials: async () => { reads++; return a.service; }, now: () => t, fetch: g.fetch });
    await sender.send(phone, hold);
    t = new Date(NOW.getTime() + 7200_000);
    await sender.send(phone, hold);
    expect(g.tokens()).toBe(2);
    expect(reads).toBe(1);
  });

  it("fails, so KIFF tries again, when the key can't be read or Google refuses it", async () => {
    const a = account();
    const g = google({ tokenStatus: 400 });
    expect(await fcmSender({ projectId: "p", credentials: async () => a.service, now: () => NOW, fetch: g.fetch }).send(phone, hold)).toBe("failed");
    expect(g.calls.every((c) => c.url === "https://oauth2.example/token"), "nothing is sent without an access token").toBe(true);
    const broken = fcmSender({ projectId: "p", credentials: async () => { throw new Error("Secrets Manager is down"); }, now: () => NOW, fetch: g.fetch });
    expect(await broken.send(phone, hold)).toBe("failed");
  });

  const answerWith = (status: number, body: object) => {
    const a = account();
    return fcmSender({ projectId: "p", credentials: async () => a.service, now: () => NOW, fetch: google({ fcm: () => ({ status, body }) }).fetch }).send(phone, hold);
  };

  it("forgets a phone whose token Google no longer knows, and only that", async () => {
    expect(await answerWith(404, { error: { code: 404, status: "NOT_FOUND", message: "Requested entity was not found.", details: [{ errorCode: "UNREGISTERED" }] } })).toBe("gone");
    expect(await answerWith(403, { error: { code: 403, status: "PERMISSION_DENIED", details: [{ errorCode: "SENDER_ID_MISMATCH" }] } })).toBe("gone");
    expect(await answerWith(400, { error: { code: 400, status: "INVALID_ARGUMENT", message: "The registration token is not a valid FCM registration token" } })).toBe("gone");
    // A malformed message of ours is no reason to forget a phone.
    expect(await answerWith(400, { error: { code: 400, status: "INVALID_ARGUMENT", message: "Invalid value at 'message.android.ttl'" } })).toBe("failed");
  });

  it("fails on anything else, so KIFF tries again", async () => {
    expect(await answerWith(500, { error: { status: "INTERNAL" } })).toBe("failed");
    expect(await answerWith(429, { error: { status: "QUOTA_EXCEEDED" } })).toBe("failed");
    expect(await answerWith(503, {})).toBe("failed");
    const a = account();
    const down = fcmSender({
      projectId: "p", credentials: async () => a.service, now: () => NOW,
      fetch: (async (url: string) => {
        if (url === "https://oauth2.example/token") return new Response(JSON.stringify({ access_token: "x", expires_in: 3600 }), { status: 200 });
        throw new Error("network down");
      }) as typeof fetch,
    });
    expect(await down.send(phone, hold)).toBe("failed");
  });

  it("makes a new access token after Google refuses the one it has", async () => {
    const a = account();
    const answers = [401, 200];
    const g = google({ fcm: () => ({ status: answers.shift() ?? 200, body: {} }) });
    const sender = fcmSender({ projectId: "p", credentials: async () => a.service, now: () => NOW, fetch: g.fetch });
    expect(await sender.send(phone, hold)).toBe("failed");
    expect(await sender.send(phone, hold)).toBe("sent");
    expect(g.tokens()).toBe(2);
  });

  it("is quiet through the silencing sender for a phone with Sound off", async () => {
    const a = account();
    const g = google({});
    const sender = silencing(fcmSender({ projectId: "p", credentials: async () => a.service, now: () => NOW, fetch: g.fetch }));
    await sender.send({ ...phone, silent: true }, hold);
    const sent = JSON.parse(String(g.calls.at(-1)!.init.body)).message;
    expect(sent.data.level).toBe("quiet");
    expect(sent.android.priority).toBe("NORMAL");
  });
});
