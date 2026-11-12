import type { Fired, Run } from "./agents.js";
import { type Lang, plural, type When, wordsIn } from "./words/index.js";
import type { DeliveryView } from "./delivery.js";
import { type Health, healthLine } from "./health.js";
import type { Sent } from "./doors/model.js";
import { isYourStep, type YourStepView, yourStepsOf } from "./yourstep.js";

/**
 * Where a taf's work stands, as both apps show it (docs/reliability.md, "The taf's view"):
 * worked out here from its runs as kiff/agents keeps them, with every line in words and every
 * time already written in the account's time zone. An app formats nothing.
 */

/** A taf's status, the most pressing first. */
export type WorkStatus = "halted" | "needs_you" | "retrying" | "working" | "waiting_on_app" | "paused" | "idle";

/** What else says where a taf stands: halted, its schedule off, no schedule at all, its next run. */
export interface TafNow { paused?: boolean; halted?: boolean; unscheduled?: boolean; next?: string | null; fired?: Fired[] }

/** What the home list shows of a taf's work. */
export interface WorkSummary {
  status: WorkStatus;
  line: string;
  /** How many of its recent requests need the owner. */
  needs_you: number;
  /**
   * Only when its line says something went wrong (its last check couldn't read, its last run stopped at a cap or
   * failed, its health): an app draws that, never "Working" or the next run, which read as all's well.
   */
  attention?: true;
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
  /** A step the taf handed its owner, waiting for "Done" or "Skip it" (yourstep.ts): its line is the step's. */
  your_step?: YourStepView;
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
  why: "missed" | "busy" | "switching" | "check_failed" | "retrying" | "unfinished" | "too_large" | "credits";
  words: string;
}

/**
 * The firings the page lists, with why each is there. A halt's skip has its own words elsewhere. Work too big
 * for one run, and work waiting for credits, are listed too: no run started, and the owner can fix either.
 */
const SKIPPED_WHY: Record<string, SkippedView["why"]> = {
  skipped_missed: "missed", skipped_busy: "busy", skipped_switching: "switching",
  failed_check: "check_failed", failed_retrying: "retrying", failed_unfinished: "unfinished",
  skipped_too_large: "too_large", skipped_credits: "credits",
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
        too_large: w.work_skipped_too_large, credits: w.work_skipped_credits,
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

/**
 * kiff/agents' words for how a run failed (runner/runner.go's Failed*, run/runs.go's lease and claim errors): a
 * fixed set, in English, saying what failed and never why. tafs cloud says each in the person's language. Any
 * other text is kept for an English reader ("It failed: …") and reads as a plain "It failed" in any other
 * language: never English inside another language's screen.
 */
const FAILED_WORDS: Record<string, "run_failed_error" | "run_failed_time" | "run_failed_start" | "run_failed_switching" | "run_failed_access" | "run_failed_halted"
  | "run_needs_you_restarts" | "run_needs_you_never_started" | "run_failed_after_held"> = {
  "the run failed": "run_failed_error",
  "its lease ran out: the invocation crashed or timed out": "run_failed_error",
  "the run reached its time limit": "run_failed_time",
  "the agent could not be built": "run_failed_start",
  "the run could not be started": "run_failed_start",
  "no invocation claimed it in time": "run_failed_start",
  "the agent is switching versions, so the run didn't start": "run_failed_switching",
  "the person who asked may no longer use this agent, so the run stopped": "run_failed_access",
  "the agent is halted, so the run didn't go on": "run_failed_halted",
  "it could not be started in the background": "run_failed_start",
  "it could not continue once KIFF resolved its held calls": "run_failed_after_held",
  // A run past its last try, said as its error and as the reason it needs you (run/runs.go, NeedsYou, NeverStarted).
  "It stopped 3 times while it worked, and was restarted each time from its last safe step. It needs you to look at it.": "run_needs_you_restarts",
  "It couldn't start: no invocation picked it up after 3 tries. It needs you to look at it.": "run_needs_you_never_started",
};

/**
 * Why a run needs the owner, in the person's language when it's one of kiff/agents' own reasons (a run past its
 * last try); any other reason is shown as it came, and none reads as "Needs you".
 */
export function needsYouLine(reason: string | null | undefined, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  const text = (reason ?? "").trim();
  const key = FAILED_WORDS[text];
  return key ? w[key] : text || w.work_needs_you;
}

/** A failed run's line in the person's language, from kiff/agents' words for it. */
export function failedLine(error: string | null | undefined, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  const text = (error ?? "").trim();
  const key = FAILED_WORDS[text];
  if (key) return w[key];
  return text && lang === "en" ? w.work_failed_because(text) : w.work_failed;
}

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
  if (r.state === "needs_you") return { ...base, state: "needs_you", line: needsYouLine(r.reason, lang) };
  if (r.state === "retrying") {
    const of = r.try && r.of ? w.work_try_of(r.try, r.of) : "";
    return { ...base, state: "retrying", line: r.next_at ? w.work_retry_at(at(r.next_at), of) : w.work_retry_soon(of) };
  }
  switch (r.status) {
    case "PENDING":
    case "RUNNING":
      return { ...base, state: "working", line: w.work_working };
    case "PAUSED": {
      // A step handed to the owner reads as theirs to do, never as an approval: it comes first.
      const step = yourStepsOf(r, lang)[0];
      if (step) return { ...base, state: "waiting", line: step.words.line, your_step: step };
      if (r.requirements?.some((q) => q.exception && !q.answered && !isYourStep(q))) return { ...base, state: "waiting", line: w.work_waiting_approval };
      // An open card is the owner's to answer: it comes before any wait on the app, so an owner's question is never drawn as the app's.
      if (r.requirements?.some((q) => q.kind === "card" && !q.answered)) return { ...base, state: "waiting", line: w.work_waiting_answer };
      const wait = r.requirements?.find((q) => q.kind === "event" && !q.answered && q.wait)?.wait;
      if (wait) {
        const words = wait.words?.trim() || w.work_waiting_event(wait.event);
        return { ...base, state: "waiting", line: words, waiting_for: words };
      }
      return { ...base, state: "waiting", line: w.work_waiting_answer };
    }
    case "COMPLETED": {
      // A hand-off the receiving taf didn't finish (delivery.ts): the run's own work is done, and its
      // managers have something to do, so it reads as theirs, never as "Done".
      const dead = own.find((d) => d.needs_you);
      if (dead) return { ...base, state: "needs_you", line: dead.line };
      // A run cut off at its turn or cost cap ended without finishing: it stopped, and says why (never "Done").
      if (r.stop === "turn_cap") return { ...base, state: "stopped", line: w.work_capped_turns(at(r.updated)) };
      if (r.stop === "cost_cap") return { ...base, state: "stopped", line: w.work_capped_cost(at(r.updated)) };
      // Its reply was cut off at its length limit, or the AI declined to go on: stopped, and says which.
      if (r.stop === "max_token") return { ...base, state: "stopped", line: w.work_stopped_out_of_room(at(r.updated)) };
      if (r.stop === "refused") return { ...base, state: "stopped", line: w.work_stopped_declined(at(r.updated)) };
      // Done only when it answered (or a run from before stops were kept): any other stop, one tafs doesn't know
      // included, reads as stopped, never as success.
      if (r.stop && r.stop !== "answered") return { ...base, state: "stopped", line: w.work_stopped_at(at(r.updated)) };
      return { ...base, state: "done", line: w.work_done_at(at(r.updated)) };
    }
    case "CANCELLED":
      return { ...base, state: "stopped", line: w.work_stopped };
    default: {
      const coded = runFailure(r, lang);
      if (coded) return { ...base, state: "failed", line: coded.words, code: coded.code };
      return { ...base, state: "failed", line: failedLine(r.error, lang) };
    }
  }
}

