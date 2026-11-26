import { describe, expect, it } from "vitest";
import { EVERY_CALL_SPACED, EVERY_CALL_UNSPACED } from "../src/cloud/every-call-words.js";
import { cardsFromRules, saysEveryCallAsks, type AgreedRule } from "../src/cloud/rules.js";
import { EVERY_CALL_SAMPLES } from "./every-call-samples.js";

// Words that say every call of a money tool asks the owner, read in all 44 app languages: the eight rules.ts reads, and
// the other 36 (35, and pt-BR's own forms) in every-call-words.ts. A figure before the noun ("every refund over €5 asks
// you") is never every call.
describe("the words that say every call asks, in every app language", () => {
  it("has samples for each language it reads, and reads no language without them", () => {
    const read = [...Object.keys(EVERY_CALL_SPACED), ...Object.keys(EVERY_CALL_UNSPACED)];
    for (const lang of read) expect(EVERY_CALL_SAMPLES[lang], lang).toBeDefined();
    for (const lang of Object.keys(EVERY_CALL_SAMPLES)) expect(read, lang).toContain(lang);
  });

  for (const [lang, { yes, no }] of Object.entries(EVERY_CALL_SAMPLES)) {
    it(`${lang}: every call asking, and not with a figure or without "every"`, () => {
      for (const text of yes) expect(saysEveryCallAsks(text), text).toBe(true);
      for (const text of no) expect(saysEveryCallAsks(text), text).toBe(false);
    });
  }

  it("keeps the eight languages' figure cases as they were", () => {
    for (const text of [
      "Every refund over 50 EUR asks you first.", "Each refund past €5 waits for your approval.",
      "A refund over 50 EUR is refused. Every call is pinned to test mode.", "Listing and reading payments is open.",
      "Chaque remboursement de plus de 50 EUR attend votre accord.",
    ]) expect(saysEveryCallAsks(text), text).toBe(false);
    expect(saysEveryCallAsks("Coach caps refunds at €50 a day, and every refund asks you first.")).toBe(true);
  });

  // The case an inherited ask was once refused for (an Eval, 26 Nov): "every refund asks you first" in Hungarian over a €5
  // cap that takes the rule's ask. It is read now, so the rules are shown again for the count of 0 a day that asks.
  it("reads the Hungarian notice over an inherited €5 cap as every call, and gives no Cards for it", () => {
    const hu = "Minden visszatérítés előbb téged kérdez.";
    const inherited: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [
      { quantity: "sum(amount)", window: "call", limit: 500 }, { quantity: "sum(amount)", window: "calendar_day", limit: 5000, over: "refuse" }] }];
    const grant = { id: "grt_x", request: { tools: [{ name: "create_refund", title: "Refund a charge" }] } } as never;
    expect(saysEveryCallAsks(hu)).toBe(true);
    expect(cardsFromRules([grant], inherited, "refunds-x", hu)[0]).toMatchObject({ unbuilt: "every_call_unheld" });
    expect(saysEveryCallAsks("Minden 5 € feletti visszatérítés előbb téged kérdez.")).toBe(false);
  });
});
