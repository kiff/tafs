import type { Fired, Run } from "./agents.js";
import type { DeliveryView } from "./delivery.js";
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

/** A firing its schedule skipped, as the taf's page shows it: not a failure, nothing was run. */
export interface SkippedView {
  due: string;
  why: "missed" | "busy";
  words: string;
}

/** How many skipped firings the page shows, newest first, and from how far back. */
const SKIPPED_SHOWN = 5;
const SKIPPED_DAYS = 7;

/**
 * The firings the taf's schedule skipped lately, in words: one that came too
 * late, or while its last run was still working.
 */
export function skippedOf(fired: Fired[], zone: string, now: Date): SkippedView[] {
  const since = now.getTime() - SKIPPED_DAYS * 86_400_000;
  return fired
    .filter((f) => (f.outcome === "skipped_missed" || f.outcome === "skipped_busy") && new Date(f.due).getTime() >= since)
    .slice(0, SKIPPED_SHOWN)
    .map((f) => {
      const busy = f.outcome === "skipped_busy";
      const when = timeIn(f.due, zone, now);
      return {
        due: new Date(f.due).toISOString(),
        why: busy ? "busy" : "missed",
        words: busy ? `Skipped the ${when} run: the last one was still working` : `Skipped the ${when} run: it came too late to run`,
      };
    });
}

/** A needs_you older than this no longer counts on the list: it stays on the taf's page. */
const NEEDS_YOU_DAYS = 7;

/** A time as the owner reads it: "14:20" today, "Fri 14:20" another day, in their zone. */
export function timeIn(at: string | Date, zone: string, now: Date): string {
  const d = typeof at === "string" ? new Date(at) : at;
  const tz = validZone(zone);
  const hm = new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(d);
  const day = (x: Date) => new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).format(x);
  if (day(d) === day(now)) return hm;
  const wd = new Intl.DateTimeFormat("en-GB", { timeZone: tz, weekday: "short" }).format(d);
  return `${wd} ${hm}`;
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
export function runFailure(r: { status?: unknown; error?: unknown; code?: unknown }): { code: string; words: string } | undefined {
  if (r.status !== "ERROR" || typeof r.code !== "string" || !/^[a-z_]{1,40}$/.test(r.code) || typeof r.error !== "string") return undefined;
  const flat = r.error.replace(/\s+/g, " ").trim();
  if (!flat) return undefined;
  return { code: r.code, words: flat.length > 400 ? `${flat.slice(0, 399)}…` : flat };
}

/** One run as a request: its state and the words for it. */
export function requestOf(r: Run, zone: string, now: Date, sent: Sent[] = [], again = new Set<string>(), own: DeliveryView[] = []): RequestView {
  // The newest stamp per run: a reply sent again replaces the one that wasn't.
  const last = sent.find((s) => s.run === r.id);
  const deliveries: RequestView["deliveries"] = last ? [{
    place: "door_reply" as const, state: last.status,
    line: last.status === "sent" ? `Replied at ${timeIn(last.at, zone, now)}` : `Reply not sent: ${last.why ?? "the provider didn't take it"}`,
    ...(last.status === "not_sent" && again.has(r.id) ? { send_again: true as const } : {}),
  }] : [];
  deliveries.push(...own);
  const base = { id: r.id, at: r.created, deliveries };
  if (r.state === "needs_you") return { ...base, state: "needs_you", line: r.reason ?? "It needs you to look at it." };
  if (r.state === "retrying") {
    const next = r.next_at ? ` Trying again at ${timeIn(r.next_at, zone, now)}` : " Trying again soon";
    const of = r.try && r.of ? `, ${r.try} of ${r.of}` : "";
    return { ...base, state: "retrying", line: `It stopped while it worked.${next}${of}.` };
  }
  switch (r.status) {
    case "PENDING":
    case "RUNNING":
      return { ...base, state: "working", line: "Working on it" };
    case "PAUSED": {
      if (r.requirements?.some((q) => q.exception && !q.answered)) return { ...base, state: "waiting", line: "Waiting for your approval" };
      // An open card is the owner's to answer: it comes before any wait on the app, so an owner's question is never drawn as the app's.
      if (r.requirements?.some((q) => q.kind === "card" && !q.answered)) return { ...base, state: "waiting", line: "Waiting for an answer" };
      const wait = r.requirements?.find((q) => q.kind === "event" && !q.answered && q.wait)?.wait;
      if (wait) {
        const words = wait.words?.trim() || `Waiting for the app's ${wait.event} event`;
        return { ...base, state: "waiting", line: words, waiting_for: words };
      }
      return { ...base, state: "waiting", line: "Waiting for an answer" };
    }
    case "COMPLETED":
      return { ...base, state: "done", line: `Done at ${timeIn(r.updated, zone, now)}` };
    case "CANCELLED":
      return { ...base, state: "stopped", line: "Stopped" };
    default: {
      const coded = runFailure(r);
      if (coded) return { ...base, state: "failed", line: coded.words, code: coded.code };
      return { ...base, state: "failed", line: r.error ? `It failed: ${r.error}` : "It failed" };
    }
  }
}

