// End to end: no fence outlives the service worker that made it (docs/chrome.md, "Runs"). MV3 stops the extension's worker when it
// idles, and Chrome keeps a stopped worker's declarativeNetRequest session rules: the fence of a tab a run joined, and of a run's
// own tab, would go on blocking every other site in them with no run left to lift it. The Runner clears every session rule as
// it's made, so the worker that starts again leaves none.
//
// The worker is stopped as Chrome stops it, and nothing else: through Chromium's DevTools protocol (`ServiceWorker.stopWorker`,
// with its version id), not `chrome.runtime.reload`, which would reload the whole extension. A message from the side panel wakes it.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, until, inWorker, sessionRules, rulesOf, runState, tabInfo } from "./harness.mjs";
import { joining } from "./joining.mjs";

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

/** Stops the extension's service worker through the DevTools protocol, and resolves once Chromium says it stopped. */
async function stopWorker() {
  const cdp = await h.context.newCDPSession(j.owner);
  const status = new Map(); // version id → its running status
  const ours = new Set();
  cdp.on("ServiceWorker.workerVersionUpdated", (e) => {
    for (const v of e.versions) {
      if (v.scriptURL.startsWith(`chrome-extension://${h.extensionId}/`)) ours.add(v.versionId);
      status.set(v.versionId, v.runningStatus);
    }
  });
  await cdp.send("ServiceWorker.enable");
  const id = await until(() => [...ours].find((v) => status.get(v) === "running"), "the extension's running worker");
  const stopped = until(() => status.get(id) === "stopped", "the worker to stop");
  await cdp.send("ServiceWorker.stopWorker", { versionId: id });
  await stopped;
  await cdp.send("ServiceWorker.disable").catch(() => {});
  await cdp.detach().catch(() => {});
}

test("a service worker that stops leaves no fence: the worker that starts again clears every session rule, and the person's tab loads any site", { skip }, async () => {
  // A run that joined the person's tab, and a run with a tab of its own: both fenced.
  const url = h.sites.url("news.test", "/article?case=worker-joined");
  const { agentRun, tabId } = await j.sendFromPanel(url);
  const joinedRun = newRun("worker-joined");
  const joined = await j.step({ op: "open", run: joinedRun, url, agent_run: agentRun });
  assert.equal(joined.ok, true, JSON.stringify(joined.error));
  assert.equal((await runState(h, joinedRun)).tab, tabId);
  const ownRun = newRun("worker-own");
  const ownURL = h.sites.url("news.test", "/article?case=worker-own");
  const own = await j.step({ op: "open", run: ownRun, url: ownURL });
  assert.equal(own.ok, true, JSON.stringify(own.error));
  const ownTab = (await runState(h, ownRun)).tab;
  assert.equal((await rulesOf(h, tabId)).length, 3);
  assert.equal((await rulesOf(h, ownTab)).length, 3);
  await inWorker(h, () => { globalThis.__e2eBeforeStop = true; });

  await stopWorker();
  // Woken by the side panel, as anything the person does there wakes it.
  await h.fromPanel({ type: "state" }).catch(() => {});
  const fresh = await until(async () => (await inWorker(h, () => globalThis.__e2eBeforeStop !== true).catch(() => false)), "a new worker");
  assert.equal(fresh, true, "the worker started again, from nothing");
  await until(async () => (await sessionRules(h)).length === 0, "the session rules to be cleared", 5000);
  assert.deepEqual(await sessionRules(h), [], "no rule of either tab is left");

  // Both tabs load another site: nothing in Chrome blocks them now.
  const away = h.sites.url("other.test", "/?case=worker-person");
  await j.owner.goto(away);
  assert.ok(h.sites.hits.includes("other.test /?case=worker-person"), "the person's tab reached other.test");
  assert.equal((await tabInfo(h, tabId)).url, away);
  const page = await until(() => h.context.pages().find((p) => p.url() === ownURL), "the run's own tab");
  const ownAway = h.sites.url("other.test", "/?case=worker-own");
  await page.goto(ownAway);
  assert.ok(h.sites.hits.includes("other.test /?case=worker-own"), "the run's own tab reached other.test");
});
