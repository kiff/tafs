/**
 * A taf's dial (the knob): what wakes it, when, and where its results go, with the face's word
 * for each and what tapping it does, all in tafs cloud's words. The apps draw them as they are
 * and compose none (docs/api.md, "The knob").
 */
import type { Schedule } from "./agents.js";
import type { ResultPlace } from "./handler.js";
import { timeIn } from "./work.js";

/** One stop on a ring. */
export interface RingItem {
  id: string;
  kind: string;
  /** The word on the ring. */
  label: string;
  /** Its line, under the dial. */
  words: string;
  on: boolean;
  /** The face's big word when the stop is chosen. */
  state: string;
  /** What tapping the face does; absent when it does nothing for this stop. */
  tap?: string;
  /** The door a door's stop opens or closes (PATCH /v1/tafs/{id}/doors/{door}). */
  door?: string;
}

export interface Knob {
  version?: number;
  does: { label: string; line: string };
  wakes: RingItem[];
  when: RingItem[];
  results: (Omit<RingItem, "kind"> & { place: ResultPlace })[];
  rim: { done: number; working: number; retrying: number; needs_you: number; line: string };
  draft: null | { version: number; words: string[] };
}

/** A door as the doors list gives it. */
export interface KnobDoor { id: string; label: string; open: boolean; words: { state: string; tap: string } }

export interface KnobInput {
  name: string;
  /** What it does, in one word ("Refund"), and in a line. */
  does: { label: string; line: string };
  unscheduled: boolean;
  schedule?: Schedule;
  doors: KnobDoor[];
  places: ResultPlace[];
  /** Today's requests by state, in the account's zone. */
  today: { done: number; working: number; retrying: number; needs_you: number };
  /** What it does when a trigger fires while it works. */
  busy?: "skip" | "latest";
  version?: number;
  zone: string;
  now: Date;
}

const DAYS = ["Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays"];

/** The schedule in words, for the forms the dial sets; any other cron reads as "On a schedule". */
export function cronWords(cron: string): { label: string; words: string } {
  const f = cron.trim().split(/\s+/);
  if (f.length !== 5) return { label: "Schedule", words: "On a schedule" };
  const [min, hour, dom, mon, dow] = f as [string, string, string, string, string];
  const at = (h: string, m: string) => `${h.padStart(2, "0")}:${m.padStart(2, "0")}`;
  const plain = (s: string) => /^\d{1,2}$/.test(s);
  const every = /^\*\/(\d+)$/.exec(min);
  if (every && hour === "*" && dom === "*" && mon === "*" && dow === "*") return { label: `Every ${every[1]} min`, words: `Every ${every[1]} minutes` };
  const between = /^(\d{1,2})-(\d{1,2})$/.exec(hour);
  if (every && between && dom === "*" && mon === "*") {
    const days = dow === "1-5" ? " on weekdays" : dow === "*" ? "" : null;
    if (days !== null) return { label: `Every ${every[1]} min`, words: `Every ${every[1]} minutes from ${at(between[1]!, "0")} to ${at(String(Number(between[2]) + 1), "0")}${days}` };
  }
  if (!plain(min) || !plain(hour) || mon !== "*") return { label: "Schedule", words: "On a schedule" };
  const t = at(hour, min);
  if (dom === "*" && dow === "*") return { label: t, words: `Every day at ${t}` };
  if (dom === "*" && dow === "1-5") return { label: "Weekdays", words: `Weekdays at ${t}` };
  if (dom === "*" && /^[0-6](,[0-6])*$/.test(dow)) {
    const names = dow.split(",").map((d) => DAYS[Number(d)]!);
    const list = names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names.at(-1)}` : names[0]!;
    return { label: names.length === 1 ? names[0]!.slice(0, 3) : "Weekly", words: `${list} at ${t}` };
  }
  if (plain(dom) && dow === "*") return { label: "Monthly", words: `On day ${Number(dom)} of each month at ${t}` };
  return { label: "Schedule", words: "On a schedule" };
}

const PLACE: Record<ResultPlace, { label: string; words: string }> = {
  door_reply: { label: "Sender", words: "Replies to whoever wrote" },
  app: { label: "You", words: "Shows each result in the app" },
  owner_email: { label: "Email", words: "Emails you each result" },
};

export function knobOf(k: KnobInput): Knob {
  const wakes: RingItem[] = [];
  for (const d of k.doors) {
    wakes.push({ id: `door_${d.id}`, kind: "door", door: d.id, label: d.label, words: d.words.state, on: d.open,
      state: d.open ? "Open" : "Closed", tap: d.words.tap });
  }
  if (!k.unscheduled && k.schedule) {
    const c = cronWords(k.schedule.cron);
    const next = k.schedule.enabled && k.schedule.next ? `, next ${timeIn(k.schedule.next, k.zone, k.now)}` : "";
    wakes.push({ id: "trg_schedule", kind: "schedule", label: c.label, words: k.schedule.enabled ? `${c.words}${next}` : `${c.words}, paused`,
      on: k.schedule.enabled, state: k.schedule.enabled ? c.label : "Paused" });
  }
  wakes.push({ id: "run_now", kind: "run_now", label: "Run now", words: "Runs it once, now", on: true, state: "Now",
    ...(k.unscheduled ? {} : { tap: "Tap to run once" }) });

  const when: RingItem[] = [];
  if (k.doors.some((d) => d.open)) when.push({ id: "when_arrivals", kind: "arrivals", label: "Arrivals", words: "Works each one as it arrives", on: true, state: "On" });
  if (!k.unscheduled && k.schedule) {
    when.push({ id: "when_missed", kind: "missed", label: "If missed", words: "A run missed by under an hour runs once; later, it's skipped", on: true, state: "Once" });
  }
  const latest = k.busy === "latest";
  when.push({ id: "when_busy", kind: "busy", label: "If busy", words: latest ? "Runs the latest once it's free" : "Skips a run while it's working", on: true,
    state: latest ? "Latest" : "Skip" });

  const results = k.places.map((p) => ({ id: `res_${p}`, place: p, label: PLACE[p].label, words: PLACE[p].words, on: true, state: "On" }));

  const t = k.today;
  const parts = [`${t.done} done today`, ...(t.working ? [`${t.working} working`] : []), ...(t.retrying ? [`${t.retrying} retrying`] : []),
    ...(t.needs_you ? [`${t.needs_you} need${t.needs_you === 1 ? "s" : ""} you`] : [])];
  return {
    ...(k.version !== undefined ? { version: k.version } : {}),
    does: k.does, wakes, when, results,
    rim: { ...t, line: parts.join(" · ") },
    draft: null,
  };
}

/** The calendar day of an instant in zone, as YYYY-MM-DD: what "today" means for the rim. */
export function dayIn(at: string, zone: string): string {
  try {
    return new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(at));
  } catch {
    return at.slice(0, 10);
  }
}

/** What each job does, in one word, for the dial's face. */
export const DOES: Record<string, string> = { refunds: "Refund", billpay: "Pay bills", access: "Access", deploys: "Deploy", chargebacks: "Chargebacks" };
