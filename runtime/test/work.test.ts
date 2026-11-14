import { describe, expect, it } from "vitest";
import type { Run } from "../src/cloud/agents.js";
import { failedLine, needsYouLine, requestOf, runFailure, skippedOf, summaryOf, timeIn, workOf } from "../src/cloud/work.js";
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
    expect(requestOf(turns, "UTC", NOW)).toMatchObject({ state: "stopped", line: "Stopped at 11:05: it reached its limit of steps for one run" });
    expect(requestOf(cost, "UTC", NOW)).toMatchObject({ state: "stopped", line: "Stopped at 11:05: it reached its cost limit for one run" });
    expect(requestDetail(turns, [], [], "UTC", NOW).steps.at(-1)).toEqual({ label: "Stopped", line: "Stopped at 11:05: it reached its limit of steps for one run", state: "failed" });
    expect(requestDetail(cost, [], [], "UTC", NOW).steps.at(-1)).toMatchObject({ label: "Stopped", line: "Stopped at 11:05: it reached its cost limit for one run" });
    expect(workOf([turns, cost], "UTC", NOW, []).counts).toMatchObject({ done: 0, stopped: 2 });
    // The list's line for the taf: the last one cut off says so, never "Last done".
    expect(summaryOf([turns], false, "UTC", NOW).line).toBe("Stopped at 11:05: it reached its limit of steps for one run");
    expect(summaryOf([cost], false, "UTC", NOW).line).toBe("Stopped at 11:05: it reached its cost limit for one run");
  });

  it("reads Done on the stop, never on the turn count: a run that answered on its last allowed turn is done", () => {
    expect(requestOf(run({ stop: "answered", turns: 50 }), "UTC", NOW)).toMatchObject({ state: "done", line: "Done at 11:05" });
    // A run from before kiff/agents kept a stop reads as it did.
    expect(requestOf(run({ turns: 50 }), "UTC", NOW)).toMatchObject({ state: "done", line: "Done at 11:05" });
    expect(summaryOf([run({ stop: "answered" })], false, "UTC", NOW).line).toBe("Last done at 11:05");
  });

  it("reads a stop it doesn't know as stopped, never as success", () => {
    expect(requestOf(run({ stop: "something_new" }), "UTC", NOW)).toMatchObject({ state: "stopped", line: "Stopped at 11:05" });
    expect(summaryOf([run({ stop: "something_new" })], false, "UTC", NOW).line).toBe("Stopped at 11:05");
  });

  it("says why a run stopped when its reply ran out of room or the AI declined to continue", () => {
    expect(requestOf(run({ stop: "max_token" }), "UTC", NOW)).toMatchObject({ state: "stopped", line: "Stopped at 11:05: its reply ran out of room" });
    expect(requestOf(run({ stop: "refused" }), "UTC", NOW)).toMatchObject({ state: "stopped", line: "Stopped at 11:05: the AI declined to continue" });
    expect(requestOf(run({ stop: "refused" }), "UTC", NOW, [], undefined, [], "fr").line).toBe("Arrêté à 11:05 : l'IA a refusé de continuer");
    expect(requestOf(run({ stop: "max_token" }), "UTC", NOW, [], undefined, [], "pl").line).toBe("Zatrzymane o 11:05: w odpowiedzi zabrakło miejsca");
  });

  it("says it in the person's language with the glossary's Stopped, never Halted's word", () => {
    const stopped = { de: "Gestoppt", fr: "Arrêté", es: "Detenido", it: "Fermato", pt: "Parado", nl: "Gestopt", pl: "Zatrzymane" } as const;
    const halted = { de: "Angehalten", fr: "Bloqué", es: "Bloqueado", it: "Bloccato", pt: "Bloqueado", nl: "Stilgezet", pl: "Zablokowane" } as const;
    for (const lang of Object.keys(stopped) as Array<keyof typeof stopped>) {
      for (const stop of ["turn_cap", "cost_cap", "max_token", "refused"]) {
        const line = requestOf(run({ stop }), "UTC", NOW, [], undefined, [], lang).line;
        expect(line.startsWith(stopped[lang]), `${lang} ${stop}: ${line}`).toBe(true);
        expect(line.toLowerCase().includes(halted[lang].toLowerCase()), `${lang} ${stop}: ${line}`).toBe(false);
      }
    }
    expect(requestOf(run({ stop: "turn_cap" }), "UTC", NOW, [], undefined, [], "de").line).toBe("Gestoppt um 11:05: er hat die Höchstzahl an Schritten für einen Lauf erreicht");
    expect(requestOf(run({ stop: "cost_cap" }), "UTC", NOW, [], undefined, [], "pl").line).toBe("Zatrzymane o 11:05: osiągnięto limit kosztów na jedno uruchomienie");
  });
});

