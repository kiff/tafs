// End to end: the run's OWN tabs are the person's to use too (docs/chrome.md, "Runs"). They can see them, type an address there,
// press Back or Forward, click; whatever page that leaves in the tab is theirs, and the run's next read or act takes nothing of
// it, as in a tab of theirs a run joined (joined.e2e.test.js). Dragging a run's tab out of its group, or one of the person's own
// tabs into it, changes nothing of what the run may read. Two runs of two tafs at once each work in their own tabs only.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, runState, tabInfo, rulesOf, inWorker } from "./harness.mjs";
import { PERSON_WORDS, PERSON_VALUE } from "./sites.mjs";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start();
});

after(async () => {
  if (h) await h.close();
});

/** What a step answers when the page in the run's tab is the person's: refused, as in a tab of theirs the run joined. */
const NOT_THE_RUNS = ["page_changed", "given_back"];

async function open(run, url, fields = {}) {
  const r = await h.planque.sendStep({ op: "open", run, url, ...fields });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  return r;
}

/** The run's own tab, as Playwright drives it (the person's hands), and its id. */
async function ownTab(run, url) {
  const page = await until(() => tabAt(h, url), "the run's own tab at " + url);
  const tabId = (await runState(h, run)).tab;
  return { page, tabId };
}

/** Nothing of the person's page went with a result. */
function nothingOf(result, ...words) {
  const text = JSON.stringify(result);
  for (const w of [PERSON_WORDS, PERSON_VALUE, ...words]) assert.ok(!text.includes(w), "the result carries " + w);
}

/** `r` is refused as the person's page, with nothing of it read. */
function refusedAsTheirs(r, what) {
  assert.equal(r.verified, true);
  assert.equal(r.ok, false, what + ": the run took the person's page: " + JSON.stringify(r.page && { url: r.page.url, title: r.page.title }));
  assert.ok(NOT_THE_RUNS.includes(r.error.code), what + ": " + r.error.code);
  assert.ok(!r.page);
  nothingOf(r);
}

test("an address the person types in the run's own tab, on the run's sites: the run's next read and act take nothing of it", { skip }, async () => {
  const run = newRun("own-typed");
  const hub = h.sites.url("news.test", "/hub?case=own-typed");
  const opened = await open(run, hub);
  const { page, tabId } = await ownTab(run, hub);
  // The person types their account's address in it: a page of the run's sites, theirs.
  const account = h.sites.url("shop.test", "/account?case=own-typed");
  await page.goto(account);
  await until(async () => (await tabInfo(h, tabId)).url === account, "the person's page in the tab");
  const posts = h.sites.posts.length;

  refusedAsTheirs(await h.planque.sendStep({ op: "read", run }), "read");
  const act = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "fill", ref: ref(opened.page, "Question"), value: "typed by the taf" } });
  assert.equal(act.ok, false, "the act was refused");
  nothingOf(act);
  assert.equal(await page.inputValue("#notes"), PERSON_VALUE, "nothing reached their page");
  assert.equal(h.sites.posts.length, posts, "nothing was sent from it");
  assert.equal(page.url(), account, "and it stays where they took it");

  // The run's own open goes on: a page of its own, read as before.
  const again = await open(run, hub);
  assert.equal(again.page.title, "Harbour news");
});

test("the person's Back and Forward in the run's own tab: the run's next read takes nothing of the page they land on", { skip }, async () => {
  const run = newRun("own-back");
  const first = h.sites.url("news.test", "/article?case=own-back");
  const second = h.sites.url("news.test", "/long?case=own-back");
  await open(run, first);
  await open(run, second);
  const { page, tabId } = await ownTab(run, second);

  await page.goBack();
  await until(async () => (await tabInfo(h, tabId)).url === first, "Back, in the run's tab");
  refusedAsTheirs(await h.planque.sendStep({ op: "read", run }), "read after the person's Back");

  await page.goForward();
  await until(async () => (await tabInfo(h, tabId)).url === second, "Forward, in the run's tab");
  refusedAsTheirs(await h.planque.sendStep({ op: "read", run }), "read after the person's Forward");

  // The taf's own back goes nowhere the person's Back and Forward took it: nothing of the person's moves is in its trail.
  const back = await h.planque.sendStep({ op: "act", run, read_id: "rd_000000000000000000000000", act: { kind: "back" } });
  assert.equal(back.ok, false);
  nothingOf(back);
});

