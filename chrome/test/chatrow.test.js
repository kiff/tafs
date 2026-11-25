import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  dayKey, startsDay, timeLabel, dayLabel, pluralKey, PLURAL_FORMS, duration, workOf, workLine, workRows, canReport, reportBody,
  reportOutcome, ownerActions, withBegan, BEGAN_MAX, cardShown, TURN_QUIET_MS, REPORT_REASONS, REPORT_NOTE_MAX,
} from "../src/chatrow.js";

// The extension's words, as chrome.i18n gives them: English's messages with their placeholders filled in.
const en = JSON.parse(readFileSync(new URL("../_locales/en/messages.json", import.meta.url), "utf8"));
const say = (key, ...subs) => {
  const m = en[key];
  if (!m) return key;
  return m.message.replace(/\$([A-Z_]+)\$/g, (_, name) => subs[Number(m.placeholders[name.toLowerCase()].content.slice(1)) - 1]);
};

// When each message was sent (docs/chat.md, "When each message was sent"), as the phones' ChatTime.
test("a header goes over the first message and over each first message of a later day, in the zone the panel reads", () => {
  const msgs = [
    { at: "2026-11-24T22:30:00Z" },
    { at: "2026-11-24T23:30:00Z" },
    { at: "2026-11-25T00:10:00Z" },
    { at: "bad" },
    { at: "2026-11-25T09:00:00Z" },
  ];
  assert.deepEqual(msgs.map((_, i) => startsDay(msgs, i, "UTC")), [true, false, true, false, false]);
  // In Paris the first three are all on the 24th's evening and the 25th's small hours: the day turns between the first two.
  assert.deepEqual(msgs.map((_, i) => startsDay(msgs, i, "Europe/Paris")), [true, true, false, false, false]);
  assert.equal(dayKey("2026-11-24T23:30:00Z", "Europe/Paris"), "2026-11-25");
  assert.equal(dayKey("", "UTC"), "");
});

test("the time under a message is in the panel's language, 24 or 12 hours as it reads them", () => {
  assert.equal(timeLabel("2026-11-25T14:05:00Z", "fr", "UTC"), "14:05");
  assert.equal(timeLabel("2026-11-25T14:05:00Z", "de", "UTC"), "14:05");
  assert.match(timeLabel("2026-11-25T14:05:00Z", "en-US", "UTC"), /^2:05\sPM$/);
  assert.equal(timeLabel(undefined, "en", "UTC"), "");
});

test("a day's header: Today, Yesterday, else the date with its weekday, and its year when it isn't this one", () => {
  const now = Date.parse("2026-11-25T12:00:00Z");
  const o = { now, timeZone: "UTC", lang: "en-US", today: "Today", yesterday: "Yesterday" };
  assert.equal(dayLabel("2026-11-25T00:01:00Z", o), "Today");
  assert.equal(dayLabel("2026-11-24T23:59:00Z", o), "Yesterday");
  assert.equal(dayLabel("2026-11-18T10:00:00Z", o), "Wed, Nov 18");
  assert.equal(dayLabel("2025-11-18T10:00:00Z", o), "Tue, Nov 18, 2025");
  assert.match(dayLabel("2026-11-18T10:00:00Z", { ...o, lang: "fr" }), /^mer\. 18 nov\.$/);
  // Yesterday by the calendar, on the day a clock goes back an hour too.
  const after = { ...o, now: Date.parse("2026-10-26T10:00:00Z"), timeZone: "Europe/Paris" };
  assert.equal(dayLabel("2026-10-25T08:00:00Z", after), "Yesterday");
});

test("a count's words come in each plural form the language has, else its other form; every form is in every locale", () => {
  assert.equal(pluralKey("chat_work_tries", 1, "en"), "chat_work_tries_one");
  assert.equal(pluralKey("chat_work_tries", 3, "en"), "chat_work_tries_other");
  assert.equal(pluralKey("chat_work_tries", 3, "pl"), "chat_work_tries_few");
  assert.equal(pluralKey("chat_work_tries", 5, "pl"), "chat_work_tries_many");
  assert.equal(pluralKey("chat_work_tries", 2, "ar"), "chat_work_tries_two");
  assert.equal(pluralKey("chat_work_tries", 3, "ja"), "chat_work_tries_other");
  for (const base of ["chat_worked_steps", "chat_worked_steps_failed", "chat_work_tries"]) {
    for (const form of PLURAL_FORMS) assert.ok(en[base + "_" + form], base + "_" + form);
  }
});

