import { describe, expect, it } from "vitest";
import type { Run, SessionEvent } from "../src/cloud/agents.js";
import { fromOf, requestDetail } from "../src/cloud/request.js";

const now = new Date("2026-11-02T22:50:00Z");
const run: Run = { id: "run_1", agent: "rae", session: "s1", trigger: "api", source: "email", requester: { kind: "door", id: "h" }, status: "COMPLETED",
  cost_micro_usd: 0, turns: 2, created: "2026-11-02T22:41:00Z", updated: "2026-11-02T22:42:30Z" };
const usage = (seq: number, time: string, waited: unknown[]): SessionEvent => ({ session: "s1", run: "run_1", seq, time, kind: "usage", body: { timing: { ms: 1000, waited } } });

describe("a request, step by step", () => {
  it("says where it came from, each call in KIFF's title, the reply and the end", () => {
    const events = [
      usage(1, "2026-11-02T22:41:20Z", [{ tool: "stripe_charge_k3f9" }]),
      usage(2, "2026-11-02T22:42:00Z", [{ tool: "stripe_refund_k3f9" }, { tool: "unnamed_k3f9", is_error: true }]),
      { session: "s1", run: "other", seq: 3, time: "2026-11-02T22:42:00Z", kind: "usage", body: { timing: { waited: [{ tool: "x" }] } } } as SessionEvent,
    ];
    const titles = new Map([["stripe_charge_k3f9", "Look up a charge"], ["stripe_refund_k3f9", "Refund a payment"]]);
    const d = requestDetail(run, events, [{ run: "run_1", door: "d", app: "email", status: "not_sent", why: "the email provider was down", at: "2026-11-02T22:42:20Z" }], "Europe/Paris", now, titles);
    expect(d.from).toBe("By email");
    expect(d.steps.map((s) => [s.label, s.line, s.state])).toEqual([
      ["Received", "By email, 23:41", "done"],
      ["Look up a charge", "23:41", "done"],
      ["Refund a payment", "23:42", "done"],
      ["Worked on it", "Didn't go through · 23:42", "failed"],
      ["Reply not delivered", "Reply not sent: the email provider was down", "failed"],
      ["Done", "23:42", "done"],
    ]);
  });

  it("a retrying request says its next try, and that the last one needs you", () => {
    const d = requestDetail({ ...run, status: "RUNNING", state: "retrying", try: 3, of: 3, next_at: "2026-11-02T22:15:00Z" }, [], [], "Europe/Paris", now);
    expect(d.steps.at(-1)).toEqual({ label: "Next try 23:15", line: "If it fails again, this needs you", at: "2026-11-02T22:15:00Z", state: "next" });
    expect(requestDetail({ ...run, status: "RUNNING", state: "retrying", try: 1, of: 3 }, [], [], "UTC", now).steps.at(-1)).toMatchObject({ label: "Next try soon", line: "Try 2 of 3" });
  });

  it("a held call waits for the owner", () => {
    const held: SessionEvent = { session: "s1", run: "run_1", seq: 1, time: "2026-11-02T22:41:30Z", kind: "tool_result", body: { state: "held", hold: { exception: "x" } } };
    const d = requestDetail({ ...run, status: "PAUSED", requirements: [{ exception: "x" } as never] }, [held], [], "UTC", now);
    expect(d.steps.map((s) => s.label)).toEqual(["Received", "Held for your approval", "Waiting for your approval"]);
  });

  it("where it came from", () => {
    expect(fromOf({ ...run, source: "whatsapp" })).toBe("By WhatsApp");
    expect(fromOf({ ...run, source: undefined, requester: undefined, trigger: "schedule" })).toBe("Its schedule");
    expect(fromOf({ ...run, source: undefined, requester: { kind: "member", id: "ana" } })).toBe("From its chat");
    expect(fromOf({ ...run, source: undefined, requester: undefined, trigger: "api" })).toBe("Run now");
  });
});
