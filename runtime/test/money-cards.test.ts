import { describe, expect, it } from "vitest";
import { countOnAmountWrite, dayTotalTwice, type ApprovalGrant } from "../src/cloud/approval.js";
import { cardsFromRules, moneyCardsOff, rulesAgreement, rulesMismatch, rulesUnnamedFigures, type AgreedRule, type MoneyCard } from "../src/cloud/rules.js";

// A phone test, 25 Nov: "every refund waits for you, a refund over €50 is refused, €200
// a day at most". Coach drafted the refund's Cards by hand eleven times, KIFF or tafs cloud refused each, and Coach went
// on to ask the owner to change their rules. The Cards are built from the agreed rules now (rules.ts, cardsFromRules).
const AGENT = "refund-requests-x7";
const stripe: ApprovalGrant = { id: "grt_Refunds0001", status: "connected", digest: "d", request: { agent: AGENT, tools: [
  { tool: "create_refund", name: "create_refund", title: "Refund a charge in EUR" },
  { tool: "list_charges", name: "list_charges", title: "List charges", read_only: true },
  { tool: "read_charge", name: "read_charge", title: "Read one charge", read_only: true },
] } };
const reads = [{ tool: "list_charges", over: "ask" as const }, { tool: "read_charge", over: "ask" as const }];
const EACH = { quantity: "sum(amount)", window: "call", limit: 5000, over: "refuse" as const };
const DAY = { quantity: "sum(amount)", window: "calendar_day", limit: 20000, over: "refuse" as const };
const EVERY_ONE = { quantity: "count", window: "calendar_day", limit: 0, over: "ask" as const };

/** The rules the owner agreed, as each notice carried them: seq 47 (rules1), 114 (rules3), 155 (rules4), 181 (rules5). */
const RULES: Record<string, AgreedRule[]> = {
  rules1: [{ tool: "create_refund", over: "ask", limits: [EVERY_ONE, EACH, DAY] }, ...reads],
  rules3: [{ tool: "create_refund", over: "ask", limits: [EACH, DAY] }, ...reads],
  rules4: [{ tool: "create_refund", over: "ask", limits: [EACH, EVERY_ONE] }, ...reads],
  rules5: [{ tool: "create_refund", over: "ask", limits: [EACH, DAY] }, ...reads],
};

const readsCard = { id: `${AGENT}-reads`, subject: AGENT, domain: "gateway", grants: [{ action: "list_charges" }, { action: "read_charge" }], aggregates: [{ quantity: "count", limit: 10000, window: "calendar_day" }] };
const limitsCard = (day: Record<string, unknown> = { quantity: "sum(amount)", limit: 20000, window: "calendar_day" }) => ({ id: `${AGENT}-limits`, subject: AGENT, domain: "gateway",
  grants: [{ action: "create_refund", per_action_max: [{ quantity: "sum(amount)", max: 5000 }] }], aggregates: [day], on_exceed: "refuse" });
const holdCard = (grant: Record<string, unknown>, aggregates: unknown[]) => ({ id: `${AGENT}-hold`, subject: AGENT, domain: "gateway", grants: [{ action: "create_refund", ...grant }], aggregates, on_exceed: "owner" });
const cap = (max: number) => ({ per_action_max: [{ quantity: "sum(amount)", max }] });
const kiffsCount = { quantity: "count", limit: 10000, window: "calendar_day" };

