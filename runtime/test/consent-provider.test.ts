import { describe, expect, it, vi } from "vitest";
import { inferenceProvider } from "../src/cloud/consent.js";
import { LANGUAGES, wordsIn } from "../src/cloud/words/index.js";

// Who runs Claude (docs/accounts.md, "Your data and the AI"): the consent names it, so a change of provider is a new
// consent. INFERENCE_PROVIDER picks it, never silently, and the words for each provider differ only in who runs the
// model and what that provider keeps (kiff/work#32, PM 13:18).
describe("the inference provider and its consent", () => {
  it("is anthropic when unset, bedrock when asked, and stops on anything else", () => {
    expect(inferenceProvider(undefined)).toBe("anthropic");
    expect(inferenceProvider("")).toBe("anthropic");
    expect(inferenceProvider(" bedrock ")).toBe("bedrock");
    expect(inferenceProvider("anthropic")).toBe("anthropic");
    expect(() => inferenceProvider("openai")).toThrow(/INFERENCE_PROVIDER/);
    expect(() => inferenceProvider("Bedrock")).toThrow(/INFERENCE_PROVIDER/);
  });

  it("has Bedrock's words in every language, changed only in who runs the model and what it keeps", () => {
    for (const lang of LANGUAGES) {
      const w = wordsIn(lang);
      const a = w.ai_consent_lines, b = w.ai_consent_lines_bedrock;
      expect(b.length, lang).toBe(a.length);
      expect([b[1], b[2], b[3], b[5]], lang).toEqual([a[1], a[2], a[3], a[5]]);
      expect(b[0], lang).toContain("Amazon Web Services");
      expect(b[4], lang).toContain("Amazon Web Services");
      expect(b[4], lang).not.toMatch(/30/);
    }
  });

  it("raises both consents' versions and serves Bedrock's words when INFERENCE_PROVIDER is bedrock", async () => {
    vi.resetModules();
    vi.stubEnv("INFERENCE_PROVIDER", "bedrock");
    try {
      const c = await import("../src/cloud/consent.js");
      expect([c.INFERENCE_PROVIDER, c.AI_CONSENT_VERSION, c.PAGE_CONSENT_VERSION]).toEqual(["bedrock", "2", "3"]);
      const view = c.aiConsentView(undefined, "en");
      expect(view.version).toBe("2");
      expect(view.words.lines).toEqual([...wordsIn("en").ai_consent_lines_bedrock]);
    } finally {
      vi.unstubAllEnvs();
      vi.resetModules();
    }
  });

  it("keeps today's versions and words when it's unset", async () => {
    vi.resetModules();
    const c = await import("../src/cloud/consent.js");
    expect([c.INFERENCE_PROVIDER, c.AI_CONSENT_VERSION, c.PAGE_CONSENT_VERSION]).toEqual(["anthropic", "1", "2"]);
    expect(c.aiConsentView(undefined, "en").words.lines).toEqual([...wordsIn("en").ai_consent_lines]);
  });
});
