// End to end: the second half of the ranked gaps (docs/chrome-e2e.md, "What each test covers"). A wrong action: no act lands on a
// control the person can't use (a disabled button, a field in a disabled fieldset, a read-only field, a disabled option, a radio
// that can't be unchecked); planque's unsigned stop really ends the run, even mid-act; a page's "leave this page?" never holds the
// run, and its dialogs as it loads are pinned as they are today (an open gap); only the extension's own pages command the worker. A stale display: after a
// tab is given back, the floating bar there no longer says it's the run's, and the badge counts live runs only.

import { test, before, after, afterEach } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, tabIdAt, signsIn, runState, tabInfo, inWorker } from "./harness.mjs";
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

/** Every run a test started: whatever it asserted, none is left live for the next test (the badge counts live runs). */
const started = new Set();
afterEach(async () => {
  if (skip) return;
  for (const run of started) await h.fromPanel({ type: "stop", run }).catch(() => {});
  started.clear();
});

async function open(run, url, fields = {}) {
  const r = await h.planque.sendStep({ op: "open", run, url, ...fields });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  return r;
}

// ---- 8. Controls the person can't use ----

/** The controls page in a new run: its read, its tab, and an `act` that sends one act on that read. */
async function controls(name) {
  const run = newRun(name);
  started.add(run);
  const url = h.sites.url("shop.test", "/controls?case=" + name);
  const opened = await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const act = (a) => h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: a });
  return { run, page: opened.page, tab, act };
}

test("a disabled button: the taf's click is refused `disabled`, and nothing happens", { skip }, async () => {
  const c = await controls("disabled-button");
  const r = await c.act({ kind: "click", ref: ref(c.page, "Send it", "controls") });
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "disabled");
  assert.equal(await c.tab.textContent("#out"), "untouched");
  await h.fromPanel({ type: "stop", run: c.run });
});

test("a field in a disabled fieldset: the taf's fill is refused `disabled`, and the field stays empty", { skip }, async () => {
  const c = await controls("fieldset");
  const r = await c.act({ kind: "fill", ref: ref(c.page, "Locked name"), value: "typed by the taf" });
  const value = await c.tab.inputValue("#fs");
  await h.fromPanel({ type: "stop", run: c.run });
  assert.equal(value, "", "the locked field took the taf's words");
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "disabled");
});

test("a read-only field: the taf's fill is refused, and its value stays as the page set it", { skip }, async () => {
  const c = await controls("readonly");
  const r = await c.act({ kind: "fill", ref: ref(c.page, "Read-only code"), value: "changed by the taf" });
  const value = await c.tab.inputValue("#ro");
  await h.fromPanel({ type: "stop", run: c.run });
  assert.equal(value, "fixed", "the read-only field took the taf's words");
  assert.equal(r.ok, false);
});

test("an option that isn't there is `option_unknown`; a disabled option is refused too, and the choice stays", { skip }, async () => {
  const c = await controls("options");
  const unknown = await c.act({ kind: "select", ref: ref(c.page, "Size"), value: "Huge" });
  assert.equal(unknown.ok, false);
  assert.equal(unknown.error.code, "option_unknown");
  const locked = await c.act({ kind: "select", ref: ref(c.page, "Size"), value: "Large" });
  const value = await c.tab.inputValue("#sz");
  await h.fromPanel({ type: "stop", run: c.run });
  assert.equal(value, "Small", "the disabled option was chosen");
  assert.equal(locked.ok, false);
});

test("a radio: `check` chooses it; `unchecked` on the checked one is refused, since a click can't uncheck a radio", { skip }, async () => {
  const c = await controls("radio");
  const express = await c.act({ kind: "check", ref: ref(c.page, "Express"), value: "checked" });
  assert.equal(express.ok, true, JSON.stringify(express.error));
  assert.equal(await c.tab.isChecked("#r2"), true);
  const read = await h.planque.sendStep({ op: "read", run: c.run });
  const un = await h.planque.sendStep({ op: "act", run: c.run, read_id: read.page.read_id, act: { kind: "check", ref: ref(read.page, "Express"), value: "unchecked" } });
  const still = await c.tab.isChecked("#r2");
  await h.fromPanel({ type: "stop", run: c.run });
  assert.equal(still, true, "a click unchecked a radio");
  assert.equal(un.ok, false, "the taf was told it unchecked a radio that's still checked");
});