/** Coach's drafts as it submitted them (kiff_draft), by seq, with the rules agreed when it did, and what answered. */
const DRAFTS: Array<{ seq: number; rules: string; cards: unknown[]; answered: string }> = [
  { seq: 55, rules: "rules1", cards: [readsCard, limitsCard(), holdCard({}, [{ quantity: "count", limit: 0, window: "calendar_day" }])], answered: "KIFF 400: a call count alone lets each call move any amount" },
  { seq: 59, rules: "rules1", cards: [readsCard, limitsCard(), holdCard(cap(0), [kiffsCount])], answered: "KIFF 400: the per-action limit must be above zero" },
  { seq: 63, rules: "rules1", cards: [readsCard, limitsCard(), holdCard(cap(1), [kiffsCount])], answered: "rules_on_exceed (mixed), seq 71" },
  { seq: 74, rules: "rules1", cards: [readsCard, limitsCard(), holdCard({}, [{ quantity: "sum(amount)", limit: 1, window: "calendar_day" }])], answered: "day_total_twice, seq 82" },
  { seq: 85, rules: "rules1", cards: [readsCard, limitsCard(), holdCard(cap(1), [kiffsCount])], answered: "rules_on_exceed (mixed), seq 93" },
  { seq: 124, rules: "rules3", cards: [readsCard, limitsCard(), holdCard(cap(1), [kiffsCount])], answered: "rules_on_exceed, seq 132" },
  { seq: 163, rules: "rules4", cards: [readsCard, limitsCard(kiffsCount), holdCard({}, [{ quantity: "count", limit: 0, window: "calendar_day" }])], answered: "KIFF 400, seq 164" },
  { seq: 167, rules: "rules4", cards: [readsCard, limitsCard(kiffsCount), holdCard(cap(1), [kiffsCount])], answered: "count_on_amount, seq 175" },
  { seq: 189, rules: "rules5", cards: [readsCard, limitsCard(), holdCard(cap(1), [{ quantity: "sum(amount)", limit: 20000, window: "calendar_day" }])], answered: "day_total_twice, seq 197" },
  { seq: 200, rules: "rules5", cards: [readsCard, limitsCard(), holdCard(cap(1), [kiffsCount])], answered: "rules_on_exceed, seq 208" },
  { seq: 228, rules: "rules5", cards: [readsCard, limitsCard()], answered: "submitted; never approved" },
];

const REFUSED: MoneyCard = { id: `${AGENT}-create_refund-refused`, subject: AGENT, domain: "gateway",
  grants: [{ action: "create_refund", per_action_max: [{ quantity: "sum(amount)", max: 5000 }] }],
  aggregates: [{ quantity: "sum(amount)", limit: 20000, window: "calendar_day" }], on_exceed: "refuse" };
const HELD: MoneyCard = { id: `${AGENT}-create_refund-held`, subject: AGENT, domain: "gateway",
  grants: [{ action: "create_refund", per_action_max: [{ quantity: "sum(amount)", max: 5000 }] }],
  aggregates: [{ quantity: "count", limit: 0, window: "calendar_day" }], on_exceed: "owner" };

/**
 * What KIFF does with a call under these Cards (kiff/cloud, mandate/authorizer.go): every Card covering the action is
 * drawn on, conjunctively; past a refusing Card's limit the call is refused, and approving an exception never waives it
 * ("refuse takes precedence"); past only a held Card's, it waits for the owner. `used` is the day's sum and count so far.
 */
function kiffDecides(cards: unknown[], action: string, amount: number, used = { sum: 0, count: 0 }): "runs" | "held" | "refused" {
  let held = false;
  for (const raw of cards) {
    const c = raw as { grants: Array<{ action: string; per_action_max?: Array<{ quantity: string; max: number }> }>; aggregates: Array<{ quantity: string; limit: number; window: string }>; on_exceed?: string };
    const g = c.grants.find((x) => x.action === action);
    if (!g) continue;
    const past = (g.per_action_max ?? []).some((m) => m.quantity === "sum(amount)" && amount > m.max) ||
      c.aggregates.some((a) => a.window === "calendar_day" && (a.quantity === "count" ? used.count + 1 > a.limit : used.sum + amount > a.limit));
    if (!past) continue;
    if (c.on_exceed !== "owner") return "refused";
    held = true;
  }
  return held ? "held" : "runs";
}

