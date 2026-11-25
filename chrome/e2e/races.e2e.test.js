// End to end: a race in a run's first open, which made any test's first `open` fail now and then under load (docs/chrome-e2e.md,
// "Known flakes"). runner.js opens a run's tab on about:blank (`runTab`) and navigates it to the step's page (`tabs.update`) as
// soon as it is grouped, without waiting for that blank page to commit. When the tab's renderer is slow to commit it, the blank
// page commits after the navigation began and aborts it (net::ERR_ABORTED); Chrome leaves the tab on about:blank with the status
// "unloaded", `loaded()` waits its whole 30 s for "complete", and the read answers bad_url.
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
  todo: "runner.js open() navigates the run's new tab (runTab's about:blank) before that blank page has committed; a late commit aborts the navigation (net::ERR_ABORTED), the tab is left 'unloaded' on about:blank, loaded() waits LOAD_TIMEOUT_MS (30 s) for 'complete', and the open answers bad_url.",
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
