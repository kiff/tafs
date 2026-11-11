import { describe, expect, it } from "vitest";
import type { Fired, Run } from "../src/cloud/agents.js";
import { healthLine, healthOf, healthView } from "../src/cloud/health.js";
import { LANGUAGES } from "../src/cloud/words/index.js";

const NOW = new Date("2026-10-29T10:00:00Z");
const ago = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000).toISOString();

/** A run that ended `minutes` ago. Newest first, as kiff/agents lists them. */
function run(minutes: number, status: Run["status"], more: Partial<Run> = {}): Run {
  return { id: `run_${minutes}`, agent: "rae", session: "s", trigger: "schedule", status, cost_micro_usd: 0, turns: 3, created: ago(minutes + 1), updated: ago(minutes), ...more } as Run;
}
const ok = (m: number) => run(m, "COMPLETED", { stop: "answered" });
const bad = (m: number) => run(m, "ERROR", { error: "the run failed" });
const capped = (m: number, stop = "turn_cap") => run(m, "COMPLETED", { stop });

/** A firing due `minutes` ago. */
const fired = (minutes: number, outcome: string): Fired => ({ due: ago(minutes), at: ago(minutes), outcome });
const firings = (...outcomes: string[]) => outcomes.map((o, i) => fired(15 * (i + 1), o));

const codes = (runs: Run[], f: Fired[] = []) => healthOf(runs, f, NOW).reasons.map((r) => r.code);
const level = (runs: Run[], f: Fired[] = []) => healthOf(runs, f, NOW).level;

