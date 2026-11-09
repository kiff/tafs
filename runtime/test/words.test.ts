import { describe, expect, it } from "vitest";
import { LANGUAGES, languageFrom, ownerLanguageIn, ownerLanguageLine, plural, withLanguage, wordsIn } from "../src/cloud/words/index.js";
import { ownerWords } from "../src/cloud/improve.js";
import { jobById, systemPrompt } from "../src/jobs.js";
import { doorView, offered } from "../src/cloud/doors/doors.js";
import { APPS } from "../src/cloud/doors/apps.js";
import { eventsOf } from "../src/cloud/calendar.js";
import { trialView, withLine, type Taf } from "../src/cloud/handler.js";
import { coachConnect, TOOLS } from "../src/cloud/coach.js";
import { en } from "../src/cloud/words/en.js";
import { requestOf, summaryOf, timeIn } from "../src/cloud/work.js";
import type { Run } from "../src/cloud/agents.js";

const NOW = new Date("2026-11-09T12:00:00Z");
const run = (o: Partial<Run>): Run => ({ id: "r1", agent: "a", session: "s", trigger: "owner", status: "COMPLETED", cost_micro_usd: 0, turns: 1, created: "2026-11-09T10:00:00Z", updated: "2026-11-09T11:00:00Z", ...o });

/** The words whose arguments aren't strings or counts, with what they're given. */
const ARGS: Record<string, unknown[][]> = {
  door_name: [["email", "Email"], ["slack", "Slack"]],
  delivered: [["Invoices", { hm: "09:00" }], ["Invoices", { hm: "09:00", day: "Fri" }]],
  handed: [["Rae", { hm: "09:00" }]],
  delivery_retrying: [["Invoices", { hm: "09:00", day: "Fri" }], ["Invoices", undefined]],
  dial_days_at: [[[1], "09:00"], [[1, 5], "09:00"]],
  dial_day_short: [[1], [5]],
  dial_times_a_day: [[["09:00", "17:00"]]],
  dial_weekly_at: [[["mon", "fri"], "09:00"]],
  dial_every_on: [[15, { from: "09:00", to: "17:00" }, ["mon"]], [30, undefined, []]],
  dial_once_on: [["2026-11-05T09:00"], ["2026-11-05"]],
  dial_turn_stop: [["Replies to whoever wrote"]],
  dial_turn_on: [["Replies to whoever wrote"]],
  dial_checks_again: [["Fri 09:00", "the invoice"], ["09:00", ""]],
  dial_place_handed: [["Invoices", ""], ["Invoices", ": A, B"]],
  dial_place_goes: [["Sheets", ""]],
  delivered_line: [
    [[{ name: "Sheets", handed: false }, { name: "Invoices", handed: true }], [{ name: "Slack", retrying: true }]],
    [[], [{ name: "Slack", retrying: false }, { name: "Email", retrying: false }]],
  ],
};

