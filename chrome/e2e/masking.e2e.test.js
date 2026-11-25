// End to end: what a read never carries, in frames and across Chrome's worlds (docs/chrome.md, "Masking", "A run's tabs get two
// scripts"). The content script reads the run's tab's top document only (page.js, injected without `allFrames`): a frame's
// fields and words, from the run's own site or another of its sites, are never read, so a password inside one is nowhere in a
// result. page.js and mask.js run in Chrome's isolated world: the page's own script sees none of their globals, and a read
// leaves no mark in the page's DOM for it to find.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, until, tabAt } from "./harness.mjs";
import { CROSS_FRAME_PASSWORD, FRAME_PASSWORD, FRAME_WORDS, PASSWORD } from "./sites.mjs";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start();
});

after(async () => {
  if (h) await h.close();
});

/** Nothing secret, nor anything only a frame holds, anywhere in a signed result. */
function nothingOfTheFrames(r) {
  const all = JSON.stringify(r);
  for (const secret of [FRAME_PASSWORD, CROSS_FRAME_PASSWORD, PASSWORD]) assert.ok(!all.includes(secret), "a frame's password leaked: " + secret);
  assert.ok(!all.includes(FRAME_WORDS), "a frame's words were read");
}

test("a password in a frame of the same site, or of another of the run's sites, is in no read, nor are the frames' words", { skip }, async () => {
  const run = newRun("frames");
  const url = h.sites.url("shop.test", "/frames");
  const opened = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(opened.verified, true);
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  // Both frames loaded, with their passwords in them: there was something to leak.
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const frames = await until(() => {
    const f = tab.frames().filter((x) => x !== tab.mainFrame());
    return f.length === 2 ? f : null;
  }, "both frames");
  const values = await Promise.all(frames.map((f) => f.inputValue("#secret")));
  assert.deepEqual(values.sort(), [CROSS_FRAME_PASSWORD, FRAME_PASSWORD].sort());
  // The read is the top document's: its words, and none of the frames' fields.
  const r = await h.planque.sendStep({ op: "read", run });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.match(r.page.text, /The page around the frames\./);
  assert.deepEqual(r.page.fields, [], "a frame's fields aren't the page's");
  assert.equal(r.page.payment_frame, undefined);
  nothingOfTheFrames(r);
  nothingOfTheFrames(opened);
});

test("the page's own script can't see the extension's isolated world: no global of page.js or mask.js, no mark in the DOM", { skip }, async () => {
  const run = newRun("world");
  const url = h.sites.url("shop.test", "/form?case=world");
  const opened = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const tab = await until(() => tabAt(h, url), "the run's tab");
  const before = await tab.evaluate(() => document.documentElement.outerHTML);
  const read = await h.planque.sendStep({ op: "read", run });
  assert.equal(read.ok, true, JSON.stringify(read.error));
  // Playwright evaluates in the page's main world, where the page's own script runs.
  const seen = await tab.evaluate(() => ({
    page: typeof globalThis.TafsPage,
    mask: typeof globalThis.TafsMask,
    bar: typeof globalThis.TafsBar,
    // Every name on the page's window that says tafs: only the dialog guard's own mark (dialogs.js runs in this world, by design).
    names: Object.getOwnPropertyNames(window).filter((n) => /tafs/i.test(n)),
    guard: window.__tafsDialogGuard,
    // An extension API the page could call: none of the extension's messages reach the page's world.
    runtime: typeof (globalThis.chrome && chrome.runtime && chrome.runtime.sendMessage),
    // What the read named: refs live in page.js's own map, never on the elements.
    marked: [...document.querySelectorAll("*")].flatMap((el) => [...el.attributes].map((a) => a.name)).filter((n) => /tafs/i.test(n)),
  }));
  assert.deepEqual(seen, { page: "undefined", mask: "undefined", bar: "undefined", names: ["__tafsDialogGuard"], guard: true, runtime: "undefined", marked: [] });
  assert.equal(await tab.evaluate(() => document.documentElement.outerHTML), before, "the read left the page's DOM as it was");
  // The password, which the page itself can read, never left the extension's read.
  assert.equal(await tab.inputValue("#pw"), PASSWORD);
  assert.ok(!JSON.stringify(read).includes(PASSWORD));
});
