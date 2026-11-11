import type { CreditRun, KIFFBalance } from "./credits.js";
import { type Lang, plural, wordsIn } from "./words/index.js";

/**
 * A taf's likely monthly credits, said before the owner approves it (docs/api.md, "A taf's estimate"):
 * one line, from real records only, never a figure of anyone's own. Every figure is credits as KIFF counts
 * them: tafs cloud works out none itself. The sources, best first, and the answer names the one it used:
 *
 *   own    the taf's own counted runs over the last 30 days: credits per run, and runs a month at that
 *          pace once 7 days of history cover it;
 *   trial  a new version's trial runs, which KIFF doesn't count, with the credits KIFF says each would have
 *          used: credits per run only;
 *   tests  the same job's runs in our Evals (tafs cloud's store, under ESTIMATES_KEY, by recipe, in credits):
 *          credits per run only;
 *   none   nothing to go on, said as such.
 *
 * Quiet firings cost nothing (a schedule reads its work list without the model), so a run here is a
 * counted run that did work.
 */

const DAY_MS = 86_400_000;
/** How far back the taf's own runs are read. */
export const WINDOW_DAYS = 30;
/** How much history a pace needs. */
export const PACE_DAYS = 7;
/** Company's price past the plan's credits, a credit, in euro cents. */
export const COMPANY_CENTS_PER_CREDIT = 1;

/** One counted run that did work: when it ended, and its credits. */
export interface Sample { at: string; credits: number }

/** The taf's own runs: the counted ones that did work, and from when the record covers. */
export interface Own { runs: Sample[]; from: string }

/** Our Evals' figures for a recipe's job (the store's ESTIMATES_KEY). */
export interface Tests { p50: number; p90: number; runs: number; from: string }

/** The account's plan, as far as the estimate says it. */
export type Plan = Pick<KIFFBalance, "kind" | "included" | "limit_cents">;

export interface Range { low: number; high: number }

export interface EstimateInput {
  own?: Own;
  /** A new version's trial runs, in credits each, as KIFF says each would have used. */
  trial?: number[];
  tests?: Tests;
  plan?: Plan;
  now: Date;
}

export type Source = "own" | "trial" | "tests" | "none";

export interface Estimate {
  source: Source;
  credits?: Range;
  runs_a_month?: Range;
  per_run?: Range;
  words: string;
}

/**
 * Where our Evals' figures are kept: one item in tafs cloud's own store (the table CoachStore reads), a map
 * of recipe id to Tests, in credits. It's written from outside this repo, from the private Eval reports, by
 * whoever runs the Eval, and read here at runtime. No figure is in this repo: missing, there are none.
 */
export const ESTIMATES_KEY = "estimates#v1";

/** The kept store, as far as our Evals' figures are read from it. */
export interface EstimatesStore { get(key: string): Promise<unknown> }

/** A recipe's figures in the kept table, when they're whole: anything else is none. */
export function testsIn(table: unknown, recipe: string | undefined): Tests | undefined {
  if (!recipe || !table || typeof table !== "object" || Array.isArray(table) || !Object.hasOwn(table, recipe)) return undefined;
  const t = (table as Record<string, Partial<Tests> | null>)[recipe];
  if (!t || typeof t !== "object" || !(Number(t.p50) > 0) || !(Number(t.p90) > 0) || !(Number(t.runs) > 0)) return undefined;
  return { p50: Number(t.p50), p90: Math.max(Number(t.p50), Number(t.p90)), runs: Number(t.runs), from: String(t.from ?? "") };
}

/**
 * The figures our Evals measured for a recipe, read from the store at ESTIMATES_KEY. No store, no table, or a
 * read that fails, is none: the line falls to the honest one, never a figure from elsewhere.
 */
export async function testsFor(recipe: string | undefined, store: EstimatesStore | undefined): Promise<Tests | undefined> {
  if (!recipe || !store) return undefined;
  try {
    return testsIn(await store.get(ESTIMATES_KEY), recipe);
  } catch (err) {
    console.warn(`taf estimate: our tests' figures unreadable: ${String((err as Error)?.message ?? err).slice(0, 200)}`);
    return undefined;
  }
}

/** The p-th percentile of a list, by nearest rank. */
function percentile(xs: number[], p: number): number {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.max(0, Math.ceil(p * s.length) - 1)]!;
}

