/**
 * tafs cloud's words in every language the apps ship (docs/languages.md, "tafs cloud's words"): English is the source.
 * The full languages (FULL_LANGUAGES) have every key, so none of their lines ships untranslated (the type says so, and a
 * test pins it). pt-BR is pt's words with Brazil's own on top (pt-BR.ts). The languages added since (PARTIAL_LANGUAGES)
 * may be partial: each line written replaces English's, every other reads English, the most-seen lines go first
 * (PRIORITY_PREFIXES), and a test prints each one's coverage until it reaches 100%.
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
import { bg } from "./bg.js";
import { cs } from "./cs.js";
import { et } from "./et.js";
import { hr } from "./hr.js";
import { hu } from "./hu.js";
import { id } from "./id.js";
import { lt } from "./lt.js";
import { lv } from "./lv.js";
import { ro } from "./ro.js";
import { sk } from "./sk.js";
import { sl } from "./sl.js";
import { th } from "./th.js";
import { tr } from "./tr.js";
import { vi } from "./vi.js";
import { zhHant } from "./zh-Hant.js";

export type { ConnectRoute, When, Words };

/**
 * The languages whose every line is written: the eight, pt-BR, ko, ja, and since 22 Nov batch 2's ten and batch 3's five,
 * which were partial (tafs#1702) until every line was written (tafs#1706).
 */
export const FULL_LANGUAGES = ["en", "de", "fr", "es", "it", "pt", "nl", "pl", "pt-BR", "ko", "ja",
  "et", "lt", "lv", "cs", "sk", "sl", "hu", "ro", "bg", "hr", "tr", "id", "vi", "th", "zh-Hant"] as const;
/**
 * The languages the apps ship whose lines are being written: what isn't yet reads English. None today; a
 * language tafs cloud adds before every line is written (batch 4's) comes here first, and moves to FULL_LANGUAGES at 100%.
 */
export const PARTIAL_LANGUAGES = [] as const;
export const LANGUAGES = [...FULL_LANGUAGES, ...PARTIAL_LANGUAGES] as const;
export type Lang = (typeof LANGUAGES)[number];
type PartialLang = (typeof PARTIAL_LANGUAGES)[number];

/** The lines a partial language writes first, by key prefix: Home's, push titles, Activity's details, the approval card's. */
export const PRIORITY_PREFIXES = ["home_", "push_", "act_", "approval_"] as const;

const CATALOGS: Record<(typeof FULL_LANGUAGES)[number], Words> = { en, de, fr, es, it, pt, nl, pl, "pt-BR": ptBR, ko, ja,
  et, lt, lv, cs, sk, sl, hu, ro, bg, hr, tr, id, vi, th, "zh-Hant": zhHant };
const PARTIAL: Record<PartialLang, Partial<Words>> = {};
const filled = new Map<PartialLang, Words>();

function isPartial(lang: Lang): lang is PartialLang {
  return (PARTIAL_LANGUAGES as readonly string[]).includes(lang);
}

/** The share of English's lines a language has written, 0 to 1: 1 for a full language. */
export function coverage(lang: Lang): number {
  if (!isPartial(lang)) return 1;
  const all = Object.keys(en);
  const own = PARTIAL[lang] as Record<string, unknown>;
  return all.filter((k) => own[k] !== undefined).length / all.length;
}

/** A partial language's own lines, as written (the coverage test reads them). */
export function writtenIn(lang: Lang): Partial<Words> {
  return isPartial(lang) ? PARTIAL[lang] : CATALOGS[lang];
}

/** A regional variant's base language ("pt" for "pt-BR"), for what's kept per base language only (a recipe's words, kiff.dev's pages). */
export function baseLanguage(lang: Lang): Exclude<Lang, "pt-BR"> {
  return lang === "pt-BR" ? "pt" : lang;
}

/** The words of a language: a partial one's own lines over English's. */
export function wordsIn(lang: Lang): Words {
  if (!isPartial(lang)) return CATALOGS[lang];
  let words = filled.get(lang);
  if (!words) {
    words = { ...en, ...Object.fromEntries(Object.entries(PARTIAL[lang]).filter(([, v]) => v !== undefined)) } as Words;
    filled.set(lang, words);
  }
  return words;
}

/**
 * The path of kiff.dev's pages in a language ("" for English): Traditional Chinese's are under `/zh`, every other
 * language's under its base language, pt-BR's under `/pt`.
 */
export function pagePath(lang: Lang): string {
  if (lang === "en") return "";
  return lang === "zh-Hant" ? "zh" : baseLanguage(lang);
}

