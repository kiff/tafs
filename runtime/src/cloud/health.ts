/**
 * A taf's health over its recent work (docs/reliability.md): healthy, at risk or failing, with the reasons,
 * from what kiff/agents already records of its runs and its schedule's firings. Nothing is stored: it's
 * read again each time, so a taf never stays failing on old history.
 */
import type { Fired, Run } from "./agents.js";
import { type Lang, wordsIn } from "./words/index.js";
import { timeIn, whenIn } from "./work.js";

export type HealthLevel = "healthy" | "at_risk" | "failing";

/** Why a taf isn't healthy. `n` of `of` where it counts; `since` is when the spell it names began. */
export interface HealthReason {
  code: "dropped" | "streak" | "check_failing" | "all_failed" | "capped" | "failed_some" | "skipped" | "retrying" | "streak_one" | "check_failing_one"
    | "too_large" | "too_large_one" | "credits";
  level: Exclude<HealthLevel, "healthy">;
  n?: number;
  of?: number;
  since?: string;
  /** A check that couldn't read: how the app answered the newest one, as kiff/agents keeps it (Fired.vendor). */
  vendor?: string;
  /**
   * A check whose read was never the app's to fail (kiff/agents' Fired.reason): its tool is on none of the taf's
   * connections now (`no_tool`), or the app's answer has no list where the check looks (`no_items_path`). Its lines
   * say that, never that the app couldn't be read.
   */
  check?: CheckCause;
}

/** The causes of a failed check that are the check's own, not the app's: each says so in its own words. */
export type CheckCause = "no_tool" | "no_items_path";

/** The newest failed check's cause when it's the check's own (CheckCause), else undefined: the app couldn't be read. */
export function checkCause(reason: string | undefined): CheckCause | undefined {
  return reason === "no_tool" || reason === "no_items_path" ? reason : undefined;
}

export interface Health { level: HealthLevel; reasons: HealthReason[] }

/**
 * What the apps get: the level, and each reason as a line that says what happened and what to do. `fixes`
 * are the buttons to draw for the lines that have one, decided here (docs/api.md): Coach opened with `say`,
 * or Plan & usage for credits.
 */
export interface HealthView { level: HealthLevel; lines: string[]; fixes?: HealthFix[] }

/** A way to fix what a line says: `line` is its index in `lines`. */
export interface HealthFix {
  line: number;
  kind: "coach" | "credits";
  /** The button's words. */
  words: string;
  /** What Coach's message box opens with, for `coach`. */
  say?: string;
}

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
/**
 * A firing that worked: a run started, or its check read the app and found nothing new. Not one that found
 * work too big for a run: kiff/agents sets that work aside for good, so nothing was done (TOO_LARGE_FAILING).
 */
const GOOD_FIRING = new Set(["run", "quiet"]);
/** This many of its newest firings in a row found only work too big for one run: failing. One: at risk. */
export const TOO_LARGE_FAILING = 2;
/** A firing skipped because the taf couldn't keep up: it came too late, or its last run was still working. */
const SKIPPED_FIRING = new Set(["skipped_missed", "skipped_busy"]);

/**
 * Whether a run ended, for its health: it finished or it failed. Not counted at all:
 *   - one paused for its owner, and one its owner stopped;
 *   - one tafs already shows as retrying or as needing them;
 *   - a scheduled run that failed before its first turn. kiff/agents makes the run, then it never starts:
 *     its schedule's time already says what it was (it couldn't start, or the taf was switching versions),
 *     so counting the run too would say one thing twice;
 *   - one that failed because the AI provider wasn't serving us (`ours`).
 */
function ended(r: Run): boolean {
  if (r.state === "retrying" || r.state === "needs_you" || ours(r)) return false;
  if (r.status === "ERROR" && r.trigger === "schedule" && r.turns === 0) return false;
  return r.status === "ERROR" || r.status === "COMPLETED";
}

/**
 * Whether a run failed on our side, not the taf's: the AI provider wasn't serving us (`ai_unavailable`).
 * It's our outage to watch, never the owner's to hear of taf by taf: neither the run nor the scheduled time
 * that started it counts toward a taf's health or its alert.
 */
function ours(r: Run): boolean {
  return r.code === "ai_unavailable";
}

/** Whether a run that ended is a failure: it failed, or a turn of it was declined. */
function failed(r: Run): boolean {
  return r.status === "ERROR" || (r.status === "COMPLETED" && r.stop === "refused");
}

/**
 * The taf's health from its recent runs and its schedule's recent firings, both newest first. Only the
 * last HEALTH_DAYS count. Skips for a halt or a version switch, a run paused for its owner and one its owner
 * stopped never count, and neither does an outcome this code doesn't know. Work skipped as too big for a run,
 * or waiting for credits, is never healthy: it says so, with the way to fix it (healthView).
 */
