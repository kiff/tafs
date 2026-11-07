import { type Fetch, KIFF_API, KIFFError } from "./kiff.js";
import { fromWords } from "./request.js";

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
  can_buy: { solo: boolean; packs: boolean };
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
  const k = await readCredits(token, false, doFetch, base);
  const b = k.balance;
  if (!b || !["solo", "company", "unbounded", "none"].includes(b.kind) || !b.period || typeof b.period.end !== "string") {
    throw new CreditsError(503, UNREADABLE);
  }
  return b;
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
const number = (n: number) => new Intl.NumberFormat("en-GB").format(n);
const credits = (n: number) => `${number(n)} ${n === 1 ? "credit" : "credits"}`;
const runs = (n: number) => `${number(n)} ${n === 1 ? "run" : "runs"}`;

/** A date of KIFF's period, as KIFF bounds it (UTC): "1 Dec". */
function day(at: Date): string {
  return new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", day: "numeric", month: "short" }).format(at);
}

/**
 * The period's line, with its first and last days as KIFF bounds the period (UTC): the start's
 * date, and the day before the end's date, never the instant before the end in the account's
 * zone, which east of UTC is already the next day. "This period: 1,240 credits used · ends on
 * 30 Nov", or "Last period: 980 credits used · 1 Oct to 31 Oct".
 */
export function periodWords(k: Pick<KIFFCredits, "period" | "current" | "credits">): string {
  const start = new Date(k.period.start), end = new Date(k.period.end);
  const last = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth(), end.getUTCDate() - 1));
  const used = k.credits > 0 ? `${credits(k.credits)} used` : k.current ? "no credits used yet" : "no credits used";
  if (k.current) return `This period: ${used} · ends on ${day(last)}`;
  return `Last period: ${used} · ${day(start)} to ${day(last)}`;
}

/** Why runs weren't counted, in words, in the order they're shown. */
const WHY: ReadonlyArray<{ kiff: string; why: string; words: string }> = [
  { kiff: "setup", why: "coach", words: "Coach, within its fair use" },
  { kiff: "trial", why: "trial", words: "Trials of a new version" },
  { kiff: "failed", why: "failed", words: "Failed on our side" },
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
  tafs: Array<{ taf: string | null; credits: number; runs: number; words: string }>;
  coach: { credits: number; runs: number; words: string } | null;
  not_counted: { runs: number; words: string; lines: Array<{ why: string; runs: number; words: string }> };
  note: string;
}

/**
 * The period, in words: each taf that used credits, most first, as the person may see it; the
 * rest (tafs removed since, and those the person doesn't use or oversee) on one row that doesn't
 * open; Coach's row past its fair use; and what wasn't counted, and why.
 */
