// End to end: the second half of the ranked gaps (docs/chrome-e2e.md, "What each test covers"). A wrong action: no act lands on a
// control the person can't use (a disabled button, a field in a disabled fieldset, a read-only field, a disabled option, a radio
// that can't be unchecked); planque's unsigned stop really ends the run, even mid-act; a page's dialogs as it loads and its "leave
// this page?" never hold the run or reach Chromium; only the extension's own pages command the worker. A stale display: after a
// tab is given back, the floating bar there no longer says it's the run's, and the badge counts live runs only.

import { test, before, after, afterEach } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, runState, tabInfo, rulesOf, inWorker } from "./harness.mjs";
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
  await until(() => tab.evaluate(() => !!document.querySelector("[data-tafs-outline]")), "the taf's outline", 5000);
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

test("an alert a page raises as it loads never reaches Chromium, nor holds the open", { skip }, async () => {
  const dialogs = h.dialogs.length;
  const run = newRun("alert-load");
  started.add(run);
  const r = await open(run, h.sites.url("shop.test", "/at-load-alert?case=load"));
  assert.equal(r.page.title, "Welcome");
  await new Promise((res) => setTimeout(res, 300));
  assert.deepEqual(h.dialogs.slice(dialogs), [], "a dialog reached Chromium");
  await h.fromPanel({ type: "stop", run });
});

test("a confirm a page raises as it loads is answered no, never reaches Chromium, and the read says the page asked", { skip }, async () => {
  const dialogs = h.dialogs.length;
  const run = newRun("confirm-load");
  started.add(run);
  const url = h.sites.url("shop.test", "/at-load-confirm?case=load");
  const r = await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  assert.equal(await tab.textContent("#out"), "refused", "the page's confirm was accepted");
  assert.deepEqual(h.dialogs.slice(dialogs), [], "a dialog reached Chromium");
  // Since 0.2.20 the tab's own sandbox (no allow-modals) drops the call before any script of the extension's is in the page, so
  // the read can't say the page asked as it loaded; a dialog raised later, once the guard is in, is still told.
  assert.equal(r.ok, true, JSON.stringify(r.error));
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


// ---- 0.2.20: the sandbox of a run's own tabs (no allow-modals) ----

test("on one site in one run: the person's own tab gets its real confirm at load; the run's tab gets no, and no dialog reaches Chromium", { skip }, async () => {
  const url = h.sites.url("shop.test", "/at-load-confirm?case=pair");
  const run = newRun("pair");
  started.add(run);
  const dialogs = h.dialogs.length;
  await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  assert.equal(await tab.textContent("#out"), "refused");
  assert.deepEqual(h.dialogs.slice(dialogs), [], "nothing reached Chromium from the run's tab");
  // The person's own tab, on the same page, while the run lives: their dialog is theirs.
  const own = await h.context.newPage();
  const mine = h.sites.url("shop.test", "/at-load-confirm?case=pair-own");
  await own.goto(mine);
  assert.deepEqual(h.dialogs.slice(dialogs).map((d) => [d.type, d.message]), [["confirm", "Stay signed in?"]], "the person's own confirm reached them");
  assert.equal(await own.textContent("#out"), "accepted", "answered by them (the harness accepts)");
  await own.close();
  await h.fromPanel({ type: "stop", run });
});

// A finding, logged for the decision: the sandbox comes off as the person's own navigation starts (onBeforeNavigate), but Chrome
// sends the request without waiting for the extension, so the page they chose may still load sandboxed. Asserted: the tab is
// theirs after, with no rule left, so their next load is their own.
test("finding: an address the person types in the run's own tab on its site, with a confirm at load", { skip }, async () => {
  const run = newRun("typed-sandbox");
  started.add(run);
  const url = h.sites.url("shop.test", "/article?case=typed-sandbox");
  await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const tabId = (await runState(h, run)).tab;
  const dialogs = h.dialogs.length;
  const theirs = h.sites.url("shop.test", "/at-load-confirm?case=typed-sandbox");
  await tab.goto(theirs);
  console.log("FINDING the person's typed page in the run's own tab: its confirm at load answered " + JSON.stringify(await tab.textContent("#out")) + ", dialogs that reached Chromium " + JSON.stringify(h.dialogs.slice(dialogs).map((d) => d.type)));
  await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the tab given to the person", 5000);
  assert.deepEqual(await rulesOf(h, tabId), [], "no rule left on their tab: their next load is their own");
  const next = h.dialogs.length;
  await tab.goto(theirs + "&next");
  assert.deepEqual(h.dialogs.slice(next).map((d) => d.type), ["confirm"], "their next page's confirm reaches them");
  await h.fromPanel({ type: "stop", run });
});

test("a frame that sends the run's own tab to a granted page (as a 3-D Secure frame does) still can, under the sandbox", { skip }, async () => {
  const run = newRun("frametop");
  started.add(run);
  const r = await open(run, h.sites.url("shop.test", "/at-load-frametop?case=frametop"));
  const tabId = (await runState(h, run)).tab;
  await until(async () => String((await tabInfo(h, tabId)).url).includes("/article?case=frametop-done"), "the tab at the page the frame sent it to", 8000);
  assert.ok(r.verified);
  await h.fromPanel({ type: "stop", run });
});

// Findings, measured and logged for the decision, not asserted: loads the header may not reach, and a tab given back.
test("findings: a confirm at load from the HTTP cache, and on a page given to the person without a new load", { skip }, async () => {
  const say = (what, value) => console.log("FINDING " + what + ": " + JSON.stringify(value));
  // HTTP cache: the same cacheable page loaded twice in the run's own tab.
  const run = newRun("cached");
  started.add(run);
  const url = h.sites.url("shop.test", "/at-load-confirmcached?case=cached&bfcache=1");
  await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  say("first load, run's own tab: the page's confirm answered", await tab.textContent("#out"));
  const dialogs = h.dialogs.length;
  const asked = h.sites.hits.filter((x) => x.includes("case=cached")).length;
  await open(run, url + "&again");
  await open(run, url);
  const again = await until(() => tabAt(h, url), "the run's tab again");
  say("second load of the same address: asked of the server again", h.sites.hits.filter((x) => x.includes("case=cached")).length > asked);
  say("second load: the page's confirm answered", await again.textContent("#out"));
  say("second load: dialogs that reached Chromium", h.dialogs.slice(dialogs).map((d) => d.type));
  // A page the run's own tab shows when it's given to the person by their click (the commit gives the tab back; the page shown
  // after it is the new one), versus the page still showing: confirm() on the page the run loaded, after the run let it go.
  await h.fromPanel({ type: "stop", run });
  await until(async () => (await runState(h, run)).tabs.length === 0, "the run's tabs let go", 5000);
  const before = h.dialogs.length;
  const answered = await again.evaluate(() => confirm("Still sandboxed?")).catch((e) => "threw: " + e.message);
  say("after the run ended, confirm() on the page it had loaded answers", answered);
  say("after the run ended: dialogs that reached Chromium", h.dialogs.slice(before).map((d) => d.type));
});
