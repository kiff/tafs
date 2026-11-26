import { describe, expect, it } from "vitest";
import { countOnAmountWrite, dayTotalTwice, MONEY_UNBUILT, type ApprovalGrant } from "../src/cloud/approval.js";
import { cardsFromRules, moneyCardsOff, rulesAgreement, rulesMismatch, rulesUnnamedFigures, saysEveryCallAsks, type AgreedRule, type MoneyCard } from "../src/cloud/rules.js";

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
    expect(of([{ quantity: "sum(amount)", window: "call", limit: 500, over: "ask" }])).toMatchObject({ unbuilt: "held_total" });
    // The same figure saying nothing of its own over, under a rule that asks, takes the rule's ask: as above, no total.
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
    off([REFUSED, { ...HELD, grants: [{ ...HELD.grants[0], per_action_max: [{ quantity: "sum(amount)", max: 5000, per: "charge" }] }] }]);
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

// Narrower than asks_unnamed: the words say every refund asks, but the figure that asks is a positive cap (one that
// inherits the rule's `over: "ask"`, or Coach's €0.01), so refunds under it would run unasked.
describe("words that say every call asks hold the rule to a count of 0 a day that asks", () => {
  const WORDS = "Every refund asks you first. A refund over 50 EUR is refused. Refunds past 200 EUR in a day are refused.";
  const inherited: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [{ ...EACH, over: undefined }, DAY] }, ...reads];

  it("reads the words in each language Coach writes the rules in", () => {
    for (const text of [
      WORDS, "Each refund waits for your approval.",
      "Chaque remboursement attend votre accord.", "Jede Erstattung wartet auf deine Zustimmung.",
      "Cada reembolso espera tu aprobación.", "Ogni rimborso aspetta la tua approvazione.",
      "Elke terugbetaling wacht op je goedkeuring.", "Każdy zwrot czeka na twoją zgodę.", "Cada reembolso espera a sua aprovação.",
    ]) expect(saysEveryCallAsks(text), text).toBe(true);
  });

  it("reads the will, must, passive and goes-to-you forms, in each language", () => {
    for (const text of [
      "Every refund will ask you first.", "Every refund must ask you.", "Each refund is approved by you first.",
      "Every refund goes to you first.", "Each refund needs you to approve it.", "Every refund must be confirmed by you.",
      "Chaque remboursement vous sera soumis.", "Chaque remboursement est validé par vous.", "Chaque remboursement passe d'abord par vous.",
      "Jede Erstattung wird dich zuerst fragen.", "Jede Erstattung muss von dir freigegeben werden.", "Jede Erstattung geht zuerst an dich.",
      "Cada reembolso te preguntará.", "Cada reembolso debe ser aprobado por ti.", "Cada reembolso pasa primero por ti.",
      "Ogni rimborso ti chiederà.", "Ogni rimborso deve essere approvato da te.", "Ogni rimborso passa prima da te.",
      "Elke terugbetaling zal je eerst vragen.", "Elke terugbetaling moet worden goedgekeurd door jou.", "Elke terugbetaling gaat eerst naar jou.",
      "Każdy zwrot zapyta cię.", "Każdy zwrot musi być zatwierdzony przez ciebie.", "Każdy zwrot trafia najpierw do ciebie.",
      "Cada reembolso perguntará-lhe.", "Cada reembolso deve ser aprovado por você.", "Cada reembolso passa primeiro por você.",
    ]) expect(saysEveryCallAsks(text), text).toBe(true);
  });

  it("never reads a figure that asks, or a rule with no asking, as every call", () => {
    for (const text of [
      "Every refund over 50 EUR asks you first.", "Each refund past €5 waits for your approval.",
      "A refund over 50 EUR is refused. Every call is pinned to test mode.", "Listing and reading payments is open.",
      "Chaque remboursement de plus de 50 EUR attend votre accord.",
    ]) expect(saysEveryCallAsks(text), text).toBe(false);
  });

  // An Eval, 26 Nov: the owner said "a single refund over 5 euros should come to me to approve on my phone first", and the
  // notice put the figure after the verb. Read as every call, the rules were held to a count of 0 a day that asks, and the
  // follow-up's €4 refund was held. A threshold word with a figure after the verb, in the same clause, is a figure that asks.
  it("a figure after the verb, past a threshold word, is a figure that asks, in each language", () => {
    for (const text of [
      "Every refund asks you first when it's over 5 EUR.", "Every refund asks you first if it is over €5.",
      "Every refund asks you first if over €5.", "Each refund waits for your approval when it's above 5 euros.",
      "Each refund needs your approval on your phone once it's over 5 EUR; 50 EUR a day at most.",
      "Every refund goes to you first when it exceeds 5 EUR.", "Each refund is approved by you if it's more than €5.",
      "Chaque remboursement vous demande d'abord au-delà de 5 €.", "Chaque remboursement attend votre accord s'il dépasse 5 €.",
      "Jede Erstattung fragt dich zuerst wenn sie über 5 € liegt.", "Jede Erstattung wartet auf deine Zustimmung ab 5 €.",
      "Cada reembolso te pregunta primero cuando supera 5 €.", "Ogni rimborso ti chiede prima se supera 5 €.",
      "Elke terugbetaling vraagt je eerst als die boven 5 € komt.", "Każdy zwrot pyta cię najpierw gdy przekracza 5 €.",
      "Cada reembolso pergunta-lhe primeiro quando passar de 5 €.",
      // A comma straight before a word that opens a condition goes on with the clause, as German and Polish always write it.
      "Jede Erstattung fragt dich zuerst, wenn sie über 5 € liegt.", "Każdy zwrot pyta cię najpierw, gdy przekracza 5 €.",
      "Every refund asks you first, when it's over €5.", "Chaque remboursement attend votre accord, s'il dépasse 5 €.",
    ]) expect(saysEveryCallAsks(text), text).toBe(false);
  });

  it("a figure that is another rule's, after a comma, an \"and\" or a stop, still leaves every call read", () => {
    for (const text of [
      "Every refund asks you first, and refunds over €200 a day are refused.",
      "Every refund asks you first and refunds over €200 a day are refused.",
      "Every refund asks you first, up to €50 a day.", "Every refund asks you first up to €50 a day.",
      "Every refund asks you first. A refund over 50 EUR is refused.",
      "Chaque remboursement attend votre accord, et au-delà de 200 € par jour il est refusé.",
      "Jede Erstattung fragt dich zuerst und über 200 € am Tag wird abgelehnt.",
      // A comma before anything but a word that opens a condition ends the clause, a threshold word like "over" included.
      "Every refund asks you first, and over a day refunds stop at 200 EUR.",
      "Every refund asks you first, over a day refunds stop at 50 EUR.",
      "Każdy zwrot czeka na twoją zgodę, a zwroty powyżej 200 € dziennie są odrzucane.",
      // Each read as a cap when any threshold word continued the clause after a comma: pinned, so a later widening can't
      // bring them back.
      "Every refund asks you first, over €200 a day is refused.",
      "Every refund asks you first, more than €200 a day is refused.",
      "Chaque remboursement vous demande d'abord, plus de 200 € par jour sont refusés.",
      "Chaque remboursement attend votre accord, plus de 200 € par jour est refusé.",
      "Jede Erstattung fragt dich zuerst, über 200 € am Tag wird abgelehnt.",
      "Jede Erstattung fragt dich zuerst, mehr als 200 € am Tag werden abgelehnt.",
      "Jede Erstattung fragt dich zuerst, ab 200 € am Tag wird abgelehnt.",
      "Każdy zwrot czeka na twoją zgodę, powyżej 200 € dziennie zwroty są odrzucane.",
      "Jede Erstattung fragt dich zuerst, und über 200 € am Tag wird abgelehnt.",
    ]) expect(saysEveryCallAsks(text), text).toBe(true);
  });

  it("the refusal names the words as what's off, keeps the owner's figure, and the count of 0 only for every call", () => {
    const words = MONEY_UNBUILT.every_call_unheld;
    expect(words).toContain("keep their figure and rule");
    expect(words).toContain("before the verb");
    expect(words).toContain("Only if the owner said every call asks");
    expect(words).not.toContain("or words that say which calls ask");
  });

  it("under words that say every call asks, a figure inheriting ask, or a positive cap that asks, gives no Cards", () => {
    expect(cardsFromRules([stripe], inherited, AGENT, WORDS)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", unbuilt: "every_call_unheld" }]);
    const cent: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [{ quantity: "sum(amount)", window: "call", limit: 1, over: "ask" }, EACH, DAY] }, ...reads];
    expect(cardsFromRules([stripe], cent, AGENT, WORDS)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", unbuilt: "every_call_unheld" }]);
    expect(moneyCardsOff([stripe], [readsCard, limitsCard(), holdCard(cap(5000), [kiffsCount])], inherited, AGENT, WORDS)).toMatchObject({ unbuilt: "every_call_unheld" });
  });

  // An Eval, 26 Nov, run 2: "Refund a charge: up to €5 each, by itself; over €5 asks you first; past €50 a day it is
  // refused", agreed with the €5 cap inheriting the rule's ask. It was refused for the inheritance alone (ask_inherited, now
  // gone), Coach told the owner such a cap couldn't be set, and a €4 refund meant to run by itself was held.
  it("a cap inheriting the rule's ask is a cap that asks past it: run 2's rules give the refused day and the held cap", () => {
    const said = "Refund a charge: up to €5 each, by itself; over €5 asks you first; past €50 a day it is refused.";
    const run2: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [{ quantity: "sum(amount)", window: "call", limit: 500 }, { quantity: "sum(amount)", window: "calendar_day", limit: 5000, over: "refuse" }] }, ...reads];
    expect(saysEveryCallAsks(said)).toBe(false);
    const got = cardsFromRules([stripe], run2, AGENT, said)[0]!;
    expect(got).toEqual({ tool: "Refund a charge in EUR", action: "create_refund", cards: [
      { id: `${AGENT}-create_refund-refused`, subject: AGENT, domain: "gateway", grants: [{ action: "create_refund" }], aggregates: [{ quantity: "sum(amount)", limit: 5000, window: "calendar_day" }], on_exceed: "refuse" },
      { id: `${AGENT}-create_refund-held`, subject: AGENT, domain: "gateway", grants: [{ action: "create_refund", per_action_max: [{ quantity: "sum(amount)", max: 500 }] }], aggregates: [kiffsCount], on_exceed: "owner" },
    ] });
    const cards = [readsCard, ...("cards" in got ? got.cards : [])];
    expect(moneyCardsOff([stripe], cards, run2, AGENT, said)).toBeUndefined();
    expect(kiffDecides(cards, "create_refund", 400)).toBe("runs");
    expect(kiffDecides(cards, "create_refund", 800)).toBe("held");
    expect(kiffDecides(cards, "create_refund", 400, { sum: 4800, count: 9 })).toBe("refused");
    // In a language the words aren't read in, the same: the figure's over is the rule's.
    expect(cardsFromRules([stripe], run2, AGENT, "Minden visszatérítés 5 euróig magától megy.")[0]).toEqual(got);
  });

  it("the 25 Nov cases still give no Cards: a rule that asks with no figure that asks, and every call asked in words", () => {
    const unnamed: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [{ ...EACH, over: "refuse" }, DAY] }, ...reads];
    expect(cardsFromRules([stripe], unnamed, AGENT, "")).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", unbuilt: "asks_unnamed" }]);
    expect(cardsFromRules([stripe], inherited, AGENT, "Chaque remboursement attend votre accord.")[0]).toMatchObject({ unbuilt: "every_call_unheld" });
  });

  it("with the count of 0 a day that asks, the same words build the held and refused Cards", () => {
    const named: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [EVERY_ONE, EACH, DAY] }, ...reads];
    expect(cardsFromRules([stripe], named, AGENT, WORDS)).toEqual([{ tool: "Refund a charge in EUR", action: "create_refund", cards: [REFUSED, HELD] }]);
  });

  it("without those words, a figure that asks is built as before: \"ask past €5 a refund\"", () => {
    const ask5: AgreedRule[] = [{ tool: "create_refund", over: "refuse", limits: [{ quantity: "sum(amount)", window: "call", limit: 500, over: "ask" }, { ...DAY, over: undefined }] }, ...reads];
    const text = "A refund over 5 EUR asks you first. Refunds past 200 EUR in a day are refused.";
    expect(saysEveryCallAsks(text)).toBe(false);
    expect(cardsFromRules([stripe], ask5, AGENT, text)[0]).toHaveProperty("cards");
  });
});

