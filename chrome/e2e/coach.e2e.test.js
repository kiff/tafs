// End to end: the last gaps that touch the person's data (docs/chrome-e2e.md, "Not covered yet"). Coach's own send from the side
// panel, with the person's page in front, starts a run that joins their tab only to read it where it is; Coach's read of a page
// with frames carries nothing of the frames; and a page that no cut makes fit the relay's 120 KB is refused `page_too_large`, while
// an act that ran is never answered as a failure.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, runState, tabInfo, rulesOf } from "./harness.mjs";
import { joining } from "./joining.mjs";
import { FRAME_WORDS, FRAME_PASSWORD, CROSS_FRAME_PASSWORD } from "./sites.mjs";

const skip = !playwright;
let h;
let j;

before(async () => {
  if (skip) return;
  h = await start({ cloud: true });
  j = await joining(h);
});

after(async () => {
  if (h) await h.close();
});

/** The person, on `url` in their tab, sends Coach `words` from the panel (panel.js's Coach composer: `coach_send`). */
async function coachSend(url, words = "What does this page say?") {
  if (j.owner.url() !== url) await j.owner.goto(url);
  await j.owner.bringToFront();
  const { windowId, tabId } = await until(async () => {
    const f = await j.front();
    return f.url === url ? f : null;
  }, "the person's tab in front");
  const before = h.cloud.coachSends.length;
  const answer = await h.fromPanel({ type: "coach_send", words, clientID: "c_coach_" + Date.now(), tabId, windowId });
  assert.equal(answer.error, undefined, JSON.stringify(answer));
  const sent = await until(() => h.cloud.coachSends.length > before && h.cloud.coachSends.at(-1), "Coach's message at tafs cloud");
  return { answer, sent, tabId };
}

test("Coach's own send, with the person's page in front, starts a run that joins their tab only to read it where it is", { skip }, async () => {
  const url = h.sites.url("news.test", "/article?case=coach-joined");
  const { answer, sent, tabId } = await coachSend(url);
  assert.equal(answer.reading, "news.test", "the panel says it read the page in front");
  assert.equal(sent.page && sent.page.url, h.sites.url("news.test", "/article"), "the page went with the words, without its query");
  assert.equal(answer.sent.run, sent.run);
  const loads = j.loads(url);
  const run = newRun("coach-joined");
  const opened = await j.step({ op: "open", run, url, agent_run: sent.run });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  assert.equal(opened.page.title, "Tide tables");
  assert.equal((await runState(h, run)).tab, tabId, "the run works in the person's tab");
  assert.equal(j.loads(url), loads, "read where it is: never loaded again");
  assert.equal((await rulesOf(h, tabId)).length > 0, true, "fenced as a run's tab");
  await j.stopAndGiveBack(run, tabId, url);
});

test("Coach's read of a page with frames, of the same site and of another: nothing of the frames goes with the words", { skip }, async () => {
  const url = h.sites.url("shop.test", "/frames?case=coach-frames");
  const { sent } = await coachSend(url, "Sum this up");
  assert.ok(sent.page, "the page went with the words");
  const all = JSON.stringify(sent);
  assert.ok(all.includes("The page around the frames"), "the top document was read");
  for (const secret of [FRAME_WORDS, FRAME_PASSWORD, CROSS_FRAME_PASSWORD]) assert.ok(!all.includes(secret), "a frame's " + secret + " went to Coach");
});

test("a page no cut makes fit the relay's 120 KB is refused `page_too_large`, signed, and the answer fits", { skip }, async () => {
  const run = newRun("no-fit");
  const r = await h.planque.sendStep({ op: "open", run, url: h.sites.url("shop.test", "/no-fit?case=no-fit") });
  assert.equal(r.verified, true);
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "page_too_large");
  assert.ok(Buffer.byteLength(JSON.stringify(r)) <= 128 * 1024);
  await h.fromPanel({ type: "stop", run });
});

// Since 0.2.21 an act that ran is answered done, with no page and `page_dropped`: the taf reads again, and never clicks twice.
test("an act that ran, whose page then grows past any cut, is never answered as a failure: the taf isn't told to click again", { skip }, async () => {
  const run = newRun("grows");
  const url = h.sites.url("shop.test", "/grows?case=grows");
  const opened = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const r = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Show every option", "controls") } });
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const state = await tab.textContent("#state");
  await h.fromPanel({ type: "stop", run });
  assert.equal(state, "grown", "the click ran");
  assert.ok(Buffer.byteLength(JSON.stringify(r)) <= 128 * 1024);
  assert.equal(r.verified, true, "signed, page_dropped included");
  assert.equal(r.ok, true, "the act that ran was answered as a failure: " + JSON.stringify(r.error));
  assert.equal(r.page, null);
  assert.equal(r.page_dropped, "page_too_large");
});

// A Submit whose answer is a page past any cut. Clicked once, it posts once; the act is answered done (no page,
// `page_dropped`), so nothing tells the taf to send it again, and nothing is sent again.
test("a Submit whose answer grows past any cut, clicked once: one POST, answered done with page_dropped, and nothing sent again", { skip }, async () => {
  const run = newRun("submit-grows");
  const url = h.sites.url("shop.test", "/submit-grows?case=submit");
  const opened = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const posts = () => h.sites.posts.filter((x) => x.startsWith("shop.test /submit-grows"));
  assert.deepEqual(posts(), [], "nothing posted by the open");
  const r = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Send it", "controls") } });
  assert.equal(r.verified, true);
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.page, null);
  assert.equal(r.page_dropped, "page_too_large");
  assert.ok(Buffer.byteLength(JSON.stringify(r)) <= 128 * 1024);
  await new Promise((done) => setTimeout(done, 1500));
  assert.equal(posts().length, 1, "exactly one POST: " + JSON.stringify(posts()));
  await h.fromPanel({ type: "stop", run });
  assert.equal(posts().length, 1, "nothing sent again");
});
