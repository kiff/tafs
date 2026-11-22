import { describe, expect, it } from "vitest";
import type { ApprovalGrant } from "../src/cloud/approval.js";
import { LIMITS_MAX, RULES_MAX, grantSuffix, rulesAgreement, rulesMismatch, rulesUnnamedFigures, rulesUnsaid } from "../src/cloud/rules.js";

/** A conversation's events, as kiff/agents records them, in order. */
const notice = (id: string, rules: unknown, run = "r1") => ({ kind: "component", run, body: { type: "notice", id, tone: "info", text: "The rules", rules } });
const confirm = (id: string, agrees?: string, run = "r1") => ({ kind: "component", run, body: { type: "confirm", id, text: "Agree?", options: [{ value: "agree", label: "Agree" }, { value: "change", label: "Change something" }], ...(agrees ? { agrees } : {}) } });
const answered = (id: string, answer: unknown, extra: Record<string, unknown> = {}, run = "r1") => ({ kind: "answered", run, body: { id, type: "confirm", answer, words: String(answer), ...extra } });

const RULES = [{ tool: "refund_charge", over: "refuse" }, { tool: "send_reply", over: "ask" }];

describe("the rules the owner agreed, from the conversation (rules.ts)", () => {
  it("the person's agree to a confirm naming the notice agrees to its rules", () => {
    expect(rulesAgreement([notice("n1", RULES), confirm("c1", "n1"), answered("c1", "agree")]))
      .toEqual({ state: "agreed", notice: "n1", rules: RULES, text: "The rules", connection: [] });
  });

  it("no rules notice: none, whatever else the conversation shows", () => {
    expect(rulesAgreement([])).toEqual({ state: "none" });
    expect(rulesAgreement([{ kind: "component", run: "r1", body: { type: "notice", id: "n1", text: "hi" } }, confirm("c1"), answered("c1", "agree")])).toEqual({ state: "none" });
  });

  it("only the person's own tap of agree counts (kiff-qa's negatives)", () => {
    const base = [notice("n1", RULES), confirm("c1", "n1")];
    // Change something; tafs cloud's own answer; words written while it waited; stopped waiting unanswered.
    for (const a of [answered("c1", "change"), answered("c1", "agree", { answered_by: "tafs cloud" }), answered("c1", "agree", { wrote: "ok fine" }), answered("c1", "agree", { unanswered: true })]) {
      expect(rulesAgreement([...base, a])).toEqual({ state: "unagreed", notice: "n1" });
    }
    // A confirm without agrees, or one in another run by the same id, agrees to nothing.
    expect(rulesAgreement([notice("n1", RULES), confirm("c1"), answered("c1", "agree")])).toEqual({ state: "unagreed", notice: "n1" });
    expect(rulesAgreement([...base, answered("c1", "agree", {}, "r2")])).toEqual({ state: "unagreed", notice: "n1" });
  });

  it("agrees naming an unknown id, or another conversation's notice, is no agreement", () => {
    expect(rulesAgreement([notice("n1", RULES), confirm("c1", "n9"), answered("c1", "agree")])).toEqual({ state: "unagreed", notice: "n1" });
    // The notice it names was never shown here (another conversation): nothing was agreed, and none was shown.
    expect(rulesAgreement([confirm("c1", "n-elsewhere"), answered("c1", "agree")])).toEqual({ state: "none" });
  });

  it("a rules notice newer than the agreement blocks it until agreed (kiff-qa's gap 2)", () => {
    const agreed = [notice("n1", RULES), confirm("c1", "n1"), answered("c1", "agree")];
    const changed = [{ tool: "refund_charge", over: "ask" }];
    expect(rulesAgreement([...agreed, notice("n2", changed)])).toEqual({ state: "unagreed", notice: "n2" });
    // The same notice shown again with other rules: also unagreed; with the same rules, still agreed.
    expect(rulesAgreement([...agreed, notice("n1", changed)])).toEqual({ state: "unagreed", notice: "n1" });
    expect(rulesAgreement([...agreed, notice("n1", RULES)])).toEqual({ state: "agreed", notice: "n1", rules: RULES, text: "The rules", connection: [] });
    // Agreed in turn: the newest agreement counts.
    expect(rulesAgreement([...agreed, notice("n2", changed), confirm("c2", "n2"), answered("c2", "agree")])).toEqual({ state: "agreed", notice: "n2", rules: changed, text: "The rules", connection: [] });
  });

  it("the same notice shown again with other lines is unagreed: the owner reads another claim (kiff-qa's lines gap)", () => {
    const withLines = (lines: unknown[]) => ({ kind: "component", run: "r1", body: { type: "notice", id: "n1", tone: "info", lang: "en", rules: RULES, lines } });
    const card = { text: "Refunds at most $50 a call", kept: "card", tools: ["refund_charge"] };
    const original = { text: "Refunds only to the original card", kept: "instructions" };
    const agreed = [withLines([card, original]), confirm("c1", "n1"), answered("c1", "agree")];
    // A line's kept changed, from the instructions to the connection.
    expect(rulesAgreement([...agreed, withLines([card, { ...original, kept: "connection", tools: ["refund_charge"] }])])).toEqual({ state: "unagreed", notice: "n1" });
    // A connection line's tools changed.
    const kept = { text: "Refunds only to the original card", kept: "connection", tools: ["refund_charge"] };
    const agreedKept = [withLines([card, kept]), confirm("c1", "n1"), answered("c1", "agree")];
    expect(rulesAgreement([...agreedKept, withLines([card, { ...kept, tools: ["refund_charge", "send_reply"] }])])).toEqual({ state: "unagreed", notice: "n1" });
    // A line's words, or a kiff line's what, changed; a line added.
    expect(rulesAgreement([...agreed, withLines([card, { ...original, text: "Refunds to any card" }])])).toEqual({ state: "unagreed", notice: "n1" });
    const disclosure = { text: "Signs each reply as AI", kept: "kiff", what: "disclosure" };
    expect(rulesAgreement([withLines([disclosure]), confirm("c1", "n1"), answered("c1", "agree"), withLines([{ ...disclosure, what: "held_approval" }])])).toEqual({ state: "unagreed", notice: "n1" });
    expect(rulesAgreement([...agreed, withLines([card, original, disclosure])])).toEqual({ state: "unagreed", notice: "n1" });
    // Shown again identical, the tools in another order: still agreed, with its connection lines.
    const same = rulesAgreement([...agreedKept, withLines([card, { ...kept }])]);
    expect(same).toMatchObject({ state: "agreed", notice: "n1", connection: [{ text: "Refunds only to the original card", tools: ["refund_charge"] }] });
    const two = { text: "Refunds only to the original card", kept: "connection", tools: ["refund_charge", "send_reply"] };
    expect(rulesAgreement([withLines([two]), confirm("c1", "n1"), answered("c1", "agree"), withLines([{ ...two, tools: ["send_reply", "refund_charge"] }])])).toMatchObject({ state: "agreed" });
  });

  it("a notice whose rules can't be read whole carries none", () => {
    const tooMany = Array.from({ length: RULES_MAX + 1 }, (_, i) => ({ tool: `t${i}`, over: "refuse" }));
    for (const bad of [tooMany, [{ tool: "x", over: "maybe" }], [{ tool: "", over: "ask" }], "refund", []]) {
      expect(rulesAgreement([notice("n1", bad), confirm("c1", "n1"), answered("c1", "agree")])).toEqual({ state: "none" });
    }
  });
});

