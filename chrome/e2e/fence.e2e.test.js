// End to end: the fence of a run's tabs (docs/chrome.md, "Runs": "A request off the run's sites never leaves"). Each way a page
// of the run's sites can leave for another site (a redirect, a form that posts there, a refresh, a page's own script, a link to a
// new tab, a frame) is stopped before other.test is asked for anything, which the fixture server's log shows. The fence is
// Chrome's declarativeNetRequest session rules, RULES_PER_TAB per tab (runner.js fence), read here from the service worker: they exist while the run
// lives, and go when it ends (planque's stop, the taf's close, unpairing).

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, pairNow, SITES, RULES_PER_TAB, inWorker, sessionRules, rulesOf, fillSessionRules, unfillSessionRules, runState, tabInfo, extensionState } from "./harness.mjs";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start();
});

after(async () => {
  if (h) await h.close();
});

/** Everything other.test was ever asked for, in this file: nothing, whatever a test did. */
const otherHits = () => h.sites.hits.filter((x) => x.startsWith("other.test "));

/** The address the run's record names for a refused move to other.test's `path` (no query: `bareURL`). */
const offSite = (path = "/") => h.sites.url("other.test", path);

async function open(run, path) {
  const r = await h.planque.sendStep({ op: "open", run, url: h.sites.url("shop.test", path) });
  assert.equal(r.verified, true);
  return r;
}

/** Waits for the run's record of a move off its sites to `url`, refused before it loaded, and gives the run as published. */
async function refusedMove(run, url) {
  return until(async () => {
    const s = await runState(h, run);
    return s && s.steps.some((x) => x.op === "navigate" && x.url === url && x.error === "site_not_listed") ? s : null;
  }, "the run's record of the refused move to " + url);
}

/** The run's tab, as Chrome has it now. */
async function runTab(run) {
  const s = await runState(h, run);
  return tabInfo(h, s.tab);
}

// Chrome holds a pattern only within a memory budget: one with every sensitive word was refused (memoryLimitExceeded), and the
// fence then fails closed for every run. Each of the fence's patterns must be one this Chromium holds.
test("this Chromium holds every pattern of the fence's sensitive labels", { skip }, async () => {
  const { SENSITIVE_LABELS_URLS } = await import("../src/sites.js");
  for (const regex of SENSITIVE_LABELS_URLS) {
    const said = await inWorker(h, (r) => chrome.declarativeNetRequest.isRegexSupported({ regex: r }), regex);
    assert.deepEqual(said, { isSupported: true }, regex);
  }
});

test("a redirect from a page of the run's sites to another site is blocked by the fence: the open is refused and nothing reached", { skip }, async () => {
  const run = newRun("redirect");
  const r = await open(run, "/redirect");
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "site_not_listed", "the read finds the tab at the blocked address, not a page of the run's");
  assert.ok(h.sites.hits.includes("shop.test /redirect"), "the granted page was asked for");
  assert.deepEqual(otherHits(), [], "the redirect's target never was");
  // webNavigation never sees a redirect (it's no new navigation): Chrome's fence alone stopped it. The tab shows Chrome's error
  // page for the blocked address, and a read of it is refused, so nothing of it is ever read.
  const tab = await runTab(run);
  assert.equal(tab.url, offSite("/?from=redirect"));
  assert.ok(h.context.pages().some((p) => p.url() === "chrome-error://chromewebdata/"));
  const read = await h.planque.sendStep({ op: "read", run });
  assert.equal(read.error.code, "site_not_listed");
  assert.deepEqual(otherHits(), []);
});

test("a form that posts to another site is fenced: the taf's click sends nothing there, and the tab goes to a blank page", { skip }, async () => {
  const run = newRun("post");
  const opened = await open(run, "/leave-post");
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const filled = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Note"), value: "a note for elsewhere" } });
  assert.equal(filled.ok, true, JSON.stringify(filled.error));
  const r = await h.planque.sendStep({ op: "act", run, read_id: filled.page.read_id, act: { kind: "click", ref: ref(filled.page, "Send the note", "controls") } });
  assert.equal(r.verified, true);
  // The click ran; the post it started was blocked before it was sent, and the tab was sent to the run's blank page, where the
  // read back finds nothing of the run's.
  assert.equal(r.ok, false);
  // The act is refused by the fence's backstop as the run's own act heading off its sites, which answers why (`site_not_listed`, 0.2.19; it
  // was the blank page's `bad_url` before).
  assert.equal(r.error.code, "site_not_listed");
  await refusedMove(run, offSite("/collect"));
  assert.equal((await runTab(run)).url, "about:blank");
  assert.deepEqual(otherHits(), [], "no post, and no request at all, reached other.test");
  assert.deepEqual(h.sites.posts, []);
});