// A run's work, folded (docs/chat.md, "The work block"; Android's Chat.workLine, iOS's WorkLog).
test("the work's line: how long, with one decimal under 10 s, then whole seconds, then minutes; how many steps and failed", () => {
  assert.equal(duration(4200, "en", say), "4.2 s");
  assert.equal(duration(30, "en", say), "0.1 s");
  assert.equal(duration(4200, "fr", say), "4,2 s", "the number in the language");
  assert.equal(duration(12_400, "en", say), "12 s");
  assert.equal(duration(65_000, "en", say), "1 min 5 s");
  const step = (words, more = {}) => ({ words, failed: false, why: null, notMade: false, ...more });
  assert.equal(workLine({ ms: 3000, steps: [] }, "en", say), "Worked 3.0 s");
  assert.equal(workLine({ ms: 12_000, steps: [step("a")] }, "en", say), "Worked 12 s · 1 step");
  assert.equal(workLine({ ms: 4200, steps: [step("a"), step(null), step("b", { failed: true })] }, "en", say), "Worked 4.2 s · 3 steps, 1 failed");
});

test("the steps: only those with words, tries of one step as one row, a failure left out once something worked", () => {
  const work = workOf({ ms: 9000, steps: [
    { tool: "kiff_list", words: "Listing payments…" },
    { tool: "secret_tool" },
    { tool: "kiff_read", words: "Reading a payment…", error: true, failed: true, why: "No such payment" },
    { tool: "kiff_read", words: "Reading a payment…", error: true, why: "Timed out" },
    { tool: "kiff_read", words: "Reading a payment…" },
    { tool: "kiff_refund", words: "Refunding", not_made: true },
  ] });
  assert.equal(JSON.stringify(work).includes("kiff_"), false, "a step's tool is never kept");
  assert.deepEqual(workRows(work), [
    { words: "Listing payments", status: "done", tries: 1 },
    { words: "Reading a payment", status: "done", tries: 3 },
    { words: "Refunding", status: "not_made", tries: 1 },
  ]);
  // Nothing worked: the failures say why.
  const failed = workOf({ ms: 1000, steps: [{ tool: "x", words: "Reading", failed: true, why: "Not found" }, { tool: "y", words: "Asking", error: true }] });
  assert.deepEqual(workRows(failed), [{ words: "Reading", status: "failed", tries: 1, why: "Not found" }, { words: "Asking", status: "failed", tries: 1 }]);
  assert.equal(workOf(null), null);
  assert.equal(workOf({ ms: "12", steps: [] }), null);
});

// Report this answer (docs/chat.md, "Reporting an answer").
test("Report is offered on an answer with an id, in a chat the panel can name, and goes with that chat's session", () => {
  const answer = { from: "taf", kind: "message", id: "agr_1.2", text: "Done." };
  assert.equal(canReport(answer, "ses_1"), true);
  assert.equal(canReport(answer, ""), false, "no chat to name: tafs cloud refuses the panel's report without it");
  assert.equal(canReport({ ...answer, id: undefined }, "ses_1"), false);
  assert.equal(canReport({ ...answer, from: "owner" }, "ses_1"), false, "never the person's own");
  assert.equal(canReport({ ...answer, kind: "kickoff" }, "ses_1"), false);
  assert.equal(canReport({ ...answer, text: "  " }, "ses_1"), false);
  assert.deepEqual(REPORT_REASONS, ["offensive", "harmful", "wrong", "other"]);
  assert.deepEqual(reportBody("wrong", "  off by one  ", "ses_1"), { reason: "wrong", note: "off by one", session: "ses_1" });
  assert.deepEqual(reportBody("harmful", "", "ses_1"), { reason: "harmful", session: "ses_1" });
  assert.equal(reportBody("spam", "", "ses_1"), null);
  assert.equal(reportBody("wrong", "", ""), null, "never without the session");
  assert.equal(reportBody("wrong", "x".repeat(REPORT_NOTE_MAX + 1), "ses_1"), null);
  assert.ok(reportBody("wrong", "x".repeat(REPORT_NOTE_MAX), "ses_1"));
});

