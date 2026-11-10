import { describe, expect, it } from "vitest";
import { checkOf, instantIn, nearestEvery, plannedOf, schedulesOf, TriggerError, triggerOf, type Trigger } from "../src/cloud/triggers.js";

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
    // An hour step that doesn't divide the day fires unevenly across midnight: refused, the nearest offered.
    expect(refused({ kind: "schedule", form: "every", every_minutes: 300 })).toEqual({ why: "every 300 minutes can't be kept exactly", nearest: "every 6 hours" });
    expect(refused({ kind: "schedule", form: "every", every_minutes: 600 })).toEqual({ why: "every 600 minutes can't be kept exactly", nearest: "every 12 hours" });
    expect(crons({ kind: "schedule", form: "every", every_minutes: 480 })).toEqual(["0 */8 * * *"]);
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

describe("a schedule read back as the dial's form", () => {
  it.each<Trigger>([
    { kind: "schedule", form: "daily", at: "09:00" },
    { kind: "schedule", form: "weekdays", at: "08:30" },
    { kind: "schedule", form: "weekly", at: "09:00", days: ["mon", "wed"] },
    { kind: "schedule", form: "monthly", at: "07:00", date: "1" },
    { kind: "schedule", form: "monthly_last_day", at: "18:00" },
    { kind: "schedule", form: "times_a_day", times: ["09:00", "15:00"] },
    { kind: "schedule", form: "every", every_minutes: 20 },
    { kind: "schedule", form: "every", every_minutes: 120, days: ["mon", "tue", "wed", "thu", "fri"] },
    { kind: "schedule", form: "every", every_minutes: 30, between: { from: "08:00", to: "18:00" } },
    { kind: "schedule", form: "every", every_minutes: 60, between: { from: "09:00", to: "17:00" } },
    { kind: "schedule", form: "daily", at: "09:00", skip: ["2026-11-04T09:00"] },
  ])("$form", (t) => {
    const [s] = schedulesOf(t);
    expect(triggerOf({ cron: s!.cron ?? "", kind: s!.kind, skip: s!.skip })).toEqual(t);
  });

  it("a once time is read in the account's zone", () => {
    const at = instantIn("2026-11-05T09:00", "Europe/Paris");
    expect(triggerOf({ cron: "", kind: "once", at }, "Europe/Paris")).toEqual({ kind: "schedule", form: "once", date: "2026-11-05T09:00" });
  });

  it("a schedule the dial can't set has no form", () => {
    expect(triggerOf({ cron: "5 4 * 2 *" })).toBeUndefined();
    expect(triggerOf({ cron: "0 9 * * 1-3" })).toBeUndefined();
  });
});

describe("a connected app's event as a trigger", () => {
  it("is checked and scheduled nowhere", () => {
    expect(schedulesOf({ kind: "app_event", grant: "grt_1", event: "JIRA_ISSUE_UPDATED", name: "Jira · issue changes" })).toEqual([]);
    expect(() => schedulesOf({ kind: "app_event", grant: "../x", event: "E", name: "n" })).toThrow(TriggerError);
    expect(() => schedulesOf({ kind: "app_event", grant: "g", event: "E" })).toThrow("name the owner picked");
  });
});

// A taf's every schedule reads its work first (kiff/agents' check), and kiff/agents keeps a check's cursor per schedule.
describe("a taf that checks its work first", () => {
  const check = { tool: "list_requests_k3f9", arguments: { status: "open" }, items: "data", cursor: "created", first: "all" as const };

  it("puts the taf's check on each schedule trigger's schedule, and leaves an app check's own", () => {
    const appCheck: Trigger = { kind: "app_check", operation: "list_charges", items: "data", cursor: "created", every_minutes: 30 };
    expect(plannedOf([{ kind: "schedule", form: "weekdays", at: "09:00" }, { kind: "door" }], "skip", check)).toEqual([{ kind: "cron", cron: "0 9 * * 1-5", busy: "skip", check }]);
    expect(plannedOf([appCheck], "skip", check)[0]!.check).toEqual({ tool: "list_charges", arguments: {}, items: "data", cursor: "created" });
    // Without a check, nothing changes: as many schedules as the triggers make.
    expect(plannedOf([{ kind: "schedule", form: "times_a_day", times: ["09:00", "12:30"] }]).map((p) => p.cron)).toEqual(["0 9 * * *", "30 12 * * *"]);
  });

  it("keeps one timetable: more is refused with the nearest one in words, never merged", () => {
    const refused = (wakes: Trigger[]) => {
      try { plannedOf(wakes, "skip", check); } catch (err) { return err instanceof TriggerError ? err.nearest : "not a TriggerError"; }
      return "kept";
    };
    expect(refused([{ kind: "schedule", form: "times_a_day", times: ["09:00", "12:30", "17:00"] }])).toBe("one list of times on the same minute: every day at 09:00, 12:00 and 17:00");
    expect(refused([{ kind: "schedule", form: "daily", at: "08:15" }, { kind: "schedule", form: "times_a_day", times: ["12:00"] }])).toBe("one list of times on the same minute: every day at 08:15 and 12:15");
    expect(refused([{ kind: "schedule", form: "monthly", at: "07:00", date: "1" }, { kind: "schedule", form: "daily", at: "09:00" }])).toBe("one of them alone, such as on day 1 of each month at 07:00");
    expect(refused([{ kind: "schedule", form: "times_a_day", times: ["09:00", "15:00"] }])).toBe("kept");
  });

  it("checks the read: one of the taf's tools, a cursor, a dotted path to the list, small plain arguments, and first new or all", () => {
    expect(checkOf({ tool: "list_requests_k3f9", items: "", cursor: "id" }, ["list_requests_k3f9"])).toEqual({ tool: "list_requests_k3f9", arguments: {}, items: "", cursor: "id" });
    expect(checkOf({ ...check, id: "number" })).toEqual({ ...check, id: "number" });
    expect(() => checkOf(check, ["send_message_k3f9"])).toThrow("check.tool names list_requests_k3f9, which isn't among the taf's tools (send_message_k3f9)");
    expect(() => checkOf(check, [])).toThrow("(none named)");
    for (const bad of [{ ...check, cursor: " " }, { ...check, items: ".data" }, { ...check, items: undefined }, { ...check, arguments: null }, { ...check, arguments: "x" },
      { ...check, arguments: { q: "x".repeat(2_048) } }, { ...check, arguments: { q: "é".repeat(1_100) } }, { ...check, first: "old" }, { ...check, tool: "" }, { ...check, id: 3 }, null, []]) {
      expect(() => checkOf(bad), JSON.stringify(bad)).toThrow();
    }
  });
});