test("a meta refresh to another site is fenced: the tab goes to a blank page and nothing reached", { skip }, async () => {
  const run = newRun("refresh");
  const r = await open(run, "/leave-refresh");
  assert.equal(r.verified, true);
  // The refresh fires as the page loads. The open's read comes after the load and a settle, so it finds the tab already sent to
  // the blank page (bad_url); only if the refresh were slower than that could it read the page itself first. The refresh is the
  // page's own, not the open's step, so the open isn't told why.
  if (r.ok) assert.equal(r.page.url, h.sites.url("shop.test", "/leave-refresh"));
  else assert.ok(["bad_url", "site_not_listed"].includes(r.error.code), r.error.code);
  await refusedMove(run, offSite("/"));
  await until(async () => (await runTab(run)).url === "about:blank", "the tab on the run's blank page");
  assert.deepEqual(otherHits(), []);
});

test("the page's own script leaving with location.assign() is fenced: nothing reached", { skip }, async () => {
  const run = newRun("assign");
  const opened = await open(run, "/leave-assign");
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  // The taf clicks a button whose handler, the page's script, calls location.assign() to other.test.
  const r = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Go elsewhere", "controls") } });
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "site_not_listed", "the act's answer says why");
  await refusedMove(run, offSite("/"));
  assert.equal((await runTab(run)).url, "about:blank");
  assert.deepEqual(otherHits(), []);
});

test("a target=_blank link to another site opens no new tab: it's followed in the run's tab, and fenced there", { skip }, async () => {
  const run = newRun("blank");
  const opened = await open(run, "/leave-blank");
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const tabs = h.context.pages().length;
  const r = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Open it in a new tab", "links") } });
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "site_not_listed", "the act's answer says why");
  await refusedMove(run, offSite("/"));
  assert.equal((await runTab(run)).url, "about:blank", "the run's own tab went, not a new one");
  assert.equal(h.context.pages().length, tabs, "no tab was opened");
  assert.equal((await runState(h, run)).tabs.length, 1);
  assert.deepEqual(otherHits(), []);
});

test("a frame from another site in a page of the run's sites is never loaded, and the page is read as usual", { skip }, async () => {
  const run = newRun("frame");
  const r = await open(run, "/leave-frame");
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.page.url, h.sites.url("shop.test", "/leave-frame"));
  assert.match(r.page.text, /A frame from elsewhere/);
  assert.deepEqual(otherHits(), [], "the frame's request was blocked in the tab");
  // A frame is no move of the tab: nothing is recorded, and the tab stays on its page.
  assert.ok(!(await runState(h, run)).steps.some((x) => x.op === "navigate"));
  assert.equal((await runTab(run)).url, h.sites.url("shop.test", "/leave-frame"));
});

/** The rules a run's tab gets as it opens, before it loads anything (runner.js `fence`): RULES_PER_TAB of them, by priority. */
function assertFenced(rules, tabId) {
  assert.equal(rules.length, RULES_PER_TAB, JSON.stringify(rules));
  const frames = ["main_frame", "sub_frame"];
  for (const r of rules) assert.deepEqual([r.condition.tabIds, r.condition.resourceTypes], [[tabId], frames]);
  const at = (p) => rules.filter((r) => r.priority === p);
  assert.deepEqual(at(1).map((r) => [r.action.type, r.condition.requestDomains || r.condition.regexFilter || null]), [["block", null]]);
  assert.deepEqual(at(2).map((r) => [r.action.type, r.condition.requestDomains]), [["allow", SITES]]);
  assert.ok(at(3).length >= 2 && at(3).every((r) => r.action.type === "block"), "the sensitive sites, by name and by label");
  assert.equal(at(4).length, 0, "no sensitive site allowed here");
  assert.deepEqual(at(5).map((r) => r.action.type), ["block", "block", "block"]);
  assert.ok(at(5).some((r) => r.condition.regexFilter === "^http://"), "plain http");
  assert.ok(at(5).some((r) => r.condition.regexFilter === "^https?://[^/?#]*@"), "user info");
  assert.ok(at(5).some((r) => (r.condition.requestDomains || []).includes("accounts.google.com")), "Chrome's own pages");
}

