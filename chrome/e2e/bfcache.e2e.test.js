// End to end: the person's own Back in a tab a run joined, with Chrome's back/forward cache on, as people have it (Playwright turns
// it off: `start({bfcache: true})` leaves it on). joined.e2e.test.js has the same case with it off. Either way the rule is the same
// (docs/chrome.md, "A taf works where you are"): a page off the run's sites committing in the person's tab moves nothing: the tab
// is given back as it is, and the run's next read and act there are no_page.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, rulesOf, runState, tabInfo } from "./harness.mjs";
import { joining } from "./joining.mjs";
import { PERSON_VALUE, PERSON_WORDS } from "./sites.mjs";

const skip = !playwright;
let h;
let j;

before(async () => {
  if (skip) return;
  h = await start({ cloud: true, bfcache: true });
  j = await joining(h);
});

after(async () => {
  if (h) await h.close();
});

test("Chrome's back/forward cache is on in this Chromium: a Back to a cacheable page restores it without a request", { skip }, async () => {
  const page = await h.context.newPage();
  try {
    const A = h.sites.url("other.test", "/account?case=bfcache-works&bfcache=1");
    await page.goto(A);
    await page.evaluate(() => { window.__e2eKept = true; });
    await page.goto(h.sites.url("news.test", "/article?case=bfcache-works&bfcache=1"));
    const asked = j.loads(A);
    await page.goBack({ waitUntil: "commit" });
    await until(() => page.evaluate(() => window.__e2eKept === true).catch(() => false), "the page restored from the cache");
    assert.equal(j.loads(A), asked, "restored, not asked for again");
  } finally {
    await page.close();
  }
});

test("with the back/forward cache on, the person's own Back off the run's sites gives their tab back with nothing moved, and the run's next read and act are given_back", { skip }, async () => {
  const { owner } = j;
  const A = h.sites.url("other.test", "/account?case=bfcache-own-back&bfcache=1");
  await owner.goto(A);
  const B = h.sites.url("news.test", "/hub?case=bfcache-own-back&bfcache=1");
  const { agentRun, tabId } = await j.sendFromPanel(B);
  const run = newRun("bfcache-own-back");
  try {
    const opened = await j.step({ op: "open", run, url: B, agent_run: agentRun });
    assert.equal(opened.ok, true, JSON.stringify(opened.error));
    await j.watchMoves();
    await owner.goBack({ waitUntil: "commit" }).catch(() => {});
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab given back", 5000);
    assert.deepEqual(await rulesOf(h, tabId), [], "unfenced");
    assert.deepEqual((await runState(h, run)).tabs, [], "forgotten by the run");
    assert.equal((await tabInfo(h, tabId)).url, A, "at the address the person went back to");
    // The fence's rules going in flushed the cache, so their Back was a new request the fence stopped: Chrome's block page at A,
    // and the extension asks for nothing itself (0.2.17). Their next try loads it.
    assert.deepEqual(await j.moves(tabId), [], "nothing moved their tab: no about:blank, no further back");
    const read = await j.step({ op: "read", run, agent_run: agentRun });
    assert.equal(read.error && read.error.code, "given_back");
    const act = await j.step({ op: "act", run, agent_run: agentRun, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Question"), value: "typed by the taf" } });
    assert.equal(act.error && act.error.code, "given_back");
    for (const r of [read, act]) {
      assert.ok(!JSON.stringify(r).includes(PERSON_WORDS));
      assert.ok(!JSON.stringify(r).includes(PERSON_VALUE));
    }
    if (owner.url() !== A) await owner.reload();
    assert.equal(owner.url(), A, "their next try lands on their page");
  } finally {
    await h.fromPanel({ type: "stop", run });
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab given back");
  }
});
