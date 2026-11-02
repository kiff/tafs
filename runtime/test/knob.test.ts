import { describe, expect, it } from "vitest";
import { cronWords, dayIn, knobOf, type KnobInput } from "../src/cloud/knob.js";

const now = new Date("2026-11-02T21:00:00Z");
const base: KnobInput = {
  name: "Refunds", does: { label: "Refund", line: "Refunds a payment in Stripe, within its Card." }, unscheduled: false,
  schedule: { id: "s", agent: "a", cron: "0 9 * * 1-5", time_zone: "Europe/Paris", input: "", enabled: true, next: "2026-11-03T08:00:00Z" },
  doors: [{ id: "d1", label: "Email", open: true, words: { state: "Open: messages from you and paired senders to r@x start work.", tap: "Tap to close the email door." } }],
  places: ["door_reply", "app"], today: { done: 12, working: 1, retrying: 1, needs_you: 1 }, zone: "Europe/Paris", now,
};

describe("the dial", () => {
  it("says every ring in tafs cloud's words, with the face's word and tap", () => {
    const k = knobOf(base);
    expect(k.does).toEqual({ label: "Refund", line: "Refunds a payment in Stripe, within its Card." });
    expect(k.wakes.map((w) => [w.kind, w.label, w.state, w.tap])).toEqual([
      ["door", "Email", "Open", "Tap to close the email door."],
      ["schedule", "Weekdays", "Weekdays", undefined],
      ["run_now", "Run now", "Now", "Tap to run once"],
    ]);
    // A door's stop carries its door, for the doors API.
    expect(k.wakes[0]!.door).toBe("d1");
    expect(k.wakes[1]!.words).toBe("Weekdays at 09:00, next Tue 09:00");
    expect(k.when.map((w) => w.kind)).toEqual(["arrivals", "missed", "busy"]);
    expect(k.results.map((r) => [r.place, r.label])).toEqual([["door_reply", "Sender"], ["app", "You"]]);
    expect(k.rim.line).toBe("12 done today · 1 working · 1 retrying · 1 needs you");
    expect(k.draft).toBeNull();
  });

  it("a taf that works from its chat has no schedule, and its face doesn't run it", () => {
    const k = knobOf({ ...base, unscheduled: true, schedule: undefined, doors: [], today: { done: 0, working: 0, retrying: 0, needs_you: 0 } });
    expect(k.wakes.map((w) => w.kind)).toEqual(["run_now"]);
    expect(k.wakes[0]!.tap).toBeUndefined();
    expect(k.when.map((w) => w.kind)).toEqual(["busy"]);
    expect(k.rim.line).toBe("0 done today");
  });

  it("a paused schedule says so", () => {
    const k = knobOf({ ...base, schedule: { ...base.schedule!, enabled: false, next: undefined } });
    expect(k.wakes[1]).toMatchObject({ on: false, state: "Paused", words: "Weekdays at 09:00, paused" });
  });
});

describe("a schedule in words", () => {
  it.each([
    ["0 9 * * *", "09:00", "Every day at 09:00"],
    ["30 8 * * 1-5", "Weekdays", "Weekdays at 08:30"],
    ["0 9 * * 1,3", "Weekly", "Mondays and Wednesdays at 09:00"],
    ["0 9 * * 5", "Fri", "Fridays at 09:00"],
    ["*/15 * * * *", "Every 15 min", "Every 15 minutes"],
    ["*/20 8-17 * * 1-5", "Every 20 min", "Every 20 minutes from 08:00 to 18:00 on weekdays"],
    ["0 7 1 * *", "Monthly", "On day 1 of each month at 07:00"],
    ["5 4 * 2 *", "Schedule", "On a schedule"],
  ])("%s", (cron, label, words) => {
    expect(cronWords(cron)).toEqual({ label, words });
  });

  it("today is the account's day", () => {
    expect(dayIn("2026-11-02T23:30:00Z", "Europe/Paris")).toBe("2026-11-03");
    expect(dayIn("2026-11-02T23:30:00Z", "UTC")).toBe("2026-11-02");
  });
});
