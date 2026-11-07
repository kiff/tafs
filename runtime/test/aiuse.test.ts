import { describe, expect, it } from "vitest";
import { aiFor, base, bound, periodName, planOf, type Plan, refusedWords, view } from "../src/cloud/aiuse.js";
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
    expect(view(SOLO, HALF / 2)).toEqual({ used_pct: 50, asks_at_pct: 100, paused: false, can_go_on: false, starts_again: null,
      words: "Your tafs used 50% of this period's AI. They ask you before going past 100%." });
    expect(view({ ...SOLO, goOns: 1 }, HALF + 1)).toMatchObject({ used_pct: 100, asks_at_pct: 200, paused: false });
    expect(view({ ...SOLO, plan: "internal" }, 10 * HALF)).toEqual({ used_pct: null, asks_at_pct: null, paused: false, can_go_on: false, starts_again: null, words: "Your plan sets no AI limit for your tafs." });
    expect(view(undefined, 0)).toEqual({ used_pct: null, asks_at_pct: null, paused: false, can_go_on: false, starts_again: null, words: "Your plan sets no AI limit for your tafs." });
  });

  // kiff/tafs#185: while the tafs wait, the billing admin is told to go on; anyone else, that an
  // admin can, and when the AI use starts again by itself. An admin is named by role, never email.
  it("tells the billing admin to go on, and anyone else who can and when it starts again", () => {
    const ending = { ...SOLO, end: "2026-10-31T23:30:00Z" };
    expect(view(ending, HALF, true, "Europe/Madrid")).toEqual({ used_pct: 100, asks_at_pct: 100, paused: true, can_go_on: true, starts_again: "2026-10-31T23:30:00Z",
      words: "Your tafs used 100% of this period's AI and are waiting for you. Go on, and they ask again at 200%. Otherwise it starts again on 1 Nov." });
    expect(view(ending, HALF, false, "UTC")).toMatchObject({ paused: true, can_go_on: false,
      words: "Your tafs used 100% of this period's AI and are waiting. An admin of this account can let them go on from Settings › AI use. Otherwise it starts again on 31 Oct." });
    expect(view(SOLO, HALF, false).words).toContain("Otherwise it starts again with the next period.");
    expect(view(SOLO, HALF, false).words).not.toMatch(/@/);
  });
});

/** KIFF: who the token is, and the account's usage read (404 until billing gives one). */
function kiff(usage: object | undefined, capabilities = ME.capabilities) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    if (url.endsWith("/v1/me")) return new Response(JSON.stringify({ ...ME, capabilities }), { status: 200 });
    if (url.endsWith("/v1/me/usage")) return usage ? new Response(JSON.stringify(usage), { status: 200 }) : new Response("{}", { status: 404 });
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
}

const usage = (plan: string, seats: number, goOns = 0) => ({ plan, seats, period: { start: START, end: "2026-11-20T09:12:00Z" }, ai: { go_ons: goOns } });

function world(kiffUsage: object | undefined, capabilities = ME.capabilities) {
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
    fetch: kiff(kiffUsage, capabilities),
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

describe("a chat, since credits are the balance", () => {
  it("sends kiff/agents no AI bound, and reads no plan for one, whatever the plan", async () => {
    for (const u of [usage("team", 2, 1), usage("solo", 1), usage("internal", 0), undefined]) {
      const w = world(u);
      w.agents.spent.set(`t_acme ${PERIOD}`, 100 * HALF);
      expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { text: "How many refunds today?" })).status).toBe(200);
      expect(w.agents.aiSent).toEqual([undefined]);
    }
  });
});

describe("GET /v1/usage/ai, kept for apps from before credits", () => {
  it("answers no bound, never waiting and no Go on, asking neither KIFF nor kiff/agents", async () => {
    const asked: string[] = [];
    const w = world(usage("solo", 1), [...ME.capabilities, "billing.manage"]);
    const read = w.deps.fetch;
    w.deps.fetch = (async (url: string, init?: RequestInit) => { asked.push(new URL(url).pathname); return read(url, init); }) as typeof fetch;
    w.agents.spent.set(`t_acme ${PERIOD}`, HALF);
    const r = await call(w, "GET", "/v1/usage/ai");
    expect(r.body).toMatchObject({ used_pct: null, asks_at_pct: null, paused: false, can_go_on: false });
    expect(asked.filter((p) => p.startsWith("/v1/me/usage"))).toEqual([]);
    expect(w.agents.calls).toEqual([]);
  });
});

describe("a chat refused for AI fair use, in words", () => {
  it("says the day the AI use starts again, in the account's zone", () => {
    const p = { plan: "solo", seats: 1, start: "2026-10-01T00:00:00Z", end: "2026-10-31T23:30:00Z", goOns: 0 };
    expect(refusedWords(p, "Europe/Madrid")).toBe("Your tafs have used this period's AI. An admin of this account can let them go on from Settings › AI use, or it starts again on 1 Nov.");
    expect(refusedWords(p, "UTC", true)).toBe("Your tafs have used this period's AI. Go on from Settings › AI use, or it starts again on 31 Oct.");
    expect(refusedWords(undefined)).toBe("Your tafs have used this period's AI. An admin of this account can let them go on from Settings › AI use, or it starts again with the next period.");
  });
});

describe("KIFF's usage read", () => {
  it("is undefined when KIFF has no billing yet, and refuses a sign-in KIFF refuses", async () => {
    expect(await planOf("ana", kiff(undefined), "https://kiff.test")).toBeUndefined();
    expect(await planOf("ana", kiff(usage("team", 3, 2)), "https://kiff.test")).toEqual({ plan: "team", seats: 3, start: START, end: "2026-11-20T09:12:00Z", goOns: 2 });
    await expect(planOf("eve", kiff(undefined), "https://kiff.test")).rejects.toThrow(/did not accept/);
  });
});
