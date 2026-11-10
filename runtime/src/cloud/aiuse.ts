import { type Fetch, KIFF_API, KIFFError } from "./kiff.js";

/**
 * AI fair use (kiff/tafs#193), from before credits: chats with the tafs and
 * Setup were bounded per period by a share of the plan's price. Since credits
 * are the balance, tafs cloud sends kiff/agents no bound with a chat, and
 * GET /v1/usage/ai answers that there is none (docs/accounts.md, "In the apps").
 * What's left here is that answer, and the words and reads older apps still meet.
 */

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
 * What GET /v1/usage/ai answers since credits are the balance: no bound, nothing used, never
 * waiting. canGoOn is passed through as the apps from before credits read it.
 */
export function view(canGoOn = false): AIView {
  return { used_pct: null, asks_at_pct: null, paused: false, can_go_on: canGoOn, starts_again: null, words: "Your plan sets no AI limit for your tafs." };
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
