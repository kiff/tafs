import { describe, expect, it } from "vitest";
import type { Run } from "../src/cloud/agents.js";
import { requestOf, runFailure, summaryOf, timeIn, workOf } from "../src/cloud/work.js";
import { requestDetail } from "../src/cloud/request.js";

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

  it("says what a run waiting for a vendor's event waits for, in the connection's words", () => {
    const wait = { call_id: "c1", tool: "desk_reply", input: {}, operation: "", expires: "2026-10-30T13:00:00Z", kind: "event", wait: { grant: "grt_desk", event: "DESK_NEW_TICKET", key: "reply_1", words: "The desk answers when the customer replies." } };
    expect(requestOf(run({ status: "PAUSED", requirements: [wait] }), "UTC", NOW)).toMatchObject({ state: "waiting", line: "The desk answers when the customer replies.", waiting_for: "The desk answers when the customer replies." });
    // No words from the connection: the event is named; an approval waiting comes first; an answered wait says nothing.
    expect(requestOf(run({ status: "PAUSED", requirements: [{ ...wait, wait: { ...wait.wait, words: "" } }] }), "UTC", NOW).line).toBe("Waiting for the app's DESK_NEW_TICKET event");
    expect(requestOf(run({ status: "PAUSED", requirements: [wait, { ...wait, call_id: "c2", kind: undefined, exception: "exc_1" }] }), "UTC", NOW).line).toBe("Waiting for your approval");
    expect(requestOf(run({ status: "PAUSED", requirements: [{ ...wait, answered: "2026-10-30T11:30:00Z" }] }), "UTC", NOW)).toMatchObject({ line: "Waiting for an answer" });
    expect(requestOf(run({ status: "PAUSED", requirements: [{ ...wait, answered: "2026-10-30T11:30:00Z" }] }), "UTC", NOW).waiting_for).toBeUndefined();
    // The taf's summary says so too, in the same words, instead of idle: after what needs the owner and what works, before paused.
    const waiting = run({ status: "PAUSED", requirements: [wait] });
    expect(summaryOf([waiting], true, "UTC", NOW)).toEqual({ status: "waiting_on_app", line: "The desk answers when the customer replies.", needs_you: 0 });
    expect(summaryOf([waiting, run({ id: "r2", status: "RUNNING" })], false, "UTC", NOW).status).toBe("working");
    expect(summaryOf([run({ status: "PAUSED", requirements: [wait, { ...wait, call_id: "c2", kind: undefined, exception: "exc_1" }] })], false, "UTC", NOW).status).toBe("needs_you");
    expect(summaryOf([run({ status: "PAUSED", requirements: [{ ...wait, answered: "2026-10-30T11:30:00Z" }] })], true, "UTC", NOW).status).toBe("paused");
    // An open card for the owner beside the wait: the run is the owner's to answer, not the app's wait, on the request and on the summary.
    const card = { call_id: "c3", tool: "show", input: { type: "confirm", id: "which" }, operation: "", expires: "2026-10-30T13:00:00Z", kind: "card" };
    const both = run({ status: "PAUSED", requirements: [wait, card] });
    expect(requestOf(both, "UTC", NOW)).toMatchObject({ state: "waiting", line: "Waiting for an answer" });
    expect(requestOf(both, "UTC", NOW).waiting_for).toBeUndefined();
    expect(summaryOf([both], true, "UTC", NOW).status).toBe("paused");
    // The card answered, the wait is the app's again.
    expect(summaryOf([run({ status: "PAUSED", requirements: [wait, { ...card, answered: "2026-10-30T11:40:00Z" }] })], true, "UTC", NOW).status).toBe("waiting_on_app");
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

describe("a run cut off, never read as done", () => {
  // kiff/agents ends a run that hit its turn or cost cap as COMPLETED, with its stop: it didn't finish.
  it("says a run stopped at its turn cap or its cost cap, and which, on its line, its request page and the list", () => {
    const turns = run({ stop: "turn_cap", turns: 50 });
    const cost = run({ stop: "cost_cap" });
    expect(requestOf(turns, "UTC", NOW)).toMatchObject({ state: "stopped", line: "Stopped at 11:05: it reached its limit of turns for one run" });
    expect(requestOf(cost, "UTC", NOW)).toMatchObject({ state: "stopped", line: "Stopped at 11:05: it reached its cost limit for one run" });
    expect(requestDetail(turns, [], [], "UTC", NOW).steps.at(-1)).toEqual({ label: "Stopped", line: "Stopped at 11:05: it reached its limit of turns for one run", state: "failed" });
    expect(requestDetail(cost, [], [], "UTC", NOW).steps.at(-1)).toMatchObject({ label: "Stopped", line: "Stopped at 11:05: it reached its cost limit for one run" });
    expect(workOf([turns, cost], "UTC", NOW, []).counts).toMatchObject({ done: 0, stopped: 2 });
    // The list's line for the taf: the last one cut off says so, never "Last done".
    expect(summaryOf([turns], false, "UTC", NOW).line).toBe("Stopped at 11:05: it reached its limit of turns for one run");
    expect(summaryOf([cost], false, "UTC", NOW).line).toBe("Stopped at 11:05: it reached its cost limit for one run");
  });

  it("reads Done on the stop, never on the turn count: a run that answered on its last allowed turn is done", () => {
    expect(requestOf(run({ stop: "answered", turns: 50 }), "UTC", NOW)).toMatchObject({ state: "done", line: "Done at 11:05" });
    // A run from before kiff/agents kept a stop reads as it did.
    expect(requestOf(run({ turns: 50 }), "UTC", NOW)).toMatchObject({ state: "done", line: "Done at 11:05" });
    expect(summaryOf([run({ stop: "answered" })], false, "UTC", NOW).line).toBe("Last done at 11:05");
  });

  it("reads a stop it doesn't know as stopped, never as success", () => {
    for (const stop of ["max_token", "refused", "something_new"]) {
      expect(requestOf(run({ stop }), "UTC", NOW), stop).toMatchObject({ state: "stopped", line: "Stopped at 11:05" });
      expect(summaryOf([run({ stop })], false, "UTC", NOW).line, stop).toBe("Stopped at 11:05");
    }
  });

  it("says it in the person's language with the glossary's Stopped, never Halted's word", () => {
    const stopped = { de: "Gestoppt", fr: "Arrêté", es: "Detenido", it: "Fermato", pt: "Parado", nl: "Gestopt", pl: "Zatrzymane" } as const;
    const halted = { de: "Angehalten", fr: "Bloqué", es: "Bloqueado", it: "Bloccato", pt: "Bloqueado", nl: "Stilgezet", pl: "Zablokowane" } as const;
    for (const lang of Object.keys(stopped) as Array<keyof typeof stopped>) {
      for (const stop of ["turn_cap", "cost_cap", "max_token"]) {
        const line = requestOf(run({ stop }), "UTC", NOW, [], undefined, [], lang).line;
        expect(line.startsWith(stopped[lang]), `${lang} ${stop}: ${line}`).toBe(true);
        expect(line.toLowerCase().includes(halted[lang].toLowerCase()), `${lang} ${stop}: ${line}`).toBe(false);
      }
    }
    expect(requestOf(run({ stop: "turn_cap" }), "UTC", NOW, [], undefined, [], "de").line).toBe("Gestoppt um 11:05: er hat die Höchstzahl an Schritten für einen Lauf erreicht");
    expect(requestOf(run({ stop: "cost_cap" }), "UTC", NOW, [], undefined, [], "pl").line).toBe("Zatrzymane o 11:05: osiągnięto limit kosztów na jedno uruchomienie");
  });
});

describe("a plan in one line", () => {
  it("names the step at work, or the first still to do, or says it's done", async () => {
    const { withLine } = await import("../src/cloud/handler.js");
    const plan = (statuses: string[]) => ({ type: "steps", id: "p", steps: statuses.map((status, i) => ({ label: `Step ${i + 1} words`, status })) });
    expect((withLine(plan(["done", "done", "active", "pending"])) as { line: string }).line).toBe("Step 3 of 4 · Step 3 words");
    expect((withLine(plan(["done", "skipped", "pending"])) as { line: string }).line).toBe("Step 3 of 3 · Step 3 words");
    expect((withLine(plan(["done", "done"])) as { line: string }).line).toBe("Done · 2 of 2");
    // Any other card is left as it came.
    expect(withLine({ type: "notice", id: "n" })).toEqual({ type: "notice", id: "n" });
  });
});

describe("the thread as one ordered list", () => {
  it("places messages and cards by time, a card shown again replacing its earlier version where it first was", async () => {
    const { threadItems } = await import("../src/cloud/handler.js");
    const msg = (id: string, at: string) => ({ id, at, from: "taf" as const, kind: "message" as const, text: id, session: "s" });
    const card = (id: string, at: string, title: string, seq: number) => ({ at, session: "s", run: "r", seq, component: { type: "steps", id, title } });
    const items = threadItems(
      [msg("m1", "2026-10-30T10:00:00Z"), msg("m2", "2026-10-30T10:02:00Z")],
      [card("plan", "2026-10-30T10:01:00Z", "first", 1), card("plan", "2026-10-30T10:03:00Z", "latest", 2)],
    );
    expect(items.map((i) => (i.type === "message" ? i.message.id : `card:${(i.card.component as { title: string }).title}`))).toEqual(["m1", "card:latest", "m2"]);
  });

  it("puts a held call where KIFF held it, by KIFF's keys only", async () => {
    const { threadItems } = await import("../src/cloud/handler.js");
    const msg = (id: string, at: string) => ({ id, at, from: "taf" as const, kind: "message" as const, text: id, session: "s" });
    const items = threadItems([msg("m1", "2026-10-30T10:00:00Z"), msg("m2", "2026-10-30T10:05:00Z")], [],
      [{ at: "2026-10-30T10:02:00Z", session: "s", run: "r", exception: "ex_1", invocation: "inv_1" }]);
    expect(items.map((i) => i.type)).toEqual(["message", "held", "message"]);
    expect(items[1]).toEqual({ type: "held", held: { at: "2026-10-30T10:02:00Z", session: "s", run: "r", exception: "ex_1", invocation: "inv_1" } });
  });
});

describe("a failed run's code", () => {
  const words = "Our AI provider isn't serving us right now, so this stopped. That's on our side, not yours: none of your credits were used. Try again a little later.";
  it("makes the request's line kiff/agents' words, with the code beside them; a failure with no code reads as before", () => {
    expect(requestOf(run({ status: "ERROR", error: words, code: "new_code" }), "UTC", NOW)).toMatchObject({ state: "failed", line: words, code: "new_code" });
    const plain = requestOf(run({ status: "ERROR", error: "Stripe said no." }), "UTC", NOW);
    expect(plain).toMatchObject({ state: "failed", line: "It failed: Stripe said no." });
    expect(plain).not.toHaveProperty("code");
  });

  it("is passed on only for an ERROR run with a code and words, in one line of at most 400 characters", () => {
    expect(runFailure({ status: "ERROR", error: `  ${words}\n`, code: "new_code" })).toEqual({ code: "new_code", words });
    expect(runFailure({ status: "ERROR", error: "a\n\nb", code: "new_code" })).toEqual({ code: "new_code", words: "a b" });
    expect(runFailure({ status: "ERROR", error: "x".repeat(500), code: "new_code" })!.words).toHaveLength(400);
    expect(runFailure({ status: "COMPLETED", error: words, code: "ai_unavailable" })).toBeUndefined();
    expect(runFailure({ status: "ERROR", error: words })).toBeUndefined();
    expect(runFailure({ status: "ERROR", error: "  ", code: "new_code" })).toBeUndefined();
    expect(runFailure({ status: "ERROR", error: words, code: "Ai-Unavailable" })).toBeUndefined();
  });
});