test("an address the person types in the run's own tab OFF the run's sites: the tab is theirs, unfenced, and they land on it", { skip }, async () => {
  const run = newRun("own-typed-off");
  const hub = h.sites.url("news.test", "/hub?case=own-typed-off");
  await open(run, hub);
  const { page, tabId } = await ownTab(run, hub);
  assert.ok((await rulesOf(h, tabId)).length > 0, "the run's tab is fenced");
  // The person types another site's address there. The fence stops the first request; the tab is then given to them, and their
  // address is asked for again with no fence on it.
  const elsewhere = h.sites.url("other.test", "/?case=own-typed-off");
  await page.goto(elsewhere).catch(() => {});
  await until(async () => (await tabInfo(h, tabId)).url === elsewhere && (await page.title()) !== "", "the person on the address they typed", 10_000);
  assert.deepEqual(await rulesOf(h, tabId), [], "no fence rule left on their tab");
  const state = await runState(h, run);
  assert.ok(!state || !state.tabs.includes(tabId), "the run let the tab go");
  refusedAsTheirs(await h.planque.sendStep({ op: "read", run }), "read after their address");
  // The run's next page opens in a fresh tab of its own; theirs stays where they took it.
  const again = await open(run, hub);
  assert.equal(again.page.title, "Harbour news");
  assert.notEqual((await runState(h, run)).tab, tabId, "a fresh tab of the run's own");
  assert.equal((await tabInfo(h, tabId)).url, elsewhere, "their tab stays on their page");
  await h.fromPanel({ type: "stop", run });
});

test("a link the person clicks in the run's own tab: the run pauses, and its next read and act take nothing of the page", { skip }, async () => {
  const run = newRun("own-click");
  const hub = h.sites.url("news.test", "/hub?case=own-click");
  const opened = await open(run, hub);
  const { page, tabId } = await ownTab(run, hub);
  const paused = h.planque.next("run_state", (m) => m.run === run && m.state === "paused");
  const long = h.sites.url("news.test", "/long?case=hub");
  await page.click("#long");
  await paused;
  await until(async () => (await tabInfo(h, tabId)).url === long, "the page the person's click opened");
  const read = await h.planque.sendStep({ op: "read", run });
  assert.equal(read.ok, false);
  assert.equal(read.error.code, "paused_by_owner");
  assert.ok(!read.page);
  const act = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Read the long page", "links") } });
  assert.equal(act.ok, false);
  assert.equal(act.error.code, "paused_by_owner");
});

test("the person drags the run's tab out of its group: it stays the run's, fenced, and only its own page is read", { skip }, async () => {
  const run = newRun("own-out");
  const url = h.sites.url("news.test", "/article?case=own-out");
  await open(run, url);
  const { tabId } = await ownTab(run, url);
  assert.notEqual((await tabInfo(h, tabId)).groupId, -1);
  // Out of the group, as the person's drag leaves it (Chrome's own ungroup: the extension's code is as it ships).
  await inWorker(h, (id) => chrome.tabs.ungroup(id), tabId);
  await until(async () => (await tabInfo(h, tabId)).groupId === -1, "the tab out of the group");

  const read = await h.planque.sendStep({ op: "read", run });
  assert.equal(read.ok, true, JSON.stringify(read.error));
  assert.equal(read.page.title, "Tide tables");
  assert.equal((await rulesOf(h, tabId)).length, 3, "still fenced");
  // Off the run's sites, still refused there.
  const off = await h.planque.sendStep({ op: "open", run, url: h.sites.url("other.test", "/?case=own-out") });
  assert.equal(off.ok, false);
  assert.equal(off.error.code, "site_not_listed");
  assert.deepEqual((await runState(h, run)).tabs, [tabId]);
  assert.equal((await h.planque.sendStep({ op: "close", run })).ok, true);
});