// ---- 9. planque's unsigned stop ----

test("planque's unsigned stop over the relay ends the run even mid-act: the act answers `stopped`, and nothing more happens", { skip }, async () => {
  const run = newRun("stop-mid");
  started.add(run);
  const url = h.sites.url("shop.test", "/form?case=stop-mid");
  const opened = await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  // The taf's fill is held for its outline; planque's stop lands in it.
  const held = h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Full name"), value: "typed by the taf" } });
  const tabId = await until(() => tabIdAt(h, url), "the run's tab id");
  // The outline is drawn in the extension's own closed layer (signs.js): read where it lives, as the page never can.
  await until(async () => ((await signsIn(h, tabId)) || {}).ring, "the taf's outline", 5000);
  const said = h.planque.next("run_state", (m) => m.run === run && m.state === "stopped");
  h.planque.stopRun(run);
  const r = await held;
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "stopped");
  assert.equal((await said).verified, true);
  assert.equal(await tab.inputValue("#name"), "", "the act reached the page after the stop");
  const next = await h.planque.sendStep({ op: "read", run });
  assert.equal(next.error.code, "stopped");
});

test("planque's stop of a run with a hand-over open tells planque the hand-over's answer: not now", { skip }, async () => {
  const run = newRun("stop-hand");
  started.add(run);
  const opened = await open(run, h.sites.url("shop.test", "/form?case=stop-hand"));
  const handed = await h.planque.step({ op: "hand", run, read_id: opened.page.read_id, hand: { words: "Type your name, then press Done", ref: ref(opened.page, "Full name") } });
  assert.equal((await h.planque.sendRaw(handed)).ok, true);
  const answer = h.planque.next("hand_result", (m) => m.hand === handed.step.id);
  h.planque.stopRun(run);
  const m = await answer;
  assert.equal(m.verified, true);
  assert.equal(m.outcome, "declined");
});

test("planque's stop of a run this Chrome doesn't hold changes nothing", { skip }, async () => {
  const run = newRun("stop-other");
  started.add(run);
  await open(run, h.sites.url("news.test", "/article?case=stop-other"));
  h.planque.stopRun(newRun("never-here"));
  await new Promise((r) => setTimeout(r, 300));
  const s = await runState(h, run);
  assert.equal(s.stopped, false);
  assert.equal((await h.planque.sendStep({ op: "read", run })).ok, true);
  await h.fromPanel({ type: "stop", run });
});

// ---- 10. Dialogs as a page loads, and "leave this page?" ----

// Open gap: load-time dialogs, see chrome-sandbox-spike. The dialog guard goes into a run's tab once its page has committed
// (webNavigation.onCommitted), so a dialog the page raises in its own script as it loads comes first: it reaches Chromium as a real
// dialog, and the run waits for it to be answered. These tests pin that, so a change to it is seen. The harness holds the dialog
// open here instead of answering it, so the test sees it, then answers it itself.
async function heldDialog(what) {
  return until(() => h.held.find((d) => d.type() === what), "the page's " + what + " in Chromium", 10_000);
}

test("open gap: an alert a page raises as it loads reaches Chromium (or, when the guard wins the race, nothing does), and the run's open waits for it", { skip }, async () => {
  const run = newRun("alert-load");
  started.add(run);
  h.holdDialogs = true;
  try {
    let answered = false;
    const opening = open(run, h.sites.url("shop.test", "/at-load-alert?case=load")).finally(() => (answered = true));
    // A race, so either way is pinned: the page's inline alert usually runs before the onCommitted guard is in, and reaches
    // Chromium; under load the guard sometimes wins, and the alert is dropped as any later one is. Never both, never a hang.
    const dialog = await Promise.race([heldDialog("alert").catch(() => null), opening.then(() => null, () => null)]);
    if (dialog) {
      assert.equal(dialog.message(), "Welcome to the shop");
      await new Promise((res) => setTimeout(res, 500));
      assert.equal(answered, false, "the open waits while the alert is up");
      await dialog.accept();
    } else {
      assert.equal(h.held.filter((d) => d.type() === "alert").length, 0, "the guard won: no alert reached Chromium");
    }
    const r = await opening;
    assert.equal(r.page.title, "Welcome");
  } finally {
    h.holdDialogs = false;
    h.held.length = 0;
  }
  await h.fromPanel({ type: "stop", run });
});

