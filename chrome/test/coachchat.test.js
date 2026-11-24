import { test } from "node:test";
import assert from "node:assert/strict";
import { FRESH, chosen, sendOptions, afterSend, chatTitle, pollDelay, POLL_BUSY_MS, POLL_IDLE_MS, BUSY_AFTER_SEND_MS, tafsHere, idleDelay, IDLE_FIRST_MS, IDLE_MOST_MS, tafOfRun, runOfTab, tafOfTab, tafsAtWork, switchDraft } from "../src/coachchat.js";

test("the panel opens on a fresh conversation, whose first message starts a new chat", () => {
  assert.equal(FRESH.fresh, true);
  assert.deepEqual(sendOptions(FRESH), { newChat: true });
});

test("a fresh conversation follows the chat its first message went into, and then writes into it", () => {
  const after = afterSend(FRESH, { session: "s9", new_session: true });
  assert.deepEqual(after, chosen("s9"));
  assert.deepEqual(sendOptions(after), { session: "s9" });
  assert.equal(afterSend(after, { session: "other" }), after, "a chosen chat stays the chat shown");
});

test("an answer with no session leaves a fresh conversation fresh, so nothing is sent into the wrong chat", () => {
  assert.equal(afterSend(FRESH, {}), FRESH);
  assert.equal(afterSend(FRESH, null), FRESH);
  assert.equal(afterSend(FRESH, { session: 5 }), FRESH);
});

test("an earlier chat is chosen by its session, and sends into it", () => {
  const v = chosen("s2");
  assert.deepEqual(v, { fresh: false, session: "s2" });
  assert.deepEqual(sendOptions(v), { session: "s2" });
});

test("a chat with no title is called by the panel's own word", () => {
  assert.equal(chatTitle({ title: "Late parcels" }, "Conversation"), "Late parcels");
  assert.equal(chatTitle({ title: "  " }, "Conversation"), "Conversation");
  assert.equal(chatTitle({}, "Conversation"), "Conversation");
  assert.equal(chatTitle(null, "Conversation"), "Conversation");
});

test("a fresh conversation is never polled; a chat is read often while Coach works or the owner just wrote, and seldom after", () => {
  const now = 1_000_000;
  assert.equal(pollDelay({ view: FRESH, working: true, sentAt: now, now }), null, "nothing to read on a fresh conversation");
  const chat = chosen("s1");
  assert.equal(pollDelay({ view: chat, working: true, now }), POLL_BUSY_MS, "Coach is at work");
  assert.equal(pollDelay({ view: chat, sentAt: now - 1_000, now }), POLL_BUSY_MS, "just sent");
  assert.equal(pollDelay({ view: chat, sentAt: now - BUSY_AFTER_SEND_MS, now }), POLL_IDLE_MS, "a minute after the send");
  assert.equal(pollDelay({ view: chat, now }), POLL_IDLE_MS, "idle");
  assert.ok(POLL_IDLE_MS >= 6 * POLL_BUSY_MS, "idle is much rarer than busy");
});

test("the panel offers each of this Chrome's connections that is one of the person's tafs, once, the one at work first", () => {
  const connections = [
    { grant: "g1", agent: "agt_a", name: "A", sites: ["a.example"] },
    { grant: "g2", agent: "agt_b", name: "B", sites: ["b.example"] },
    { grant: "g3", agent: "agt_a", name: "A", sites: ["c.example"] },
    { grant: "g4", agent: "agt_gone", name: "Gone", sites: ["d.example"] },
  ];
  const tafs = [{ id: "taf-a", name: "Alpha", agent: "agt_a" }, { id: "taf-b", name: "Beta", agent: "agt_b" }];
  assert.deepEqual(tafsHere(connections, tafs).map((t) => t.id), ["taf-a", "taf-b"], "once each; a taf the person can't see isn't offered");
  assert.deepEqual(tafsHere(connections, tafs, "Beta").map((t) => t.id), ["taf-b", "taf-a"], "the one at work first");
  assert.deepEqual(tafsHere([], tafs), []);
});

test("an idle chat is read less and less, from 60 s to 5 minutes, each wait up to a fifth shorter", () => {
  assert.equal(idleDelay(0, 0), IDLE_FIRST_MS);
  assert.equal(idleDelay(1, 0), 120_000);
  assert.equal(idleDelay(2, 0), 240_000);
  assert.equal(idleDelay(3, 0), IDLE_MOST_MS);
  assert.equal(idleDelay(30, 0), IDLE_MOST_MS);
  assert.equal(idleDelay(0, 1), 48_000, "a fifth off at most");
  assert.ok(idleDelay(0, 0.5) <= IDLE_FIRST_MS);
});

// Several tafs, several tabs: the panel follows the tab, by the grant each run came under.

const offered = [
  { id: "taf-a", name: "Alpha", sites: ["a.example"], grants: ["g-a"] },
  { id: "taf-b", name: "Beta", sites: ["b.example"], grants: ["g-b", "g-b2"] },
];

