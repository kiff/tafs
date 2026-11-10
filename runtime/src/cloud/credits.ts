import { type Fetch, KIFF_API, KIFFError } from "./kiff.js";
import { fromWords } from "./request.js";
import { type Lang, plural, wordsIn } from "./words/index.js";

/**
 * Credits in Plan & usage (docs/accounts.md, "Credits"): read-only, beside the tasks, until
 * credits replace them. KIFF keeps each run of a taf or of Coach that used AI, and works out its
 * credits once; tafs cloud reads that with the person's session, names the tafs, and writes every
 * line the apps show. The apps draw the words as they are and derive nothing but order.
 */

/** KIFF's period credits (GET /v1/me/usage/credits). */
export interface KIFFCredits {
  period: { start: string; end: string };
  current: boolean;
  credits: number;
  runs: number;
  by_agent: Array<{ agent: string; runtime_agent: string; name?: string; credits: number; runs: number }>;
  setup: { credits: number; runs: number };
  free: Record<string, number>;
  /** The balance now, whichever period is read; absent from a KIFF from before it. */
  balance?: KIFFBalance;
}

/** The account's balance as KIFF gives it, for the reader: its figures, and what they may do. */
export interface KIFFBalance {
  kind: "solo" | "company" | "unbounded" | "none";
  state: "ok" | "warning" | "waiting";
  why?: string;
  meter_pct: number | null;
  included: number;
  used: number;
  left: number | null;
  packs_left: number;
  over: number;
  beyond: number;
  beyond_cents: number;
  limit_cents: number;
  seats: number;
  period: { start: string; end: string };
  can_raise: boolean;
  raise_to_cents: number;
  /** `once`: the pack sold once per account (the First month pack) is still on offer; absent from a KIFF before it. */
  can_buy: { solo: boolean; packs: boolean; once?: boolean };
  billed_by: string;
}

/** One run in KIFF's list (GET /v1/me/usage/credits/runs). */
export interface KIFFRun {
  run: string;
  trigger: string;
  source?: string;
  requester_kind?: string;
  ended: string;
  counted: boolean;
  free?: string;
  credits: number;
  refused: number;
  declined: number;
  kept_until?: string;
}

/** A page of KIFF's runs, and where the next one goes on. */
export interface KIFFRuns {
  items: KIFFRun[];
  next?: string;
}

/** Credits couldn't be read: the words say so, for the apps to show as they are. */
export const UNREADABLE = "Credits can't be read right now. Try again in a moment.";

/** What the read was refused or failed with, for the route to answer. */
export class CreditsError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

