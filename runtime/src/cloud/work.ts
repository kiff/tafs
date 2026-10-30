import type { Run } from "./agents.js";
import type { Sent } from "./doors/model.js";

/**
 * Where a taf's work stands, as both apps show it (docs/reliability.md, "The taf's view"):
 * worked out here from its runs as kiff/agents keeps them, with every line in words and every
 * time already written in the account's time zone. An app formats nothing.
 */

/** A taf's status, the most pressing first. */
export type WorkStatus = "needs_you" | "retrying" | "working" | "paused" | "idle";

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
  /** Where its result went, as far as tafs cloud sent it. */
  deliveries: Array<{ place: "door_reply"; state: Sent["status"]; line: string; send_again?: true }>;
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

/** One run as a request: its state and the words for it. */
export function requestOf(r: Run, zone: string, now: Date, sent: Sent[] = [], again = new Set<string>()): RequestView {
  // The newest stamp per run: a reply sent again replaces the one that wasn't.
  const last = sent.find((s) => s.run === r.id);
  const deliveries = last ? [{
    place: "door_reply" as const, state: last.status,
    line: last.status === "sent" ? `Replied at ${timeIn(last.at, zone, now)}` : `Reply not sent: ${last.why ?? "the provider didn't take it"}`,
    ...(last.status === "not_sent" && again.has(r.id) ? { send_again: true as const } : {}),
  }] : [];
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
    case "PAUSED":
      return { ...base, state: "waiting", line: r.requirements?.some((q) => q.exception && !q.answered) ? "Waiting for your approval" : "Waiting for an answer" };
    case "COMPLETED":
      return { ...base, state: "done", line: `Done at ${timeIn(r.updated, zone, now)}` };
    case "CANCELLED":
      return { ...base, state: "stopped", line: "Stopped" };
    default:
      return { ...base, state: "failed", line: r.error ? `It failed: ${r.error}` : "It failed" };
  }
}

/** A taf's status and line on the list, from its recent runs, newest first. */
export function summaryOf(runs: Run[], paused: boolean, zone: string, now: Date): WorkSummary {
  const recent = runs.filter((r) => now.getTime() - Date.parse(r.updated) < NEEDS_YOU_DAYS * 86_400_000);
  const needs = recent.filter((r) => r.state === "needs_you" || (r.status === "PAUSED" && r.requirements?.some((q) => q.exception && !q.answered)));
  if (needs.length) {
    const first = requestOf(needs[0]!, zone, now);
    return { status: "needs_you", line: needs.length === 1 ? first.line : `${needs.length} requests need you`, needs_you: needs.length };
  }
  const retrying = runs.find((r) => r.state === "retrying");
  if (retrying) return { status: "retrying", line: requestOf(retrying, zone, now).line, needs_you: 0 };
  if (runs.some((r) => r.status === "RUNNING" || r.status === "PENDING")) return { status: "working", line: "Working on it", needs_you: 0 };
  if (paused) return { status: "paused", line: "Paused", needs_you: 0 };
  const last = runs.find((r) => r.status === "COMPLETED");
  return { status: "idle", line: last ? `Last done at ${timeIn(last.updated, zone, now)}` : "Nothing yet", needs_you: 0 };
}

/** The taf's page: counts per state, and the recent requests with what was sent. */
export function workOf(runs: Run[], zone: string, now: Date, sent: Sent[], again = new Set<string>()): { counts: Record<RequestView["state"], number>; requests: RequestView[] } {
  const requests = runs.map((r) => requestOf(r, zone, now, sent, again));
  const counts = { done: 0, working: 0, retrying: 0, needs_you: 0, waiting: 0, stopped: 0, failed: 0 };
  for (const q of requests) counts[q.state]++;
  return { counts, requests };
}
