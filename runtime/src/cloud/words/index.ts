/**
 * tafs cloud's words in the eight languages, and Brazilian Portuguese over Portuguese (docs/languages.md): English
 * is the source, and every other language has every key, so a line can't ship untranslated (the type says so, and a
 * test pins it). pt-BR is pt's words with Brazil's own on top (pt-BR.ts).
 * The language is the app's own, sent as `Accept-Language`; anything else reads English. A language only
 * changes words: never what a Card allows, nor how an amount or a date is formatted beyond the locale.
 */
import { de } from "./de.js";
import { type ConnectRoute, en, type When, type Words } from "./en.js";
import { es } from "./es.js";
import { fr } from "./fr.js";
import { it } from "./it.js";
import { ko } from "./ko.js";
import { ja } from "./ja.js";
import { nl } from "./nl.js";
import { pl } from "./pl.js";
import { pt } from "./pt.js";
import { ptBR } from "./pt-BR.js";

export type { ConnectRoute, When, Words };

export const LANGUAGES = ["en", "de", "fr", "es", "it", "pt", "nl", "pl", "pt-BR", "ko", "ja"] as const;
export type Lang = (typeof LANGUAGES)[number];

const CATALOGS: Record<Lang, Words> = { en, de, fr, es, it, pt, nl, pl, "pt-BR": ptBR, ko, ja };

/** A regional variant's base language ("pt" for "pt-BR"), for what's kept per base language only (a recipe's words, kiff.dev's pages). */
export function baseLanguage(lang: Lang): Exclude<Lang, "pt-BR"> {
  return lang === "pt-BR" ? "pt" : lang;
}

/** The words of a language. */
export function wordsIn(lang: Lang): Words {
  return CATALOGS[lang];
}

/**
 * The best of the languages for an `Accept-Language` header: the highest `q` whose tag is one of them as it is
 * (`pt-BR`), else whose base language is (`de-AT` reads `de`, `pt-PT` reads `pt`), in the order given among equals;
 * English when none is, or there's no header.
 */
export function languageFrom(header: string | undefined): Lang {
  if (!header) return "en";
  let best: { lang: Lang; q: number; at: number } | undefined;
  header.split(",").slice(0, 20).forEach((part, at) => {
    const [tag, ...params] = part.trim().split(";");
    const [base = "", region = ""] = (tag ?? "").trim().replace(/_/g, "-").split("-");
    const regional = `${base.toLowerCase()}-${region.toUpperCase()}`;
    const lang = (LANGUAGES as readonly string[]).includes(regional) ? regional : base.toLowerCase();
    if (!(LANGUAGES as readonly string[]).includes(lang)) return;
    const qp = params.map((p) => p.trim()).find((p) => p.startsWith("q="));
    const q = qp ? Number(qp.slice(2)) : 1;
    if (!Number.isFinite(q) || q <= 0 || q > 1) return;
    if (!best || q > best.q) best = { lang: lang as Lang, q, at };
  });
  return best?.lang ?? "en";
}

/**
 * The plural form of n in a language, for words that count: Intl's categories (Polish has few and many). Our `pt` is
 * European, where 0 is plural ("0 tafs"); CLDR's plain `pt` is Brazilian, where 0 is singular, as in our `pt-BR`.
 */
export function plural(lang: Lang, n: number): Intl.LDMLPluralRule {
  return new Intl.PluralRules(lang === "pt" ? "pt-PT" : lang).select(n);
}

/**
 * How often, in the person's language: a whole number of hours as hours ("Every 4 hours", "Every hour"), anything
 * else in minutes ("Every 15 minutes"). Never "Every 240 minutes".
 */
export function everyWords(lang: Lang, minutes: number): string {
  const w = wordsIn(lang);
  return minutes >= 60 && minutes % 60 === 0 ? w.dial_every_hours(minutes / 60, plural(lang, minutes / 60)) : w.dial_every_minutes(minutes);
}

/** A phrase that opens a line, as it reads inside one ("Every 4 hours" → "every 4 hours"). */
export function midLine(phrase: string): string {
  return phrase.charAt(0).toLocaleLowerCase() + phrase.slice(1);
}

/** Each language's name in English, for the lines Coach and the tafs read (they're written in English, as their instructions). */
export const LANGUAGE_NAMES: Record<Lang, string> = {
  en: "English", de: "German", fr: "French", es: "Spanish", it: "Italian", pt: "European Portuguese", nl: "Dutch", pl: "Polish",
  "pt-BR": "Brazilian Portuguese", ko: "Korean", ja: "Japanese",
};

/** The line tafs cloud opens a Coach message with for a person whose app is in another language than English. */
export const LANGUAGE_LINE = "[tafs language]";

