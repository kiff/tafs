// The content script in a real Chromium page (Playwright, which CI installs; skipped where it isn't there): what a read
// shows and masks, what an act does, and what it refuses.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { installDialogGuard } from "../src/dialogs.js";

const require = createRequire(import.meta.url);
let playwright = null;
try {
  playwright = require("playwright");
} catch {
  // CI installs Playwright for these tests: there, a missing one is a failure, never a skip.
  if (process.env.CI) throw new Error("Playwright is needed for the page tests in CI");
  playwright = null;
}

const MASK = readFileSync(new URL("../src/mask.js", import.meta.url), "utf8");
const PAGE = readFileSync(new URL("../src/page.js", import.meta.url), "utf8");

const SHOP = `<!doctype html><html><head><title>Shop · checkout key sk_live_${"a".repeat(24)}</title></head><body>
<h1>Your account</h1>
<p>Saved card 4242 4242 4242 4242. Order 1234567890123.</p>
<p>api_key=abcd1234efgh</p>
<form>
  <label for="email">Email</label><input id="email" type="email" value="ana@shop.example">
  <label for="pw">Password</label><input id="pw" type="password" value="hunter22">
  <label for="otp">Code</label><input id="otp" autocomplete="one-time-code" value="987654">
  <input type="hidden" name="csrf" value="secret-token">
  <select id="size" aria-label="Size"><option>S</option><option>M</option></select>
  <input id="news" type="checkbox" aria-label="Newsletter">
  <button type="submit" id="save">Save</button>
</form>
<a href="https://shop.example/reset?token=abc123&page=2">Reset</a>
<button id="covered" style="position:relative">Delete account</button>
<div style="position:absolute;inset:0;z-index:5;background:transparent" id="overlay-off" hidden></div>
<button id="hidden" style="display:none">Hidden</button>
<label><input type="radio" name="ship" value="std"> Standard</label>
<form id="pay"><input autocomplete="cc-number" aria-label="Card number"><button type="submit">Continue</button></form>
<div><button class="paypal-button">→</button></div>
</body></html>`;

let browser;
let ctx;

before(async () => {
  if (!playwright) return;
  browser = await playwright.chromium.launch();
  ctx = await browser.newContext();
  await ctx.route("https://shop.example/**", (route) => route.fulfill({ status: 200, contentType: "text/html; charset=utf-8", body: SHOP }));
});

after(async () => {
  if (browser) await browser.close();
});

async function open() {
  const page = await ctx.newPage();
  await page.goto("https://shop.example/account?session=abc#x");
  await page.evaluate(() => (globalThis.chrome = { runtime: { sendMessage: () => {} } }));
  await page.addScriptTag({ content: MASK });
  await page.addScriptTag({ content: PAGE });
  return page;
}

test("a read names fields, controls and links by ref, and masks every secret", { skip: !playwright }, async () => {
  const page = await open();
  const read = await page.evaluate(() => TafsPage.read());
  assert.match(read.read_id, /^rd_[0-9a-f]{24}$/);
  assert.equal(read.url, "https://shop.example/account", "no query or fragment");
  assert.equal(read.title, "Shop · checkout key [masked]");
  assert.match(read.text, /Saved card •••• 4242\./);
  assert.match(read.text, /Order 1234567890123/);
  assert.match(read.text, /api_key=\[masked\]/);
  const byLabel = Object.fromEntries(read.fields.map((f) => [f.label, f]));
  assert.deepEqual(byLabel.Email, { ref: "f1", label: "Email", kind: "email", value: "ana@shop.example" });
  assert.deepEqual(byLabel.Password, { ref: "f2", label: "Password", kind: "password", masked: true });
  assert.equal(byLabel.Code.kind, "code");
  assert.equal(byLabel.Code.value, undefined, "a one-time code is never read");
  assert.ok(!JSON.stringify(read).includes("hunter22"));
  assert.ok(!JSON.stringify(read).includes("987654"));
  assert.ok(!JSON.stringify(read).includes("secret-token"), "hidden inputs aren't read");
  assert.deepEqual(byLabel.Size.options, ["S", "M"]);
  assert.ok(read.controls.some((c) => c.label === "Save" && c.submits));
  assert.ok(!read.controls.some((c) => c.label === "Hidden"), "an element nobody can see isn't offered");
  assert.equal(read.links[0].url, "https://shop.example/reset?token=%5Bmasked%5D&page=2");
  // Kinds are planque's set, and a control that pays is said so by the page's semantics, whatever its words.
  assert.equal(read.fields.find((f) => f.label === "Standard").kind, "checkbox");
  assert.equal(read.fields.find((f) => f.label === "Card number").kind, "card");
  assert.equal(read.controls.find((c) => c.label === "Continue").places_order, true, "the submit of a form with a card field");
  assert.equal(read.controls.find((c) => c.label === "→").places_order, true, "a payment provider's button");
  assert.equal(read.controls.find((c) => c.label === "Save").places_order, undefined);
  await page.close();
});

