import { describe, expect, it } from "vitest";
import { type Deps, handle, type HTTPEvent } from "../src/cloud/handler.js";
import { foldMarks, INBOX_FOLD_AT, INBOX_IDS_MAX, INBOX_KEPT_MS, INBOX_MARK_MAX, inboxKey, inboxMarks, markInbox } from "../src/cloud/watch-inbox.js";
import { memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs, fakeAgents } from "./fakes.js";

/**
 * The watch Inbox's read and dismissed, in step across the person's phones and watches (docs/watch-app.md, step iv):
 * ids alone, each person's own, and the person's phones told to read again, with nothing else in the push.
 */

const T = "t_acme";
const T2 = "t_other";
const ANA = "u_ana";
const BO = "u_bo";
const NOW = new Date("2026-11-23T23:00:00Z");

function world(opts: { coach?: boolean; watchAsk?: boolean } = {}) {
  let clock = NOW;
  const coach = memoryCoach(() => clock);
  const pushed: Array<{ token: string; payload: Record<string, unknown> }> = [];
  const devices = [
    { token: "fcm-ana", platform: "android" as const, environment: "production" as const, subject: ANA, answers: true, at: "" },
    { token: "a".repeat(64), platform: "ios" as const, environment: "production" as const, subject: ANA, answers: false, at: "" },
    { token: "fcm-bo", platform: "android" as const, environment: "production" as const, subject: BO, answers: true, at: "" },
  ];
  const deps: Deps = {
    agents: fakeAgents(() => clock).api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: memoryTafs(), names: memoryNames(), slots: memorySlots(),
    claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), aiConsent: false, watchAsk: opts.watchAsk ?? false,
    push: { get: async () => ({ devices, secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async (d, n) => { pushed.push({ token: d.token, payload: n.payload }); return "sent" as const; } },
    forward: async () => undefined, ...(opts.coach === false ? {} : { coach }), kiffBase: "https://api.test", now: () => clock,
    fetch: (async (url: string, init?: RequestInit) => {
      const who = String(((init?.headers ?? {}) as Record<string, string>).authorization ?? "").replace("Bearer ", "");
      // "zed" is a person of another account, with the same subject as Ana's; "key" is an API key.
      const subject = who === "bo" ? BO : ANA;
      const tenant = who === "zed" ? T2 : T;
      if (url.endsWith("/v1/me")) return new Response(JSON.stringify({ tenant_id: tenant, subject, api_key: who === "key", capabilities: ["read"] }));
      return new Response("{}", { status: 404 });
    }) as unknown as typeof fetch,
  };
  const call = async (method: string, body?: unknown, who = "ana") => {
    const event: HTTPEvent = {
      rawPath: "/v1/watch/inbox", requestContext: { http: { method } },
      headers: { authorization: `Bearer ${who}`, "accept-language": "en" },
      body: body === undefined ? undefined : JSON.stringify(body),
    };
    const r = await handle(event, deps);
    return { status: r.statusCode, body: JSON.parse(r.body || "{}") as Record<string, unknown> };
  };
  return { call, coach, pushed, tick: (ms: number) => { clock = new Date(clock.getTime() + ms); } };
}

describe("the watch Inbox's read and dismissed", () => {
  it("keeps what the person marked, and answers it to their next read, not behind the watch ask's switch", async () => {
    const w = world();
    expect((await w.call("GET")).body).toEqual({ read: [], dismissed: [] });
    const r = await w.call("POST", { read: ["hold_1", "wa_a1"] });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ read: ["hold_1", "wa_a1"], dismissed: [] });
    await w.call("POST", { dismissed: ["hold_1"] });
    expect((await w.call("GET")).body).toEqual({ read: ["wa_a1"], dismissed: ["hold_1"] });
  });

  it("never brings a dismissed line back as read", async () => {
    const w = world();
    await w.call("POST", { dismissed: ["hold_1"] });
    await w.call("POST", { read: ["hold_1"] });
    expect((await w.call("GET")).body).toEqual({ read: [], dismissed: ["hold_1"] });
  });

  it("keeps two marks sent at once", async () => {
    const w = world();
    await Promise.all([w.call("POST", { dismissed: ["hold_1"] }), w.call("POST", { dismissed: ["hold_2"] })]);
    expect(((await w.call("GET")).body.dismissed as string[]).sort()).toEqual(["hold_1", "hold_2"]);
  });

  it("is each person's own, in their own account", async () => {
    const w = world();
    await w.call("POST", { dismissed: ["hold_1"] });
    expect((await w.call("GET", undefined, "bo")).body).toEqual({ read: [], dismissed: [] });
    expect((await w.call("GET", undefined, "zed")).body).toEqual({ read: [], dismissed: [] });
    expect((await w.call("GET", undefined, "key")).status).toBe(403);
    expect((await w.call("POST", { read: ["hold_1"] }, "key")).status).toBe(403);
  });

  it("tells the person's phones alone, iPhone and Android, that the marks changed, and nothing more", async () => {
    const w = world();
    await w.call("POST", { dismissed: ["hold_1"] });
    expect(w.pushed.map((p) => p.token)).toEqual(["fcm-ana", "a".repeat(64)]);
    for (const p of w.pushed) expect(p.payload).toEqual({ aps: { "content-available": 1 }, change: "watch_inbox" });
    // A read tells no one.
    w.pushed.length = 0;
    await w.call("GET");
    expect(w.pushed).toEqual([]);
  });

  it("refuses anything but a line's ids, and keeps nothing of a refused write", async () => {
    const w = world();
    for (const body of [
      {}, { read: [] }, { read: "hold_1" }, { read: [1] }, { read: ["hold 1"] }, { read: ["x".repeat(101)] },
      { read: ["hold_1"], words: "Pay 40 EUR" }, { dismissed: Array.from({ length: 101 }, (_, i) => `hold_${i}`) },
    ]) {
      const r = await w.call("POST", body);
      expect(r.status, JSON.stringify(body)).toBe(400);
      expect(r.body.code).toBe("bad_marks");
    }
    expect((await w.call("GET")).body).toEqual({ read: [], dismissed: [] });
    expect(w.pushed).toEqual([]);
    expect((await w.call("DELETE")).status).toBe(404);
  });

  it("is kept 30 days after the person's last mark", async () => {
    const w = world();
    await w.call("POST", { read: ["hold_1"] });
    w.tick(INBOX_KEPT_MS - 60_000);
    await w.call("POST", { read: ["hold_2"] });
    w.tick(INBOX_KEPT_MS - 60_000);
    expect((await w.call("GET")).body).toEqual({ read: ["hold_1", "hold_2"], dismissed: [] });
    w.tick(120_000);
    expect((await w.call("GET")).body).toEqual({ read: [], dismissed: [] });
  });

  it("isn't there without the store", async () => {
    expect((await world({ coach: false }).call("GET")).status).toBe(404);
  });
});

