/**
 * tafs cloud's words in the eight languages (docs/languages.md): English is the source, and every other
 * language has every key, so a line can't ship untranslated (the type says so, and a test pins it).
 * The language is the app's own, sent as `Accept-Language`; anything else reads English. A language only
 * changes words: never what a Card allows, nor how an amount or a date is formatted beyond the locale.
 */
import { de } from "./de.js";
import { en, type Words } from "./en.js";
import { es } from "./es.js";
import { fr } from "./fr.js";
import { it } from "./it.js";
import { nl } from "./nl.js";
import { pl } from "./pl.js";
import { pt } from "./pt.js";

export type { Words };

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