/** A taf's status and line on the list, from its recent runs, newest first. */
export function summaryOf(runs: Run[], how: boolean | TafNow, zone: string, now: Date): WorkSummary {
  const { paused = false, halted = false, unscheduled = false, next = null } = typeof how === "boolean" ? { paused: how } : how;
  // Halted stops everything, its holds included: that comes first.
  if (halted) return { status: "halted", line: "Halted · resume it from Halt", needs_you: 0 };
  const recent = runs.filter((r) => now.getTime() - Date.parse(r.updated) < NEEDS_YOU_DAYS * 86_400_000);
  const needs = recent.filter((r) => r.state === "needs_you" || (r.status === "PAUSED" && r.requirements?.some((q) => q.exception && !q.answered)));
  if (needs.length) {
    const first = requestOf(needs[0]!, zone, now);
    return { status: "needs_you", line: needs.length === 1 ? first.line : `${needs.length} requests need you`, needs_you: needs.length };
  }
  const retrying = runs.find((r) => r.state === "retrying");
  if (retrying) return { status: "retrying", line: requestOf(retrying, zone, now).line, needs_you: 0 };
  if (runs.some((r) => r.status === "RUNNING" || r.status === "PENDING")) return { status: "working", line: "Working on it", needs_you: 0 };
  // A run waiting for a connected app's event (the slow-tools design): the taf isn't idle, and the line is the
  // connection's words for the wait. A run that also needs the owner was counted above. Named apart from the
  // home item's `state: "waiting"`, which means a call held for the owner.
  const waiting = runs.find((r) => {
    const open = (r.requirements ?? []).filter((q) => !q.answered);
    return r.status === "PAUSED" && open.length > 0 && open.every((q) => q.kind === "event" && q.wait);
  });
  if (waiting) return { status: "waiting_on_app", line: requestOf(waiting, zone, now).line, needs_you: 0 };
  if (paused) return { status: "paused", line: "Paused", needs_you: 0 };
  if (unscheduled) return { status: "idle", line: "Works from its chat", needs_you: 0 };
  if (next) return { status: "idle", line: `Next run ${timeIn(next, zone, now)}`, needs_you: 0 };
  const last = runs.find((r) => r.status === "COMPLETED");
  return { status: "idle", line: last ? `Last done at ${timeIn(last.updated, zone, now)}` : "Nothing yet", needs_you: 0 };
}

/** The taf's page: counts per state, and the recent requests with what was sent. */
export function workOf(runs: Run[], zone: string, now: Date, sent: Sent[], again = new Set<string>(), own = new Map<string, DeliveryView[]>()): { counts: Record<RequestView["state"], number>; requests: RequestView[] } {
  const requests = runs.map((r) => requestOf(r, zone, now, sent, again, own.get(r.id) ?? []));
  const counts = { done: 0, working: 0, retrying: 0, needs_you: 0, waiting: 0, stopped: 0, failed: 0 };
  for (const q of requests) counts[q.state]++;
  return { counts, requests };
}
