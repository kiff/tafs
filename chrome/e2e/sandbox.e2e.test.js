// End to end, a spike: Chrome's own dialog guard for the pages a run loads (docs/chrome.md, "Dialogs"). The sandbox (a response header
// Chrome adds for one tab, every sandbox permission but modals) is in place only while the run's own navigation is in flight: on
// just before the runner moves its tab (an open, an act, a back, a pop-up's first page), off at that navigation's commit or error
// and at the step's end. Each case is tried TRIES times and its count logged (`MEASURE` lines), then asserted:
//   (a) an address the person types in the run's own tab never loads sandboxed: their page's confirm as it loads reaches them;
//   (b) after the tab is given back, no page of theirs stays sandboxed: a confirm in it reaches them;
//   (c) the run's own loads drop a load-time confirm, with nothing reaching Chromium (an open, and an act's navigation).

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until, tabAt, runState, tabInfo, inWorker } from "./harness.mjs";

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

/**
 * Records, in tab `tabId`'s isolated world, every state the floating bar is shown with from now on (bar.js `TafsBar.show`): the
 * bar's shadow root is closed, so what it says is read where it's given. Nothing of the extension's code changes.
 */
function watchBar(tabId) {
  return inWorker(h, (id) => chrome.scripting.executeScript({ target: { tabId: id }, files: ["src/bar.js"] }).then(() => chrome.scripting.executeScript({
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
const groupTitle = (groupId) => inWorker(h, (g) => chrome.tabGroups.get(g).then((x) => x.title, () => null), groupId);

// The residual of the in-flight sandbox (0.2.21): the page the run itself loaded keeps its sandbox for its life, so after the run
// lets the tab go, its dialogs are still off. The bar stays in that tab and says so, with Reload, and the done group says reload;
// the person's own press reloads it, their page loads as it is, and both go.
test("(b) after Stop, the page the run left has its dialogs off; the bar says so with Reload; the person's Reload brings their real confirm back", { skip }, async () => {
  const tag = "b-notice";
  const url = confirmPage(tag);
  const run = newRun("b-notice");
  await open(run, url);
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const tabId = (await runState(h, run)).tab;
  const groupId = (await tabInfo(h, tabId)).groupId;
  assert.equal(await tab.textContent("#out"), "refused", "the run's own load dropped the page's confirm");
  await watchBar(tabId);
  await h.fromPanel({ type: "stop", run });
  const said = await until(async () => {
    const s = await barSaid(tabId);
    return s && s.notice ? s : null;
  }, "the bar's notice in the tab the run left", 8000);
  assert.match(said.words, /dialogs are off/);
  assert.equal(said.labels.reload, "Reload");
  await until(async () => /reload/.test((await groupTitle(groupId)) || ""), "the done group saying reload", 8000);
  // The page's own dialogs are still off: a confirm answers no, and nothing reaches Chromium.
  const dialogs = h.dialogs.length;
  assert.equal(await tab.evaluate(() => confirm("Still there?")), false);
  assert.equal(h.dialogs.length, dialogs);
  // The person presses Reload on the bar (a real click, at the bar's one button, its right end): their page loads as it is.
  const box = await tab.evaluate(() => {
    const r = document.querySelector("tafs-working-bar").getBoundingClientRect();
    return { x: r.right - 30, y: r.top + r.height / 2 };
  });
  await tab.mouse.click(box.x, box.y);
  await until(() => reached(tag).length === 1, "the reloaded page's confirm in Chromium", 8000);
  await until(async () => (await tab.textContent("#out").catch(() => "")) === "accepted", "the person's answer read by the page", 5000);
  await until(async () => (await barSaid(tabId)) === null || !(await tab.evaluate(() => !!document.querySelector("tafs-working-bar"))), "the notice gone", 8000);
  await until(async () => !/reload/.test((await groupTitle(groupId)) || ""), "the done group's title back", 8000);
  await tab.close();
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

// The one window the sandbox rule is on: the run's own navigation in flight. Here it's held open (`/hold`), and the person types an
// address while it is. Their navigation takes the rule off as it starts (onBeforeNavigate), racing their own request: each try
// counts whether the window was hit (the run's open still waiting), and whether their page loaded sandboxed. A page that did is
// noted, so once the tab is theirs the bar says so, with Reload: asserted for every try that loaded sandboxed.
test(`(a, in flight) an address the person types while the run's own open is held: logged, and noted when it loads sandboxed (${TRIES} tries)`, { skip }, async () => {
  let sandboxed = 0;
  let hit = 0;
  let noticed = 0;
  for (let i = 0; i < TRIES; i++) {
    const run = newRun("af" + i);
    const first = h.sites.url("news.test", "/article?case=af" + i);
    await open(run, first);
    const tab = await until(() => tabAt(h, first), "the run's tab");
    const tabId = (await runState(h, run)).tab;
    let answered = false;
    const going = h.planque.sendStep({ op: "open", run, url: h.sites.url("news.test", "/hold?ms=2500&case=af" + i) }).finally(() => (answered = true));
    await new Promise((r) => setTimeout(r, 400));
    if (!answered) hit++;
    const tag = "inflight-a" + i;
    await tab.goto(confirmPage(tag)).catch(() => {});
    await going.catch(() => {});
    const out = await tab.textContent("#out").catch(() => "");
    const loadedSandboxed = out !== "accepted" || reached(tag).length !== 1;
    // Their page is a new document: the bar's states are watched in it from here, and the run's end brings the bars up to date.
    await watchBar(tabId);
    await h.fromPanel({ type: "stop", run });
    if (loadedSandboxed) {
      sandboxed++;
      const s = await until(async () => {
        const said = await barSaid(tabId);
        return said && said.notice ? said : null;
      }, "the notice for a page of theirs that loaded sandboxed", 5000).catch(() => null);
      if (s) noticed++;
    }
    await tab.close();
  }
  console.log(`MEASURE (a, in flight) the run's open still in flight when the person typed: ${hit}/${TRIES}; their page loaded sandboxed: ${sandboxed}/${TRIES}; of those, noticed: ${noticed}`);
  assert.ok(hit > 0, "the window was never hit");
  assert.equal(noticed, sandboxed, "a page of theirs that loaded sandboxed is always noticed");
});
