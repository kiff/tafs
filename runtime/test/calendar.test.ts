import { describe, expect, it } from "vitest";
import type { Run, Schedule } from "../src/cloud/agents.js";
import { calendarOf, dateOf, eventsOf, firings, localOf, parseCron, plusDays } from "../src/cloud/calendar.js";

const NOW = new Date("2026-11-05T10:00:00Z");
const base: Schedule = { id: "s1", agent: "rae", cron: "0 9 * * *", time_zone: "Europe/Paris", input: "", enabled: true };

describe("the calendar's dates", () => {
  it("takes only real dates, and counts days across a month's end", () => {
    expect(dateOf("2026-11-05")).toBe("2026-11-05");
    expect(dateOf("2026-02-30")).toBeUndefined();
    expect(dateOf("2026-11-5")).toBeUndefined();
    expect(dateOf("tomorrow")).toBeUndefined();
    expect(plusDays("2026-11-30", 1)).toBe("2026-12-01");
    expect(plusDays("2026-03-01", -1)).toBe("2026-02-28");
  });

  it("places an instant on its day and time in a zone", () => {
    expect(localOf("2026-11-05T23:30:00Z", "Europe/Paris")).toEqual({ day: "2026-11-06", time: "00:30", minute: 30 });
    expect(localOf("2026-11-05T23:30:00Z", "UTC")).toEqual({ day: "2026-11-05", time: "23:30", minute: 1410 });
  });
});

describe("a cron as kiff/agents reads it", () => {
  it("parses lists, ranges and steps, with 7 as Sunday", () => {
    const c = parseCron("*/15 9-17/4 * * 1,7")!;
    expect([...c.minute]).toEqual([0, 15, 30, 45]);
    expect([...c.hour]).toEqual([9, 13, 17]);
    expect([...c.dow].sort()).toEqual([0, 1]);
    expect(parseCron("0 9 * *")).toBeUndefined();
    expect(parseCron("60 9 * * *")).toBeUndefined();
  });
});

describe("a schedule's firings in a range", () => {
  it("fires on each date of the range at its time, in the account's zone, after now only", () => {
    const at = firings(base, "2026-11-05", "2026-11-07", "Europe/Paris", NOW);
    // Today's 09:00 Paris (08:00Z) is past: the next two days.
    expect(at).toEqual(["2026-11-06T08:00:00Z", "2026-11-07T08:00:00Z"]);
  });

  it("reads the cron in the schedule's own zone and keeps what falls in the account's range", () => {
    // 09:00 in Paris is 02:00 the same day in New York; 23:00 in Paris on the 5th is the 5th there too.
    const late = { ...base, cron: "0 23 * * *" };
    expect(firings(late, "2026-11-05", "2026-11-05", "America/New_York", NOW)).toEqual(["2026-11-05T22:00:00Z"]);
    // Tokyo's 6 November starts at 15:00Z on the 5th: Paris's 23:00 on the 5th is the 6th in Tokyo.
    expect(firings(late, "2026-11-05", "2026-11-05", "Asia/Tokyo", NOW)).toEqual([]);
    expect(firings(late, "2026-11-06", "2026-11-06", "Asia/Tokyo", NOW)).toEqual(["2026-11-05T22:00:00Z"]);
  });

  it("follows a DST change: the same wall time, a different instant", () => {
    const before = new Date("2026-10-20T00:00:00Z");
    // Paris leaves summer time on 25 October 2026: 09:00 is 07:00Z before, 08:00Z after.
    expect(firings(base, "2026-10-24", "2026-10-26", "Europe/Paris", before)).toEqual(["2026-10-24T07:00:00Z", "2026-10-25T08:00:00Z", "2026-10-26T08:00:00Z"]);
  });

  it("leaves out a wall time the zone skips, as kiff/agents does, and takes a doubled one once", () => {
    const early = new Date("2026-03-20T00:00:00Z");
    // 29 March 2026, Paris: 02:00 to 03:00 doesn't exist.
    expect(firings({ ...base, cron: "30 2 * * *" }, "2026-03-28", "2026-03-30", "Europe/Paris", early)).toEqual(["2026-03-28T01:30:00Z", "2026-03-30T00:30:00Z"]);
    // 25 October 2026, Paris: 02:30 happens twice; one firing.
    expect(firings({ ...base, cron: "30 2 * * *" }, "2026-10-25", "2026-10-25", "Europe/Paris", new Date("2026-10-24T00:00:00Z"))).toHaveLength(1);
  });

  it("keeps the days of the week, the month's day, and the last day of the month where the schedule says so", () => {
    const weekdays = { ...base, cron: "0 9 * * 1-5" };
    // 7 and 8 November 2026 are a weekend.
    expect(firings(weekdays, "2026-11-06", "2026-11-09", "Europe/Paris", NOW)).toEqual(["2026-11-06T08:00:00Z", "2026-11-09T08:00:00Z"]);
    const monthly = { ...base, cron: "0 9 15 * *" };
    expect(firings(monthly, "2026-11-05", "2026-12-31", "Europe/Paris", NOW)).toEqual(["2026-11-15T08:00:00Z", "2026-12-15T08:00:00Z"]);
    const last = { ...base, kind: "monthly_last_day" as const, cron: "0 9 * * *" };
    expect(firings(last, "2026-11-05", "2026-12-31", "Europe/Paris", NOW)).toEqual(["2026-11-30T08:00:00Z", "2026-12-31T08:00:00Z"]);
  });

  it("leaves out a firing the owner skipped, and a schedule that's off", () => {
    const skipped = { ...base, skip: ["2026-11-06T08:00:00Z"] };
    expect(firings(skipped, "2026-11-05", "2026-11-07", "Europe/Paris", NOW)).toEqual(["2026-11-07T08:00:00Z"]);
    expect(firings({ ...base, enabled: false }, "2026-11-05", "2026-11-07", "Europe/Paris", NOW)).toEqual([]);
  });

  it("fires a once schedule at its time, inside the range and after now only", () => {
    const once = { ...base, kind: "once" as const, at: "2026-11-06T15:00:00Z" };
    expect(firings(once, "2026-11-05", "2026-11-07", "UTC", NOW)).toEqual(["2026-11-06T15:00:00Z"]);
    expect(firings(once, "2026-11-07", "2026-11-08", "UTC", NOW)).toEqual([]);
    expect(firings(once, "2026-11-05", "2026-11-07", "UTC", new Date("2026-11-06T16:00:00Z"))).toEqual([]);
  });

  it("fires every N minutes in a window, on the days it says", () => {
    const every = { ...base, cron: "*/30 9-10 * * 5" }; // Fridays 09:00 to 10:59
    expect(firings(every, "2026-11-06", "2026-11-07", "Europe/Paris", NOW)).toEqual(["2026-11-06T08:00:00Z", "2026-11-06T08:30:00Z", "2026-11-06T09:00:00Z", "2026-11-06T09:30:00Z"]);
  });
});

