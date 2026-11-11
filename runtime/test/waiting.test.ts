import { describe, expect, it } from "vitest";
import type { Fired, Run } from "../src/cloud/agents.js";
import { durationIn, late, lateAlert, oldestWaiting, waitingView } from "../src/cloud/waiting.js";
import { LANGUAGES } from "../src/cloud/words/index.js";

const NOW = new Date("2026-10-29T10:00:00Z");
const ago = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000).toISOString();
const fired = (minutes: number, outcome: string): Fired => ({ due: ago(minutes), at: ago(minutes), outcome });
const run = (minutes: number, more: Partial<Run>): Run =>
  ({ id: `run_${minutes}`, agent: "rae", session: "s", trigger: "schedule", status: "COMPLETED", cost_micro_usd: 0, turns: 2, created: ago(minutes + 1), updated: ago(minutes), ...more }) as Run;
const held = (minutes: number) => run(minutes, { status: "PAUSED", requirements: [{ call_id: "c", tool: "stripe_refund", input: {}, operation: "o", exception: "exc_1", expires: ago(-60) }] });

describe("a taf's oldest waiting case", () => {
  it("is the oldest of a call waiting for the owner and found work that couldn't start", () => {
    // A held call since 50 minutes ago, and work waiting for credits since 90: the work is older.
    expect(oldestWaiting([held(50)], [fired(15, "skipped_credits"), fired(90, "skipped_busy"), fired(120, "run")])).toEqual({ since: ago(90), kind: "work" });
    // Only the held call.
    expect(oldestWaiting([held(50), run(200, {})], [fired(15, "quiet")])).toEqual({ since: ago(50), kind: "held" });
    // A run past its last try, waiting for the owner, counts the same.
    expect(oldestWaiting([run(70, { status: "ERROR", state: "needs_you" })], [])).toEqual({ since: ago(70), kind: "held" });
  });

  it("ends at the last firing that ran or was quiet, and never counts work set aside as too big", () => {
    expect(oldestWaiting([], [fired(15, "run"), fired(30, "skipped_credits")])).toBeUndefined();
    expect(oldestWaiting([], [fired(15, "skipped_missed"), fired(30, "quiet"), fired(45, "skipped_credits")])).toEqual({ since: ago(15), kind: "work" });
    expect(oldestWaiting([], [fired(15, "skipped_too_large"), fired(30, "skipped_too_large")])).toBeUndefined();
    // A held call that was answered waits for nothing.
    const answered = run(40, { status: "PAUSED", requirements: [{ call_id: "c", tool: "t", input: {}, operation: "o", exception: "e", expires: ago(-60), answered: ago(30) }] });
    expect(oldestWaiting([answered], [])).toBeUndefined();
  });

  it("says how long in the owner's words, and whether it's past their promise", () => {
    expect(durationIn(25 * 60_000)).toBe("25 min");
    expect(durationIn(5 * 3600_000 + 20 * 60_000)).toBe("5 h");
    expect(durationIn(3 * 86_400_000)).toBe("3 days");
    expect(durationIn(1 * 86_400_000 + 30 * 3600_000)).toBe("2 days");
    const w = { since: ago(5 * 60 + 10), kind: "held" as const };
    expect(late(w, undefined, NOW)).toBe(false);
    expect(late(w, 6, NOW)).toBe(false);
    expect(late(w, 4, NOW)).toBe(true);
    expect(waitingView(w, 6, NOW)).toEqual({ since: ago(310), words: "Oldest case waiting 5 h", late: false });
    expect(waitingView(w, 4, NOW)).toEqual({ since: ago(310), words: "Oldest case waiting 5 h, past the 4 h you set", late: true });
    expect(lateAlert("Refunds", w, 4, NOW)).toEqual({ title: "Refunds", body: "A case has waited 5 h, past the 4 h you set." });
  });

  it("says it in all eight languages, each its own", () => {
    const w = { since: ago(3 * 86_400_000 / 60_000), kind: "work" as const };
    const en = waitingView(w, 4, NOW, "en").words;
    for (const lang of LANGUAGES) {
      const view = waitingView(w, 4, NOW, lang);
      const alert = lateAlert("Refunds", w, 4, NOW, lang).body;
      for (const line of [view.words, alert]) {
        expect(line, lang).toMatch(/3/);
        expect(line, lang).toMatch(/4/);
        expect(line, lang).not.toMatch(/undefined|NaN/);
      }
      if (lang !== "en") expect(view.words, lang).not.toBe(en);
    }
  });
});
