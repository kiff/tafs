/**
 * A taf's health over its recent work (docs/reliability.md): healthy, at risk or failing, with the reasons,
 * from what kiff/agents already records of its runs and its schedule's firings. Nothing is stored: it's
 * read again each time, so a taf never stays failing on old history.
 */
import type { Fired, Run } from "./agents.js";
import { type Lang, wordsIn } from "./words/index.js";

export type HealthLevel = "healthy" | "at_risk" | "failing";

/** Why a taf isn't healthy. `n` of `of` where it counts; `since` is when the spell it names began. */
export interface HealthReason {
  code: "dropped" | "streak" | "check_failing" | "all_failed" | "capped" | "failed_some" | "skipped" | "retrying" | "streak_one" | "check_failing_one";
  level: Exclude<HealthLevel, "healthy">;
  n?: number;
  of?: number;
  since?: string;
}

export interface Health { level: HealthLevel; reasons: HealthReason[] }

/** What the apps get: the level, and each reason as a line that says what happened and what to do. */
export interface HealthView { level: HealthLevel; lines: string[] }

/** How far back health looks, and how many of each. Older work has stopped counting. */
export const HEALTH_DAYS = 7;
export const HEALTH_RUNS = 10;
export const HEALTH_FIRINGS = 20;
/** This many runs failed in a row: failing. This many in a row at the schedule: failing. */
export const FAILED_IN_A_ROW = 3;
export const STREAK_FAILING = 2;
/** This many of the window: at risk. */
export const CAPPED_AT_RISK = 2;
export const FAILED_AT_RISK = 2;
export const SKIPPED_AT_RISK = 3;

/** A run cut short by a limit: its turns, its cost, or its reply running out of room. */
const CAPS = new Set(["turn_cap", "cost_cap", "max_token"]);
/** A firing that failed: nothing started, its check couldn't read the app, or its run dropped its work. */
const FAILED_FIRING = new Set(["failed_unworked", "failed_check", "failed_unfinished"]);
/** A firing that worked: a run started, or its check read the app and found nothing new or nothing it could take. */
const GOOD_FIRING = new Set(["run", "quiet", "skipped_too_large"]);
/** A firing skipped because the taf couldn't keep up: it came too late, or its last run was still working. */
const SKIPPED_FIRING = new Set(["skipped_missed", "skipped_busy"]);

/**
 * Whether a run ended, for its health: it finished or it failed. One paused for its owner, one its owner
 * stopped, and one tafs already shows as retrying or as needing them aren't counted at all.
 */
function ended(r: Run): boolean {
  return (r.status === "ERROR" || r.status === "COMPLETED") && r.state !== "retrying" && r.state !== "needs_you";
}

/** Whether a run that ended is a failure: it failed, or a turn of it was declined. */
function failed(r: Run): boolean {
  return r.status === "ERROR" || (r.status === "COMPLETED" && r.stop === "refused");
}

/**
 * The taf's health from its recent runs and its schedule's recent firings, both newest first. Only the
 * last HEALTH_DAYS count. Skips for a halt, a version switch or credits, a run paused for its owner and
 * one its owner stopped never count, and neither does an outcome this code doesn't know.
 */
