// End to end: the signs a run draws on the page it works in (signs.js, 0.2.23; docs/chrome.md, "While a taf works"). While it
// works, a glow round the window; before each act, the pointer at what it'll touch, with what it does ("Clicking"); every sign
// gone at once when the run pauses, stops, is done, gives the tab back, or the worker that drew them stops. The page sees none of
// it, and an act lands only on the element the pointer went to.
//
// What a sign shows is read in the tab's own extension world (`TafsSigns.state()`): the layer's root is closed, and the page's own
// world can't reach it, which is checked too.

import { test, before, after, afterEach } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, tabIdAt, tabInfo, inWorker, signsIn as signsOf } from "./harness.mjs";
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

const started = new Set();
afterEach(async () => {
  if (skip) return;
  for (const run of started) await h.fromPanel({ type: "stop", run }).catch(() => {});
  started.clear();
});

const signsIn = (tabId) => signsOf(h, tabId);

const NONE = (s) => s === null || (!s.glow && !s.ring && !s.pointer && !s.attached);

async function open(name, path = "/signs") {
  const run = newRun(name);
  started.add(run);
  const url = h.sites.url("shop.test", path + "?case=" + name);
  const opened = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const tabId = await until(() => tabIdAt(h, url), "the run's tab id");
  return { run, url, tab, tabId, page: opened.page };
}

test("while a run works, its tab glows and the pointer says what it does; the act lands, and the page sees none of it", { skip }, async () => {
  const o = await open("signs-working");
  const s = await until(async () => {
    const x = await signsIn(o.tabId);
    return x && x.glow ? x : null;
  }, "the glow on the run's tab");
  assert.equal(s.pointer, true, "the pointer, saying it reads");
  assert.equal(s.words, "Reading");
  const r = await h.planque.sendStep({ op: "act", run: o.run, read_id: o.page.read_id, act: { kind: "click", ref: ref(o.page, "Press me", "controls") } });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(await o.tab.textContent("#out"), "pressed", "the click went through the pointer to the button");
  // The page's own world: no element, attribute or global of the extension's, and the layer's root is closed.
  const seen = await o.tab.evaluate(() => ({
    tafs: [...document.querySelectorAll("*")].some((e) => /tafs/i.test(e.localName + " " + [...e.attributes].map((a) => a.name + "=" + a.value).join(" "))),
    globals: typeof globalThis.TafsSigns + " " + typeof globalThis.TafsPage,
    openRoots: [...document.querySelectorAll("*")].filter((e) => e.shadowRoot).length,
    text: document.body.innerText,
  }));
  assert.deepEqual([seen.tafs, seen.globals, seen.openRoots], [false, "undefined undefined", 0]);
  assert.ok(!/Reading|Clicking|is working here/.test(seen.text), "its words aren't the page's text");
});

test("the pointer goes to what the taf types into, saying so; a read after says it reads", { skip }, async () => {
  const o = await open("signs-typing");
  const r = await h.planque.sendStep({ op: "act", run: o.run, read_id: o.page.read_id, act: { kind: "fill", ref: ref(o.page, "Your name"), value: "Ana" } });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(await o.tab.textContent("#out"), "typed Ana");
  const s = await signsIn(o.tabId);
  assert.equal(s.pointer, true);
  assert.equal(s.words, "Reading", "the read after the act");
  assert.equal(s.ring, false, "the outline went as the act started");
});

test("a target that slides away between the pointer and the act is refused page_changed: nothing is clicked", { skip }, async () => {
  const o = await open("signs-mover", "/mover");
  const r = await h.planque.sendStep({ op: "act", run: o.run, read_id: o.page.read_id, act: { kind: "click", ref: ref(o.page, "Move away", "controls") } });
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "page_changed");
  assert.equal(await o.tab.textContent("#out"), "untouched", "not where it was, not where it went");
});

test("a pause takes every sign away at once, Resume brings the glow back, and Stop takes them for good", { skip }, async () => {
  const o = await open("signs-pause");
  await until(async () => ((await signsIn(o.tabId)) || {}).glow, "the glow");
  await h.fromPanel({ type: "pause", run: o.run });
  await until(async () => NONE(await signsIn(o.tabId)), "every sign gone on pause", 5000);
  await h.fromPanel({ type: "resume", run: o.run });
  await until(async () => ((await signsIn(o.tabId)) || {}).glow, "the glow again on resume", 5000);
  await h.fromPanel({ type: "stop", run: o.run });
  await until(async () => NONE(await signsIn(o.tabId)), "every sign gone on stop", 5000);
  // A sync after it (the tab in front changes) draws nothing again.
  await o.tab.bringToFront();
  await new Promise((go) => setTimeout(go, 500));
  assert.ok(NONE(await signsIn(o.tabId)), "stays gone");
});

test("a run whose turn is over (done) leaves no sign", { skip }, async () => {
  const o = await open("signs-done");
  await until(async () => ((await signsIn(o.tabId)) || {}).glow, "the glow");
  // tafs cloud says the turn is over: once the run has been quiet long enough, it ends here.
  await until(async () => ((await h.fromPanel({ type: "turn_over", run: o.run })) || {}).ended, "the turn to end", 20_000);
  await until(async () => NONE(await signsIn(o.tabId)), "every sign gone when done", 5000);
});

test("a tab the person takes back loses the signs, and none is drawn there after the run's next step", { skip }, async () => {
  const url = h.sites.url("shop.test", "/signs?case=signs-given");
  const { agentRun, tabId } = await j.sendFromPanel(url);
  const run = newRun("signs-given");
  started.add(run);
  const opened = await j.step({ op: "open", run, url, agent_run: agentRun });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  await until(async () => ((await signsIn(tabId)) || {}).glow, "their tab glows while the run works in it");
  // The person types another address of the run's sites: their tab is theirs again.
  await j.owner.goto(h.sites.url("shop.test", "/account?case=signs-given"));
  await until(async () => (await tabInfo(h, tabId)).groupId === -1, "their tab given back");
  await j.step({ op: "read", run, agent_run: agentRun }).catch(() => {});
  await new Promise((go) => setTimeout(go, 500));
  assert.ok(NONE(await signsIn(tabId)), "no glow, no pointer in a tab the person took back: " + JSON.stringify(await signsIn(tabId)));
});

/** Stops the extension's service worker through the DevTools protocol (as worker.e2e.test.js does). */
async function stopWorker() {
  const cdp = await h.context.newCDPSession(j.owner);
  const status = new Map();
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

test("a worker that stops forgets its runs, and the worker that starts again takes their signs off every page", { skip }, async () => {
  const o = await open("signs-worker");
  await until(async () => ((await signsIn(o.tabId)) || {}).glow, "the glow");
  await inWorker(h, () => { globalThis.__e2eBeforeStop = true; });
  await stopWorker();
  await h.fromPanel({ type: "state" }).catch(() => {});
  await until(async () => await inWorker(h, () => globalThis.__e2eBeforeStop !== true).catch(() => false), "a new worker");
  await until(async () => NONE(await signsIn(o.tabId)), "the signs gone after the restart", 5000);
});
