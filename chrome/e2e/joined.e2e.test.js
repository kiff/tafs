// End to end: a taf works where you are (docs/chrome.md, "A taf works where you are", 0.2.12 to 0.2.14). The person sends the taf
// a message from the side panel with their page in front; tafs cloud (the fake one, planque.mjs `FakeCloud`) answers with the
// agent run it starts, and the panel's mark ties that run to the tab. A run whose signed steps name that `agent_run` joins the
// person's tab only to read it where it is, at its exact address; an open elsewhere goes to a tab of the run's own; the person's
// tab is never loaded again or sent elsewhere by an open; acts on it stay inside the run's fence; a step of another agent run
// gives the tab back unread. Each test ends with the person's Stop, which gives the tab back.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, rulesOf, runState, tabInfo } from "./harness.mjs";

const skip = !playwright;
let h;
let owner; // the person's tab: the one that opened the side panel, in front of its window

before(async () => {
  if (skip) return;
  h = await start({ cloud: true });
  // The view-only KIFF device is claimed once the pairing is done; then the person gives the page consent in the panel.
  await until(() => h.cloud.claimed, "the KIFF device's claim", 15_000);
  await until(async () => (await h.fromPanel({ type: "page_allow" })).allowed === true, "the page consent");
  owner = h.context.pages()[0];
});

after(async () => {
  if (h) await h.close();
});

/** The panel's window and the tab in front of it, as the panel knows them. */
function front() {
  return h.inPanel(`chrome.windows.getCurrent().then(async (w) => {
    const [t] = await chrome.tabs.query({ active: true, windowId: w.id });
    return { windowId: w.id, tabId: t.id, url: t.url };
  })`);
}

/**
 * The person, on `url` in their tab, sends the taf a message from the panel, as panel.js's composer does (`taf_send`, with the
 * panel's window and the tab in front). Resolves with the agent run tafs cloud started for it.
 */
