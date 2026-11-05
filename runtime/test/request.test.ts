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
    expect(d.steps.at(-1)).toEqual({ label: "Next try 23:15", line: "If it fails again, this needs you", state: "next" });
    expect(requestDetail({ ...run, status: "RUNNING", state: "retrying", try: 1, of: 3 }, [], [], "UTC", now).steps.at(-1)).toMatchObject({ label: "Next try soon", line: "Try 2 of 3" });
  });

  it("a call that failed and was retried with the same tool shows the retry's outcome only", () => {
    const events = [
      usage(1, "2026-11-02T22:41:20Z", [{ tool: "stripe_charge_k3f9", is_error: true }]),
      usage(2, "2026-11-02T22:42:00Z", [{ tool: "stripe_charge_k3f9" }, { tool: "stripe_refund_k3f9", is_error: true }]),
    ];
    const titles = new Map([["stripe_charge_k3f9", "Look up a charge"], ["stripe_refund_k3f9", "Refund a payment"]]);
    const d = requestDetail(run, events, [], "UTC", now, titles);
    expect(d.steps.map((s) => [s.label, s.state])).toEqual([
      ["Received", "done"],
      ["Look up a charge", "done"],
      ["Refund a payment", "failed"],
      ["Done", "done"],
    ]);
  });

  it("a held call waits for the owner", () => {
    const held: SessionEvent = { session: "s1", run: "run_1", seq: 1, time: "2026-11-02T22:41:30Z", kind: "tool_result", body: { state: "held", hold: { exception: "x" } } };
    const d = requestDetail({ ...run, status: "PAUSED", requirements: [{ exception: "x" } as never] }, [held], [], "UTC", now);
    expect(d.steps.map((s) => s.label)).toEqual(["Received", "Held for your approval", "Waiting for your approval"]);
    // Waiting for the owner: no step is the app's.
    expect(d.steps.some((s) => s.waits_on)).toBe(false);
  });

  it("a call whose outcome comes later waits for the app, in the connection's words, and the step says so", () => {
    const wait = { call_id: "c1", tool: "desk_reply", input: {}, operation: "", expires: "2026-11-02T23:00:00Z", kind: "event", wait: { grant: "grt_desk", event: "DESK_NEW_TICKET", key: "reply_1", words: "The desk answers when the customer replies." } };
    const d = requestDetail({ ...run, status: "PAUSED", requirements: [wait] }, [], [], "UTC", now);
    expect(d.waiting_for).toBe("The desk answers when the customer replies.");
    expect(d.steps.at(-1)).toEqual({ label: "The desk answers when the customer replies.", state: "waiting", waits_on: "app" });
    // An open card for the owner beside the wait: the step is the owner's, with nothing on the app.
    const card = { call_id: "c3", tool: "show", input: { type: "confirm", id: "which" }, operation: "", expires: "2026-11-02T23:00:00Z", kind: "card" };
    const withCard = requestDetail({ ...run, status: "PAUSED", requirements: [wait, card] }, [], [], "UTC", now);
    expect(withCard.waiting_for).toBeUndefined();
    expect(withCard.steps.at(-1)).toEqual({ label: "Waiting for an answer", state: "waiting" });
    // Answered, the run goes on: nothing waits on the app any more.
    expect(requestDetail({ ...run, status: "RUNNING", requirements: [{ ...wait, answered: "2026-11-02T22:50:00Z" }] }, [], [], "UTC", now).steps.at(-1)).toEqual({ label: "Working on it", state: "next" });
  });

  it("where it came from", () => {
    expect(fromOf({ ...run, source: "whatsapp" })).toBe("By WhatsApp");
    expect(fromOf({ ...run, source: undefined, requester: undefined, trigger: "schedule" })).toBe("Its schedule");
    expect(fromOf({ ...run, source: undefined, requester: { kind: "member", id: "ana" } })).toBe("From its chat");
    expect(fromOf({ ...run, source: undefined, requester: undefined, trigger: "api" })).toBe("Run now");
  });
});
