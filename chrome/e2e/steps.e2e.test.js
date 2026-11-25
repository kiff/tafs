// End to end: the real extension, paired with the fake planque, opens, reads and acts on the fixture sites (docs/chrome.md,
// "Steps", "Masking", "The guard", "Dialogs"). Every result is checked as planque checks it: signed by the extension's key,
// for this Chrome, for the step's own nonce.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, tabAt, newRun, ref, until } from "./harness.mjs";
import { PASSWORD } from "./sites.mjs";

const skip = !playwright;
let h;

before(async () => {
  if (!skip) h = await start();
});

after(async () => {
  if (h) await h.close();
});

/** A result as planque files it: the extension's signature, this Chrome, and the step's id and nonce. */
function signedFor(r, sent) {
  assert.equal(r.verified, true, "signed by the paired extension's key");
  assert.equal(r.chrome_id, h.planque.chromeId);
  if (sent) {
    assert.equal(r.id, sent.step.id);
    assert.equal(r.nonce, sent.step.nonce);
  }
}

async function send(fields) {
  const sent = await h.planque.step(fields);
  const r = await h.planque.sendRaw(sent);
  signedFor(r, sent);
  return r;
}

test("open: a page on the grant's sites opens in a tab of the run's own, and its result is signed", { skip }, async () => {
  const url = h.sites.url("news.test", "/article");
  const r = await send({ op: "open", run: newRun("open"), url });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(r.error, null);
  assert.equal(r.page.url, url);
  assert.equal(r.page.title, "Tide tables");
  const tab = await until(() => tabAt(h, url), "the run's tab");
  assert.equal(await tab.title(), "Tide tables");
  // The run's tab opened in the background, beside the owner's: their tab (the first) stays where it was.
  assert.equal(h.context.pages()[0].url(), "about:blank");
});

test("read: the page's text, fields and refs come back, and a password's value never does", { skip }, async () => {
  const run = newRun("read");
  const opened = await send({ op: "open", run, url: h.sites.url("shop.test", "/form") });
  assert.equal(opened.ok, true, JSON.stringify(opened.error));
  const r = await send({ op: "read", run });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  const page = r.page;
  assert.match(page.read_id, /^rd_[0-9a-f]{24}$/);
  assert.notEqual(page.read_id, opened.page.read_id, "each read has its own id");
  assert.match(page.text, /Delivery details/);
  const byLabel = Object.fromEntries(page.fields.map((f) => [f.label, f]));
  assert.equal(byLabel["Full name"].kind, "text");
  assert.deepEqual(byLabel.Size.options, ["Small", "Medium", "Large"]);
  assert.equal(byLabel["Send me news"].kind, "checkbox");
  assert.deepEqual(byLabel.Password, { ref: byLabel.Password.ref, label: "Password", kind: "password", masked: true });
  assert.ok(page.controls.some((c) => c.label === "Save details"));
  for (const f of page.fields) assert.match(f.ref, /^f\d+$/);
  assert.ok(!JSON.stringify(r).includes(PASSWORD), "the password is nowhere in the signed result");
  assert.ok(!JSON.stringify(opened).includes(PASSWORD), "nor in the open's read");
});

test("read: a long page's text comes in windows, each with its own read", { skip }, async () => {
  const run = newRun("long");
  const first = await send({ op: "open", run, url: h.sites.url("news.test", "/long") });
  assert.equal(first.ok, true, JSON.stringify(first.error));
  assert.equal(first.page.more, true);
  assert.equal(typeof first.page.next_offset, "number");
  assert.ok(!first.page.text.includes("The last line."));
  const next = await send({ op: "read", run, offset: first.page.next_offset });
  assert.equal(next.ok, true, JSON.stringify(next.error));
  assert.equal(next.page.offset, first.page.next_offset);
  assert.match(next.page.text, /The last line\./);
  assert.equal(next.page.more, undefined);
});

test("act: fill, select and check change the page; a fill of the password is refused and changes nothing", { skip }, async () => {
  const run = newRun("act");
  const url = h.sites.url("shop.test", "/form");
  let r = await send({ op: "open", run, url });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  const tab = await until(() => h.context.pages().filter((p) => p.url() === url).at(-1), "the form's tab");

  r = await send({ op: "act", run, read_id: r.page.read_id, act: { kind: "fill", ref: ref(r.page, "Full name"), value: "Ada Lovelace" } });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(await tab.inputValue("#name"), "Ada Lovelace");
  assert.equal(r.page.fields.find((f) => f.label === "Full name").value, "Ada Lovelace", "the page is read back after the act");

  r = await send({ op: "act", run, read_id: r.page.read_id, act: { kind: "select", ref: ref(r.page, "Size"), value: "Large" } });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(await tab.inputValue("#size"), "Large");

  r = await send({ op: "act", run, read_id: r.page.read_id, act: { kind: "check", ref: ref(r.page, "Send me news") } });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.equal(await tab.isChecked("#news"), true);

  const refused = await send({ op: "act", run, read_id: r.page.read_id, act: { kind: "fill", ref: ref(r.page, "Password"), value: "typed-by-a-taf" } });
  assert.equal(refused.ok, false);
  assert.equal(refused.error.code, "secret_field");
  assert.equal(await tab.inputValue("#pw"), PASSWORD, "the field is as the page left it");

  // An act on a read that isn't the tab's latest is refused in the tab: the page may have changed since.
  const stale = await send({ op: "act", run, read_id: "rd_000000000000000000000000", act: { kind: "fill", ref: ref(r.page, "Full name"), value: "Someone else" } });
  assert.equal(stale.ok, false);
  assert.equal(stale.error.code, "page_changed");
  assert.equal(await tab.inputValue("#name"), "Ada Lovelace");
});

test("dialogs: a page's confirm() doesn't block the run and is never accepted", { skip }, async () => {
  const run = newRun("dialog");
  const url = h.sites.url("shop.test", "/confirm");
  let r = await send({ op: "open", run, url });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  const tab = await until(() => tabAt(h, url), "the drafts tab");
  r = await send({ op: "act", run, read_id: r.page.read_id, act: { kind: "click", ref: ref(r.page, "Delete drafts", "controls") } });
  assert.equal(r.ok, true, JSON.stringify(r.error));
  assert.deepEqual(r.page.dialog, { kind: "confirm", text: "Delete every draft?" }, "the taf reads that the page asked");
  assert.equal(await tab.textContent("#out"), "refused");
  // Chromium never showed it: the harness accepts any dialog that reaches it, and none did.
  assert.deepEqual(h.dialogs, []);
});
