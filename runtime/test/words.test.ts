import { describe, expect, it } from "vitest";
import { LANGUAGES, languageFrom, plural, wordsIn } from "../src/cloud/words/index.js";
import { en } from "../src/cloud/words/en.js";
import { requestOf, summaryOf, timeIn } from "../src/cloud/work.js";
import type { Run } from "../src/cloud/agents.js";

const NOW = new Date("2026-11-09T12:00:00Z");
const run = (o: Partial<Run>): Run => ({ id: "r1", agent: "a", session: "s", trigger: "owner", status: "COMPLETED", cost_micro_usd: 0, turns: 1, created: "2026-11-09T10:00:00Z", updated: "2026-11-09T11:00:00Z", ...o });

/** Each word filled in: a string, or a function's answer for sample values. */
function sample(v: unknown): string[] {
  if (typeof v === "string") return [v];
  if (typeof v !== "function") return [];
  const f = v as (...a: unknown[]) => string;
  // Every catalog function takes strings, numbers and a plural form: try both kinds for each.
  return [f("X", "Y"), f(1, 3), f(2, "few"), f(5, "many"), f(1, "one")];
}

describe("tafs cloud's words in eight languages (docs/languages.md)", () => {
  it("has every key English has in every language, each a non-empty line, and no other key", () => {
    for (const lang of LANGUAGES) {
      const words = wordsIn(lang) as Record<string, unknown>;
      expect(Object.keys(words).sort(), lang).toEqual(Object.keys(en).sort());
      for (const [key, v] of Object.entries(words)) {
        expect(typeof v, `${lang}.${key}`).toBe(typeof (en as Record<string, unknown>)[key]);
        for (const line of sample(v)) expect(line.trim().length, `${lang}.${key}`).toBeGreaterThan(0);
      }
    }
  });

  it("isn't English in another language: a line copied over untranslated would read the same", () => {
    for (const lang of LANGUAGES.filter((l) => l !== "en")) {
      const words = wordsIn(lang) as Record<string, unknown>;
      const same = Object.keys(en).filter((k) => JSON.stringify(sample(words[k])) === JSON.stringify(sample((en as Record<string, unknown>)[k])));
      expect(same, lang).toEqual([]);
    }
  });

  it("picks the app's language from Accept-Language: the highest q among the eight, by base language, else English", () => {
    expect(languageFrom(undefined)).toBe("en");
    expect(languageFrom("")).toBe("en");
    expect(languageFrom("de")).toBe("de");
    expect(languageFrom("de-AT,de;q=0.9,en;q=0.5")).toBe("de");
    expect(languageFrom("pt-BR")).toBe("pt");
    expect(languageFrom("ja,fr;q=0.8,en;q=0.5")).toBe("fr");
    expect(languageFrom("en;q=0.5,nl;q=0.9")).toBe("nl");
    expect(languageFrom("pl;q=0,es;q=0.1")).toBe("es");
    expect(languageFrom("ja,zh")).toBe("en");
    expect(languageFrom("it;q=abc,de;q=2")).toBe("en");
    expect(languageFrom("PL-pl")).toBe("pl");
  });

  it("counts in each language's own plural forms", () => {
    expect([1, 2, 5, 22].map((n) => wordsIn("pl").work_needs_you_count(n, plural("pl", n)))).toEqual([
      "1 prośba czeka na Ciebie", "2 prośby czekają na Ciebie", "5 próśb czeka na Ciebie", "22 prośby czekają na Ciebie"]);
    expect(wordsIn("de").work_needs_you_count(1, plural("de", 1))).toBe("1 Anfrage braucht dich");
    expect(wordsIn("de").work_needs_you_count(3, plural("de", 3))).toBe("3 Anfragen brauchen dich");
  });

  it("writes a taf's work lines in the person's language, with the weekday in it and the time as in all eight", () => {
    expect(requestOf(run({ status: "RUNNING" }), "UTC", NOW, [], undefined, [], "fr").line).toBe("En cours");
    expect(requestOf(run({ status: "COMPLETED" }), "UTC", NOW, [], undefined, [], "es").line).toBe("Hecho a las 11:00");
    expect(requestOf(run({ status: "ERROR", error: "x", code: "ai_unavailable" }), "UTC", NOW, [], undefined, [], "it")).toMatchObject({ code: "ai_unavailable", line: wordsIn("it").code_ai_unavailable });
    // The weekday as the language writes it (its exact form is the runtime's locale data's).
    const fri = (lang: string) => new Intl.DateTimeFormat(lang, { timeZone: "UTC", weekday: "short" }).format(new Date("2026-11-06T09:00:00Z"));
    expect(summaryOf([run({ status: "COMPLETED", updated: "2026-11-06T09:00:00Z" })], false, "UTC", NOW, "de").line).toBe(`Zuletzt erledigt um ${fri("de")} 09:00`);
    expect(timeIn("2026-11-06T09:00:00Z", "UTC", NOW, "pl")).toBe(`${fri("pl")} 09:00`);
    expect(fri("pl")).not.toBe(fri("en-GB"));
    // English as before, by default.
    expect(summaryOf([], { unscheduled: true }, "UTC", NOW).line).toBe("Works from its chat");
  });
});
