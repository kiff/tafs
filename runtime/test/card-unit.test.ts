import { describe, expect, it } from "vitest";
import { cardUnitExceptions, collapsedCurrencyDecision } from "../src/cloud/card-unit.js";
import type { Held } from "../src/cloud/home.js";

const now = new Date("2026-11-04T12:00:00Z");
const taf = { id: "taf-example", name: "Example", kiffAgent: "agent-example" };
const exception: Held = { id: "exc-example", kind: "card_unit", subject: "agent-example", action: "answer_case", status: "held",
  breaches: [{ outcome: "amount_unit_mismatch", unit: "USD", card_unit: "EUR" }], created_at: "2026-11-02T12:00:00Z", last_at: "2026-11-04T11:00:00Z", count: 4 };
describe("KIFF currency exceptions", () => {
  it("composes one stable row from the exception, with attempt history and no approve-once action", () => {
    const result = cardUnitExceptions(taf, [exception, exception], [], "Europe/Paris", now);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ id: "exc-example", taf: "taf-example", title: "Example needs a USD limit", action_title: "Add a USD limit to this action", dismiss_title: "Dismiss", can_approve_once: false });
    expect(result[0]!.words).toContain("Its Card only allows EUR");
    expect(result[0]!.history).toBe("Tried 4 times since 2 Nov · last 12:00");
    expect(result[0]!.coach_text).toContain("ask me to approve the change");
  });
  it("does not show another taf's exception, a normal hold or a resolved mismatch", () => {
    expect(cardUnitExceptions(taf, [{ ...exception, subject: "other-agent" }, { ...exception, kind: "hold" }, { ...exception, status: "changed" }], [], "UTC", now)).toEqual([]);
  });
  it("rejects unreadable currency exceptions instead of inventing units or counts", () => {
    for (const bad of [{ ...exception, breaches: [{ outcome: "amount_unit_mismatch", unit: "unknown" }] }, { ...exception, count: -1 }, { ...exception, created_at: "yesterday" }]) {
      expect(() => cardUnitExceptions(taf, [bad], [], "UTC", now)).toThrow("could not be read");
    }
  });
  it("keeps other agents, actions, currencies, outcomes and earlier episodes visible", () => {
    const call = { proposal_id: "proposal-example", actor_id: "agent-example", action_name: "answer_case", outcome: "blocked",
      reasons: ["amount_unit_mismatch"], amount: { value: "10", unit: "USD" }, created_at: "2026-11-03T12:00:00Z" };
    expect(collapsedCurrencyDecision(call, [exception])).toBe(true);
    for (const changed of [{ actor_id: "other-agent" }, { action_name: "other_action" }, { amount: { value: "10", unit: "EUR" } },
      { outcome: "allowed" }, { outcome: "approval_required" }, { reasons: [] }, { created_at: "2026-11-01T12:00:00Z" }]) {
      expect(collapsedCurrencyDecision({ ...call, ...changed }, [exception])).toBe(false);
    }
    expect(collapsedCurrencyDecision(call, [{ ...exception, status: "changed" }])).toBe(false);
  });
});
