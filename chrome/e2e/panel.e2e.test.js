// End to end: what the side panel shows the person when a run's step is refused because the page isn't the run's to take
// (docs/chrome.md, "The side panel"): `given_back`, `page_changed` and `no_back`, in a tab of the person's the run joined and in a
// tab of the run's own. Each refusal is one line in the run's card, in the extension's words with its code, and nothing stale is
// left: a tab given back is in none of the run's tabs, the panel no longer takes the tab in front for the run's, and the card's
// site is never the person's.

import { test, before, after, afterEach } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, runState, tabInfo } from "./harness.mjs";
import { joining } from "./joining.mjs";

const skip = !playwright;
let h;
let j;

before(async () => {
  if (skip) return;
  h = await start({ cloud: true });
  j = await joining(h);
  // The panel knows the account's tafs (tafs cloud's list), which its head matches runs to.
  await until(() => h.inPanel(`document.getElementById("tafPick").options.length > 0`), "the panel's list of tafs");
});

/** Every run a test started: whatever it asserted, none is left live for the next test's panel. */
const started = new Set();
afterEach(async () => {
  if (skip) return;
  for (const run of started) await h.fromPanel({ type: "stop", run }).catch(() => {});
  started.clear();
  await until(async () => (await cards()).length === 0, "no live run in the panel");
});

after(async () => {
  if (h) await h.close();
});

/** The run cards the panel draws now: the taf's line, its site, whether a pause or a hand-over shows, and its step lines. */
function cards() {
  return h.inPanel(`[...document.querySelectorAll("#runs article.run")].map((a) => ({
    taf: a.querySelector(".taf").textContent,
    where: a.querySelector(".where").textContent,
    paused: !a.querySelector(".paused").hidden,
    hand: !a.querySelector(".hand").hidden,
    steps: [...a.querySelectorAll(".steps li")].map((li) => ({ text: li.textContent, refused: li.className === "refused" })),
  }))`);
}

/** Whether the panel's head marks a taf at work as the one whose tab is in front. */
const hereChip = () => h.inPanel(`!!document.querySelector("#atWorkList .here")`);

/**
 * Sends `fields` as a step of `run` and checks what the person sees of its refusal `code`: the run's state gains exactly one step,
 * the refused one; the panel's one card (the only live run) ends with `line`, marked refused, and draws nothing else new.
 */
async function refusedAndShown(run, fields, code, line) {
  const before = (await runState(h, run)).steps.length;
  const r = await j.step({ run, ...fields });
  assert.equal(r.ok, false, JSON.stringify(r.page && r.page.url));
  assert.equal(r.error.code, code);
  const s = await until(async () => {
    const now = await runState(h, run);
    return now.steps.length > before ? now : null;
  }, "the refused step in the run's state");
  assert.deepEqual(s.steps.slice(before).map((x) => [x.op, x.error]), [[fields.op, code]], "one step, the refused one");
  const card = await until(async () => {
    const [c] = await cards();
    return c && c.steps.length && c.steps.at(-1).text === line ? c : null;
  }, "the panel's line: " + line);
  assert.equal((await cards()).length, 1, "one card: the run's");
  assert.equal(card.steps.at(-1).refused, true, "drawn as refused");
  assert.equal(card.steps.filter((x) => x.text === line).length >= 1, true);
  assert.equal(card.hand, false, "no Your turn left over");
  return { r, card };
}

/** The person's Stop, and the panel shows no live run any more. */
async function stop(run) {
  assert.deepEqual(await h.fromPanel({ type: "stop", run }), {});
  await until(async () => (await cards()).length === 0, "no live run in the panel");
}

// ---- A tab of the person's the run joined ----

