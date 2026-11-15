import { describe, expect, it } from "vitest";
import type { ApprovalGrant } from "../src/cloud/approval.js";
import { RULES_MAX, rulesAgreement, rulesMismatch } from "../src/cloud/rules.js";

/** A conversation's events, as kiff/agents records them, in order. */
const notice = (id: string, rules: unknown, run = "r1") => ({ kind: "component", run, body: { type: "notice", id, tone: "info", text: "The rules", rules } });
const confirm = (id: string, agrees?: string, run = "r1") => ({ kind: "component", run, body: { type: "confirm", id, text: "Agree?", options: [{ value: "agree", label: "Agree" }, { value: "change", label: "Change something" }], ...(agrees ? { agrees } : {}) } });
const answered = (id: string, answer: unknown, extra: Record<string, unknown> = {}, run = "r1") => ({ kind: "answered", run, body: { id, type: "confirm", answer, words: String(answer), ...extra } });

const RULES = [{ tool: "refund_charge", over: "refuse" }, { tool: "send_reply", over: "ask" }];

describe("the rules the owner agreed, from the conversation (rules.ts)", () => {
  it("the person's agree to a confirm naming the notice agrees to its rules", () => {
    expect(rulesAgreement([notice("n1", RULES), confirm("c1", "n1"), answered("c1", "agree")]))
      .toEqual({ state: "agreed", notice: "n1", rules: RULES });
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
    expect(rulesAgreement([...agreed, notice("n1", RULES)])).toEqual({ state: "agreed", notice: "n1", rules: RULES });
    // Agreed in turn: the newest agreement counts.
    expect(rulesAgreement([...agreed, notice("n2", changed), confirm("c2", "n2"), answered("c2", "agree")])).toEqual({ state: "agreed", notice: "n2", rules: changed });
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
