import { test } from "node:test";
import assert from "node:assert/strict";
import { FRESH, chosen, sendOptions, afterSend, chatTitle, pollDelay, POLL_BUSY_MS, POLL_IDLE_MS, BUSY_AFTER_SEND_MS } from "../src/coachchat.js";

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