test("joined: the person moves their tab, and the run's next read is one line, `given_back`, with the tab in none of the run's", { skip }, async () => {
  const hub = h.sites.url("news.test", "/hub?case=panel-joined-given");
  const { agentRun, tabId } = await j.sendFromPanel(hub);
  const run = newRun("panel-joined-given");
  started.add(run);
  const opened = await j.step({ op: "open", run, url: hub, agent_run: agentRun });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  await until(async () => (await cards()).length === 1, "the run's card");
  await until(hereChip, "the taf marked as the one whose tab is in front");

  // The person types another address of the run's sites in their tab: it's given back to them.
  const theirs = h.sites.url("shop.test", "/account?case=panel-joined-given");
  await j.owner.goto(theirs);
  await until(async () => (await tabInfo(h, tabId)).groupId === -1, "their tab given back");

  const { card } = await refusedAndShown(run, { op: "read", agent_run: agentRun }, "given_back", "Read the page · not done (given_back)");
  assert.ok(!(await runState(h, run)).tabs.includes(tabId), "their tab is in none of the run's tabs");
  assert.equal(await hereChip(), false, "the tab in front is no longer taken for the run's");
  assert.equal(card.where, "news.test", "the card's site is the run's, never the person's page's");
  assert.ok(!card.steps.some((x) => /shop\.test/.test(x.text)), "no line names the person's own move");
  await stop(run);
});

test("joined: an act on an old read is one line, `page_changed`, and the tab stays the run's", { skip }, async () => {
  const hub = h.sites.url("news.test", "/hub?case=panel-joined-changed");
  const { agentRun, tabId } = await j.sendFromPanel(hub);
  const run = newRun("panel-joined-changed");
  started.add(run);
  const opened = await j.step({ op: "open", run, url: hub, agent_run: agentRun });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  await refusedAndShown(
    run,
    { op: "act", agent_run: agentRun, read_id: "rd_000000000000000000000000", act: { kind: "fill", ref: ref(opened.page, "Question"), value: "x" } },
    "page_changed",
    "Filled in a field · not done (page_changed)",
  );
  assert.ok((await runState(h, run)).tabs.includes(tabId), "still the run's: nothing was given back");
  assert.equal(await hereChip(), true);
  await stop(run);
});

test("joined: the taf's back is one line, `no_back`, and nothing moves", { skip }, async () => {
  const hub = h.sites.url("news.test", "/hub?case=panel-joined-noback");
  const { agentRun, tabId } = await j.sendFromPanel(hub);
  const run = newRun("panel-joined-noback");
  started.add(run);
  const opened = await j.step({ op: "open", run, url: hub, agent_run: agentRun });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  await refusedAndShown(run, { op: "act", agent_run: agentRun, read_id: opened.page.read_id, act: { kind: "back" } }, "no_back", "Went back · not done (no_back)");
  assert.equal((await tabInfo(h, tabId)).url, hub, "the person's tab is where it was");
  assert.ok((await runState(h, run)).tabs.includes(tabId));
  await stop(run);
});

// ---- A tab of the run's own ----

