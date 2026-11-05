/**
 * The calendar (docs/api.md, "The account", `GET /v1/calendar`): the tafs' scheduled work still to come in a
 * range of dates, computed here in the account's zone so both apps draw the same. An occurrence is a live
 * schedule's firing (read as kiff/agents reads it: its cron in its own zone, a wall time a DST change removes
 * left out, a skipped firing left out, a month's last day where the schedule says so), a follow-up the taf set
 * itself, or a run's next try. Nothing past, nothing invented: a paused taf's schedules are off, so they're not here.
 */
import type { Run, Schedule } from "./agents.js";
import { instantIn, triggerOf, triggerWords } from "./triggers.js";

/** One occurrence, as the apps draw it. */
export interface CalendarEvent {
  /** The taf it belongs to, its name and colour (`home.COLORS`): a tap opens the taf. */
  taf: string;
  name: string;
  color: number;
  /** A schedule's firing, a follow-up the taf set itself, or a run's next try. */
  kind: "schedule" | "follow_up" | "retry";
  /** The instant, RFC 3339 in UTC. */
  at: string;
  /** Its date and time of day in the account's zone, for the apps' slots: YYYY-MM-DD, HH:MM, and the minutes since midnight. */
  day: string;
  time: string;
  minute: number;
  /** What it is, in tafs cloud's words: "Every day at 09:00", "Checks again", "Try 2 of 3". */
  words: string;
  /** How many firings it stands for: a schedule that fires more than FOLD_AT times on a day is one event that day. */
  count: number;
}

/** The most days one read covers. */
export const MAX_DAYS = 62;

/**
 * A schedule that fires more than this many times on one day is one event that day ("Every 15 minutes: 36 times from 09:00 to
 * 17:45"), so a dense schedule can't make a read too large for the apps or for the answer's limit (kiff/tafs#537).
 */
export const FOLD_AT = 12;

/** The most events one read answers; past it, `more` says the rest were left out. */
export const MAX_EVENTS = 2000;

const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** A date as YYYY-MM-DD, or undefined when it isn't one. */
export function dateOf(s: string | null | undefined): string | undefined {
  const m = DATE.exec(s ?? "");
  if (!m) return undefined;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const t = new Date(Date.UTC(y, mo - 1, d));
  return t.getUTCFullYear() === y && t.getUTCMonth() === mo - 1 && t.getUTCDate() === d ? s! : undefined;
}

/** The date `n` days after a date, both YYYY-MM-DD. */
export function plusDays(date: string, n: number): string {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
}

/** One formatter per zone: building one is the costly part of reading a wall clock, and a read reads thousands. */
const formatters = new Map<string, Intl.DateTimeFormat>();
function formatter(zone: string): Intl.DateTimeFormat {
  let f = formatters.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", { timeZone: zone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
    formatters.set(zone, f);
  }
  return f;
}

/** The date an instant falls on, and its time of day, in zone. */
export function localOf(at: string | Date | number, zone: string): { day: string; time: string; minute: number } {
  const p: Record<string, string> = {};
  for (const x of formatter(zone).formatToParts(typeof at === "string" ? new Date(at) : at)) p[x.type] = x.value;
  return { day: `${p.year}-${p.month}-${p.day}`, time: `${p.hour}:${p.minute}`, minute: Number(p.hour) * 60 + Number(p.minute) };
}

/** The zone's offset from UTC at an instant, in milliseconds. */
function offsetAt(at: number, zone: string): number {
  const l = localOf(at, zone);
  const [y, m, d] = l.day.split("-").map(Number) as [number, number, number];
  return Date.UTC(y, m - 1, d) + l.minute * 60_000 - Math.floor(at / 60_000) * 60_000;
}

const iso = (t: number) => new Date(t).toISOString().replace(/\.000Z$/, "Z");

