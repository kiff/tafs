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
  const u = (await res.json().catch(() => ({}))) as { plan?: unknown; seats?: unknown; period?: { start?: unknown }; ai?: { go_ons?: unknown } };
  if (typeof u.plan !== "string" || typeof u.period?.start !== "string" || !u.period.start) return undefined;
  const count = (n: unknown) => (typeof n === "number" && Number.isInteger(n) && n > 0 ? n : 0);
  return { plan: u.plan, seats: count(u.seats), start: u.period.start, goOns: count(u.ai?.go_ons) };
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
}

/** The period's use as a share of the base, where the tafs ask next, and whether they're asking now. */
export function view(p: Plan | undefined, spent: number): AIView {
  const b = p ? base(p) : 0;
  if (!p || b <= 0) return { used_pct: null, asks_at_pct: null, paused: false };
  return { used_pct: Math.floor((spent * 100) / b), asks_at_pct: 100 * (p.goOns + 1), paused: spent >= bound(p) };
}

/** Whether kiff/agents refused a chat for AI fair use. */
export function refusedForAI(err: unknown): boolean {
  if (!(err instanceof Error) || (err as { status?: number }).status !== 409) return false;
  const code = (err as { code?: string }).code;
  return code !== undefined ? code === "ai_fair_use" : err.message.startsWith("ai_fair_use");
}
