/**
 * A request spoken on the owner's watch (docs/watch-ask.md). The watch holds no keys: the paired phone signs
 * the request with its device key and sends it here, and only this route (`POST /v1/watch/asks`) marks a
 * Coach conversation as the watch's. That mark is never a field, a header or a word a message can carry: it
 * is where the request came in. The conversation then has the watch's one skill (`ask-a-taf`), routes the
 * request to the one taf that can do it under that taf's own Cards, and the taf's result comes back as one
 * short line, checked here, for the watch to show and say.
 *
 * What's kept, per request: its id, the client's id (so a retry never runs it twice), its state, the taf it
 * went to, the line, and the Coach conversation and runs it lives in. Never the request's words.
 */

import { localOf } from "./calendar.js";
import type { CoachStore } from "./coach.js";
import { RETENTION_NS } from "./tafdef.js";
import { wordsIn, type Lang } from "./words/index.js";

export type AskState = "accepted" | "working" | "done" | "no_taf" | "failed";

/** The states nothing more comes of: only a request in one of them can be replied to. */
export const ASK_FINAL: readonly AskState[] = ["done", "no_taf", "failed"];

/** A watch request as tafs cloud keeps it. */
export interface Ask {
  id: string;
  client_id: string;
  /** Whose: the person whose phone signed it. Only they read it, continue it or hear of it. */
  person: string;
  state: AskState;
  /** The one taf Coach handed it to, once it did. */
  taf?: { id: string; name: string };
  /** The checked line, once there is one. */
  line?: string;
  /** Coach's conversation for this request, and its runs. */
  session?: string;
  coach_run?: string;
  taf_run?: string;
  /** The request it replies to: the same conversation goes on. */
  reply_to?: string;
  /** A message to a taf the person chose (TAFS_WATCH_TAF_CHAT): it went into that taf's own chat, never through Coach. */
  kind?: "taf";
  /** The taf's answer was longer than the watch shows: the rest is on the phone. */
  more?: boolean;
  /** The taf waits on the person (an approval, a question): they answer it on the phone, never on the watch. */
  held?: boolean;
  /** Which of Coach's turns is at work: routing the request, or writing the taf's result as a line. */
  turn?: "route" | "result";
  /** The state the result turn's line ends the request in. */
  next?: AskState;
  at: string;
  updated: string;
}

/** What the phone and the watch read: no conversation or run ids. */
export interface ShownAsk {
  id: string;
  client_id: string;
  state: AskState;
  taf?: { id: string; name: string };
  line?: string;
  /** A taf chat's answer goes on past the line: the rest is on the phone. */
  more?: boolean;
  /** A taf chat's taf waits on the person, on the phone. */
  held?: boolean;
  words: string;
  at: string;
  updated: string;
}

/** The client's id for a request: the watch makes one per request, and keeps it for a retry. */
export const CLIENT_ID = /^[A-Za-z0-9_-]{8,64}$/;
/** A spoken request, as the watch's link takes it. */
export const MAX_ASK_TEXT = 2000;
/** The result line the watch shows and says. */
export const LINE_MAX = 140;
/** A taf's own answer in a chat from the watch: longer than Coach's line, and cut the same way past it. */
export const TAF_LINE_MAX = 400;
/** A request's claims (its client id, its hand-over, its runs' refs) are kept a week, as a task Coach hands over is. */
export const ASK_KEPT_MS = 7 * 24 * 3600_000;
/**
 * The request itself, its place among the person's and its conversation's mark are kept as long as its Coach
 * conversation is (a run's chat, 30 days: tafdef's RETENTION_NS), so its watch day goes when the conversation does (W11).
 */
export const ASK_DAYS_KEPT_MS = RETENTION_NS / 1_000_000;
/** The recent requests a person's watch reads back: the newest, at most this many. */
export const RECENT_MAX = 20;
/** The requests a person's list keeps, newest first, for their watch days: past it the oldest go first. */
export const DAYS_MAX = 500;
/** By default the watch reads back the last day's. */
export const SINCE_DEFAULT_MS = 24 * 3600_000;

/** The tools a watch conversation is offered (kiff/agents' session `only`): read the tafs, hand one the request. */
export const WATCH_TOOLS = ["tafs_state", "taf_task", "read_skill"] as const;

export const watchKeys = {
  ask: (tenant: string, id: string) => `${tenant}#watch-ask#${id}`,
  /** Per person and client id: claimed once, so a retry finds the first request's id. */
  client: (tenant: string, person: string, client: string) => `${tenant}#watch-client#${person}#${client}`,
  /** A Coach conversation opened by the watch route: its request's id. The only watch mark there is. */
  session: (tenant: string, session: string) => `${tenant}#watch-session#${session}`,
  /** A run-ended note's ref for a watch conversation's Coach run: its request's id. */
  ref: (tenant: string, ref: string) => `${tenant}#watch-ref#${ref}`,
  /** A person's recent requests, newest first. */
  recent: (tenant: string, person: string) => `${tenant}#watch-recent#${person}`,
};