test("a report's outcome: thanks, already reported, or not sent with tafs cloud's words, else the phones' own lines", () => {
  assert.deepEqual(reportOutcome({ report: { id: "r", new: true } }), { state: "sent" });
  assert.deepEqual(reportOutcome({ report: { id: "r", new: false } }), { state: "already" });
  assert.deepEqual(reportOutcome({ failed: { status: 403, words: "Your account is halted." } }), { state: "failed", words: "Your account is halted." });
  assert.deepEqual(reportOutcome({ failed: { status: 404 } }), { state: "failed", key: "report_answer_gone" });
  assert.deepEqual(reportOutcome({ failed: { status: 0 } }), { state: "failed", key: "report_not_reached" });
  assert.deepEqual(reportOutcome(null), { state: "failed", key: "report_not_reached" });
  assert.deepEqual(reportOutcome({ error: "failed" }), { state: "failed", key: "report_not_reached" });
});

// The person's own message (the phones' Send again, Edit and send, Copy).
test("the person's message: Copy always; Send again and Edit and send only in a chat begun in this Chrome", () => {
  const mine = { from: "owner", kind: "message", text: "List the refunds" };
  assert.deepEqual(ownerActions(mine, true), { copy: true, again: true, edit: true });
  assert.deepEqual(ownerActions(mine, false), { copy: true, again: false, edit: false }, "a run's chat shown here: Copy only");
  assert.deepEqual(ownerActions(mine, undefined), { copy: true, again: false, edit: false }, "not known as begun here: Copy only");
  assert.deepEqual(ownerActions({ ...mine, text: "" }, true), { copy: false, again: false, edit: false }, "files alone");
  assert.deepEqual(ownerActions({ ...mine, kind: "kickoff" }, true), { copy: false, again: false, edit: false }, "a job's instruction");
  assert.deepEqual(ownerActions({ from: "taf", text: "x" }, true), { copy: false, again: false, edit: false });
});

test("the Coach chats begun here: each once, newest last, at most BEGAN_MAX", () => {
  assert.deepEqual(withBegan([], "s1"), ["s1"]);
  assert.deepEqual(withBegan(["s1", "s2"], "s1"), ["s2", "s1"]);
  assert.deepEqual(withBegan(["s1"], ""), ["s1"]);
  assert.deepEqual(withBegan(null, "s1"), ["s1"]);
  const many = Array.from({ length: BEGAN_MAX }, (_, i) => "s" + i);
  const next = withBegan(many, "new");
  assert.equal(next.length, BEGAN_MAX);
  assert.equal(next[0], "s1");
  assert.equal(next.at(-1), "new");
});

// A run's card gives way to the answer's folded work once its turn is over.
test("a run's card shows while it works, and not once tafs cloud said its turn is over and it's quiet, until a step comes after", async () => {
  const now = 100_000;
  const run = { id: "run1", steps: [{ op: "read", at: 10_000 }, { op: "act", at: 20_000 }] };
  assert.equal(cardShown(run, new Map(), now), true);
  assert.equal(cardShown(run, new Map([["run1", 2]]), now), false);
  assert.equal(cardShown({ ...run, steps: [...run.steps, { op: "read", at: 99_000 }] }, new Map([["run1", 2]]), now), true, "a step since");
  assert.equal(cardShown(run, new Map([["run1", 2]]), 20_000 + TURN_QUIET_MS - 1), true, "a step a moment ago: it may still work");
  assert.equal(cardShown({ ...run, paused: "owner" }, new Map([["run1", 2]]), now), true, "Resume is never hidden");
  assert.equal(cardShown({ ...run, hand: { id: "h" } }, new Map([["run1", 2]]), now), true, "a hand-over is never hidden");
  const { TURN_QUIET_MS: runnerQuiet } = await import("../src/runner.js");
  assert.equal(TURN_QUIET_MS, runnerQuiet, "the runner's own rule");
});
