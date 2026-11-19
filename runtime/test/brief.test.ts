import { wordsIn } from "../src/cloud/words/index.js";
import { describe, expect, it } from "vitest";
import { BRIEF_DECISIONS, briefDecisions, briefTimes, buildBrief, isUrgent, laterOf, putOff, type BriefDecisionIn, type BriefWords } from "../src/cloud/brief.js";

const W: BriefWords = {
  decisions: "Decisions", done: "Done", rest: "The rest", later: "Later",
  more: (n) => `${n} more`, restLine: (n) => `${n} other things, all fine`, credits: "12 credits used today",
};
const taf = (id: string, labels: string[] = []) => ({ id, name: id, labels });
const hold = (id: string, over: Partial<BriefDecisionIn> = {}): BriefDecisionIn => ({
  id: `hold:${id}`, ref: { kind: "hold", id, taf: "collect" }, taf: taf("collect", ["money"]),
  question: `Send reminder ${id}?`, answers: [{ words: "Send", answer: "approve", recommended: true }, { words: "Don't", answer: "decline" }],
  why: "It's 30 days late", meanwhile: "Nothing is sent", ...over,
});

describe("Your brief (design v2.1 §7)", () => {
  it("is at 08:00 and 18:00 in the account's time zone", () => {
    // 10:30 in Paris (09:30 UTC, winter): the last brief was 08:00 Paris, the next is 18:00 Paris.
    const t = briefTimes(new Date("2026-11-16T09:30:00Z"), "Europe/Paris");
    expect(t.at.toISOString()).toBe("2026-11-16T07:00:00.000Z");
    expect(t.next_at.toISOString()).toBe("2026-11-16T17:00:00.000Z");
    // 02:00 in Paris: the last was yesterday's 18:00, the next today's 08:00.
    const n = briefTimes(new Date("2026-11-16T01:00:00Z"), "Europe/Paris");
    expect(n.at.toISOString()).toBe("2026-11-15T17:00:00.000Z");
    expect(n.next_at.toISOString()).toBe("2026-11-16T07:00:00.000Z");
    // A zone that isn't one reads as UTC, never a crash.
    expect(briefTimes(new Date("2026-11-16T09:30:00Z"), "Mars/Base").next_at.toISOString()).toBe("2026-11-16T18:00:00.000Z");
    // Summer time: 08:00 Paris is 06:00 UTC.
    expect(briefTimes(new Date("2026-07-01T07:00:00Z"), "Europe/Paris").at.toISOString()).toBe("2026-07-01T06:00:00.000Z");
  });

  it("calls urgent only what tafs cloud's rules say: a deadline before the next brief, money out today, a halt, a blocked connection, or ask-me-at-once", () => {
    const next = new Date("2026-11-16T17:00:00Z");
    expect(isUrgent(hold("a"), next)).toBe(false);
    expect(isUrgent(hold("a", { deadline: "2026-11-16T12:00:00Z" }), next)).toBe(true);
    expect(isUrgent(hold("a", { deadline: "2026-11-17T12:00:00Z" }), next)).toBe(false);
    for (const k of ["money_today", "halt", "blocked", "ask_at_once"] as const) expect(isUrgent(hold("a", { [k]: true }), next)).toBe(true);
  });

  it("lists the urgent first, then by deadline, then the oldest; recommends one answer only with a why; offers Later only for what can wait", () => {
    const now = new Date("2026-11-16T09:30:00Z");
    const next = new Date("2026-11-16T17:00:00Z");
    const out = briefDecisions([
      hold("old", { since: "2026-11-10T00:00:00Z" }),
      hold("late", { deadline: "2026-11-20T00:00:00Z" }),
      hold("halt", { halt: true }),
      hold("equal", { why: undefined, since: "2026-11-01T00:00:00Z" }),
      hold("twice", { answers: [{ words: "A", answer: "a", recommended: true }, { words: "B", answer: "b", recommended: true }] }),
      hold("stuck", { can_wait: false, laterWhy: "It expires before the next brief" }),
    ], now, next, new Map(), W);
    expect(out.map((d) => d.id)).toEqual(["hold:halt", "hold:late", "hold:equal", "hold:old", "hold:twice", "hold:stuck"]);
    expect(out[0]).toMatchObject({ urgent: true });
    expect(out[0]!.later).toBeUndefined();
    // No why: the answers are equals, and no why is shown.
    const equal = out.find((d) => d.id === "hold:equal")!;
    expect(equal.answers.every((a) => !a.recommended)).toBe(true);
    expect(equal.why).toBeUndefined();
    // Two recommended is none recommended.
    expect(out.find((d) => d.id === "hold:twice")!.answers.every((a) => !a.recommended)).toBe(true);
    // One that can't wait says why in place of Later.
    expect(out.find((d) => d.id === "hold:stuck")).toMatchObject({ now_why: "It expires before the next brief" });
    expect(out.find((d) => d.id === "hold:stuck")!.later).toBeUndefined();
    expect(out.find((d) => d.id === "hold:old")).toMatchObject({ later: { words: "Later" }, why: "It's 30 days late" });
  });

  it("keeps a decision put off with Later out until its brief, unless it turned urgent; refuses Later for what's urgent or can't wait", () => {
    const now = new Date("2026-11-16T09:30:00Z");
    const next = new Date("2026-11-16T17:00:00Z");
    const later = new Map([["hold:a", "2026-11-16T17:00:00.000Z"], ["hold:b", "2026-11-16T17:00:00.000Z"]]);
    const out = briefDecisions([hold("a"), hold("b", { money_today: true }), hold("c")], now, next, later, W);
    expect(out.map((d) => d.id)).toEqual(["hold:b", "hold:c"]);
    expect(briefDecisions([hold("a")], new Date("2026-11-16T17:00:00Z"), new Date("2026-11-17T07:00:00Z"), later, W).map((d) => d.id)).toEqual(["hold:a"]);
    expect(putOff(hold("a"), now, "Europe/Paris")).toEqual({ until: "2026-11-16T17:00:00.000Z" });
    expect(putOff(hold("a", { halt: true }), now, "Europe/Paris")).toEqual({ refused: "urgent" });
    expect(putOff(hold("a", { can_wait: false }), now, "Europe/Paris")).toEqual({ refused: "cant_wait" });
  });

  it("shows at most five decisions and counts the rest; narrows to a label; counts each label over the whole account", () => {
    const decisions = Array.from({ length: 7 }, (_, i) => hold(`d${i}`, { since: `2026-11-1${i}T00:00:00Z` }));
    decisions.push(hold("other", { taf: taf("desk", ["support"]), ref: { kind: "step", id: "s1", taf: "desk" } }));
    const b = buildBrief({
      now: new Date("2026-11-16T09:30:00Z"), timeZone: "Europe/Paris", decisions, later: new Map(), rest: 4, words: W,
      cases: [
        { taf: taf("collect", ["money"]), mission: { id: "m1", name: "Money" }, words: "Atelier Dubois, invoice 0142: reminder sent Tuesday", state: "waiting", next_check: "2026-11-20" },
        { taf: taf("desk", ["support"]), words: "Ticket 88: answered", state: "closed" },
      ],
      labels: [{ id: "money", name: "Money", color: "green" }, { id: "support", name: "Support", color: "blue" }, { id: "empty", name: "Empty", color: "red" }],
      tafs: [taf("collect", ["money"]), taf("desk", ["support"])],
    });
    expect(b.decisions).toHaveLength(BRIEF_DECISIONS);
    expect(b.more).toEqual({ count: 3, words: "3 more" });
    expect(b.done.map((g) => g.mission?.name ?? "")).toEqual(["Money", ""]);
    expect(b.rest).toEqual({ count: 4, words: "4 other things, all fine" });
    expect(b.labels).toEqual([
      { id: "money", name: "Money", color: "green", count: 1, waiting: 7 },
      { id: "support", name: "Support", color: "blue", count: 1, waiting: 1 },
    ]);
    expect(b.at).toBe("2026-11-16T07:00:00.000Z");
    const support = buildBrief({ ...{ now: new Date("2026-11-16T09:30:00Z"), timeZone: "Europe/Paris", decisions, later: new Map(), rest: 0, words: W, cases: [], labels: [], tafs: [] }, label: "support" });
    expect(support.decisions.map((d) => d.id)).toEqual(["hold:other"]);
    expect(support.more).toBeUndefined();
  });

  // With a label chosen, the other labels keep their waiting, so their dot still says where to look.
  it("says how many of Coach's chats wait on the person, so the brief accounts for Needs you's coach part; never in a label, never at 0", () => {
    const base = { now: new Date("2026-11-19T17:30:00Z"), timeZone: "Europe/Paris", decisions: [hold("a")], later: new Map(), rest: 3, cases: [], labels: [], tafs: [] };
    const words = { ...W, coachLine: (n: number) => `${n} Coach chats wait for your answer.` };
    expect(buildBrief({ ...base, coachWaiting: 17, words }).coach_waiting).toEqual({ count: 17, words: "17 Coach chats wait for your answer." });
    expect(buildBrief({ ...base, coachWaiting: 0, words }).coach_waiting).toBeUndefined();
    expect(buildBrief({ ...base, words }).coach_waiting).toBeUndefined();
    expect(buildBrief({ ...base, coachWaiting: 17, words, label: "money" }).coach_waiting).toBeUndefined();
    // The words, in English, with its one and other.
    const en = wordsIn("en");
    expect(en.brief_coach_line(1, "one")).toBe("1 Coach chat waits for your answer.");
    expect(en.brief_coach_line(17, "other")).toBe("17 Coach chats wait for your answer.");
  });

  it("keeps every label's waiting over the whole account when one label is chosen, put-off decisions included", () => {
    const decisions = [hold("a"), hold("b"), hold("other", { taf: taf("desk", ["support"]), ref: { kind: "step", id: "s1", taf: "desk" } })];
    const b = buildBrief({
      now: new Date("2026-11-16T09:30:00Z"), timeZone: "Europe/Paris", decisions, later: new Map([["hold:a", "2026-11-16T17:00:00.000Z"]]),
      rest: 0, words: W, cases: [], label: "support",
      labels: [{ id: "money", name: "Money", color: "green" }, { id: "support", name: "Support", color: "blue" }],
      tafs: [taf("collect", ["money"]), taf("desk", ["support"])],
    });
    expect(b.decisions.map((d) => d.id)).toEqual(["hold:other"]);
    expect(b.labels).toEqual([
      { id: "money", name: "Money", color: "green", count: 1, waiting: 2 },
      { id: "support", name: "Support", color: "blue", count: 1, waiting: 1 },
    ]);
  });

  it("always shows every urgent decision, capping only the others; reads an unreadable deadline as urgent; refuses a brief with no time", () => {
    const decisions = [...Array.from({ length: 6 }, (_, i) => hold(`u${i}`, { halt: true })), hold("calm")];
    const b = buildBrief({ now: new Date("2026-11-16T09:30:00Z"), timeZone: "Europe/Paris", decisions, later: new Map(), rest: 0, words: W, cases: [], labels: [], tafs: [] });
    expect(b.decisions.filter((d) => d.urgent)).toHaveLength(6);
    expect(b.more).toEqual({ count: 1, words: "1 more" });
    expect(isUrgent(hold("x", { deadline: "soon" }), new Date("2026-11-16T17:00:00Z"))).toBe(true);
    expect(() => briefTimes(new Date(), "Europe/Paris", [])).toThrow();
    expect(() => briefTimes(new Date(), "Europe/Paris", ["8am"])).toThrow();
  });

  it("keeps only the Laters still ahead of now from what was stored, and drops anything that isn't one", () => {
    const now = new Date("2026-11-16T09:30:00Z");
    expect([...laterOf({ "hold:a": "2026-11-16T17:00:00.000Z", "hold:b": "2026-11-16T07:00:00.000Z", "hold:c": 5, "hold:d": "soon" }, now)]).toEqual([["hold:a", "2026-11-16T17:00:00.000Z"]]);
    for (const kept of [undefined, null, "x", ["hold:a"]]) expect(laterOf(kept, now).size).toBe(0);
  });

  it("says a deadline in each language's own words, with Spanish's \"del\" before a weekday", () => {
    expect(wordsIn("es").brief_by({ hm: "12:00", day: "vie." })).toBe("antes del vie. a las 12:00");
    expect(wordsIn("es").brief_by({ hm: "14:00" })).toBe("antes de las 14:00");
    expect(wordsIn("en").brief_by({ hm: "12:00", day: "Fri" })).toBe("by Fri 12:00");
    expect(wordsIn("de").brief_by({ hm: "14:00" })).toBe("bis 14:00");
  });
});
