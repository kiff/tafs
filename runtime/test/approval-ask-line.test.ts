import { describe, expect, it } from "vitest";
import { approvalAskLine } from "../src/cloud/handler.js";

// Eval 44162956815's night: five taps on the owner's two approval cards were answered 200, and the log said nothing of
// what tafs cloud decided. One line per tap says it: the card, its grant, whether it carried a Card draft, the outcome.
describe("the line an Approve tap leaves", () => {
  it("names the step tafs cloud asks the phone to sign, beside whether the card carried a Card draft", () => {
    const line = JSON.parse(approvalAskLine("gmail-tools", "approval", { grant: "grt_1", draft: "drf_1", shown_draft: "3" },
      { outcome: "sign", ask: { step: "tools_and_cards" } }));
    expect(line).toEqual({ event: "approval_ask", component: "gmail-tools", kind: "approval", grant: "grt_1", card_draft: true,
      shown_draft: "3", outcome: "sign", code: null, step: "tools_and_cards" });
  });

  it("names an answer's code, and a card with no draft", () => {
    const line = JSON.parse(approvalAskLine("moneybird-tools", "approval", { grant: "grt_2" },
      { outcome: "answered", code: "other_app_first" }));
    expect(line).toMatchObject({ card_draft: false, shown_draft: null, outcome: "answered", code: "other_app_first", step: null });
  });

  it("keeps ids and codes only: no words, no keys, no message to sign", () => {
    const text = approvalAskLine("c", "sign_in", { grant: "grt_3" },
      { outcome: "read_again", code: "out_of_date", note: "What you're asked to approve changed", ask: { step: "cards", message: "tafs.cards.v1|t|d|1", key: "k" } } as never);
    expect(text).not.toContain("approve changed");
    expect(text).not.toContain("tafs.cards.v1");
    expect(Object.keys(JSON.parse(text)).sort()).toEqual(["card_draft", "code", "component", "event", "grant", "kind", "outcome", "shown_draft", "step"]);
  });
});
