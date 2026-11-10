import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import type { Run } from "../src/cloud/agents.js";
import type { KIFFBalance, KIFFRun } from "../src/cloud/credits.js";
import { ceilingOf, creditsOf, estimate, type Own, paceLine, weekOneWords } from "../src/cloud/estimate.js";
import { coachTool, handle, type HTTPEvent, sweepWeekOne, type Taf } from "../src/cloud/handler.js";
import { LANGUAGES, wordsIn } from "../src/cloud/words/index.js";
import { creditsOf as scriptCreditsOf, tableFrom } from "../scripts/estimates.mjs";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const REFUNDS = "taf-3f9a1c0b7d2e4a6f8b91";
const HIDDEN = "taf-ffff0000000000000000";
const NOW = new Date("2026-11-20T12:00:00Z");
const DAY = 86_400_000;
const NEVER = "It never goes past your balance: when credits run out, it waits.";
const FIRST_WEEK = "After its first week, you'll see what it really uses.";

/** n runs, one a day back from now, each with these credits. */
const daily = (credits: number[]): Own["runs"] => credits.map((c, i) => ({ at: new Date(NOW.getTime() - (i + 1) * DAY).toISOString(), credits: c }));
/** Twenty days of history: enough for a pace. */
const own = (credits: number[], days = 20): Own => ({ runs: daily(credits), from: new Date(NOW.getTime() - days * DAY).toISOString() });
const solo = { kind: "solo" as const, included: 500, limit_cents: 0 };
const company = { kind: "company" as const, included: 500, limit_cents: 5000 };

describe("KIFF's meter, mirrored", () => {
  it("counts credits from a run's AI cost as KIFF does: ceil(cost × 100 × 100 × 4 / (1,000,000 × 108)), the same in the generator", () => {
    expect(creditsOf(0)).toBe(0);
    expect(creditsOf(-5)).toBe(0);
    expect(creditsOf(2700)).toBe(1);
    expect(creditsOf(2701)).toBe(2);
    expect(creditsOf(270_000)).toBe(100);
    expect(creditsOf(1)).toBe(1);
    for (const c of [1, 999, 2699, 2700, 2701, 54_000, 123_457, 2_000_000]) {
      expect(creditsOf(c)).toBe(Math.ceil((c * 100 * 100 * 4) / (1_000_000 * 108) - 1e-12));
      expect(scriptCreditsOf(c)).toBe(creditsOf(c));
    }
  });
});

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
    expect(estimate({ now: NOW })).toEqual({ source: "none", words: `I can't tell yet what it will use. ${FIRST_WEEK} ${NEVER}` });
  });

  it("counts only runs that used credits, and takes runs a month from the taf's own pace only", () => {
    const e = estimate({ own: own([0, 20, 20, 20, 20, 20, 20, 20, 20, 30, 40]), now: NOW });
    // Ten counted runs over twenty days: fifteen a month.
    expect(e.runs_a_month).toEqual({ low: 15, high: 15 });
    expect(e.per_run).toEqual({ low: 20, high: 30 });
    expect(e.credits).toEqual({ low: 300, high: 450 });
    // A trial's cost per run, with no pace of the taf's own: no credits a month.
    expect(estimate({ trial: [100, 120], now: NOW })).not.toHaveProperty("credits");
  });

  it("calls nothing a pace before seven days of history, and says so honestly", () => {
    const young = estimate({ own: own([20, 30], 5), now: NOW });
    expect(young.runs_a_month).toBeUndefined();
    expect(young.words).toBe(`I can't tell yet how often work comes in. So far, a run used about 20 to 30 credits. ${FIRST_WEEK} ${NEVER}`);
    expect(estimate({ tests, now: NOW }).words)
      .toBe(`I can't tell yet how often work comes in. In our tests, a run of this job used about 110 to 220 credits. ${FIRST_WEEK} ${NEVER}`);
    expect(estimate({ trial: [100, 100], now: NOW }).words).toContain("In its trial, a run used about 100 credits.");
  });

  it("says the ceiling only without a pace: the schedule's firings a month at the run's cap", () => {
    const ceiling = { firings: 30, cap: 741 };
    const none = estimate({ ceiling, now: NOW });
    expect(none.ceiling).toBe(22_230);
    expect(none.words).toBe(`I can't tell yet what it will use. At most 22,230 credits a month if every check found work. ${FIRST_WEEK} ${NEVER}`);
    const paced = estimate({ own: own([20, 20, 20]), ceiling, now: NOW });
    expect(paced.ceiling).toBeUndefined();
    expect(paced.words).not.toContain("At most");
    // A weekday schedule at nine: about 22 firings in 30 days, each at a 2-dollar run's cap (741 credits).
    const c = ceilingOf([{ id: "s", agent: "t", cron: "0 9 * * 1-5", time_zone: "UTC", input: "", enabled: true }], 200, NOW);
    expect(c?.cap).toBe(741);
    expect(c?.firings).toBeGreaterThanOrEqual(20);
    expect(c?.firings).toBeLessThanOrEqual(23);
    expect(ceilingOf([{ id: "s", agent: "t", cron: "0 9 * * 1-5", input: "", enabled: false }], 200, NOW)).toBeUndefined();
  });
});

