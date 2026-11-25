// End to end: a taf works where you are (docs/chrome.md, "A taf works where you are", 0.2.12 to 0.2.15). The person sends the taf
// a message from the side panel with their page in front; tafs cloud (the fake one, planque.mjs `FakeCloud`) answers with the
// agent run it starts, and the panel's mark ties that run to the tab. A run whose signed steps name that `agent_run` joins the
// person's tab only to read it where it is, at its exact address; an open elsewhere goes to a tab of the run's own; the person's
// tab is never loaded again or sent elsewhere, by an open or by the fence; acts on it stay inside the run's fence; the taf's back
// never goes before the page it joined at, and a page the person had before is never read; a step of another agent run, or the
// person's own Back off the run's sites, gives the tab back unread. Each test ends with the person's Stop, which gives the tab back.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, rulesOf, runState, tabInfo, fillSessionRules, unfillSessionRules } from "./harness.mjs";
import { joining } from "./joining.mjs";
import { PERSON_VALUE, PERSON_WORDS } from "./sites.mjs";

const skip = !playwright;
let h;
let owner; // the person's tab: the one that opened the side panel, in front of its window
let sendFromPanel, loads, step, stopAndGiveBack, watchMoves, moves;

before(async () => {
  if (skip) return;
  h = await start({ cloud: true });
  // The view-only KIFF device is claimed once the pairing is done; then the person gives the page consent in the panel.
  ({ owner, sendFromPanel, loads, step, stopAndGiveBack, watchMoves, moves } = await joining(h));
});

after(async () => {
  if (h) await h.close();
});

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

/** Everything other.test was asked for since the start, as "other.test path?query". */
const otherHits = () => h.sites.hits.filter((x) => x.startsWith("other.test "));

/** Nothing of the person's own page (sites.mjs `ACCOUNT`): neither its words nor its field's value. */
function nothingOfTheirs(r, what = "the result") {
  const all = JSON.stringify(r);
  assert.ok(!all.includes(PERSON_WORDS), what + " carries the person's page's words");
  assert.ok(!all.includes(PERSON_VALUE), what + " carries the person's field's value");
  assert.ok(!/\/account/.test(all), what + " names the person's page");
}

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

  // A form whose action is on another site: Chrome's fence blocks its submit before it's sent. The person's tab is never sent
  // anywhere by the extension (no about:blank, no tabs.update with an address, no back or forward), and other.test is asked
  // for nothing. (Whether their page stays as it was is the next test's.)
  await watchMoves();
  const asked = otherHits().length;
  r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "click", ref: ref(r.page, "Ask elsewhere", "controls") } });
  assert.notEqual(r.ok, true, "the submit went nowhere the taf can read");
  await until(async () => (await runState(h, run)).steps.some((x) => x.op === "navigate" && x.url === h.sites.url("other.test", "/") && x.error === "site_not_listed"), "the refused move's record");
  assert.equal(otherHits().length, asked, "other.test was never asked for anything");
  assert.deepEqual(h.sites.posts, []);
  const tab = await tabInfo(h, tabId);
  assert.notEqual(tab.url, "about:blank", "the person's tab is never blanked");
  assert.deepEqual(await moves(tabId), [], "nothing moved the person's tab");
  assert.equal((await runState(h, run)).tab, tabId);
  await stopAndGiveBack(run, tabId, tab.url);
});

test("an off-site submit the fence stops in the person's tab leaves their page as it was", {
  skip,
  todo: "Chrome's fence (a declarativeNetRequest block of the main frame) doesn't cancel the navigation: Chrome commits its own error page (chrome-error://chromewebdata/, 'blocked by an extension') at the blocked address in the person's tab, in place of their page, and adds a history entry. The tab stays joined, grouped and fenced on that error page, and the act and the next read answer site_not_listed. runner.js onBeforeNavigate (joined: no tabs.update) assumes 'the page they had stays'; it doesn't.",
}, async () => {
  const url = h.sites.url("news.test", "/hub?case=acts-stay");
  const { agentRun, tabId } = await sendFromPanel(url);
  const run = newRun("acts-stay");
  try {
    let r = await step({ op: "open", run, url, agent_run: agentRun });
    assert.equal(r.ok, true, JSON.stringify(r.error));
    const entries = await owner.evaluate(() => history.length);
    const asked = otherHits().length;
    r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "click", ref: ref(r.page, "Ask elsewhere", "controls") } });
    await until(async () => (await runState(h, run)).steps.some((x) => x.op === "navigate" && x.error === "site_not_listed"), "the refused move's record");
    assert.equal(otherHits().length, asked);
    assert.equal((await tabInfo(h, tabId)).url, url, "the person's tab is still at their page");
    assert.equal(owner.url(), url, "showing it, not Chrome's error page");
    assert.equal(await owner.evaluate(() => history.length), entries, "and their history has nothing new");
    const read = await step({ op: "read", run, agent_run: agentRun });
    assert.equal(read.ok, true, JSON.stringify(read.error));
    assert.equal(read.page.url, h.sites.url("news.test", "/hub"));
  } finally {
    await h.fromPanel({ type: "stop", run });
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab given back");
  }
});

