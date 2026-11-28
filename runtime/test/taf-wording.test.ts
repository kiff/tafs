import { afterEach, describe, expect, it } from "vitest";
import { definition } from "../src/cloud/tafdef.js";
import { NOTE_CLAIM_FIX, noteClaimsOn, TAF_WORDING } from "../src/cloud/taf-wording.js";
import { JOBS } from "../src/jobs.js";

/** A pattern as kiff/agents' show compiles it (RE2, whole words, without case), in JS: \pL is \p{L}. */
function claims(text: string): string | undefined {
  for (const w of TAF_WORDING) {
    for (const p of w.patterns) {
      const re = new RegExp(`(?:^|[^\\p{L}\\p{N}])(?:${p.replaceAll("\\pL", "\\p{L}")})(?:$|[^\\p{L}\\p{N}])`, "iu");
      if (re.test(text)) return w.instead;
    }
  }
  return undefined;
}

// A taf said "I've also noted the tab name for future runs" with no memory write: its next run had nothing.
describe("a taf's note claims", () => {
  afterEach(() => { delete process.env.TAFS_NOTE_CLAIMS; });

  it("matches a note claim and its paraphrases in each of Coach's 8 languages, with that language's own words in its place", () => {
    const said: Record<string, string> = {
      "I've also noted the tab name for future runs.": "I didn't save that.",
      "I'll remember that the tab is Feuille 1.": "I didn't save that.",
      "I saved it to my notes.": "I didn't save that.",
      "J'ai bien noté le nom de l'onglet.": "Je ne l'ai pas enregistré.",
      "C'est noté pour la suite.": "Je ne l'ai pas enregistré.",
      "Ich habe mir den Tab-Namen gemerkt.": "Das habe ich nicht gespeichert.",
      "Ist notiert.": "Das habe ich nicht gespeichert.",
      "Lo he anotado para las próximas ejecuciones.": "No lo he guardado.",
      "Queda anotado.": "No lo he guardado.",
      "Ho preso nota del foglio.": "Non l'ho salvato.",
      "Me lo ricorderò.": "Non l'ho salvato.",
      "Já anotei o nome do separador.": "Não guardei isso.",
      "Vou lembrar disso.": "Não guardei isso.",
      "Ik heb de tabnaam genoteerd.": "Dat heb ik niet opgeslagen.",
      "Ik zal het onthouden.": "Dat heb ik niet opgeslagen.",
      "Zanotowałam nazwę karty.": "Tego nie zapisano.",
      "Zapisałem to w notatkach.": "Tego nie zapisano.",
    };
    for (const [text, instead] of Object.entries(said)) expect(claims(text) ?? `none: ${text}`, text).toMatch(new RegExp(`^${instead.replace(/[.']/g, "\\$&")}`));
  });

  it("leaves what a taf did with its tools and what it read alone", () => {
    for (const text of [
      "I saved the receipt to Drive and logged it in the sheet.",
      "According to my notes, you prefer PDF.",
      "Dans mes notes, vous préférez le PDF.",
      "Ho segnato la fattura come pagata.",
      "Registrei a linha na folha.",
      "Zapisałem plik na Dysku.",
      "Ich habe gemerkt, dass die Rechnung fehlt.",
      "I'll check again next time.",
      "Your Card lets me file receipts.",
    ]) expect(claims(text), text).toBeUndefined();
  });

  it("is backed by a memory write alone, and tells the agent where setup goes", () => {
    for (const w of TAF_WORDING) {
      expect(w.unless_done).toEqual(["memory:write"]);
      expect([...w.instead].length).toBeLessThanOrEqual(500);
    }
    expect(NOTE_CLAIM_FIX).toContain("add it to the setup");
    expect([...NOTE_CLAIM_FIX].length).toBeLessThanOrEqual(500);
    expect(TAF_WORDING.length).toBe(8);
  });

  it("goes on every taf's definition only once kiff/agents serves it (TAFS_NOTE_CLAIMS=on)", () => {
    const job = JOBS[0]!;
    expect(noteClaimsOn()).toBe(false);
    expect(definition("t1", job).wording).toBeUndefined();
    process.env.TAFS_NOTE_CLAIMS = "on";
    expect(definition("t1", job).wording).toEqual(TAF_WORDING);
    expect(definition("t1", job).instructions).toContain("only after your memory write went through in this run");
  });
});
