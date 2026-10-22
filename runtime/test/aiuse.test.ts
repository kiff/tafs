import { describe, expect, it } from "vitest";
import { aiFor, base, bound, periodName, planOf, type Plan, view } from "../src/cloud/aiuse.js";
import { handle, handleStream, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const RAE = "taf-aaaa0000000000000000";
const START = "2026-10-20T09:12:00Z";
const PERIOD = "2026-10-20t09.12.00z";
const SOLO: Plan = { plan: "solo", seats: 1, start: START, goOns: 0 };
/** Half of €8.99 at 1.08 $/€, in micro US dollars. */
const HALF = 4_854_600;
const NOW = new Date("2026-10-22T12:00:00Z");

describe("the bound", () => {
  it("is half the plan's monthly price, in micro dollars at the fixed rate", () => {
    expect(base(SOLO)).toBe(HALF);
    expect(base({ ...SOLO, plan: "trial" })).toBe(HALF);
    // Team grows with its seats; the trial and Solo are one seat whatever KIFF says.
    expect(base({ ...SOLO, plan: "team", seats: 3 })).toBe(3 * HALF);
    expect(base({ ...SOLO, seats: 4 })).toBe(HALF);
  });

  it("asks again at each further full bound after a go on", () => {
    expect(bound({ ...SOLO, goOns: 1 })).toBe(2 * HALF);
    expect(bound({ ...SOLO, goOns: 2 })).toBe(3 * HALF);
  });

  it("is none on the internal plan and on a plan it has no price for", () => {
    expect(bound({ ...SOLO, plan: "internal", seats: 0 })).toBe(0);
    expect(bound({ ...SOLO, plan: "company", seats: 0 })).toBe(0);
    expect(aiFor({ ...SOLO, plan: "internal", seats: 0 })).toEqual({ period: PERIOD, bound_micro_usd: 0 });
  });

  it("names the period as kiff/agents takes it", () => {
    expect(periodName(START)).toBe(PERIOD);
    expect(periodName("2026-10-20T09:12:00.5+02:00")).toMatch(/^[a-z0-9._-]+$/);
  });

  it("shows the use as a share of the base, where the tafs ask, and when they're asking", () => {
    expect(view(SOLO, HALF / 2)).toEqual({ used_pct: 50, asks_at_pct: 100, paused: false });
    expect(view(SOLO, HALF)).toEqual({ used_pct: 100, asks_at_pct: 100, paused: true });
    expect(view({ ...SOLO, goOns: 1 }, HALF + 1)).toEqual({ used_pct: 100, asks_at_pct: 200, paused: false });
    expect(view({ ...SOLO, plan: "internal" }, 10 * HALF)).toEqual({ used_pct: null, asks_at_pct: null, paused: false });
    expect(view(undefined, 0)).toEqual({ used_pct: null, asks_at_pct: null, paused: false });
  });
});

/** KIFF: who the token is, and the account's usage read (404 until billing gives one). */
function kiff(usage: object | undefined) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    if (url.endsWith("/v1/me")) return new Response(JSON.stringify(ME), { status: 200 });
    if (url.endsWith("/v1/me/usage")) return usage ? new Response(JSON.stringify(usage), { status: 200 }) : new Response("{}", { status: 404 });
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
}

const usage = (plan: string, seats: number, goOns = 0) => ({ plan, seats, period: { start: START, end: "2026-11-20T09:12:00Z" }, ai: { go_ons: goOns } });

function world(kiffUsage: object | undefined) {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  const taf: Taf = { id: RAE, name: "Rae", job: "refunds", kiffAgent: "rae", nameClaim: "c", created: "2026-10-15T09:00:00Z" };
  tafs.held.set(`t_acme#${RAE}`, taf);
  void agents.api.putAgent("t_acme", RAE, {} as never);
  agents.calls.length = 0;
  const deps = {
    agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff(kiffUsage),
    now: () => NOW,
  };
  return { agents, deps };
}
type World = ReturnType<typeof world>;

function event(method: string, path: string, body?: unknown): HTTPEvent {
  return { rawPath: path, requestContext: { http: { method } }, headers: { Authorization: "Bearer ana" }, body: body === undefined ? undefined : JSON.stringify(body) };
}