/** The pace of the taf's own runs: runs and credits a month, once PACE_DAYS cover it and it ran at all. */
export function paceOf(own: Own | undefined, now: Date): { runs: number; credits: number; days: number } | undefined {
  if (!own || !own.runs.length) return undefined;
  const from = Math.max(Date.parse(own.from), now.getTime() - WINDOW_DAYS * DAY_MS);
  const days = (now.getTime() - from) / DAY_MS;
  if (!(days >= PACE_DAYS)) return undefined;
  const runs = own.runs.filter((r) => Date.parse(r.at) >= from);
  if (!runs.length) return undefined;
  const sum = runs.reduce((n, r) => n + r.credits, 0);
  return { runs: (runs.length * 30) / days, credits: (sum * 30) / days, days };
}

/** Credits as a sentence says them: as they are below 10, to the nearest 10 below 1,000, else to the nearest 50. */
export function roundCredits(n: number): number {
  if (!(n > 0)) return 0;
  if (n < 10) return Math.max(1, Math.round(n));
  if (n < 1000) return Math.round(n / 10) * 10;
  return Math.round(n / 50) * 50;
}

const range = (low: number, high: number, round: (n: number) => number = roundCredits): Range => {
  const l = round(low), h = round(high);
  return { low: l, high: Math.max(l, h) };
};

/**
 * The estimate: its source, its figures and its one line, in the language. Any figure is from the input's
 * records; with none, the line says it can't tell yet.
 */
export function estimate(input: EstimateInput, lang: Lang = "en"): Estimate {
  const own = input.own?.runs.filter((r) => r.credits > 0) ?? [];
  const trial = (input.trial ?? []).filter((c) => c > 0);
  // The best source for the cost of a run: the taf's own, then a trial's, then our tests'.
  let source: Source = "none";
  let cost: Range | undefined;
  if (own.length) {
    source = "own";
    cost = { low: percentile(own.map((r) => r.credits), 0.5), high: percentile(own.map((r) => r.credits), 0.9) };
  } else if (trial.length) {
    source = "trial";
    cost = { low: percentile(trial, 0.5), high: percentile(trial, 0.9) };
  } else if (input.tests) {
    source = "tests";
    cost = { low: input.tests.p50, high: input.tests.p90 };
  }
  // Said rounded; a month is worked out from the figures as they are, and rounded once.
  const perRun = cost ? range(cost.low, cost.high) : undefined;
  // Runs a month only ever come from the taf's own runs.
  const pace = paceOf(input.own && { ...input.own, runs: own }, input.now);
  const runsAMonth = pace ? Math.max(1, Math.round(pace.runs)) : undefined;
  const credits = pace && cost ? range(runsAMonth! * cost.low, runsAMonth! * cost.high) : undefined;
  return {
    source,
    ...(credits ? { credits } : {}),
    ...(runsAMonth ? { runs_a_month: { low: runsAMonth, high: runsAMonth } } : {}),
    ...(perRun ? { per_run: perRun } : {}),
    words: wordsOf(source, credits, perRun, input.plan, lang),
  };
}

/** The locale numbers and amounts are written in: English as tafs cloud writes it (en-GB). */
const locale = (lang: Lang) => (lang === "en" ? "en-GB" : lang);
const num = (n: number, lang: Lang) => new Intl.NumberFormat(locale(lang)).format(n);
const eur = (cents: number, lang: Lang, digits = 0) =>
  new Intl.NumberFormat(locale(lang), { style: "currency", currency: "EUR", minimumFractionDigits: digits, maximumFractionDigits: digits }).format(cents / 100);

