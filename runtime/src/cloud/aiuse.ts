import { type Fetch, KIFF_API, KIFFError } from "./kiff.js";

/**
 * AI fair use (kiff/tafs#193): what chats with the tafs and Setup cost the
 * model is bounded at half the plan's monthly price, per period. kiff/agents
 * counts the cost and refuses a new chat past the bound; KIFF keeps the plan,
 * its seats, the period and how many times the owner said go on (signed on
 * the phone). tafs cloud joins them: the bound it sends with each chat, and
 * what the app shows. AI use is never billed: the bound only asks first.
 */

/** Each plan's monthly price per seat, in euro cents excl. VAT, as /pricing shows it. The trial uses Solo's. */
export const PLAN_EUR_CENTS: Readonly<Record<string, number>> = { trial: 899, solo: 899, team: 899 };

/**
 * US dollars per euro: the model costs are in dollars and the prices in euros.
 * A fixed rate, changed by hand (kiff/tafs#193), so the bound stays predictable.
 */
export const USD_PER_EUR = 1.08;

/** The part of KIFF's usage read AI fair use needs. */
export interface Plan {
  plan: string;
  seats: number;
  /** The period's start, as KIFF names it (RFC 3339). */
  start: string;
  /** The period's end, when the AI use starts again (RFC 3339), when KIFF gives it. */
  end?: string;
  /** How many times the owner said go on this period. */
  goOns: number;
}

/**
 * The account's plan and period, from KIFF's usage read with the owner's session;
 * undefined when KIFF doesn't give one (no billing yet, or not answering), so
 * a chat is never refused for a read that failed.
 */
export async function planOf(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<Plan | undefined> {
  let res: Response;
  try {
    res = await doFetch(`${base}/v1/me/usage`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
  } catch {
    return undefined;
  }
  if (res.status === 401 || res.status === 403) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (!res.ok) return undefined;
  const u = (await res.json().catch(() => ({}))) as { plan?: unknown; seats?: unknown; period?: { start?: unknown; end?: unknown }; ai?: { go_ons?: unknown } };
  if (typeof u.plan !== "string" || typeof u.period?.start !== "string" || !u.period.start) return undefined;
  const count = (n: unknown) => (typeof n === "number" && Number.isInteger(n) && n > 0 ? n : 0);
  const end = typeof u.period.end === "string" && u.period.end ? { end: u.period.end } : {};
  return { plan: u.plan, seats: count(u.seats), start: u.period.start, goOns: count(u.ai?.go_ons), ...end };
}

/** The period's name for kiff/agents, which takes a-z, 0-9, '.', '_' and '-': "2026-10-20t09.12.00z". */
export function periodName(start: string): string {
  return start.toLowerCase().replace(/:/g, ".").replace(/[^a-z0-9._-]/g, "-");
}

/**
 * Half the plan's monthly price, in micro US dollars: the bound before any go on.
 * The trial and Solo have one seat. 0 is no bound: the internal plan, and any
 * plan without a price here.
 */
export function base(p: Plan): number {
  const cents = PLAN_EUR_CENTS[p.plan];
  if (cents === undefined) return 0;
  const seats = p.plan === "team" ? p.seats : 1;
  return Math.round((cents / 2) * seats * USD_PER_EUR * 10_000);
}

/** The bound now: each go on adds one more base, so the tafs ask again at 200%, 300%… */
export function bound(p: Plan): number {
  return base(p) * (p.goOns + 1);
}

/** What kiff/agents takes with a chat: the period it counts toward and its bound (0, none). */
export function aiFor(p: Plan | undefined): { period: string; bound_micro_usd: number } | undefined {
  return p ? { period: periodName(p.start), bound_micro_usd: bound(p) } : undefined;
}

/** What the app shows. No bound: nulls, and never paused. */
export interface AIView {
  used_pct: number | null;
  asks_at_pct: number | null;
  paused: boolean;
  /**
   * Whether this person may go on (billing.manage): the apps show **Go on** only then, signed
   * through KIFF's `PUT /v1/me/usage/ai` (kiff/tafs#185).
   */
  can_go_on: boolean;
  /** When the AI use starts again by itself: the period's end (RFC 3339), null when KIFF gives none. */
  starts_again: string | null;
  /** The same, in words the apps show as they are. */
  words: string;
}

/** The day the period ends, in the account's zone ("1 Nov"), or "" when KIFF gives no end. */
export function startsAgainDay(p: Plan | undefined, zone = "UTC"): string {
  const end = p?.end ? new Date(p.end) : undefined;
  if (!end || Number.isNaN(end.getTime())) return "";
  try {
    return new Intl.DateTimeFormat("en-GB", { timeZone: zone, day: "numeric", month: "short" }).format(end);
  } catch {
    return new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", day: "numeric", month: "short" }).format(end);
  }
}

/** When the AI use starts again, as a clause: "on 1 Nov", or "with the next period". */
function again(p: Plan | undefined, zone: string): string {
  const day = startsAgainDay(p, zone);
  return day ? `on ${day}` : "with the next period";
}

/**
 * The period's use as a share of the base, where the tafs ask next, and whether they're asking now.
 * While they wait, someone who may go on is told to; anyone else is told who can, and when it starts
 * again by itself. An account's admin is named by role, never by email.
 */
export function view(p: Plan | undefined, spent: number, canGoOn = false, zone = "UTC"): AIView {
  const b = p ? base(p) : 0;
  const starts = p?.end ?? null;
  if (!p || b <= 0) return { used_pct: null, asks_at_pct: null, paused: false, can_go_on: canGoOn, starts_again: starts, words: "Your plan sets no AI limit for your tafs." };
  const used = Math.floor((spent * 100) / b), at = 100 * (p.goOns + 1), paused = spent >= bound(p);
  let words = `Your tafs used ${used}% of this period's AI. They ask you before going past ${at}%.`;
  if (paused) {
    words = canGoOn
      ? `Your tafs used ${used}% of this period's AI and are waiting for you. Go on, and they ask again at ${at + 100}%. Otherwise it starts again ${again(p, zone)}.`
      : `Your tafs used ${used}% of this period's AI and are waiting. An admin of this account can let them go on from Settings › AI use. Otherwise it starts again ${again(p, zone)}.`;
  }
  return { used_pct: used, asks_at_pct: at, paused, can_go_on: canGoOn, starts_again: starts, words };
}

/** Whether kiff/agents refused a chat for AI fair use. */
export function refusedForAI(err: unknown): boolean {
  if (!(err instanceof Error) || (err as { status?: number }).status !== 409) return false;
  const code = (err as { code?: string }).code;
  return code !== undefined ? code === "ai_fair_use" : err.message.startsWith("ai_fair_use");
}

/**
 * A chat refused for AI fair use, in words: Go on in Settings › AI use for someone who may, who can
 * otherwise, and when the AI use starts again by itself, on the period's end in the account's zone.
 */
export function refusedWords(p: Plan | undefined, zone = "UTC", canGoOn = false): string {
  const how = canGoOn
    ? "Go on from Settings › AI use"
    : "An admin of this account can let them go on from Settings › AI use";
  return `Your tafs have used this period's AI. ${how}, or it starts again ${again(p, zone)}.`;
}
