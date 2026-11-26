// What the tests of a run that joins the person's tab share (joined.e2e.test.js, bfcache.e2e.test.js, worker.e2e.test.js): the
// person's tab, a send to the taf from the side panel with it in front, signed steps of that run, and the person's Stop. A test file
// that uses them starts its harness with `cloud: true` and calls `joining(h)` once its `before` has it.

import assert from "node:assert/strict";
import { until, rulesOf, runState, tabInfo, inWorker } from "./harness.mjs";

/** The KIFF device claimed and the page consent given, as the person does once; then the helpers for harness `h`. */
export async function joining(h) {
  await until(() => h.cloud.claimed, "the KIFF device's claim", 15_000);
  await until(async () => (await h.fromPanel({ type: "page_allow" })).allowed === true, "the page consent");
  // The person's tab: the one that opened the side panel, in front of its window.
  const owner = h.context.pages()[0];
  const j = { owner };

  /** The panel's window and the tab in front of it, as the panel knows them. */
  j.front = () => h.inPanel(`chrome.windows.getCurrent().then(async (w) => {
    const [t] = await chrome.tabs.query({ active: true, windowId: w.id });
    return { windowId: w.id, tabId: t.id, url: t.url };
  })`);

  /**
   * The person, on `url` in their tab, sends the taf a message from the panel, as panel.js's composer does (`taf_send`, with the
   * panel's window and the tab in front). Resolves with the agent run tafs cloud started for it.
   */
  j.sendFromPanel = async (url, words = "Sum up this page") => {
    if (owner.url() !== url) await owner.goto(url);
    // The person's tab in front of the panel's window: a run Chrome followed may have brought its own tab there.
    await owner.bringToFront();
    const { windowId, tabId } = await until(async () => {
      const f = await j.front();
      return f.url === url ? f : null;
    }, "the person's tab in front");
    const answer = await h.fromPanel({ type: "taf_send", taf: h.cloud.tafId, words, attachments: [], clientID: "c_" + Date.now(), tabId, windowId });
    assert.equal(answer.error, undefined, JSON.stringify(answer));
    // The page went with the words, masked, from the taf's own signed sites.
    assert.equal(answer.reading, new URL(url).hostname);
    const sent = h.cloud.sends.at(-1);
    assert.equal(sent.page.url, url.replace(/[?#].*$/, ""));
    assert.equal(answer.sent.run, sent.run);
    return { agentRun: sent.run, tabId };
  };

  /** How many times the fixture sites were asked for `url`. */
  j.loads = (url) => h.sites.hits.filter((x) => x === new URL(url).hostname + " " + new URL(url).pathname + new URL(url).search).length;

  /** A step signed by planque, and its result, checked as signed by the extension. */
  j.step = async (fields) => {
    const r = await h.planque.sendStep(fields);
    assert.equal(r.verified, true);
    return r;
  };

  /**
   * The person's Stop in the panel: the run ends and their tab goes back to them, out of the group, unfenced, still open at `url`.
   * The panel's state lists none of the run's tabs as soon as the Stop is answered.
   */
  j.stopAndGiveBack = async (run, tabId, url) => {
    assert.deepEqual(await h.fromPanel({ type: "stop", run }), {});
    const now = await runState(h, run);
    assert.equal(now.stopped, true);
    assert.deepEqual(now.tabs, [], "the panel lists none of the stopped run's tabs at once");
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab out of the run's group");
    assert.deepEqual(await rulesOf(h, tabId), [], "its fence rules went");
    const tab = await tabInfo(h, tabId);
    assert.equal(tab.url, url, "still open, where it was");
  };

  /**
   * Records, in the service worker, every chrome.tabs.update, goBack and goForward the extension makes from now on (`moves()` gives
   * them): how a test shows the person's tab was never sent anywhere. The worker's own API object is wrapped, nothing of the
   * extension's code changes, and a worker that restarts starts without it.
   */
  j.watchMoves = () => inWorker(h, () => {
    globalThis.__e2eMoves = [];
    if (globalThis.__e2eWatching) return;
    globalThis.__e2eWatching = true;
    for (const name of ["update", "goBack", "goForward"]) {
      const real = chrome.tabs[name];
      chrome.tabs[name] = function (...args) {
        globalThis.__e2eMoves.push({ name, tabId: typeof args[0] === "number" ? args[0] : null, props: args[0] && typeof args[0] === "object" ? args[0] : args[1] || null });
        return real.apply(this, args);
      };
    }
  });
  j.moves = (tabId) => inWorker(h, (id) => (globalThis.__e2eMoves || []).filter((m) => m.tabId === id), tabId);

  return j;
}