/** A taf's status and line on the list, from its recent runs, newest first. */
/** `dead`: the lines of its hand-offs the receiving taf didn't finish (delivery.ts), each one the managers' to send again. */
export function summaryOf(runs: Run[], how: boolean | TafNow, zone: string, now: Date, lang: Lang = "en", health?: Health, dead: string[] = []): WorkSummary {
  const w = wordsIn(lang);
  const { paused = false, halted = false, unscheduled = false, next = null, fired = [] } = typeof how === "boolean" ? { paused: how } : how;
  // Halted stops everything, its holds included: that comes first.
  if (halted) return { status: "halted", line: w.work_halted, needs_you: 0 };
  const recent = runs.filter((r) => now.getTime() - Date.parse(r.updated) < NEEDS_YOU_DAYS * 86_400_000);
  const needs = recent.filter((r) => r.state === "needs_you" || (r.status === "PAUSED" && r.requirements?.some((q) => q.exception && !q.answered)));
  const asking = needs.length + dead.length;
  if (asking) {
    const first = needs[0] ? requestOf(needs[0], zone, now, [], undefined, [], lang).line : dead[0]!;
    return { status: "needs_you", line: asking === 1 ? first : w.work_needs_you_count(asking, plural(lang, asking)), needs_you: asking };
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
  // What went wrong last comes before the next run, which would read as all's well: its latest firing's check
  // that couldn't read first (a Run now's too), then its health, then its last run stopped at a cap or failed.
  const newest = fired[0];
  if (newest?.outcome === "failed_check") return { status: "idle", line: w.work_check_failed(whenIn(newest.due, zone, now, lang)), needs_you: 0, attention: true };
  if (health && health.level !== "healthy") return { status: "idle", line: healthLine(health.reasons[0]!, lang), needs_you: 0, attention: true };
  const ended = runs.find((r) => r.status === "COMPLETED" || r.status === "ERROR");
  if (ended && !unscheduled && (ended.status === "ERROR" || (ended.stop && ended.stop !== "answered"))) {
    return { status: "idle", line: requestOf(ended, zone, now, [], undefined, [], lang).line, needs_you: 0, attention: true };
  }
  if (unscheduled) return { status: "idle", line: w.work_from_chat, needs_you: 0 };
  if (next) return { status: "idle", line: w.work_next_run(whenIn(next, zone, now, lang)), needs_you: 0 };
  const last = runs.find((r) => r.status === "COMPLETED");
  // The last one cut off at a cap says so, as its request does.
  if (last && last.stop && last.stop !== "answered") return { status: "idle", line: requestOf(last, zone, now, [], undefined, [], lang).line, needs_you: 0, attention: true };
  return { status: "idle", line: last ? w.work_last_done(whenIn(last.updated, zone, now, lang)) : w.work_nothing_yet, needs_you: 0 };
}

/** The taf's page: counts per state, and the recent requests with what was sent. */
export function workOf(runs: Run[], zone: string, now: Date, sent: Sent[], again = new Set<string>(), own = new Map<string, DeliveryView[]>(), lang: Lang = "en"): { counts: Record<RequestView["state"], number>; requests: RequestView[] } {
  const requests = runs.map((r) => requestOf(r, zone, now, sent, again, own.get(r.id) ?? [], lang));
  const counts = { done: 0, working: 0, retrying: 0, needs_you: 0, waiting: 0, stopped: 0, failed: 0 };
  for (const q of requests) counts[q.state]++;
  return { counts, requests };
}