const tool = (t: string, suffix: string, over: Record<string, unknown> = {}) => ({ tool: t, name: `${t}_${suffix}`, title: t.replace(/_/g, " "), ...over });
const grantOf = (id: string, tools: unknown[]): ApprovalGrant => ({ id, status: "pending", digest: "d", request: { agent: "refunds-x", tools: tools as never } });
const card = (action: string | string[], on_exceed?: string) => ({ id: "c", subject: "refunds-x", ...(on_exceed ? { on_exceed } : {}), grants: [action].flat().map((a) => ({ action: a })), aggregates: [] });

describe("a draft's Cards against the agreed rules", () => {
  const stripe = grantOf("g1", [tool("refund_charge", "k3"), tool("list_charges", "k3", { read_only: true })]);

  it("ask is on_exceed owner, refuse is refuse, by planque's name before the suffix", () => {
    const rules = [{ tool: "refund_charge", over: "refuse" as const }];
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "refuse")], rules)).toBeUndefined();
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "owner")], rules)).toEqual({ kind: "on_exceed", tool: "refund charge", over: "refuse", onExceed: "owner" });
    // A Card that says nothing holds for the owner, as KIFF does.
    expect(rulesMismatch([stripe], [card("refund_charge_k3")], rules)?.kind).toBe("on_exceed");
    const ask = [{ tool: "refund_charge", over: "ask" as const }];
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "owner")], ask)).toBeUndefined();
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "refuse")], ask)).toEqual({ kind: "on_exceed", tool: "refund charge", over: "ask", onExceed: "refuse" });
    // A rule keyed by KIFF's name is read too.
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "owner")], [{ tool: "refund_charge_k3", over: "ask" }])).toBeUndefined();
  });

  it("a rule names a managed tool by its job name: KIFF's name with this grant's own suffix off", () => {
    // The owner's phone, 18 Nov: a Gmail write whose `tool` is the connector's id, named send_reminder_3qa1 on
    // grt_3Qa1…; every notice's rule said send_reminder, and the write was refused three times as missing.
    const gmail: ApprovalGrant = { id: "grt_3Qa1xYz9", status: "pending", digest: "d", request: { agent: "chase-x", tools: [
      { tool: "connector_cac677194bf6ba46b262eed82d2a52fd", name: "send_reminder_3qa1", title: "Email a payment reminder to you" },
    ] as never } };
    const rules = [{ tool: "send_reminder", over: "ask" as const }];
    expect(rulesMismatch([gmail], [card("send_reminder_3qa1", "owner")], rules)).toBeUndefined();
    expect(rulesMismatch([gmail], [card("send_reminder_3qa1", "refuse")], rules)?.kind).toBe("on_exceed");
    // Only this grant's own suffix comes off: another grant's tool, or a name the suffix doesn't end, isn't covered.
    const other: ApprovalGrant = { ...gmail, id: "grt_9zzzAbc" };
    expect(rulesMismatch([other], [card("send_reminder_3qa1", "owner")], rules)).toEqual({ kind: "missing", tool: "Email a payment reminder to you" });
    const bare: ApprovalGrant = { ...gmail, request: { agent: "chase-x", tools: [{ tool: "connector_x", name: "send_reminder", title: "t" }] as never } };
    expect(rulesMismatch([bare], [card("send_reminder", "owner")], [{ tool: "send", over: "ask" }])).toEqual({ kind: "missing", tool: "t" });
    // A second name taken on KIFF gives numberedKey's n after the suffix (2 and up); the job still covers it.
    const numbered: ApprovalGrant = { ...gmail, request: { agent: "chase-x", tools: [{ tool: "connector_x", name: "send_reminder_3qa12", title: "t" }] as never } };
    expect(rulesMismatch([numbered], [card("send_reminder_3qa12", "owner")], rules)).toBeUndefined();
    const one: ApprovalGrant = { ...gmail, request: { agent: "chase-x", tools: [{ tool: "connector_x", name: "send_reminder_3qa11", title: "t" }] as never } };
    expect(rulesMismatch([one], [card("send_reminder_3qa11", "owner")], rules)?.kind).toBe("missing");
    // A job planque cut at 59 characters covers the rule that names it whole; one shorter covers only itself.
    const long = "send_a_payment_reminder_to_the_customer_whose_invoice_is_overdue";
    const cut: ApprovalGrant = { ...gmail, request: { agent: "chase-x", tools: [{ tool: "connector_x", name: `${long.slice(0, 59)}_3qa1`, title: "t" }] as never } };
    expect(rulesMismatch([cut], [card(`${long.slice(0, 59)}_3qa1`, "owner")], [{ tool: long, over: "ask" }])).toBeUndefined();
    expect(rulesMismatch([gmail], [card("send_reminder_3qa1", "owner")], [{ tool: "send_reminder_and_more", over: "ask" }])?.kind).toBe("missing");
    // The title still covers nothing.
    expect(rulesMismatch([gmail], [card("send_reminder_3qa1", "owner")], [{ tool: "Email a payment reminder to you", over: "ask" }])?.kind).toBe("missing");
  });

  it("a grant's suffix is planque's toolKey: _ and the id's first four letters or digits, lowercased", () => {
    expect(grantSuffix("grt_3Qa1xYz9")).toBe("_3qa1");
    expect(grantSuffix("grt_-A_b-C1d")).toBe("_abc1");
    expect(grantSuffix("grt_")).toBe("");
    expect(grantSuffix("g1")).toBe("_g1");
  });

  it("every Card covering the tool must match, not one of them", () => {
    const rules = [{ tool: "refund_charge", over: "refuse" as const }];
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "refuse"), card("refund_charge_k3", "owner")], rules)?.kind).toBe("on_exceed");
  });

  it("a write with no rule isn't signed; a read with none is; a rule for a tool no grant has is ignored", () => {
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "refuse")], [{ tool: "list_charges", over: "refuse" }])).toEqual({ kind: "missing", tool: "refund charge" });
    expect(rulesMismatch([stripe], [card("list_charges_k3", "refuse")], [{ tool: "refund_charge", over: "refuse" }])).toBeUndefined();
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "owner")], [{ tool: "delete_everything", over: "ask" }, { tool: "refund_charge", over: "refuse" }])?.kind).toBe("on_exceed");
    expect(rulesMismatch([stripe], [card("refund_charge_k3", "refuse")], [{ tool: "delete_everything", over: "ask" }])).toEqual({ kind: "missing", tool: "refund charge" });
  });

  it("two apps: the app whose Card doesn't match is the one named", () => {
    const gmail = grantOf("g2", [tool("send_reply", "q7")]);
    const rules = [{ tool: "refund_charge", over: "refuse" as const }, { tool: "send_reply", over: "ask" as const }];
    expect(rulesMismatch([stripe, gmail], [card("refund_charge_k3", "refuse"), card("send_reply_q7", "owner")], rules)).toBeUndefined();
    expect(rulesMismatch([stripe, gmail], [card("refund_charge_k3", "refuse"), card("send_reply_q7", "refuse")], rules)).toEqual({ kind: "on_exceed", tool: "send reply", over: "ask", onExceed: "refuse" });
    // One Card over both tools can't satisfy two different rules.
    expect(rulesMismatch([stripe, gmail], [card(["refund_charge_k3", "send_reply_q7"], "refuse")], rules)?.tool).toBe("send reply");
  });

  it("a tool no Card in the draft covers is the coverage checks', not this one's", () => {
    expect(rulesMismatch([stripe], [card("something_else", "owner")], [])).toBeUndefined();
  });
});