test("open gap: a confirm a page raises as it loads reaches Chromium, the run's open waits, and the page reads the answer it got", { skip }, async () => {
  const run = newRun("confirm-load");
  started.add(run);
  const url = h.sites.url("shop.test", "/at-load-confirm?case=load");
  h.holdDialogs = true;
  try {
    let answered = false;
    const opening = open(run, url).finally(() => (answered = true));
    // A race, as with the alert above: when the guard wins it, the confirm is answered no by the guard, and nothing reaches Chromium.
    const dialog = await Promise.race([heldDialog("confirm").catch(() => null), opening.then(() => null, () => null)]);
    if (dialog) {
      assert.equal(dialog.message(), "Stay signed in?");
      await new Promise((res) => setTimeout(res, 500));
      assert.equal(answered, false, "the open waits while the confirm is up");
      await dialog.dismiss();
    } else {
      assert.equal(h.held.filter((d) => d.type() === "confirm").length, 0, "the guard won: no confirm reached Chromium");
    }
    await opening;
    const tab = await until(() => tabAt(h, url), "the run's tab");
    assert.equal(await tab.textContent("#out"), "refused", "the page got no for an answer, from Chromium or from the guard");
  } finally {
    h.holdDialogs = false;
    h.held.length = 0;
  }
  await h.fromPanel({ type: "stop", run });
});

test("a page that asks before it's left never holds the taf's next open, nor reaches Chromium", { skip }, async () => {
  const dialogs = h.dialogs.length;
  const run = newRun("beforeunload");
  started.add(run);
  const first = h.sites.url("shop.test", "/at-load-beforeunload?case=leave");
  const opened = await open(run, first);
  // The taf types in it (the page now has something it would ask about), then opens another page in the same tab.
  const filled = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Draft"), value: "half a draft" } });
  assert.equal(filled.ok, true, JSON.stringify(filled.error));
  const next = await open(run, h.sites.url("news.test", "/article?case=leave"));
  assert.equal(next.page.title, "Tide tables");
  assert.deepEqual(h.dialogs.slice(dialogs), [], "a dialog reached Chromium");
  await h.fromPanel({ type: "stop", run });
});

// ---- 11. Only the extension's own pages command the worker ----

/** Sends `message` to the worker from the extension's own content-script world in tab `tabId` (as bar.js or page.js could). */
function fromContentScript(tabId, message) {
  return inWorker(h, ([id, m]) => chrome.scripting.executeScript({ target: { tabId: id }, func: (x) => chrome.runtime.sendMessage(x).then((a) => a ?? null, (e) => "error: " + e.message), args: [m] }).then(([r]) => r.result), [tabId, message]);
}

test("a content script's message of a panel's kind changes nothing: no stop, no pause, no site switched off, no consent taken back", { skip }, async () => {
  const run = newRun("senders");
  started.add(run);
  const url = h.sites.url("news.test", "/article?case=senders");
  await open(run, url);
  const tabId = (await runState(h, run)).tab;
  for (const m of [
    { type: "stop", run },
    { type: "pause", run },
    { type: "aside_site", host: "news.test", off: true },
    { type: "page_withdraw" },
    { type: "unpair" },
    { type: "taf_send", taf: h.cloud.tafId, words: "from a page", attachments: [], clientID: "c_cs", tabId, windowId: (await tabInfo(h, tabId)).windowId },
  ]) {
    const answer = await fromContentScript(tabId, m);
    assert.ok(answer == null || /error/.test(String(answer)), `${m.type} was answered: ${JSON.stringify(answer)}`);
  }
  const s = await runState(h, run);
  assert.equal(s.stopped, false, "stopped");
  assert.ok(!s.paused, "paused");
  assert.equal((await h.planque.sendStep({ op: "read", run })).ok, true, "the run still reads its page");
  assert.deepEqual(await inWorker(h, () => chrome.storage.local.get("aside_off").then((x) => x.aside_off || [])), [], "a site was switched off");
  assert.equal((await h.fromPanel({ type: "page_allow" })).allowed, true, "the page consent is still given");
  assert.ok(!h.cloud.sends.some((x) => x.text === "from a page"), "a page's words were sent to the taf");
  await h.fromPanel({ type: "stop", run });
});

