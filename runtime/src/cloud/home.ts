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
  /**
   * Of per_window, the extra room given on a held call (kiff/tafs#211, 6.13), as KIFF says
   * it, with the Card's own limit and when the room ends; absent without any. The app shows
   * the Card's own total and this apart, not one sum (the 755 retest).
   */
  extra_room?: Money;
  base_limit?: Money;
  room_window_end?: string;
  count_per_window: number | null;
  /** KIFF's window, as KIFF names it: calendar_day, rolling_24h, rolling_1h or lifetime. */
  window: string;
  /** The window in the owner's words, for the app to show as it is. */
  window_words: WindowWords;
}

/**
 * A spending window in the owner's words: `each` ends a limit ("$300 a day"), `now` names
 * the window's use so far ("$40 used today"). One case for each of KIFF's windows; a rolling
 * one never reads as a day that resets at midnight, and a window KIFF adds later is shown as
 * it is sent, never guessed.
 */
export interface WindowWords {
  each: string;
  now: string;
}

const WINDOWS: Record<string, WindowWords> = {
  calendar_day: { each: "a day", now: "today" },
  rolling_24h: { each: "in any 24 hours", now: "in the last 24 hours" },
  rolling_1h: { each: "in any hour", now: "in the last hour" },
  lifetime: { each: "in total", now: "so far" },
};

/** A window's words: KIFF's own, or the window as sent when tafs doesn't know it. */
export function windowWords(window: string): WindowWords {
  return WINDOWS[window] ?? { each: window, now: window };
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
  /** Each Card on its own (kiff/tafs#211), the live ones first. */
  cards: CardView[];
}

/** A Card's total for a window and what it has used; used is null when KIFF couldn't read it. */
export interface Total<T> {
  limit: T;
  used: T | null;
  window: string;
  window_words: WindowWords;
  /** Of limit, the extra room KIFF says is active, the Card's own limit, and when the room ends; absent without any. */
  extra_room?: T;
  base_limit?: T;
  room_window_end?: string;
}

/** One KIFF Card, drawn as its own Card. */
export interface CardView {
  id: string;
  /** Its own title from KIFF, else its main tool's. */
  title: string;
  /** The environment KIFF scopes it to, as KIFF names it; "" for every environment. */
  environment: string;
  /** It covers what the taf does for real: production, or every environment. */
  live: boolean;
  active: boolean;
  expires: string | null;
  rules: Rule[];
  /** Its money total, at the ceiling it has now (extra room included). */
  total: Total<Money> | null;
  /** Its count of calls. */
  calls: Total<number> | null;
}

/**
 * - working: tafs cloud runs it and its Cards are active;
 * - paused: its schedule is off;
 * - waiting: a call of it is held for the owner;
 * - idle: an agent tafs doesn't run (made through KIFF's API), acting only when its own software calls;
 * - stopped: no active Card.
 */
export type HomeState = "working" | "paused" | "waiting" | "idle" | "stopped" | "halted";