/**
 * A line the watch can show and say: one line of plain text, at most LINE_MAX characters. Markdown marks,
 * links' targets and control characters go; a longer line is cut at a word, with an ellipsis. Undefined when
 * nothing is left, and the state's own words stand in.
 */
export function checkedLine(raw: unknown, max = LINE_MAX): string | undefined {
  if (typeof raw !== "string") return undefined;
  let s = raw
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/https?:\/\/\S+/g, "")
    .replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2066-\u2069]/g, " ")
    .replace(/[*_`#>~|]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  if (!s) return undefined;
  if ([...s].length <= max) return s;
  const cut = [...s].slice(0, max - 1).join("");
  const at = cut.lastIndexOf(" ");
  s = (at > max / 2 ? cut.slice(0, at) : cut).replace(/[\s,;:.–-]+$/, "");
  return `${s}…`;
}

/**
 * A taf's own answer as the watch shows it: checked as Coach's line is (one line, no markdown, links' targets or
 * control characters), at most TAF_LINE_MAX, and whether there was more than that (the rest is on the phone).
 */
export function tafLine(raw: unknown): { line?: string; more: boolean } {
  const line = checkedLine(raw, TAF_LINE_MAX);
  return { ...(line ? { line } : {}), more: line !== undefined && line !== checkedLine(raw, Number.MAX_SAFE_INTEGER) };
}

/** The most of a taf's whole answer the watch is sent on "Read more": its paragraphs, checked, never more than 16 KB. */
export const FULL_MAX_BYTES = 16 * 1024;

/**
 * A taf's whole answer, for the watch's "Read more" (the owner's 20 Nov pick: never trimmed, shown partly, then expanded).
 * Each paragraph is checked as the line is (no markdown, links' targets or control characters) and none is cut; paragraphs
 * are kept apart by a blank line. Past FULL_MAX_BYTES of UTF-8 it's cut at a word, with an ellipsis. Undefined when nothing is left.
 */
export function fullAnswer(raw: unknown): string | undefined {
  if (typeof raw !== "string") return undefined;
  const paras = raw.split(/\n\s*\n/).map((p) => checkedLine(p, Number.MAX_SAFE_INTEGER)).filter((p): p is string => !!p);
  let s = paras.join("\n\n");
  if (!s) return undefined;
  if (Buffer.byteLength(s, "utf8") <= FULL_MAX_BYTES) return s;
  const chars = [...s];
  let bytes = 0, n = 0;
  while (n < chars.length && bytes + Buffer.byteLength(chars[n]!, "utf8") <= FULL_MAX_BYTES - 3) bytes += Buffer.byteLength(chars[n++]!, "utf8");
  const cut = chars.slice(0, n).join("");
  const at = cut.lastIndexOf(" ");
  s = (at > cut.length / 2 ? cut.slice(0, at) : cut).replace(/[\s,;:.–-]+$/, "");
  return `${s}…`;
}

/** The request as the phone and the watch read it, in their language. */
export function shownAsk(ask: Ask, lang: Lang): ShownAsk {
  return {
    id: ask.id, client_id: ask.client_id, state: ask.state,
    ...(ask.taf ? { taf: ask.taf } : {}),
    ...(ask.line ? { line: ask.line } : {}),
    ...(ask.more ? { more: true } : {}),
    ...(ask.held ? { held: true } : {}),
    // {name}: who holds the request now, its taf once there's one, else Coach (the same word in every language).
    words: wordsIn(lang).watch_ask[ask.state].replace("{name}", ask.taf?.name ?? "Coach"),
    at: ask.at, updated: ask.updated,
  };
}

/** A request's id. */
export function newAskID(random: () => string): string {
  return `wa_${random().replace(/[^A-Za-z0-9]/g, "").slice(0, 26)}`;
}

/** The request by id, the person's own only. */
export async function ownAsk(store: CoachStore, tenant: string, person: string, id: string): Promise<Ask | undefined> {
  if (!/^wa_[A-Za-z0-9]{1,26}$/.test(id)) return undefined;
  const ask = (await store.get(watchKeys.ask(tenant, id))) as Ask | undefined;
  return ask && ask.person === person ? ask : undefined;
}

/** Keeps the request, and its place among the person's recent ones, as long as its conversation is kept. */
export async function keepAsk(store: CoachStore, tenant: string, ask: Ask, now: Date): Promise<void> {
  const until = new Date(now.getTime() + ASK_DAYS_KEPT_MS);
  await store.put(watchKeys.ask(tenant, ask.id), ask, until);
  const recent = ((await store.get(watchKeys.recent(tenant, ask.person))) as string[] | undefined) ?? [];
  const ids = [ask.id, ...recent.filter((id) => id !== ask.id)].slice(0, DAYS_MAX);
  await store.put(watchKeys.recent(tenant, ask.person), ids, until);
}

/** The person's requests changed after `since`, newest first: of the RECENT_MAX last changed. */
export async function recentAsks(store: CoachStore, tenant: string, person: string, since: Date): Promise<Ask[]> {
  const ids = (((await store.get(watchKeys.recent(tenant, person))) as string[] | undefined) ?? []).slice(0, RECENT_MAX);
  const asks = await Promise.all(ids.map((id) => store.get(watchKeys.ask(tenant, id)) as Promise<Ask | undefined>));
  return asks.filter((a): a is Ask => !!a && a.person === person && Date.parse(a.updated) > since.getTime());
}

/** Every request the person's list still keeps, theirs alone, newest first: what their watch days read (W11). */
export async function ownAsks(store: CoachStore, tenant: string, person: string): Promise<Ask[]> {
  const ids = ((await store.get(watchKeys.recent(tenant, person))) as string[] | undefined) ?? [];
  const asks = await Promise.all(ids.map((id) => store.get(watchKeys.ask(tenant, id)) as Promise<Ask | undefined>));
  return asks.filter((a): a is Ask => !!a && a.person === person);
}

/** One request on a watch day (W11): what the person said, when, and where it went, as `GET /v1/watch/asks` words it. */
export interface WatchTurn {
  id: string;
  said: string;
  said_at: string;
  taf?: { id: string; name: string };
  state: AskState;
  line?: string;
  words: string;
  /** The request it replies to, listed just before it the same day. */
  reply_to?: string;
}

/** The day a request was made on, YYYY-MM-DD in the account's zone. */
export function askDay(ask: Ask, zone: string): string {
  return localOf(ask.at, zone).day;
}

/** The days with requests, newest first, each with how many. */
export function watchDaysOf(asks: Ask[], zone: string): Array<{ date: string; count: number }> {
  const count = new Map<string, number>();
  for (const a of asks) count.set(askDay(a, zone), (count.get(askDay(a, zone)) ?? 0) + 1);
  return [...count].map(([date, n]) => ({ date, count: n })).sort((a, b) => b.date.localeCompare(a.date));
}

/**
 * One day's turns, oldest first, each reply straight after the request it replies to (then by time), so an app can
 * show it under the one before. `reply_to` is sent only when that request is listed the same day.
 */
export function dayTurns(said: Array<{ ask: Ask; said: string }>, lang: Lang): WatchTurn[] {
  const byTime = [...said].sort((a, b) => Date.parse(a.ask.at) - Date.parse(b.ask.at) || a.ask.id.localeCompare(b.ask.id));
  const listed = new Set(byTime.map((t) => t.ask.id));
  const replies = new Map<string, typeof byTime>();
  for (const t of byTime) {
    const to = t.ask.reply_to;
    if (to && listed.has(to) && to !== t.ask.id) replies.set(to, [...(replies.get(to) ?? []), t]);
  }
  const out: WatchTurn[] = [];
  const placed = new Set<string>();
  const place = (t: (typeof byTime)[number]) => {
    if (placed.has(t.ask.id)) return;
    placed.add(t.ask.id);
    const shown = shownAsk(t.ask, lang);
    const to = t.ask.reply_to && listed.has(t.ask.reply_to) && t.ask.reply_to !== t.ask.id ? t.ask.reply_to : undefined;
    out.push({ id: shown.id, said: t.said, said_at: t.ask.at, ...(shown.taf ? { taf: shown.taf } : {}), state: shown.state,
      ...(shown.line ? { line: shown.line } : {}), words: shown.words, ...(to ? { reply_to: to } : {}) });
    for (const r of replies.get(t.ask.id) ?? []) place(r);
  };
  // A request whose own request is listed comes under it; any other starts where its time puts it.
  for (const t of byTime) if (!t.ask.reply_to || !listed.has(t.ask.reply_to) || t.ask.reply_to === t.ask.id) place(t);
  // A loop of replies (never made by the route) still lists each once, by time.
  for (const t of byTime) place(t);
  return out;
}

/** The request a Coach conversation was opened for by the watch route; none for any other conversation. */
export async function askOfSession(store: CoachStore, tenant: string, session: string | undefined): Promise<Ask | undefined> {
  if (!session) return undefined;
  const id = (await store.get(watchKeys.session(tenant, session))) as string | undefined;
  return id ? ((await store.get(watchKeys.ask(tenant, id))) as Ask | undefined) : undefined;
}

/**
 * TAFS_WATCH_ASK: "on" for every account; one account id, or several joined with "+", for those alone; else off
 * (docs/watch-ask.md). Never commas: infra/aws.sh passes tafs cloud's environment to Lambda as a comma-separated list.
 */
export function watchAskOn(v: string | undefined): boolean | string[] {
  const s = (v ?? "").trim();
  if (s === "on") return true;
  const ids = s.split("+").map((x) => x.trim()).filter((x) => /^[A-Za-z0-9_-]{1,64}$/.test(x) && x !== "off");
  return ids.length ? ids : false;
}