describe("a taf's events", () => {
  const taf = { id: "rae", name: "Rae", color: 2 };

  it("names each firing with the schedule's words, a follow-up as its own kind, and a run's next try", () => {
    const followUp: Schedule = { ...base, id: "fu-1", kind: "once", at: "2026-11-06T12:00:00Z", follow_up: true };
    const run = { id: "r1", state: "retrying", next_at: "2026-11-05T11:00:00Z", try: 1, of: 3 } as Run;
    const done = { id: "r2", state: "retrying", next_at: "2026-11-05T09:00:00Z", try: 1, of: 3 } as Run; // past
    const ev = eventsOf(taf, [base, followUp], [run, done], "2026-11-05", "2026-11-06", "Europe/Paris", NOW);
    expect(calendarOf(ev)).toEqual({
      events: [
        { taf: "rae", name: "Rae", color: 2, kind: "retry", at: "2026-11-05T11:00:00Z", day: "2026-11-05", time: "12:00", minute: 720, words: "Try 2 of 3" },
        { taf: "rae", name: "Rae", color: 2, kind: "schedule", at: "2026-11-06T08:00:00Z", day: "2026-11-06", time: "09:00", minute: 540, words: "Every day at 09:00" },
        { taf: "rae", name: "Rae", color: 2, kind: "follow_up", at: "2026-11-06T12:00:00Z", day: "2026-11-06", time: "13:00", minute: 780, words: "Checks again, as it set itself" },
      ],
      days: ["2026-11-05", "2026-11-06"],
    });
  });

  it("orders events by time, then by taf", () => {
    const a = eventsOf({ id: "b", name: "Bo", color: 1 }, [base], [], "2026-11-06", "2026-11-06", "Europe/Paris", NOW);
    const b = eventsOf({ id: "a", name: "Al", color: 0 }, [base], [], "2026-11-06", "2026-11-06", "Europe/Paris", NOW);
    expect(calendarOf([...a, ...b]).events.map((e) => e.name)).toEqual(["Al", "Bo"]);
  });
});