/** Opens a page in a new run and gives the run and its tab id, once its rules are seen. */
async function fencedRun(name) {
  const run = newRun(name);
  const r = await h.planque.sendStep({ op: "open", run, url: h.sites.url("news.test", "/article?case=" + name) });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  const tab = (await runState(h, run)).tab;
  assertFenced(await rulesOf(h, tab), tab);
  return { run, tab };
}

test("while a run lives its tab has its rules, and planque's stop takes them away", { skip }, async () => {
  const { run, tab } = await fencedRun("rules-stop");
  const other = await fencedRun("rules-bystander");
  const stopped = h.planque.next("run_state", (m) => m.run === run && m.state === "stopped");
  h.planque.stopRun(run);
  await stopped;
  await until(async () => (await rulesOf(h, tab)).length === 0, "the stopped run's rules to go");
  assert.ok(await tabInfo(h, tab), "its tab stays open, unfenced");
  assertFenced(await rulesOf(h, other.tab), other.tab);
});

test("the taf's close takes its run's rules away before it answers", { skip }, async () => {
  const { run, tab } = await fencedRun("rules-close");
  const closed = await h.planque.sendStep({ op: "close", run });
  assert.equal(closed.ok, true, JSON.stringify(closed.error));
  assert.deepEqual(await rulesOf(h, tab), []);
});

test("unpairing (planque's close 4410) stops every run and leaves no rule at all", { skip }, async () => {
  const { tab } = await fencedRun("rules-unpair");
  const live = (await extensionState(h)).runs.filter((r) => !r.stopped);
  assert.ok(live.length >= 2, "runs of this file's earlier tests still live");
  assert.ok((await sessionRules(h)).length >= RULES_PER_TAB * live.length);
  h.planque.send({ type: "close", code: 4410, reason: "unpaired" });
  await until(async () => {
    const s = await extensionState(h);
    return s && !s.paired;
  }, "the extension to say it's unpaired");
  await until(async () => (await sessionRules(h)).length === 0, "every session rule to go");
  assert.equal(await tabInfo(h, tab), null, "unpairing closes the run's own tabs");
  // Paired again, for the test after this one.
  h.planque.newCode();
  await pairNow(h);
});

test("the person closing a run's last tab ends the run, and its tab's rules go with it", { skip }, async () => {
  const { run, tab } = await fencedRun("rules-person-closes");
  const page = await until(() => h.context.pages().find((p) => p.url() === h.sites.url("news.test", "/article?case=rules-person-closes")), "the run's tab");
  const stopped = h.planque.next("run_state", (m) => m.run === run && m.state === "stopped");
  await page.close();
  await stopped;
  await until(async () => (await runState(h, run)).stopped, "the run to end");
  assert.deepEqual((await runState(h, run)).tabs, []);
  // Nothing is left for a tab that no longer exists (the unfence goes with the close: a few seconds at most).
  await until(async () => (await rulesOf(h, tab)).length === 0, "the closed tab's rules to go", 3000);
});

test("a fence Chrome can't install loads nothing: the run's new tab closes, its page is never asked for, and the step says why", { skip }, async () => {
  // Chrome's limit on session rules, filled by the test from the service worker (rules no run's tab names), so the run's fence fails
  // as Chrome refuses it. The extension's code is as it ships.
  const filled = await fillSessionRules(h);
  try {
    assert.equal(filled.total, filled.max, JSON.stringify(filled));
    // The run's first open: its tab is made, its fence refused, and the tab closes before anything loads.
    const run = newRun("unfenceable");
    const url = h.sites.url("news.test", "/article?case=unfenceable");
    const pages = h.context.pages().length;
    const r = await h.planque.sendStep({ op: "open", run, url });
    assert.equal(r.verified, true);
    assert.equal(r.ok, false);
    assert.equal(r.error.code, "browser_unavailable");
    assert.ok(!h.sites.hits.some((x) => x.includes("case=unfenceable")), "the page was never asked for");
    await until(() => h.context.pages().length === pages, "the run's tab to close");
    assert.ok(!h.context.pages().some((p) => p.url() === url));
    const s = await runState(h, run);
    assert.deepEqual(s.tabs, []);
  } finally {
    await unfillSessionRules(h);
  }
});