test("each taf offered carries the grants of its connections here, once each", () => {
  const connections = [
    { grant: "g-a", agent: "ag-a", name: "Alpha", sites: ["a.example"] },
    { grant: "g-b", agent: "ag-b", name: "Beta", sites: ["b.example"] },
    { grant: "g-b2", agent: "ag-b", name: "Beta", sites: ["b.example"] },
    { grant: "g-b2", agent: "ag-b", name: "Beta", sites: ["b.example"] },
  ];
  const tafs = [{ id: "taf-a", agent: "ag-a", name: "Alpha" }, { id: "taf-b", agent: "ag-b", name: "Beta" }];
  assert.deepEqual(tafsHere(connections, tafs).map((t) => t.grants), [["g-a"], ["g-b", "g-b2"]]);
});

test("two tafs on two tabs: each tab shows its own taf", () => {
  const runs = [
    { id: "r1", grant: "g-a", tab: 11, tabs: [11] },
    { id: "r2", grant: "g-b2", tab: 22, tabs: [22, 23] },
  ];
  assert.equal(tafOfTab(11, runs, offered).id, "taf-a");
  assert.equal(tafOfTab(22, runs, offered).id, "taf-b");
  assert.equal(tafOfTab(23, runs, offered).id, "taf-b", "a tab the run opened is the run's too");
});

test("a tab of no run, a stopped run, or a grant no taf here holds picks nothing, never a guess by name", () => {
  const runs = [
    { id: "r1", grant: "g-a", tab: 11, tabs: [11], stopped: true },
    { id: "r2", grant: "g-gone", taf: "Alpha", tab: 22, tabs: [22] },
    { id: "r3", taf: "Beta", tab: 33, tabs: [33] },
  ];
  assert.equal(tafOfTab(99, runs, offered), null);
  assert.equal(tafOfTab(11, runs, offered), null, "a stopped run's tab is no taf's");
  assert.equal(tafOfTab(22, runs, offered), null, "a grant none offered holds");
  assert.equal(tafOfTab(33, runs, offered), null, "no grant");
  assert.equal(tafOfTab(undefined, runs, offered), null);
});

test("one taf with two runs: its tab is the run acting in it, and the head lists the taf once", () => {
  const runs = [
    { id: "r1", grant: "g-b", tab: 11, tabs: [11, 12] },
    { id: "r2", grant: "g-b2", tab: 12, tabs: [12], hand: { id: "h1" } },
  ];
  assert.equal(runOfTab(12, runs).id, "r2", "the run acting in the tab first");
  assert.deepEqual(tafsAtWork(runs, offered), [{ id: "taf-b", name: "Beta", run: "r1", waiting: true }]);
});

test("the head lists each taf at work once, in the runs' order, and none when nothing works", () => {
  const runs = [
    { id: "r1", grant: "g-b", tab: 11, tabs: [11] },
    { id: "r2", grant: "g-a", tab: 22, tabs: [22] },
    { id: "r3", grant: "g-a", tab: 33, tabs: [33], stopped: true },
    { id: "r4", grant: "g-gone", tab: 44, tabs: [44] },
  ];
  assert.deepEqual(tafsAtWork(runs, offered).map((t) => [t.id, t.run, t.waiting]), [["taf-b", "r1", false], ["taf-a", "r2", false]]);
  assert.deepEqual(tafsAtWork([], offered), []);
  assert.equal(tafOfRun({ id: "r", grant: "g-a" }, null), null);
});

test("words typed for one taf stay that taf's: a switch keeps them, and the other taf's box is its own", () => {
  // Typed for Alpha, then Beta's tab comes to the front: the box is empty for Beta, never Alpha's words.
  let d = switchDraft(new Map(), "taf-a", "taf-b", "Refund order 42");
  assert.equal(d.text, "");
  assert.equal(d.drafts.get("taf-a"), "Refund order 42");
  // Typed for Beta too, then back to Alpha: Alpha's words come back, Beta's are kept for Beta.
  d = switchDraft(d.drafts, "taf-b", "taf-a", "Pause until Monday");
  assert.equal(d.text, "Refund order 42");
  assert.equal(d.drafts.get("taf-b"), "Pause until Monday");
  assert.equal(d.drafts.has("taf-a"), false, "the one on show holds its words in the box, not twice");
  // An emptied box leaves no draft behind; a switch to no taf empties the box.
  d = switchDraft(d.drafts, "taf-a", "taf-b", "");
  assert.equal(d.text, "Pause until Monday");
  assert.equal(d.drafts.has("taf-a"), false);
  d = switchDraft(d.drafts, "taf-b", null, "x");
  assert.equal(d.text, "");
  assert.equal(d.drafts.get("taf-b"), "x");
  // The first taf shown starts empty.
  assert.deepEqual(switchDraft(null, null, "taf-a", "").text, "");
});
