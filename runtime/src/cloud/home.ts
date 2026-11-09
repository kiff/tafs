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
import { amountWords } from "./activity.js";
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
  /** KIFF's window, as KIFF names it: calendar_day, rolling_24h, rolling_1h, rolling_7d or lifetime. */
  window: string;
  /** The window in the owner's words, for the app to show as it is. */
  window_words: WindowWords;
}

/**
 * A spending window in the owner's words: `each` ends a limit ("€300 a day"), `now` names
 * the window's use so far ("€40 used today"). One case for each of KIFF's windows; a rolling
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
  rolling_7d: { each: "in any 7 days", now: "in the last 7 days" },
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
  /** What of its use is from calls the tool refused, which count until KIFF can confirm nothing moved, in KIFF's words; absent without any. */
  pending_text?: string;
  /** Why the amount has no currency, in KIFF's words: a tool the Card grants isn't connected. Absent otherwise. */
  unit_words?: string;
  /** Which tools that is, and whether they're connected, as KIFF says; absent with unit_words. */
  unit_missing?: { tools: string[]; connected: boolean };
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
  /** How this is counted: each tool's lines in KIFF's words, as the owner approved them; absent when KIFF has none. */
  counted?: { tool: string; title: string; lines: string[] }[];
  /**
   * Its totals for each item apart, in the owner's words: "1 call in any 7 days for each invoice id". Each item has its
   * own count, so none has a used or left here; absent without any.
   */
  each_item?: string[];
}

/**
 * - working: tafs cloud runs it and its Cards are active;
 * - paused: its schedule is off;
 * - waiting: a call of it is held for the owner;
 * - idle: an agent tafs doesn't run (made through KIFF's API), acting only when its own software calls;
 * - stopped: no active Card.
 */
export type HomeState = "working" | "paused" | "waiting" | "idle" | "stopped" | "halted";

/** The avatar's door rim, ready to draw on both apps. */
export function avatarRim(lights: Array<{ open: boolean; resting?: boolean; knocks?: number }>) {
  const open = lights.filter((d) => d.open);
  return { visible: open.length > 0, tint: lights.some((d) => (d.knocks ?? 0) > 0) ? "knock" : open.every((d) => d.resting) ? "resting" : "active",
    length: Math.min(0.12 * open.length + 0.1, 0.6) };
}

/** KIFF's Card as `GET /v1/me/mandates?include=use` sends it, as much as the list reads. */
export interface Mandate {
  id: string;
  subject: string;
  /** The environment it is scoped to; empty for every environment. */
  environment?: string | null;
  /** Its title for people, when KIFF has one for it. */
  title?: string | null;
  grants?: { action: string; title?: string | null; per_action_max?: { quantity: string; max: number; unit?: string | null; scale?: number | null }[] | null }[] | null;
  /** per: a total for each value of that argument apart ("for each invoice id"), never the Card's single total. */
  aggregates?: { quantity: string; limit: number; window: string; unit?: string | null; scale?: number | null; per?: string | null }[] | null;
  limits?: {
    quantity: string; window?: string | null; limit: number; used?: number | null; status?: string | null; unit?: string | null; scale?: number | null;
    /** The Card's own limit, without extra room; limit is the ceiling now. */
    base_limit?: number | null;
    /** The extra room active now, given on a held call. */
    extra_room?: number | null;
    /** When the window the room counts in ends, and the room with it. */
    room_window_end?: string | null;
    /** What of its use is from calls the tool refused and still counts, in the owner's words. */
    pending_text?: string | null;
    /** On a summed line with no unit: why, in the owner's words, and the tools it waits on (a tool the Card grants isn't connected). */
    unit_words?: string | null;
    unit_missing?: { tools?: string[] | null; connected?: boolean | null } | null;
  }[] | null;
  active: boolean;
  valid_until?: string | null;
  revoked_at?: string | null;
  on_exceed?: string | null;
  /** How each tool it covers is counted: the lines of KIFF's words the owner approved for the tool's profile. */
  counted?: { tool?: string | null; lines?: string[] | null }[] | null;
}

