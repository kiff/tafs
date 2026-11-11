/**
 * A taf's oldest waiting case (docs/reliability.md, "Its oldest waiting case"), and the owner's promise:
 * how long a case may wait before they're told. Read from what kiff/agents records, each time, with no
 * session: its runs and its schedule's firings. Nothing is stored but the promise, on the taf.
 */
import type { Fired, Run } from "./agents.js";
import { type Lang, wordsIn } from "./words/index.js";

/** Firings whose found work couldn't start and waits: for credits, for the run before it, or too late. */
const WAITING_FIRING = new Set(["skipped_credits", "skipped_busy", "skipped_missed"]);
/** Firings that took what was found: a run started, or the check found nothing new. They end a wait. */
const TOOK_FIRING = new Set(["run", "quiet"]);
/** The longest promise an owner may set, in hours: a week. */
export const PROMISE_MAX_HOURS = 168;

/** Since when the oldest case has waited, and for what: the owner's answer, or a run to start. */
export interface Waiting {
  since: string;
  kind: "held" | "work";
}

/** What the apps get: since when, in words, and whether it's past the owner's promise. */
export interface WaitingView {
  since: string;
  words: string;
  late: boolean;
}

/**
 * The oldest case the taf has waiting, or undefined when none does:
 *   - a run paused for its owner on a call KIFF holds, unanswered, since the run began: kiff/agents moves a
 *     run's `updated` on every change, a partial answer or a note's retry among them, so a case that still
 *     waits would look younger, and be alerted again. It over-states the wait by the minutes the run worked
 *     before it paused, never under-states it;
 *   - a run past its last try (needs_you), since it stopped: such a run moves only on retries of its note;
 *   - work its schedule found but couldn't start (credits, busy, missed), since the first such firing after
 *     the last one that ran or was quiet. Work too big for one run was set aside, not kept: health says it.
 */
export function oldestWaiting(runs: Run[], fired: Fired[]): Waiting | undefined {
  const held = runs
    .filter((r) => r.state === "needs_you" || (r.status === "PAUSED" && r.requirements?.some((q) => q.exception && !q.answered)))
    .map((r) => (r.status === "PAUSED" ? r.created : r.updated))
    .filter((at) => !Number.isNaN(Date.parse(at)));
  let work: string | undefined;
  for (const f of fired) {
    if (TOOK_FIRING.has(f.outcome)) break;
    if (WAITING_FIRING.has(f.outcome)) work = f.due;
  }
  const candidates: Waiting[] = [...held.map((since) => ({ since, kind: "held" as const })), ...(work ? [{ since: work, kind: "work" as const }] : [])];
  if (!candidates.length) return undefined;
  return candidates.reduce((a, b) => (Date.parse(b.since) < Date.parse(a.since) ? b : a));
}

/** A duration as the owner reads it: "40 min", "5 h", "2 days", in their language. */
export function durationIn(ms: number, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  const minutes = Math.max(1, Math.floor(ms / 60_000));
  if (minutes < 60) return w.waiting_minutes(minutes);
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return w.waiting_hours(hours);
  return w.waiting_days(Math.floor(hours / 24));
}

/** Whether the oldest case is past the promise: none set, never. */
export function late(waiting: Waiting, promiseHours: number | undefined, now: Date): boolean {
  return promiseHours !== undefined && now.getTime() - Date.parse(waiting.since) > promiseHours * 3600_000;
}

/** The oldest waiting case in words: how long it has waited, and past which promise when it's late. */
export function waitingView(waiting: Waiting, promiseHours: number | undefined, now: Date, lang: Lang = "en"): WaitingView {
  const w = wordsIn(lang);
  const age = durationIn(now.getTime() - Date.parse(waiting.since), lang);
  const isLate = late(waiting, promiseHours, now);
  return {
    since: new Date(waiting.since).toISOString(),
    words: isLate ? w.waiting_late(age, durationIn(promiseHours! * 3600_000, lang)) : w.waiting_line(age),
    late: isLate,
  };
}

/** The alert for a case past the promise: the taf's name, how long, and the promise. */
export function lateAlert(taf: string, waiting: Waiting, promiseHours: number, now: Date, lang: Lang = "en"): { title: string; body: string } {
  const w = wordsIn(lang);
  return { title: taf, body: w.waiting_alert(durationIn(now.getTime() - Date.parse(waiting.since), lang), durationIn(promiseHours * 3600_000, lang)) };
}