/** A cron's five fields as sets, as kiff/agents parses them (schedule/cron.go): "*", lists, ranges and steps. */
export interface Cron { minute: Set<number>; hour: Set<number>; dom: Set<number>; month: Set<number>; dow: Set<number> }

const FIELDS: Array<[keyof Cron, number, number]> = [["minute", 0, 59], ["hour", 0, 23], ["dom", 1, 31], ["month", 1, 12], ["dow", 0, 7]];

function field(s: string, min: number, max: number): Set<number> | undefined {
  const out = new Set<number>();
  for (const part of s.split(",")) {
    const m = /^(\*|\d{1,2}(?:-\d{1,2})?)(?:\/(\d{1,2}))?$/.exec(part);
    if (!m) return undefined;
    let lo = min, hi = max;
    if (m[1] !== "*") {
      const [a, b] = m[1]!.split("-").map(Number) as [number, number | undefined];
      lo = a; hi = b ?? (m[2] ? max : a);
    }
    const step = m[2] ? Number(m[2]) : 1;
    if (lo < min || hi > max || lo > hi || step < 1) return undefined;
    for (let v = lo; v <= hi; v += step) out.add(v);
  }
  return out;
}

/** The cron parsed, or undefined for one kiff/agents wouldn't keep. */
export function parseCron(expr: string): Cron | undefined {
  const parts = expr.trim().split(/\s+/);
  if (parts.length !== 5) return undefined;
  const sets = FIELDS.map(([, min, max], i) => field(parts[i]!, min, max));
  if (sets.some((s) => !s)) return undefined;
  const c = { minute: sets[0]!, hour: sets[1]!, dom: sets[2]!, month: sets[3]!, dow: sets[4]! };
  if (c.dow.has(7)) { c.dow.delete(7); c.dow.add(0); }
  return c;
}

/** The day of the week of a date (0 is Sunday), and whether it's its month's last day. */
function dayFacts(date: string): { dow: number; dom: number; month: number; last: boolean } {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  const t = new Date(Date.UTC(y, m - 1, d));
  return { dow: t.getUTCDay(), dom: d, month: m, last: new Date(Date.UTC(y, m - 1, d + 1)).getUTCDate() === 1 };
}

