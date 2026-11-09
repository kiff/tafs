import type { Fired, Run } from "./agents.js";
import { type Lang, plural, type When, wordsIn } from "./words/index.js";
import type { DeliveryView } from "./delivery.js";
import { type Health, healthLine } from "./health.js";
import type { Sent } from "./doors/model.js";

/**
 * Where a taf's work stands, as both apps show it (docs/reliability.md, "The taf's view"):
 * worked out here from its runs as kiff/agents keeps them, with every line in words and every
 * time already written in the account's time zone. An app formats nothing.
 */

/** A taf's status, the most pressing first. */
export type WorkStatus = "halted" | "needs_you" | "retrying" | "working" | "waiting_on_app" | "paused" | "idle";

/** What else says where a taf stands: halted, its schedule off, no schedule at all, its next run. */
export interface TafNow { paused?: boolean; halted?: boolean; unscheduled?: boolean; next?: string | null }

/** What the home list shows of a taf's work. */
export interface WorkSummary {
  status: WorkStatus;
  line: string;
  /** How many of its recent requests need the owner. */
  needs_you: number;
}

/** One request as the taf's page shows it, newest first. */
export interface RequestView {
  id: string;
  at: string;
  state: "done" | "working" | "retrying" | "needs_you" | "waiting" | "stopped" | "failed";
  line: string;
  /** What a waiting run waits for, in the connection's words, when it waits for a vendor's event (the slow-tools design). */
  waiting_for?: string;
  /** Why a failed run stopped, as kiff/agents' code (`runFailure`), when it has one: its words are the line. */
  code?: string;
  /** Where its result went: the door's reply as far as tafs cloud sent it, and each place of the taf's own (delivery.ts). */
  deliveries: Array<{ place: "door_reply"; state: Sent["status"]; line: string; send_again?: true } | DeliveryView>;
}

/**
 * A firing that didn't end in finished work, as the taf's page shows it: one its schedule skipped (nothing was
 * run), one whose check couldn't read the app, and one whose run didn't finish (its work goes to the next run,
 * or was dropped). An app that doesn't know a `why` draws its `words` plainly.
 */
export interface SkippedView {
  due: string;
  why: "missed" | "busy" | "switching" | "check_failed" | "retrying" | "unfinished";
  words: string;
}

/** The firings the page lists, with why each is there. A halt's and a credits skip have their own words elsewhere. */
const SKIPPED_WHY: Record<string, SkippedView["why"]> = {
  skipped_missed: "missed", skipped_busy: "busy", skipped_switching: "switching",
  failed_check: "check_failed", failed_retrying: "retrying", failed_unfinished: "unfinished",
};

/** How many skipped firings the page shows, newest first, and from how far back. */
const SKIPPED_SHOWN = 5;
const SKIPPED_DAYS = 7;

/**
 * The firings of the taf's schedule that didn't end in finished work lately, in words: one that came too
 * late, while its last run was still working or while the taf switched versions, one whose check couldn't
 * read the app, and one whose run didn't finish.
 */
export function skippedOf(fired: Fired[], zone: string, now: Date, lang: Lang = "en"): SkippedView[] {
  const w = wordsIn(lang);
  const since = now.getTime() - SKIPPED_DAYS * 86_400_000;
  return fired
    .filter((f) => SKIPPED_WHY[f.outcome] !== undefined && new Date(f.due).getTime() >= since)
    .slice(0, SKIPPED_SHOWN)
    .map((f) => {
      const why = SKIPPED_WHY[f.outcome]!;
      const when = whenIn(f.due, zone, now, lang);
      const words = {
        missed: w.work_skipped_late, busy: w.work_skipped_busy, switching: w.work_skipped_switching,
        check_failed: w.work_check_failed, retrying: w.work_run_retrying, unfinished: w.work_run_unfinished,
      }[why](when);
      return { due: new Date(f.due).toISOString(), why, words };
    });
}

/** A needs_you older than this no longer counts on the list: it stays on the taf's page. */
const NEEDS_YOU_DAYS = 7;

/** A time as the owner reads it: "14:20" today, "Fri 14:20" another day, in their zone. */
export function timeIn(at: string | Date, zone: string, now: Date, lang: Lang = "en"): string {
  const d = typeof at === "string" ? new Date(at) : at;
  const tz = validZone(zone);
  const hm = new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(d);
  const day = (x: Date) => new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).format(x);
  if (day(d) === day(now)) return hm;
  // The weekday in the person's language ("Fri", "Fr.", "ven."): the time itself reads the same in all eight.
  const wd = new Intl.DateTimeFormat(lang === "en" ? "en-GB" : lang, { timeZone: tz, weekday: "short" }).format(d);
  return `${wd} ${hm}`;
}

