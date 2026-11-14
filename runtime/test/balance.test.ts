import { describe, expect, it } from "vitest";
import { balanceView, type KIFFBalance, waitingWords } from "../src/cloud/credits.js";
import { AgentsError } from "../src/cloud/agents.js";
import { handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const NOW = new Date("2026-11-20T12:00:00Z");
const NOV = { start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z" };
const REFUNDS = "taf-3f9a1c0b7d2e4a6f8b91";
const COACH = "Coach is included: enough to set up about 4 new jobs a month. Past that, Coach's help uses your credits.";

const balance = (over: Partial<KIFFBalance> = {}): KIFFBalance => ({
  kind: "solo", state: "ok", meter_pct: 40, included: 1000, used: 400, left: 600, packs_left: 0, over: 0,
  beyond: 0, beyond_cents: 0, limit_cents: 0, seats: 1, period: NOV, can_raise: false, raise_to_cents: 0,
  can_buy: { solo: false, packs: true }, billed_by: "apple", ...over,
});

describe("the balance, in words", () => {
  // docs/languages.md: the person's language changes the words, and amounts, dates and counts format by its locale.
  it("speaks the person's language, with amounts, dates and counts as its locale writes them", () => {
    const fmt = (lang: string, cents: number) => new Intl.NumberFormat(lang, { style: "currency", currency: "EUR", minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(cents / 100);
    const dec1 = (lang: string) => new Intl.DateTimeFormat(lang, { timeZone: "UTC", day: "numeric", month: "short" }).format(new Date("2026-12-01T00:00:00Z"));
    const co = balance({ kind: "company", state: "warning", meter_pct: 85, seats: 4, included: 2000, used: 2000, beyond: 300, beyond_cents: 6850, limit_cents: 8000, can_buy: { solo: false, packs: false } });
    const de = balanceView(co, "de");
    expect(de.balance.words).toBe(`Company · 4 Plätze · ${fmt("de", 6850)} vom Ausgabenlimit (${new Intl.NumberFormat("de", { style: "currency", currency: "EUR", minimumFractionDigits: 0, maximumFractionDigits: 2 }).format(80)}) verbraucht`);
    expect(de.balance.warning).toBe("85 % des Ausgabenlimits sind verbraucht. Am Limit warten deine tafs, bis ein Admin es erhöht.");
    const fr = balanceView(balance({ state: "waiting", why: "credits_used_up", meter_pct: 100, left: 0 }), "fr");
    expect(fr.balance.warning).toBe(`Vos tafs attendent : les crédits de cette période et vos packs sont utilisés. Un pack leur permet de continuer, sinon ils reprennent au début de la prochaine période, le 1 déc.`);
    expect(dec1("fr")).toBe("1 déc."); // its own period ends the sentence
    // Polish counts in its three forms; 1,240 written as Polish writes it.
    expect(balanceView(balance({ left: 1 }), "pl").balance.words).toBe("Solo · zostało 1 kredyt w tym okresie");
    expect(balanceView(balance({ left: 3 }), "pl").balance.words).toBe("Solo · zostało 3 kredyty w tym okresie");
    expect(balanceView(balance({ left: 1240 }), "pl").balance.words).toBe(`Solo · zostało ${new Intl.NumberFormat("pl").format(1240)} kredytów w tym okresie`);
    // English as before.
    expect(balanceView(balance({ left: 1 })).balance.words).toBe("Solo · 1 credit left this period");
  });

  it("says what Solo has left, its packs and what wasn't charged, and what may be bought", () => {
    expect(balanceView(balance({ packs_left: 300, left: 900, over: 12 }))).toEqual({
      balance: { state: "ok", why: null, meter_pct: 40, words: "Solo · 900 credits left this period",
        detail: "300 credits of it in packs, which never expire · 12 credits used past your balance weren't charged",
        warning: null, raise: null, buy: { solo: false, packs: true, first_month: null, coach: COACH }, credits_panel: true },
      plan_line: "Solo · 900 credits left", plan_tone: "ok", home_line: null,
    });
  });

  it("warns Solo from 80%, and says what lets the tafs go on once all of it is used", () => {
    const warn = balanceView(balance({ state: "warning", meter_pct: 83.7, left: 170 }));
    expect(warn.balance.warning).toBe("83% of this period's credits are used. A pack lets your tafs go on past them.");
    expect(warn.home_line).toBe("83% of this period's credits are used");
    const noPacks = balanceView(balance({ state: "warning", meter_pct: 90, can_buy: { solo: false, packs: false } }));
    expect(noPacks.balance.warning).toBe("90% of this period's credits are used. Then your tafs wait until the period starts again on 1 Dec.");
    expect(noPacks.balance.buy).toBeNull();
    const waiting = balanceView(balance({ state: "waiting", why: "credits_used_up", meter_pct: 100, left: 0 }));
    expect(waiting).toMatchObject({
      balance: { state: "waiting", why: "credits_used_up", words: "Solo · this period's credits and your packs are used",
        warning: "Your tafs are waiting: this period's credits and your packs are used. A pack lets them go on, or they go on when the period starts again on 1 Dec." },
      plan_line: "Solo · waiting: this period's credits are used", plan_tone: "waiting",
      home_line: "Your tafs are waiting: this period's credits and your packs are used. A pack lets them go on, or they go on when the period starts again on 1 Dec.",
    });
  });

  it("shows Company's included credits, then the spend limit, and offers the raise to whoever may make it", () => {
    const co = (over: Partial<KIFFBalance>) => balance({ kind: "company", seats: 4, included: 2000, limit_cents: 8000, can_buy: { solo: false, packs: false },
      can_raise: true, raise_to_cents: 12000, billed_by: "stripe", ...over });
    expect(balanceView(co({ used: 1500, left: 500, meter_pct: 75 }))).toEqual({
      balance: { state: "ok", why: null, meter_pct: 75, words: "Company · 4 seats · 1,500 of 2,000 credits included used",
        detail: "Then up to the spend limit, €80, on this period's invoice", warning: null,
        raise: { words: "Raise the spend limit to €120", from_cents: 8000 }, buy: null, credits_panel: true },
      plan_line: "Company · 4 seats · 1,500 credits used", plan_tone: "ok", home_line: null,
    });
    const beyond = balanceView(co({ state: "warning", used: 2640, left: 0, beyond: 640, beyond_cents: 6450, meter_pct: 80 }));
    expect(beyond.balance).toMatchObject({ words: "Company · 4 seats · €64.50 of the €80 spend limit used",
      detail: "The plan's included credits are used. Beyond them, credits go on this period's invoice.",
      warning: "80% of the spend limit is used. At the limit, your tafs wait until an admin raises it." });
    expect(beyond.home_line).toBe("80% of the spend limit is used");
    // Waiting: the raise for whoever may make it; past the ceiling, KIFF; an admin, for anyone else.
    expect(balanceView(co({ state: "waiting", why: "limit_reached", beyond: 800, beyond_cents: 8000, meter_pct: 100 })).balance.warning)
      .toBe("Spend limit reached. Raise it, or your tafs go on from 1 Dec.");
    const ceiling = balanceView(co({ state: "waiting", why: "limit_reached", raise_to_cents: 0 }));
    expect(ceiling.balance.raise).toBeNull();
    expect(ceiling.balance.warning).toContain("can't go higher from the app. Contact KIFF to raise it further");
    expect(balanceView(co({ state: "waiting", why: "limit_reached", can_raise: false, raise_to_cents: 0 })).balance)
      .toMatchObject({ raise: null, warning: "Your tafs are waiting: the spend limit is reached. An admin of this account can raise it." });
  });

  it("shows an agreement or internal with no bar, and no plan as waiting with Solo to start", () => {
    expect(balanceView(balance({ kind: "unbounded", meter_pct: null, left: null, used: 1240, billed_by: "contract", can_buy: { solo: false, packs: false } })))
      .toMatchObject({ balance: { meter_pct: null, words: "Your company's agreement · 1,240 credits used this period", warning: null, buy: null },
        plan_line: "Your company's agreement · 1,240 credits used", home_line: null });
    expect(balanceView(balance({ kind: "unbounded", meter_pct: null, used: 0, billed_by: "" })).balance.words).toBe("No limit · no credits used yet this period");
    expect(balanceView(balance({ kind: "none", state: "waiting", why: "no_plan", meter_pct: null, can_buy: { solo: true, packs: false } }))).toEqual({
      balance: { state: "waiting", why: "no_plan", meter_pct: null, words: "No plan", detail: null, warning: "Choose a plan to start your tafs.",
        raise: null, buy: { solo: true, packs: false, first_month: null, coach: COACH }, credits_panel: true },
      plan_line: "No plan · choose one to start", plan_tone: "waiting", home_line: "Choose a plan to start your tafs.",
    });
  });

  // The First month pack, sold once per account: KIFF says while it's still on offer (can_buy.once).
  it("offers the First month pack only while Solo runs, among the packs, never before a plan", () => {
    // No plan: not offered, since it can't be bought before Solo.
    const noPlan = balanceView(balance({ kind: "none", state: "waiting", why: "no_plan", meter_pct: null, can_buy: { solo: true, packs: false, once: true } }));
    expect(noPlan.balance.buy).toEqual({ solo: true, packs: false, first_month: null, coach: COACH });
    // Solo runs: it can be bought, and needs no words.
    expect(balanceView(balance({ can_buy: { solo: false, packs: true, once: true } })).balance.buy)
      .toEqual({ solo: false, packs: true, first_month: { ready: true, words: null }, coach: COACH });
    // Bought, or a KIFF from before the pack: not offered.
    expect(balanceView(balance({ can_buy: { solo: false, packs: true, once: false } })).balance.buy).toEqual({ solo: false, packs: true, first_month: null, coach: COACH });
    expect(balanceView(balance({ can_buy: { solo: true, packs: false } })).balance.buy).toEqual({ solo: true, packs: false, first_month: null, coach: COACH });
    // Someone who may buy nothing is offered nothing, whatever KIFF says of the pack.
    expect(balanceView(balance({ can_buy: { solo: false, packs: false, once: true } })).balance.buy).toBeNull();
    // Never alone: Solo running with no packs on offer, or another plan, offers none.
    expect(balanceView(balance({ can_buy: { solo: true, packs: false, once: true } })).balance.buy).toEqual({ solo: true, packs: false, first_month: null, coach: COACH });
    expect(balanceView(balance({ kind: "company", seats: 4, can_buy: { solo: true, packs: false, once: true } })).balance.buy)
      .toEqual({ solo: true, packs: false, first_month: null, coach: COACH });
  });

  // The Credits panel shows this period and the last: with no plan and no run in either, it has
  // nothing to say to someone who just signed in.
  it("hides the Credits panel only with no plan and no run lately", () => {
    const none = balance({ kind: "none", state: "waiting", why: "no_plan", meter_pct: null, can_buy: { solo: true, packs: false } });
    expect(balanceView(none, "en", false).balance.credits_panel).toBe(false);
    expect(balanceView(none, "en", true).balance.credits_panel).toBe(true);
    expect(balanceView(none).balance.credits_panel).toBe(true);
    for (const kind of ["solo", "company", "unbounded"] as const) {
      expect(balanceView(balance({ kind, seats: 4 }), "en", false).balance.credits_panel).toBe(true);
    }
  });

  it("words a refusal by its code, for this reader", () => {
    expect(waitingWords({ ...balance(), why: "no_plan" })).toBe("Choose a plan to start your tafs.");
    expect(waitingWords({ ...balance({ can_buy: { solo: false, packs: false } }), why: "credits_used_up" }))
      .toBe("Your tafs are waiting: this period's credits and your packs are used. They go on when the period starts again on 1 Dec.");
  });

  it("says what Coach's help includes with every store offer, in all 8 languages, and nothing without one", () => {
    for (const lang of ["en", "de", "es", "fr", "it", "nl", "pl", "pt"] as const) {
      const coach = balanceView(balance({ can_buy: { solo: true, packs: false } }), lang).balance.buy?.coach ?? "";
      expect(coach, lang).toContain("4");
      expect(coach, lang).toContain("Coach");
      if (lang !== "en") expect(coach, lang).not.toBe(COACH);
    }
    expect(balanceView(balance({ can_buy: { solo: false, packs: false } })).balance.buy).toBeNull();
  });
});

/** KIFF as the routes read it: who the token is, and the credits read with its balance. */
function kiff(opts: { balance?: KIFFBalance | null; status?: number; asked?: string[]; runs?: number; previousRuns?: number; previousStatus?: number } = {}) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    const u = new URL(url);
    opts.asked?.push(u.pathname + u.search);
    if (u.pathname === "/v1/me") return new Response(JSON.stringify(ME), { status: 200 });
    if (u.pathname === "/v1/me/access") return new Response(JSON.stringify({ agents: { "agt-refunds": { use: true } } }), { status: 200 });
    if (u.pathname === "/v1/me/usage/credits") {
      if (opts.status) return new Response("{}", { status: opts.status });
      const previous = u.searchParams.get("period") === "previous";
      if (previous && opts.previousStatus) return new Response("{}", { status: opts.previousStatus });
      const b = opts.balance === undefined ? balance() : opts.balance;
      return new Response(JSON.stringify({ period: NOV, current: !previous, credits: 0, runs: (previous ? opts.previousRuns : opts.runs) ?? 0, by_agent: [],
        setup: { credits: 0, runs: 0 }, free: {}, ...(b ? { balance: b } : {}) }), { status: 200 });
    }
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
}

function world(fetcher: typeof fetch) {
  const agents = fakeAgents(() => NOW);
  const held = memoryTafs();
  const taf: Taf = { id: REFUNDS, name: "Refunds", job: "refunds", kiffAgent: "agt-refunds", nameClaim: "c", created: "2026-10-15T09:00:00Z" };
  held.held.set(`t_acme#${REFUNDS}`, taf);
  return {
    agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: held,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: fetcher,
    now: () => NOW,
    fake: agents,
  };
}

function event(path: string, method = "GET", body?: string): HTTPEvent {
  return { rawPath: path, rawQueryString: "", requestContext: { http: { method } }, headers: { Authorization: "Bearer ana", "content-type": "application/json" },
    ...(body ? { body } : {}) } as HTTPEvent;
}

async function call(deps: ReturnType<typeof world>, path: string, method = "GET", body?: string) {
  const r = await handle(event(path, method, body), deps);
  return { status: r.statusCode, body: r.body && typeof r.body === "string" && r.body.startsWith("{") ? JSON.parse(r.body) : r.body };
}

describe("GET /v1/usage/balance", () => {
  it("answers the balance now, in words, from KIFF's credits read with the person's session", async () => {
    const asked: string[] = [];
    const r = await call(world(kiff({ balance: balance({ state: "warning", meter_pct: 85 }), asked })), "/v1/usage/balance");
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ balance: { state: "warning", meter_pct: 85 }, plan_tone: "warning", home_line: "85% of this period's credits are used" });
    expect(asked).toContain("/v1/me/usage/credits");
  });

  it("shows a new no-plan account no First month pack and no Credits panel", async () => {
    const none = balance({ kind: "none", state: "waiting", why: "no_plan", meter_pct: null, can_buy: { solo: true, packs: false, once: true } });
    const asked: string[] = [];
    const r = await call(world(kiff({ balance: none, asked })), "/v1/usage/balance");
    expect(r.status).toBe(200);
    expect((r.body as { balance: { buy: unknown } }).balance.buy).toEqual({ solo: true, packs: false, first_month: null, coach: COACH });
    expect((r.body as { balance: { credits_panel: boolean } }).balance.credits_panel).toBe(false);
    expect(asked).toContain("/v1/me/usage/credits?period=previous");
  });

  it("keeps the Credits panel for a no-plan account that ran lately, or when last period can't be read", async () => {
    const none = balance({ kind: "none", state: "waiting", why: "no_plan", meter_pct: null, can_buy: { solo: true, packs: false } });
    for (const opts of [{ runs: 2 }, { previousRuns: 3 }, { previousStatus: 500 }]) {
      const r = await call(world(kiff({ balance: none, ...opts })), "/v1/usage/balance");
      expect(r.status).toBe(200);
      expect((r.body as { balance: { credits_panel: boolean } }).balance.credits_panel).toBe(true);
    }
    // With a plan, last period isn't read.
    const asked: string[] = [];
    const r = await call(world(kiff({ asked })), "/v1/usage/balance");
    expect((r.body as { balance: { credits_panel: boolean } }).balance.credits_panel).toBe(true);
    expect(asked).not.toContain("/v1/me/usage/credits?period=previous");
  });

  it("answers 503 with words when KIFF can't be read, or gives no balance", async () => {
    for (const k of [kiff({ status: 500 }), kiff({ balance: null })]) {
      const r = await call(world(k), "/v1/usage/balance");
      expect(r.status).toBe(503);
      expect(r.body.words).toBe("Credits can't be read right now. Try again in a moment.");
    }
  });
});

describe("GET /v1/usage/ai, kept for apps from before", () => {
  it("answers no bound, never waiting and no Go on, reading nothing", async () => {
    const asked: string[] = [];
    const r = await call(world(kiff({ asked })), "/v1/usage/ai");
    expect(r.status).toBe(200);
    expect(r.body.can_go_on).toBe(false);
    expect(r.body.paused).toBe(false);
    expect(asked.filter((p) => p.startsWith("/v1/me/usage"))).toEqual([]);
  });
});

describe("a chat refused for credits", () => {
  it("answers 409 with the code and this person's words, and sends no AI bound", async () => {
    const w = world(kiff({ balance: balance({ kind: "company", state: "waiting", why: "limit_reached", can_raise: false, can_buy: { solo: false, packs: false } }) }));
    w.fake.fail.set("startRun", new AgentsError(409, "limit_reached: the account's tafs wait for credits", undefined, "limit_reached"));
    const r = await call(w, `/v1/tafs/${REFUNDS}/messages`, "POST", JSON.stringify({ text: "Refund order 1043" }));
    expect(r.status).toBe(409);
    expect(r.body).toMatchObject({ code: "limit_reached", words: "Your tafs are waiting: the spend limit is reached. An admin of this account can raise it." });
  });

  it("keeps the plain words when the balance can't be read", async () => {
    const w = world(kiff({ status: 500 }));
    w.fake.fail.set("startRun", new AgentsError(409, "no_plan: the account's tafs wait for credits", undefined, "no_plan"));
    const r = await call(w, `/v1/tafs/${REFUNDS}/messages`, "POST", JSON.stringify({ text: "hi" }));
    expect(r.status).toBe(409);
    expect(r.body).toMatchObject({ code: "no_plan", words: "Choose a plan to start your tafs." });
  });
});

// fr and pt abbreviate a month with a period ("1 déc.", "1 de dez."): a line that ends on the date ends with one, never "..".
describe("a credits line that ends on a date", () => {
  it("ends with one period, in every language and every branch", async () => {
    const { LANGUAGES } = await import("../src/cloud/words/index.js");
    for (const lang of LANGUAGES) {
      // Every branch: no plan; the limit raised to a sum, raised to its ceiling, or the admin's; packs to buy, or the wait.
      // The period starts again on 1 December.
      const lines = [
        waitingWords({ ...balance(), why: "no_plan" }, lang),
        waitingWords({ ...balance({ kind: "company", can_raise: true, raise_to_cents: 5000 }), why: "limit_reached" }, lang),
        waitingWords({ ...balance({ kind: "company", can_raise: true, raise_to_cents: 0 }), why: "limit_reached" }, lang),
        waitingWords({ ...balance({ kind: "company", can_raise: false }), why: "limit_reached" }, lang),
        waitingWords({ ...balance({ can_buy: { solo: false, packs: true } }), why: "credits_used_up" }, lang),
        waitingWords({ ...balance({ can_buy: { solo: false, packs: false } }), why: "credits_used_up" }, lang),
      ];
      for (const line of lines) expect(line, lang).not.toMatch(/\.\.$/);
    }
  });
});
