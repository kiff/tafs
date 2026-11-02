/**
 * The dial's triggers (docs/api.md, "The knob"): the forms an owner sets, checked, and turned into the
 * schedules kiff/agents keeps, one per firing time it can say exactly. A form the scheduler can't say
 * exactly is refused with the nearest one in words, for Coach and the apps to offer before the owner
 * confirms. Nothing here reaches the scheduler: only a version made live does.
 */

/** A schedule as kiff/agents stores it. */
export interface PlannedSchedule {
  kind: "cron" | "once" | "monthly_last_day";
  cron?: string;
  at?: string;
  busy: "skip" | "latest";
  skip?: string[];
  check?: { tool: string; arguments: Record<string, unknown>; items: string; cursor: string };
}

/** A trigger from the dial or Coach's draft. */
export interface Trigger {
  kind: "schedule" | "app_check" | "door" | "vendor_event" | "before_event";
  form?: "daily" | "weekdays" | "weekly" | "monthly" | "monthly_last_day" | "times_a_day" | "every" | "once";
  at?: string;
  days?: string[];
  times?: string[];
  every_minutes?: number;
  between?: { from: string; to: string };
  date?: string;
  skip?: string[];
  operation?: string;
  args?: Record<string, unknown>;
  items?: string;
  cursor?: string;
}

/** A trigger the scheduler can't keep as written: why, and the nearest form, in words. */
export class TriggerError extends Error {
  constructor(message: string, readonly nearest?: string) {
    super(message);
  }
}

const DAY = { sun: 0, mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6 } as const;
const HHMM = /^([01]\d|2[0-3]):([0-5]\d)$/;
/** The fewest minutes between two firings, as kiff/agents keeps it. */
export const MIN_EVERY = 15;

function time(s: string | undefined, what: string): { h: number; m: number } {
  const t = HHMM.exec(s ?? "");
  if (!t) throw new TriggerError(`${what} is a time of day, such as 09:00`);
  return { h: Number(t[1]), m: Number(t[2]) };
}

function days(ds: string[] | undefined): string {
  if (!ds?.length) throw new TriggerError("weekly needs at least one day");
  const n = [...new Set(ds.map((d) => {
    const v = DAY[d.toLowerCase().slice(0, 3) as keyof typeof DAY];
    if (v === undefined) throw new TriggerError(`${d} isn't a day of the week`);
    return v;
  }))].sort();
  return n.join(",");
}

/** The nearest every-N the scheduler says exactly: 15, 20 or 30 minutes, or whole hours. */
export function nearestEvery(n: number): number {
  if (n <= 15) return 15;
  if (n < 60) return [15, 20, 30, 60].reduce((a, b) => (Math.abs(b - n) < Math.abs(a - n) ? b : a));
  return Math.max(1, Math.round(n / 60)) * 60;
}

function every(n: number | undefined): number {
  if (!n || !Number.isInteger(n) || n < 1) throw new TriggerError("every_minutes is how many minutes apart");
  const exact = n === 15 || n === 20 || n === 30 || (n >= 60 && n % 60 === 0 && n <= 720);
  if (!exact) {
    const near = nearestEvery(n);
    throw new TriggerError(`every ${n} minutes can't be kept exactly`, near >= 60 ? `every ${near / 60} hour${near === 60 ? "" : "s"}` : `every ${near} minutes`);
  }
  return n;
}

/** The trigger's schedules, or why it can't be kept. Doors and vendor events aren't schedules: none. */
export function schedulesOf(t: Trigger, busy: "skip" | "latest" = "skip"): PlannedSchedule[] {
  const base = { busy, ...(t.skip?.length ? { skip: t.skip } : {}) };
  if (t.kind === "door" || t.kind === "vendor_event") return [];
  if (t.kind === "before_event") throw new TriggerError("before a calendar event isn't available yet");
  if (t.kind === "app_check") {
    if (!t.operation || !t.cursor) throw new TriggerError("an app check names its read and the field it compares");
    const n = every(t.every_minutes);
    return [{ kind: "cron", cron: n < 60 ? `*/${n} * * * *` : `0 */${n / 60} * * *`, ...base,
      check: { tool: t.operation, arguments: t.args ?? {}, items: t.items ?? "", cursor: t.cursor } }];
  }
  switch (t.form) {
    case "daily": { const { h, m } = time(t.at, "at"); return [{ kind: "cron", cron: `${m} ${h} * * *`, ...base }]; }
    case "weekdays": { const { h, m } = time(t.at, "at"); return [{ kind: "cron", cron: `${m} ${h} * * 1-5`, ...base }]; }
    case "weekly": { const { h, m } = time(t.at, "at"); return [{ kind: "cron", cron: `${m} ${h} * * ${days(t.days)}`, ...base }]; }
    case "monthly": {
      const { h, m } = time(t.at, "at");
      const d = Number(/^\d{1,2}$/.test(t.date ?? "") ? t.date : NaN);
      if (!(d >= 1 && d <= 28)) throw new TriggerError("a monthly day is 1 to 28", d > 28 ? "the last day of the month" : undefined);
      return [{ kind: "cron", cron: `${m} ${h} ${d} * *`, ...base }];
    }
    case "monthly_last_day": { const { h, m } = time(t.at, "at"); return [{ kind: "monthly_last_day", cron: `${m} ${h} * * *`, ...base }]; }
    case "times_a_day": {
      if (!t.times?.length || t.times.length > 12) throw new TriggerError("several times a day is 1 to 12 times");
      // One schedule per distinct minute, its hours together: 09:00 and 15:00 is one cron.
      const byMinute = new Map<number, number[]>();
      for (const s of t.times) {
        const { h, m } = time(s, "each time");
        byMinute.set(m, [...(byMinute.get(m) ?? []), h]);
      }
      const dow = t.days?.length ? days(t.days) : "*";
      return [...byMinute].sort(([a], [b]) => a - b).map(([m, hs]) => ({ kind: "cron" as const, cron: `${m} ${[...new Set(hs)].sort((a, b) => a - b).join(",")} * * ${dow}`, ...base }));
    }
    case "every": {
      const n = every(t.every_minutes);
      const dow = t.days?.length ? days(t.days) : "*";
      if (!t.between) return [{ kind: "cron", cron: n < 60 ? `*/${n} * * * ${dow}` : `0 */${n / 60} * * ${dow}`, ...base }];
      const from = time(t.between.from, "from"), to = time(t.between.to, "to");
      if (from.m !== 0 || to.m !== 0) throw new TriggerError("a window starts and ends on the hour", `from ${String(from.h).padStart(2, "0")}:00 to ${String(to.m ? to.h + 1 : to.h).padStart(2, "0")}:00`);
      if (to.h <= from.h) throw new TriggerError("a window ends after it starts");
      if (n >= 60) return [{ kind: "cron", cron: `0 ${from.h}-${to.h - 1}/${n / 60} * * ${dow}`, ...base }];
      return [{ kind: "cron", cron: `*/${n} ${from.h}-${to.h - 1} * * ${dow}`, ...base }];
    }
    case "once": {
      if (!t.date || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(t.date)) throw new TriggerError("once is a date and time, such as 2026-11-05T09:00");
      return [{ kind: "once", at: t.date, ...base }];
    }
  }
  throw new TriggerError("form is daily, weekdays, weekly, monthly, monthly_last_day, times_a_day, every or once");
}