test("an act fills, selects and ticks; it refuses secret fields, stale reads and unknown refs", { skip: !playwright }, async () => {
  const page = await open();
  let read = await page.evaluate(() => TafsPage.read());
  const ref = (label) => read.fields.find((f) => f.label === label).ref;
  assert.deepEqual(await page.evaluate(([r, id]) => TafsPage.act({ kind: "fill", ref: r, value: "bo@shop.example" }, id), [ref("Email"), read.read_id]), { ok: true });
  assert.equal(await page.inputValue("#email"), "bo@shop.example");
  assert.deepEqual(await page.evaluate(([r, id]) => TafsPage.act({ kind: "fill", ref: r, value: "x" }, id), [ref("Email"), read.read_id]), { ok: false, code: "page_changed" }, "an act needs a new read");
  read = await page.evaluate(() => TafsPage.read());
  assert.deepEqual(await page.evaluate(([r, id]) => TafsPage.act({ kind: "fill", ref: r, value: "x" }, id), [ref("Password"), read.read_id]), { ok: false, code: "secret_field" });
  assert.equal(await page.inputValue("#pw"), "hunter22");
  read = await page.evaluate(() => TafsPage.read());
  assert.deepEqual(await page.evaluate(([r, id]) => TafsPage.act({ kind: "select", ref: r, value: "M" }, id), [ref("Size"), read.read_id]), { ok: true });
  assert.equal(await page.inputValue("#size"), "M");
  read = await page.evaluate(() => TafsPage.read());
  assert.deepEqual(await page.evaluate(([r, id]) => TafsPage.act({ kind: "check", ref: r }, id), [ref("Newsletter"), read.read_id]), { ok: true });
  assert.equal(await page.isChecked("#news"), true);
  read = await page.evaluate(() => TafsPage.read());
  assert.deepEqual(await page.evaluate((id) => TafsPage.act({ kind: "click", ref: "c99" }, id), read.read_id), { ok: false, code: "ref_unknown" });
  await page.close();
});

test("a fill never submits, and a navigation in the page makes the read stale", { skip: !playwright }, async () => {
  const page = await open();
  await page.evaluate(() => document.querySelector("form").addEventListener("submit", (e) => (e.preventDefault(), (window.__submitted = true))));
  const read = await page.evaluate(() => TafsPage.read());
  await page.evaluate((id) => TafsPage.act({ kind: "fill", ref: "f1", value: "x" }, id), read.read_id);
  assert.equal(await page.evaluate(() => !!window.__submitted), false);
  const again = await page.evaluate(() => TafsPage.read());
  await page.evaluate(() => history.pushState({}, "", "/other"));
  assert.deepEqual(await page.evaluate((id) => TafsPage.act({ kind: "click", ref: "c1" }, id), again.read_id), { ok: false, code: "page_changed" });
  await page.close();
});

test("a control under another element isn't clicked", { skip: !playwright }, async () => {
  const page = await open();
  await page.evaluate(() => {
    const cover = document.createElement("div");
    cover.style.cssText = "position:fixed;inset:0;z-index:99;background:rgba(0,0,0,0)";
    document.body.append(cover);
    document.getElementById("covered").onclick = () => (window.__deleted = true);
  });
  const read = await page.evaluate(() => TafsPage.read());
  const del = read.controls.find((c) => c.label === "Delete account");
  assert.deepEqual(await page.evaluate(([r, id]) => TafsPage.act({ kind: "click", ref: r }, id), [del.ref, read.read_id]), { ok: false, code: "not_visible" });
  assert.equal(await page.evaluate(() => !!window.__deleted), false);
  await page.close();
});

test("the page's dialogs don't block: a confirm is never accepted, and the next read reports it", { skip: !playwright }, async () => {
  const page = await open();
  const token = "t".repeat(24);
  await page.evaluate(`(${installDialogGuard.toString()})(${JSON.stringify(token)})`);
  await page.evaluate((t) => TafsPage.listen(t), token);
  const answer = await page.evaluate(() => confirm("Delete everything?"));
  assert.equal(answer, false);
  const read = await page.evaluate(() => TafsPage.read());
  assert.deepEqual(read.dialog, { kind: "confirm", text: "Delete everything?" });
  assert.equal(await page.evaluate(() => prompt("Your password?")), null);
  assert.deepEqual(await page.evaluate((id) => TafsPage.act({ kind: "click", ref: "c1" }, id), read.read_id), { ok: false, code: "dialog_open" });
  await page.close();
});