/** A time as a line says it in the person's language: hours and minutes, and the weekday when it isn't today. */
export function whenIn(at: string | Date, zone: string, now: Date, lang: Lang = "en"): When {
  const d = typeof at === "string" ? new Date(at) : at;
  const tz = validZone(zone);
  const hm = new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(d);
  const day = (x: Date) => new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).format(x);
  if (day(d) === day(now)) return { hm };
  return { hm, day: new Intl.DateTimeFormat(lang === "en" ? "en-GB" : lang, { timeZone: tz, weekday: "short" }).format(d) };
}

function validZone(zone: string): string {
  try {
    new Intl.DateTimeFormat("en-GB", { timeZone: zone || "UTC" });
    return zone || "UTC";
  } catch {
    return "UTC";
  }
}

/**
 * A failed run's code and its words for people, when kiff/agents gave both (`code`, `error`): passed on to the
 * apps as they are, so a new code needs no tafs release. A run with no code has none: its error text is never shown
 * as words for people where a code would be. The words are one line, at most 400 characters.
 */
export function runFailure(r: { status?: unknown; error?: unknown; code?: unknown }, lang: Lang = "en"): { code: string; words: string } | undefined {
  if (r.status !== "ERROR" || typeof r.code !== "string" || !/^[a-z_]{1,40}$/.test(r.code) || typeof r.error !== "string") return undefined;
  // A code tafs cloud knows is worded from its own catalog, in the person's language; any other passes
  // kiff/agents' words through, so a new code needs no tafs release (docs/languages.md).
  const own = CODE_WORDS[r.code];
  if (own) return { code: r.code, words: wordsIn(lang)[own] };
  const flat = r.error.replace(/\s+/g, " ").trim();
  if (!flat) return undefined;
  return { code: r.code, words: flat.length > 400 ? `${flat.slice(0, 399)}…` : flat };
}

/** The run codes tafs cloud words itself, by catalog key. */
const CODE_WORDS: Record<string, "code_ai_unavailable"> = { ai_unavailable: "code_ai_unavailable" };

/** One run as a request: its state and the words for it. */
export function requestOf(r: Run, zone: string, now: Date, sent: Sent[] = [], again = new Set<string>(), own: DeliveryView[] = [], lang: Lang = "en"): RequestView {
  const w = wordsIn(lang);
  const at = (t: string | Date) => whenIn(t, zone, now, lang);
  // The newest stamp per run: a reply sent again replaces the one that wasn't.
  const last = sent.find((s) => s.run === r.id);
  const deliveries: RequestView["deliveries"] = last ? [{
    place: "door_reply" as const, state: last.status,
    line: last.status === "sent" ? w.work_replied_at(at(last.at)) : w.work_reply_not_sent(last.why ?? w.work_provider_refused),
    ...(last.status === "not_sent" && again.has(r.id) ? { send_again: true as const } : {}),
  }] : [];
  deliveries.push(...own);
  const base = { id: r.id, at: r.created, deliveries };
  if (r.state === "needs_you") return { ...base, state: "needs_you", line: r.reason ?? w.work_needs_you };
  if (r.state === "retrying") {
    const of = r.try && r.of ? w.work_try_of(r.try, r.of) : "";
    return { ...base, state: "retrying", line: r.next_at ? w.work_retry_at(at(r.next_at), of) : w.work_retry_soon(of) };
  }
  switch (r.status) {
    case "PENDING":
    case "RUNNING":
      return { ...base, state: "working", line: w.work_working };
    case "PAUSED": {
      if (r.requirements?.some((q) => q.exception && !q.answered)) return { ...base, state: "waiting", line: w.work_waiting_approval };
      // An open card is the owner's to answer: it comes before any wait on the app, so an owner's question is never drawn as the app's.
      if (r.requirements?.some((q) => q.kind === "card" && !q.answered)) return { ...base, state: "waiting", line: w.work_waiting_answer };
      const wait = r.requirements?.find((q) => q.kind === "event" && !q.answered && q.wait)?.wait;
      if (wait) {
        const words = wait.words?.trim() || w.work_waiting_event(wait.event);
        return { ...base, state: "waiting", line: words, waiting_for: words };
      }
      return { ...base, state: "waiting", line: w.work_waiting_answer };
    }
    case "COMPLETED":
      // A run cut off at its turn or cost cap ended without finishing: it stopped, and says why (never "Done").
      if (r.stop === "turn_cap") return { ...base, state: "stopped", line: w.work_capped_turns(at(r.updated)) };
      if (r.stop === "cost_cap") return { ...base, state: "stopped", line: w.work_capped_cost(at(r.updated)) };
      // Done only when it answered (or a run from before stops were kept): any other stop, one tafs doesn't know
      // included (out of room mid-answer, a refused turn), reads as stopped, never as success.
      if (r.stop && r.stop !== "answered") return { ...base, state: "stopped", line: w.work_stopped_at(at(r.updated)) };
      return { ...base, state: "done", line: w.work_done_at(at(r.updated)) };
    case "CANCELLED":
      return { ...base, state: "stopped", line: w.work_stopped };
    default: {
      const coded = runFailure(r, lang);
      if (coded) return { ...base, state: "failed", line: coded.words, code: coded.code };
      return { ...base, state: "failed", line: r.error ? w.work_failed_because(r.error) : w.work_failed };
    }
  }
}