export function healthOf(runs: Run[], fired: Fired[], now: Date): Health {
  const since = now.getTime() - HEALTH_DAYS * 86_400_000;
  const reasons: HealthReason[] = [];

  // Its schedule: the failures in a row at its newest firings, until one that worked.
  const firings = fired.filter((f) => new Date(f.due).getTime() >= since).slice(0, HEALTH_FIRINGS);
  const streak: Fired[] = [];
  let retrying = false;
  // The scheduled times whose run failed on our side: they neither fail nor recover, like a skip.
  const oursToWatch = new Set(runs.filter(ours).map((r) => r.id));
  // A firing that counts for nothing: skipped for a halt or a version switch, or whose run failed on our side.
  const neutral = (f: Fired) => f.outcome === "skipped_halted" || f.outcome === "skipped_switching" || (f.run !== undefined && oursToWatch.has(f.run));
  for (const f of firings) {
    if (f.run && oursToWatch.has(f.run)) continue;
    // Work too big for a run isn't a good firing, but its check read the app: the check failures before it are over.
    if (GOOD_FIRING.has(f.outcome) || f.outcome === "skipped_too_large") break;
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
    else if (newest === "failed_check") {
      const cause = checkCause(streak[0]!.reason);
      const more = { ...(streak[0]!.vendor ? { vendor: streak[0]!.vendor } : {}), ...(cause ? { check: cause } : {}) };
      reasons.push(streak.length >= STREAK_FAILING ? { code: "check_failing", level: "failing", n: streak.length, since: began, ...more } : { code: "check_failing_one", level: "at_risk", since: began, ...more });
    }
    else reasons.push(streak.length >= STREAK_FAILING ? { code: "streak", level: "failing", n: streak.length, since: began } : { code: "streak_one", level: "at_risk", since: began });
  }
  if (retrying) reasons.push({ code: "retrying", level: "at_risk" });
  // Its newest firings that found only work too big for one run: that work is set aside, not done.
  const tooLarge: Fired[] = [];
  for (const f of firings) {
    if (neutral(f)) continue;
    if (f.outcome !== "skipped_too_large") break;
    tooLarge.push(f);
  }
  if (tooLarge.length) {
    const began = tooLarge[tooLarge.length - 1]!.due;
    reasons.push(tooLarge.length >= TOO_LARGE_FAILING ? { code: "too_large", level: "failing", n: tooLarge.length, since: began } : { code: "too_large_one", level: "at_risk", since: began });
  }
  // Its newest firings that waited for credits: its work waits too, since the first of them.
  const credits: Fired[] = [];
  for (const f of firings) {
    if (neutral(f)) continue;
    if (f.outcome !== "skipped_credits") break;
    credits.push(f);
  }
  if (credits.length) reasons.push({ code: "credits", level: "at_risk", since: credits[credits.length - 1]!.due });
  const skipped = firings.filter((f) => SKIPPED_FIRING.has(f.outcome)).length;
  if (skipped >= SKIPPED_AT_RISK) reasons.push({ code: "skipped", level: "at_risk", n: skipped, of: firings.length });

  // Its runs that ended, whatever started them.
  const recent = runs.filter((r) => ended(r) && Date.parse(r.updated) >= since).slice(0, HEALTH_RUNS);
  let inARow = 0;
  while (inARow < recent.length && failed(recent[inARow]!)) inARow++;
  const failures = recent.filter(failed).length;
  // Its newest run worked (not failed, not cut short): a fix took, so the failures before it are history,
  // shown in its runs, not in its health. A taf that fails on and off is at risk again at its next failure.
  const newestWorked = recent.length > 0 && !failed(recent[0]!) && !CAPS.has(recent[0]!.stop ?? "");
  if (inARow >= FAILED_IN_A_ROW) reasons.push({ code: "all_failed", level: "failing", n: inARow, since: recent[inARow - 1]!.updated });
  else if (failures >= FAILED_AT_RISK && !newestWorked) reasons.push({ code: "failed_some", level: "at_risk", n: failures, of: recent.length });
  const capped = recent.filter((r) => r.status === "COMPLETED" && CAPS.has(r.stop ?? "")).length;
  if (capped >= CAPPED_AT_RISK) reasons.push({ code: "capped", level: "at_risk", n: capped, of: recent.length });

  reasons.sort((a, b) => Number(b.level === "failing") - Number(a.level === "failing"));
  return { level: reasons.some((r) => r.level === "failing") ? "failing" : reasons.length ? "at_risk" : "healthy", reasons };
}

/**
 * What happened, in words, with no "what to do": the line the list shows, and the alert's start. A check that couldn't
 * read says when, and names the app it reads by its connection's own name (`app`, checkAppOf), else "the app".
 */
