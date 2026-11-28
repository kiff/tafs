import type { AgreedLimit } from "./rules.js";
import type { Lang } from "./words/index.js";

/**
 * The rules notice's limit lines, written by tafs cloud from the agreed figures themselves (kiff/tafs#1563), never from
 * Coach's prose: one line per figure, in the owner's language and number format, so the words the owner agrees to and the
 * figures a Card holds come from one source. Coach gives only the names a figure can't carry (the tool's `title`, a `sum`
 * figure's `currency`, a per-item figure's `per_label`); a figure missing what it needs gets no line, and the notice keeps
 * today's check (`rules_unsaid`). A language whose lines aren't written yet gets none either.
 */

/** What a line needs beyond its figure: the tool's name in the notice's words, and the names its figure can't carry. */
export interface LineNames {
  /** The tool as the notice's own line names it ("Replies to customers"). */
  title: string;
  /** A `sum(…)` figure's currency (ISO 4217), already checked against the connection's own by the caller. */
  currency?: string;
  /** A per-item figure's item, in the notice's words ("order"). */
  perLabel?: string;
}

/** A figure's window, as a line says it. */
type Window = "call" | "calendar_day" | "rolling_1h" | "rolling_24h" | "rolling_7d" | "lifetime";

interface LineWords {
  /** At most `amount` (a count or a sum, formatted) over the window, for each `per` when there is one. */
  upTo: (title: string, amount: string, window: Window, per?: string) => string;
  /** A limit of 0: every one past nothing, so every one asks, or none goes. */
  none: (title: string, over: "ask" | "refuse", per?: string) => string;
  /** What happens past the figure. */
  past: (over: "ask" | "refuse") => string;
}

const EN_WINDOW: Record<Window, string> = {
  call: "each time", calendar_day: "a day", rolling_1h: "an hour", rolling_24h: "in 24 hours", rolling_7d: "in 7 days", lifetime: "in all",
};

/** The lines' words by language: English first; the other languages come with their reviewed words. */
const WORDS: Partial<Record<Lang, LineWords>> = {
  en: {
    upTo: (title, amount, window, per) => `${title}: up to ${amount} ${EN_WINDOW[window]}${per ? ` for each ${per}` : ""}`,
    none: (title, over, per) => `${title}: ${over === "refuse" ? "never" : "every one asks you first"}${per ? ` for each ${per}` : ""}`,
    past: (over) => (over === "refuse" ? "past that, it's refused" : "past that, it asks you"),
  },
};

const locale = (lang: Lang) => (lang === "en" ? "en-GB" : lang);
const CURRENCY = /^[A-Z]{3}$/;

/**
 * One figure's line ("Refunds: up to €50.00 each time; past that, it asks you"), or undefined when it can't be said
 * exactly: a language without its words, no title, a `sum` with no valid currency, a per-item figure with no per label,
 * or a window a line doesn't name. `over` is the figure's own, else its rule's.
 */
export function limitLine(l: AgreedLimit, ruleOver: "ask" | "refuse", names: LineNames, lang: Lang): string | undefined {
  const w = WORDS[lang];
  // The line starts with the title, so its first letter is a capital, as the notice's own words may not be ("replies to customers").
  const named = names.title?.replace(/\s+/g, " ").trim();
  const title = named ? named.charAt(0).toLocaleUpperCase(locale(lang)) + named.slice(1) : "";
  if (!w || !title) return undefined;
  if (!(l.window in EN_WINDOW)) return undefined;
  const per = l.per !== undefined ? names.perLabel?.replace(/\s+/g, " ").trim() : undefined;
  if (l.per !== undefined && !per) return undefined;
  const over = l.over ?? ruleOver;
  if (l.limit === 0) return w.none(title, over, per);
  let amount: string;
  if (l.quantity === "count") {
    amount = new Intl.NumberFormat(locale(lang)).format(l.limit);
  } else {
    if (!names.currency || !CURRENCY.test(names.currency)) return undefined;
    let fmt: Intl.NumberFormat;
    try { fmt = new Intl.NumberFormat(locale(lang), { style: "currency", currency: names.currency }); } catch { return undefined; }
    // The Card's figure is in the currency's minor units, by its own decimals: 5000 in EUR is €50.00, in JPY ¥5,000.
    const digits = fmt.resolvedOptions().maximumFractionDigits ?? 2;
    amount = fmt.format(l.limit / 10 ** digits);
  }
  return `${w.upTo(title, amount, l.window as Window, per)}; ${w.past(over)}`;
}