test("the person drags a tab of theirs into the run's group: it never becomes the run's, read, fenced or closed", { skip }, async () => {
  const run = newRun("own-in");
  const url = h.sites.url("news.test", "/article?case=own-in");
  await open(run, url);
  const { tabId } = await ownTab(run, url);
  const groupId = (await tabInfo(h, tabId)).groupId;
  // The person's own tab, on a page of the run's sites, dragged into the run's group.
  const account = h.sites.url("shop.test", "/account?case=own-in");
  const theirs = await h.context.newPage();
  await theirs.goto(account);
  // What they type there is theirs alone: no result of the run's ever carries it.
  const typed = "typed-in-their-own-tab-e2e";
  await theirs.fill("#notes", typed);
  const theirId = await inWorker(h, (u) => chrome.tabs.query({}).then((ts) => (ts.find((t) => t.url === u) || {}).id), account);
  assert.ok(theirId, "the person's tab");
  await inWorker(h, ([g, id]) => chrome.tabs.group({ groupId: g, tabIds: [id] }), [groupId, theirId]);
  assert.equal((await tabInfo(h, theirId)).groupId, groupId);

  assert.deepEqual((await runState(h, run)).tabs, [tabId], "the run's tabs are as they were");
  assert.deepEqual(await rulesOf(h, theirId), [], "their tab isn't fenced");
  const read = await h.planque.sendStep({ op: "read", run });
  assert.equal(read.ok, true, JSON.stringify(read.error));
  assert.equal(read.page.title, "Tide tables");
  // An open of their tab's exact address goes to the run's own tab: theirs is never read where it is.
  const there = await open(run, account);
  assert.ok(!JSON.stringify(read).includes(typed) && !JSON.stringify(there).includes(typed), "their typing went with a result");
  assert.equal((await runState(h, run)).tab, tabId, "read in the run's own tab");
  assert.equal((await tabInfo(h, tabId)).url, account);
  assert.equal(theirs.url(), account);
  assert.equal(await theirs.inputValue("#notes"), typed, "their tab is as they left it");

  // The run's end, Stop or close, never closes it.
  assert.deepEqual(await h.fromPanel({ type: "stop", run }), {});
  // Waited for the runner to have let the stopped run's tabs go, not for a fixed time: a close that comes late still fails here.
  await until(async () => {
    const s = await runState(h, run);
    return !s || s.tabs.length === 0;
  }, "the stopped run to let its tabs go", 5000);
  assert.ok(await tabInfo(h, theirId), "their tab is still open");
  assert.equal(theirs.url(), account);
  // The same taf's next run, which closes the last run's tabs, leaves it too.
  const next = newRun("own-in-next");
  await open(next, h.sites.url("news.test", "/article?case=own-in-next"));
  // The taf's next run closes the last run's own tab as it opens: waited for until Chrome no longer has it, then theirs is checked.
  await until(async () => !(await tabInfo(h, tabId)), "the last run's own tab closed by the taf's next run", 5000);
  assert.ok(await tabInfo(h, theirId), "their tab is still open after the taf's next run");
  assert.deepEqual(await rulesOf(h, theirId), []);
  assert.ok(!(await runState(h, next)).tabs.includes(theirId));
  await h.fromPanel({ type: "stop", run: next });
  await theirs.close();
});

// ---- Two tafs ----

/** A second taf's connection: its own grant and name, its sites signed by the paired phone. */
const TWO = { taf: "Ivo", grant: "grt_e2e_two" };
async function asTwo(fields) {
  return h.planque.sendStep({ ...TWO, sites_signed: await h.planque.sitesSigned(h.planque.sites, { grant: TWO.grant }), ...fields });
}