test("the person's own input pauses only the run whose tab it is: input in a tab of no run pauses nothing", { skip }, async () => {
  const run = newRun("input-elsewhere");
  started.add(run);
  await open(run, h.sites.url("news.test", "/article?case=input-elsewhere"));
  const other = await h.context.newPage();
  await other.goto(h.sites.url("shop.test", "/form?case=input-elsewhere"));
  const otherId = await inWorker(h, (u) => chrome.tabs.query({}).then((ts) => (ts.find((t) => t.url === u) || {}).id), other.url());
  await fromContentScript(otherId, { type: "owner_input" });
  await new Promise((r) => setTimeout(r, 300));
  assert.ok(!(await runState(h, run)).paused, "a touch in another tab paused the run");
  await other.close();
  await h.fromPanel({ type: "stop", run });
});

// ---- 12. The bar and the badge, after a give-back ----

/**
 * Records, in tab `tabId`'s isolated world, every state the floating bar is shown with from now on (bar.js `TafsBar.show`): the
 * bar's shadow root is closed, so what it says is read where it's given. Nothing of the extension's code changes.
 */
function watchBar(tabId) {
  return inWorker(h, (id) => chrome.scripting.executeScript({
    target: { tabId: id },
    files: ["src/bar.js"],
  }).then(() => chrome.scripting.executeScript({
    target: { tabId: id },
    func: () => {
      globalThis.__e2eBar = [];
      if (globalThis.__e2eBarWatching) return;
      globalThis.__e2eBarWatching = true;
      const show = globalThis.TafsBar.show;
      const hide = globalThis.TafsBar.hide;
      globalThis.TafsBar.show = (s) => { globalThis.__e2eBar.push(s); return show(s); };
      globalThis.TafsBar.hide = () => { globalThis.__e2eBar.push(null); return hide(); };
    },
  })), tabId);
}
const barSaid = (tabId) => inWorker(h, (id) => chrome.scripting.executeScript({ target: { tabId: id }, func: () => (globalThis.__e2eBar || []).at(-1) ?? null }).then(([r]) => r.result), tabId);
const badge = () => inWorker(h, () => chrome.action.getBadgeText({}));

test("after the person takes their tab back, the bar there no longer says it's the run's tab, and the badge counts the run until it ends", { skip }, async () => {
  const url = h.sites.url("news.test", "/hub?case=bar-given");
  const { agentRun, tabId } = await j.sendFromPanel(url);
  const run = newRun("bar-given");
  started.add(run);
  const opened = await j.step({ op: "open", run, url, agent_run: agentRun });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  await watchBar(tabId);
  // A step makes the bars sync: the bar in the person's tab is the run's, "here".
  await j.step({ op: "read", run, agent_run: agentRun });
  const here = await until(async () => {
    const s = await barSaid(tabId);
    return s && s.run === run ? s : null;
  }, "the bar in the run's tab");
  assert.equal(here.here, true);
  assert.notEqual(await badge(), "", "the badge says a taf works");

  // The person types another address of the run's sites in their tab: it's theirs again.
  await j.owner.goto(h.sites.url("shop.test", "/account?case=bar-given"));
  await until(async () => (await tabInfo(h, tabId)).groupId === -1, "their tab given back");
  await watchBar(tabId);
  await j.step({ op: "read", run, agent_run: agentRun }).catch(() => {});
  const after = await until(async () => {
    const s = await barSaid(tabId);
    return s !== undefined && (s === null || s.here === false) ? { s } : null;
  }, "the bar in their tab, after the give-back", 10_000).catch(async () => ({ s: await barSaid(tabId) }));
  assert.ok(after.s === null || after.s.here === false, "the bar still says it's the run's tab: " + JSON.stringify(after.s));
  assert.notEqual(await badge(), "", "the run is still live: the badge still counts it");

  await h.fromPanel({ type: "stop", run });
  await until(async () => (await badge()) === "", "the badge cleared once no run is live");
});

