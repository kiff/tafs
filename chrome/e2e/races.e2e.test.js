// End to end: a race in a run's first open, which made any test's first `open` fail now and then under load, until the run's new
// tab waited for its blank page (`Runner.blankReady`, docs/chrome.md). runner.js opened a run's tab on about:blank (`runTab`) and
// navigated it to the step's page as soon as it was grouped, without waiting for that blank page to commit. When the tab's renderer
// was slow to commit it, the blank page committed after the navigation began and aborted it (net::ERR_ABORTED); Chrome left the tab
// on about:blank with the status "unloaded", `loaded()` waited its whole 30 s for "complete", and the read answered bad_url.
//
// This file makes the renderer slow on purpose: Chromium may run one renderer process for every page (`--renderer-process-limit=1`,
// no spare renderer), and a page of the person's runs a script for two seconds as the step arrives, so the run's new tab, which
// shares that process, commits its blank page late. Without those, the same open succeeds at once.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun } from "./harness.mjs";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start({ args: ["--renderer-process-limit=1", "--disable-features=SpareRendererForSitePerProcess"] });
});

after(async () => {
  if (h) await h.close();
});

test("a run's first open succeeds even when its new tab is slow to show its blank page", {
  skip,
}, async () => {
  const person = h.context.pages()[0];
  await person.goto(h.sites.url("news.test", "/article?case=busy"));
  const url = h.sites.url("news.test", "/article?case=slow-tab");
  const answer = h.planque.sendStep({ op: "open", run: newRun("slow-tab"), url });
  // The person's page is busy for two seconds as the step arrives (no await: its renderer is held while the run's tab opens).
  person.evaluate(() => {
    const end = Date.now() + 2000;
    while (Date.now() < end);
  }).catch(() => {});
  const r = await answer;
  assert.equal(r.verified, true);
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.page.url, h.sites.url("news.test", "/article"));
});
