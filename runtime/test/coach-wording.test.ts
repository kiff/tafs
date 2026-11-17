import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { COACH_WORDING } from "../src/cloud/coach-wording.js";
import { setupDefinition } from "../src/cloud/setupdef.js";
import { definition } from "../src/cloud/tafdef.js";
import { jobById } from "../src/jobs.js";

/** An RE2 pattern as JavaScript reads it in u mode: RE2's one-letter classes \pL and \pN are \p{L} and \p{N}. */
const js = (p: string) => p.replace(/\\p([LN])/g, "\\p{$1}");

// Eval 43975057022: Coach wrote "read-only" of a Google Sheets connection in 1 run of 3. kiff/agents' show refuses such a
// card by these rules before anyone sees it; the Eval's plan gate reads them beside its own list.
describe("Coach's wording rules", () => {
  it("are the contract's, whole, and Setup's definition sends them", () => {
    const contract = JSON.parse(readFileSync(new URL("../../contracts/coach-wording.json", import.meta.url), "utf8"));
    expect(COACH_WORDING).toEqual(contract);
    expect(setupDefinition("coach-x").wording).toEqual(contract);
    expect(setupDefinition("coach-x").show).toBe(true);
    // A taf's own definition carries none: its cards aren't Coach's.
    expect(definition("refunds-x", jobById("refunds")).wording).toBeUndefined();
  });

  it("are within kiff/agents' limits, each pattern a whole RE2 expression with no capturing group but the next word's", () => {
    expect(COACH_WORDING.length).toBeGreaterThan(0);
    expect(COACH_WORDING.length).toBeLessThanOrEqual(32);
    for (const w of COACH_WORDING) {
      expect(w.patterns.length).toBeGreaterThan(0);
      expect(w.patterns.length).toBeLessThanOrEqual(32);
      expect(w.fix.trim().length).toBeGreaterThan(0);
      expect([...w.fix].length).toBeLessThanOrEqual(500);
      for (const p of w.patterns) {
        expect(p.length).toBeLessThanOrEqual(500);
        expect(() => new RegExp(js(p), "iu")).not.toThrow();
        // Only the "next word" group captures, and only where unless_next reads it.
        const capturing = (p.match(/\((?!\?)/g) ?? []).length;
        expect(capturing, p).toBeLessThanOrEqual(w.unless_next ? 1 : 0);
      }
    }
  });

  it("catch the Eval's words and let the job's own through", () => {
    const edge = "(?:^|[^\\p{L}\\p{N}])";
    const claims = (text: string) => COACH_WORDING.some((w) => w.patterns.some((p) => {
      for (const m of text.matchAll(new RegExp(`${edge}(?:${js(p)})(?=$|[^\\p{L}\\p{N}])`, "giu"))) {
        const next = (m[1] ?? "").trim().toLowerCase();
        if (next && (w.unless_next ?? []).includes(next)) continue;
        return true;
      }
      return false;
    }));
    for (const bad of [
      "I'm connecting Google Sheets with read-only access to cells.", "Je connecterai ensuite Google Sheets en lecture seule.", "It won't change anything.",
      // Eval 44005248914, Gmail EN: a sign-in's breadth said in Coach's own words. The card shows it, from the grant.
      "Google's sign-in asks for full mail access, but the taf will only be able to read.",
      "Google fragt nach vollem E-Mail-Zugriff.", "Google pide acceso completo a tu correo.", "Google demande un accès complet à votre messagerie.",
      "Google chiede l'accesso completo alla posta.", "Google vraagt om volledige toegang tot je mail.", "Google poprosi o pełny dostęp do poczty.",
      "O Google pede acesso total ao seu correio.",
      // A sign-in breadth claim with the app's name between "full" and "access", and the curly apostrophe Coach writes in French and Italian.
      "Google will ask you for full access to Gmail.", "The sign-in shows full Gmail access, but the taf only reads.",
      "Google chiede l’accesso completo.", "Google demandera l’accès complet à votre messagerie.",
    ]) {
      expect(claims(bad), bad).toBe(true);
    }
    for (const good of ["It will read only the rows added since its last run.", "If you decline, nothing changes.", "The taf only gets the tools to read.", "Full name and email are on the card.", "The taf reads your full inbox history once."]) {
      expect(claims(good), good).toBe(false);
    }
  });
});