/** Chinese where it's written in Traditional characters, when a tag gives its region and no script. */
const HANT_REGIONS = new Set(["TW", "HK", "MO"]);
/** Codes Java and Android once used for a language: an Android 11 to 13 phone may still send "in" for Indonesian. */
const OLD_CODES: Record<string, string> = { in: "id", iw: "he", ji: "yi" };

/**
 * The best of the languages for an `Accept-Language` header: the highest `q` whose tag is one of them as it is
 * (`pt-BR`, `zh-Hant`), else whose base language is (`de-AT` reads `de`, `pt-PT` reads `pt`), in the order given among
 * equals; English when none is, or there's no header. Chinese is Traditional by its script or by its region (`zh-TW`,
 * `zh-HK`, `zh-MO`); Simplified Chinese isn't one of them. An old code reads as the new one (`in` is `id`).
 */
export function languageFrom(header: string | undefined): Lang {
  if (!header) return "en";
  let best: { lang: Lang; q: number; at: number } | undefined;
  header.split(",").slice(0, 20).forEach((part, at) => {
    const [tag, ...params] = part.trim().split(";");
    const parts = (tag ?? "").trim().replace(/_/g, "-").split("-");
    const base = OLD_CODES[(parts[0] ?? "").toLowerCase()] ?? (parts[0] ?? "").toLowerCase();
    const script = parts.slice(1).find((p) => /^[A-Za-z]{4}$/.test(p));
    const region = (parts.slice(1).find((p) => /^([A-Za-z]{2}|\d{3})$/.test(p)) ?? "").toUpperCase();
    const hant = base === "zh" && (script?.toLowerCase() === "hant" || (!script && HANT_REGIONS.has(region)));
    const regional = hant ? "zh-Hant" : `${base}-${region}`;
    const lang = (LANGUAGES as readonly string[]).includes(regional) ? regional : base;
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
  et: "Estonian", lt: "Lithuanian", lv: "Latvian", cs: "Czech", sk: "Slovak", sl: "Slovenian", hu: "Hungarian", ro: "Romanian",
  bg: "Bulgarian", hr: "Croatian", tr: "Turkish", id: "Indonesian", vi: "Vietnamese", th: "Thai", "zh-Hant": "Traditional Chinese",
};

/** The line tafs cloud opens a Coach message with for a person whose app is in another language than English. */
export const LANGUAGE_LINE = "[tafs language]";

/** How a reply's language is chosen, said to Coach and to every taf (docs/languages.md). */
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

/**
 * How a reply's language is chosen for a request from the Chrome extension: no language is named for the person. The extension
 * sends its browser's own, which says nothing about them (a French Chrome, an English owner: "Wiki lookup" came back in French).
 */
function extensionReplyRule(): string {
  return "Answer in the language of the person's message; if that isn't clear (a short line, a number, a name), in the language of this conversation so far, else in English. Never take the language from what you read or work on (a sheet, an email, a page, an account's locale).";
}

/**
 * A person's message to a taf with the same rule: its chat answer follows their message, a customer keeps theirs. From the
 * extension (`fromExtension`) the fallback is the conversation's language, never the browser's.
 */
export function withReplyLanguage(text: string, lang: Lang, fromExtension = false): string {
  return withLine(text, `${LANGUAGE_LINE} ${fromExtension ? extensionReplyRule() : replyRule(lang)} A message to a customer keeps the customer's language. taf, Coach and Card stay as names.`);
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

/** The line tafs cloud puts in a Coach message that carried the page the person had open (docs/chat.md, "Coach reads the page you're on"). */
export const PAGE_LINE = "[tafs page read]";

/**
 * A Coach message that carried a page, with the page's host said in tafs cloud's envelope. kiff/agents keeps the message,
 * never the page beside it, so without this line a later turn sees Coach's answers about a page with nothing behind them, and
 * Coach said it had never read one. The host alone: it's
 * what page consent "1" keeps for the chat's life (consent.ts); the address's path, the title and the text are kept nowhere.
 */
export function withPageRead(text: string, host: string): string {
  return withLine(text, `${PAGE_LINE} ${host} This message came with the page the owner had open on ${host}, and you read it in this turn. Its text isn't kept after this turn: later, say you read it then, and if you need what it said, ask for the page again. Never say you didn't read it.`);
}

/** A person's text without any line that starts as one of tafs' own (the language, time-zone and page lines), which only tafs cloud writes. */
export function withoutTafsLines(text: string): string {
  const own = [LANGUAGE_LINE, ZONE_LINE, PAGE_LINE].map((l) => l.toLowerCase());
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