// ---- 8, the rest: what is and isn't a lock ----

/** The second controls page in a new run. */
async function controls2(name) {
  const run = newRun(name);
  started.add(run);
  const url = h.sites.url("shop.test", "/controls2?case=" + name);
  const opened = await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const act = (a) => h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: a });
  return { run, page: opened.page, tab, act };
}

test("a button the person can't press, inside [inert] or marked aria-disabled, is refused, and nothing happens", { skip }, async () => {
  const c = await controls2("inert");
  const inert = (c.page.controls || []).find((x) => x.label === "Inert send");
  const r1 = inert ? await c.act({ kind: "click", ref: inert.ref }) : { ok: false, error: { code: "not_in_read" } };
  const r2 = await c.act({ kind: "click", ref: ref(c.page, "Aria send", "controls") });
  const out = await c.tab.textContent("#out");
  assert.equal(out, "untouched", "a locked button was pressed");
  assert.equal(r1.ok, false);
  assert.equal(r2.ok, false);
});

test("readonly on a checkbox, a radio or a select locks nothing (browsers ignore it): the taf's check and select go through", { skip }, async () => {
  const c = await controls2("readonly-ignored");
  const box = await c.act({ kind: "check", ref: ref(c.page, "Keep me posted"), value: "checked" });
  assert.equal(box.ok, true, JSON.stringify(box.error));
  assert.equal(await c.tab.isChecked("#rc"), true);
  const read = await h.planque.sendStep({ op: "read", run: c.run });
  const radio = await h.planque.sendStep({ op: "act", run: c.run, read_id: read.page.read_id, act: { kind: "check", ref: ref(read.page, "Fast"), value: "checked" } });
  assert.equal(radio.ok, true, JSON.stringify(radio.error));
  assert.equal(await c.tab.isChecked("#rr2"), true);
  const read2 = await h.planque.sendStep({ op: "read", run: c.run });
  const colour = await h.planque.sendStep({ op: "act", run: c.run, read_id: read2.page.read_id, act: { kind: "select", ref: ref(read2.page, "Colour"), value: "Blue" } });
  assert.equal(colour.ok, true, JSON.stringify(colour.error));
  assert.equal(await c.tab.inputValue("#rs"), "Blue");
});

test("a field in the first legend of a disabled fieldset isn't disabled, and takes the taf's fill; one inside the fieldset is refused", { skip }, async () => {
  const c = await controls2("legend");
  const legend = await c.act({ kind: "fill", ref: ref(c.page, "Legend name"), value: "in the legend" });
  assert.equal(legend.ok, true, JSON.stringify(legend.error));
  assert.equal(await c.tab.inputValue("#lg"), "in the legend");
  const read = await h.planque.sendStep({ op: "read", run: c.run });
  const inside = await h.planque.sendStep({ op: "act", run: c.run, read_id: read.page.read_id, act: { kind: "fill", ref: ref(read.page, "Inside name"), value: "inside" } });
  assert.equal(await c.tab.inputValue("#inside"), "", "the field inside the disabled fieldset took the fill");
  assert.equal(inside.ok, false);
});

test("an option of a disabled optgroup is refused, and the choice stays", { skip }, async () => {
  const c = await controls2("optgroup");
  const r = await c.act({ kind: "select", ref: ref(c.page, "Plan"), value: "Pro" });
  assert.equal(await c.tab.inputValue("#og"), "Free", "the disabled optgroup's option was chosen");
  assert.equal(r.ok, false);
});

// ---- A frame that moves the whole tab ----

test("a frame of the run's site can still send the whole tab to another page of the run's sites", { skip }, async () => {
  const run = newRun("frame-top");
  started.add(run);
  await open(run, h.sites.url("shop.test", "/frame-top?case=frame-top"));
  const tabId = (await runState(h, run)).tab;
  await until(async () => /\/article-moved$/.test(((await tabInfo(h, tabId)) || {}).url || ""), "the tab moved by its frame", 5000);
});