describe("a taf's estimate, against the plan", () => {
  const paced = (credits: number[]) => own(credits.flatMap((c) => [c, c]), 20);

  it("says what the plan covers first: fits inside it", () => {
    // 20 runs over 20 days: 30 a month, at 10 to 15 credits each.
    const e = estimate({ own: paced([10, 10, 10, 10, 10, 10, 10, 10, 15, 15]), plan: solo, now: NOW });
    expect(e.words).toBe(`About 300 to 450 credits a month. Fits inside your plan's 500 a month. ${NEVER}`);
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

  it("on Company, past the plan: the credits past it, their euros at €0.01, and the account's own limit, honestly", () => {
    const e = estimate({ own: paced([40, 40, 40, 40, 40, 40, 40, 40, 60, 60]), plan: company, now: NOW });
    expect(e.words).toBe(`About 1,200 to 1,800 credits a month, about 700 to 1,300 past the plan's credits, about €7 to €13 at €0.01, within your €50 spend limit. ${NEVER}`);
    expect(estimate({ own: paced([40, 40, 40, 40, 40, 40, 40, 40, 60, 60]), plan: { ...company, limit_cents: 1000 }, now: NOW }).words)
      .toContain("about €7 to €13 at €0.01, past your €10 spend limit.");
    expect(estimate({ own: paced([10, 10, 10, 10, 10, 10, 10, 10, 15, 15]), plan: company, now: NOW }).words)
      .toBe(`About 300 to 450 credits a month. Fits inside your plan's 500 a month. ${NEVER}`);
  });

  it("with no plan to hold it against, the credits alone", () => {
    expect(estimate({ own: paced([10, 10, 10, 10, 10, 10, 10, 10, 15, 15]), plan: { kind: "unbounded", included: 0, limit_cents: 0 }, now: NOW }).words)
      .toBe(`About 300 to 450 credits a month. ${NEVER}`);
  });

  it("is in all eight languages, each line its own", () => {
    const e = { own: paced([40, 40, 40, 40, 40, 40, 40, 40, 60, 60]), plan: company, now: NOW };
    const lines = LANGUAGES.map((lang) => estimate(e, lang).words);
    expect(new Set(lines).size).toBe(8);
    expect(estimate(e, "de").words).toContain("Ausgabenlimit");
    expect(estimate({ now: NOW }, "pl").words).toBe(`${wordsIn("pl").est_unknown} ${wordsIn("pl").est_first_week} ${wordsIn("pl").est_never_past}`);
    for (const lang of LANGUAGES) expect(weekOneWords("Refunds", { at: "", credits: 120, month: 510 }, lang)).toContain("Refunds");
  });
});

describe("the generator of our tests' figures", () => {
  it("reads an Eval report's runs into credits per run by recipe, leaving out runs without a cost and cases with no recipe", () => {
    const results = [
      { case: "refunds", cost_micro_usd: 270_000 }, { case: "refunds", cost_micro_usd: 540_000 }, { case: "refunds", cost_micro_usd: 0 },
      { case: "chargebacks", cost_micro_usd: 27_000 }, { case: "leads", cost_micro_usd: 100 },
    ];
    const { table, unnamed } = tableFrom(results, { refunds: "refunds-within-a-limit", chargebacks: "chargebacks-with-evidence" }, "2026-11-10");
    expect(table).toEqual({
      "chargebacks-with-evidence": { p50: 10, p90: 10, runs: 1, from: "2026-11-10" },
      "refunds-within-a-limit": { p50: 100, p90: 200, runs: 2, from: "2026-11-10" },
    });
    expect(unnamed).toEqual(["leads"]);
  });

  it("is committed empty: no figure until an Eval report is read", () => {
    expect(JSON.parse(readFileSync(new URL("../src/cloud/estimates.json", import.meta.url), "utf8"))).toEqual({});
  });
});

// The routes and Coach's tool, on a world with KIFF's credits and kiff/agents' runs.

const run = (o: Partial<KIFFRun>): KIFFRun => ({ run: "r", trigger: "schedule", ended: "2026-11-19T08:00:00Z", counted: true, credits: 20, refused: 0, declined: 0, ...o });
const balance = (o: Partial<KIFFBalance> = {}): KIFFBalance => ({
  kind: "solo", state: "ok", meter_pct: 10, included: 500, used: 50, left: 450, packs_left: 0, over: 0, beyond: 0, beyond_cents: 0, limit_cents: 0,
  seats: 1, period: { start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z" }, can_raise: false, raise_to_cents: 0, can_buy: { solo: false, packs: true }, billed_by: "apple", ...o,
});

function kiff(opts: { runs?: KIFFRun[]; previous?: KIFFRun[]; runsStatus?: number; balance?: KIFFBalance } = {}) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    const u = new URL(url);
    if (u.pathname === "/v1/me") return new Response(JSON.stringify(ME), { status: 200 });
    if (u.pathname === "/v1/me/access") return new Response(JSON.stringify({ agents: { "agt-refunds": { use: true } } }), { status: 200 });
    if (u.pathname === "/v1/me/usage/credits") {
      return new Response(JSON.stringify({ period: { start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z" }, current: u.searchParams.get("period") !== "previous", credits: 400, runs: 20,
        by_agent: [{ agent: "agt-refunds", runtime_agent: REFUNDS, credits: 400, runs: 20 }], setup: { credits: 0, runs: 0 }, free: {}, balance: opts.balance ?? balance() }), { status: 200 });
    }
    if (u.pathname === "/v1/me/usage/credits/runs") {
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

/** Twenty counted runs over the last thirty days, twenty to thirty credits each, and a trial that wasn't counted. */
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

  it("falls to a trial's runs in kiff/agents, metered as KIFF meters them, with the ceiling of its schedule, and to none", async () => {
    const w = world(kiff({ runs: [] }));
    w.agents.runs.push({ id: "tr1", agent: REFUNDS, tenant: "t_acme", session: "s", trigger: "trial", status: "COMPLETED", cost_micro_usd: 270_000, turns: 3, created: "2026-11-19T08:00:00Z", updated: "2026-11-19T08:05:00Z" } as Run & { tenant: string });
    const r = await get(w.deps, `/v1/tafs/${REFUNDS}/estimate`);
    expect(r.body.source).toBe("trial");
    expect(r.body.per_run).toEqual({ low: 100, high: 100 });
    expect(r.body.ceiling).toBeGreaterThan(0);
    expect(r.body.words).toContain("In its trial, a run used about 100 credits.");
    expect(r.body.words).toContain("if every check found work");
    w.agents.runs.length = 0;
    expect((await get(w.deps, `/v1/tafs/${REFUNDS}/estimate`)).body.source).toBe("none");
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

describe("Coach's taf_estimate", () => {
  const callOf = { tenant: "t_acme", coach: "setup-acme", notify: () => ({ url: "https://tafs.test/v1/runs/ended", ref: "r" }) };
  const ended = (i: number, cost: number): Run & { tenant: string } => ({ id: `r${i}`, agent: REFUNDS, tenant: "t_acme", session: "s", trigger: "schedule", status: "COMPLETED",
    cost_micro_usd: cost, turns: 3, created: new Date(NOW.getTime() - (i + 1) * DAY).toISOString(), updated: new Date(NOW.getTime() - (i + 1) * DAY).toISOString() });

  it("answers an existing taf's words from its runs in kiff/agents and the plan the balance read kept, in the language asked", async () => {
    const w = world(kiff());
    for (let i = 0; i < 20; i++) w.agents.runs.push(ended(i, 54_000));
    await w.coach.put("t_acme#plan", { kind: "solo", included: 1000, limit_cents: 0 }, new Date(NOW.getTime() + DAY));
    const a = await coachTool("taf_estimate", { taf: "Refunds" }, callOf, w.deps) as { estimate: boolean; source: string; words: string };
    expect(a).toMatchObject({ estimate: true, source: "own" });
    // Twenty runs in thirty days at 20 credits each.
    expect(a.words).toBe(`About 400 credits a month. Fits inside your plan's 1,000 a month. ${NEVER}`);
    const de = await coachTool("taf_estimate", { taf: REFUNDS, lang: "de" }, callOf, w.deps) as { words: string };
    expect(de.words.startsWith("Etwa 400 Credits im Monat.")).toBe(true);
  });

  it("answers a new taf from its draft: our tests' figures by recipe when there are some, else the honest line and the ceiling", async () => {
    const w = world(kiff());
    const a = await coachTool("taf_estimate", { recipe: "refunds-within-a-limit", cron: "0 9 * * 1-5" }, callOf, w.deps) as { estimate: boolean; source: string; words: string };
    // The table is empty until an Eval report is read: no figure is made up for the recipe.
    expect(a.source).toBe("none");
    expect(a.words).toMatch(/^I can't tell yet what it will use\. At most [\d,]+ credits a month if every check found work\. /);
  });

  // The guard: an estimate never holds anything up. Whatever fails answers that there's none this time, and
  // Coach goes on without the line. Remove the guard and this fails.
  it("answers that there's no estimate this time when anything fails or takes too long, and never a figure", async () => {
    const w = world(kiff());
    w.agents.fail.set("runs", new Error("kiff/agents is down"));
    const down = await coachTool("taf_estimate", { taf: REFUNDS }, callOf, w.deps);
    expect(down).toEqual({ estimate: false, say: "There's no estimate this time: say nothing about cost, and go on." });
    // KIFF not answering what the person may see: no estimate either.
    const person = { ...callOf, person: { subject: "u_ana", permit: "p" } };
    w.agents.fail.delete("runs");
    expect(await coachTool("taf_estimate", { taf: REFUNDS }, person, { ...w.deps, fetch: (async () => { throw new Error("offline"); }) as typeof fetch }))
      .toMatchObject({ estimate: false });
    // Slow: past its deadline, no estimate, and nothing waits on it longer.
    vi.useFakeTimers();
    try {
      const slow = coachTool("taf_estimate", { taf: REFUNDS }, callOf, { ...w.deps, agents: { ...w.deps.agents, runs: () => new Promise<Run[]>(() => undefined) } });
      await vi.advanceTimersByTimeAsync(3100);
      expect(await slow).toMatchObject({ estimate: false });
    } finally {
      vi.useRealTimers();
    }
    // A taf that isn't there is Coach's to hear, as from any tool.
    await expect(coachTool("taf_estimate", { taf: "Nobody" }, callOf, w.deps)).rejects.toThrow("there is no taf");
  });

  it("is called by no approval or result path: only its route, its tool and Plan & usage read it", () => {
    const src = readFileSync(new URL("../src/cloud/handler.ts", import.meta.url), "utf8");
    const users = (name: string) => [...src.matchAll(new RegExp(`\\b${name}\\(`, "g"))].length;
    // Each is defined once and called from one place.
    expect(users("estimateRoute")).toBe(2);
    expect(users("estimateTool")).toBe(2);
    expect(users("paceLines")).toBe(2);
    const fn = (name: string) => { const at = src.indexOf(`async function ${name}(`); return src.slice(at, src.indexOf("\n}\n", at)); };
    for (const path of ["newTafFor", "improvedVersionFor", "handOver", "approvalRoute"].filter((n) => src.includes(`async function ${n}(`))) {
      expect(fn(path), path).not.toMatch(/estimate(Route|Tool)\(|ownFrom(KIFF|Agents)\(|paceLines\(/);
    }
  });
});

describe("the week-one note", () => {
  const at = (h: number) => new Date(Date.parse("2026-11-12T09:00:00Z") + h * 3600_000).toISOString();

  it("is posted once in the taf's chat, seven days after it went live, with its first week's credits and a month at that pace", async () => {
    const w = world(kiff(), "2026-11-12T09:00:00Z");
    const later = new Date("2026-11-19T10:00:00Z");
    w.deps.now = () => later;
    for (const [i, h] of [[1, 2], [2, 30], [3, 80]] as const) {
      w.agents.runs.push({ id: `r${i}`, agent: REFUNDS, tenant: "t_acme", session: "s", trigger: "schedule", status: "COMPLETED", cost_micro_usd: 108_000, turns: 2, created: at(h), updated: at(h) } as Run & { tenant: string });
    }
    // A failed run isn't counted, as KIFF counts none.
    w.agents.runs.push({ id: "r4", agent: REFUNDS, tenant: "t_acme", session: "s", trigger: "schedule", status: "ERROR", cost_micro_usd: 999_000, turns: 1, created: at(5), updated: at(5) } as Run & { tenant: string });
    expect(await sweepWeekOne(w.deps)).toContainEqual({ tenant: "t_acme", taf: REFUNDS });
    const taf = w.tafs.held.get(`t_acme#${REFUNDS}`)!;
    expect(taf.weekOne).toEqual({ at: later.toISOString(), credits: 120, month: 510 });
    expect(weekOneWords(taf.name, taf.weekOne!)).toBe("Refunds used 120 credits in its first week, so about 510 a month at this pace.");
    // Kept once: the next sweep posts nothing more.
    expect(await sweepWeekOne(w.deps)).toEqual([]);
    expect(w.tafs.held.get(`t_acme#${REFUNDS}`)!.weekOne).toEqual(taf.weekOne);
    // In the taf's thread, as tafs cloud's note.
    const r = await handle({ rawPath: `/v1/tafs/${REFUNDS}/conversation`, requestContext: { http: { method: "GET" } }, headers: { Authorization: "Bearer ana" } } as HTTPEvent, w.deps);
    const notes = (JSON.parse(r.body).messages as Array<{ text: string; note?: boolean }>).filter((m) => m.note);
    expect(notes.map((m) => m.text)).toEqual(["Refunds used 120 credits in its first week, so about 510 a month at this pace."]);
  });

  it("is never posted before the week is out, nor to a taf from long before", async () => {
    const w = world(kiff(), "2026-11-15T09:00:00Z");
    expect(await sweepWeekOne(w.deps)).toEqual([]);
    const old = world(kiff(), "2026-10-01T09:00:00Z");
    expect(await sweepWeekOne(old.deps)).toEqual([]);
    expect([...old.tafs.held.values()].every((t: Taf) => !t.weekOne)).toBe(true);
  });
});