async function sendFromPanel(url, words = "Sum up this page") {
  if (owner.url() !== url) await owner.goto(url);
  // The person's tab in front of the panel's window: a run Chrome followed may have brought its own tab there.
  await owner.bringToFront();
  const { windowId, tabId } = await until(async () => {
    const f = await front();
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
}

/** How many times the fixture sites were asked for `url`. */
const loads = (url) => h.sites.hits.filter((x) => x === new URL(url).hostname + " " + new URL(url).pathname + new URL(url).search).length;

async function step(fields) {
  const r = await h.planque.sendStep(fields);
  assert.equal(r.verified, true);
  return r;
}

/** The person's Stop in the panel: the run ends and their tab goes back to them, out of the group, unfenced, still open. */
async function stopAndGiveBack(run, tabId, url) {
  assert.deepEqual(await h.fromPanel({ type: "stop", run }), {});
  await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab out of the run's group");
  assert.deepEqual(await rulesOf(h, tabId), [], "its fence rules went");
  const tab = await tabInfo(h, tabId);
  assert.equal(tab.url, url, "still open, where it was");
  assert.equal((await runState(h, run)).stopped, true);
}

test("a run started from the panel reads the person's tab where it is, only when its open asks for that exact address", { skip }, async () => {
  const url = h.sites.url("news.test", "/article?case=exact");
  // A first open of another address, even the same page with another query, joins nothing: the run opens a tab of its own.
  const first = await sendFromPanel(url);
  const elsewhere = newRun("not-exact");
  const r1 = await step({ op: "open", run: elsewhere, url: url + "&more=1", agent_run: first.agentRun });
  assert.equal(r1.ok, true, JSON.stringify(r1.error));
  const s1 = await runState(h, elsewhere);
  assert.notEqual(s1.tab, first.tabId, "a tab of the run's own");
  assert.equal((await tabInfo(h, s1.tab)).url, url + "&more=1");
  assert.equal(owner.url(), url, "the person's tab is as it was");
  assert.equal((await tabInfo(h, first.tabId)).groupId, -1, "and in no run's group");
  assert.deepEqual(await rulesOf(h, first.tabId), [], "nor fenced");
  assert.deepEqual(await h.fromPanel({ type: "stop", run: elsewhere }), {});

  // The exact address: the person's tab joins, and is read in place, never loaded again.
  const second = await sendFromPanel(url);
  const run = newRun("exact");
  const before = new Set(h.context.pages());
  const r = await step({ op: "open", run, url, agent_run: second.agentRun });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.page.url, h.sites.url("news.test", "/article"));
  assert.equal(r.page.title, "Tide tables");
  const s = await runState(h, run);
  assert.equal(s.tab, second.tabId, "the run works in the person's tab");
  assert.deepEqual(s.tabs, [second.tabId]);
  assert.deepEqual(h.context.pages().filter((p) => !before.has(p)).map((p) => p.url()), [], "no tab was opened");
  assert.equal(loads(url), 1, "read where it is: the page was loaded once, by the person");
  assert.equal(owner.url(), url);
  const tab = await tabInfo(h, second.tabId);
  assert.notEqual(tab.groupId, -1, "it joined the run's group");
  assert.equal((await rulesOf(h, second.tabId)).length, 3, "fenced as a run's own tab");
  // A read while the run is on it reads it in place too.
  const read = await step({ op: "read", run, agent_run: second.agentRun });
  assert.equal(read.ok, true, JSON.stringify(read.error));
  assert.equal(loads(url), 1);
  await stopAndGiveBack(run, second.tabId, url);
});

test("an open of another address goes to a tab of the run's own: the person's tab never changes address", { skip }, async () => {
  const url = h.sites.url("news.test", "/article?case=elsewhere");
  const { agentRun, tabId } = await sendFromPanel(url);
  const run = newRun("elsewhere");
  const joined = await step({ op: "open", run, url, agent_run: agentRun });
  assert.equal(joined.ok, true, JSON.stringify(joined.error));
  assert.equal((await runState(h, run)).tab, tabId);

  const other = h.sites.url("news.test", "/long?case=elsewhere");
  const r = await step({ op: "open", run, url: other, agent_run: agentRun });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.page.url, h.sites.url("news.test", "/long"));
  const s = await runState(h, run);
  assert.notEqual(s.tab, tabId, "the run works in a tab of its own now");
  assert.deepEqual(s.tabs.sort(), [tabId, s.tab].sort());
  const own = await tabInfo(h, s.tab);
  assert.equal(own.url, other);
  assert.equal(own.groupId, (await tabInfo(h, tabId)).groupId, "in the same group");
  assert.equal((await rulesOf(h, s.tab)).length, 3, "fenced before it loaded");
  assert.equal(owner.url(), url, "the person's tab is where they left it");
  assert.equal((await tabInfo(h, tabId)).url, url);

  // An open of the address the person's tab shows reads it where it is again, never loading it.
  const back = await step({ op: "open", run, url, agent_run: agentRun });
  assert.equal(back.ok, true, JSON.stringify(back.error));
  assert.equal((await runState(h, run)).tab, tabId);
  assert.equal(loads(url), 1);
  await stopAndGiveBack(run, tabId, url);
});

test("the taf's acts in the person's tab stay inside the run's sites: a link on them works, a form to another site is fenced", { skip }, async () => {
  const url = h.sites.url("news.test", "/hub?case=acts");
  const { agentRun, tabId } = await sendFromPanel(url);
  const run = newRun("acts");
  let r = await step({ op: "open", run, url, agent_run: agentRun });
  assert.equal(r.ok, true, JSON.stringify(r.error));

  // A link on the run's sites: the taf's click moves the person's tab there, still the run's.
  r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "click", ref: ref(r.page, "Read the long page", "links") } });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.page.url, h.sites.url("news.test", "/long"));
  assert.equal((await runState(h, run)).tab, tabId);
  assert.equal(owner.url(), h.sites.url("news.test", "/long?case=hub"));

  // Back on the hub (an open of its address goes to a tab of the run's own, so the person goes back themselves).
  await owner.goBack();
  assert.equal(owner.url(), url);
  r = await step({ op: "read", run, agent_run: agentRun });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.page.url, h.sites.url("news.test", "/hub"));

  // A form whose action is on another site: its submit is blocked before it's sent, and the tab goes to a blank page, as a
  // run's own tab does ("Fenced as a run's own tab").
  r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "click", ref: ref(r.page, "Ask elsewhere", "controls") } });
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "bad_url");
  await until(async () => (await runState(h, run)).steps.some((x) => x.op === "navigate" && x.url === h.sites.url("other.test", "/") && x.error === "site_not_listed"), "the refused move's record");
  assert.ok(!h.sites.reached("other.test"), "other.test was never asked for anything");
  assert.equal((await tabInfo(h, tabId)).url, "about:blank");
  await stopAndGiveBack(run, tabId, "about:blank");
});