function wordsOf(source: Source, credits: Range | undefined, perRun: Range | undefined, plan: Plan | undefined, lang: Lang): string {
  const w = wordsIn(lang);
  const span = (r: Range, f: (n: number) => string = (n) => num(n, lang)) => (r.low === r.high ? f(r.low) : w.est_span(f(r.low), f(r.high)));
  const lines: string[] = [];
  if (credits) {
    const form = plural(lang, credits.high);
    const included = plan && (plan.kind === "solo" || plan.kind === "company") && plan.included > 0 ? plan.included : 0;
    if (included && credits.high <= included) {
      lines.push(w.est_month(span(credits), form), w.est_fits(num(included, lang)));
    } else if (included && plan!.kind === "solo") {
      // What the plan covers first; past it, packs. No price for a pack is known here, so no euros are said.
      lines.push(w.est_month(span(credits), form), w.est_solo_over(num(included, lang), overPhrase(credits, included, (n) => num(n, lang), lang)));
    } else if (included && plan!.kind === "company") {
      const over = overRange(credits, included);
      const cents = { low: over.low * COMPANY_CENTS_PER_CREDIT, high: over.high * COMPANY_CENTS_PER_CREDIT };
      const euros = range(cents.low, cents.high, (c) => (c > 0 ? Math.max(100, Math.round(c / 100) * 100) : 0));
      const limit = plan!.limit_cents > 0
        ? (cents.high <= plan!.limit_cents ? w.est_within(eur(plan!.limit_cents, lang)) : w.est_past(eur(plan!.limit_cents, lang)))
        : "";
      lines.push(w.est_company(span(credits), overPhrase(credits, included, (n) => num(n, lang), lang),
        overPhrase(euros, 0, (c) => eur(c, lang), lang), eur(COMPANY_CENTS_PER_CREDIT, lang, 2), limit, form));
    } else {
      lines.push(w.est_month(span(credits), form));
    }
  } else {
    if (perRun) {
      lines.push(w.est_pace_unknown);
      const s = span(perRun), form = plural(lang, perRun.high);
      lines.push(source === "own" ? w.est_per_run_own(s, form) : source === "trial" ? w.est_per_run_trial(s, form) : w.est_per_run_tests(s, form));
    } else {
      lines.push(w.est_unknown);
    }
    lines.push(w.est_first_week);
  }
  // The last line, by plan: Solo waits when its credits run out, Company at its spend limit. A plan with no
  // such bound (an agreement), or one not known, has none.
  if (plan?.kind === "solo") lines.push(w.est_never_past_solo);
  else if (plan?.kind === "company") lines.push(w.est_never_past_company);
  return lines.join(" ").replace(/\s+/g, " ").replace(/ ,/g, ",").replace(/, \./g, ".").trim();
}

/** What's past what the plan includes, rounded as credits are. */
function overRange(r: Range, included: number): Range {
  return { low: Math.max(0, r.low - included), high: Math.max(0, r.high - included) };
}

/** "about 700 to 1,300", or "up to 1,300" when the low end is inside the plan. */
function overPhrase(r: Range, included: number, f: (n: number) => string, lang: Lang): string {
  const w = wordsIn(lang);
  const o = overRange(r, included);
  if (o.low <= 0) return w.est_up_to(f(o.high));
  return w.est_about(o.low === o.high ? f(o.low) : w.est_span(f(o.low), f(o.high)));
}

/** The taf's own counted runs that did work, from KIFF's records: counted, with credits, never a trial's. */
export function samplesFromKIFF(runs: CreditRun[]): Sample[] {
  return runs.filter((r) => r.counted === true && r.credits > 0 && r.free !== "trial" && r.trigger !== "trial" && typeof r.ended === "string")
    .map((r) => ({ at: r.ended, credits: Math.floor(r.credits) }));
}

/**
 * A new version's trial runs, from KIFF's records: KIFF counts none of them, and gives in `credits` what each
 * would have used. A trial with none given says nothing; any other free run (one that failed) is left out.
 * KIFF's record doesn't name the version a trial ran, and tafs cloud keeps no time for a version, so every
 * trial run the record holds (its last 30 days) counts, as every trial run kiff/agents listed did before.
 */
export function trialsFromKIFF(runs: CreditRun[]): number[] {
  return runs.filter((r) => r.free === "trial" && r.counted !== true && r.credits > 0).map((r) => r.credits);
}

/** "At this pace, about 640 this month", for a taf's row in Plan & usage, once its own runs make a pace. */
export function paceLine(own: Own | undefined, now: Date, lang: Lang = "en"): string | undefined {
  const pace = paceOf(own && { ...own, runs: own.runs.filter((r) => r.credits > 0) }, now);
  if (!pace) return undefined;
  const n = roundCredits(pace.credits);
  return n > 0 ? wordsIn(lang).est_pace_line(num(n, lang), plural(lang, n)) : undefined;
}

/** What a taf's first week used, as kept on the taf for its note: credits, and a month at that pace. */
export interface WeekOne { at: string; credits: number; month: number }

/** The week-one note's figures from the runs of its first seven days. */
export function weekOneOf(runs: Sample[], at: Date): WeekOne {
  const credits = runs.reduce((n, r) => n + r.credits, 0);
  return { at: at.toISOString(), credits, month: roundCredits((credits * 30) / PACE_DAYS) };
}

/** The week-one note, in the language. */
export function weekOneWords(name: string, w1: WeekOne, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  if (w1.credits <= 0) return w.est_week_one_none(name);
  return w.est_week_one(name, num(w1.credits, lang), plural(lang, w1.credits), num(w1.month, lang));
}

/** The account's plan, as a balance read gives it, to keep for Coach's tool, which reads no session of its own. */
export function planOf(b: Pick<KIFFBalance, "kind" | "included" | "limit_cents">): Plan {
  return { kind: b.kind, included: Math.max(0, Math.floor(b.included || 0)), limit_cents: Math.max(0, Math.floor(b.limit_cents || 0)) };
}
