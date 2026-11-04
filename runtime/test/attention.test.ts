import { describe, expect, it } from "vitest";
import { cardUnitAsks } from "../src/cloud/attention.js";
import type { AgentAsk } from "../src/cloud/agents.js";

const now = new Date("2026-11-04T12:00:00Z");
const ask: AgentAsk = { id: "ask-example", kind: "card_unit", tool: "answer_case", unit: "USD", card_unit: "EUR", first_at: "2026-11-02T12:00:00Z", last_at: "2026-11-04T11:00:00Z", count: 4 };
describe("persistent currency requests", () => {
  it("composes one stable ask per tool and currency, with honest history and an ordinary Coach request", () => {
    const result = cardUnitAsks({ id: "taf-example", name: "Example" }, [ask, ask], [], "Europe/Paris", now);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ id: "taf-example:ask-example", taf: "taf-example", title: "Example needs a USD limit", action_title: "Add a USD limit in Coach" });
    expect(result[0]!.words).toContain("Its Card only allows EUR");
    expect(result[0]!.history).toBe("Tried 4 times since 2 Nov · last 12:00");
    expect(result[0]!.coach_text).toContain("ask me to approve the change");
    expect(cardUnitAsks({ id: "other-taf", name: "Other" }, [ask], [], "UTC", now)[0]!.id).not.toBe(result[0]!.id);
  });
  it("does not manufacture a limit when the source cannot name one, and rejects unreadable known asks", () => {
    expect(cardUnitAsks({ id: "taf-example", name: "Example" }, [{ ...ask, card_unit: undefined }], [], "UTC", now)[0]!.words).toContain("Its Card has no USD limit");
    for (const bad of [{ ...ask, unit: "unknown" }, { ...ask, count: -1 }, { ...ask, first_at: "yesterday" }]) {
      expect(() => cardUnitAsks({ id: "taf-example", name: "Example" }, [bad], [], "UTC", now)).toThrow("could not be read");
    }
  });
});