async function read<T>(path: string, token: string, doFetch: Fetch, base: string): Promise<T> {
  let res: Response;
  try {
    res = await doFetch(`${base}${path}`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
  } catch {
    throw new CreditsError(503, UNREADABLE);
  }
  if (res.status === 401 || res.status === 403) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (!res.ok) throw new CreditsError(503, UNREADABLE);
  return (await res.json().catch(() => {
    throw new CreditsError(503, UNREADABLE);
  })) as T;
}

/** The period asked for, as KIFF takes it: "previous", or none for the current one. */
function periodQuery(previous: boolean): string {
  return previous ? "period=previous" : "";
}

/** KIFF's credits for the current period, or the one before. */
export async function readCredits(token: string, previous: boolean, doFetch: Fetch = fetch, base = KIFF_API): Promise<KIFFCredits> {
  const q = periodQuery(previous);
  const k = await read<Partial<KIFFCredits>>(`/v1/me/usage/credits${q ? `?${q}` : ""}`, token, doFetch, base);
  if (!k.period || typeof k.period.start !== "string" || typeof k.period.end !== "string") throw new CreditsError(503, UNREADABLE);
  return { period: k.period, current: k.current === true, credits: count(k.credits), runs: count(k.runs),
    by_agent: Array.isArray(k.by_agent) ? k.by_agent : [], setup: { credits: count(k.setup?.credits), runs: count(k.setup?.runs) },
    free: k.free && typeof k.free === "object" ? k.free : {}, ...(k.balance && typeof k.balance === "object" ? { balance: k.balance } : {}) };
}

/** The account's balance now, from KIFF's credits read; unreadable from a KIFF from before it. */
export async function readBalance(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<KIFFBalance> {
  return (await readBalanceAndRuns(token, doFetch, base)).balance;
}

/** The balance now, and how many runs this period counted, from the same read. */
export async function readBalanceAndRuns(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<{ balance: KIFFBalance; runs: number }> {
  const k = await readCredits(token, false, doFetch, base);
  const b = k.balance;
  if (!b || !["solo", "company", "unbounded", "none"].includes(b.kind) || !b.period || typeof b.period.end !== "string") {
    throw new CreditsError(503, UNREADABLE);
  }
  return { balance: b, runs: k.runs };
}

/** A page of one taf's runs from KIFF: the person's own, and everyone's on a taf they manage. */
export async function readCreditRuns(token: string, taf: string, previous: boolean, after: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<KIFFRuns> {
  const q = new URLSearchParams({ runtime_agent: taf });
  if (previous) q.set("period", "previous");
  if (after) q.set("cursor", after);
  const k = await read<Partial<KIFFRuns>>(`/v1/me/usage/credits/runs?${q}`, token, doFetch, base);
  return { items: Array.isArray(k.items) ? k.items : [], ...(typeof k.next === "string" && k.next ? { next: k.next } : {}) };
}

const count = (n: unknown) => (typeof n === "number" && Number.isFinite(n) && n > 0 ? Math.floor(n) : 0);
/** The locale a language formats numbers, amounts and dates in: English as before (en-GB). */
const locale = (lang: Lang) => (lang === "en" ? "en-GB" : lang);
const number = (n: number, lang: Lang = "en") => new Intl.NumberFormat(locale(lang)).format(n);
const credits = (n: number, lang: Lang = "en") => wordsIn(lang).cr_credits(number(n, lang), plural(lang, n));
const runs = (n: number, lang: Lang = "en") => wordsIn(lang).cr_runs(number(n, lang), plural(lang, n));

/** A date of KIFF's period, as KIFF bounds it (UTC): "1 Dec", "1. Dez.", "1 déc.". */
function day(at: Date, lang: Lang = "en"): string {
  return new Intl.DateTimeFormat(locale(lang), { timeZone: "UTC", day: "numeric", month: "short" }).format(at);
}

/**
 * The period's line, with its first and last days as KIFF bounds the period (UTC): the start's
 * date, and the day before the end's date, never the instant before the end in the account's
 * zone, which east of UTC is already the next day. "This period: 1,240 credits used · ends on
 * 30 Nov", or "Last period: 980 credits used · 1 Oct to 31 Oct".
 */
export function periodWords(k: Pick<KIFFCredits, "period" | "current" | "credits">, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  const start = new Date(k.period.start), end = new Date(k.period.end);
  const last = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth(), end.getUTCDate() - 1));
  const used = k.credits > 0 ? w.cr_used(credits(k.credits, lang)) : k.current ? w.cr_none_yet : w.cr_none;
  if (k.current) return w.cr_this_period(used, day(last, lang));
  return w.cr_last_period(used, day(start, lang), day(last, lang));
}

/** Why runs weren't counted, in words, in the order they're shown. */
const WHY: ReadonlyArray<{ kiff: string; why: string; words: "cr_why_coach" | "cr_why_trial" | "cr_why_failed" }> = [
  { kiff: "setup", why: "coach", words: "cr_why_coach" },
  { kiff: "trial", why: "trial", words: "cr_why_trial" },
  { kiff: "failed", why: "failed", words: "cr_why_failed" },
];

/** The note under the panel. */
export const NOTE = "Each run counts once, when it ends, in whole credits.";

/** A taf as the read names it: its id, its name, its KIFF agent, and whether this person may open it. */
export interface CreditsTaf {
  id: string;
  name: string;
  kiffAgent: string;
  opens: boolean;
}

export interface CreditsView {
  period: { start: string; end: string; current: boolean };
  credits: number;
  runs: number;
  words: string;
  /** Each row; `pace` is its line at this pace ("At this pace, about 640 this month"), once its own runs make one, this period only. */
  tafs: Array<{ taf: string | null; credits: number; runs: number; words: string; pace?: string }>;
  coach: { credits: number; runs: number; words: string } | null;
  not_counted: { runs: number; words: string; lines: Array<{ why: string; runs: number; words: string }> };
  note: string;
}

/**
 * The period, in words: each taf that used credits, most first, as the person may see it; the
 * rest (tafs removed since, and those the person doesn't use or oversee) on one row that doesn't
 * open; Coach's row past its fair use; and what wasn't counted, and why.
 */
export function creditsView(k: KIFFCredits, tafs: CreditsTaf[], lang: Lang = "en"): CreditsView {
  const w = wordsIn(lang);
  const rows: CreditsView["tafs"] = [];
  let rest = { credits: 0, runs: 0, unseen: false };
  for (const a of k.by_agent) {
    const t = tafs.find((x) => x.id === a.runtime_agent) ?? tafs.find((x) => x.kiffAgent === a.agent);
    if (t?.opens) {
      rows.push({ taf: t.id, credits: count(a.credits), runs: count(a.runs), words: `${t.name} · ${credits(count(a.credits), lang)} · ${runs(count(a.runs), lang)}` });
      continue;
    }
    rest = { credits: rest.credits + count(a.credits), runs: rest.runs + count(a.runs), unseen: rest.unseen || t !== undefined };
  }
  rows.sort((a, b) => b.credits - a.credits);
  if (rest.runs > 0) {
    const name = rest.unseen ? w.cr_other_tafs : w.cr_removed_tafs;
    rows.push({ taf: null, credits: rest.credits, runs: rest.runs, words: `${name} · ${credits(rest.credits, lang)} · ${runs(rest.runs, lang)}` });
  }
  const coach = k.setup.runs > 0 ? { credits: k.setup.credits, runs: k.setup.runs, words: `${w.cr_coach_past} · ${credits(k.setup.credits, lang)} · ${runs(k.setup.runs, lang)}` } : null;
  const lines = WHY.filter((y) => count(k.free[y.kiff]) > 0).map((y) => ({ why: y.why, runs: count(k.free[y.kiff]), words: `${w[y.words]} · ${runs(count(k.free[y.kiff]), lang)}` }));
  const free = lines.reduce((n, l) => n + l.runs, 0);
  return {
    period: { start: k.period.start, end: k.period.end, current: k.current },
    credits: k.credits, runs: k.runs, words: periodWords(k, lang), tafs: rows, coach,
    not_counted: { runs: free, words: free > 0 ? w.cr_not_counted(runs(free, lang)) : w.cr_not_counted_none, lines },
    note: w.cr_note,
  };
}

export interface TafCreditsView {
  period: { start: string; end: string; current: boolean };
  taf: string;
  credits: number;
  runs: number;
  words: string;
  items: Array<{ run: string; at: string; credits: number; counted: boolean; why: string | null; words: string; opens: boolean }>;
  next?: string;
}

/** What a run's refused and declined calls add to its line: " · 1 action refused by KIFF". */
function blocked(refused: number, declined: number, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  const r = count(refused), d = count(declined);
  const action = (n: number) => w.cr_actions(number(n, lang), plural(lang, n));
  if (r > 0 && d > 0) return ` · ${w.cr_refused_declined(action(r), number(d, lang))}`;
  if (r > 0) return ` · ${w.cr_refused(action(r))}`;
  if (d > 0) return ` · ${w.cr_declined(action(d))}`;
  return "";
}

/** One run, in words: where it came from and what it used, or why it wasn't counted. */
export function runWords(r: KIFFRun, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  if (r.free === "trial" || r.trigger === "trial") return w.cr_trial_run;
  const from = fromWords(r.trigger, r.source, r.requester_kind, lang);
  if (!r.counted) return `${from} · ${r.free === "failed" ? w.cr_failed_not_counted : w.cr_not_counted_run}`;
  return `${from} · ${credits(count(r.credits), lang)}${blocked(r.refused, r.declined, lang)}`;
}

/** Whether a run's row opens its request: while kiff/agents keeps the run, and never a trial's. */
export function opens(r: KIFFRun, now: Date): boolean {
  if (r.free === "trial" || r.trigger === "trial") return false;
  if (!r.kept_until) return true;
  const kept = Date.parse(r.kept_until);
  return Number.isNaN(kept) || kept > now.getTime();
}

/** One taf's runs in the period, in words, with its line from the period's credits. */
export function tafCreditsView(k: KIFFCredits, page: KIFFRuns, taf: CreditsTaf, now: Date, lang: Lang = "en"): TafCreditsView {
  const w = wordsIn(lang);
  const a = k.by_agent.find((x) => x.runtime_agent === taf.id) ?? k.by_agent.find((x) => x.agent === taf.kiffAgent);
  const c = count(a?.credits), n = count(a?.runs);
  return {
    period: { start: k.period.start, end: k.period.end, current: k.current },
    taf: taf.id, credits: c, runs: n,
    words: `${taf.name} · ${credits(c, lang)} · ${runs(n, lang)} ${k.current ? w.cr_this_period_short : w.cr_last_period_short}`,
    items: page.items.map((r) => ({ run: r.run, at: r.ended, credits: r.counted ? count(r.credits) : 0, counted: r.counted === true,
      why: r.counted ? null : r.free === "setup" ? "coach" : (r.free ?? null), words: runWords(r, lang), opens: opens(r, now) })),
    ...(page.next ? { next: page.next } : {}),
  };
}

/** Euro cents as the apps show them: "€12", or "€12.34". */
function euros(cents: number, lang: Lang = "en"): string {
  const whole = cents % 100 === 0;
  return new Intl.NumberFormat(locale(lang), { style: "currency", currency: "EUR", minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 })
    .format(cents / 100);
}

/** The day the period starts again: its end's date, as KIFF bounds it (UTC). "1 Dec". */
function startsAgain(b: KIFFBalance, lang: Lang = "en"): string {
  return day(new Date(b.period.end), lang);
}

const seats = (n: number, lang: Lang = "en") => wordsIn(lang).cr_seats(number(n, lang), plural(lang, n));

/** The balance read (GET /v1/usage/balance): the balance now, in words. */
export interface BalanceView {
  balance: {
    state: KIFFBalance["state"];
    why: string | null;
    meter_pct: number | null;
    words: string;
    detail: string | null;
    warning: string | null;
    raise: { words: string; from_cents: number } | null;
    /**
     * What the app may offer this person in the store. `first_month` is the First month pack, offered
     * only while Solo runs, among the packs: `{ready: true, words: null}`. `null`: not offered, as
     * before a plan, where a pack can't be bought.
     */
    buy: { solo: boolean; packs: boolean; first_month: { ready: boolean; words: string | null } | null } | null;
    /**
     * Whether Plan & usage shows the Credits panel: false only with no plan and no run this period or
     * the last, the two periods the panel shows, as for an account just made.
     */
    credits_panel: boolean;
  };
  plan_line: string;
  plan_tone: KIFFBalance["state"];
  home_line: string | null;
}

/**
 * What waiting means, and what lets the tafs go on, in words for this reader: on Solo a pack, on
 * Company a raise for whoever may make one (an admin of the account otherwise, by role, never by
 * email), else the period starting again. The words a refused chat or Run now shows too.
 */
export function waitingWords(b: Pick<KIFFBalance, "kind" | "why" | "can_raise" | "raise_to_cents" | "can_buy" | "period">, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  const day = startsAgain(b as KIFFBalance, lang);
  const again = w.cr_w_again(day);
  switch (b.why ?? (b.kind === "none" ? "no_plan" : b.kind === "company" ? "limit_reached" : "credits_used_up")) {
    case "no_plan":
      return w.cr_w_no_plan;
    case "limit_reached":
      if (b.can_raise && b.raise_to_cents > 0) return w.cr_w_limit_raise(again);
      if (b.can_raise) return w.cr_w_limit_ceiling(again);
      return w.cr_w_limit_admin;
    default:
      if (b.can_buy?.packs) return w.cr_w_used_pack(again);
      return w.cr_w_used_wait(day);
  }
}

/**
 * The balance, in tafs cloud's words (docs/accounts.md, "The balance read"): its line and second
 * line, the bar, the warning from 80% and while waiting, the raise for whoever may make it, what may
 * be bought, and the plan's lines for Settings and Home. The apps draw them as they are. `ranLately`:
 * a run counted this period or the last; with no plan and none, the Credits panel has nothing to show.
 */
export function balanceView(b: KIFFBalance, lang: Lang = "en", ranLately = true): BalanceView {
  const w = wordsIn(lang);
  const c = (n: number) => credits(n, lang);
  const eur = (cents: number) => euros(cents, lang);
  const pct = typeof b.meter_pct === "number" ? Math.max(0, Math.min(100, Math.floor(b.meter_pct))) : null;
  const p = w.cr_pct(pct ?? 0);
  const waiting = b.state === "waiting";
  const raise = b.kind === "company" && b.can_raise && b.raise_to_cents > 0
    ? { words: w.cr_raise_to(eur(b.raise_to_cents)), from_cents: b.limit_cents } : null;
  // The First month pack is bought once Solo runs, like any pack, and shown only then, among the
  // packs: before a plan, a lone pack that can't be bought only reads as an upsell.
  const firstMonth = b.can_buy?.once === true && b.kind === "solo" && b.can_buy.packs === true ? { ready: true, words: null } : null;
  const buy = b.can_buy && (b.can_buy.solo || b.can_buy.packs)
    ? { solo: b.can_buy.solo === true, packs: b.can_buy.packs === true, first_month: firstMonth } : null;
  let words: string, detail: string | null = null, warning: string | null = null, plan: string, home: string | null = null;
  switch (b.kind) {
    case "solo": {
      const left = count(b.left);
      words = waiting ? w.cr_solo_used_up : w.cr_solo_left(c(left));
      const parts: string[] = [];
      if (count(b.packs_left) > 0) parts.push(w.cr_packs_part(c(count(b.packs_left))));
      if (count(b.over) > 0) parts.push(w.cr_over_part(c(count(b.over))));
      detail = parts.length ? parts.join(" · ") : null;
      if (b.state === "warning") warning = b.can_buy?.packs ? w.cr_solo_warn_pack(p) : w.cr_solo_warn_wait(p, startsAgain(b, lang));
      plan = waiting ? w.cr_solo_plan_waiting : w.cr_solo_plan_left(c(left));
      if (b.state === "warning") home = w.cr_solo_home(p);
      break;
    }
    case "company": {
      const team = w.cr_company(seats(count(b.seats), lang));
      if (count(b.beyond) === 0) {
        words = w.cr_co_used_of(team, number(count(b.used), lang), c(count(b.included)));
        detail = w.cr_co_detail_limit(eur(count(b.limit_cents)));
      } else {
        words = w.cr_co_used_beyond(team, eur(count(b.beyond_cents)), eur(count(b.limit_cents)));
        detail = w.cr_co_detail_beyond;
      }
      if (b.state === "warning") warning = w.cr_co_warn(p);
      plan = waiting ? w.cr_co_plan_waiting : w.cr_co_plan(team, c(count(b.used)));
      if (b.state === "warning") home = w.cr_co_home(p);
      break;
    }
    case "unbounded": {
      const name = b.billed_by === "contract" ? w.cr_agreement : w.cr_no_limit;
      words = w.cr_ub_words(name, count(b.used) > 0 ? w.cr_used(c(count(b.used))) : w.cr_none_yet);
      plan = w.cr_ub_plan(name, c(count(b.used)));
      break;
    }
    default:
      words = w.cr_no_plan;
      plan = w.cr_no_plan_line;
      break;
  }
  if (waiting) {
    warning = waitingWords(b, lang);
    home = warning;
  }
  return {
    balance: { state: b.state, why: waiting ? (b.why ?? null) : null, meter_pct: b.kind === "solo" || b.kind === "company" ? pct : null,
      words, detail, warning, raise, buy, credits_panel: b.kind !== "none" || ranLately },
    plan_line: plan, plan_tone: b.state, home_line: home,
  };
}