describe("the figures the owner agreed (each call's, the day's total)", () => {
  const stripe = grantOf("g1", [tool("refund_charge", "k3"), tool("list_charges", "k3", { read_only: true })]);
  const EACH = { quantity: "sum(amount)", window: "call" as const, limit: 500 };
  const DAY = { quantity: "sum(amount)", window: "calendar_day" as const, limit: 5000 };
  const rule = (limits = [EACH, DAY]) => [{ tool: "refund_charge", over: "refuse" as const, limits }];
  /** A refund Card with a per-call cap and aggregates. */
  const refund = (max: number | undefined, aggregates: unknown[], on_exceed = "refuse", action = "refund_charge_k3") => ({ id: "c", subject: "refunds-x", on_exceed,
    grants: [{ action, ...(max === undefined ? {} : { per_action_max: [{ quantity: "sum(amount)", max }] }) }], aggregates });
  const day = (limit: number, extra: Record<string, unknown> = {}) => ({ quantity: "sum(amount)", window: "calendar_day", limit, ...extra });

  it("read with the rules: agreed, and the same notice shown with another figure is other rules", () => {
    const withFigures = rule();
    expect(rulesAgreement([notice("n1", withFigures), confirm("c1", "n1"), answered("c1", "agree")])).toEqual({ state: "agreed", notice: "n1", rules: withFigures, text: "The rules", connection: [] });
    const agreed = [notice("n1", withFigures), confirm("c1", "n1"), answered("c1", "agree")];
    expect(rulesAgreement([...agreed, notice("n1", rule([EACH, { ...DAY, limit: 50000 }]))])).toEqual({ state: "unagreed", notice: "n1" });
    // The same figures in another order are the same rules.
    expect(rulesAgreement([...agreed, notice("n1", rule([DAY, EACH]))]).state).toBe("agreed");
  });

  it("KIFF's own count, listed as a rule's figure, is never the owner's: not held to the notice's words, not required on a Card (Eval 45062249364)", () => {
    const KIFFS = { quantity: "count", window: "calendar_day" as const, limit: 10_000 };
    const listed = [...rule([EACH, DAY, KIFFS]), { tool: "list_charges", over: "ask" as const, limits: [KIFFS] }];
    const agreement = rulesAgreement([notice("n1", listed), confirm("c1", "n1"), answered("c1", "agree")]);
    // The refund keeps the owner's two figures; the reads' rule, with only KIFF's count, has none.
    expect(agreement).toEqual({ state: "agreed", notice: "n1", rules: [...rule(), { tool: "list_charges", over: "ask" }], text: "The rules", connection: [] });
    const rules = agreement.state === "agreed" ? agreement.rules : [];
    // A notice that never writes 10,000 says every figure agreed.
    expect(rulesUnsaid(rules, "Refunds up to €5 each and €50 a day. Charges listed freely.")).toEqual([]);
    // The refund's Cards with the owner's figures and no 10,000 count match.
    expect(rulesMismatch([stripe], [refund(500, [day(5000)])], rules)).toBeUndefined();
    // Only exactly KIFF's count goes: 9,999, a per total, or another window is the owner's figure as before.
    // Each near miss differs from KIFF's count in one way only (qa-kiff's mutants): the limit, the quantity, the window
    // (each call's), or a per (a day's, a week's, a lifetime's total for each charge).
    for (const other of [{ ...KIFFS, limit: 9_999 }, { ...KIFFS, quantity: "sum(amount)" }, { ...KIFFS, window: "call" as const },
      { ...KIFFS, per: "charge" }, { ...KIFFS, window: "rolling_7d", per: "charge" }, { ...KIFFS, window: "lifetime", per: "charge" }]) {
      const kept = rulesAgreement([notice("n1", rule([EACH, other])), confirm("c1", "n1"), answered("c1", "agree")]);
      expect(kept.state === "agreed" && kept.rules[0]!.limits, JSON.stringify(other)).toEqual([EACH, other]);
    }
  });

  // kiff-qa's 3 to 6: a rule's figures are read whole or not at all, so a notice with one it can't read carries no rules.
  it("figures that can't be read whole leave the notice with no rules", () => {
    const bad: unknown[] = [
      [], // given but empty: never "figures agreed"
      Array.from({ length: LIMITS_MAX + 1 }, (_, i) => ({ quantity: `sum(a${i})`, window: "call", limit: 1 })),
      [EACH, { ...EACH, limit: 600 }], // the same quantity and window twice
      [{ ...EACH, limit: -1 }], [{ ...EACH, limit: 1.5 }], [{ ...EACH, limit: 2 ** 53 }], [{ ...EACH, limit: "500" }], [{ quantity: "sum(amount)", window: "call" }],
      [{ ...EACH, window: "lifetime" }], [{ ...EACH, per: "order_id" }], [{ ...DAY, per: "order id" }], [{ ...DAY, per: "" }], [{ ...DAY, per: 7 }], [{ ...DAY, window: "ROLLING_7D", per: "order_id" }],
      [{ ...DAY, window: "lifetime", per: "order_id" }, { ...DAY, window: "lifetime", per: "order_id", limit: 1 }], // the same quantity, window and per twice
      [{ ...EACH, quantity: "sum( amount )" }], [{ ...EACH, quantity: "SUM(amount)" }], [{ ...EACH, quantity: "amount" }], [{ ...EACH, quantity: "sum()" }], [{ ...EACH, quantity: `sum(${"a".repeat(65)})` }],
      "500",
    ];
    for (const limits of bad) {
      expect(rulesAgreement([notice("n1", [{ tool: "refund_charge", over: "refuse", limits }]), confirm("c1", "n1"), answered("c1", "agree")])).toEqual({ state: "none" });
    }
    // An argument as kiff/agents' schema takes it (letters, digits, _ and .) is read.
    expect(rulesAgreement([notice("n1", rule([{ ...EACH, quantity: "sum(amount.total_Cents)" }])), confirm("c1", "n1"), answered("c1", "agree")]).state).toBe("agreed");
    // 0 is a figure (never), and 2^53−1 the largest one read exactly.
    for (const limit of [0, Number.MAX_SAFE_INTEGER]) {
      expect(rulesAgreement([notice("n1", rule([{ ...EACH, limit }])), confirm("c1", "n1"), answered("c1", "agree")]).state).toBe("agreed");
    }
  });

  it("each agreed figure is on the Card exactly: missing, looser or stricter is refused", () => {
    expect(rulesMismatch([stripe], [refund(500, [day(5000)])], rule())).toBeUndefined();
    // The Card at 50000 a call against an agreed 500.
    expect(rulesMismatch([stripe], [refund(50000, [day(5000)])], rule())).toEqual({ kind: "figure", tool: "refund charge", quantity: "sum(amount)", window: "call", agreed: 500, drafted: 50000 });
    // A stricter day's total: the owner didn't agree to it either.
    expect(rulesMismatch([stripe], [refund(500, [day(2000)])], rule())).toEqual({ kind: "figure", tool: "refund charge", quantity: "sum(amount)", window: "calendar_day", agreed: 5000, drafted: 2000 });
    // No day's total at all, or no per-call cap.
    expect(rulesMismatch([stripe], [refund(500, [])], rule())).toEqual({ kind: "figure", tool: "refund charge", quantity: "sum(amount)", window: "calendar_day", agreed: 5000 });
    expect(rulesMismatch([stripe], [refund(undefined, [day(5000)])], rule())?.kind).toBe("figure");
    // 0 agreed is 0 drafted, not a missing figure.
    expect(rulesMismatch([stripe], [refund(0, [day(5000)])], rule([{ ...EACH, limit: 0 }, DAY]))).toBeUndefined();
  });

  // kiff-qa's 1: a total for each customer at the agreed figure isn't the day's total.
  it("an aggregate with per set doesn't stand for the day's total", () => {
    expect(rulesMismatch([stripe], [refund(500, [day(5000, { per: "customer" })])], rule())).toEqual({ kind: "figure", tool: "refund charge", quantity: "sum(amount)", window: "calendar_day", agreed: 5000 });
  });

  // kiff-qa's 2: one matching Card doesn't excuse a looser one beside it.
  it("two Cards over the tool, one matching and one looser, is refused", () => {
    expect(rulesMismatch([stripe], [refund(500, [day(5000)]), refund(500, [day(9000)])], rule())?.kind).toBe("figure");
    // The figure on one Card and the day's total on another is the agreed rules, kept as two Cards.
    expect(rulesMismatch([stripe], [refund(500, []), refund(undefined, [day(5000)])], rule())).toBeUndefined();
  });

  // kiff-qa's 6: the quantity matches as written.
  it("a quantity spelled another way on the Card isn't the agreed figure", () => {
    const spaced = { id: "c", subject: "refunds-x", on_exceed: "refuse", grants: [{ action: "refund_charge_k3", per_action_max: [{ quantity: "sum( amount )", max: 500 }] }], aggregates: [day(5000)] };
    expect(rulesMismatch([stripe], [spaced], rule())).toEqual({ kind: "figure", tool: "refund charge", quantity: "sum(amount)", window: "call", agreed: 500 });
  });

  it("a rule with no figures is checked as before; on_exceed is checked first", () => {
    expect(rulesMismatch([stripe], [refund(50000, [])], [{ tool: "refund_charge", over: "refuse" }])).toBeUndefined();
    expect(rulesMismatch([stripe], [refund(500, [day(5000)], "owner")], rule())?.kind).toBe("on_exceed");
  });

  it("another connection's tool of the same taf is held to its figures too", () => {
    const shop = grantOf("g2", [tool("refund_order", "s9")]);
    const rules = [...rule(), { tool: "refund_order", over: "refuse" as const, limits: [{ ...EACH, limit: 1000 }] }];
    expect(rulesMismatch([stripe, shop], [refund(500, [day(5000)]), refund(1000, [], "refuse", "refund_order_s9")], rules)).toBeUndefined();
    expect(rulesMismatch([stripe, shop], [refund(500, [day(5000)]), refund(9999, [], "refuse", "refund_order_s9")], rules)).toMatchObject({ kind: "figure", tool: "refund order", agreed: 1000, drafted: 9999 });
  });

  // Eval 43927756622 (Setup, run 1): after a refused draft, Coach signed an hour's €50 held on the refunds, beside the agreed
  // €5 a refund and €50 a day refused. Any window counts, not only each call's and the day's.
  it("figures the rules don't name, in any window, are each named, with the tool as the connection titles it", () => {
    const count = { quantity: "count", window: "calendar_day", limit: 100 };
    const cards = [refund(500, [day(5000), count, day(9, { per: "customer" })])];
    expect(rulesMismatch([stripe], cards, rule())).toBeUndefined();
    expect(rulesUnnamedFigures([stripe], cards, rule())).toEqual([{ tool: "refund charge", quantity: "count", window: "calendar_day", per: "", limit: 100 }]);
    // The Eval's draft: the agreed figures, plus an hour's total on the held Card.
    const split = [{ tool: "refund_charge", over: "refuse" as const, limits: [{ ...EACH, over: "ask" as const }, DAY] }];
    const hour = [refund(500, [{ quantity: "sum(amount)", window: "rolling_1h", limit: 5000 }], "owner"), refund(undefined, [day(5000)])];
    expect(rulesMismatch([stripe], hour, split)).toBeUndefined();
    expect(rulesUnnamedFigures([stripe], hour, split)).toEqual([{ tool: "refund charge", quantity: "sum(amount)", window: "rolling_1h", per: "", limit: 5000 }]);
    for (const window of ["rolling_24h", "rolling_7d", "lifetime", "ROLLING_1H"]) {
      expect(rulesUnnamedFigures([stripe], [refund(500, [day(5000), { quantity: "sum(amount)", window, limit: 1 }])], rule())).toHaveLength(1);
    }
    // As agreed, a total for each item (another check's), and a count of 0 (each call asks): none named.
    expect(rulesUnnamedFigures([stripe], [refund(500, [day(5000)])], rule())).toEqual([]);
    expect(rulesUnnamedFigures([stripe], [refund(500, [day(5000), day(9, { per: "customer" }), { quantity: "count", window: "calendar_day", limit: 0 }])], rule())).toEqual([]);
    // KIFF's own count (10,000 a day) as a held Card's only total is never the owner's: not named. On a refused Card,
    // beside another total, or at another figure, it is (Eval 43975057022, Setup run 3).
    const kiffs = { quantity: "count", window: "calendar_day", limit: 10_000 };
    expect(rulesUnnamedFigures([stripe], [refund(500, [kiffs], "owner"), refund(undefined, [day(5000)])], split)).toEqual([]);
    expect(rulesMismatch([stripe], [refund(500, [kiffs], "owner"), refund(undefined, [day(5000)])], split)).toBeUndefined();
    expect(rulesUnnamedFigures([stripe], [refund(500, [day(5000), kiffs])], rule())).toHaveLength(1);
    expect(rulesUnnamedFigures([stripe], [refund(500, [kiffs, { quantity: "sum(amount)", window: "rolling_1h", limit: 1 }], "owner"), refund(undefined, [day(5000)])], split)).toHaveLength(2);
    expect(rulesUnnamedFigures([stripe], [refund(500, [{ ...kiffs, limit: 9_999 }], "owner"), refund(undefined, [day(5000)])], split)).toHaveLength(1);
    expect(rulesUnnamedFigures([stripe], [refund(500, [kiffs], "owner")], split)).toHaveLength(1);
    expect(rulesUnnamedFigures([stripe], [refund(500, [kiffs], "owner"), refund(undefined, [day(5000, { per: "customer" })])], split)).toHaveLength(1);
    // A rule with no figures names none to compare: nothing is named.
    expect(rulesUnnamedFigures([stripe], cards, [{ tool: "refund_charge", over: "refuse" }])).toEqual([]);
  });

  // Kiff Admin Worker's edge on #1267: a total for each item is a figure the owner agrees like the others.
  it("a total for each item is agreed as (quantity, window, per), and held to it on a rule that names one", () => {
    const PER = { quantity: "count", window: "lifetime", per: "charge", limit: 1 };
    const once = { quantity: "count", window: "lifetime", per: "charge", limit: 1 };
    // Read with the rules, and the same figure with another per is other rules.
    const agreed = [notice("n1", rule([EACH, DAY, PER])), confirm("c1", "n1"), answered("c1", "agree")];
    expect(rulesAgreement(agreed)).toMatchObject({ state: "agreed", rules: [{ limits: [EACH, DAY, PER] }] });
    expect(rulesAgreement([...agreed, notice("n1", rule([EACH, DAY, { ...PER, per: "customer" }]))]).state).toBe("unagreed");
    // On the Cards as agreed (per in any case): signed, and nothing unnamed.
    const cards = [refund(500, [day(5000), { ...once, per: " Charge" }])];
    expect(rulesMismatch([stripe], cards, rule([EACH, DAY, PER]))).toBeUndefined();
    expect(rulesUnnamedFigures([stripe], cards, rule([EACH, DAY, PER]))).toEqual([]);
    // Missing, or another limit: the agreed figure isn't what's signed.
    expect(rulesMismatch([stripe], [refund(500, [day(5000)])], rule([EACH, DAY, PER]))).toMatchObject({ kind: "figure", window: "lifetime", per: "charge", agreed: 1 });
    expect(rulesMismatch([stripe], [refund(500, [day(5000), { ...once, limit: 3 }])], rule([EACH, DAY, PER]))).toMatchObject({ kind: "figure", per: "charge", agreed: 1, drafted: 3 });
    // A per total the rule doesn't name, beside one it does: another per, window or quantity.
    for (const extra of [{ ...once, per: "customer" }, { ...once, window: "rolling_7d" }, { ...once, quantity: "sum(amount)", limit: 5000 }]) {
      expect(rulesUnnamedFigures([stripe], [refund(500, [day(5000), once, extra])], rule([EACH, DAY, PER]))).toEqual([{ tool: "refund charge", ...extra, limit: extra.limit }]);
    }
    // A per total for the day isn't the day's total: the rule's day figure doesn't name it.
    expect(rulesUnnamedFigures([stripe], [refund(500, [day(5000), once, day(5000, { per: "customer" })])], rule([EACH, DAY, PER]))).toEqual([{ tool: "refund charge", quantity: "sum(amount)", window: "calendar_day", per: "customer", limit: 5000 }]);
    // On a rule that names no per total: left to the shape checks, until TAFS_RULES_PER holds it to the letter.
    expect(rulesUnnamedFigures([stripe], [refund(500, [day(5000), once])], rule())).toEqual([]);
    expect(rulesUnnamedFigures([stripe], [refund(500, [day(5000), once])], rule(), true)).toEqual([{ tool: "refund charge", ...once }]);
  });

  it("figures the notice's words don't say are listed, in the Card's units or major units", () => {
    expect(rulesUnsaid(rule(), "Refunds up to €5 each, and €50 a day, are refused past that.")).toEqual([]);
    expect(rulesUnsaid(rule(), "Remboursements jusqu'à 5,00 € chacun et 50 € par jour.")).toEqual([]);
    expect(rulesUnsaid(rule([{ ...DAY, limit: 100000 }]), "Bis 1.000,00 € am Tag.")).toEqual([]);
    expect(rulesUnsaid(rule([{ quantity: "count", window: "calendar_day", limit: 1000 }]), "At most 1,000 a day.")).toEqual([]);
    expect(rulesUnsaid(rule(), "Refunds up to €5 each.")).toEqual(["refund_charge sum(amount) calendar_day 5000"]);
  });

  it("1 is said in words only as a one that is never an article, beside its language's per word; anything unsure is unsaid", () => {
    const once = [{ tool: "create_invoice", over: "refuse" as const, limits: [{ quantity: "count", window: "lifetime", limit: 1, per: "order_id" }] }];
    const unsaid = ["create_invoice count lifetime 1 per order_id"];
    for (const text of ["One invoice per order, ever.", "Één factuur per bestelling.", "Één factuur voor elke bestelling.", "Eins pro Bestellung.",
      "Jedna faktura dla każdego zamówienia.", "Jedna faktura robocza na każde zamówienie.", "Invoices makes 1 draft per order.", "1 facture par commande."]) {
      expect(rulesUnsaid(once, text), text).toEqual([]);
    }
    // An article, which doubles as "one" in most languages, never says 1: nor "for each one", a one with no per word, a
    // per word too far, a per word that is a preposition, or a pair from two languages.
    for (const text of ["A draft invoice for each order.", "An invoice per order.", "A draft invoice for each one.", "Makes one draft invoice.",
      "One draft invoice from the shop and so on per run.", "Un rapport que je relis.", "Uno entwurf pro Bestellung.", "Every order gets its invoice.",
      "Une facture par commande.", "Eine Rechnung pro Bestellung.", "Ein Entwurf pro Bestellung.", "Eins je nach Bedarf.", "Una factura por cada pedido.", "Uma fatura por encomenda.", "Een factuur per bestelling.",
      // mac-local's five: the article and a preposition ("by", "for", "on", "depending on").
      "Una factura borrador enviada por correo, hasta 10 al día.", "Una bozza di fattura per il cliente, fino a 10 al giorno.",
      "Jedna wersja robocza na koncie, do 10 dziennie.", "Ein Entwurf je nach Bedarf, bis zu 10 am Tag.", "Een conceptfactuur per e-mail, tot 10 per dag."]) {
      expect(rulesUnsaid(once, text), text).toEqual(unsaid);
    }
    // One said in words is 1, never 10: the day's 10 still has to be written.
    expect(rulesUnsaid([{ ...once[0]!, limits: [...once[0]!.limits, { quantity: "count", window: "calendar_day", limit: 10 }] }], "One invoice per order.")).toEqual(["create_invoice count calendar_day 10"]);
  });
});