export function healthLine(r: HealthReason, lang: Lang = "en", zone = "UTC", now = new Date(), app?: string): string {
  const w = wordsIn(lang);
  switch (r.code) {
    case "too_large": return w.health_too_large(r.n ?? TOO_LARGE_FAILING);
    case "too_large_one": return w.health_too_large_one;
    case "credits": return w.health_credits(timeIn(r.since ?? now.toISOString(), zone, now, lang));
    case "dropped": return w.health_dropped;
    case "streak": return w.health_streak(r.n ?? STREAK_FAILING);
    case "streak_one": return w.health_streak_one;
    // A check of its own cause failed, the app wasn't unreadable: the line never says it was (healthDo says why).
    case "check_failing": return r.check ? w.health_check_stuck(r.n ?? STREAK_FAILING, timeIn(r.since ?? now.toISOString(), zone, now, lang))
      : w.health_check_failing(r.n ?? STREAK_FAILING, timeIn(r.since ?? now.toISOString(), zone, now, lang), app);
    case "check_failing_one": return r.check ? w.health_check_stuck_one(whenIn(r.since ?? now.toISOString(), zone, now, lang))
      : w.health_check_failing_one(whenIn(r.since ?? now.toISOString(), zone, now, lang), app);
    case "all_failed": return w.health_all_failed(r.n ?? FAILED_IN_A_ROW);
    case "capped": return w.health_capped(r.n ?? 0, r.of ?? 0);
    case "failed_some": return w.health_failed_some(r.n ?? 0, r.of ?? 0);
    case "skipped": return w.health_skipped(r.n ?? 0, r.of ?? 0);
    case "retrying": return w.health_retrying;
  }
}

/**
 * How an app answered a check's read it refused, as the owner can act on it: it wants an account that can read (401, 403),
 * the connected account isn't set up for the read (FAILED_PRECONDITION, such as a Google account with no mailbox), or it
 * couldn't answer just then (429, 5xx). Anything else, or nothing kept, is undefined: the line says what it said before.
 */
export function vendorKind(vendor: string | undefined): "access" | "account" | "busy" | undefined {
  if (!vendor) return undefined;
  const [status, code] = vendor.split(" ");
  if (code === "FAILED_PRECONDITION") return "account";
  if (code === "UNAUTHENTICATED" || code === "PERMISSION_DENIED" || status === "401" || status === "403") return "access";
  if (code === "RESOURCE_EXHAUSTED" || code === "UNAVAILABLE" || code === "DEADLINE_EXCEEDED" || code === "INTERNAL" || status === "429" || /^5\d\d$/.test(status ?? "")) return "busy";
  return undefined;
}

/**
 * What the owner can do about it, or nothing where it takes care of itself. `tool` is the read the taf's check names
 * (its schedule's check.tool), said when the check's tool is on none of its connections now.
 */
function healthDo(r: HealthReason, lang: Lang, app?: string, tool?: string): string {
  const w = wordsIn(lang);
  if (r.code === "check_failing" || r.code === "check_failing_one") {
    // A check of its own cause: Coach fixes the check, so the owner hears that, never to check the app's connection.
    if (r.check === "no_tool" && tool) return w.check_why_no_tool(tool);
    if (r.check === "no_items_path") return w.check_why_no_items(app);
    // A check the app refused says what the app's answer asks of the owner, when it's one of those.
    const kind = vendorKind(r.vendor);
    if (kind === "access") return w.check_why_access(app);
    if (kind === "account") return w.check_why_account(app);
    if (kind === "busy") return w.check_why_busy(app);
  }
  switch (r.code) {
    case "dropped": return w.health_do_that_run;
    case "streak": case "all_failed": case "failed_some": case "skipped": return w.health_do_open;
    case "streak_one": case "check_failing_one": return w.health_do_wait;
    case "check_failing": return w.health_do_connection;
    case "capped": return w.health_do_limit;
    case "too_large": case "too_large_one": return w.health_do_narrow;
    case "credits": return "";
    case "retrying": return "";
  }
}

/** Who reads the health, for its fixes: whether they may buy credits (`billing.manage`). */
export interface Reader {
  billing?: boolean;
}

/** The button for a reason, or none: Coach to narrow what the taf reads, or Plan & usage for credits. */
function healthFix(r: HealthReason, lang: Lang, reader: Reader): Omit<HealthFix, "line"> | undefined {
  const w = wordsIn(lang);
  switch (r.code) {
    case "too_large": case "too_large_one": return { kind: "coach", words: w.health_fix_narrow, say: w.health_fix_narrow_say };
    case "credits": return { kind: "credits", words: reader.billing ? w.health_fix_credits : w.health_fix_credits_ask };
    default: return undefined;
  }
}

/** The level and each reason as a whole line: what happened, then what to do; and the buttons that fix them. */
export function healthView(h: Health, lang: Lang = "en", reader: Reader = {}, zone = "UTC", now = new Date(), app?: string, tool?: string): HealthView {
  const lines = h.reasons.map((r) => [healthLine(r, lang, zone, now, app) + ".", healthDo(r, lang, app, tool)].filter(Boolean).join(" "));
  const fixes = h.reasons.flatMap((r, line) => {
    const fix = healthFix(r, lang, reader);
    return fix ? [{ line, ...fix }] : [];
  });
  return { level: h.level, lines, ...(fixes.length ? { fixes } : {}) };
}
