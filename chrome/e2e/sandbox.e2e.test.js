// End to end, a spike: Chrome's own dialog guard for the pages a run loads (docs/chrome.md, "Dialogs"). The sandbox (a response header
// Chrome adds for one tab, every sandbox permission but modals) is in place only while the run's own navigation is in flight: on
// just before the runner moves its tab (an open, an act, a back, a pop-up's first page), off at that navigation's commit or error
// and at the step's end. Each case is tried TRIES times and its count logged (`MEASURE` lines), then asserted:
//   (a) an address the person types in the run's own tab never loads sandboxed: their page's confirm as it loads reaches them;
//   (b) after the tab is given back, no page of theirs stays sandboxed: a confirm in it reaches them;
//   (c) the run's own loads drop a load-time confirm, with nothing reaching Chromium (an open, and an act's navigation).

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, runState } from "./harness.mjs";
import { joining } from "./joining.mjs";

const skip = !playwright;
const TRIES = Number(process.env.SANDBOX_TRIES || 20);
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

const confirmPage = (tag) => h.sites.url("shop.test", "/at-load-confirm?case=" + tag);
/** The dialogs that reached Chromium from a page whose address carries `tag`. */
const reached = (tag) => h.dialogs.filter((d) => d.url.includes(tag) || d.url.includes(encodeURIComponent(tag)));

async function open(run, url) {
  const r = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  return r;
}

function measured(name, bad) {
  console.log(`MEASURE ${name}: ${bad}/${TRIES}`);
}

test(`(a) an address the person types in the run's own tab never loads sandboxed (${TRIES} tries)`, { skip }, async () => {
  let sandboxed = 0;
  for (let i = 0; i < TRIES; i++) {
    const run = newRun("a" + i);
    const url = h.sites.url("news.test", "/article?case=a" + i);
    await open(run, url);
    const tab = await until(() => tabAt(h, url), "the run's tab");
    const tag = "typed-a" + i;
    await tab.goto(confirmPage(tag));
    const out = await tab.textContent("#out");
    if (out !== "accepted" || reached(tag).length !== 1) sandboxed++;
    await h.fromPanel({ type: "stop", run });
    await tab.close();
  }
  measured("(a) typed address loaded sandboxed", sandboxed);
  assert.equal(sandboxed, 0);
});

test(`(b) after the run lets its tab go, no page of the person's stays sandboxed (${TRIES} tries)`, {
  skip,
  todo: "A document keeps the sandbox it loaded with for its whole life: the page the run itself loaded (sandboxed, as it must be for its load-time dialogs) is still sandboxed after the run lets its tab go, and a confirm there answers false with no dialog until the page is loaded again. Taking the rule off can't reach it; only a new load can (the person's own next navigation always loads unsandboxed, as (a) shows).",
}, async () => {
  let sandboxed = 0;
  for (let i = 0; i < TRIES; i++) {
    const run = newRun("b" + i);
    const url = h.sites.url("news.test", "/article?case=b" + i);
    await open(run, url);
    const tab = await until(() => tabAt(h, url), "the run's tab");
    await h.fromPanel({ type: "stop", run });
    // The tab is the person's now: a confirm in the page the run left reaches them (the harness answers it yes).
    const before = h.dialogs.length;
    const answer = await tab.evaluate(() => confirm("Still there?"));
    if (answer !== true || h.dialogs.length !== before + 1) sandboxed++;
    await tab.close();
  }
  measured("(b) page left by the run still sandboxed after the run let it go", sandboxed);
  assert.equal(sandboxed, 0);
});

test(`(c) the run's own loads drop a load-time confirm, nothing reaching Chromium: its open and its act (${TRIES} tries each)`, { skip }, async () => {
  let openLeaks = 0;
  let actLeaks = 0;
  for (let i = 0; i < TRIES; i++) {
    const run = newRun("c" + i);
    const tag = "open-c" + i;
    const url = confirmPage(tag);
    await open(run, url);
    const tab = await until(() => tabAt(h, url), "the run's tab");
    if ((await tab.textContent("#out")) !== "refused" || reached(tag).length) openLeaks++;
    // An act's navigation: the taf clicks a link to a page that asks as it loads.
    const linkTag = "act-c" + i;
    const hub = h.sites.url("shop.test", "/link-to?to=" + encodeURIComponent(confirmPage(linkTag)));
    const opened = await open(run, hub);
    const clicked = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Go there", "links") } });
    assert.equal(clicked.ok, true, JSON.stringify(clicked.error));
    const tabId = (await runState(h, run)).tab;
    const actTab = await until(() => h.context.pages().find((p) => p.url().includes(linkTag)), "the act's page");
    if ((await actTab.textContent("#out")) !== "refused" || reached(linkTag).length) actLeaks++;
    void tabId;
    await h.fromPanel({ type: "stop", run });
    await tab.close().catch(() => {});
  }
  measured("(c) run's open whose load-time confirm reached Chromium", openLeaks);
  measured("(c) run's act whose load-time confirm reached Chromium", actLeaks);
  assert.equal(openLeaks, 0);
  assert.equal(actLeaks, 0);
});