// Eval 16's refunds: "ask past €5 a refund, refuse past €50 a day" is one tool with two answers, so the answer goes on
// the figure (17 Nov): the rule's `over` is the default for a figure that says none.
describe("what happens past each figure (over on a limit)", () => {
  const stripe = grantOf("g1", [tool("refund_charge", "k3")]);
  const EACH = { quantity: "sum(amount)", window: "call" as const, limit: 500, over: "ask" as const };
  const DAY = { quantity: "sum(amount)", window: "calendar_day" as const, limit: 5000 };
  const rules = [{ tool: "refund_charge", over: "refuse" as const, limits: [EACH, DAY] }];
  const each = (onExceed: string) => ({ id: "each", subject: "refunds-x", on_exceed: onExceed, grants: [{ action: "refund_charge_k3", per_action_max: [{ quantity: "sum(amount)", max: 500 }] }], aggregates: [] });
  const day = (onExceed: string) => ({ id: "day", subject: "refunds-x", on_exceed: onExceed, grants: [{ action: "refund_charge_k3" }], aggregates: [{ quantity: "sum(amount)", window: "calendar_day", limit: 5000 }] });

  it("reads a figure's over with the rules, and the same rules with another over are other rules", () => {
    expect(rulesAgreement([notice("n1", rules), confirm("c1", "n1"), answered("c1", "agree")])).toEqual({ state: "agreed", notice: "n1", rules, text: "The rules", connection: [] });
    const flipped = [{ ...rules[0]!, limits: [{ ...EACH, over: "refuse" as const }, DAY] }];
    expect(rulesAgreement([notice("n1", rules), confirm("c1", "n1"), answered("c1", "agree"), notice("n1", flipped)])).toEqual({ state: "unagreed", notice: "n1" });
    for (const over of ["maybe", "", 1, null]) {
      expect(rulesAgreement([notice("n1", [{ tool: "refund_charge", over: "refuse", limits: [{ ...EACH, over }] }]), confirm("c1", "n1"), answered("c1", "agree")])).toEqual({ state: "none" });
    }
  });

  it("each Card answers as its figures say: the held call figure on its own Card, the refused day's total on another", () => {
    expect(rulesMismatch([stripe], [each("owner"), day("refuse")], rules)).toBeUndefined();
    expect(rulesMismatch([stripe], [each("refuse"), day("refuse")], rules)).toEqual({ kind: "on_exceed", tool: "refund charge", over: "ask", onExceed: "refuse" });
    expect(rulesMismatch([stripe], [each("owner"), day("owner")], rules)).toEqual({ kind: "on_exceed", tool: "refund charge", over: "refuse", onExceed: "owner" });
  });

  it("two figures with different answers on one Card are refused: one Card has one on_exceed", () => {
    const both = { ...each("owner"), aggregates: day("refuse").aggregates };
    expect(rulesMismatch([stripe], [both], rules)).toEqual({ kind: "mixed", tool: "refund charge" });
    expect(rulesMismatch([stripe], [{ ...both, on_exceed: "refuse" }], rules)).toEqual({ kind: "mixed", tool: "refund charge" });
    // The same answer on both figures may share a Card.
    const sameAnswer = [{ tool: "refund_charge", over: "refuse" as const, limits: [{ ...EACH, over: "refuse" as const }, DAY] }];
    expect(rulesMismatch([stripe], [{ ...both, on_exceed: "refuse" }], sameAnswer)).toBeUndefined();
  });

  it("a figure that says no over takes the tool's, and a Card with no agreed figure answers as the tool does", () => {
    const plain = [{ tool: "refund_charge", over: "refuse" as const, limits: [{ ...EACH, over: undefined }, DAY] }];
    expect(rulesMismatch([stripe], [each("refuse"), day("refuse")], plain)).toBeUndefined();
    const other = { id: "o", subject: "refunds-x", on_exceed: "owner", grants: [{ action: "refund_charge_k3" }], aggregates: [{ quantity: "sum(fee)", window: "calendar_day", limit: 9 }] };
    expect(rulesMismatch([stripe], [each("owner"), day("refuse"), other], rules)).toEqual({ kind: "on_exceed", tool: "refund charge", over: "refuse", onExceed: "owner" });
  });
});
