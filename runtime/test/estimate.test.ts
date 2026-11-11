import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { type CreditRun, type KIFFBalance, type KIFFRun, readCoachCredits } from "../src/cloud/credits.js";
import { estimate, ESTIMATES_KEY, type Own, paceLine, testsFor, testsIn, weekOneWords } from "../src/cloud/estimate.js";
import { coachTool, handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { LANGUAGES, wordsIn } from "../src/cloud/words/index.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const REFUNDS = "taf-3f9a1c0b7d2e4a6f8b91";
const HIDDEN = "taf-ffff0000000000000000";
const NOW = new Date("2026-11-20T12:00:00Z");
const DAY = 86_400_000;
const NEVER = "It never goes past your credits: when they run out, it waits.";
const NEVER_COMPANY = "It never goes past your spend limit: there, it waits.";
const FIRST_WEEK = "After its first week, you'll see what it really uses.";
const UNKNOWN = "I can't tell yet what it will use.";

/** n runs, one a day back from now, each with these credits. */
const daily = (credits: number[]): Own["runs"] => credits.map((c, i) => ({ at: new Date(NOW.getTime() - (i + 1) * DAY).toISOString(), credits: c }));
/** Twenty days of history: enough for a pace. */
const own = (credits: number[], days = 20): Own => ({ runs: daily(credits), from: new Date(NOW.getTime() - days * DAY).toISOString() });
const solo = { kind: "solo" as const, included: 500, limit_cents: 0 };
const company = { kind: "company" as const, included: 500, limit_cents: 5000 };

describe("a taf's estimate: the source, best first", () => {
  const tests = { p50: 110, p90: 220, runs: 12, from: "2026-11-01" };

  // The rule the owner relies on: the taf's own record beats a trial's, a trial's beats our tests', and with
  // none the answer says so. Remove the order and this fails.
  it("uses the taf's own runs over a trial's, a trial's over our tests', and says none with nothing", () => {
    const all = { own: own([30, 30, 40]), trial: [90, 90], tests, now: NOW };
    expect(estimate(all).source).toBe("own");
    expect(estimate(all).per_run).toEqual({ low: 30, high: 40 });
    expect(estimate({ ...all, own: { runs: [], from: all.own.from } })).toMatchObject({ source: "trial", per_run: { low: 90, high: 90 } });
    expect(estimate({ ...all, own: undefined, trial: [] })).toMatchObject({ source: "tests", per_run: { low: 110, high: 220 } });
    expect(estimate({ now: NOW, plan: solo })).toEqual({ source: "none", words: `${UNKNOWN} ${FIRST_WEEK} ${NEVER}` });
  });

  it("counts only runs that used credits, and takes runs a month from the taf's own pace only", () => {
    const e = estimate({ own: own([0, 20, 20, 20, 20, 20, 20, 20, 20, 30, 40]), now: NOW });
    // Ten counted runs over twenty days: fifteen a month.
    expect(e.runs_a_month).toEqual({ low: 15, high: 15 });
    expect(e.per_run).toEqual({ low: 20, high: 30 });
    expect(e.credits).toEqual({ low: 300, high: 450 });
    // A trial's credits per run, with no pace of the taf's own: no credits a month.
    expect(estimate({ trial: [100, 120], now: NOW })).not.toHaveProperty("credits");
  });

  it("calls nothing a pace before seven days of history, and says so honestly", () => {
    const young = estimate({ own: own([20, 30], 5), plan: solo, now: NOW });
    expect(young.runs_a_month).toBeUndefined();
    expect(young.words).toBe(`I can't tell yet how often work comes in. So far, a run used about 20 to 30 credits. ${FIRST_WEEK} ${NEVER}`);
    expect(estimate({ tests, plan: solo, now: NOW }).words)
      .toBe(`I can't tell yet how often work comes in. In our tests, a run of this job used about 110 to 220 credits. ${FIRST_WEEK} ${NEVER}`);
    expect(estimate({ trial: [100, 100], now: NOW }).words).toContain("In its trial, a run used about 100 credits.");
  });

  // No pace and no tests: the honest line, and nothing that looks like a figure. No most-it-could-use either.
  it("with no pace and no tests, says it can't tell yet, with no figure at all", () => {
    const e = estimate({ own: { runs: [], from: new Date(NOW.getTime() - 3 * DAY).toISOString() }, now: NOW });
    expect(e).toEqual({ source: "none", words: `${UNKNOWN} ${FIRST_WEEK}` });
    expect(e.words).not.toMatch(/\d/);
  });
});

describe("a taf's estimate, against the plan", () => {
  const paced = (credits: number[]) => own(credits.flatMap((c) => [c, c]), 20);

  it("says what the plan covers first: on its own, it fits inside it", () => {
    // 20 runs over 20 days: 30 a month, at 10 to 15 credits each.
    const e = estimate({ own: paced([10, 10, 10, 10, 10, 10, 10, 10, 15, 15]), plan: solo, now: NOW });
    expect(e.words).toBe(`About 300 to 450 credits a month. On its own, it fits inside your plan's 500 a month. ${NEVER}`);
  });

  it("on Solo, past the plan: what it includes, then how much more, and no euros, since no pack's price is known here", () => {
    const e = estimate({ own: paced([40, 40, 40, 40, 40, 40, 40, 40, 60, 60]), plan: solo, now: NOW });
    expect(e.credits).toEqual({ low: 1200, high: 1800 });
    expect(e.words).toBe(`About 1,200 to 1,800 credits a month. Your plan includes 500, so about 700 to 1,300 more. ${NEVER}`);
    expect(e.words).not.toContain("€");
    // Its low end inside the plan: up to the high end more.
    expect(estimate({ own: paced([10, 10, 10, 10, 10, 10, 10, 10, 30, 30]), plan: solo, now: NOW }).words)
      .toBe(`About 300 to 900 credits a month. Your plan includes 500, so up to 400 more. ${NEVER}`);
  });

  it("on Company, past the plan: the credits past it, their euros at the published €0.01, and the account's own limit, honestly", () => {
    const e = estimate({ own: paced([40, 40, 40, 40, 40, 40, 40, 40, 60, 60]), plan: company, now: NOW });
    expect(e.words).toBe(`About 1,200 to 1,800 credits a month, about 700 to 1,300 past the plan's credits, about €7 to €13 at €0.01, within your €50 spend limit. ${NEVER_COMPANY}`);
    expect(estimate({ own: paced([40, 40, 40, 40, 40, 40, 40, 40, 60, 60]), plan: { ...company, limit_cents: 1000 }, now: NOW }).words)
      .toContain("about €7 to €13 at €0.01, past your €10 spend limit.");
    expect(estimate({ own: paced([10, 10, 10, 10, 10, 10, 10, 10, 15, 15]), plan: company, now: NOW }).words)
      .toBe(`About 300 to 450 credits a month. On its own, it fits inside your plan's 500 a month. ${NEVER_COMPANY}`);
  });

  it("with no plan to hold it against, the credits alone", () => {
    expect(estimate({ own: paced([10, 10, 10, 10, 10, 10, 10, 10, 15, 15]), plan: { kind: "unbounded", included: 0, limit_cents: 0 }, now: NOW }).words)
      .toBe("About 300 to 450 credits a month.");
  });

  it("is in all eight languages, each line its own", () => {
    const e = { own: paced([40, 40, 40, 40, 40, 40, 40, 40, 60, 60]), plan: company, now: NOW };
    const lines = LANGUAGES.map((lang) => estimate(e, lang).words);
    expect(new Set(lines).size).toBe(8);
    expect(estimate(e, "de").words).toContain("Ausgabenlimit");
    expect(estimate({ now: NOW, plan: solo }, "pl").words).toBe(`${wordsIn("pl").est_unknown} ${wordsIn("pl").est_first_week} ${wordsIn("pl").est_never_past_solo}`);
    for (const lang of LANGUAGES) expect(weekOneWords("Refunds", { at: "", credits: 120, month: 510 }, lang)).toContain("Refunds");
  });
});

// The last line says where the taf stops, and that depends on the plan: one test per kind.
describe("a taf's estimate: its last line, by plan", () => {
  const e = (plan?: Parameters<typeof estimate>[0]["plan"]) => estimate({ own: own([20, 20, 20]), now: NOW, ...(plan ? { plan } : {}) });

  it("on Solo, it waits when the credits run out", () => {
    expect(e(solo).words.endsWith(` ${NEVER}`)).toBe(true);
    for (const lang of LANGUAGES) expect(estimate({ now: NOW, plan: solo }, lang).words.endsWith(wordsIn(lang).est_never_past_solo)).toBe(true);
  });

  it("on Company, it waits at the spend limit", () => {
    expect(e(company).words.endsWith(` ${NEVER_COMPANY}`)).toBe(true);
    for (const lang of LANGUAGES) expect(estimate({ now: NOW, plan: company }, lang).words.endsWith(wordsIn(lang).est_never_past_company)).toBe(true);
  });

  it("on an agreement (unbounded), no last line", () => {
    const words = e({ kind: "unbounded", included: 0, limit_cents: 0 }).words;
    expect(words).toBe("About 100 credits a month.");
    for (const lang of LANGUAGES) {
      const w = estimate({ now: NOW, plan: { kind: "unbounded", included: 0, limit_cents: 0 } }, lang).words;
      expect(w).not.toContain(wordsIn(lang).est_never_past_solo);
      expect(w).not.toContain(wordsIn(lang).est_never_past_company);
    }
  });

  it("with no plan, or none known, no last line", () => {
    expect(e().words).toBe("About 100 credits a month.");
    expect(e({ kind: "none", included: 0, limit_cents: 0 }).words).toBe("About 100 credits a month.");
  });
});

describe("our tests' figures", () => {
  // They're kept in tafs cloud's store, written from the private Eval reports outside this repo: no file of
  // figures is committed, and the code holds none.
  it("are in no file in this repo", () => {
    expect(existsSync(new URL("../src/cloud/estimates.json", import.meta.url))).toBe(false);
    expect(readFileSync(new URL("../src/cloud/estimate.ts", import.meta.url), "utf8")).not.toMatch(/\.json/);
  });

  it("are read from the store at estimates#v1, a recipe's whole figures only", async () => {
    const store = memoryCoach(() => NOW);
    expect(ESTIMATES_KEY).toBe("estimates#v1");
    expect(await testsFor("chargebacks-with-evidence", store)).toBeUndefined();
    await store.put(ESTIMATES_KEY, {
      "chargebacks-with-evidence": { p50: 40, p90: 30, runs: 12, from: "2026-11-11" },
      "half": { p50: 40, runs: 12 },
      "zero": { p50: 0, p90: 10, runs: 3 },
      "nothing": null,
    }, new Date(NOW.getTime() + 365 * DAY));
    // A p90 below the median is read as the median: never a range that runs backwards.
    expect(await testsFor("chargebacks-with-evidence", store)).toEqual({ p50: 40, p90: 40, runs: 12, from: "2026-11-11" });
    for (const r of ["half", "zero", "nothing", "toString", "refunds-within-a-limit", undefined]) expect(await testsFor(r, store)).toBeUndefined();
    expect(await testsFor("chargebacks-with-evidence", undefined)).toBeUndefined();
    for (const table of [[], "x", 7, null]) expect(testsIn(table, "chargebacks-with-evidence")).toBeUndefined();
  });

  it("are none when the store can't be read, never a failure", async () => {
    const broken = { get: async () => { throw new Error("throttled"); } };
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    try {
      expect(await testsFor("chargebacks-with-evidence", broken)).toBeUndefined();
    } finally {
      warn.mockRestore();
    }
  });
});

// The routes and Coach's tool, on a world with KIFF's credits.

const run = (o: Partial<KIFFRun>): KIFFRun => ({ run: "r", trigger: "schedule", ended: "2026-11-19T08:00:00Z", counted: true, credits: 20, refused: 0, declined: 0, ...o });
const balance = (o: Partial<KIFFBalance> = {}): KIFFBalance => ({
  kind: "solo", state: "ok", meter_pct: 10, included: 500, used: 50, left: 450, packs_left: 0, over: 0, beyond: 0, beyond_cents: 0, limit_cents: 0,
  seats: 1, period: { start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z" }, can_raise: false, raise_to_cents: 0, can_buy: { solo: false, packs: true }, billed_by: "apple", ...o,
});

interface KIFFOpts {
  runs?: KIFFRun[];
  previous?: KIFFRun[];
  runsStatus?: number;
  /** A run page that never answers. */
  runsHang?: boolean;
  balance?: KIFFBalance;
  /** Whether the person manages Refunds (KIFF lists everyone's runs on it only then). */
  manage?: boolean;
  /** Coach's read of a taf's credits (POST /v1/coach/credits): its answer, or a status alone. */
  coach?: { status?: number; body?: unknown; hang?: boolean };
  /** What KIFF says the person in Coach's chat may do. */
  act?: { use?: boolean; status?: number };
  /** Each request to KIFF, by path, with its body. */
  seen?: Array<{ path: string; body?: unknown }>;
}

function kiff(opts: KIFFOpts = {}) {
  return (async (url: string, init?: RequestInit) => {
    const u = new URL(url);
    opts.seen?.push({ path: u.pathname, ...(init?.body ? { body: JSON.parse(String(init.body)) } : {}) });
    if (u.pathname === "/v1/coach/credits") {
      const c = opts.coach;
      if (!c) return new Response("{}", { status: 404 });
      if (c.hang) return new Promise<Response>(() => undefined);
      return new Response(JSON.stringify(c.body ?? {}), { status: c.status ?? 200 });
    }
    if (u.pathname === "/v1/coach/act") {
      if (opts.act?.status) return new Response("{}", { status: opts.act.status });
      return new Response(JSON.stringify({ use: opts.act?.use ?? true, oversee: false, manage: false }), { status: 200 });
    }
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    if (u.pathname === "/v1/me") return new Response(JSON.stringify(ME), { status: 200 });
    if (u.pathname === "/v1/me/access") return new Response(JSON.stringify({ agents: { "agt-refunds": { use: true, ...(opts.manage ? { manage: true } : {}) } } }), { status: 200 });
    if (u.pathname === "/v1/me/usage/credits") {
      return new Response(JSON.stringify({ period: { start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z" }, current: u.searchParams.get("period") !== "previous", credits: 400, runs: 20,
        by_agent: [{ agent: "agt-refunds", runtime_agent: REFUNDS, credits: 400, runs: 20 }], setup: { credits: 0, runs: 0 }, free: {}, balance: opts.balance ?? balance() }), { status: 200 });
    }
    if (u.pathname === "/v1/me/usage/credits/runs") {
      if (opts.runsHang) return new Promise<Response>(() => undefined);
      if (opts.runsStatus) return new Response("{}", { status: opts.runsStatus });
      const items = u.searchParams.get("period") === "previous" ? opts.previous ?? [] : opts.runs ?? [];
      return new Response(JSON.stringify({ items }), { status: 200 });
    }
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
}

function world(fetcher: typeof fetch, created = "2026-10-15T09:00:00Z") {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  for (const [id, name, agent] of [[REFUNDS, "Refunds", "agt-refunds"], [HIDDEN, "Payroll", "agt-hidden"]] as const) {
    tafs.held.set(`t_acme#${id}`, { id, name, job: "refunds", kiffAgent: agent, nameClaim: "c", created, budgetCents: 200 });
    void agents.api.putAgent("t_acme", id, {} as never);
  }
  void agents.api.createSchedule("t_acme", { id: REFUNDS, agent: REFUNDS, cron: "0 9 * * 1-5", input: "Work the queue.", enabled: true });
  const coach = memoryCoach(() => NOW);
  return {
    agents, tafs, coach,
    deps: {
      agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs,
      names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(),
      push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
      apns: { send: async () => "sent" as const }, forward: async () => undefined, fetch: fetcher, now: () => NOW, coach,
    },
  };
}

async function get(deps: ReturnType<typeof world>["deps"], path: string) {
  const r = await handle({ rawPath: path, requestContext: { http: { method: "GET" } }, headers: { Authorization: "Bearer ana" } } as HTTPEvent, deps);
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

/** Twenty counted runs over the last thirty days, twenty to thirty credits each, and a trial KIFF gave no credits for. */
const month = (): KIFFRun[] => [
  ...Array.from({ length: 20 }, (_, i) => run({ run: `r${i}`, credits: i < 16 ? 20 : 30, ended: new Date(NOW.getTime() - (i + 1) * 1.4 * DAY).toISOString() })),
  run({ run: "t1", trigger: "trial", free: "trial", counted: false, credits: 0 }),
];

describe("GET /v1/tafs/{id}/estimate", () => {
  it("answers from the taf's own counted runs in KIFF, held against the plan, and keeps the plan for Coach", async () => {
    const w = world(kiff({ runs: month() }));
    const r = await get(w.deps, `/v1/tafs/${REFUNDS}/estimate`);
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ source: "own", credits: { low: 400, high: 600 }, runs_a_month: { low: 20, high: 20 }, per_run: { low: 20, high: 30 },
      words: `About 400 to 600 credits a month. Your plan includes 500, so up to 100 more. ${NEVER}` });
    expect(await w.coach.get("t_acme#plan")).toMatchObject({ kind: "solo", included: 500, limit_cents: 0 });
  });

  it("falls to a trial's runs, with the credits KIFF says each would have used, and to none; never kiff/agents' runs", async () => {
    const trial = run({ run: "t1", trigger: "trial", free: "trial", counted: false, credits: 100 });
    const failed = run({ run: "f1", free: "failed", counted: false, credits: 900 });
    const w = world(kiff({ runs: [trial, failed] }));
    // A run kiff/agents keeps is no source here, whatever it cost.
    w.agents.runs.push({ id: "x", agent: REFUNDS, tenant: "t_acme", session: "s", trigger: "trial", status: "COMPLETED", cost_micro_usd: 9_000_000, turns: 3, created: "2026-11-19T08:00:00Z", updated: "2026-11-19T08:05:00Z" } as never);
    const r = await get(w.deps, `/v1/tafs/${REFUNDS}/estimate`);
    expect(r.body.source).toBe("trial");
    expect(r.body.per_run).toEqual({ low: 100, high: 100 });
    expect(r.body).not.toHaveProperty("ceiling");
    expect(r.body.words).toBe(`I can't tell yet how often work comes in. In its trial, a run used about 100 credits. ${FIRST_WEEK} ${NEVER}`);
    expect((await get(world(kiff({ runs: [failed] })).deps, `/v1/tafs/${REFUNDS}/estimate`)).body).toEqual({ source: "none", words: `${UNKNOWN} ${FIRST_WEEK} ${NEVER}` });
  });

  it("says our tests' figures for the taf's recipe when the store keeps them and it has no runs of its own", async () => {
    const w = world(kiff());
    w.tafs.held.set(`t_acme#${REFUNDS}`, { ...w.tafs.held.get(`t_acme#${REFUNDS}`)!, recipe: "chargebacks-with-evidence" });
    await w.coach.put(ESTIMATES_KEY, { "chargebacks-with-evidence": { p50: 110, p90: 220, runs: 12, from: "2026-11-11" } }, new Date(NOW.getTime() + 365 * DAY));
    const r = await get(w.deps, `/v1/tafs/${REFUNDS}/estimate`);
    expect(r.body).toMatchObject({ source: "tests", per_run: { low: 110, high: 220 } });
  });

  it("is a 404 for a taf the person can't see, and a 503 with words, never a figure, when KIFF's runs can't be read", async () => {
    expect((await get(world(kiff()).deps, `/v1/tafs/${HIDDEN}/estimate`)).status).toBe(404);
    const r = await get(world(kiff({ runsStatus: 500 })).deps, `/v1/tafs/${REFUNDS}/estimate`);
    expect(r.status).toBe(503);
    expect(r.body).not.toHaveProperty("credits");
    expect(r.body.words).toBe("Credits can't be read right now. Try again in a moment.");
  });
});

describe("Plan & usage: a taf's pace", () => {
  it("adds 'At this pace, about N this month' to a taf's row once seven days of its runs make a pace, and none before", async () => {
    const r = await get(world(kiff({ runs: month() })).deps, "/v1/usage/credits");
    expect(r.body.tafs[0]).toMatchObject({ taf: REFUNDS, pace: "At this pace, about 440 this month" });
    const young = await get(world(kiff({ runs: month().slice(0, 2) }), "2026-11-17T09:00:00Z").deps, "/v1/usage/credits");
    expect(young.body.tafs[0]).not.toHaveProperty("pace");
    // Runs that can't be read leave the row as it was.
    const down = await get(world(kiff({ runsStatus: 500 })).deps, "/v1/usage/credits");
    expect(down.status).toBe(200);
    expect(down.body.tafs[0]).not.toHaveProperty("pace");
    expect(paceLine(undefined, NOW)).toBeUndefined();
  });
});

describe("KIFF's credits for Coach (POST /v1/coach/credits)", () => {
  const ok = (runs: unknown) => kiff({ coach: { body: { runs } } });

  it("posts the call's permit and the taf's KIFF agent, and takes runs only in the shape KIFF promises", async () => {
    const seen: KIFFOpts["seen"] = [];
    const runs: CreditRun[] = [{ run: "a", ended: "2026-11-19T08:00:00Z", credits: 20, counted: true }, { run: "b", ended: "2026-11-18T08:00:00Z", credits: 40, counted: false, free: "trial" }];
    expect(await readCoachCredits("permit-1", "agt-refunds", kiff({ coach: { body: { runs } }, seen }), "https://kiff.test")).toEqual(runs);
    expect(seen).toEqual([{ path: "/v1/coach/credits", body: { permit: "permit-1", agent: "agt-refunds" } }]);
    // A free run with null for free, as JSON may carry it: no free.
    expect(await readCoachCredits("p", "a", ok([{ run: "a", ended: "2026-11-19T08:00:00Z", credits: 0, counted: true, free: null }]), "https://kiff.test"))
      .toEqual([{ run: "a", ended: "2026-11-19T08:00:00Z", credits: 0, counted: true }]);
  });

  it("refuses anything else: no permit, a status but 200, no list, or a run that isn't one", async () => {
    await expect(readCoachCredits("", "agt", ok([]), "https://kiff.test")).rejects.toThrow();
    await expect(readCoachCredits("p", "agt", kiff(), "https://kiff.test")).rejects.toThrow("404");
    await expect(readCoachCredits("p", "agt", kiff({ coach: { status: 204 } }), "https://kiff.test")).rejects.toThrow();
    await expect(readCoachCredits("p", "agt", kiff({ coach: { body: { items: [] } } }), "https://kiff.test")).rejects.toThrow();
    for (const bad of [{ run: "a", ended: "never", credits: 1, counted: true }, { run: "a", ended: "2026-11-19T08:00:00Z", credits: "1", counted: true },
      { run: "a", ended: "2026-11-19T08:00:00Z", credits: -1, counted: true }, { run: "a", ended: "2026-11-19T08:00:00Z", credits: 1 },
      { run: "", ended: "2026-11-19T08:00:00Z", credits: 1, counted: true }, { run: "a", ended: "2026-11-19T08:00:00Z", credits: 1, counted: true, free: 3 }, null]) {
      await expect(readCoachCredits("p", "agt", ok([bad]), "https://kiff.test"), JSON.stringify(bad)).rejects.toThrow();
    }
    await expect(readCoachCredits("p", "agt", (async () => { throw new Error("offline"); }) as typeof fetch, "https://kiff.test")).rejects.toThrow();
  });
});

describe("Coach's taf_estimate", () => {
  const callOf = { tenant: "t_acme", coach: "setup-acme", notify: () => ({ url: "https://tafs.test/v1/runs/ended", ref: "r" }) };
  const person = { ...callOf, person: { subject: "u_ana", permit: "permit-ana" } };
  /** Twenty counted runs over thirty days, 20 credits each, newest first, as KIFF answers Coach. */
  const counted = (): CreditRun[] => Array.from({ length: 20 }, (_, i) => ({ run: `r${i}`, ended: new Date(NOW.getTime() - (i + 1) * 1.4 * DAY).toISOString(), credits: 20, counted: true }));
  type Answer = { estimate: boolean; source?: string; words?: string };

  it("answers an existing taf's words from KIFF's counted credits, read with the call's permit, and the plan the balance read kept", async () => {
    const seen: KIFFOpts["seen"] = [];
    const w = world(kiff({ coach: { body: { runs: counted() } }, seen }));
    await w.coach.put("t_acme#plan", { kind: "solo", included: 1000, limit_cents: 0 }, new Date(NOW.getTime() + DAY));
    const a = await coachTool("taf_estimate", { taf: "Refunds" }, person, w.deps) as Answer;
    expect(a).toMatchObject({ estimate: true, source: "own" });
    // Twenty runs in thirty days at 20 credits each.
    expect(a.words).toBe(`About 400 credits a month. On its own, it fits inside your plan's 1,000 a month. ${NEVER}`);
    expect(seen.find((s) => s.path === "/v1/coach/credits")).toEqual({ path: "/v1/coach/credits", body: { permit: "permit-ana", agent: "agt-refunds" } });
    const de = await coachTool("taf_estimate", { taf: REFUNDS, lang: "de" }, person, w.deps) as Answer;
    expect(de.words!.startsWith("Etwa 400 Credits im Monat.")).toBe(true);
  });

  it("takes a new version's trial runs from KIFF, with the credits KIFF says they would have used, and leaves failed ones out", async () => {
    const runs: CreditRun[] = [
      { run: "t1", ended: "2026-11-19T08:00:00Z", credits: 60, counted: false, free: "trial" },
      { run: "t2", ended: "2026-11-18T08:00:00Z", credits: 60, counted: false, free: "trial" },
      { run: "f1", ended: "2026-11-18T09:00:00Z", credits: 900, counted: false, free: "failed" },
    ];
    const a = await coachTool("taf_estimate", { taf: REFUNDS }, person, world(kiff({ coach: { body: { runs } } })).deps) as Answer;
    expect(a).toMatchObject({ estimate: true, source: "trial" });
    expect(a.words).toBe(`I can't tell yet how often work comes in. In its trial, a run used about 60 credits. ${FIRST_WEEK}`);
  });

  // KIFF's route may not be there yet, or may answer something else: the source is left out, the line falls to
  // what's left, and nothing is guessed. Remove the fallback and this fails.
  it("leaves KIFF's credits out when they can't be read: no permit, a 404 from a KIFF without the route, a bad shape, or late", async () => {
    const honest = { estimate: true, source: "none", words: `${UNKNOWN} ${FIRST_WEEK}` };
    const pick = (a: Answer) => ({ estimate: a.estimate, source: a.source, words: a.words });
    // No person in the chat, so no permit: KIFF isn't asked.
    const seen: KIFFOpts["seen"] = [];
    expect(pick(await coachTool("taf_estimate", { taf: REFUNDS }, callOf, world(kiff({ coach: { body: { runs: counted() } }, seen })).deps) as Answer)).toEqual(honest);
    expect(seen.some((s) => s.path === "/v1/coach/credits")).toBe(false);
    expect(pick(await coachTool("taf_estimate", { taf: REFUNDS }, person, world(kiff()).deps) as Answer)).toEqual(honest);
    expect(pick(await coachTool("taf_estimate", { taf: REFUNDS }, person, world(kiff({ coach: { status: 500 } })).deps) as Answer)).toEqual(honest);
    expect(pick(await coachTool("taf_estimate", { taf: REFUNDS }, person, world(kiff({ coach: { body: { runs: [{ run: "a" }] } } })).deps) as Answer)).toEqual(honest);
    // Late: past its own deadline, inside the tool's, so the answer still comes, without it.
    vi.useFakeTimers();
    try {
      const slow = coachTool("taf_estimate", { taf: REFUNDS }, person, world(kiff({ coach: { hang: true } })).deps);
      await vi.advanceTimersByTimeAsync(2100);
      expect(pick(await slow as Answer)).toEqual(honest);
    } finally {
      vi.useRealTimers();
    }
  });

  it("answers a new taf from its draft: our tests' figures by recipe when there are some, else the honest line", async () => {
    const w = world(kiff());
    await w.coach.put("t_acme#plan", { kind: "company", included: 5000, limit_cents: 10_000 }, new Date(NOW.getTime() + DAY));
    // Nothing kept yet: no figure is made up for the recipe.
    const a = await coachTool("taf_estimate", { recipe: "refunds-within-a-limit" }, person, w.deps) as Answer;
    expect(a).toMatchObject({ estimate: true, source: "none", words: `${UNKNOWN} ${FIRST_WEEK} ${NEVER_COMPANY}` });
    // Kept for one recipe: that recipe's line says our tests, and any other still says it can't tell.
    await w.coach.put(ESTIMATES_KEY, { "chargebacks-with-evidence": { p50: 110, p90: 220, runs: 12, from: "2026-11-11" } }, new Date(NOW.getTime() + 365 * DAY));
    const kept = await coachTool("taf_estimate", { recipe: "chargebacks-with-evidence" }, person, w.deps) as Answer;
    expect(kept).toMatchObject({ estimate: true, source: "tests" });
    expect(kept.words).toContain("In our tests, a run of this job used about 110 to 220 credits.");
    expect(await coachTool("taf_estimate", { recipe: "refunds-within-a-limit" }, person, w.deps)).toMatchObject({ source: "none" });
  });

  // The guard: an estimate never holds anything up. Whatever fails answers that there's none this time, and
  // Coach goes on without the line. Remove the guard and this fails.
  it("answers that there's no estimate this time when KIFF can't say what the person may see, or anything takes too long, and never a figure", async () => {
    const none = { estimate: false, say: "There's no estimate this time: say nothing about cost, and go on." };
    const w = world(kiff({ coach: { body: { runs: counted() } } }));
    expect(await coachTool("taf_estimate", { taf: REFUNDS }, person, { ...w.deps, fetch: (async () => { throw new Error("offline"); }) as typeof fetch })).toEqual(none);
    expect(await coachTool("taf_estimate", { taf: REFUNDS }, person, world(kiff({ act: { status: 503 }, coach: { body: { runs: counted() } } })).deps)).toEqual(none);
    // A taf that isn't shared with them is Coach's to hear.
    await expect(coachTool("taf_estimate", { taf: REFUNDS }, person, world(kiff({ act: { use: false } })).deps)).rejects.toThrow("isn't shared with them");
    // Slow: past its deadline, no estimate, and nothing waits on it longer.
    vi.useFakeTimers();
    try {
      const hang = (async (url: string) => (String(url).endsWith("/v1/coach/act") ? new Promise<Response>(() => undefined) : new Response("{}", { status: 404 }))) as typeof fetch;
      const slow = coachTool("taf_estimate", { taf: REFUNDS }, person, { ...w.deps, fetch: hang });
      await vi.advanceTimersByTimeAsync(3100);
      expect(await slow).toEqual(none);
    } finally {
      vi.useRealTimers();
    }
    // A taf that isn't there is Coach's to hear, as from any tool.
    await expect(coachTool("taf_estimate", { taf: "Nobody" }, callOf, w.deps)).rejects.toThrow("there is no taf");
  });

  it("is called by no approval or result path: only its route, its tool, Plan & usage and the conversation read reach KIFF's runs", () => {
    const src = readFileSync(new URL("../src/cloud/handler.ts", import.meta.url), "utf8");
    const users = (name: string) => [...src.matchAll(new RegExp(`\\b${name}\\(`, "g"))].length;
    // Each is defined once and called from one place.
    expect(users("estimateRoute")).toBe(2);
    expect(users("estimateTool")).toBe(2);
    expect(users("paceLines")).toBe(2);
    expect(users("weekOneOnRead")).toBe(2);
    expect(users("readCoachCredits")).toBe(1);
    const fn = (name: string) => { const at = src.indexOf(`async function ${name}(`); return src.slice(at, src.indexOf("\n}\n", at)); };
    for (const path of ["newTafFor", "improvedVersionFor", "handOver", "approvalRoute"].filter((n) => src.includes(`async function ${n}(`))) {
      expect(fn(path), path).not.toMatch(/estimate(Route|Tool)\(|recordFromKIFF\(|readCoachCredits\(|paceLines\(|weekOneOnRead\(/);
    }
  });
});

describe("the week-one note, on the taf's conversation read", () => {
  const START = "2026-11-12T09:00:00Z";
  const at = (h: number) => new Date(Date.parse(START) + h * 3600_000).toISOString();
  const NOTE = "Refunds used 120 credits in its first week, so about 510 a month at this pace.";
  /** Three counted runs in its first week, 40 credits each, one after it, and a failed one KIFF didn't count. */
  const firstWeek = (): KIFFRun[] => [
    run({ run: "late", ended: at(7 * 24 + 5), credits: 500 }),
    run({ run: "r3", ended: at(80), credits: 40 }), run({ run: "r2", ended: at(30), credits: 40 }),
    run({ run: "f1", ended: at(5), credits: 900, counted: false, free: "failed" }),
    run({ run: "r1", ended: at(2), credits: 40 }),
  ];
  const read = async (deps: ReturnType<typeof world>["deps"]) => {
    const r = await get(deps, `/v1/tafs/${REFUNDS}/conversation`);
    return { status: r.status, notes: (r.body.messages as Array<{ text: string; note?: boolean }>).filter((m) => m.note).map((m) => m.text) };
  };

  it("is posted once, on the first read 7 days after the taf started, from its first week's counted credits in KIFF", async () => {
    const seen: KIFFOpts["seen"] = [];
    const w = world(kiff({ runs: firstWeek(), manage: true, seen }), START);
    const later = new Date("2026-11-19T10:00:00Z");
    w.deps.now = () => later;
    expect(await read(w.deps)).toEqual({ status: 200, notes: [NOTE] });
    const taf = w.tafs.held.get(`t_acme#${REFUNDS}`)!;
    expect(taf.weekOne).toEqual({ at: later.toISOString(), credits: 120, month: 510 });
    // Kept once: the next read reads no runs for it, and shows the same note.
    const runsRead = seen.filter((s) => s.path === "/v1/me/usage/credits/runs").length;
    expect(await read(w.deps)).toEqual({ status: 200, notes: [NOTE] });
    expect(seen.filter((s) => s.path === "/v1/me/usage/credits/runs").length).toBe(runsRead);
    expect(w.tafs.held.get(`t_acme#${REFUNDS}`)!.weekOne).toEqual(taf.weekOne);
  });

  it("is never posted before the week is out, nor to a taf from long before, nor on the read of someone who doesn't manage it", async () => {
    const seen: KIFFOpts["seen"] = [];
    const early = world(kiff({ runs: firstWeek(), manage: true, seen }), "2026-11-15T09:00:00Z");
    expect(await read(early.deps)).toEqual({ status: 200, notes: [] });
    const old = world(kiff({ runs: firstWeek(), manage: true, seen }), "2026-10-01T09:00:00Z");
    expect(await read(old.deps)).toEqual({ status: 200, notes: [] });
    expect(seen.some((s) => s.path === "/v1/me/usage/credits/runs")).toBe(false);
    // KIFF lists only their own runs to someone who doesn't manage the taf: no partial week.
    const user = world(kiff({ runs: firstWeek() }), START);
    user.deps.now = () => new Date("2026-11-19T10:00:00Z");
    expect(await read(user.deps)).toEqual({ status: 200, notes: [] });
    expect([...user.tafs.held.values(), ...old.tafs.held.values()].every((t: Taf) => !t.weekOne)).toBe(true);
  });

  // Best effort: the thread never fails or waits on it, and a later read tries again.
  it("never fails the conversation read: KIFF down posts nothing, and the next read posts it", async () => {
    let down = true;
    const up = kiff({ runs: firstWeek(), manage: true });
    const w = world((async (url: string, init?: RequestInit) => (down && String(url).includes("/usage/credits/runs") ? new Response("{}", { status: 500 }) : up(url, init))) as typeof fetch, START);
    w.deps.now = () => new Date("2026-11-19T10:00:00Z");
    expect(await read(w.deps)).toEqual({ status: 200, notes: [] });
    expect(w.tafs.held.get(`t_acme#${REFUNDS}`)!.weekOne).toBeUndefined();
    down = false;
    expect(await read(w.deps)).toEqual({ status: 200, notes: [NOTE] });
  });

  it("never holds the conversation read past a second and a half", async () => {
    const w = world(kiff({ runs: firstWeek(), manage: true, runsHang: true }), START);
    w.deps.now = () => new Date("2026-11-19T10:00:00Z");
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      const pending = read(w.deps);
      await vi.advanceTimersByTimeAsync(1600);
      expect(await pending).toEqual({ status: 200, notes: [] });
    } finally {
      vi.useRealTimers();
    }
    expect(w.tafs.held.get(`t_acme#${REFUNDS}`)!.weekOne).toBeUndefined();
  });
});