describe("a money tool's Cards are built from the rules the owner agreed (cardsFromRules)", () => {
  it("every refund held, over €50 refused, €200 a day: a refused Card and a held one carrying the same €50 cap", () => {
    expect(cardsFromRules([stripe], RULES.rules1, AGENT)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", cards: [REFUSED, HELD] }]);
  });

  it("the built Cards pass every check tafs cloud makes of a draft", () => {
    const cards = [readsCard, REFUSED, HELD];
    expect(moneyCardsOff([stripe], cards, RULES.rules1, AGENT)).toBeUndefined();
    expect(rulesMismatch([stripe], cards, RULES.rules1)).toBeUndefined();
    expect(rulesUnnamedFigures([stripe], cards, RULES.rules1)).toEqual([]);
    expect(countOnAmountWrite(stripe, cards)).toBeUndefined();
    expect(dayTotalTwice(stripe, cards)).toBeUndefined();
  });

  // KIFF's Cards are conjunctive: a refund the held Card would hold but over €50 is refused, never held for the owner.
  it("conjunctive: within the held Card but over €50 is refused; under €50 waits for the owner; past €200 a day is refused", () => {
    const cards = [readsCard, REFUSED, HELD];
    expect(kiffDecides(cards, "create_refund", 6000)).toBe("refused");
    expect(kiffDecides(cards, "create_refund", 5001)).toBe("refused");
    expect(kiffDecides(cards, "create_refund", 5000)).toBe("held");
    expect(kiffDecides(cards, "create_refund", 100)).toBe("held");
    expect(kiffDecides(cards, "create_refund", 2000, { sum: 19000, count: 4 })).toBe("refused");
    expect(kiffDecides(cards, "list_charges", 0)).toBe("runs");
  });

  for (const d of DRAFTS) {
    it(`seq ${d.seq} (${d.answered}): answered with the Cards the rules give, never a piece at a time`, () => {
      const built = cardsFromRules([stripe], RULES[d.rules]!, AGENT)[0]!;
      const off = moneyCardsOff([stripe], d.cards, RULES[d.rules]!, AGENT);
      if (d.rules === "rules3" || d.rules === "rules5") {
        // rules3 and rules5 say the refund asks (`over: "ask"`) with no figure that asks: no Cards, never the refused Card
        // alone, under which every refund up to €50 would run unasked (seq 228 drafted that, and it is refused now).
        expect(built).toEqual({ tool: "Refund a charge in EUR", action: "create_refund", unbuilt: "asks_unnamed" });
        expect(off).toEqual(built);
        return;
      }
      expect(off).toEqual(built);
      // And Coach drafting what it hears passes.
      if ("cards" in built) expect(moneyCardsOff([stripe], [readsCard, ...built.cards], RULES[d.rules]!, AGENT)).toBeUndefined();
    });
  }

  it("seq 155's rules (over €50 refused, every refund held, no day's total) give no Cards KIFF signs: Coach asks for the day's total", () => {
    expect(cardsFromRules([stripe], RULES.rules4!, AGENT)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", unbuilt: "refused_total" }]);
  });

  it("ask past €5 a refund, refuse past €50 a day: the held Card has its own cap and KIFF's count, the refused one the day's total", () => {
    const split: AgreedRule[] = [{ tool: "create_refund", over: "refuse", limits: [{ quantity: "sum(amount)", window: "call", limit: 500, over: "ask" }, { quantity: "sum(amount)", window: "calendar_day", limit: 5000 }] }, ...reads];
    const got = cardsFromRules([stripe], split, AGENT)[0]!;
    expect(got).toEqual({ tool: "Refund a charge in EUR", action: "create_refund", cards: [
      { id: `${AGENT}-create_refund-refused`, subject: AGENT, domain: "gateway", grants: [{ action: "create_refund" }], aggregates: [{ quantity: "sum(amount)", limit: 5000, window: "calendar_day" }], on_exceed: "refuse" },
      { id: `${AGENT}-create_refund-held`, subject: AGENT, domain: "gateway", grants: [{ action: "create_refund", per_action_max: [{ quantity: "sum(amount)", max: 500 }] }], aggregates: [kiffsCount], on_exceed: "owner" },
    ] });
    const cards = [readsCard, ...("cards" in got ? got.cards : [])];
    expect(rulesMismatch([stripe], cards, split)).toBeUndefined();
    expect(rulesUnnamedFigures([stripe], cards, split)).toEqual([]);
    expect(countOnAmountWrite(stripe, cards)).toBeUndefined();
    expect(kiffDecides(cards, "create_refund", 400)).toBe("runs");
    expect(kiffDecides(cards, "create_refund", 600)).toBe("held");
    expect(kiffDecides(cards, "create_refund", 400, { sum: 4800, count: 9 })).toBe("refused");
  });

  it("refuse past €50 a refund and €200 a day, nothing held: one refused Card", () => {
    const refuse: AgreedRule[] = [{ tool: "create_refund", over: "refuse", limits: [{ ...EACH, over: undefined }, { ...DAY, over: undefined }] }];
    expect(cardsFromRules([stripe], refuse, AGENT)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", cards: [REFUSED] }]);
  });

  it("rules that give no Cards KIFF signs say what they lack", () => {
    const of = (limits: AgreedRule["limits"], over: "ask" | "refuse" = "ask") => cardsFromRules([stripe], [{ tool: "create_refund", over, limits }], AGENT)[0];
    // Every refund held and €200 a day refused, no cap on each: the held Card would limit no amount.
    expect(of([EVERY_ONE, DAY])).toMatchObject({ unbuilt: "held_bound" });
    // Ask past €5 a refund, nothing else: no total for the held Card, and no refused day's total for KIFF's count.
    expect(of([{ quantity: "sum(amount)", window: "call", limit: 500 }])).toMatchObject({ unbuilt: "held_total" });
    // A count of a money tool's calls.
    expect(of([{ quantity: "count", window: "calendar_day", limit: 3 }, DAY])).toMatchObject({ unbuilt: "count" });
  });

  it("reads, and writes whose rules have no amount, are the other checks' alone", () => {
    expect(cardsFromRules([stripe], [{ tool: "create_refund", over: "ask", limits: [EVERY_ONE] }, ...reads], AGENT)).toEqual([]);
    expect(cardsFromRules([stripe], reads, AGENT)).toEqual([]);
  });
});

// The rebuild's second try, 25 Nov: Coach agreed "every refund waits for you" as a count of 0 for each call. Read as 0 a
// day (rules.ts, limitsOf), it builds the same two Cards KIFF signs, never a per-call count KIFF refuses.
describe("every refund held, written as 0 for each call", () => {
  it("builds the refused Card and the held one, as 0 a day", () => {
    const shown = { type: "notice", id: "rules-1", text: "Every refund waits for your approval first. Over 50 EUR refused, 200 EUR a day.", rules: [
      { tool: "create_refund", over: "ask", limits: [{ quantity: "count", window: "call", limit: 0, over: "ask" }, { ...EACH }, { ...DAY }] }, ...reads] };
    const agreement = rulesAgreement([{ kind: "component", run: "r1", body: shown },
      { kind: "component", run: "r1", body: { type: "confirm", id: "agree-1", agrees: "rules-1", options: [{ value: "agree" }] } },
      { kind: "answered", run: "r1", body: { id: "agree-1", type: "confirm", answer: "agree" } }]);
    expect(agreement.state).toBe("agreed");
    const rules = agreement.state === "agreed" ? agreement.rules : [];
    expect(cardsFromRules([stripe], rules, AGENT)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", cards: [REFUSED, HELD] }]);
    expect(JSON.stringify(cardsFromRules([stripe], rules, AGENT))).not.toContain('"quantity":"count","max"');
  });
});

describe("a draft's money Cards are the built ones, compared on what limits them (moneyCardsOff)", () => {
  const ok = (cards: unknown[]) => expect(moneyCardsOff([stripe], [readsCard, ...cards], RULES.rules1, AGENT)).toBeUndefined();
  const off = (cards: unknown[]) => expect(moneyCardsOff([stripe], [readsCard, ...cards], RULES.rules1, AGENT)).toMatchObject({ action: "create_refund", cards: [REFUSED, HELD] });

  it("ids, order and a title aside, and a measure only as KIFF's default for its quantity", () => {
    ok([{ ...HELD, id: "h" }, { ...REFUSED, id: "r" }]);
    ok([{ ...REFUSED, title: "Refunds", aggregates: [{ ...REFUSED.aggregates[0], measure: "effects" }] },
      { ...HELD, aggregates: [{ ...HELD.aggregates[0], measure: "attempts" }] }]);
  });

  // A field the comparison doesn't read could widen a Card: compared on an allowlist, failing closed.
  it("any field it doesn't name, another subject or domain, or another measure is refused", () => {
    off([{ ...REFUSED, aggregates: [{ ...REFUSED.aggregates[0], extra_room: [{ amount: 10000 }] }] }, HELD]);
    off([{ ...REFUSED, aggregates: [{ ...REFUSED.aggregates[0], measure: "attempts" }] }, HELD]);
    off([REFUSED, { ...HELD, aggregates: [{ ...HELD.aggregates[0], measure: "effects" }] }]);
    off([{ ...REFUSED, subject: "another-agent" }, HELD]);
    off([{ ...REFUSED, subjects: [AGENT, "another-agent"] }, HELD]);
    const { domain: _, ...noDomain } = REFUSED;
    off([noDomain, HELD]);
    off([{ ...REFUSED, domain: "stripe" }, HELD]);
    off([REFUSED, { ...HELD, environment: "test" }]);
    off([REFUSED, { ...HELD, hold_expiry: "720h" }]);
    off([REFUSED, { ...HELD, grants: [{ ...HELD.grants[0], resources: { parameter: "charge", values: ["ch_1"] } }] }]);
    off([REFUSED, { ...HELD, grants: [{ ...HELD.grants[0], per_action_max: [{ quantity: "sum(amount)", max: 5000, unit: "EUR" }] }] }]);
    off([REFUSED, { ...HELD, on_exceed: "keep" }]);
  });

  it("on_exceed unsaid is owner, as KIFF reads a new Card's", () => {
    const { on_exceed: _, ...unsaid } = HELD;
    ok([REFUSED, unsaid]);
    off([REFUSED, { ...HELD, on_exceed: "Owner" }]);
  });

  it("any other figure, a stricter one too, is another Card: the person signs what they agreed", () => {
    off([REFUSED, { ...HELD, grants: [{ action: "create_refund", per_action_max: [{ quantity: "sum(amount)", max: 4000 }] }] }]);
    off([{ ...REFUSED, aggregates: [{ quantity: "sum(amount)", limit: 10000, window: "calendar_day" }] }, HELD]);
    off([REFUSED, { ...HELD, aggregates: [...HELD.aggregates, { quantity: "sum(amount)", limit: 1, window: "rolling_1h" }] }]);
  });

  it("a Card missing, one more, or one over the refund and another tool, isn't the built ones", () => {
    off([REFUSED]);
    off([REFUSED, HELD, HELD]);
    off([REFUSED, { ...HELD, grants: [...HELD.grants, { action: "list_charges" }] }]);
  });
});

describe("a held Card may carry the refused Card's cap on each call (rulesMismatch)", () => {
  it("the same figure, on a held Card beside a refusing one that carries it: allowed", () => {
    expect(rulesMismatch([stripe], [readsCard, REFUSED, HELD], RULES.rules1)).toBeUndefined();
  });

  it("with no refusing Card carrying it, or at another figure, it is still the refused figure on a held Card", () => {
    const refusedNoCap = { ...REFUSED, grants: [{ action: "create_refund" }] };
    expect(rulesMismatch([stripe], [readsCard, refusedNoCap, HELD], RULES.rules1)?.kind).toBe("mixed");
    const lower = { ...HELD, grants: [{ action: "create_refund", per_action_max: [{ quantity: "sum(amount)", max: 4000 }] }] };
    expect(rulesMismatch([stripe], [readsCard, REFUSED, lower], RULES.rules1)?.kind).toBe("mixed");
  });
});

// The apple@ rebuild, 25 Nov 22:43: "Every refund asks you first. A refund over 50 EUR is refused. Refunds past 200 EUR in a
// day are refused", agreed as `over: "ask"` beside the two refused figures and nothing that asks. tafs cloud answered with
// the refused Card alone, and Coach stopped rather than draft it: every refund under €50 would have run unasked.
describe("a rule that asks with no figure that asks gives no Cards", () => {
  const agreed: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [EACH, DAY] }, ...reads];

  it("names what's missing, never builds the refused Card alone", () => {
    expect(cardsFromRules([stripe], agreed, AGENT)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", unbuilt: "asks_unnamed" }]);
  });

  it("refuses every draft for it: the refused Card alone, and Coach's €0.01 held cap", () => {
    for (const cards of [[readsCard, limitsCard()], [readsCard, limitsCard(), holdCard(cap(1), [kiffsCount])]]) {
      expect(moneyCardsOff([stripe], cards, agreed, AGENT)).toMatchObject({ unbuilt: "asks_unnamed" });
    }
  });

  it("with \"every refund asks\" as a count of 0 a day, gives the held Card beside the refused one, and nothing under €50 runs", () => {
    const named: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [EVERY_ONE, EACH, DAY] }, ...reads];
    const built = cardsFromRules([stripe], named, AGENT)[0]!;
    expect(built).toEqual({ tool: "Refund a charge in EUR", action: "create_refund", cards: [REFUSED, HELD] });
    const cards = "cards" in built ? built.cards : [];
    for (const amount of [1, 100, 4999]) expect(kiffDecides(cards, "create_refund", amount)).toBe("held");
    expect(kiffDecides(cards, "create_refund", 5001)).toBe("refused");
  });

  it("with nothing asking (over: refuse), the refused Card alone is what was agreed", () => {
    const refused: AgreedRule[] = [{ tool: "create_refund", over: "refuse", limits: [EACH, DAY] }, ...reads];
    expect(cardsFromRules([stripe], refused, AGENT)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", cards: [REFUSED] }]);
  });
});