// Logged, and asserted only that it ran: the one window the sandbox rule is on, the run's own navigation in flight. The run opens a
// page whose answer is held (`/slow`: its headers not yet sent), and while it's held the person types an address in that tab. Their
// navigation takes the sandbox off as it starts (onBeforeNavigate), racing their own request; this counts how often their page
// still loads sandboxed.
test(`(a, in flight) an address the person types while the run's own open is held in flight: logged (${TRIES} tries)`, { skip }, async () => {
  let sandboxed = 0;
  let tried = 0;
  for (let i = 0; i < TRIES; i++) {
    const run = newRun("af" + i);
    const first = h.sites.url("news.test", "/article?case=af" + i);
    await open(run, first);
    const tab = await until(() => tabAt(h, first), "the run's tab");
    const slow = h.sites.url("news.test", "/slow?ms=2500&case=af" + i);
    const going = h.planque.sendStep({ op: "open", run, url: slow });
    // The run's request is out and held: its navigation is in flight, the sandbox rule on.
    await until(() => h.sites.hits.some((x) => x.includes("/slow?") && x.includes("case=af" + i + "")), "the run's held request", 5000);
    const tag = "inflight-a" + i;
    await tab.goto(confirmPage(tag)).catch(() => {});
    await going.catch(() => {});
    if (tab.url().includes(tag)) {
      tried++;
      if ((await tab.textContent("#out").catch(() => "")) !== "accepted" || reached(tag).length !== 1) sandboxed++;
    }
    await h.fromPanel({ type: "stop", run });
    await tab.close();
  }
  console.log(`MEASURE (a, in flight) typed address while the run's open was held loaded sandboxed: ${sandboxed}/${tried} (of ${TRIES} tries)`);
  assert.ok(tried > 0, "no try reached the person's page: the window wasn't measured");
});

// ---- The reviewers' cases ----

test("a PDF the run opens in a tab of its own loads under the sandbox and Chrome still shows it: the open answers as it does with no sandbox", { skip }, async () => {
  // The same PDF in a tab of the person's, with no run: what Chrome shows there.
  const plain = await h.context.newPage();
  const plainURL = h.sites.url("shop.test", "/invoice.pdf?case=person");
  await plain.goto(plainURL).catch(() => {});
  const plainType = await plain.evaluate(() => document.contentType).catch(() => "unreadable");
  await plain.close();
  const run = newRun("pdf");
  const url = h.sites.url("shop.test", "/invoice.pdf?case=run");
  const r = await h.planque.sendStep({ op: "open", run, url });
  const tab = await until(() => tabAt(h, url), "the run's tab on the PDF", 10_000);
  const runType = await tab.evaluate(() => document.contentType).catch(() => "unreadable");
  console.log(`MEASURE PDF: open ${r.ok ? "ok" : r.error.code}; the person's tab shows ${plainType}, the run's ${runType}`);
  assert.equal(runType, plainType, "the run's tab doesn't show the PDF as Chrome does with no sandbox");
  assert.ok(h.sites.hits.some((x) => x.includes("/invoice.pdf?case=run")), "the PDF was asked for");
  await h.fromPanel({ type: "stop", run });
});

test(`a navigation the page starts after the taf's act has returned loads with no sandbox: its load-time confirm reaches Chromium, and the tab is the person's (${TRIES} tries, logged)`, { skip }, async () => {
  let reachedChromium = 0;
  for (let i = 0; i < TRIES; i++) {
    const run = newRun("late" + i);
    const tag = "late-" + i;
    const url = h.sites.url("shop.test", "/late-move?ms=2500&to=" + encodeURIComponent(confirmPage(tag)) + "&case=" + tag);
    const opened = await open(run, url);
    const clicked = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Send later", "controls") } });
    assert.equal(clicked.ok, true, JSON.stringify(clicked.error));
    // The page moves 2.5 s after the click; its next page asks as it loads. Wait for that page's answer, whichever way it went.
    await until(async () => {
      const p = h.context.pages().find((x) => x.url().includes(tag) && x.url().includes("at-load-confirm"));
      return p && /accepted|refused/.test((await p.textContent("#out").catch(() => "")) || "");
    }, "the page's late move, loaded", 10_000);
    if (reached(tag).length) reachedChromium++;
    await h.fromPanel({ type: "stop", run });
  }
  console.log(`MEASURE late navigation after the act returned: load-time confirm reached Chromium ${reachedChromium}/${TRIES}`);
});

test(`a run's act that moves the person's joined tab never sandboxes it: the next page's confirm as it loads reaches the person (${TRIES} tries)`, { skip }, async () => {
  let sandboxed = 0;
  for (let i = 0; i < TRIES; i++) {
    const tag = "joined-" + i;
    const hub = h.sites.url("shop.test", "/link-to?to=" + encodeURIComponent(confirmPage(tag)) + "&case=" + tag);
    const { agentRun } = await j.sendFromPanel(hub);
    const run = newRun("joined-sb" + i);
    const opened = await j.step({ op: "open", run, url: hub, agent_run: agentRun });
    assert.equal(opened.ok, true, JSON.stringify(opened.error));
    await j.step({ op: "act", run, agent_run: agentRun, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Go there", "links") } });
    await until(async () => /accepted|refused/.test((await j.owner.textContent("#out").catch(() => "")) || ""), "the joined tab's next page, loaded", 10_000);
    if ((await j.owner.textContent("#out")) !== "accepted" || reached(tag).length !== 1) sandboxed++;
    await h.fromPanel({ type: "stop", run });
  }
  console.log(`MEASURE joined tab moved by the run's act: its next page loaded sandboxed ${sandboxed}/${TRIES}`);
  assert.equal(sandboxed, 0);
});