/** A taf's status and line on the list, from its recent runs, newest first. */
export function summaryOf(runs: Run[], how: boolean | TafNow, zone: string, now: Date, lang: Lang = "en", health?: Health): WorkSummary {
  const w = wordsIn(lang);
  const { paused = false, halted = false, unscheduled = false, next = null } = typeof how === "boolean" ? { paused: how } : how;
  // Halted stops everything, its holds included: that comes first.
  if (halted) return { status: "halted", line: w.work_halted, needs_you: 0 };
  const recent = runs.filter((r) => now.getTime() - Date.parse(r.updated) < NEEDS_YOU_DAYS * 86_400_000);
  const needs = recent.filter((r) => r.state === "needs_you" || (r.status === "PAUSED" && r.requirements?.some((q) => q.exception && !q.answered)));
  if (needs.length) {
    const first = requestOf(needs[0]!, zone, now, [], undefined, [], lang);
    return { status: "needs_you", line: needs.length === 1 ? first.line : w.work_needs_you_count(needs.length, plural(lang, needs.length)), needs_you: needs.length };
  }
  const retrying = runs.find((r) => r.state === "retrying");
  if (retrying) return { status: "retrying", line: requestOf(retrying, zone, now, [], undefined, [], lang).line, needs_you: 0 };
  if (runs.some((r) => r.status === "RUNNING" || r.status === "PENDING")) return { status: "working", line: w.work_working, needs_you: 0 };
  // A run waiting for a connected app's event (the slow-tools design): the taf isn't idle, and the line is the
  // connection's words for the wait. A run that also needs the owner was counted above. Named apart from the
  // home item's `state: "waiting"`, which means a call held for the owner.
  const waiting = runs.find((r) => {
    const open = (r.requirements ?? []).filter((q) => !q.answered);
    return r.status === "PAUSED" && open.length > 0 && open.every((q) => q.kind === "event" && q.wait);
  });
  if (waiting) return { status: "waiting_on_app", line: requestOf(waiting, zone, now, [], undefined, [], lang).line, needs_you: 0 };
  if (paused) return { status: "paused", line: w.work_paused, needs_you: 0 };
  // A taf that isn't healthy says why, where an idle one would say its next run: what's happening now, and
  // what waits for the owner, came first.
  if (health && health.level !== "healthy") return { status: "idle", line: healthLine(health.reasons[0]!, lang), needs_you: 0 };
  if (unscheduled) return { status: "idle", line: w.work_from_chat, needs_you: 0 };
  if (next) return { status: "idle", line: w.work_next_run(whenIn(next, zone, now, lang)), needs_you: 0 };
  const last = runs.find((r) => r.status === "COMPLETED");
  // The last one cut off at a cap says so, as its request does.
  if (last && last.stop && last.stop !== "answered") return { status: "idle", line: requestOf(last, zone, now, [], undefined, [], lang).line, needs_you: 0 };
  return { status: "idle", line: last ? w.work_last_done(whenIn(last.updated, zone, now, lang)) : w.work_nothing_yet, needs_you: 0 };
}

/** The taf's page: counts per state, and the recent requests with what was sent. */
export function workOf(runs: Run[], zone: string, now: Date, sent: Sent[], again = new Set<string>(), own = new Map<string, DeliveryView[]>(), lang: Lang = "en"): { counts: Record<RequestView["state"], number>; requests: RequestView[] } {
  const requests = runs.map((r) => requestOf(r, zone, now, sent, again, own.get(r.id) ?? [], lang));
  const counts = { done: 0, working: 0, retrying: 0, needs_you: 0, waiting: 0, stopped: 0, failed: 0 };
  for (const q of requests) counts[q.state]++;
  return { counts, requests };
}
