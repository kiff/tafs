import { describe, expect, it } from "vitest";
import { creditsView, type KIFFCredits, type KIFFRun, NOTE, opens, periodWords, runWords, tafCreditsView, UNREADABLE } from "../src/cloud/credits.js";
import { handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const REFUNDS = "taf-3f9a1c0b7d2e4a6f8b91";
const CHARGEBACKS = "taf-0b7c2e9d4a1f6e3b8c50";
const HIDDEN = "taf-ffff0000000000000000";
const NOW = new Date("2026-11-07T12:00:00Z");
const NOV = { start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z" };

const kiffCredits = (over: Partial<KIFFCredits> = {}): KIFFCredits => ({
  period: NOV, current: true, credits: 1240, runs: 61,
  by_agent: [
    { agent: "agt-chargebacks", runtime_agent: CHARGEBACKS, credits: 374, runs: 18 },
    { agent: "agt-refunds", runtime_agent: REFUNDS, credits: 820, runs: 41 },
  ],
  setup: { credits: 46, runs: 2 },
  free: { setup: 7, trial: 3, failed: 2 },
  ...over,
});

const tafs = [
  { id: REFUNDS, name: "Refunds", kiffAgent: "agt-refunds", opens: true },
  { id: CHARGEBACKS, name: "Chargebacks", kiffAgent: "agt-chargebacks", opens: true },
  { id: HIDDEN, name: "Payroll", kiffAgent: "agt-hidden", opens: false },
];

describe("the period's credits, in words", () => {
  it("says what the period used and when it ends, or the last one's dates", () => {
    expect(periodWords({ period: NOV, current: true, credits: 1240 })).toBe("This period: 1,240 credits used · ends on 30 Nov");
    expect(periodWords({ period: NOV, current: true, credits: 0 })).toBe("This period: no credits used yet · ends on 30 Nov");
    expect(periodWords({ period: { start: "2026-10-01T00:00:00Z", end: "2026-11-01T00:00:00Z" }, current: false, credits: 1 }))
      .toBe("Last period: 1 credit used · 1 Oct to 31 Oct");
    expect(periodWords({ period: { start: "2026-10-01T00:00:00Z", end: "2026-11-01T00:00:00Z" }, current: false, credits: 0 }))
      .toBe("Last period: no credits used · 1 Oct to 31 Oct");
  });

  it("names its days as KIFF bounds the period (UTC), whatever the account's zone", async () => {
    // In Paris, the instant before 1 Dec 00:00 UTC is already 1 Dec: the day before the end's date is 30 Nov.
    const r = await get(world(kiff({ credits: kiffCredits({ credits: 0, by_agent: [] }), me: { ...ME, time_zone: "Europe/Paris" } })), "/v1/usage/credits");
    expect(r.body.words).toBe("This period: no credits used yet · ends on 30 Nov");
    const west = await get(world(kiff({ credits: kiffCredits({ current: false, credits: 0, by_agent: [], period: { start: "2026-10-01T00:00:00Z", end: "2026-11-01T00:00:00Z" } }),
      me: { ...ME, time_zone: "America/New_York" } })), "/v1/usage/credits", "period=previous");
    expect(west.body.words).toBe("Last period: no credits used · 1 Oct to 31 Oct");
    // A period Stripe bounds mid-day ends the day before its end's date.
    expect(periodWords({ period: { start: "2026-10-20T09:12:00Z", end: "2026-11-20T09:12:00Z" }, current: true, credits: 3 }))
      .toBe("This period: 3 credits used · ends on 19 Nov");
  });

  it("names each taf that used credits, most first, Coach past what's included, and what wasn't counted", () => {
    expect(creditsView(kiffCredits(), tafs)).toEqual({
      period: { ...NOV, current: true },
      credits: 1240, runs: 61,
      words: "This period: 1,240 credits used · ends on 30 Nov",
      tafs: [
        { taf: REFUNDS, credits: 820, runs: 41, words: "Refunds · 820 credits · 41 runs" },
        { taf: CHARGEBACKS, credits: 374, runs: 18, words: "Chargebacks · 374 credits · 18 runs" },
      ],
      coach: { credits: 46, runs: 2, words: "Coach, past what's included · 46 credits · 2 runs" },
      not_counted: {
        runs: 12, words: "Not counted: 12 runs",
        lines: [
          { why: "coach", runs: 7, words: "Coach, included · 7 runs" },
          { why: "trial", runs: 3, words: "Trials of a new version · 3 runs" },
          { why: "failed", runs: 2, words: "Failed on our side · 2 runs" },
        ],
      },
      note: NOTE,
    });
  });

  it("puts a taf the person doesn't see, and one removed since, on one row that doesn't open", () => {
    const k = kiffCredits({ by_agent: [
      { agent: "agt-refunds", runtime_agent: REFUNDS, credits: 10, runs: 1 },
      { agent: "agt-hidden", runtime_agent: HIDDEN, credits: 30, runs: 2 },
      { agent: "agt-gone", runtime_agent: "taf-0000aaaa0000aaaa0000", credits: 5, runs: 1 },
    ] });
    expect(creditsView(k, tafs).tafs).toEqual([
      { taf: REFUNDS, credits: 10, runs: 1, words: "Refunds · 10 credits · 1 run" },
      { taf: null, credits: 35, runs: 3, words: "Other tafs · 35 credits · 3 runs" },
    ]);
    // Only removed ones: said so.
    const gone = kiffCredits({ by_agent: [{ agent: "agt-gone", runtime_agent: "taf-0000aaaa0000aaaa0000", credits: 5, runs: 1 }] });
    expect(creditsView(gone, tafs).tafs).toEqual([{ taf: null, credits: 5, runs: 1, words: "Removed tafs · 5 credits · 1 run" }]);
    // A taf named by its KIFF agent alone is still found.
    const byAgent = kiffCredits({ by_agent: [{ agent: "agt-refunds", runtime_agent: "", credits: 2, runs: 1 }] });
    expect(creditsView(byAgent, tafs).tafs[0]).toMatchObject({ taf: REFUNDS });
  });

  it("says nothing went uncounted, and no Coach row, when that's so", () => {
    const v = creditsView(kiffCredits({ setup: { credits: 0, runs: 0 }, free: { setup: 0, trial: 0, failed: 0 } }), tafs);
    expect(v.coach).toBeNull();
    expect(v.not_counted).toEqual({ runs: 0, words: "Not counted: none", lines: [] });
  });
});

const run = (over: Partial<KIFFRun> = {}): KIFFRun => ({
  run: "4f0c2a9e", trigger: "schedule", ended: "2026-11-07T08:00:41Z", counted: true, credits: 6, refused: 0, declined: 0, ...over,
});

describe("a run, in words", () => {
  it("says where it came from and what it used, as the request view says it", () => {
    expect(runWords(run())).toBe("Its schedule · 6 credits");
    expect(runWords(run({ trigger: "owner", requester_kind: "member", credits: 1 }))).toBe("From its chat · 1 credit");
    expect(runWords(run({ trigger: "event", requester_kind: "door", source: "email" }))).toBe("By email · 6 credits");
    expect(runWords(run({ trigger: "owner" }))).toBe("Run now · 6 credits");
  });

  it("says what was blocked beside what it used, which it doesn't change", () => {
    expect(runWords(run({ refused: 1 }))).toBe("Its schedule · 6 credits · 1 action refused by KIFF");
    expect(runWords(run({ refused: 2 }))).toBe("Its schedule · 6 credits · 2 actions refused by KIFF");
    expect(runWords(run({ declined: 1 }))).toBe("Its schedule · 6 credits · 1 action declined on approval");
    expect(runWords(run({ refused: 1, declined: 2 }))).toBe("Its schedule · 6 credits · 1 action refused by KIFF, 2 declined on approval");
  });

  it("says why one wasn't counted", () => {
    expect(runWords(run({ trigger: "trial", counted: false, free: "trial", credits: 0 }))).toBe("A trial of a new version · not counted");
    expect(runWords(run({ counted: false, free: "failed", credits: 0 }))).toBe("Its schedule · failed on our side, not counted");
  });

  it("opens its request while kiff/agents keeps it, never a trial's", () => {
    expect(opens(run(), NOW)).toBe(true);
    expect(opens(run({ kept_until: "2026-11-14T08:00:41Z" }), NOW)).toBe(true);
    expect(opens(run({ kept_until: "2026-11-07T11:59:59Z" }), NOW)).toBe(false);
    expect(opens(run({ trigger: "trial", free: "trial", counted: false }), NOW)).toBe(false);
  });

  it("lists one taf's runs with its line from the period", () => {
    const v = tafCreditsView(kiffCredits(), { items: [run({ refused: 1 }), run({ run: "0a9e77d1", trigger: "trial", counted: false, free: "trial", credits: 0 })], next: "abc" },
      tafs[0]!, NOW);
    expect(v).toEqual({
      period: { ...NOV, current: true }, taf: REFUNDS, credits: 820, runs: 41, words: "Refunds · 820 credits · 41 runs this period",
      items: [
        { run: "4f0c2a9e", at: "2026-11-07T08:00:41Z", credits: 6, counted: true, why: null, words: "Its schedule · 6 credits · 1 action refused by KIFF", opens: true },
        { run: "0a9e77d1", at: "2026-11-07T08:00:41Z", credits: 0, counted: false, why: "trial", words: "A trial of a new version · not counted", opens: false },
      ],
      next: "abc",
    });
    expect(tafCreditsView(kiffCredits({ current: false, by_agent: [] }), { items: [] }, tafs[0]!, NOW))
      .toMatchObject({ credits: 0, runs: 0, words: "Refunds · 0 credits · 0 runs last period", items: [] });
  });
});

/** KIFF as the routes read it: who the token is, what they may use, and the credits reads. */
function kiff(opts: { credits?: object; status?: number; accessStatus?: number; runs?: object; asked?: string[]; me?: object } = {}) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    const u = new URL(url);
    opts.asked?.push(u.pathname + u.search);
    if (u.pathname === "/v1/me") return new Response(JSON.stringify(opts.me ?? ME), { status: 200 });
    if (u.pathname === "/v1/me/access") {
      if (opts.accessStatus) return new Response("{}", { status: opts.accessStatus });
      return new Response(JSON.stringify({ agents: { "agt-refunds": { use: true }, "agt-chargebacks": { oversee: true } } }), { status: 200 });
    }
    if (u.pathname === "/v1/me/usage/credits") {
      return opts.status ? new Response("{}", { status: opts.status }) : new Response(JSON.stringify(opts.credits ?? kiffCredits()), { status: 200 });
    }
    if (u.pathname === "/v1/me/usage/credits/runs") return new Response(JSON.stringify(opts.runs ?? { items: [run()] }), { status: 200 });
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
}

function world(fetcher: typeof fetch) {
  const agents = fakeAgents(() => NOW);
  const held = memoryTafs();
  for (const [id, name, agent] of [[REFUNDS, "Refunds", "agt-refunds"], [CHARGEBACKS, "Chargebacks", "agt-chargebacks"], [HIDDEN, "Payroll", "agt-hidden"]] as const) {
    const taf: Taf = { id, name, job: "refunds", kiffAgent: agent, nameClaim: "c", created: "2026-10-15T09:00:00Z" };
    held.held.set(`t_acme#${id}`, taf);
  }
  return {
    agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: held,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: fetcher,
    now: () => NOW,
  };
}

function event(path: string, query = ""): HTTPEvent {
  return { rawPath: path, rawQueryString: query, requestContext: { http: { method: "GET" } }, headers: { Authorization: "Bearer ana" } } as HTTPEvent;
}

async function get(deps: ReturnType<typeof world>, path: string, query = "") {
  const r = await handle(event(path, query), deps);
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

describe("GET /v1/usage/credits", () => {
  it("answers the period in words, a taf the person doesn't see on the row that doesn't open", async () => {
    const credits = kiffCredits({ by_agent: [...kiffCredits().by_agent, { agent: "agt-hidden", runtime_agent: HIDDEN, credits: 9, runs: 1 }] });
    const r = await get(world(kiff({ credits })), "/v1/usage/credits");
    expect(r.status).toBe(200);
    expect(r.body.words).toBe("This period: 1,240 credits used · ends on 30 Nov");
    expect(r.body.tafs.map((t: { taf: string | null }) => t.taf)).toEqual([REFUNDS, CHARGEBACKS, null]);
    expect(r.body.tafs[2].words).toBe("Other tafs · 9 credits · 1 run");
    expect(JSON.stringify(r.body)).not.toContain("Payroll");
  });

  it("asks KIFF for the period before with ?period=previous", async () => {
    const asked: string[] = [];
    await get(world(kiff({ asked })), "/v1/usage/credits", "period=previous");
    expect(asked).toContain("/v1/me/usage/credits?period=previous");
  });

  it("answers 503 with words when KIFF can't be read", async () => {
    for (const status of [500, 503, 404]) {
      const r = await get(world(kiff({ status })), "/v1/usage/credits");
      expect(r.status).toBe(503);
      expect(r.body.words).toBe(UNREADABLE);
    }
  });

  it("answers the same 503 when what the person may use can't be read, on both reads", async () => {
    for (const path of ["/v1/usage/credits", `/v1/usage/credits/tafs/${REFUNDS}`]) {
      const r = await get(world(kiff({ accessStatus: 502 })), path);
      expect(r.status).toBe(503);
      expect(r.body.words).toBe(UNREADABLE);
    }
  });
});

describe("GET /v1/usage/credits/tafs/{id}", () => {
  it("answers one taf's runs, asking KIFF for that taf's, with the page after", async () => {
    const asked: string[] = [];
    const r = await get(world(kiff({ asked, runs: { items: [run()], next: "c2" } })), `/v1/usage/credits/tafs/${REFUNDS}`, "after=c1&period=previous");
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ taf: REFUNDS, credits: 820, words: "Refunds · 820 credits · 41 runs this period", next: "c2" });
    expect(r.body.items[0]).toMatchObject({ run: "4f0c2a9e", words: "Its schedule · 6 credits", opens: true });
    expect(asked).toContain(`/v1/me/usage/credits/runs?runtime_agent=${REFUNDS}&period=previous&cursor=c1`);
  });

  it("is a 404 for a taf the person doesn't see, or one that isn't one", async () => {
    expect((await get(world(kiff()), `/v1/usage/credits/tafs/${HIDDEN}`)).status).toBe(404);
    expect((await get(world(kiff()), "/v1/usage/credits/tafs/not-a-taf")).status).toBe(404);
  });
});