const hhmm = (h: number, m: number) => `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;

/**
 * A schedule's firings on the dates `from` to `to` (inclusive, YYYY-MM-DD in the account's zone), after `now`, as instants in
 * order. The cron is read in the schedule's own zone (kiff/agents reads it there): each wall time on each date becomes an
 * instant, and one a DST change removes is left out, as kiff/agents leaves it. Skipped firings are left out too.
 */
export function firings(s: Schedule, from: string, to: string, zone: string, now: Date): string[] {
  if (!s.enabled) return [];
  const lower = Math.max(now.getTime(), Date.parse(instantIn(`${from}T00:00`, zone)));
  const upper = Date.parse(instantIn(`${plusDays(to, 1)}T00:00`, zone));
  const within = (at: string) => { const t = Date.parse(at); return t > lower && t < upper; };
  if (s.kind === "once") return s.at && within(s.at) ? [new Date(s.at).toISOString().replace(/\.000Z$/, "Z")] : [];
  const cron = parseCron(s.cron);
  if (!cron) return [];
  const sz = s.time_zone || "UTC";
  const skip = new Set((s.skip ?? []).map((x) => Date.parse(x)));
  const out: string[] = [];
  // The schedule's zone and the account's can differ: walk the schedule's dates one wider on each side, and keep what
  // falls in the account's range by instant.
  const hours = [...cron.hour].sort((a, b) => a - b), minutes = [...cron.minute].sort((a, b) => a - b);
  for (let date = plusDays(from, -1); date <= plusDays(to, 1); date = plusDays(date, 1)) {
    const f = dayFacts(date);
    if (!cron.month.has(f.month) || !cron.dom.has(f.dom) || !cron.dow.has(f.dow)) continue;
    if (s.kind === "monthly_last_day" && !f.last) continue;
    const [y, mo, d] = date.split("-").map(Number) as [number, number, number];
    const midnight = Date.UTC(y, mo - 1, d);
    // A day the zone keeps one offset all through is plain arithmetic; a day it changes (DST) reads each wall time.
    const early = offsetAt(midnight - 14 * 3_600_000, sz), late = offsetAt(midnight + 38 * 3_600_000, sz);
    for (const h of hours) {
      for (const m of minutes) {
        let at: string;
        if (early === late) {
          at = iso(midnight + (h * 60 + m) * 60_000 - early);
        } else {
          at = instantIn(`${date}T${hhmm(h, m)}`, sz);
          const back = localOf(at, sz);
          if (back.day !== date || back.time !== hhmm(h, m)) continue; // a wall time the zone skips
        }
        if (skip.has(Date.parse(at)) || !within(at)) continue;
        out.push(at);
      }
    }
  }
  return [...new Set(out)].sort();
}

/** A taf as the calendar names it. */
export interface CalendarTaf { id: string; name: string; color: number }

/** The schedule's words for its occurrences: the dial's form in words, or "On a schedule". */
function scheduleWords(s: Schedule, zone: string): string {
  if (s.follow_up) return "Checks again, as it set itself";
  const t = triggerOf(s, zone);
  return t ? triggerWords(t) : "On a schedule";
}

/** A range's events for one taf: its schedules' firings, its follow-ups and its runs' next tries, after now. */
export function eventsOf(taf: CalendarTaf, schedules: Schedule[], runs: Run[], from: string, to: string, zone: string, now: Date): CalendarEvent[] {
  const out: CalendarEvent[] = [];
  const event = (kind: CalendarEvent["kind"], at: string, words: string, count = 1): CalendarEvent => ({ taf: taf.id, name: taf.name, color: taf.color, kind, at, ...localOf(at, zone), words, count });
  for (const s of schedules) {
    const kind = s.follow_up ? "follow_up" : "schedule";
    const words = scheduleWords(s, zone);
    // By the day it falls on in the account's zone: a day with more than FOLD_AT firings is one event, at its first.
    const byDay = new Map<string, string[]>();
    for (const at of firings(s, from, to, zone, now)) {
      const day = localOf(at, zone).day;
      byDay.set(day, [...(byDay.get(day) ?? []), at]);
    }
    for (const ats of byDay.values()) {
      if (ats.length <= FOLD_AT) { for (const at of ats) out.push(event(kind, at, words)); continue; }
      const first = localOf(ats[0]!, zone).time, last = localOf(ats[ats.length - 1]!, zone).time;
      out.push(event(kind, ats[0]!, `${words}: ${ats.length} times from ${first} to ${last}`, ats.length));
    }
  }
  const lower = Math.max(now.getTime(), Date.parse(instantIn(`${from}T00:00`, zone)));
  const upper = Date.parse(instantIn(`${plusDays(to, 1)}T00:00`, zone));
  for (const r of runs) {
    if (r.state !== "retrying" || !r.next_at) continue;
    const t = Date.parse(r.next_at);
    if (!(t > lower && t < upper)) continue;
    out.push(event("retry", new Date(t).toISOString().replace(/\.000Z$/, "Z"), `Try ${(r.try ?? 0) + 1}${r.of ? ` of ${r.of}` : ""}`));
  }
  return out;
}

/** The events in time order, then by taf name, at most MAX_EVENTS (`more` when some were left out), and the dates that have one. */
export function calendarOf(events: CalendarEvent[]): { events: CalendarEvent[]; days: string[]; more: boolean } {
  const sorted = [...events].sort((a, b) => a.at.localeCompare(b.at) || a.name.localeCompare(b.name) || a.kind.localeCompare(b.kind));
  return { events: sorted.slice(0, MAX_EVENTS), days: [...new Set(sorted.map((e) => e.day))], more: sorted.length > MAX_EVENTS };
}
