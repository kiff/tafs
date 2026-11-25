// End to end: what leaves for planque when a page is too large, or the relay is down (docs/chrome.md, "The relay", "A step handed to
// the person"). A read over the relay's 120 KB is cut to fit, and no cut carries a masked value. A hand-over's Done pressed while the
// relay is down reaches planque once it's back, signed, without what the person typed. One whose page is over the cap goes as Done
// with no page.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt } from "./harness.mjs";
import { PASSWORD, CARD_TEXT } from "./sites.mjs";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start();
});

after(async () => {
  if (h) {
    h.planque.relayUp();
    await h.close();
  }
});

const TYPED = "typed-by-the-person-e2e-Qm5";
const digits = CARD_TEXT.replace(/ /g, "");

/** Nothing masked or typed is anywhere in `x`: the password field's value, the card number (spaced or not), the person's words. */
function nothingMasked(x, what) {
  const all = JSON.stringify(x);
  assert.ok(!all.includes(PASSWORD), what + " carries the password");
  assert.ok(!all.includes(CARD_TEXT) && !all.includes(digits), what + " carries the card number");
  assert.ok(!all.includes(TYPED), what + " carries what the person typed");
}

test("a read over the relay's 120 KB is cut to fit, says so, and no cut carries a masked value", { skip }, async () => {
  const run = newRun("huge");
  const r = await h.planque.sendStep({ op: "open", run, url: h.sites.url("shop.test", "/huge?case=cut") });
  assert.equal(r.verified, true);
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.ok(Buffer.byteLength(JSON.stringify(r)) <= 128 * 1024, "the result fits the relay");
  assert.ok(r.page.truncated, "the page says what was cut: " + JSON.stringify(Object.keys(r.page)));
  nothingMasked(r, "the cut read");
  // Fields are never cut: the password field is there, masked.
  const pw = r.page.fields.find((f) => f.label === "Password");
  assert.ok(pw && pw.masked, JSON.stringify(pw));
  await h.fromPanel({ type: "stop", run });
});

/** A hand-over on `url` in a new run: the step handed and answered. */
async function handOn(url, words) {
  const run = newRun("hand-down");
  const opened = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const handed = await h.planque.step({ op: "hand", run, read_id: opened.page.read_id, hand: { words, ref: ref(opened.page, "Your name") } });
  const r = await h.planque.sendRaw(handed);
  assert.equal(r.ok, true, JSON.stringify(r.error));
  return { run, handed };
}

test("a hand-over's Done pressed while the relay is down reaches planque once it's back: signed, once, without what the person typed", { skip }, async () => {
  const url = h.sites.url("shop.test", "/form?case=hand-down");
  // The form has "Full name"; the hand-over names it by that label.
  const run = newRun("hand-down");
  const opened = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const handed = await h.planque.step({ op: "hand", run, read_id: opened.page.read_id, hand: { words: "Type your name, then press Done", ref: ref(opened.page, "Full name") } });
  assert.equal((await h.planque.sendRaw(handed)).ok, true);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  await tab.fill("#name", TYPED);

  h.planque.relayDown();
  await until(async () => (await h.inPanel("chrome.storage.session.get('state').then((x) => x.state && x.state.connected)")) === false, "the extension to see the relay down");
  const got = [];
  const on = (m) => m.hand === handed.step.id && got.push(m);
  h.planque.on("hand_result", on);
  try {
    assert.deepEqual(await h.fromPanel({ type: "hand_answer", run, outcome: "done" }), { ok: true });
    await new Promise((r) => setTimeout(r, 500));
    assert.equal(got.length, 0, "nothing could reach planque while the relay was down");

    h.planque.relayUp();
    await until(() => got.length > 0, "the Done, once the relay is back", 45_000);
    await new Promise((r) => setTimeout(r, 1000));
    assert.equal(got.length, 1, "sent once");
    assert.equal(got[0].verified, true, "signed by the extension");
    assert.equal(got[0].outcome, "done");
    nothingMasked(got[0], "the Done");
    for (const f of (got[0].page && got[0].page.fields) || []) assert.equal(f.value, undefined, f.label + " goes without its value");
  } finally {
    h.planque.off("hand_result", on);
    h.planque.relayUp();
  }
  await h.fromPanel({ type: "stop", run });
});

test("a hand-over's Done whose page is over the relay's 120 KB goes as Done with no page, and nothing typed rides along", { skip }, async () => {
  const url = h.sites.url("shop.test", "/huge?case=hand-huge");
  const { run, handed } = await handOn(url, "Type your name, then press Done");
  const tab = await until(() => tabAt(h, url), "the run's tab");
  await tab.fill("#who", TYPED);
  const answer = h.planque.next("hand_result", (m) => m.hand === handed.step.id, 20_000);
  assert.deepEqual(await h.fromPanel({ type: "hand_answer", run, outcome: "done" }), { ok: true });
  const m = await answer;
  assert.equal(m.verified, true);
  assert.equal(m.outcome, "done");
  assert.ok(m.page == null, "the page went, over the cap: " + Buffer.byteLength(JSON.stringify(m.page || null)) + " bytes");
  nothingMasked(m, "the Done");
  await h.fromPanel({ type: "stop", run });
});