/** A held call, as much as the list reads: whose it is. */
export interface Held {
  subject: string;
  id?: string; kind?: string; action?: string; title?: string; status?: string; count?: number; proposal_id?: string;
  created_at?: string; last_at?: string; accounting_version?: number;
  breaches?: Array<{ outcome?: string; unit?: string; card_unit?: string }>;
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
    readExceptions(token, doFetch, base),
  ]);
  return { mandates: m.mandates ?? [], held: e };
}

/** The account's Cards alone, read with the person's session. */
export async function readMandates(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<Mandate[]> {
  const m = await read<{ mandates?: Mandate[] }>("/v1/me/mandates", token, doFetch, base);
  if (!Array.isArray(m.mandates)) throw new KIFFError(502, "KIFF's Cards could not be read");
  return m.mandates;
}

export async function readExceptions(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<Held[]> {
  const result = await read<{ exceptions?: Held[] }>("/v1/me/card-exceptions?status=held", token, doFetch, base, "the waiting calls");
  if (!Array.isArray(result.exceptions)) throw new KIFFError(502, "KIFF's waiting calls could not be read");
  return result.exceptions;
}

/** How many money calls wait for the person's "Did this happen?" (`GET /v1/me/did-this-happen`). */
export async function readUnsure(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<number> {
  const result = await read<{ calls?: unknown[] }>("/v1/me/did-this-happen", token, doFetch, base, "the calls to confirm");
  if (!Array.isArray(result.calls)) throw new KIFFError(502, "KIFF's calls to confirm could not be read");
  return result.calls.length;
}

/**
 * One money call KIFF can't prove, as `GET /v1/me/did-this-happen` carries it with its context
 * (kiff/tafs#498): the taf, the action and the vendor, the amount, what it acted on, when, why KIFF
 * can't tell, the vendor's record address. Only the fields tafs cloud reads; the rest passes through.
 */
export interface UnsureCall {
  proposal: string;
  agent?: string;
  title?: string;
  vendor?: string;
  amount_words?: string;
  when_words?: string;
  why?: string;
  record_url?: string;
  taf?: { agent?: string; name?: string };
  counterparty?: { noun?: string; id?: string; words?: string };
  /** Who asked for help checking it ("not sure"), and until when: KIFF sets it after its own manager check. */
  checking?: { by?: string; until?: string };
}

/** The money calls that wait for the person's "Did this happen?", with their context. */
export async function readDidThisHappen(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<UnsureCall[]> {
  const result = await read<{ calls?: UnsureCall[] }>("/v1/me/did-this-happen", token, doFetch, base, "the calls to confirm");
  if (!Array.isArray(result.calls)) throw new KIFFError(502, "KIFF's calls to confirm could not be read");
  return result.calls;
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

/**
 * The account's KIFF agents that KIFF has archived, each with when (`GET /v1/me/agents`, read with the person's
 * session). Removing a taf archives its KIFF agent (kiff/agents' removal, with the taf's own key), so this is
 * where "removed" comes from: KIFF's record, never a taf missing from someone's list.
 */
export async function readArchived(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<Map<string, string>> {
  const out = await read<{ agents?: Array<{ id?: unknown; archived_at?: unknown }> }>("/v1/me/agents", token, doFetch, base, "the account's agents");
  if (!Array.isArray(out.agents)) throw new KIFFError(502, "KIFF's agents could not be read");
  return new Map(out.agents.flatMap((a) => (typeof a.id === "string" && a.id && typeof a.archived_at === "string" && a.archived_at ? [[a.id, a.archived_at] as const] : [])));
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
  const total = (m.aggregates ?? []).find((a) => isSum(a.quantity) && !a.per);
  if (!total) return undefined;
  // Never a total for each item apart: KIFF lists it with the same quantity and window, with no single use.
  const line = (m.limits ?? []).find((l) => l.status !== "per_item" && l.quantity === total.quantity && l.window === total.window);
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
  const count = (m.aggregates ?? []).find((a) => !isSum(a.quantity) && !a.per);
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
    // Passed through as KIFF says it: the apps show it, never their own words.
    if (ok && line?.pending_text) total.pending_text = line.pending_text;
    // Why its amount has no currency: about the Card, not its use, so whatever the read's status.
    if (line?.unit_words) {
      total.unit_words = line.unit_words;
      if (line.unit_missing) total.unit_missing = { tools: line.unit_missing.tools ?? [], connected: line.unit_missing.connected ?? false };
    }
  }
  let calls: CardView["calls"] = null;
  const count = (m.aggregates ?? []).find((a) => !isSum(a.quantity) && !a.per);
  if (count) {
    const line = (m.limits ?? []).find((l) => l.status !== "per_item" && l.quantity === count.quantity && l.window === count.window);
    const ok = line?.status === "ok";
    calls = { limit: ok && line ? line.limit : count.limit, used: ok && typeof line?.used === "number" ? line.used : null, ...window(count.window) };
    const r = room(line);
    if (r) {
      calls.extra_room = r.extra;
      calls.base_limit = r.base;
      if (r.ends) calls.room_window_end = r.ends;
    }
    if (ok && line?.pending_text) calls.pending_text = line.pending_text;
  }
  const environment = (m.environment ?? "").trim();
  // Passed through as KIFF words it, each under its tool's title.
  const counted = (m.counted ?? []).flatMap((c) => {
    const lines = (c.lines ?? []).filter((l): l is string => typeof l === "string" && l.trim() !== "");
    if (typeof c.tool !== "string" || !c.tool || lines.length === 0) return [];
    const title = (m.grants ?? []).find((g) => g.action === c.tool)?.title?.trim() || c.tool;
    return [{ tool: c.tool, title, lines }];
  });
  const each_item = (m.aggregates ?? []).filter((a) => a.per).map(eachItemWords);
  return {
    id: m.id, title: m.title?.trim() || lines[0]?.label || "No actions yet", environment, live: isLive(environment),
    active: m.active, expires: m.valid_until ?? null, rules: lines, total, calls, ...(counted.length ? { counted } : {}),
    ...(each_item.length ? { each_item } : {}),
  };
}

/**
 * A total for each item apart, in the owner's words, as KIFF says it on a draft: "1 call in any 7 days for each invoice
 * id", "€100.00 in total a day for each customer". A window tafs doesn't know is said as it was sent.
 */
export function eachItemWords(a: NonNullable<Mandate["aggregates"]>[number]): string {
  const window = WINDOWS[a.window]?.each ?? a.window;
  const item = (a.per ?? "").trim().toLowerCase().replaceAll("_", " ");
  if (!isSum(a.quantity)) return `${a.limit} ${a.limit === 1 ? "call" : "calls"} ${window} for each ${item}`;
  const scale = a.scale ?? 0;
  const amount = amountWords({ value: (a.limit / 10 ** scale).toFixed(scale), unit: a.unit ?? "" }) ?? String(a.limit);
  // An amount says it's a total where its window doesn't: "€100.00 in total a day", "€100.00 in any 7 days".
  const over = a.window === "calendar_day" ? "in total a day" : window;
  return `${amount} ${over} for each ${item}`;
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
  // Only a held call waits on someone: a currency ask (card_unit) doesn't stop the taf, it asks for a
  // limit, so it never makes the taf read "Waiting on you" (Needs you still lists it).
  for (const h of held) if ((h.kind ?? "hold") !== "card_unit") waiting.set(h.subject, (waiting.get(h.subject) ?? 0) + 1);
  return { cards, waiting };
}