describe("a taf's health", () => {
  it("is healthy with nothing behind it, and with runs that worked", () => {
    expect(healthOf([], [], NOW)).toEqual({ level: "healthy", reasons: [] });
    expect(level([ok(10), ok(20), ok(30)], firings("run", "quiet", "run"))).toBe("healthy");
  });

  it("counts a run of failures at its schedule: one is at risk, two are failing", () => {
    expect(healthOf([], firings("failed_unworked", "run"), NOW)).toMatchObject({ level: "at_risk", reasons: [{ code: "streak_one" }] });
    expect(healthOf([], firings("failed_unworked", "failed_unworked", "run"), NOW)).toMatchObject({ level: "failing", reasons: [{ code: "streak", n: 2, since: ago(30) }] });
    expect(healthOf([], firings("failed_check", "quiet"), NOW)).toMatchObject({ level: "at_risk", reasons: [{ code: "check_failing_one" }] });
    expect(healthOf([], firings("failed_check", "failed_check", "failed_check", "quiet"), NOW)).toMatchObject({ level: "failing", reasons: [{ code: "check_failing", n: 3 }] });
  });

  it("is failing at once when a run dropped its work", () => {
    expect(healthOf([], firings("failed_unfinished", "run"), NOW)).toMatchObject({ level: "failing", reasons: [{ code: "dropped", since: ago(15) }] });
    // Named by the newest failure of the spell, which began at its oldest.
    expect(healthOf([], firings("failed_unfinished", "failed_unworked", "run"), NOW)).toMatchObject({ level: "failing", reasons: [{ code: "dropped", since: ago(30) }] });
  });

  it("counts failed runs: one of ten is healthy, two are at risk, three in a row are failing", () => {
    const nine = Array.from({ length: 9 }, (_, i) => ok(20 + i * 10));
    expect(level([bad(10), ...nine])).toBe("healthy");
    expect(healthOf([bad(10), ok(15), bad(18), ...nine], [], NOW)).toMatchObject({ level: "at_risk", reasons: [{ code: "failed_some", n: 2, of: 10 }] });
    expect(healthOf([bad(10), bad(15), ok(18), ...nine], [], NOW)).toMatchObject({ level: "at_risk", reasons: [{ code: "failed_some", n: 2, of: 10 }] });
    expect(healthOf([bad(10), bad(15), bad(18), ...nine], [], NOW)).toMatchObject({ level: "failing", reasons: [{ code: "all_failed", n: 3, since: ago(18) }] });
    // Four in a row say four, and the spell began at the oldest of them.
    expect(healthOf([bad(10), bad(15), bad(18), bad(19), ok(40)], [], NOW)).toMatchObject({ reasons: [{ code: "all_failed", n: 4, since: ago(19) }] });
  });

  it("counts runs cut short by a limit: one of ten is healthy, two are at risk", () => {
    const eight = Array.from({ length: 8 }, (_, i) => ok(30 + i * 10));
    expect(level([capped(10), ok(20), ...eight])).toBe("healthy");
    expect(healthOf([capped(10), capped(20, "cost_cap"), ...eight], [], NOW)).toMatchObject({ level: "at_risk", reasons: [{ code: "capped", n: 2, of: 10 }] });
    // A reply that ran out of room is cut short like a cap, and a declined turn is a failure.
    expect(codes([capped(10, "max_token"), capped(20), ...eight])).toEqual(["capped"]);
    expect(codes([run(10, "COMPLETED", { stop: "refused" }), bad(20), ...eight])).toEqual(["failed_some"]);
    expect(level([run(10, "COMPLETED", { stop: "refused" }), run(12, "COMPLETED", { stop: "refused" }), bad(20), ...eight])).toBe("failing");
  });

  it("counts skipped firings: two of twenty are healthy, three are at risk", () => {
    const rest = Array.from({ length: 17 }, () => "run");
    expect(level([], firings("skipped_busy", "skipped_missed", ...rest))).toBe("healthy");
    expect(healthOf([], firings("skipped_busy", "run", "skipped_missed", "skipped_busy", ...rest), NOW)).toMatchObject({ level: "at_risk", reasons: [{ code: "skipped", n: 3, of: 20 }] });
  });

  it("says a run whose work goes to the next run, while that's the newest firing", () => {
    expect(healthOf([], firings("failed_retrying", "run"), NOW)).toMatchObject({ level: "at_risk", reasons: [{ code: "retrying" }] });
    expect(level([], firings("run", "failed_retrying", "run"))).toBe("healthy");
    // A skip since doesn't change that: its work still waits for the next run.
    expect(codes([], firings("skipped_halted", "failed_retrying", "run"))).toEqual(["retrying"]);
    // A failure since does: that's what's said, and the failures before the retry are another spell.
    expect(healthOf([], firings("failed_unworked", "failed_retrying", "failed_unworked", "failed_unworked", "run"), NOW)).toMatchObject({ level: "at_risk", reasons: [{ code: "streak_one", since: ago(15) }] });
  });

  it("never reads an empty queue as a problem: quiet firings are healthy, however many", () => {
    expect(level([], firings(...Array.from({ length: 20 }, () => "quiet")))).toBe("healthy");
    expect(level([], firings("quiet", "quiet", "quiet", "quiet"))).toBe("healthy");
  });

  it("never counts a skip for a halt or a switch, a run paused for its owner, or one its owner stopped", () => {
    for (const outcome of ["skipped_halted", "skipped_switching", "skipped_by_owner", "waiting", "skipped_replaced", "skipped_dropped"]) {
      expect(level([], firings(...Array.from({ length: 20 }, () => outcome))), outcome).toBe("healthy");
    }
    const waits = Array.from({ length: 10 }, (_, i) => run(10 + i, "PAUSED"));
    expect(level(waits)).toBe("healthy");
    const stopped = Array.from({ length: 10 }, (_, i) => run(10 + i, "CANCELLED", { stop: "cancelled" }));
    expect(level(stopped)).toBe("healthy");
    // A run kiff/tafs already shows as needing the owner, or as retrying, isn't counted a second time.
    const needs = Array.from({ length: 5 }, (_, i) => run(10 + i, "ERROR", { state: "needs_you" } as Partial<Run>));
    expect(level(needs)).toBe("healthy");
    const retrying = Array.from({ length: 5 }, (_, i) => run(10 + i, "ERROR", { state: "retrying" } as Partial<Run>));
    expect(level(retrying)).toBe("healthy");
  });

  it("is never healthy when its check found work too big for one run: at risk once, failing twice in a row, with Coach to narrow it", () => {
    // The wave-1 case: an oversized batch starts no run, and the taf doesn't read healthy.
    const once = healthOf([ok(60)], firings("skipped_too_large", "run"), NOW);
    expect(once.level).toBe("at_risk");
    expect(once.reasons.map((r) => r.code)).toEqual(["too_large_one"]);
    const twice = healthOf([], firings("skipped_too_large", "skipped_too_large", "run"), NOW);
    expect(twice).toMatchObject({ level: "failing", reasons: [{ code: "too_large", n: 2 }] });
    expect(healthView(twice)).toEqual({
      level: "failing",
      lines: ["Its last 2 checks found work too big for one run, so nothing ran. Have Coach narrow what it reads, so each piece fits one run."],
      fixes: [{ line: 0, kind: "coach", words: "Narrow it with Coach", say: "Its last check found work too big for one run. Narrow what it reads, so each piece fits one run." }],
    });
    // A firing that ran ends it, as any spell.
    expect(level([], firings("run", "skipped_too_large", "skipped_too_large"))).toBe("healthy");
  });

  it("ends a spell of checks that couldn't read the app at one that found work too big: that check read it", () => {
    const h = healthOf([], firings("skipped_too_large", "failed_check", "failed_check"), NOW);
    expect(h).toMatchObject({ level: "at_risk", reasons: [{ code: "too_large_one" }] });
    expect(healthView(h).lines).toEqual(["Its last check found work too big for one run, so nothing ran. Have Coach narrow what it reads, so each piece fits one run."]);
  });

  it("looks past a skip that counts for nothing to the too-large or credits spell behind it", () => {
    expect(codes([], firings("skipped_halted", "skipped_too_large", "skipped_too_large"))).toEqual(["too_large"]);
    expect(codes([], firings("skipped_switching", "skipped_credits"))).toEqual(["credits"]);
    // Nor a firing whose run failed on our side: the AI provider wasn't serving us.
    const down = run(15, "ERROR", { id: "run_down", code: "ai_unavailable" });
    const f = [{ ...fired(15, "failed_unworked"), run: "run_down" }, fired(30, "skipped_credits")];
    expect(codes([down], f)).toEqual(["credits"]);
    // A skip that counts for nothing doesn't join two spells across a firing that worked.
    expect(level([], firings("skipped_switching", "run", "skipped_credits"))).toBe("healthy");
  });

  it("is never healthy while its work waits for credits, with the way to add them, or to ask an admin", () => {
    const h = healthOf([], firings("skipped_credits", "skipped_credits", "run"), NOW);
    expect(h).toMatchObject({ level: "at_risk", reasons: [{ code: "credits", since: ago(30) }] });
    expect(healthView(h, "en", { billing: true }, "UTC", NOW)).toEqual({
      level: "at_risk", lines: ["Waiting for credits since 09:30."], fixes: [{ line: 0, kind: "credits", words: "Add credits" }],
    });
    expect(healthView(h, "en", {}, "UTC", NOW).fixes).toEqual([{ line: 0, kind: "credits", words: "Ask an admin for credits" }]);
    expect(level([], firings("run", "skipped_credits"))).toBe("healthy");
    // A reason with no fix carries none, so an app that reads `lines` alone sees what it always did.
    expect(healthView(healthOf([bad(1), bad(2), bad(3)], [], NOW)).fixes).toBeUndefined();
  });

  it("says a scheduled run that never started once, from its schedule, never a second time from its runs", () => {
    // kiff/agents leaves a run behind each time: ERROR, with no turn.
    const never = (m: number) => run(m, "ERROR", { turns: 0, error: "the run didn't start" });
    const three = [never(15), never(30), never(45)];
    expect(healthOf(three, firings("failed_unworked", "failed_unworked", "failed_unworked", "run"), NOW)).toMatchObject({ level: "failing", reasons: [{ code: "streak", n: 3 }] });
    expect(healthOf(three, firings("failed_unworked", "failed_unworked", "failed_unworked", "run"), NOW).reasons).toHaveLength(1);
    // A run a version switch stopped before it began is the same kind of run, and is never a failure.
    expect(level(three, firings("skipped_switching", "skipped_switching", "skipped_switching"))).toBe("healthy");
    // With no schedule times to say it (a read of its runs alone), they still count for nothing.
    expect(level(three)).toBe("healthy");
    // A run its owner started that fails before a turn still counts, and so does a scheduled one that worked first.
    const owner = (m: number) => run(m, "ERROR", { turns: 0, trigger: "owner" });
    expect(healthOf([owner(15), owner(30), owner(45)], [], NOW)).toMatchObject({ level: "failing", reasons: [{ code: "all_failed", n: 3 }] });
    expect(level([run(15, "ERROR", { turns: 1 }), run(30, "ERROR", { turns: 2 }), run(45, "ERROR", { turns: 1 })])).toBe("failing");
  });

  it("never counts our own outage: a run the AI provider didn't serve, or the scheduled time that started it", () => {
    const down = (m: number, more: Partial<Run> = {}) => run(m, "ERROR", { code: "ai_unavailable", error: "The AI isn't available right now.", ...more });
    // Scheduled runs that never started for it, with their times: nothing against the taf.
    const never = [down(15, { id: "r1", turns: 0 }), down(30, { id: "r2", turns: 0 }), down(45, { id: "r3", turns: 0 })];
    const times = [{ ...fired(15, "failed_unworked"), run: "r1" }, { ...fired(30, "failed_unworked"), run: "r2" }, { ...fired(45, "failed_unworked"), run: "r3" }];
    expect(healthOf(never, times, NOW)).toEqual({ level: "healthy", reasons: [] });
    // Runs it cut off after they began, and chat runs: the same.
    expect(level([down(15), down(30), down(45)])).toBe("healthy");
    expect(level([down(15, { trigger: "owner" }), down(30, { trigger: "owner" }), down(45, { trigger: "owner" })])).toBe("healthy");
    // They neither fail nor recover: the taf's own failures around them still count.
    const mixed = [bad(10), down(20), bad(30), down(40), bad(50)];
    expect(healthOf(mixed, [], NOW)).toMatchObject({ level: "failing", reasons: [{ code: "all_failed", n: 3 }] });
    const around = [{ ...fired(15, "failed_unworked"), run: "r1" }, fired(30, "failed_unworked"), { ...fired(45, "failed_unworked"), run: "r3" }, fired(60, "failed_unworked"), fired(75, "run")];
    expect(healthOf(never, around, NOW)).toMatchObject({ level: "failing", reasons: [{ code: "streak", n: 2, since: ago(60) }] });
    // A scheduled time whose run failed for another reason still counts.
    expect(healthOf([run(15, "ERROR", { id: "r1", turns: 0 }), run(30, "ERROR", { id: "r2", turns: 0 })], times.slice(0, 2), NOW)).toMatchObject({ level: "failing", reasons: [{ code: "streak", n: 2 }] });
  });

  it("is healthy for an outcome or a stop it doesn't know, without a crash", () => {
    expect(level([run(10, "COMPLETED", { stop: "something_new" }), run(20, "COMPLETED", { stop: "something_new" })], firings("brand_new_outcome", "brand_new_outcome", "another_one"))).toBe("healthy");
    // An unknown outcome neither fails nor recovers: the failures around it still count.
    expect(healthOf([], firings("failed_unworked", "brand_new_outcome", "failed_unworked", "run"), NOW)).toMatchObject({ level: "failing", reasons: [{ code: "streak", n: 2 }] });
    // A skip between two failures doesn't break the run of failures either.
    expect(codes([], firings("failed_check", "skipped_halted", "failed_check", "quiet"))).toEqual(["check_failing"]);
  });

  it("recovers: one firing that worked ends the run of failures, and old history stops counting", () => {
    expect(level([], firings("run", "failed_unworked", "failed_unworked", "failed_unworked"))).toBe("healthy");
    expect(level([], firings("quiet", "failed_check", "failed_check"))).toBe("healthy");
    expect(level([], firings("run", "failed_unfinished"))).toBe("healthy");
    expect(level([ok(5), bad(10), bad(15), bad(18)])).toBe("at_risk"); // three of four failed, but no longer in a row
    // Past the window nothing counts: eight days ago is history.
    const old = 8 * 24 * 60;
    expect(level([bad(old), bad(old + 1), bad(old + 2)], [fired(old, "failed_unworked"), fired(old + 15, "failed_unworked")])).toBe("healthy");
    // Only the last ten runs and twenty firings count.
    const ten = Array.from({ length: 10 }, (_, i) => ok(10 + i));
    expect(level([...ten, bad(30), bad(31), bad(32)])).toBe("healthy");
    const twenty = Array.from({ length: 20 }, () => "run");
    expect(level([], firings(...twenty, "skipped_busy", "skipped_busy", "skipped_busy"))).toBe("healthy");
  });

  it("puts what's failing first, and gives every reason", () => {
    const h = healthOf([capped(10), capped(11), bad(12), bad(13), ok(14)], firings("failed_unworked", "failed_unworked", "run"), NOW);
    expect(h.level).toBe("failing");
    expect(h.reasons.map((r) => r.code)).toEqual(["streak", "failed_some", "capped"]);
    // Whatever order they're found in: skipped times are at risk, three failed runs in a row are failing.
    const mixed = healthOf([bad(10), bad(11), bad(12)], firings("skipped_busy", "skipped_busy", "skipped_missed", "run"), NOW);
    expect(mixed.reasons.map((r) => r.code)).toEqual(["all_failed", "skipped"]);
    expect(healthView(mixed).lines[0]).toBe("Its last 3 runs failed. Open one to see why.");
  });

  it("says each reason in words: what happened, then what the owner can do", () => {
    const view = (runs: Run[], f: Fired[] = []) => healthView(healthOf(runs, f, NOW)).lines;
    const eight = Array.from({ length: 8 }, (_, i) => ok(30 + i * 10));
    expect(view([], firings("failed_unworked", "failed_unworked", "run"))).toEqual(["Its last 2 scheduled runs couldn't start. Open one to see why."]);
    expect(view([], firings("failed_unworked", "run"))).toEqual(["Its last scheduled run couldn't start. It tries again at its next time."]);
    expect(view([], firings("failed_check", "failed_check", "run"))).toEqual(["Its check couldn't read the app 2 times in a row. Check the app's connection."]);
    expect(view([], firings("failed_check", "run"))).toEqual(["Its last check couldn't read the app. It tries again at its next time."]);
    expect(view([], firings("failed_unfinished", "run"))).toEqual(["A scheduled run stopped part-way, and what it was working on won't be tried again. Open that run to see what it did."]);
    expect(view([bad(10), bad(15), bad(18)])).toEqual(["Its last 3 runs failed. Open one to see why."]);
    expect(view([bad(10), ok(12), bad(18), ...eight])).toEqual(["2 of its last 10 runs failed. Open one to see why."]);
    expect(view([capped(10), capped(20), ...eight])).toEqual(["2 of its last 10 runs were cut short by a limit. Open one to see which limit stopped it."]);
    expect(view([], firings("skipped_busy", "skipped_busy", "skipped_missed", "run"))).toEqual(["3 of its last 4 scheduled runs were skipped. Open one to see why."]);
    expect(view([], firings("failed_retrying", "run"))).toEqual(["Its last run didn't finish, so its work goes to the next run."]);
  });

  it("has every reason's words in all eight languages, with none of the runtime's own names in them", () => {
    const every = [
      healthOf([], firings("failed_unworked", "failed_unworked", "run"), NOW), healthOf([], firings("failed_unworked", "run"), NOW),
      healthOf([], firings("failed_check", "failed_check", "run"), NOW), healthOf([], firings("failed_check", "run"), NOW),
      healthOf([], firings("failed_unfinished", "run"), NOW), healthOf([bad(1), bad(2), bad(3)], [], NOW),
      healthOf([bad(1), ok(2), bad(3), ok(4)], [], NOW), healthOf([capped(1), capped(2), ok(3)], [], NOW),
      healthOf([], firings("skipped_busy", "skipped_busy", "skipped_missed", "run"), NOW), healthOf([], firings("failed_retrying", "run"), NOW),
      healthOf([], firings("skipped_too_large", "skipped_too_large", "run"), NOW), healthOf([], firings("skipped_too_large", "run"), NOW),
      healthOf([], firings("skipped_credits", "run"), NOW),
    ];
    expect(new Set(every.flatMap((h) => h.reasons.map((r) => r.code))).size).toBe(13);
    for (const lang of LANGUAGES) {
      for (const h of every) {
        const view = healthView(h, lang, { billing: true });
        const fixes = [...(view.fixes ?? []), ...(healthView(h, lang).fixes ?? [])].flatMap((f) => [f.words, f.say ?? "the button's words"]);
        for (const line of [...view.lines, ...h.reasons.map((r) => healthLine(r, lang)), ...fixes]) {
          expect(line.length, `${lang}: ${line}`).toBeGreaterThan(10);
          expect(line, lang).not.toMatch(/cursor|firing|failed_|skipped_|undefined/i);
          expect(line, lang).not.toMatch(/NaN/);
        }
      }
      // The other seven don't fall back to English.
      if (lang !== "en") expect(healthView(every[5]!, lang).lines[0]).not.toBe(healthView(every[5]!, "en").lines[0]);
    }
  });
});
