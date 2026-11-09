/**
 * tafs cloud's words in the eight languages (docs/languages.md): English is the source, and every other
 * language has every key, so a line can't ship untranslated (the type says so, and a test pins it).
 * The language is the app's own, sent as `Accept-Language`; anything else reads English. A language only
 * changes words: never what a Card allows, nor how an amount or a date is formatted beyond the locale.
 */
import { de } from "./de.js";
import { en, type When, type Words } from "./en.js";
import { es } from "./es.js";
import { fr } from "./fr.js";
import { it } from "./it.js";
import { nl } from "./nl.js";
import { pl } from "./pl.js";
import { pt } from "./pt.js";

export type { When, Words };

export const LANGUAGES = ["en", "de", "fr", "es", "it", "pt", "nl", "pl"] as const;
export type Lang = (typeof LANGUAGES)[number];

const CATALOGS: Record<Lang, Words> = { en, de, fr, es, it, pt, nl, pl };

/** The words of a language. */
export function wordsIn(lang: Lang): Words {
  return CATALOGS[lang];
}

/**
 * The best of the eight languages for an `Accept-Language` header: the highest `q` whose base language is one
 * of them (`de-AT` reads `de`), in the order given among equals; English when none is, or there's no header.
 */
export function languageFrom(header: string | undefined): Lang {
  if (!header) return "en";
  let best: { lang: Lang; q: number; at: number } | undefined;
  header.split(",").slice(0, 20).forEach((part, at) => {
    const [tag, ...params] = part.trim().split(";");
    const base = (tag ?? "").trim().toLowerCase().split("-")[0] ?? "";
    if (!(LANGUAGES as readonly string[]).includes(base)) return;
    const qp = params.map((p) => p.trim()).find((p) => p.startsWith("q="));
    const q = qp ? Number(qp.slice(2)) : 1;
    if (!Number.isFinite(q) || q <= 0 || q > 1) return;
    if (!best || q > best.q) best = { lang: base as Lang, q, at };
  });
  return best?.lang ?? "en";
}

/** The plural form of n in a language, for words that count: Intl's categories (Polish has few and many). */
export function plural(lang: Lang, n: number): Intl.LDMLPluralRule {
  return new Intl.PluralRules(lang).select(n);
}

/** Each language's name in English, for the lines Coach and the tafs read (they're written in English, as their instructions). */
export const LANGUAGE_NAMES: Record<Lang, string> = {
  en: "English", de: "German", fr: "French", es: "Spanish", it: "Italian", pt: "European Portuguese", nl: "Dutch", pl: "Polish",
};

/** The line tafs cloud opens a Coach message with for a person whose app is in another language than English. */
export const LANGUAGE_LINE = "[tafs language]";

/**
 * A Coach message with the person's language said, when it isn't English: Coach replies in it, and words
 * what they read in it. The line goes before the owner's words, after any of tafs' own opening lines, so
 * what reads a message's opening (entryOf, ownerWords) reads it as before.
 */
export function withLanguage(text: string, lang: Lang): string {
  if (lang === "en") return text;
  const line = `${LANGUAGE_LINE} The person writing reads ${LANGUAGE_NAMES[lang]} (${lang}), from their app: answer them in it, and write what they read in it (questions, cards, a version's note, a taf's summary). taf, Coach and Card stay as names. A message a taf sends a customer keeps the customer's language.`;
  // A door's knock quotes the message after the owner's words: the line goes last, out of the quote.
  if (text.startsWith("[door knock ")) return `${text}\n\n${line}`;
  const at = text.lastIndexOf("\nThe owner says: ");
  if (text.startsWith("[") && at >= 0) return `${text.slice(0, at)}\n${line}${text.slice(at)}`;
  return `${line}\nThe owner says: ${text}`;
}

/** What a taf's instructions say of its owner's language, when it isn't English: its reports and alerts to them go in it. */
export function ownerLanguageLine(lang: Lang): string | undefined {
  if (lang === "en") return undefined;
  return `Your owner reads ${LANGUAGE_NAMES[lang]} (${lang}): write what's for them in it (your reports, summaries and alerts, and your chat answers when they write in it). A message to anyone else, such as a customer, keeps that person's language. Names stay as they are: taf, Coach, Card.`;
}

/** The owner's language as ownerLanguageLine wrote it into instructions, or undefined. */
export function ownerLanguageIn(text: string): Lang | undefined {
  const m = /Your owner reads [A-Za-z ]+ \(([a-z]{2})\):/.exec(text);
  return m && (LANGUAGES as readonly string[]).includes(m[1]!) ? (m[1] as Lang) : undefined;
}
