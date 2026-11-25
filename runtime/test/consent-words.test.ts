import { describe, expect, it } from "vitest";
import { AI_CONSENT_VERSION } from "../src/cloud/consent.js";
import { LANGUAGES, wordsIn } from "../src/cloud/words/index.js";

// The AI consent names Claude's maker and points to the subprocessors page for who runs it, so moving between the
// providers listed there needs no new consent; its words hold on the least protective of them (at most 30 days).
describe("the AI consent's words", () => {
  it("is version 2, the words that name no single company running the model", () => {
    expect(AI_CONSENT_VERSION).toBe("2");
  });

  it("has the same seven lines in every language, the maker named and the 30-day bound kept", () => {
    const en = wordsIn("en").ai_consent_lines;
    expect(en).toHaveLength(7);
    for (const lang of LANGUAGES) {
      const lines = wordsIn(lang).ai_consent_lines;
      expect(lines, lang).toHaveLength(7);
      expect(lines[0], lang).toContain("Anthropic");
      expect(lines[1], lang).toContain("Anthropic");
      expect(lines[5], lang).toMatch(/30/);
      expect(lines[5], lang).not.toMatch(/^Anthropic/);
    }
  });
});