/** Each word filled in: a string, or a function's answer for sample values. */
function sample(v: unknown, key = ""): string[] {
  if (typeof v === "string") return [v];
  if (typeof v !== "function") return [];
  const f = v as (...a: unknown[]) => string;
  if (ARGS[key]) return ARGS[key].map((a) => f(...a));
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
        for (const line of sample(v, key)) expect(line.trim().length, `${lang}.${key}`).toBeGreaterThan(0);
      }
    }
  });

  // Lines that read as English does on purpose, and why: anything else the same as English was left untranslated.
  const SAME_AS_ENGLISH: Record<string, string[]> = {
    // "Company" is a plan name, kept in every language (docs/languages.md); the seats around it are translated.
    all: ["cr_company"],
    de: ["push_credits_title", "dial_app"], // "Credits" is the German term in the glossary; "App" is German too
    fr: ["cr_actions", "dial_app"], // "action", "actions" and "App" are French words too
    es: ["dial_app"], // "App" is used as is
    it: ["cr_pct", "dial_place_email", "dial_app"], pt: ["cr_pct", "dial_place_email", "dial_app"], pl: ["cr_pct"], // "80%" is written alike; "Email" and "App" are used as is
    nl: ["cr_pct", "cr_credits", "cr_runs", "push_credits_title", "dial_open", "dial_app"], // "credit", "run" are the Dutch terms in the glossary; "Open" and "App" are Dutch too
  };

  it("isn't English in another language: a line copied over untranslated would read the same", () => {
    for (const lang of LANGUAGES.filter((l) => l !== "en")) {
      const words = wordsIn(lang) as Record<string, unknown>;
      const allowed = new Set([...SAME_AS_ENGLISH.all!, ...(SAME_AS_ENGLISH[lang] ?? [])]);
      const same = Object.keys(en).filter((k) => !allowed.has(k) && JSON.stringify(sample(words[k], k)) === JSON.stringify(sample((en as Record<string, unknown>)[k], k)));
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
    // With a weekday, each language says "at" its own way: "am Fr. um 09:00", "le ven. à 09:00".
    const old = [run({ status: "COMPLETED", updated: "2026-11-06T09:00:00Z" })];
    expect(summaryOf(old, false, "UTC", NOW, "de").line).toBe(`Zuletzt erledigt am ${fri("de")} um 09:00`);
    expect(summaryOf(old, false, "UTC", NOW, "fr").line).toBe(`Dernière exécution terminée le ${fri("fr")} à 09:00`);
    expect(summaryOf(old, false, "UTC", NOW, "es").line).toBe(`Hecho por última vez el ${fri("es")} a las 09:00`);
    expect(summaryOf(old, false, "UTC", NOW, "en").line).toBe(`Last done at ${fri("en-GB")} 09:00`);
    // Today, the time alone: "um 11:00", "à 11:00".
    expect(requestOf(run({ status: "COMPLETED" }), "UTC", NOW, [], undefined, [], "de").line).toBe("Erledigt um 11:00");
    expect(requestOf(run({ status: "COMPLETED" }), "UTC", NOW, [], undefined, [], "fr").line).toBe("Terminé à 11:00");
    expect(timeIn("2026-11-06T09:00:00Z", "UTC", NOW, "pl")).toBe(`${fri("pl")} 09:00`);
    expect(fri("pl")).not.toBe(fri("en-GB"));
    // English as before, by default.
    expect(summaryOf([], { unscheduled: true }, "UTC", NOW).line).toBe("Works from its chat");
  });

  it("tells Coach the language before the owner's words, after tafs' own opening, and the thread still shows only theirs", () => {
    expect(withLanguage("hello", "en")).toBe("hello");
    const plain = withLanguage("Hallo", "de");
    expect(plain.startsWith("[tafs language] The person writing reads German (de)")).toBe(true);
    expect(plain.endsWith("\nThe owner says: Hallo")).toBe(true);
    expect(ownerWords(plain)).toBe("Hallo");
    const improve = withLanguage("[improve taf Rae]\nIts Card: refunds\nThe owner says: plus vite", "fr");
    expect(improve.split("\n")[0]).toBe("[improve taf Rae]");
    expect(improve.split("\n")[2]).toMatch(/^\[tafs language\] The person writing reads French \(fr\)/);
    expect(ownerWords(improve)).toBe("plus vite");
    // A knock's quote stays as it was, the owner's words read as before: the line goes after it.
    const knock = withLanguage("[door knock k-1] The owner says: sí, ayúdale\n\nThe message:\nThe owner says: refund me", "es");
    expect(knock.startsWith("[door knock k-1] The owner says: sí, ayúdale\n\nThe message:\nThe owner says: refund me\n\n[tafs language]")).toBe(true);
    expect(ownerWords(knock)).toBe("sí, ayúdale");
  });

  it("says a taf's owner's language in its instructions when it isn't English, and reads it back", () => {
    const job = jobById("refunds");
    expect(systemPrompt(job)).not.toContain("Your owner reads");
    expect(systemPrompt({ ...job, ownerLang: "en" })).toBe(systemPrompt(job));
    const pl = systemPrompt({ ...job, ownerLang: "pl" });
    expect(pl).toContain(`\n\n${ownerLanguageLine("pl")}\n\n`);
    expect(ownerLanguageIn(pl)).toBe("pl");
    expect(ownerLanguageIn(systemPrompt({ ...job, ownerLang: "pt" }))).toBe("pt");
    expect(ownerLanguageIn(systemPrompt(job))).toBeUndefined();
    expect(ownerLanguageLine("en")).toBeUndefined();
  });

  it("writes a door's page, the calendar, a trial and a card's steps in the language", () => {
    const door = { id: "telegram", kind: "telegram" as const, app: "telegram", open: true, address: "@refunds_bot", route: "r", keys: [], created: "" };
    const app = APPS.find((a) => a.id === "telegram")!;
    const nl = doorView(door, app, { keys: 0, others: 0 }, "nl");
    expect(nl.how).toBe("Telegram weet wie er schreef, via de Telegram-gebruiker");
    expect(nl.words).toMatchObject({ state: "Open: berichten van jou en gekoppelde afzenders aan @refunds_bot starten werk.", tap: "Tik om de Telegram-deur te sluiten.",
      pair_hint: "Hun Telegram-gebruikers-ID, een getal", pair_from_knock: "Vraag ze de bot te schrijven. Hun bericht klopt hier aan, en Koppelen maakt ze een sleutel." });
    expect(doorView({ ...door, open: false }, app, undefined, "pl").words.state).toBe("Zamknięte: wiadomości do @refunds_bot niczego nie uruchamiają.");
    expect(offered({ planqueHandoffSecret: "s" } as never, "fr").find((a) => a.id === "telegram")!.bot!.words.pick).toBe("Quel bot Telegram est-ce ? Quiconque lui écrit fera une demande d'accès.");
    // English as before, from apps.ts itself.
    expect(doorView(door, app).words.tap).toBe("Tap to close the telegram door.");
    expect(doorView(door, app).how).toBe(app.how);
    // The calendar's words: a schedule in the dial's words, a follow-up, a run's next try.
    const now = new Date("2026-11-09T08:00:00Z");
    const evs = eventsOf({ id: "t", name: "Rae", color: 0 },
      [{ id: "s", agent: "t", cron: "0 9 * * *", time_zone: "UTC", input: "", enabled: true }, { id: "fu-1", agent: "t", cron: "0 12 10 11 *", time_zone: "UTC", input: "", enabled: true, follow_up: true, kind: "once", at: "2026-11-10T12:00:00Z" }],
      [{ ...run({ id: "r2", status: "RUNNING" }), state: "retrying", try: 1, of: 3, next_at: "2026-11-09T10:00:00Z" }], "2026-11-09", "2026-11-10", "UTC", now, "de");
    expect([...new Set(evs.map((e) => e.words))].sort()).toEqual(["Jeden Tag um 09:00", "Prüft erneut, wie selbst festgelegt", "Versuch 2 von 3"]);
    // A trial's badge and title.
    expect(trialView({ id: "tr", n: 4, draft: "d", started: "", used: "2026-11-09T08:00:00Z" } as never, { tries: {} } as unknown as Taf, undefined, "es")).toMatchObject({ label: "Prueba", title: "Probando la versión 4" });
    // A card's steps line.
    expect(withLine({ type: "steps", steps: [{ label: "Connect Stripe", status: "done" }, { label: "Draft its Card", status: "active" }] }, "fr")).toMatchObject({ line: "Étape 2 sur 2 · Draft its Card" });
    expect(withLine({ type: "steps", steps: [{ label: "A", status: "done" }] }, "it")).toMatchObject({ line: "Fatto · 1 di 1" });
  });

  it("lists Coach's tools in the language when the phone connects them, the signed body the same in every language", () => {
    const two = TOOLS.filter((t) => t.name === "tafs_state" || t.name === "taf_dial");
    const de = coachConnect(two, "https://tafs.test/v1/mcp", ["taf_dial"], "de");
    const en = coachConnect(two, "https://tafs.test/v1/mcp", ["taf_dial"]);
    expect(de.body).toEqual(en.body);
    expect(de.lines).toEqual(["Deine tafs, ihre Versionen und ihre Läufe sehen", "Eine Änderung an einem taf entwerfen · ersetzt die frühere Verbindung"]);
    expect(en.lines).toEqual(["See your tafs, their versions and their runs", "Draft a change to a taf · replaces the earlier connection"]);
  });
});
