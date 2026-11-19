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

import type { CoachStore } from "./coach.js";
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
/** A request and what's known of it are kept a week, as a task Coach hands over is. */
export const ASK_KEPT_MS = 7 * 24 * 3600_000;
/** The recent requests a person's watch reads back: the newest, at most this many. */
export const RECENT_MAX = 20;
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
export function checkedLine(raw: unknown): string | undefined {
  if (typeof raw !== "string") return undefined;
  let s = raw
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/https?:\/\/\S+/g, "")
    .replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2066-\u2069]/g, " ")
    .replace(/[*_`#>~|]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  if (!s) return undefined;
  if ([...s].length <= LINE_MAX) return s;
  const cut = [...s].slice(0, LINE_MAX - 1).join("");
  const at = cut.lastIndexOf(" ");
  s = (at > LINE_MAX / 2 ? cut.slice(0, at) : cut).replace(/[\s,;:.–-]+$/, "");
  return `${s}…`;
}

/** The request as the phone and the watch read it, in their language. */
export function shownAsk(ask: Ask, lang: Lang): ShownAsk {
  return {
    id: ask.id, client_id: ask.client_id, state: ask.state,
    ...(ask.taf ? { taf: ask.taf } : {}),
    ...(ask.line ? { line: ask.line } : {}),
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

/** Keeps the request, and its place among the person's recent ones. */
export async function keepAsk(store: CoachStore, tenant: string, ask: Ask, now: Date): Promise<void> {
  const until = new Date(now.getTime() + ASK_KEPT_MS);
  await store.put(watchKeys.ask(tenant, ask.id), ask, until);
  const recent = ((await store.get(watchKeys.recent(tenant, ask.person))) as string[] | undefined) ?? [];
  const ids = [ask.id, ...recent.filter((id) => id !== ask.id)].slice(0, RECENT_MAX);
  await store.put(watchKeys.recent(tenant, ask.person), ids, until);
}

/** The person's requests changed after `since`, newest first. */
export async function recentAsks(store: CoachStore, tenant: string, person: string, since: Date): Promise<Ask[]> {
  const ids = ((await store.get(watchKeys.recent(tenant, person))) as string[] | undefined) ?? [];
  const asks = await Promise.all(ids.map((id) => store.get(watchKeys.ask(tenant, id)) as Promise<Ask | undefined>));
  return asks.filter((a): a is Ask => !!a && a.person === person && Date.parse(a.updated) > since.getTime());
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