describe("a capped run never reads Done, on the work page, the request page or the list", () => {
  const LANGS = ["en", "de", "fr", "es", "it", "pt", "nl", "pl"] as const;

  it("never says Done for a run stopped at its turn or cost cap, in any language (negative)", async () => {
    const { wordsIn } = await import("../src/cloud/words/index.js");
    for (const lang of LANGS) {
      const w = wordsIn(lang);
      const done = w.work_done_at({ hm: "11:05" });
      const lastDone = w.work_last_done({ hm: "11:05" });
      for (const stop of ["turn_cap", "cost_cap"]) {
        const r = run({ stop, turns: 50 });
        const q = requestOf(r, "UTC", NOW, [], undefined, [], lang);
        expect(q.state, `${lang} ${stop}`).not.toBe("done");
        expect(q.line, `${lang} ${stop}`).not.toBe(done);
        // The request page: no "Done" step, and its last step is the stopped line.
        const steps = requestDetail(r, [], [], "UTC", NOW, undefined, undefined, [], lang).steps;
        expect(steps.some((s) => s.state === "done" && s.label === w.step_done), `${lang} ${stop}`).toBe(false);
        expect(steps.at(-1)!.line, `${lang} ${stop}`).toBe(stop === "turn_cap" ? w.work_capped_turns({ hm: "11:05" }) : w.work_capped_cost({ hm: "11:05" }));
        // The list, with or without a next run: never "Last done".
        for (const how of [false, { next: "2026-10-31T07:00:00Z" }, { unscheduled: true }]) {
          expect(summaryOf([r], how, "UTC", NOW, lang).line, `${lang} ${stop}`).not.toBe(lastDone);
        }
        expect(workOf([r], "UTC", NOW, [], undefined, undefined, lang).counts.done, `${lang} ${stop}`).toBe(0);
      }
    }
    // In English, the word itself is absent from every line said of a capped run.
    for (const stop of ["turn_cap", "cost_cap"]) {
      const r = run({ stop, turns: 50 });
      expect(requestOf(r, "UTC", NOW).line).not.toMatch(/\bDone\b/i);
      expect(summaryOf([r], false, "UTC", NOW).line).not.toMatch(/\bdone\b/i);
      expect(JSON.stringify(requestDetail(r, [], [], "UTC", NOW).steps)).not.toMatch(/\bDone\b/);
      expect(workOf([r], "UTC", NOW, []).requests[0]!.line).not.toMatch(/\bDone\b/);
    }
  });

  it("says plainly which limit stopped it, and when", () => {
    expect(requestOf(run({ stop: "turn_cap" }), "Europe/Tallinn", NOW).line).toBe("Stopped at 13:05: it reached its limit of steps for one run");
    expect(requestOf(run({ stop: "cost_cap" }), "Europe/Tallinn", NOW).line).toBe("Stopped at 13:05: it reached its cost limit for one run");
  });

  it("still reads Done for an ordinary completed run, on its line, its request page and the list", () => {
    for (const stop of ["answered", undefined]) {
      const r = run(stop ? { stop } : {});
      expect(requestOf(r, "UTC", NOW)).toMatchObject({ state: "done", line: "Done at 11:05" });
      expect(requestDetail(r, [], [], "UTC", NOW).steps.at(-1)).toEqual({ label: "Done", line: "11:05", state: "done" });
      expect(summaryOf([r], false, "UTC", NOW).line).toBe("Last done at 11:05");
      expect(workOf([r], "UTC", NOW, []).counts).toMatchObject({ done: 1, stopped: 0 });
    }
  });

  it("leaves a failed run as it was", () => {
    const failed = run({ status: "ERROR", error: "the run failed" });
    expect(requestOf(failed, "UTC", NOW)).toMatchObject({ state: "failed", line: "It stopped on an error. Nothing was done after it." });
    expect(requestDetail(failed, [], [], "UTC", NOW).steps.at(-1)).toEqual({ label: "It failed", line: "It stopped on an error. Nothing was done after it.", state: "failed" });
    expect(summaryOf([failed], false, "UTC", NOW)).toMatchObject({ status: "idle", line: "It stopped on an error. Nothing was done after it.", attention: true });
    expect(workOf([failed], "UTC", NOW, []).counts).toMatchObject({ failed: 1, done: 0, stopped: 0 });
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
  // A French screen showed "Échec : the run failed": kiff/agents' own English words for a failed run.
  // A French phone read kiff/agents' English under Needs you: a run past its last try.
  it("says kiff/agents' reasons a run needs you in the person's language, and any other reason as it came", () => {
    const needs = (reason: string | undefined, lang: "en" | "fr" | "pl" = "en") => requestOf(run({ status: "PAUSED", state: "needs_you", reason } as Partial<Run>), "UTC", NOW, [], undefined, [], lang).line;
    expect(needs("It stopped 3 times while it worked, and was restarted each time from its last safe step. It needs you to look at it.", "fr"))
      .toBe("Elle s'est arrêtée 3 fois pendant son travail et a redémarré chaque fois depuis sa dernière étape sûre. Elle a besoin que vous y jetiez un œil.");
    expect(needs("It couldn't start: no invocation picked it up after 3 tries. It needs you to look at it.", "pl")).toBe("Nie mogło się rozpocząć po 3 próbach. Musisz na nie spojrzeć.");
    expect(needs("It couldn't start: no invocation picked it up after 3 tries. It needs you to look at it.")).toBe("It couldn't start after 3 tries. It needs you to look at it.");
    expect(needs("Stripe refused the refund.", "fr")).toBe("Stripe refused the refund.");
    expect(needs(undefined)).toBe(needsYouLine(undefined));
    // The two failures that fell back to a plain "failed" have their own words now.
    expect(failedLine("it could not continue once KIFF resolved its held calls", "fr")).toBe("Elle n'a pas pu continuer une fois ses appels retenus réglés. Rien n'a été fait ensuite.");
    expect(failedLine("it could not be started in the background", "fr")).toBe("Elle n'a pas pu démarrer, donc rien n'a été fait.");
  });

  it("says kiff/agents' words for a failed run in the person's language, never its English", () => {
    const failed = (error: string, lang: "en" | "fr" | "de" | "pl" = "en") => requestOf(run({ status: "ERROR", error }), "UTC", NOW, [], undefined, [], lang).line;
    expect(failed("the run failed")).toBe("It stopped on an error. Nothing was done after it.");
    expect(failed("the run failed", "fr")).toBe("Elle s'est arrêtée sur une erreur. Rien n'a été fait ensuite.");
    expect(failed("its lease ran out: the invocation crashed or timed out", "de")).toBe("Er ist bei einem Fehler stehen geblieben. Danach wurde nichts mehr getan.");
    expect(failed("the run reached its time limit", "fr")).toBe("Elle a atteint sa limite de temps et s'est arrêtée.");
    expect(failed("no invocation claimed it in time", "pl")).toBe("Nie mogło się rozpocząć, więc nic nie zostało zrobione.");
    expect(failed("the agent is halted, so the run didn't go on", "fr")).toBe("Elle n'a pas continué : le taf est bloqué.");
    // A run that never started reads apart from one that stopped partway (kiff/agents' FailedFirstTurn, FailedTools).
    expect(failed("the run failed before its first step, so nothing was done")).toBe("It couldn't start, so nothing was done.");
    expect(failed("the run failed before its first step, so nothing was done", "fr")).toBe("Elle n'a pas pu démarrer, donc rien n'a été fait.");
    expect(failed("the AI provider refused the agent's tools, so the run didn't start")).toBe("It didn't start: its tools couldn't be used. Nothing was done.");
    expect(failed("the AI provider refused the agent's tools, so the run didn't start", "de")).toBe("Er ist nicht gestartet: Die Werkzeuge des tafs konnten nicht verwendet werden. Es wurde nichts getan.");
    // Text tafs doesn't know: kept for an English reader, a plain "failed" in any other language.
    expect(failed("Stripe said no.", "fr")).toBe("Échec");
    expect(failedLine("Stripe said no.")).toBe("It failed: Stripe said no.");
    expect(failedLine(undefined, "de")).toBe("Fehlgeschlagen");
  });

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

// A taf that checks first wakes the AI only when its read lists something new: a firing that found nothing is quiet,
// never shown as skipped or failed.
describe("a check that found nothing", () => {
  it("isn't listed among the skipped or failed times, while a check that couldn't read the app is", () => {
    const fired = [
      { due: "2026-10-30T11:45:00Z", at: "2026-10-30T11:45:01Z", outcome: "quiet" },
      { due: "2026-10-30T11:30:00Z", at: "2026-10-30T11:30:01Z", outcome: "failed_check" },
      { due: "2026-10-30T11:15:00Z", at: "2026-10-30T11:15:01Z", outcome: "quiet" },
    ];
    expect(skippedOf(fired, "UTC", NOW).map((s) => s.why)).toEqual(["check_failed"]);
  });
});

describe("a run's credits on its row", () => {
  it("says the counted credits from KIFF's usage record, or why a run used none, ready to draw", async () => {
    const { runCredits } = await import("../src/cloud/credits.js");
    const base = { run: "r", trigger: "schedule", ended: "2026-11-12T19:43:40Z", refused: 0, declined: 0 };
    expect(runCredits({ ...base, counted: true, credits: 34 })).toEqual({ credits: 34, counted: true, words: "34 credits" });
    expect(runCredits({ ...base, counted: true, credits: 1 })).toEqual({ credits: 1, counted: true, words: "1 credit" });
    expect(runCredits({ ...base, counted: false, free: "failed", credits: 9 })).toMatchObject({ credits: 0, counted: false });
    expect(runCredits({ ...base, counted: false, free: "failed", credits: 9 }).words).not.toMatch(/\d/);
    expect(runCredits({ ...base, trigger: "trial", counted: false, credits: 5 }).counted).toBe(false);
    expect(runCredits({ ...base, counted: true, credits: 34 }, "fr").words).toMatch(/34/);
  });

  it("goes on each request KIFF has counted, and none on a run it hasn't or a read that failed", async () => {
    const { workOf } = await import("../src/cloud/work.js");
    const runs = [run({ id: "a", status: "COMPLETED" }), run({ id: "b", status: "COMPLETED" })];
    const credits = new Map([["a", { credits: 34, counted: true, words: "34 credits" }]]);
    const { requests } = workOf(runs, "UTC", NOW, [], new Set(), new Map(), "en", credits);
    expect(requests.find((q) => q.id === "a")?.credits).toEqual({ credits: 34, counted: true, words: "34 credits" });
    expect(requests.find((q) => q.id === "b")).not.toHaveProperty("credits");
    expect(workOf(runs, "UTC", NOW, []).requests.every((q) => !("credits" in q))).toBe(true);
  });

  it("reads the period's runs with the person's session, and gives none when KIFF can't be read", async () => {
    const { runCreditsOf } = await import("../src/cloud/credits.js");
    const seen: string[] = [];
    const ok = (async (url: string, init?: RequestInit) => {
      seen.push(`${url} ${new Headers(init?.headers).get("authorization") ?? ""}`);
      return new Response(JSON.stringify({ items: [{ run: "a", trigger: "schedule", ended: "2026-11-12T19:43:40Z", counted: true, credits: 34, refused: 0, declined: 0 }] }), { status: 200 });
    }) as unknown as typeof fetch;
    const got = await runCreditsOf("tok", "taf-q", "en", ok, "https://kiff.test");
    expect(got.get("a")?.words).toBe("34 credits");
    expect(seen[0]).toContain("/v1/me/usage/credits/runs?runtime_agent=taf-q");
    expect(seen[0]).toContain("Bearer tok");
    const down = (async () => new Response("{}", { status: 503 })) as unknown as typeof fetch;
    expect((await runCreditsOf("tok", "taf-q", "en", down, "https://kiff.test")).size).toBe(0);
  });
});
