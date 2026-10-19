/**
 * The app's home list (kiff/tafs#91, the owner's rule of 2026-10-19): each taf
 * with its state, colour and Card summary, made here from tafs cloud's records
 * and KIFF's Cards and held calls, so the app shows one list and combines
 * nothing itself. KIFF is read with the owner's own session, as the phone
 * would; the phone still reads held calls from KIFF itself to answer them.
 *
 * An amount carries the unit and decimal places KIFF declares for it, or none:
 * nothing here guesses a currency or a scale from a parameter's name.
 */
import { type Fetch, KIFF_API, KIFFError } from "./kiff.js";

/** A number with what KIFF says it means ("EUR", 2), or a bare number (unit null). */
export interface Money {
  minor: number;
  unit: string | null;
  scale: number;
}

/** One line of a Card, as the app shows it. */
export interface Rule {
  action: string;
  /** The tool's title as KIFF keeps it from the vendor, else the action. */
  label: string;
  outside: "hold" | "refuse";
  per_action: Money | null;
  /** The Card's total for the window, with the room it has now. */
  per_window: Money | null;
  count_per_window: number | null;
  window: "day" | "week" | "month";
}

/** What a holder's Cards allow and have used. */
export interface CardSummary {
  /** The live Cards: what revoking stops. */
  ids: string[];
  active: boolean;
  /** When the first of them ends; null when none has an end. */
  expires: string | null;
  /** What its Cards grant, as KIFF names them. */
  tools: string[];
  rules: Rule[];
  /** Used so far in each rule's window, by action. */
  used: Record<string, Money>;
  /** Actions whose use KIFF couldn't read: never shown as zero. */
  usage_unknown: string[];
}

/**
 * - working: tafs cloud runs it and its Cards are active;
 * - paused: its schedule is off;
 * - waiting: a call of it is held for the owner;
 * - idle: an agent tafs doesn't run (made through KIFF's API), acting only when its own software calls;
 * - stopped: no active Card.
 */
export type HomeState = "working" | "paused" | "waiting" | "idle" | "stopped";

/** KIFF's Card as `GET /v1/me/mandates?include=use` sends it, as much as the list reads. */
export interface Mandate {
  id: string;
  subject: string;
  grants?: { action: string; title?: string | null; per_action_max?: { quantity: string; max: number; unit?: string | null; scale?: number | null }[] | null }[] | null;
  aggregates?: { quantity: string; limit: number; window: string; unit?: string | null; scale?: number | null }[] | null;
  limits?: { quantity: string; window?: string | null; limit: number; used?: number | null; status?: string | null; unit?: string | null; scale?: number | null }[] | null;
  active: boolean;
  valid_until?: string | null;
  revoked_at?: string | null;
  on_exceed?: string | null;
}

/** A held call, as much as the list reads: whose it is. */
export interface Held {
  subject: string;
}

/** The colours the app has for tafs (`Theme.tafColors`). */
export const COLORS = 6;