export function creditsView(k: KIFFCredits, tafs: CreditsTaf[]): CreditsView {
  const rows: CreditsView["tafs"] = [];
  let rest = { credits: 0, runs: 0, unseen: false };
  for (const a of k.by_agent) {
    const t = tafs.find((x) => x.id === a.runtime_agent) ?? tafs.find((x) => x.kiffAgent === a.agent);
    if (t?.opens) {
      rows.push({ taf: t.id, credits: count(a.credits), runs: count(a.runs), words: `${t.name} · ${credits(count(a.credits))} · ${runs(count(a.runs))}` });
      continue;
    }
    rest = { credits: rest.credits + count(a.credits), runs: rest.runs + count(a.runs), unseen: rest.unseen || t !== undefined };
  }
  rows.sort((a, b) => b.credits - a.credits);
  if (rest.runs > 0) {
    const name = rest.unseen ? "Other tafs" : "Removed tafs";
    rows.push({ taf: null, credits: rest.credits, runs: rest.runs, words: `${name} · ${credits(rest.credits)} · ${runs(rest.runs)}` });
  }
  const coach = k.setup.runs > 0 ? { credits: k.setup.credits, runs: k.setup.runs, words: `Coach, past its fair use · ${credits(k.setup.credits)} · ${runs(k.setup.runs)}` } : null;
  const lines = WHY.filter((w) => count(k.free[w.kiff]) > 0).map((w) => ({ why: w.why, runs: count(k.free[w.kiff]), words: `${w.words} · ${runs(count(k.free[w.kiff]))}` }));
  const free = lines.reduce((n, l) => n + l.runs, 0);
  return {
    period: { start: k.period.start, end: k.period.end, current: k.current },
    credits: k.credits, runs: k.runs, words: periodWords(k), tafs: rows, coach,
    not_counted: { runs: free, words: free > 0 ? `Not counted: ${runs(free)}` : "Not counted: none", lines },
    note: NOTE,
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
function blocked(refused: number, declined: number): string {
  const r = count(refused), d = count(declined);
  const action = (n: number) => `${n} ${n === 1 ? "action" : "actions"}`;
  if (r > 0 && d > 0) return ` · ${action(r)} refused by KIFF, ${d} declined on approval`;
  if (r > 0) return ` · ${action(r)} refused by KIFF`;
  if (d > 0) return ` · ${action(d)} declined on approval`;
  return "";
}

/** One run, in words: where it came from and what it used, or why it wasn't counted. */
export function runWords(r: KIFFRun): string {
  if (r.free === "trial" || r.trigger === "trial") return "A trial of a new version · not counted";
  const from = fromWords(r.trigger, r.source, r.requester_kind);
  if (!r.counted) return `${from} · ${r.free === "failed" ? "failed on our side, not counted" : "not counted"}`;
  return `${from} · ${credits(count(r.credits))}${blocked(r.refused, r.declined)}`;
}

/** Whether a run's row opens its request: while kiff/agents keeps the run, and never a trial's. */
export function opens(r: KIFFRun, now: Date): boolean {
  if (r.free === "trial" || r.trigger === "trial") return false;
  if (!r.kept_until) return true;
  const kept = Date.parse(r.kept_until);
  return Number.isNaN(kept) || kept > now.getTime();
}

/** One taf's runs in the period, in words, with its line from the period's credits. */
export function tafCreditsView(k: KIFFCredits, page: KIFFRuns, taf: CreditsTaf, now: Date): TafCreditsView {
  const a = k.by_agent.find((x) => x.runtime_agent === taf.id) ?? k.by_agent.find((x) => x.agent === taf.kiffAgent);
  const c = count(a?.credits), n = count(a?.runs);
  return {
    period: { start: k.period.start, end: k.period.end, current: k.current },
    taf: taf.id, credits: c, runs: n,
    words: `${taf.name} · ${credits(c)} · ${runs(n)} ${k.current ? "this period" : "last period"}`,
    items: page.items.map((r) => ({ run: r.run, at: r.ended, credits: r.counted ? count(r.credits) : 0, counted: r.counted === true,
      why: r.counted ? null : r.free === "setup" ? "coach" : (r.free ?? null), words: runWords(r), opens: opens(r, now) })),
    ...(page.next ? { next: page.next } : {}),
  };
}

/** Euro cents as the apps show them: "€50", or "€12.34". */
function euros(cents: number): string {
  const whole = cents % 100 === 0;
  return new Intl.NumberFormat("en-GB", { style: "currency", currency: "EUR", minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 })
    .format(cents / 100);
}

/** The day the period starts again: its end's date, as KIFF bounds it (UTC). "1 Dec". */
function startsAgain(b: KIFFBalance): string {
  return day(new Date(b.period.end));
}

const seats = (n: number) => `${number(n)} ${n === 1 ? "seat" : "seats"}`;

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
    buy: { solo: boolean; packs: boolean } | null;
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
export function waitingWords(b: Pick<KIFFBalance, "kind" | "why" | "can_raise" | "raise_to_cents" | "can_buy" | "period">): string {
  const again = `they go on when the period starts again on ${startsAgain(b as KIFFBalance)}`;
  switch (b.why ?? (b.kind === "none" ? "no_plan" : b.kind === "company" ? "limit_reached" : "credits_used_up")) {
    case "no_plan":
      return "Choose a plan to start your tafs.";
    case "limit_reached":
      if (b.can_raise && b.raise_to_cents > 0) return `Your tafs are waiting: the spend limit is reached. Raise it to let them go on, or ${again}.`;
      if (b.can_raise) return `Your tafs are waiting: the spend limit is reached, and it can't go higher from the app. Contact KIFF to raise it further, or ${again}.`;
      return "Your tafs are waiting: the spend limit is reached. An admin of this account can raise it.";
    default:
      if (b.can_buy?.packs) return `Your tafs are waiting: this period's credits and your packs are used. A pack lets them go on, or ${again}.`;
      return `Your tafs are waiting: this period's credits and your packs are used. They go on when the period starts again on ${startsAgain(b as KIFFBalance)}.`;
  }
}

/**
 * The balance, in tafs cloud's words (docs/accounts.md, "The balance read"): its line and second
 * line, the bar, the warning from 80% and while waiting, the raise for whoever may make it, what may
 * be bought, and the plan's lines for Settings and Home. The apps draw them as they are.
 */
export function balanceView(b: KIFFBalance): BalanceView {
  const pct = typeof b.meter_pct === "number" ? Math.max(0, Math.min(100, Math.floor(b.meter_pct))) : null;
  const waiting = b.state === "waiting";
  const raise = b.kind === "company" && b.can_raise && b.raise_to_cents > 0
    ? { words: `Raise the spend limit to ${euros(b.raise_to_cents)}`, from_cents: b.limit_cents } : null;
  const buy = b.can_buy && (b.can_buy.solo || b.can_buy.packs) ? { solo: b.can_buy.solo === true, packs: b.can_buy.packs === true } : null;
  let words: string, detail: string | null = null, warning: string | null = null, plan: string, home: string | null = null;
  switch (b.kind) {
    case "solo": {
      const left = count(b.left);
      words = waiting ? "Solo · this period's credits and your packs are used" : `Solo · ${credits(left)} left this period`;
      const parts: string[] = [];
      if (count(b.packs_left) > 0) parts.push(`${credits(count(b.packs_left))} of it in packs, which never expire`);
      if (count(b.over) > 0) parts.push(`${credits(count(b.over))} used past your balance weren't charged`);
      detail = parts.length ? parts.join(" · ") : null;
      if (b.state === "warning") {
        warning = b.can_buy?.packs
          ? `${pct}% of this period's credits are used. A pack lets your tafs go on past them.`
          : `${pct}% of this period's credits are used. Then your tafs wait until the period starts again on ${startsAgain(b)}.`;
      }
      plan = waiting ? "Solo · waiting: this period's credits are used" : `Solo · ${credits(left)} left`;
      if (b.state === "warning") home = `${pct}% of this period's credits are used`;
      break;
    }
    case "company": {
      const team = `Company · ${seats(count(b.seats))}`;
      if (count(b.beyond) === 0) {
        words = `${team} · ${number(count(b.used))} of ${credits(count(b.included))} included used`;
        detail = `Then up to the spend limit, ${euros(count(b.limit_cents))}, on this period's invoice`;
      } else {
        words = `${team} · ${euros(count(b.beyond_cents))} of the ${euros(count(b.limit_cents))} spend limit used`;
        detail = "The plan's included credits are used. Beyond them, credits go on this period's invoice.";
      }
      if (b.state === "warning") warning = `${pct}% of the spend limit is used. At the limit, your tafs wait until an admin raises it.`;
      plan = waiting ? "Company · waiting: the spend limit is reached" : `${team} · ${credits(count(b.used))} used`;
      if (b.state === "warning") home = `${pct}% of the spend limit is used`;
      break;
    }
    case "unbounded": {
      const name = b.billed_by === "contract" ? "Your company's agreement" : "No limit";
      words = `${name} · ${count(b.used) > 0 ? `${credits(count(b.used))} used` : "no credits used yet"} this period`;
      plan = `${name} · ${credits(count(b.used))} used`;
      break;
    }
    default:
      words = "No plan";
      plan = "No plan · choose one to start";
      break;
  }
  if (waiting) {
    warning = waitingWords(b);
    home = warning;
  }
  return {
    balance: { state: b.state, why: waiting ? (b.why ?? null) : null, meter_pct: b.kind === "solo" || b.kind === "company" ? pct : null,
      words, detail, warning, raise, buy },
    plan_line: plan, plan_tone: b.state, home_line: home,
  };
}
