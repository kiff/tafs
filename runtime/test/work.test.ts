import { describe, expect, it } from "vitest";
import type { Run } from "../src/cloud/agents.js";
import { requestOf, summaryOf, timeIn, workOf } from "../src/cloud/work.js";

const NOW = new Date("2026-10-30T12:00:00Z");
const run = (o: Partial<Run>): Run => ({
  id: "r1", agent: "taf-a", session: "s", trigger: "door", status: "COMPLETED", cost_micro_usd: 0, turns: 1,
  created: "2026-10-30T11:00:00Z", updated: "2026-10-30T11:05:00Z", ...o,
});

describe("a taf's work, as both apps show it", () => {
  it("writes every time in the account's zone: the time today, the day and time another day", () => {
    expect(timeIn("2026-10-30T11:20:00Z", "Europe/Tallinn", NOW)).toBe("13:20");
    expect(timeIn("2026-10-28T07:00:00Z", "Europe/Tallinn", NOW)).toBe("Wed 09:00");
    // A zone it can't read is UTC, never an error.
    expect(timeIn("2026-10-30T11:20:00Z", "Not/AZone", NOW)).toBe("11:20");
  });

  it("says a retrying run's next try and count, and a needs_you run's reason as kiff/agents gives it", () => {
    expect(requestOf(run({ status: "PAUSED", state: "retrying", try: 1, of: 3, next_at: "2026-10-30T12:20:00Z" }), "Europe/Tallinn", NOW).line)
      .toBe("It stopped while it worked. Trying again at 14:20, 1 of 3.");
    const stuck = "It stopped 3 times while it worked, and was restarted each time from its last safe step. It needs you to look at it.";
    expect(requestOf(run({ status: "ERROR", state: "needs_you", reason: stuck }), "UTC", NOW)).toMatchObject({ state: "needs_you", line: stuck });
  });

  it("puts what needs the owner first on the list: a run past its restarts, or a held call", () => {
    const held = run({ id: "r2", status: "PAUSED", requirements: [{ call_id: "c", tool: "refund", input: {}, operation: "op", exception: "ex1", expires: "2026-10-30T13:00:00Z" }] });
    expect(summaryOf([held, run({})], false, "UTC", NOW)).toEqual({ status: "needs_you", line: "Waiting for your approval", needs_you: 1 });
    expect(summaryOf([held, run({ id: "r3", status: "ERROR", state: "needs_you", reason: "x" })], false, "UTC", NOW)).toMatchObject({ status: "needs_you", line: "2 requests need you", needs_you: 2 });
    // One from more than a week ago stays on the taf's page, not the list.
    expect(summaryOf([run({ status: "ERROR", state: "needs_you", reason: "x", updated: "2026-10-01T00:00:00Z" })], false, "UTC", NOW).status).toBe("idle");
    expect(summaryOf([run({ status: "PAUSED", state: "retrying", next_at: "2026-10-30T12:05:00Z" })], false, "UTC", NOW).status).toBe("retrying");
    expect(summaryOf([run({ status: "RUNNING" })], true, "UTC", NOW)).toEqual({ status: "working", line: "Working on it", needs_you: 0 });
    expect(summaryOf([], true, "UTC", NOW)).toEqual({ status: "paused", line: "Paused", needs_you: 0 });
    expect(summaryOf([run({})], false, "UTC", NOW)).toEqual({ status: "idle", line: "Last done at 11:05", needs_you: 0 });
    // Halted comes before anything; a taf with no schedule says it works from its chat; one with a schedule, its next run.
    expect(summaryOf([run({ status: "RUNNING" })], { halted: true }, "UTC", NOW)).toEqual({ status: "halted", line: "Halted · resume it from Halt", needs_you: 0 });
    expect(summaryOf([], { unscheduled: true }, "UTC", NOW).line).toBe("Works from its chat");
    expect(summaryOf([], { next: "2026-10-31T07:00:00Z" }, "Europe/Tallinn", NOW).line).toBe("Next run Sat 09:00");
  });

  it("counts the taf's requests by state, each with what was sent through its door", () => {
    const w = workOf([run({ id: "r1" }), run({ id: "r2", status: "RUNNING" })], "UTC", NOW,
      [{ run: "r1", door: "email", app: "email", status: "sent", at: "2026-10-30T11:06:00Z" }]);
    expect(w.counts).toMatchObject({ done: 1, working: 1 });
    expect(w.requests[0]!.deliveries).toEqual([{ place: "door_reply", state: "sent", line: "Replied at 11:06" }]);
    expect(w.requests[1]!.deliveries).toEqual([]);
  });
});