async function read<T>(path: string, token: string, doFetch: Fetch, base: string): Promise<T> {
  const res = await doFetch(`${base}${path}`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
  if (res.status === 401 || res.status === 403) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (!res.ok) throw new KIFFError(502, `KIFF answered ${res.status} for the Cards`);
  return (await res.json()) as T;
}

/** The account's Cards, with each limit's use, and its held calls, read with the owner's session. */
export async function readKIFF(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<{ mandates: Mandate[]; held: Held[] }> {
  const [m, e] = await Promise.all([
    read<{ mandates?: Mandate[] }>("/v1/me/mandates?include=use", token, doFetch, base),
    read<{ exceptions?: Held[] }>("/v1/me/card-exceptions?status=held", token, doFetch, base),
  ]);
  return { mandates: m.mandates ?? [], held: e.exceptions ?? [] };
}

const isSum = (quantity: string) => quantity.toLowerCase().startsWith("sum(");

function money(minor: number, unit: string | null | undefined, scale: number | null | undefined): Money {
  return unit ? { minor, unit, scale: scale ?? 0 } : { minor, unit: null, scale: 0 };
}

function window(raw: string): Rule["window"] {
  const w = raw.toLowerCase();
  if (w.includes("week") || w === "7d") return "week";
  if (w.includes("month") || w === "30d") return "month";
  return "day";
}

const label = (action: string, title?: string | null) => title?.trim() || action;

/**
 * The summed total a Card shows, and its use line: the same quantity and window,
 * so a meter never divides one window's use by another's.
 */
function shownTotal(m: Mandate) {
  const total = (m.aggregates ?? []).find((a) => isSum(a.quantity));
  if (!total) return undefined;
  const line = (m.limits ?? []).find((l) => l.quantity === total.quantity && l.window === total.window);
  return { total, line };
}

/**
 * A Card's lines: one per grant, with its limit for one call. A Card's totals
 * cover all its grants, so the first line shows one of each: the first summed
 * total, at the ceiling it has now (extra room included), and the first count.
 */
export function rules(m: Mandate): Rule[] {
  const outside = m.on_exceed === "refuse" ? "refuse" : "hold";
  const out: Rule[] = (m.grants ?? []).map((g) => {
    const cap = g.per_action_max?.[0];
    return {
      action: g.action, label: label(g.action, g.title), outside,
      per_action: cap ? money(cap.max, cap.unit, cap.scale) : null,
      per_window: null, count_per_window: null, window: "day",
    };
  });
  const first = out[0];
  if (!first) return out;
  const count = (m.aggregates ?? []).find((a) => !isSum(a.quantity));
  if (count) first.count_per_window = count.limit;
  const shown = shownTotal(m);
  if (shown) {
    const { total, line } = shown;
    const ceiling = line?.status === "ok" ? line.limit : total.limit;
    first.per_window = money(ceiling, line?.unit ?? total.unit, line?.scale ?? total.scale);
    first.window = window(total.window);
  } else if (count) {
    first.window = window(count.window);
  }
  return out;
}

/** What a holder's live Cards allow and have used. */
export function summary(cards: Mandate[]): CardSummary {
  const tools: string[] = [];
  for (const g of cards.flatMap((c) => c.grants ?? [])) {
    const t = label(g.action, g.title);
    if (g.action && !tools.includes(t)) tools.push(t);
  }
  const used: Record<string, Money> = {};
  const unknown = new Set<string>();
  for (const c of cards) {
    const action = c.grants?.[0]?.action;
    const shown = shownTotal(c);
    if (!action || !shown) continue;
    const { total, line } = shown;
    if (line?.status === "ok" && typeof line.used === "number") used[action] = money(line.used, line.unit ?? total.unit, line.scale ?? total.scale);
    else unknown.add(action);
  }
  const ends = cards.map((c) => c.valid_until).filter((v): v is string => !!v).sort((a, b) => Date.parse(a) - Date.parse(b));
  return {
    ids: cards.map((c) => c.id),
    active: cards.some((c) => c.active),
    expires: ends[0] ?? null,
    tools,
    rules: cards.flatMap(rules),
    used,
    usage_unknown: [...unknown],
  };
}

/**
 * A holder's state: waiting while a call of it is held, stopped without an
 * active Card, then paused or working for a taf tafs cloud runs, idle for an
 * agent it doesn't.
 */
export function stateOf(card: CardSummary, held: number, run: "paused" | "active" | null): HomeState {
  if (held > 0) return "waiting";
  if (!card.active) return "stopped";
  if (run === null) return "idle";
  return run === "paused" ? "paused" : "working";
}

/** The first colour no other taf has, so a new taf never takes another's. */
export function freeColor(taken: number[]): number {
  for (let c = 0; c < COLORS; c++) if (!taken.includes(c)) return c;
  return taken.length % COLORS;
}

/** Each holder's live Cards and held calls, by KIFF agent. */
export function byHolder(mandates: Mandate[], held: Held[]) {
  const cards = new Map<string, Mandate[]>();
  for (const m of mandates) {
    if (m.revoked_at) continue;
    cards.set(m.subject, [...(cards.get(m.subject) ?? []), m]);
  }
  const waiting = new Map<string, number>();
  for (const h of held) waiting.set(h.subject, (waiting.get(h.subject) ?? 0) + 1);
  return { cards, waiting };
}
