import { describe, expect, it } from "vitest";
import { nearestEvery, schedulesOf, TriggerError, type Trigger } from "../src/cloud/triggers.js";

const crons = (t: Trigger, busy?: "skip" | "latest") => schedulesOf(t, busy).map((s) => s.cron ?? s.at);

describe("the dial's triggers, as kiff/agents keeps them", () => {
  it.each<[Trigger, string[]]>([
    [{ kind: "schedule", form: "daily", at: "09:00" }, ["0 9 * * *"]],
    [{ kind: "schedule", form: "weekdays", at: "08:30" }, ["30 8 * * 1-5"]],
    [{ kind: "schedule", form: "weekly", at: "09:00", days: ["wed", "mon", "Mon"] }, ["0 9 * * 1,3"]],
    [{ kind: "schedule", form: "monthly", at: "07:00", date: "1" }, ["0 7 1 * *"]],
    [{ kind: "schedule", form: "times_a_day", times: ["15:00", "09:00", "12:30"] }, ["0 9,15 * * *", "30 12 * * *"]],
    [{ kind: "schedule", form: "every", every_minutes: 20, between: { from: "08:00", to: "18:00" }, days: ["mon", "tue", "wed", "thu", "fri"] }, ["*/20 8-17 * * 1,2,3,4,5"]],
    [{ kind: "schedule", form: "every", every_minutes: 120 }, ["0 */2 * * *"]],
    [{ kind: "schedule", form: "once", date: "2026-11-05T09:00" }, ["2026-11-05T09:00"]],
  ])("%j", (t, want) => {
    expect(crons(t)).toEqual(want);
  });

  it("the last day of the month is its own kind, fired daily and run on the last day only", () => {
    expect(schedulesOf({ kind: "schedule", form: "monthly_last_day", at: "18:00" })).toEqual([{ kind: "monthly_last_day", cron: "0 18 * * *", busy: "skip" }]);
  });

  it("an app check is a cron with its read, the list and the field it compares", () => {
    expect(schedulesOf({ kind: "app_check", operation: "gmail_list", args: { q: "refund" }, items: "messages", cursor: "internalDate", every_minutes: 15 }, "latest")).toEqual([
      { kind: "cron", cron: "*/15 * * * *", busy: "latest", check: { tool: "gmail_list", arguments: { q: "refund" }, items: "messages", cursor: "internalDate" } },
    ]);
  });

  it("doors and vendor events wake it without a schedule", () => {
    expect(schedulesOf({ kind: "door" })).toEqual([]);
    expect(schedulesOf({ kind: "vendor_event" })).toEqual([]);
  });

  it("refuses what the scheduler can't keep exactly, with the nearest form in words", () => {
    const refused = (t: Trigger) => {
      try {
        schedulesOf(t);
      } catch (err) {
        expect(err).toBeInstanceOf(TriggerError);
        return { why: (err as TriggerError).message, nearest: (err as TriggerError).nearest };
      }
      throw new Error("accepted");
    };
    expect(refused({ kind: "schedule", form: "every", every_minutes: 25 })).toEqual({ why: "every 25 minutes can't be kept exactly", nearest: "every 20 minutes" });
    expect(refused({ kind: "schedule", form: "every", every_minutes: 5 })).toEqual({ why: "every 5 minutes can't be kept exactly", nearest: "every 15 minutes" });
    expect(refused({ kind: "schedule", form: "every", every_minutes: 90 })).toMatchObject({ nearest: "every 2 hours" });
    expect(refused({ kind: "schedule", form: "every", every_minutes: 30, between: { from: "08:30", to: "18:00" } })).toMatchObject({ nearest: "from 08:00 to 18:00" });
    expect(refused({ kind: "schedule", form: "monthly", at: "09:00", date: "31" })).toMatchObject({ nearest: "the last day of the month" });
    expect(refused({ kind: "schedule", form: "daily", at: "9am" }).why).toContain("time of day");
    expect(refused({ kind: "before_event" }).why).toContain("isn't available yet");
  });

  it("the nearest every-N is one the scheduler keeps", () => {
    expect([1, 15, 17, 18, 24, 26, 45, 50, 60, 100, 150].map(nearestEvery)).toEqual([15, 15, 15, 20, 20, 30, 30, 60, 60, 120, 180]);
  });
});

describe("a once trigger's time", () => {
  it("is the wall clock in the account's zone, as an instant", async () => {
    const { instantIn } = await import("../src/cloud/triggers.js");
    expect(instantIn("2026-11-05T09:00", "Europe/Paris")).toBe("2026-11-05T08:00:00Z");
    expect(instantIn("2026-07-05T09:00", "Europe/Paris")).toBe("2026-07-05T07:00:00Z");
    expect(instantIn("2026-11-05T09:00", "UTC")).toBe("2026-11-05T09:00:00Z");
    expect(instantIn("2026-11-05T09:00", "America/New_York")).toBe("2026-11-05T14:00:00Z");
  });
});
