// End to end: what the real extension refuses, whatever planque sends (docs/chrome.md, "Steps", "What the extension refuses").
// A step that isn't planque's, isn't for this Chrome, is late or comes twice is answered `bad_step` when it carries an id, and
// nothing happens in the browser; a run's sites are the phone's signed list, and a page that leads off them is fenced.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until } from "./harness.mjs";
import { keyPair } from "./planque.mjs";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start();
});

after(async () => {
  if (h) await h.close();
});

/** The tabs Playwright sees now, by URL. */
const urls = () => h.context.pages().map((p) => p.url());

/** A `bad_step` answer to `sent`, signed by the extension. */
function badStep(r, sent, detail) {
  assert.equal(r.verified, true, "even a refusal is signed by the extension");
  assert.equal(r.id, sent.step.id);
  assert.equal(r.ok, false);
  assert.equal(r.page, null);
  assert.equal(r.error.code, "bad_step");
  if (detail) assert.equal(r.error.detail, detail);
}

/** A URL no other test opens, so a tab or a request for it is this test's alone. */
const unique = (path) => h.sites.url("news.test", "/article?case=" + path);
/** Whether the fixture sites were asked for that URL. */
const requested = (path) => h.sites.hits.includes("news.test /article?case=" + path);

test("a step signed by another key than the pinned one is refused, and nothing opens", { skip }, async () => {
  const url = unique("forged");
  const forger = await keyPair();
  const sent = await h.planque.step({ op: "open", run: newRun("forged"), url }, { key: forger.privateKey });
  badStep(await h.planque.sendRaw(sent), sent, "not signed by planque");
  assert.ok(!urls().includes(url));
  assert.ok(!requested("forged"));
});

test("a step changed after planque signed it is refused", { skip }, async () => {
  const url = unique("changed");
  const sent = await h.planque.step({ op: "open", run: newRun("changed"), url: unique("signed") });
  sent.step.url = url; // the signature was over the other URL
  badStep(await h.planque.sendRaw(sent), sent, "not signed by planque");
  assert.ok(!urls().includes(url));
});

test("a step for another Chrome is refused", { skip }, async () => {
  const url = unique("other-chrome");
  const sent = await h.planque.step({ op: "open", run: newRun("other"), url, chrome_id: "chr_someone_else" });
  badStep(await h.planque.sendRaw(sent), sent, "not for this Chrome");
  assert.ok(!urls().includes(url));
  assert.ok(!requested("other-chrome"));
});

test("an expired step is refused, and one too far ahead too", { skip }, async () => {
  const late = await h.planque.step({ op: "open", run: newRun("late"), url: unique("late"), exp: Date.now() - 120_000 });
  badStep(await h.planque.sendRaw(late), late, "expired");
  const ahead = await h.planque.step({ op: "open", run: newRun("ahead"), url: unique("ahead"), exp: Date.now() + 10 * 60_000 });
  badStep(await h.planque.sendRaw(ahead), ahead, "expiry too far ahead");
  assert.ok(!urls().includes(unique("late")));
  assert.ok(!urls().includes(unique("ahead")));
  assert.ok(!requested("late") && !requested("ahead"));
});

test("a step sent twice runs once: the replay is refused and opens nothing more", { skip }, async () => {
  const url = unique("replay");
  const sent = await h.planque.step({ op: "open", run: newRun("replay"), url });
  const first = await h.planque.sendRaw(sent);
  assert.equal(first.ok, true, JSON.stringify(first.error));
  const tabs = urls().filter((u) => u === url).length;
  assert.equal(tabs, 1);
  badStep(await h.planque.sendRaw(sent), sent, "seen before");
  // The same nonce under a new id is the same step too.
  const again = await h.planque.step({ ...sent.step, id: "op_another_id" });
  badStep(await h.planque.sendRaw(again), again, "seen before");
  assert.equal(urls().filter((u) => u === url).length, tabs);
  assert.equal(h.sites.hits.filter((x) => x === "news.test /article?case=replay").length, 1, "the page was loaded once");
});

test("a message that isn't a signed step with an id gets no answer", { skip }, async () => {
  const before = h.planque.messages.length;
  h.planque.send({ type: "step", step: { op: "open", url: unique("unsigned") } }); // no id, no signature
  // A step after it is answered; nothing came for the unsigned one in between, nor after.
  const r = await h.planque.sendStep({ op: "open", run: newRun("after-unsigned"), url: unique("after-unsigned") });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  await new Promise((resolve) => setTimeout(resolve, 300));
  const results = h.planque.messages.slice(before).filter((m) => m.type === "result");
  assert.deepEqual(results.map((m) => m.id), [r.id]);
  assert.ok(!urls().includes(unique("unsigned")));
});

test("a run's sites are the phone's: a site the phone didn't sign is refused before anything opens", { skip }, async () => {
  const url = h.sites.url("other.test", "/");
  // planque lists other.test, but the phone signed only the grant's sites.
  const widened = await h.planque.step({ op: "open", run: newRun("widened"), url, sites: ["shop.test", "news.test", "other.test"] });
  const r = await h.planque.sendRaw(widened);
  assert.equal(r.verified, true);
  assert.equal(r.error.code, "bad_step");
  assert.equal(r.error.detail, "the sites aren't the phone's");
  // planque signed the sites itself, in the phone's place.
  const byPlanque = await h.planque.step({ op: "open", run: newRun("planque-signed"), url: h.sites.url("shop.test", "/form?case=planque-signed") }, { phoneKey: h.planque.planqueKeys.privateKey });
  const r2 = await h.planque.sendRaw(byPlanque);
  assert.equal(r2.error.code, "bad_step");
  assert.equal(r2.error.detail, "the sites aren't the phone's");
  assert.ok(!urls().some((u) => u.includes("other.test") || u.includes("planque-signed")));
});

test("an open of a site outside the run's sites is refused, and the site is never reached", { skip }, async () => {
  const run = newRun("outside");
  const r = await h.planque.sendStep({ op: "open", run, url: h.sites.url("other.test", "/") });
  assert.equal(r.verified, true);
  assert.equal(r.ok, false);
  assert.equal(r.error.code, "site_not_listed");
  for (const [url, code] of [["http://shop.test/form", "bad_url"], ["https://127.0.0.1/", "address_private"], ["https://printer.local/", "address_private"]]) {
    const x = await h.planque.sendStep({ op: "open", run: newRun("outside"), url });
    assert.equal(x.error.code, code, url);
  }
  assert.ok(!h.sites.reached("other.test"));
  assert.ok(!urls().some((u) => u.includes("other.test")));
});

test("a link off the run's sites is fenced in the tab: clicking it never reaches the other site", { skip }, async () => {
  const run = newRun("fence");
  const url = h.sites.url("shop.test", "/links");
  const opened = await h.planque.sendStep({ op: "open", run, url });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const off = opened.page.links.find((l) => l.label === "Somewhere else");
  assert.ok(off, "the link is offered as a link");
  const r = await h.planque.sendStep({ op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Somewhere else", "links") } });
  assert.equal(r.verified, true);
  // The click ran; the navigation it started was stopped before it was sent, so the read back finds no page of the run's.
  assert.equal(r.ok, false);
  assert.ok(["bad_url", "site_not_listed"].includes(r.error.code), JSON.stringify(r.error));
  await until(() => !urls().some((u) => u.includes("other.test")), "no tab on other.test");
  assert.ok(!h.sites.reached("other.test"), "the request never left the browser");
});
