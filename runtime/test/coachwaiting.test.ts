import { describe, expect, it } from "vitest";
import type { Run } from "../src/cloud/agents.js";
import { coachWaitingOf, waitingOn } from "../src/cloud/coachwaiting.js";
import { LANGUAGES, plural, wordsIn } from "../src/cloud/words/index.js";

const now = new Date("2026-11-14T10:00:00Z");
const run = (input: Record<string, unknown>, extra: Record<string, unknown> = {}) => ({
  id: "r1", agent: "coach", session: "s1", status: "PAUSED", created: "2026-11-14T09:00:00Z", updated: "2026-11-14T09:00:00Z",
  requester: { kind: "member", id: "u_ana" },
  requirements: [{ call_id: "q1", tool: "show", input: { id: "q1", ...input }, operation: "", expires: "2026-11-20T09:00:00Z", kind: "card", ...extra }],
}) as unknown as Run;

// The owner asked a new job in a finished chat: the row named the chat's first message ("Set up a taf that every Friday…"),
// not the question Coach was waiting on.
describe("a waiting Coach row names what waits", () => {
  it("in the card's own words: its title, else its first question, else its text", () => {
    expect(waitingOn(run({ type: "approval", title: "Connect Gmail", text: "Approve it once." }), now)).toBe("Connect Gmail");
    expect(waitingOn(run({ type: "questions", questions: [{ id: "a", text: "Which day should it remind you?" }, { id: "b", text: "When?" }] }), now))
      .toBe("Which day should it remind you");
    expect(waitingOn(run({ type: "confirm", text: "Set it up now?" }), now)).toBe("Set it up now");
  });

  it("over the chat's first message, which may be another job's", () => {
    const [row] = coachWaitingOf([run({ type: "questions", questions: [{ id: "a", text: "Which day should it remind you?" }] })], "coach", "u_ana", now, "en", "UTC",
      new Map([["s1", "Set up a taf that every Friday at 17:00 writes me a short summary"]]));
    expect(row!.words.line).toBe("Which day should it remind you: Coach is waiting for your answer since 14 Nov.");
  });

  it("falls back to the chat's title when the card says nothing, and to no name with neither", () => {
    const silent = run({ type: "approval" });
    expect(waitingOn(silent, now)).toBeUndefined();
    expect(coachWaitingOf([silent], "coach", "u_ana", now, "en", "UTC", new Map([["s1", "Refunds"]]))[0]!.words.line)
      .toBe("Refunds: Coach is waiting for your approval since 14 Nov.");
    expect(coachWaitingOf([silent], "coach", "u_ana", now)[0]!.words.line).toBe("Coach is waiting for your approval.");
  });

  it("skips an answered or expired card, and a card that doesn't wait on the person", () => {
    expect(waitingOn(run({ type: "questions", title: "Old" }, { answered: true }), now)).toBeUndefined();
    expect(waitingOn(run({ type: "questions", title: "Old" }, { expires: "2026-11-14T09:30:00Z" }), now)).toBeUndefined();
    expect(waitingOn(run({ type: "result", title: "Refunds is ready" }), now)).toBeUndefined();
  });

  it("cuts a long name at a word, with an ellipsis", () => {
    const long = "Should it look through every unanswered email from your customers in the last two weeks, or only the ones marked urgent?";
    const named = waitingOn(run({ type: "questions", questions: [{ id: "a", text: long }] }), now)!;
    expect(named.length).toBeLessThanOrEqual(81);
    expect(named.endsWith("…")).toBe(true);
    expect(long.startsWith(named.slice(0, -1))).toBe(true);
  });
});

// After Set aside, Coach opened blank and the owner went looking for the chat: the answer says where it is.
describe("the words after a set-aside", () => {
  it("say where the chats are, in every language, by count", () => {
    expect(wordsIn("en").coach_waiting_set_aside_done(1, plural("en", 1))).toBe("Set aside. You'll find it in Previous conversations.");
    expect(wordsIn("en").coach_waiting_set_aside_done(3, plural("en", 3))).toBe("3 chats set aside. You'll find them in Previous conversations.");
    expect(wordsIn("pl").coach_waiting_set_aside_done(5, plural("pl", 5))).toBe("Odłożono 5 rozmów. Znajdziesz je w „Poprzednie rozmowy”.");
    for (const lang of LANGUAGES) for (const n of [1, 2, 5]) expect(wordsIn(lang).coach_waiting_set_aside_done(n, plural(lang, n)), lang).toMatch(/[.。]$/);
  });
});

describe("a Coach chat that handed a taf over", () => {
  it("is one with a result answered with the taf made, never a failed or pending one", async () => {
    const { handedOver } = await import("../src/cloud/handler.js");
    expect(handedOver([{ kind: "answered", body: { type: "result", answer: { taf_id: "taf_9" } } }])).toBe(true);
    expect(handedOver([{ kind: "answered", body: { type: "result", answer: { failed: "a taf named Refunds exists" } } }])).toBe(false);
    expect(handedOver([{ kind: "answered", body: { type: "result", answer: { version: 3 } } }])).toBe(false);
    expect(handedOver([{ kind: "answered", body: { type: "questions", answer: { taf_id: "x" } } }])).toBe(false);
    expect(handedOver([{ kind: "component", body: { type: "result" } }])).toBe(false);
  });
});