/** How a reply's language is chosen, said to Coach and to every taf (docs/languages.md, PM 18 Nov 23:35). */
function replyRule(lang: Lang): string {
  const name = LANGUAGE_NAMES[lang];
  return `The person writing reads ${name} (${lang}), from their app. Answer them in the language of their message; if that isn't clear (a short line, a number, a name), in ${name}. Never take the language from what you read or work on (a sheet, an email, a page, an account's locale).`;
}

/**
 * A Coach message with the person's language said, English included: Coach answers in the language of their
 * message, else in their app's, never in a data source's (14 Nov an English app's plan came out in French; 18 Nov
 * a French Google account turned a taf's English chat French). What the app shows them is in the app's language.
 * The line goes before the owner's words, after any of tafs' own opening lines, so what reads a message's
 * opening (entryOf, ownerWords) reads it as before. `before` is kept for callers; every message says it now.
 */
export function withLanguage(text: string, lang: Lang, _before: Lang = "en"): string {
  return withLine(text, `${LANGUAGE_LINE} ${replyRule(lang)} Write what the app shows them (questions, cards, a version's note, a taf's summary) in ${LANGUAGE_NAMES[lang]}. taf, Coach and Card stay as names. A message a taf sends a customer keeps the customer's language.`);
}

/** A person's message to a taf with the same rule: its chat answer follows their message, a customer keeps theirs. */
export function withReplyLanguage(text: string, lang: Lang): string {
  return withLine(text, `${LANGUAGE_LINE} ${replyRule(lang)} A message to a customer keeps the customer's language. taf, Coach and Card stay as names.`);
}

/** The line tafs cloud opens a Coach message with for an account outside UTC. */
export const ZONE_LINE = "[tafs time zone]";

/**
 * A Coach message with the account's time zone said, when it isn't UTC: kiff/agents' date line is in UTC, and
 * a taf's cron and times are in the account's zone, so Coach writes the hour the owner said, never one
 * converted to UTC (a 09:00 drafted as "0 8 * * 1"). Placed as the language line is.
 */
export function withZone(text: string, zone: string | undefined, now: Date): string {
  if (!zone || zone === "UTC") return text;
  let local: string;
  try {
    local = new Intl.DateTimeFormat("en-GB", { timeZone: zone, weekday: "long", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(now);
  } catch {
    return text;
  }
  return withLine(text, `${ZONE_LINE} The account's time zone is ${zone}: it's ${local} there now. Every time you write or say (a cron, an at, a time in a message) is in it, as the owner says it: never convert it to UTC.`);
}

/** A person's text without any line that starts as one of tafs' own (the language and time-zone lines), which only tafs cloud writes. */
export function withoutTafsLines(text: string): string {
  const own = [LANGUAGE_LINE, ZONE_LINE].map((l) => l.toLowerCase());
  // Any case, any leading space, anywhere in the message: "[TAFS Language]" reads the same to a model.
  return text.split("\n").filter((l) => !own.some((o) => l.trimStart().toLowerCase().startsWith(o))).join("\n");
}

/** A line of tafs' own before the owner's words, after any opening lines, so what reads a message's opening reads it as before. */
function withLine(text: string, line: string): string {
  // A door's knock quotes the message after the owner's words: the line goes last, out of the quote.
  if (text.startsWith("[door knock ")) return `${text}\n\n${line}`;
  const at = text.lastIndexOf("\nThe owner says: ");
  if (text.startsWith("[") && at >= 0) return `${text.slice(0, at)}\n${line}${text.slice(at)}`;
  return `${line}\nThe owner says: ${text}`;
}

/** What a taf's instructions say of its owner's language, when it isn't English: its reports and alerts to them go in it. */
export function ownerLanguageLine(lang: Lang): string | undefined {
  if (lang === "en") return undefined;
  return `Your owner reads ${LANGUAGE_NAMES[lang]} (${lang}): write what's for them in it (your reports, summaries and alerts; a chat answer follows the language of their message). A message to anyone else, such as a customer, keeps that person's language. Names stay as they are: taf, Coach, Card.`;
}

/** The owner's language as ownerLanguageLine wrote it into instructions, or undefined. */
export function ownerLanguageIn(text: string): Lang | undefined {
  const m = /Your owner reads [A-Za-z ]+ \(([a-z]{2}(?:-[A-Z]{2})?)\):/.exec(text);
  return m && (LANGUAGES as readonly string[]).includes(m[1]!) ? (m[1] as Lang) : undefined;
}

/**
 * A line that ends its sentence on a date whose short month already ends in a period ("14 nov.", "1 de dez." in fr and
 * pt): one period, never two ("depuis le 14 nov..").
 */
export function endSentence(line: string): string {
  return line.replace(/\.\.$/, ".");
}