async function call(w: World, method: string, path: string, body?: unknown) {
  const r = await handle(event(method, path, body), w.deps);
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

describe("chats under AI fair use", () => {
  it("sends kiff/agents the period and the bound with each chat", async () => {
    const w = world(usage("team", 2, 1));
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { text: "How many refunds today?" });
    expect(r.status).toBe(200);
    expect(w.agents.aiSent).toEqual([{ period: PERIOD, bound_micro_usd: 2 * 2 * HALF }]);
  });

  it("refuses a chat past the bound, and doesn't retry it in a new chat", async () => {
    const w = world(usage("solo", 1));
    const first = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { text: "Hello" });
    expect(first.status).toBe(200);
    w.agents.spent.set(`t_acme ${PERIOD}`, HALF);
    w.agents.calls.length = 0;
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { text: "And now?" });
    expect(r.status).toBe(409);
    expect(r.body.error).toMatch(/^ai_fair_use/);
    // The live chat was refused; no new chat was tried for it.
    expect(w.agents.calls.filter((c) => c.startsWith("startRun"))).toEqual([`startRun ${RAE} ${first.body.session}`]);
  });

  it("refuses a streamed chat past the bound with 409", async () => {
    const w = world(usage("trial", 1));
    w.agents.spent.set(`t_acme ${PERIOD}`, HALF);
    const r = await handleStream(event("POST", `/v1/tafs/${RAE}/messages/stream`, { text: "Hello" }), w.deps);
    expect(r.statusCode).toBe(409);
    let body = "";
    for await (const s of r.body) body += s;
    expect(JSON.parse(body).error).toMatch(/^ai_fair_use/);
  });

  it("lets the owner go on after a go on, up to the next bound", async () => {
    const w = world(usage("solo", 1, 1));
    w.agents.spent.set(`t_acme ${PERIOD}`, HALF);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { text: "Go on" })).status).toBe(200);
  });

  it("counts with no bound on the internal plan", async () => {
    const w = world(usage("internal", 0));
    w.agents.spent.set(`t_acme ${PERIOD}`, 100 * HALF);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { text: "Hi" })).status).toBe(200);
    expect(w.agents.aiSent).toEqual([{ period: PERIOD, bound_micro_usd: 0 }]);
  });

  it("neither counts nor bounds a chat when KIFF gives no plan", async () => {
    const w = world(undefined);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { text: "Hi" })).status).toBe(200);
    expect(w.agents.aiSent).toEqual([undefined]);
  });
});

describe("GET /v1/usage/ai", () => {
  it("answers the share used, where the tafs ask, and whether they're asking", async () => {
    const w = world(usage("solo", 1));
    w.agents.spent.set(`t_acme ${PERIOD}`, Math.round(HALF * 0.42));
    expect((await call(w, "GET", "/v1/usage/ai")).body).toEqual({ used_pct: 42, asks_at_pct: 100, paused: false });
    w.agents.spent.set(`t_acme ${PERIOD}`, HALF);
    expect((await call(w, "GET", "/v1/usage/ai")).body).toEqual({ used_pct: 100, asks_at_pct: 100, paused: true });
  });

  it("answers nulls with no bound, without asking kiff/agents", async () => {
    const w = world(usage("internal", 0));
    expect((await call(w, "GET", "/v1/usage/ai")).body).toEqual({ used_pct: null, asks_at_pct: null, paused: false });
    expect(w.agents.calls).toEqual([]);
  });
});

describe("KIFF's usage read", () => {
  it("is undefined when KIFF has no billing yet, and refuses a sign-in KIFF refuses", async () => {
    expect(await planOf("ana", kiff(undefined), "https://kiff.test")).toBeUndefined();
    expect(await planOf("ana", kiff(usage("team", 3, 2)), "https://kiff.test")).toEqual({ plan: "team", seats: 3, start: START, goOns: 2 });
    await expect(planOf("eve", kiff(undefined), "https://kiff.test")).rejects.toThrow(/did not accept/);
  });
});
