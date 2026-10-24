import { describe, expect, it } from "vitest";
import { apsFor, AWAY_MINUTES, levelOf, presentUntil } from "../src/cloud/levels.js";

// kiff/tafs#210, part 5: each notification's level, decided in tafs cloud as it sends.
describe("notification levels", () => {
  it("interrupts for a hold waiting for the owner, around or away", () => {
    expect(levelOf("hold", false)).toBe("interrupt");
    expect(levelOf("hold", true)).toBe("interrupt");
  });

  it("interrupts for a halt, around or away", () => {
    expect(levelOf("halt", false)).toBe("interrupt");
    expect(levelOf("halt", true)).toBe("interrupt");
  });

  it("tells quietly of a run that ended while the owner is around, and keeps it for later while away", () => {
    expect(levelOf("run_ended", false)).toBe("quiet");
    expect(levelOf("run_ended", true)).toBe("later");
  });

  it("tells a request answered quietly: it goes to one person, not to the owner's Coach", () => {
    expect(levelOf("request_answered", false)).toBe("quiet");
    expect(levelOf("request_answered", true)).toBe("quiet");
  });

  it("tells a request for tasks quietly, for the same reason", () => {
    expect(levelOf("tasks_requested", false)).toBe("quiet");
    expect(levelOf("tasks_requested", true)).toBe("quiet");
  });

  it("makes an interruption Time Sensitive with a sound, and a quiet one passive with none", () => {
    expect(apsFor("interrupt")).toEqual({ sound: "default", "interruption-level": "time-sensitive" });
    expect(apsFor("quiet")).toEqual({ "interruption-level": "passive" });
  });

  it("counts the owner around for AWAY_MINUTES after tafs was open", () => {
    expect(presentUntil(new Date("2026-10-24T12:00:00Z")).toISOString())
      .toBe(new Date(Date.parse("2026-10-24T12:00:00Z") + AWAY_MINUTES * 60_000).toISOString());
  });
});