// tafs cloud reads a draft's Cards as KIFF shows them (GET /v1/me/agent-drafts/{id}), and KIFF writes words on each for the
// owner to read: its lines, each grant's tool title, and on each total and cap what it counts and its amount's unit. Read
// as terms, they made every money draft differ from the built Cards, the ones the refusal prescribes included, so Coach
// drafted exactly what it was told and was refused again, every time.
describe("a draft's money Cards as KIFF shows them are compared on their terms alone", () => {
  // "A refund over 5 EUR comes to me first; 50 EUR a day at most", agreed as each call's figure that asks and a day's that's refused.
  const agreed: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [
    { quantity: "sum(amount)", window: "call", limit: 500, over: "ask" },
    { quantity: "sum(amount)", window: "calendar_day", limit: 5000, over: "refuse" },
  ] }, ...reads];
  const line = (l: Record<string, unknown>) => ({ ...l, quantity_of: { kind: "sum", parameter: "amount" }, ...(l.quantity === "count" ? {} : { unit: "EUR", scale: 2 }) });
  /** A Card as KIFF's draft read shows it: its terms as drafted, with the words KIFF writes on it. */
  const shown = (c: MoneyCard | Record<string, unknown>) => {
    const card = c as { grants: Array<Record<string, unknown>>; aggregates: Array<Record<string, unknown>>; on_exceed?: string };
    return { ...card, on_exceed: card.on_exceed ?? "owner", lines: ["Refund a charge", "Up to 50.00 EUR a day"],
      grants: card.grants.map((g) => ({ ...g, title: "Refund a charge in EUR", ...(Array.isArray(g.per_action_max) ? { per_action_max: (g.per_action_max as Array<Record<string, unknown>>).map(line) } : {}) })),
      aggregates: card.aggregates.map(line) };
  };
  const built = () => {
    const m = cardsFromRules([stripe], agreed, AGENT)[0]!;
    if (!("cards" in m)) throw new Error(`no Cards built: ${JSON.stringify(m)}`);
    return m.cards;
  };

  it("the Cards a refusal prescribes, drafted and read back from KIFF, pass", () => {
    const prescribed = built();
    expect(prescribed.map((c) => c.on_exceed).sort()).toEqual(["owner", "refuse"]);
    expect(moneyCardsOff([stripe], [shown(readsCard), ...prescribed.map(shown)], agreed, AGENT)).toBeUndefined();
    // Through the JSON a draft is sent and read as, ids KIFF keeps and order aside.
    const roundTrip = JSON.parse(JSON.stringify([...prescribed].reverse().map(shown))) as unknown[];
    expect(moneyCardsOff([stripe], roundTrip, agreed, AGENT)).toBeUndefined();
  });

  it("a revision's Card, shown beside the one it revises, passes on its own terms", () => {
    const [first, second] = built();
    const revision = { ...shown(first!), revises: first!.id, unchanged: true, current: shown({ ...first!, aggregates: [{ quantity: "sum(amount)", limit: 1, window: "calendar_day" }] }) };
    expect(moneyCardsOff([stripe], [revision, shown(second!)], agreed, AGENT)).toBeUndefined();
  });

  it("any term changed is still refused, read through KIFF's words: a limit, a cap, a window, on_exceed, the action, a subject", () => {
    const refused = built().find((c) => c.on_exceed === "refuse")!;
    const held = built().find((c) => c.on_exceed === "owner")!;
    const g = (c: MoneyCard) => c.grants[0]!;
    const changed: Array<[string, MoneyCard, MoneyCard]> = [
      ["the day's limit", { ...refused, aggregates: [{ ...refused.aggregates[0]!, limit: 50000 }] }, held],
      ["the day's window", { ...refused, aggregates: [{ ...refused.aggregates[0]!, window: "calendar_month" }] }, held],
      ["each call's cap", refused, { ...held, grants: [{ ...g(held), per_action_max: [{ quantity: "sum(amount)", max: 5000 }] }] }],
      ["on_exceed", { ...refused, on_exceed: "owner" }, held],
      ["the action", refused, { ...held, grants: [{ ...g(held), action: "create_payout" }] }],
      ["the subject", { ...refused, subject: "another-agent" }, held],
    ];
    for (const [what, r, h] of changed) {
      expect(moneyCardsOff([stripe], [shown(r), shown(h)], agreed, AGENT), what).toMatchObject({ action: "create_refund" });
    }
  });

  it("only KIFF's own words are set aside, each where KIFF writes it: a term KIFF reads, or its words anywhere else, is refused", () => {
    const [first, second] = built();
    const s = () => [shown(first!), shown(second!)] as Array<Record<string, unknown> & { grants: Array<Record<string, unknown>>; aggregates: Array<Record<string, unknown>> }>;
    const cases: Array<[string, (cards: ReturnType<typeof s>) => void]> = [
      ["hold_expiry", (c) => { c[0]!.hold_expiry = "720h"; }],
      ["environment", (c) => { c[0]!.environment = "test"; }],
      ["subjects", (c) => { c[0]!.subjects = [AGENT, "another-agent"]; }],
      ["valid_until", (c) => { c[0]!.valid_until = "2099-01-01T00:00:00Z"; }],
      ["a grant's resources", (c) => { c[0]!.grants[0]!.resources = { parameter: "charge", values: ["ch_1"] }; }],
      ["a total's extra room", (c) => { c[0]!.aggregates[0]!.extra_room = [{ amount: 10000 }]; }],
      ["lines on a grant", (c) => { c[0]!.grants[0]!.lines = ["x"]; }],
      ["a unit on the Card", (c) => { c[0]!.unit = "EUR"; }],
    ];
    for (const [what, edit] of cases) {
      const cards = s();
      edit(cards);
      expect(moneyCardsOff([stripe], cards, agreed, AGENT), what).toMatchObject({ action: "create_refund" });
    }
  });
});