test("the taf's back in the person's tab returns to the page its click left", {
  skip,
  todo: "runner.js back: Chrome skips, on back, the history entry the taf's own click left (it had no user activation: page.js press is el.click()), so chrome.tabs.goBack lands on the page the person had before the run joined; that page is requested again (the fixture server's log shows it) and committed in their tab, and only then does back() go forward again and answer no_back. The answer should be the joined page (the hub), and the person's earlier page never loaded.",
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
    const earlier = loads(before);
    r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "back" } });
    assert.equal(loads(before), earlier, "the person's page before the run was never loaded again");
    assert.equal(r.ok, true, JSON.stringify(r.error));
    assert.equal(owner.url(), url, "the person's tab is back on the hub, not on the page they had before it");
    assert.equal(r.page.url, h.sites.url("news.test", "/hub"));
  } finally {
    await h.fromPanel({ type: "stop", run });
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab given back");
  }
});

for (const host of ["shop.test", "other.test"]) {
  const where = host === "shop.test" ? "on the run's sites" : "off the run's sites";

  test(`the taf's back never goes before the page it joined at: the person's page before it (${where}) is never read nor acted on`, { skip }, async () => {
    // The person's own page A, with their words and a field's value, then page B in the same tab: A is in its history before B.
    const A = h.sites.url(host, "/account?case=exposure-" + host);
    await owner.goto(A);
    const B = h.sites.url("news.test", "/hub?case=exposure-" + host);
    const { agentRun, tabId } = await sendFromPanel(B);
    const run = newRun("exposure");
    let r = await step({ op: "open", run, url: B, agent_run: agentRun });
    assert.equal(r.ok, true, JSON.stringify(r.error));
    const loadsOfA = loads(A);
    const posts = h.sites.posts.length;

    // The run hasn't moved the tab: there is nowhere for its back to go.
    r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "back" } });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, "no_back");
    assert.equal(r.page ?? null, null, "nothing was read");
    nothingOfTheirs(r, "back's answer");
    assert.equal((await tabInfo(h, tabId)).url, B, "the person's tab never left the page it joined at");
    assert.equal(owner.url(), B);
    assert.equal(loads(A), loadsOfA, "A was never loaded again");

    // A read and an act there are the joined page's, and nothing of A's.
    const read = await step({ op: "read", run, agent_run: agentRun });
    assert.equal(read.ok, true, JSON.stringify(read.error));
    assert.equal(read.page.url, h.sites.url("news.test", "/hub"));
    nothingOfTheirs(read, "the read");
    const act = await step({ op: "act", run, agent_run: agentRun, read_id: read.page.read_id, act: { kind: "fill", ref: ref(read.page, "Question"), value: "a question" } });
    assert.equal(act.ok, true, JSON.stringify(act.error));
    assert.equal(act.page.url, h.sites.url("news.test", "/hub"));
    nothingOfTheirs(act, "the act");
    assert.equal(await owner.inputValue("#q"), "a question", "the act was on the joined page");
    assert.equal(h.sites.posts.length, posts, "no form was posted");
    assert.equal(loads(A), loadsOfA);
    await stopAndGiveBack(run, tabId, B);
  });

  test(`after the taf's click, its back reads nothing of the person's page before the joined one (${where})`, { skip }, async () => {
    const A = h.sites.url(host, "/account?case=click-back-" + host);
    await owner.goto(A);
    const B = h.sites.url("news.test", "/hub?case=click-back-" + host);
    const { agentRun, tabId } = await sendFromPanel(B);
    const run = newRun("click-back");
    let r = await step({ op: "open", run, url: B, agent_run: agentRun });
    assert.equal(r.ok, true, JSON.stringify(r.error));
    r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "click", ref: ref(r.page, "Read the long page", "links") } });
    assert.equal(r.page.url, h.sites.url("news.test", "/long"));
    const posts = h.sites.posts.length;
    // Chrome may skip the hub on back (the taf's click gave no user activation): wherever its back went, A is never the answer,
    // and the tab is never left on it.
    r = await step({ op: "act", run, agent_run: agentRun, read_id: r.page.read_id, act: { kind: "back" } });
    nothingOfTheirs(r, "back's answer");
    if (r.ok) assert.equal(r.page.url, h.sites.url("news.test", "/hub"));
    else assert.equal(r.error.code, "no_back");
    const at = (await tabInfo(h, tabId)).url;
    assert.notEqual(at, A, "the person's tab isn't left on their page before the run");
    assert.notEqual(owner.url(), A);
    const read = await step({ op: "read", run, agent_run: agentRun });
    nothingOfTheirs(read, "the read after back");
    if (read.ok) {
      const act = await step({ op: "act", run, agent_run: agentRun, read_id: read.page.read_id, act: { kind: "scroll" } });
      nothingOfTheirs(act, "the act after back");
    }
    assert.equal(h.sites.posts.length, posts, "no form was posted");
    await stopAndGiveBack(run, tabId, at);
  });
}