test("two runs of two tafs at once: each reads and acts only in its own tab, never the other's", { skip }, async () => {
  const one = newRun("taf-one");
  const two = newRun("taf-two");
  const url = h.sites.url("news.test", "/hub?case=two-tafs");
  // Both open the same address at once.
  const [a, b] = await Promise.all([h.planque.sendStep({ op: "open", run: one, url }), asTwo({ op: "open", run: two, url })]);
  assert.equal(a.ok, true, JSON.stringify(a.error));
  assert.equal(b.ok, true, JSON.stringify(b.error));
  const [sa, sb] = [await runState(h, one), await runState(h, two)];
  assert.notEqual(sa.tab, sb.tab, "a tab each");
  assert.deepEqual(sa.tabs, [sa.tab]);
  assert.deepEqual(sb.tabs, [sb.tab]);
  const [ta, tb] = [await tabInfo(h, sa.tab), await tabInfo(h, sb.tab)];
  assert.notEqual(ta.groupId, tb.groupId, "a group each");

  // A read of the one is never an act's read in the other.
  const cross = await asTwo({ op: "act", run: two, read_id: a.page.read_id, act: { kind: "fill", ref: ref(a.page, "Question"), value: "from the other taf" } });
  assert.equal(cross.ok, false);
  assert.equal(cross.error.code, "page_changed");

  // Each fills its own field at once: each value lands in its own tab only.
  const [fa, fb] = await Promise.all([
    h.planque.sendStep({ op: "act", run: one, read_id: a.page.read_id, act: { kind: "fill", ref: ref(a.page, "Question"), value: "one's words" } }),
    asTwo({ op: "act", run: two, read_id: b.page.read_id, act: { kind: "fill", ref: ref(b.page, "Question"), value: "two's words" } }),
  ]);
  assert.equal(fa.ok, true, JSON.stringify(fa.error));
  assert.equal(fb.ok, true, JSON.stringify(fb.error));
  const value = (tabId) => inWorker(h, (id) => chrome.scripting.executeScript({ target: { tabId: id }, func: () => document.getElementById("q").value }).then(([r]) => r.result), tabId);
  assert.equal(await value(sa.tab), "one's words");
  assert.equal(await value(sb.tab), "two's words");
  assert.ok(!JSON.stringify(fa).includes("two's words"));
  assert.ok(!JSON.stringify(fb).includes("one's words"));

  // The one's end leaves the other's tab, group and fence as they were, and the other goes on.
  const closed = await h.planque.sendStep({ op: "close", run: one });
  assert.equal(closed.ok, true);
  const read = await asTwo({ op: "read", run: two });
  assert.equal(read.ok, true, JSON.stringify(read.error));
  assert.equal((await runState(h, two)).tab, sb.tab);
  assert.equal((await rulesOf(h, sb.tab)).length, 3);
  assert.equal((await tabInfo(h, sb.tab)).groupId, tb.groupId);
  // The one taf's next run closes its own last run's tab, never the other's.
  const again = newRun("taf-one-again");
  await open(again, h.sites.url("news.test", "/article?case=two-tafs-again"));
  assert.ok(await tabInfo(h, sb.tab), "the other taf's tab is still open");
  assert.equal((await asTwo({ op: "read", run: two })).ok, true);
  await h.fromPanel({ type: "stop", run: again });
  await h.fromPanel({ type: "stop", run: two });
});

test("a second taf of the same name, under another grant: neither closes, regroups nor reads the other's tabs", { skip }, async () => {
  const same = { taf: h.planque.taf, grant: "grt_e2e_same_name" };
  const asSame = async (fields) => h.planque.sendStep({ ...same, sites_signed: await h.planque.sitesSigned(h.planque.sites, { grant: same.grant }), ...fields });
  const first = newRun("same-name-one");
  const url = h.sites.url("news.test", "/article?case=same-name");
  await open(first, url);
  const firstTab = (await runState(h, first)).tab;
  const firstGroup = (await tabInfo(h, firstTab)).groupId;
  // The first taf's run ends; its tab is kept, in its "· done" group, for the person to see.
  assert.equal((await h.planque.sendStep({ op: "close", run: first })).ok, true);

  // The other taf of the same name starts a run: the first taf's kept tab is not its last run's.
  const second = newRun("same-name-two");
  const r = await asSame({ op: "open", run: second, url: h.sites.url("news.test", "/long?case=same-name") });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  const secondTab = (await runState(h, second)).tab;
  assert.ok(await tabInfo(h, firstTab), "the first taf's kept tab is still open");
  assert.notEqual((await tabInfo(h, secondTab)).groupId, firstGroup, "the second taf works in a group of its own");
  await h.fromPanel({ type: "stop", run: second });
});