/** KIFF's Card as `GET /v1/me/mandates?include=use` sends it, as much as the list reads. */
export interface Mandate {
  id: string;
  subject: string;
  /** The environment it is scoped to; empty for every environment. */
  environment?: string | null;
  /** Its title for people, when KIFF has one for it. */
  title?: string | null;
  grants?: { action: string; title?: string | null; per_action_max?: { quantity: string; max: number; unit?: string | null; scale?: number | null }[] | null }[] | null;
  aggregates?: { quantity: string; limit: number; window: string; unit?: string | null; scale?: number | null }[] | null;
  limits?: {
    quantity: string; window?: string | null; limit: number; used?: number | null; status?: string | null; unit?: string | null; scale?: number | null;
    /** The Card's own limit, without extra room; limit is the ceiling now. */
    base_limit?: number | null;
    /** The extra room active now, given on a held call. */
    extra_room?: number | null;
    /** When the window the room counts in ends, and the room with it. */
    room_window_end?: string | null;
  }[] | null;
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

async function read<T>(path: string, token: string, doFetch: Fetch, base: string, what = "the Cards"): Promise<T> {
  const res = await doFetch(`${base}${path}`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
  if (res.status === 401 || res.status === 403) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (!res.ok) throw new KIFFError(502, `KIFF answered ${res.status} for ${what}`);
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

/** What the person may do with an agent, as KIFF answers it. */
export interface Access {
  use: boolean;
  oversee: boolean;
  manage: boolean;
}

/**
 * The person's answer for each of the account's agents (GET /v1/me/access), read with
 * their session. An agent KIFF doesn't name gets no answer. A KIFF that can't be read
 * fails, so the list never falls back to showing everything.
 */
export async function readAccess(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<Map<string, Access>> {
  const out = await read<{ agents?: Record<string, Partial<Access>> }>("/v1/me/access", token, doFetch, base, "what this person may use");
  return new Map(Object.entries(out.agents ?? {}).map(([agent, a]) => [agent, { use: a.use === true, oversee: a.oversee === true, manage: a.manage === true }]));
}

/** Whether the list shows an agent: KIFF says the person may use it or oversee it. tafs cloud decides nothing. */
export function listable(access: Map<string, Access>, agent: string): boolean {
  const a = agent ? access.get(agent) : undefined;
  return a !== undefined && (a.use || a.oversee);
}

const isSum = (quantity: string) => quantity.toLowerCase().startsWith("sum(");

function money(minor: number, unit: string | null | undefined, scale: number | null | undefined): Money {
  return unit ? { minor, unit, scale: scale ?? 0 } : { minor, unit: null, scale: 0 };
}

/** A window as the app gets it: KIFF's name, as sent, and its words. */
function window(raw: string | null | undefined): { window: string; window_words: WindowWords } {
  // A line with no total has no window of its own: it reads as a day, the Card's default.
  const w = raw == null ? "calendar_day" : raw.trim();
  return { window: w, window_words: windowWords(w) };
}

const label = (action: string, title?: string | null) => title?.trim() || action;

/**
 * Lines that would read the same get what tells them apart (kiff/tafs#138, item 53): two
 * tools a vendor titles alike ("Read Stripe API data", for listing charges and for getting
 * one) read as the title and the tool's own words, its name without the grant's prefix.
 */
export function distinct(rules: Rule[]): Rule[] {
  const seen = new Map<string, number>();
  for (const r of rules) seen.set(r.label, (seen.get(r.label) ?? 0) + 1);
  return rules.map((r) => ((seen.get(r.label) ?? 0) > 1 ? { ...r, label: `${r.label} · ${toolWords(r.action)}` } : r));
}

/** "bxkwf7vi_list_charges" → "list charges": the grant's prefix (letters and a digit) goes. */
function toolWords(action: string): string {
  return action.replace(/^(?=[a-z]*\d)[a-z0-9]{6,}_/, "").replace(/_/g, " ");
}

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
 * The extra room on a total, as KIFF's Card read says it (extra_room, base_limit,
 * room_window_end): passed through, never worked out here. Nothing without any, or from
 * a KIFF that doesn't say.
 */
function room(line: NonNullable<Mandate["limits"]>[number] | undefined): { extra: number; base: number; ends?: string } | undefined {
  if (line?.status !== "ok" || !line.extra_room || line.extra_room <= 0 || typeof line.base_limit !== "number") return undefined;
  return { extra: line.extra_room, base: line.base_limit, ...(line.room_window_end ? { ends: line.room_window_end } : {}) };
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
      per_window: null, count_per_window: null, ...window(null),
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
    const r = room(line);
    if (r) {
      const unit = line?.unit ?? total.unit, scale = line?.scale ?? total.scale;
      first.extra_room = money(r.extra, unit, scale);
      first.base_limit = money(r.base, unit, scale);
      if (r.ends) first.room_window_end = r.ends;
    }
    Object.assign(first, window(total.window));
  } else if (count) {
    Object.assign(first, window(count.window));
  }
  return out;
}

/** Production, or a Card scoped to no environment, which applies in every one. */
export function isLive(environment: string | null | undefined): boolean {
  const env = (environment ?? "").trim().toLowerCase();
  return env === "" || env === "production" || env === "prod" || env === "live";
}

/** A Card on its own: its lines, and its money total and call count with their use. */
export function cardView(m: Mandate): CardView {
  const lines = distinct(rules(m));
  let total: CardView["total"] = null;
  const shown = shownTotal(m);
  if (shown) {
    const { total: agg, line } = shown;
    const ok = line?.status === "ok";
    const unit = line?.unit ?? agg.unit;
    const scale = line?.scale ?? agg.scale;
    total = {
      limit: money(ok && line ? line.limit : agg.limit, unit, scale),
      used: ok && typeof line?.used === "number" ? money(line.used, unit, scale) : null,
      ...window(agg.window),
    };
    const r = room(line);
    if (r) {
      total.extra_room = money(r.extra, unit, scale);
      total.base_limit = money(r.base, unit, scale);
      if (r.ends) total.room_window_end = r.ends;
    }
  }
  let calls: CardView["calls"] = null;
  const count = (m.aggregates ?? []).find((a) => !isSum(a.quantity));
  if (count) {
    const line = (m.limits ?? []).find((l) => l.quantity === count.quantity && l.window === count.window);
    const ok = line?.status === "ok";
    calls = { limit: ok && line ? line.limit : count.limit, used: ok && typeof line?.used === "number" ? line.used : null, ...window(count.window) };
    const r = room(line);
    if (r) {
      calls.extra_room = r.extra;
      calls.base_limit = r.base;
      if (r.ends) calls.room_window_end = r.ends;
    }
  }
  const environment = (m.environment ?? "").trim();
  return {
    id: m.id, title: m.title?.trim() || lines[0]?.label || "No actions yet", environment, live: isLive(environment),
    active: m.active, expires: m.valid_until ?? null, rules: lines, total, calls,
  };
}

/** The live Cards first, then the active ones, each group in KIFF's order. */
function shownOrder(a: CardView, b: CardView): number {
  return Number(b.live) - Number(a.live) || Number(b.active) - Number(a.active);
}

/** What a holder's live Cards allow and have used. */
export function summary(cards: Mandate[]): CardSummary {
  const lines = distinct(cards.flatMap(rules));
  const tools: string[] = [];
  for (const r of lines) if (r.action && !tools.includes(r.label)) tools.push(r.label);
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
    rules: lines,
    used,
    usage_unknown: [...unknown],
    cards: cards.map(cardView).sort(shownOrder),
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