export function healthOf(runs: Run[], fired: Fired[], now: Date): Health {
  const since = now.getTime() - HEALTH_DAYS * 86_400_000;
  const reasons: HealthReason[] = [];

  // Its schedule: the failures in a row at its newest firings, until one that worked.
  const firings = fired.filter((f) => new Date(f.due).getTime() >= since).slice(0, HEALTH_FIRINGS);
  const streak: Fired[] = [];
  let retrying = false;
  for (const f of firings) {
    if (GOOD_FIRING.has(f.outcome)) break;
    if (FAILED_FIRING.has(f.outcome)) streak.push(f);
    // A run that didn't finish, whose work goes to the next run: not a failure, and the failures before it
    // are another spell. It's still waiting to go again when nothing has failed or worked since.
    else if (f.outcome === "failed_retrying") { retrying = streak.length === 0; break; }
    // Anything else (a skip, an outcome not known here) neither fails nor recovers.
  }
  if (streak.length) {
    const began = streak[streak.length - 1]!.due;
    const newest = streak[0]!.outcome;
    if (newest === "failed_unfinished") reasons.push({ code: "dropped", level: "failing", since: began });
    else if (newest === "failed_check") reasons.push(streak.length >= STREAK_FAILING ? { code: "check_failing", level: "failing", n: streak.length, since: began } : { code: "check_failing_one", level: "at_risk", since: began });
    else reasons.push(streak.length >= STREAK_FAILING ? { code: "streak", level: "failing", n: streak.length, since: began } : { code: "streak_one", level: "at_risk", since: began });
  }
  if (retrying) reasons.push({ code: "retrying", level: "at_risk" });
  const skipped = firings.filter((f) => SKIPPED_FIRING.has(f.outcome)).length;
  if (skipped >= SKIPPED_AT_RISK) reasons.push({ code: "skipped", level: "at_risk", n: skipped, of: firings.length });

  // Its runs that ended, whatever started them.
  const recent = runs.filter((r) => ended(r) && Date.parse(r.updated) >= since).slice(0, HEALTH_RUNS);
  let inARow = 0;
  while (inARow < recent.length && failed(recent[inARow]!)) inARow++;
  const failures = recent.filter(failed).length;
  if (inARow >= FAILED_IN_A_ROW) reasons.push({ code: "all_failed", level: "failing", n: inARow, since: recent[inARow - 1]!.updated });
  else if (failures >= FAILED_AT_RISK) reasons.push({ code: "failed_some", level: "at_risk", n: failures, of: recent.length });
  const capped = recent.filter((r) => r.status === "COMPLETED" && CAPS.has(r.stop ?? "")).length;
  if (capped >= CAPPED_AT_RISK) reasons.push({ code: "capped", level: "at_risk", n: capped, of: recent.length });

  reasons.sort((a, b) => Number(b.level === "failing") - Number(a.level === "failing"));
  return { level: reasons.some((r) => r.level === "failing") ? "failing" : reasons.length ? "at_risk" : "healthy", reasons };
}

/** What happened, in words, with no "what to do": the line the list shows, and the alert's start. */
export function healthLine(r: HealthReason, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  switch (r.code) {
    case "dropped": return w.health_dropped;
    case "streak": return w.health_streak(r.n ?? STREAK_FAILING);
    case "streak_one": return w.health_streak_one;
    case "check_failing": return w.health_check_failing(r.n ?? STREAK_FAILING);
    case "check_failing_one": return w.health_check_failing_one;
    case "all_failed": return w.health_all_failed(r.n ?? FAILED_IN_A_ROW);
    case "capped": return w.health_capped(r.n ?? 0, r.of ?? 0);
    case "failed_some": return w.health_failed_some(r.n ?? 0, r.of ?? 0);
    case "skipped": return w.health_skipped(r.n ?? 0, r.of ?? 0);
    case "retrying": return w.health_retrying;
  }
}

/** What the owner can do about it, or nothing where it takes care of itself. */
function healthDo(r: HealthReason, lang: Lang): string {
  const w = wordsIn(lang);
  switch (r.code) {
    case "dropped": return w.health_do_that_run;
    case "streak": case "all_failed": case "failed_some": case "skipped": return w.health_do_open;
    case "streak_one": case "check_failing_one": return w.health_do_wait;
    case "check_failing": return w.health_do_connection;
    case "capped": return w.health_do_limit;
    case "retrying": return "";
  }
}

/** The level and each reason as a whole line: what happened, then what to do. */
export function healthView(h: Health, lang: Lang = "en"): HealthView {
  return { level: h.level, lines: h.reasons.map((r) => [healthLine(r, lang) + ".", healthDo(r, lang)].filter(Boolean).join(" ")) };
}