describe("the marks kept", () => {
  it("keep the ids marked last, each once", () => {
    const writes = Array.from({ length: INBOX_IDS_MAX + 10 }, (_, i) => ({ read: [`hold_${i}`], dismissed: [] }));
    writes.push({ read: ["hold_3"], dismissed: [] });
    const state = foldMarks(writes);
    expect(state.read).toHaveLength(INBOX_IDS_MAX);
    expect(state.read.at(-1)).toBe("hold_3");
    expect(state.read).not.toContain("hold_0");
    expect(new Set(state.read).size).toBe(state.read.length);
  });

  it("fold into one write past their limit, losing no mark", async () => {
    const coach = memoryCoach(() => NOW);
    for (let i = 0; i <= INBOX_FOLD_AT; i++) await markInbox(coach, T, ANA, { read: [`hold_${i}`], dismissed: i === 7 ? ["hold_7"] : [] }, NOW);
    const kept = (await coach.get(inboxKey(T, ANA))) as unknown[];
    expect(kept).toHaveLength(1);
    const state = foldMarks(kept);
    expect(state.read).toHaveLength(INBOX_FOLD_AT);
    expect(state.dismissed).toEqual(["hold_7"]);
  });

  it("stay under DynamoDB's 400 KB item with every write at its largest, and every write lands", async () => {
    const coach = memoryCoach(() => NOW);
    // The store refuses an item past 400 KB, as DynamoDB does.
    const held = (key: string) => JSON.stringify(coach.held.get(key)?.value ?? []);
    const append = coach.append.bind(coach);
    coach.append = async (key, item, until) => {
      if (Buffer.byteLength(held(key)) + Buffer.byteLength(JSON.stringify(item)) > 400_000) throw new Error("Item size has exceeded the maximum allowed size");
      return append(key, item, until);
    };
    const id = (w: number, i: number) => `hold_${String(w).padStart(3, "0")}_${String(i).padStart(3, "0")}`.padEnd(100, "x");
    let state;
    for (let w = 0; w < 60; w++) {
      state = await markInbox(coach, T, ANA, { read: Array.from({ length: INBOX_MARK_MAX }, (_, i) => id(w, i)), dismissed: [] }, NOW);
    }
    expect(Buffer.byteLength(held(inboxKey(T, ANA)))).toBeLessThan(200_000);
    expect(state!.read).toHaveLength(INBOX_IDS_MAX);
    expect(state!.read.at(-1)).toBe(id(59, 99));
  });

  it("read only what a write may hold", () => {
    expect(inboxMarks({ read: ["hold_1", "hold_1"], dismissed: ["wa_x"] })).toEqual({ read: ["hold_1"], dismissed: ["wa_x"] });
    expect(foldMarks([{ read: ["ok_1", "not ok", 3] }, null, "x", { dismissed: "hold_2" }])).toEqual({ read: ["ok_1"], dismissed: [] });
  });
});