test("the taf's back in the person's tab returns to the page its click left", {
  skip,
  todo: "runner.js act back (chrome.tabs.goBack) skips the page the taf's own click left and lands on the person's page before it. The likely cause is Chrome's history intervention: an entry the page left without a user's activation is skipped on back, and a taf's click (page.js press: el.click(), untrusted) gives none. In a run's own tab, with nothing before that page, back does nothing and answers ok with the page it was on (steps.e2e.test.js).",
}, async () => {
  const before = h.sites.url("news.test", "/article?case=back-before");
  await owner.goto(before);
  const url = h.sites.url("news.test", "/hub?case=back");
  const { agentRun, tabId } = await sendFromPanel(url);
  const run = newRun("back");
  try {
    let r = await step({ op: "open", run, url, agent_run: agentRun });
    assert.equal(r.ok, true, JSON.stringify(r.error));
    r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "click", ref: ref(r.page, "Read the long page", "links") } });
    assert.equal(r.page.url, h.sites.url("news.test", "/long"));
    r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "back" } });
    assert.equal(r.ok, true, JSON.stringify(r.error));
    assert.equal(owner.url(), url, "the person's tab is back on the hub, not on the page they had before it");
    assert.equal(r.page.url, h.sites.url("news.test", "/hub"));
  } finally {
    await h.fromPanel({ type: "stop", run });
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab given back");
  }
});

test("a step of another agent run gives the person's tab back unread: out of the group, unfenced, still where it was", { skip }, async () => {
  const url = h.sites.url("news.test", "/article?case=other-run");
  const { agentRun, tabId } = await sendFromPanel(url);
  const run = newRun("other-run");
  const joined = await step({ op: "open", run, url, agent_run: agentRun });
  assert.equal(joined.ok, true, JSON.stringify(joined.error));
  assert.equal((await rulesOf(h, tabId)).length, 3);

  // A schedule, the phone or a later message: planque keeps the run, but its steps name another agent run.
  const r = await step({ op: "read", run, agent_run: "agr_someone_else" });
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "no_page", "nothing was read: the run has no tab of its own yet");
  assert.equal(r.page ?? null, null, "no page in the answer");
  const s = await runState(h, run);
  assert.ok(!s.stopped, "the run goes on");
  assert.deepEqual(s.tabs, [], "the person's tab is the run's no more");
  const tab = await tabInfo(h, tabId);
  assert.equal(tab.groupId, -1, "out of the group");
  assert.equal(tab.url, url, "still open, where it was");
  assert.deepEqual(await rulesOf(h, tabId), [], "unfenced");

  assert.deepEqual(await h.fromPanel({ type: "stop", run }), {});
});

test("after giving the person's tab back, the run's next open goes on in a tab of its own", {
  skip,
  todo: "runner.js giveBackJoined ungroups the person's tab, which empties the run's group, and Chrome ends an empty group; run.groupId still names it, so the next open (run.tab null: a tab of its own) skips making a group, tabGroups.update throws 'No group with id', and the open's catch stops and closes the run: the step answers browser_unavailable instead of going on as docs/chrome.md says.",
}, async () => {
  const url = h.sites.url("news.test", "/article?case=after-give-back");
  const { agentRun, tabId } = await sendFromPanel(url);
  const run = newRun("after-give-back");
  try {
    const joined = await step({ op: "open", run, url, agent_run: agentRun });
    assert.equal(joined.ok, true, JSON.stringify(joined.error));
    const read = await step({ op: "read", run, agent_run: "agr_someone_else" });
    assert.equal(read.error.code, "no_page");
    // The run's next open, even of the person's address, goes to a tab of its own: it can't join one again.
    const again = await step({ op: "open", run, url, agent_run: "agr_someone_else" });
    assert.equal(again.ok, true, JSON.stringify(again.error));
    assert.notEqual((await runState(h, run)).tab, tabId);
    assert.equal(loads(url), 2, "loaded in the run's own tab, not the person's");
    assert.equal((await tabInfo(h, tabId)).groupId, -1);
  } finally {
    await h.fromPanel({ type: "stop", run });
  }
});