test("the person's own Back to a page they had before the run, on the run's sites, is never read: the run's read and act there are page_changed", {
  skip,
  todo: "runner.js noteAddress: the webNavigation commit of the person's own Back (to page A, on the run's sites, which they had before the run joined at B) is taken as the run's work and added to the joined tab's trail, so joinedNow finds A in the trail and the run's read answers ok with A's page: its words and its field's value (PERSON_WORDS, PERSON_VALUE) go to planque, and an act on that read acts on A (a click on its Save posted its form: the fixture server logged POST shop.test /account). Only a pause of the person's input keeps such a page out of the trail; a Back pauses nothing.",
}, async () => {
  const A = h.sites.url("shop.test", "/account?case=own-back");
  await owner.goto(A);
  const B = h.sites.url("news.test", "/hub?case=own-back");
  const { agentRun, tabId } = await sendFromPanel(B);
  const run = newRun("own-back");
  try {
    const opened = await step({ op: "open", run, url: B, agent_run: agentRun });
    assert.equal(opened.ok, true, JSON.stringify(opened.error));
    const posts = h.sites.posts.length;
    await owner.goBack();
    assert.equal(owner.url(), A);
    const read = await step({ op: "read", run, agent_run: agentRun });
    nothingOfTheirs(read, "the read");
    assert.equal(read.ok, false);
    assert.equal(read.error.code, "page_changed");
    // An act planned on B's read, and one on whatever read the run could get, reach nothing of A's.
    const act = await step({ op: "act", run, agent_run: agentRun, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Question"), value: "typed by the taf" } });
    nothingOfTheirs(act, "the act");
    assert.equal(act.ok, false);
    assert.equal(act.error.code, "page_changed");
    assert.equal(await owner.inputValue("#notes"), PERSON_VALUE, "their field is as they left it");
    assert.equal(h.sites.posts.length, posts, "no form was posted");
  } finally {
    await h.fromPanel({ type: "stop", run });
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab given back");
  }
});

test("the person's own Back off the run's sites gives their tab back with nothing moved, and the run's next read and act are no_page", {
  skip,
  todo: "With Chrome's back/forward cache off (Playwright's default), and in effect with it on too (bfcache.e2e.test.js: Chrome flushes it as the fence's rules are added, 'CacheFlushed'), the person's Back to their page off the run's sites is a new request, and the joined tab's fence blocks it: Chrome shows its error page (net::ERR_BLOCKED_BY_CLIENT) at that address. webNavigation commits nothing there, so runner.js onCommitted's give-back never runs: the tab stays in the run's group, fenced (its 3 rules), the run's; the run's read and act answer site_not_listed, not no_page; and the person's own Back can't load their page until they Stop.",
}, async () => {
  const A = h.sites.url("other.test", "/account?case=own-back-off");
  await owner.goto(A);
  const B = h.sites.url("news.test", "/hub?case=own-back-off");
  const { agentRun, tabId } = await sendFromPanel(B);
  const run = newRun("own-back-off");
  try {
    const opened = await step({ op: "open", run, url: B, agent_run: agentRun });
    assert.equal(opened.ok, true, JSON.stringify(opened.error));
    await watchMoves();
    const loadsOfA = loads(A);
    await owner.goBack().catch(() => {});
    // Given back: out of the group, unfenced, the run's no more, its bar hidden; at the address the person went to.
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab given back", 5000);
    assert.deepEqual(await rulesOf(h, tabId), [], "unfenced");
    assert.deepEqual((await runState(h, run)).tabs, [], "forgotten by the run");
    assert.equal((await tabInfo(h, tabId)).url, A, "at the address the person went back to");
    assert.equal(owner.url(), A, "their page, not Chrome's error page");
    assert.equal(loads(A), loadsOfA + 1, "their page loaded for them");
    assert.deepEqual(await moves(tabId), [], "nothing moved their tab: no about:blank, no further back");
    const read = await step({ op: "read", run, agent_run: agentRun });
    assert.equal(read.error && read.error.code, "no_page");
    const act = await step({ op: "act", run, agent_run: agentRun, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Question"), value: "typed by the taf" } });
    assert.equal(act.error && act.error.code, "no_page");
    nothingOfTheirs(read);
    nothingOfTheirs(act);
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
  await watchMoves();

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
  assert.equal(owner.url(), url);
  assert.deepEqual(await rulesOf(h, tabId), [], "unfenced");
  assert.deepEqual(await moves(tabId), [], "nothing moved it");
  // An act of the run, planned on its read of the person's tab, finds no page either.
  const act = await step({ op: "act", run, agent_run: "agr_someone_else", read_id: joined.page.read_id, act: { kind: "scroll" } });
  assert.equal(act.ok, false);
  assert.equal(act.error.code, "no_page");

  assert.deepEqual(await h.fromPanel({ type: "stop", run }), {});
});

test("after giving the person's tab back, the run's next open goes on in a tab of its own", { skip }, async () => {
  const url = h.sites.url("news.test", "/article?case=after-give-back");
  const { agentRun, tabId } = await sendFromPanel(url);
  const run = newRun("after-give-back");
  try {
    const joined = await step({ op: "open", run, url, agent_run: agentRun });
    assert.equal(joined.ok, true, JSON.stringify(joined.error));
    const read = await step({ op: "read", run, agent_run: "agr_someone_else" });
    assert.equal(read.error.code, "no_page");
    // Chrome ended the run's group as the person's tab left it.
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab out of the group");
    // The run's next open, even of the person's address, goes to a tab of its own: it can't join one again.
    const again = await step({ op: "open", run, url, agent_run: "agr_someone_else" });
    assert.equal(again.ok, true, JSON.stringify(again.error));
    assert.equal(again.page.url, h.sites.url("news.test", "/article"));
    const s = await runState(h, run);
    assert.ok(!s.stopped, "the run goes on");
    assert.notEqual(s.tab, tabId);
    assert.deepEqual(s.tabs, [s.tab]);
    assert.equal(loads(url), 2, "loaded in the run's own tab, not the person's");
    assert.equal((await tabInfo(h, tabId)).groupId, -1);
    const own = await tabInfo(h, s.tab);
    assert.equal(own.url, url);
    assert.notEqual(own.groupId, -1, "in a group made again");
    assert.equal((await rulesOf(h, s.tab)).length, 3, "fenced");
  } finally {
    await h.fromPanel({ type: "stop", run });
  }
});

test("a further tab whose fence Chrome can't install closes before it loads, and the person's tab stays as it was", { skip }, async () => {
  const url = h.sites.url("news.test", "/article?case=unfenceable-more");
  const { agentRun, tabId } = await sendFromPanel(url);
  const run = newRun("unfenceable-more");
  const joined = await step({ op: "open", run, url, agent_run: agentRun });
  assert.equal(joined.ok, true, JSON.stringify(joined.error));
  // Chrome's limit on session rules filled from the service worker: the next fence is refused.
  const filled = await fillSessionRules(h);
  try {
    assert.equal(filled.total, filled.max, JSON.stringify(filled));
    const pages = h.context.pages().length;
    const other = h.sites.url("shop.test", "/form?case=unfenceable-more");
    const r = await step({ op: "open", run, url: other, agent_run: agentRun });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, "browser_unavailable");
    assert.equal(loads(other), 0, "the page was never asked for");
    await until(() => h.context.pages().length === pages, "the unfenced tab to close");
    const s = await runState(h, run);
    assert.ok(s.tabs.every((t) => t === tabId), "no unfenced tab is the run's: " + JSON.stringify(s.tabs));
    assert.equal(owner.url(), url, "the person's tab is where it was");
  } finally {
    await unfillSessionRules(h);
  }
  const s = await runState(h, run);
  if (s.stopped) {
    // The failure ended the run: the person's tab was given back as at a Stop.
    await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the person's tab given back");
    assert.deepEqual(await rulesOf(h, tabId), []);
  } else await stopAndGiveBack(run, tabId, url);
});