/** A run of its own (no panel send): its first open, in a tab of its own, and that tab as Playwright drives it. */
async function ownRun(name, url) {
  const run = newRun(name);
  started.add(run);
  const opened = await j.step({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const page = await until(() => tabAt(h, url), "the run's own tab");
  const tabId = (await runState(h, run)).tab;
  await until(async () => (await cards()).length === 1, "the run's card");
  return { run, opened, page, tabId };
}

test("own tab: the person's address there gives it to them; the next read is one line, `given_back`, and the next open takes a tab of its own", { skip }, async () => {
  const hub = h.sites.url("news.test", "/hub?case=panel-own-given");
  const { run, page, tabId } = await ownRun("panel-own-given", hub);
  await page.bringToFront();
  await until(hereChip, "the taf marked as the one whose tab is in front");

  const theirs = h.sites.url("shop.test", "/account?case=panel-own-given");
  await page.goto(theirs);
  await until(async () => !(await runState(h, run)).tabs.includes(tabId), "the tab given to the person");

  const { card } = await refusedAndShown(run, { op: "read" }, "given_back", "Read the page · not done (given_back)");
  assert.equal(await hereChip(), false, "the tab in front is the person's now, not taken for the run's");
  assert.equal(card.where, "news.test");
  assert.ok(!card.steps.some((x) => /shop\.test/.test(x.text)), "no line names the person's own move");

  // The run goes on in a tab of its own: its open is one plain line, and the person's tab stays where they took it.
  const again = await j.step({ op: "open", run, url: hub });
  assert.equal(again.ok, true, JSON.stringify(again.error));
  const s = await runState(h, run);
  assert.notEqual(s.tab, tabId);
  await until(async () => ((await cards())[0] || { steps: [] }).steps.at(-1)?.text === "Opened news.test", "the open's line");
  assert.equal(page.url(), theirs);
  await stop(run);
});

test("own tab: an act on an old read is one line, `page_changed`, and the tab stays the run's", { skip }, async () => {
  const hub = h.sites.url("news.test", "/hub?case=panel-own-changed");
  const { run, opened, tabId } = await ownRun("panel-own-changed", hub);
  await refusedAndShown(
    run,
    { op: "act", read_id: "rd_000000000000000000000000", act: { kind: "fill", ref: ref(opened.page, "Question"), value: "x" } },
    "page_changed",
    "Filled in a field · not done (page_changed)",
  );
  assert.ok((await runState(h, run)).tabs.includes(tabId));
  await stop(run);
});

test("own tab: the taf's back with no page before is one line, `no_back`, and nothing moves", { skip }, async () => {
  const hub = h.sites.url("news.test", "/hub?case=panel-own-noback");
  const { run, opened, tabId } = await ownRun("panel-own-noback", hub);
  await refusedAndShown(run, { op: "act", read_id: opened.page.read_id, act: { kind: "back" } }, "no_back", "Went back · not done (no_back)");
  assert.equal((await tabInfo(h, tabId)).url, hub);
  assert.ok((await runState(h, run)).tabs.includes(tabId));
  await stop(run);
});

// ---- The person's own move off the run's sites ----

test("joined: the person's own address off the run's sites gives their tab back, and the run's card never says the taf is there", { skip }, async () => {
  const hub = h.sites.url("news.test", "/hub?case=panel-joined-off");
  const { agentRun, tabId } = await j.sendFromPanel(hub);
  const run = newRun("panel-joined-off");
  started.add(run);
  const opened = await j.step({ op: "open", run, url: hub, agent_run: agentRun });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  await j.owner.goto(h.sites.url("other.test", "/?case=panel-joined-off")).catch(() => {});
  await until(async () => (await tabInfo(h, tabId)).groupId === -1, "their tab given back");
  const { card } = await refusedAndShown(run, { op: "read", agent_run: agentRun }, "given_back", "Read the page · not done (given_back)");
  assert.equal(card.where, "news.test", "the card's site is the run's, never the person's: " + JSON.stringify(card));
  assert.ok(!card.steps.some((x) => /other\.test/.test(x.text)), "no line takes the person's own move for the taf's: " + JSON.stringify(card.steps));
});

test("own tab: the person's own address off the run's sites gives the tab to them, and the run's card never says the taf is there", { skip }, async () => {
  const hub = h.sites.url("news.test", "/hub?case=panel-own-off");
  const { run, page, tabId } = await ownRun("panel-own-off", hub);
  await page.goto(h.sites.url("other.test", "/?case=panel-own-off")).catch(() => {});
  await until(async () => !(await runState(h, run)).tabs.includes(tabId), "the tab given to the person");
  const { card } = await refusedAndShown(run, { op: "read" }, "given_back", "Read the page · not done (given_back)");
  assert.equal(card.where, "news.test", "the card's site is the run's, never the person's: " + JSON.stringify(card));
  assert.ok(!card.steps.some((x) => /other\.test/.test(x.text)), "no line takes the person's own move for the taf's: " + JSON.stringify(card.steps));
});
