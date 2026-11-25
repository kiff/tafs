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

const skip = !playwright;
const TRIES = Number(process.env.SANDBOX_TRIES || 20);
let h;

before(async () => {
  if (!skip) h = await start();
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

// Logged, not asserted: the one window the sandbox rule is on, the run's own navigation in flight. The person's address typed in the
// run's tab while the run's open is still loading takes the sandbox off as their navigation starts (onBeforeNavigate), racing
// their request; this counts how often their page still loads sandboxed.
test(`(a, in flight) an address the person types while the run's own open is still loading: logged (${TRIES} tries)`, { skip }, async () => {
  let sandboxed = 0;
  let tried = 0;
  for (let i = 0; i < TRIES; i++) {
    const run = newRun("af" + i);
    // A first open gives the run its tab; the second is the one in flight.
    const first = h.sites.url("news.test", "/article?case=af" + i);
    await open(run, first);
    const tab = await until(() => tabAt(h, first), "the run's tab");
    const going = h.planque.sendStep({ op: "open", run, url: h.sites.url("news.test", "/long?case=af" + i) });
    await until(() => tab.url().includes("/long"), "the run's navigation started", 5000).catch(() => {});
    const tag = "inflight-a" + i;
    await tab.goto(confirmPage(tag)).catch(() => {});
    await going.catch(() => {});
    if (!tab.url().includes(tag)) {
      await h.fromPanel({ type: "stop", run });
      await tab.close();
      continue;
    }
    tried++;
    if ((await tab.textContent("#out").catch(() => "")) !== "accepted" || reached(tag).length !== 1) sandboxed++;
    await h.fromPanel({ type: "stop", run });
    await tab.close();
  }
  console.log(`MEASURE (a, in flight) typed address during the run's open loaded sandboxed: ${sandboxed}/${tried} (of ${TRIES} tries, ${TRIES - tried} where the person's page didn't stay)`);
});
