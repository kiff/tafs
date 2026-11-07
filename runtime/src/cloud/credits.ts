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
    free: k.free && typeof k.free === "object" ? k.free : {} };
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

/** A day in the account's zone: "1 Dec". */
function day(at: Date, zone: string): string {
  try {
    return new Intl.DateTimeFormat("en-GB", { timeZone: zone, day: "numeric", month: "short" }).format(at);
  } catch {
    return new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", day: "numeric", month: "short" }).format(at);
  }
}

/** The period's line: "This period: 1,240 credits used · ends on 1 Dec", or the last one's dates. */
export function periodWords(k: Pick<KIFFCredits, "period" | "current" | "credits">, zone: string): string {
  const start = new Date(k.period.start), end = new Date(k.period.end);
  const used = k.credits > 0 ? `${credits(k.credits)} used` : k.current ? "no credits used yet" : "no credits used";
  if (k.current) return `This period: ${used} · ends on ${day(end, zone)}`;
  return `Last period: ${used} · ${day(start, zone)} to ${day(new Date(end.getTime() - 1), zone)}`;
}

/** Why runs weren't counted, in words, in the order they're shown. */
const WHY: ReadonlyArray<{ kiff: string; why: string; words: string }> = [
  { kiff: "setup", why: "coach", words: "Coach, within its fair use" },
  { kiff: "trial", why: "trial", words: "Trials of a new version" },
  { kiff: "failed", why: "failed", words: "Failed on our side" },
];

/** The note under the panel. */
export const NOTE = "Credits will replace tasks. Nothing is charged in credits yet.";

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
export function creditsView(k: KIFFCredits, tafs: CreditsTaf[], zone: string): CreditsView {
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
    credits: k.credits, runs: k.runs, words: periodWords(k, zone), tafs: rows, coach,
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
