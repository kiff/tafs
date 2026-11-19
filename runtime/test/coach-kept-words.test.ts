import { describe, expect, it } from "vitest";
import { COACH_KEPT_WORDS } from "../src/cloud/coach-kept-words.js";
import { setupDefinition } from "../src/cloud/setupdef.js";

// kiff/agents' show writes these after each line of Coach's rules notice, by where it's kept, and refuses a notice in a language
// without them: so all 8 of Coach's languages carry all three, each a short ending on one line (show's CheckKeptWords: at most 80).
describe("Coach's rules-notice endings", () => {
  it("has all three endings in each of Coach's 8 languages", () => {
    expect(Object.keys(COACH_KEPT_WORDS).sort()).toEqual(["de", "en", "es", "fr", "it", "nl", "pl", "pt"]);
    for (const [lang, words] of Object.entries(COACH_KEPT_WORDS)) {
      expect(Object.keys(words).sort(), lang).toEqual(["connection", "instructions", "kiff"]);
      for (const w of Object.values(words)) {
        expect(w, lang).toMatch(/^\(.+\)$/u);
        expect([...w].length, `${lang}: ${w}`).toBeLessThanOrEqual(80);
        expect(w, lang).not.toMatch(/[\n\r]/u);
      }
    }
    // legal-i18n's English, as show writes it.
    expect(COACH_KEPT_WORDS.en).toEqual({ connection: "(the connection keeps it)", kiff: "(KIFF adds it)", instructions: "(in its instructions)" });
  });

  // Eval 44128040350: Setup's notices with lines failed "signs what was agreed" in 3 of 3 runs, so until the Upgrade
  // Setup's definition carries none, show offers no lines, and Coach writes the notice's text (draft-the-card@39).
  it("stays out of Setup's definition while the rules notice is text only", () => {
    const def = setupDefinition("setup-1") as unknown as { kept_words?: unknown };
    expect(def.kept_words).toBeUndefined();
  });
});
