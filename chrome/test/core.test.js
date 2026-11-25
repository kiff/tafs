import { test } from "node:test";
import assert from "node:assert/strict";
import { canonical } from "../src/jcs.js";
import { LABEL, sign, verify, newKeyPair, publicSPKI, b64u, fromB64u, derToRaw, signingBytes } from "../src/crypto.js";
import { refuseURL, bareURL, sensitive, siteOK, asideRefusal, mailHost, NEVER_OPENED } from "../src/sites.js";
import { readForCoach } from "../src/asideread.js";
import { signedSitesOf } from "../src/protocol.js";
import { readConnections } from "../src/relay.js";
import { refuse, PAY_WORDS, MONEY_OUT, KINDS } from "../src/guard.js";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { parseCode } from "../src/pairing.js";
import "../src/mask.js";

const { maskText, maskURL } = globalThis.TafsMask;

test("canonical JSON sorts keys, drops undefined, and writes numbers as ECMAScript does", () => {
  assert.equal(canonical({ b: 1, a: [true, null, "x"], c: undefined }), '{"a":[true,null,"x"],"b":1}');
  assert.equal(canonical({ "é": 1, e: 2, Z: 3 }), '{"Z":3,"e":2,"é":1}');
  assert.equal(canonical(1e21), "1e+21");
  assert.equal(canonical(0.1), "0.1");
  assert.throws(() => canonical(NaN));
});

test("a signature holds only for its label and its body", async () => {
  const k = await newKeyPair();
  const pub = b64u(await publicSPKI(k.publicKey));
  const body = { id: "op_1", n: 2 };
  const sig = await sign(k.privateKey, LABEL.step, body);
  assert.equal(await verify(pub, LABEL.step, body, sig), true);
  assert.equal(await verify(pub, LABEL.result, body, sig), false, "another label");
  assert.equal(await verify(pub, LABEL.step, { id: "op_1", n: 3 }, sig), false, "another body");
  assert.equal(await verify(pub, LABEL.step, body, "not-a-sig"), false);
  const other = await newKeyPair();
  assert.equal(await verify(b64u(await publicSPKI(other.publicKey)), LABEL.step, body, sig), false, "another key");
});

test("a phone's DER signature is read as raw r‖s", async () => {
  const k = await newKeyPair();
  const pub = b64u(await publicSPKI(k.publicKey));
  const raw = fromB64u(await sign(k.privateKey, LABEL.pair, { a: 1 }));
  const der = toDER(raw);
  assert.deepEqual(derToRaw(der), raw);
  assert.equal(await verify(pub, LABEL.pair, { a: 1 }, b64u(der)), true);
  assert.equal(derToRaw(new Uint8Array([1, 2, 3])), null);
});

function toDER(raw) {
  const int = (b) => {
    let i = 0;
    while (i < b.length - 1 && b[i] === 0) i++;
    let v = b.slice(i);
    if (v[0] & 0x80) v = Uint8Array.of(0, ...v);
    return Uint8Array.of(2, v.length, ...v);
  };
  const r = int(raw.slice(0, 32));
  const s = int(raw.slice(32));
  return Uint8Array.of(0x30, r.length + s.length, ...r, ...s);
}

test("the signed bytes are the label, a zero byte, then JCS", () => {
  assert.deepEqual([...signingBytes("L", { b: 1, a: 2 })], [...new TextEncoder().encode('L\0{"a":2,"b":1}')]);
});

test("a run opens only https pages on its sites, never an address, a local name or a credential URL", () => {
  const sites = ["shop.example"];
  assert.equal(refuseURL("https://shop.example/cart", sites), null);
  assert.equal(refuseURL("https://www.shop.example/", sites), null, "a subdomain");
  assert.equal(refuseURL("https://evilshop.example/", sites).code, "site_not_listed", "a look-alike suffix");
  assert.equal(refuseURL("https://shop.example.evil.example/", sites).code, "site_not_listed");
  assert.equal(refuseURL("http://shop.example/", sites).code, "bad_url");
  assert.equal(refuseURL("https://user:pw@shop.example/", sites).code, "bad_url");
  assert.equal(refuseURL("//shop.example/", sites).code, "bad_url");
  assert.equal(refuseURL("https://127.0.0.1/", ["127.0.0.1"]).code, "address_private");
  assert.equal(refuseURL("https://0x7f.1/", sites).code, "address_private");
  assert.equal(refuseURL("https://[::1]/", sites).code, "address_private");
  assert.equal(refuseURL("https://printer.local/", ["printer.local"]).code, "address_private");
  assert.equal(refuseURL("https://shop。example/", sites), null, "an ideographic dot reads as a dot");
  assert.equal(refuseURL("https://shop.example/", []).code, "site_not_listed", "no sites reach nothing");
  assert.equal(refuseURL("javascript:alert(1)", sites).code, "bad_url");
  assert.equal(refuseURL("file:///etc/passwd", sites).code, "bad_url");
});

test("Chrome's own pages are never opened, and sensitive sites only when allowed by name", () => {
  assert.equal(refuseURL("https://chromewebstore.google.com/", ["google.com"]).code, "site_blocked");
  assert.equal(refuseURL("https://accounts.google.com/", ["google.com"]).code, "site_blocked");
  assert.equal(refuseURL("https://www.paypal.com/", ["paypal.com"]).code, "site_blocked");
  assert.equal(refuseURL("https://www.paypal.com/", ["paypal.com"], ["paypal.com"]), null, "allowed by name");
  assert.equal(refuseURL("https://online.bank.example/", ["bank.example"]).code, "site_blocked", "a bank by its name");
  // KIFF's own sites: never, even granted and allowed by name. A taf never drives the controls it's held by.
  for (const url of ["https://kiff.dev/", "https://www.kiff.dev/cards", "https://api.kiff.dev/v1/me", "https://planque.kiff.dev/", "https://cloud.tafs.kiff.dev/v1/tafs", "https://KIFF.dev./"]) {
    assert.equal(refuseURL(url, ["kiff.dev"], ["kiff.dev"]).code, "site_blocked", url);
  }
  assert.ok(NEVER_OPENED.includes("kiff.dev"), "Chrome's fence holds it too");
  assert.equal(refuseURL("https://notkiff.dev/", ["notkiff.dev"]), null, "only kiff.dev and its subdomains");
  // A merchant's console: blocked unless the phone allowed it by name.
  assert.equal(refuseURL("https://dashboard.stripe.com/payments", ["stripe.com"]).code, "site_blocked");
  assert.equal(refuseURL("https://dashboard.stripe.com/payments", ["stripe.com"], ["stripe.com"]), null, "allowed by name");
  assert.equal(sensitive("vendors.paddle.com"), true);
  assert.equal(sensitive("notstripe.com"), false);
  assert.equal(sensitive("www.impots.gouv.fr"), true);
  assert.equal(sensitive("bank.example.com"), true);
  assert.equal(sensitive("www.gov.uk"), true);
  assert.equal(sensitive("shop.example"), false);
  assert.equal(sensitive("bankside-coffee.example"), false, "a word that only starts with bank");
});

test("a grant's site is never a public suffix, which would reach every site under it", () => {
  assert.equal(siteOK("shop.example"), true);
  assert.equal(siteOK("shop.co.uk"), true);
  for (const s of ["com", "co.uk", "com.au", "github.io", ""]) assert.equal(siteOK(s), false, s);
  assert.equal(refuseURL("https://shop.example.co.uk/", ["co.uk"]).code, "site_not_listed");
  assert.equal(refuseURL("https://shop.example/", ["example"]).code, "site_not_listed");
});

test("a URL as recorded has no query or fragment", () => {
  assert.equal(bareURL("https://shop.example/a/b?token=x#y"), "https://shop.example/a/b");
  assert.equal(bareURL("nope"), "");
  assert.equal(bareURL("about:blank"), "", "a page off the web has no origin: never \"nullblank\"");
  assert.equal(bareURL("chrome://newtab/"), "");
});

test("card numbers, keys and labelled secrets are masked; prose and order numbers aren't", () => {
  assert.equal(maskText("card 4242 4242 4242 4242 ok"), "card •••• 4242 ok");
  assert.equal(maskText("order 1234567890123"), "order 1234567890123", "fails Luhn");
  assert.match(maskText("key sk_live_" + "a".repeat(24)), /key \[masked\]/);
  assert.match(maskText("ghp_" + "b".repeat(36)), /^\[masked\]$/);
  assert.match(maskText("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.c2lnbmF0dXJlc2ln"), /^\[masked\]$/);
  assert.equal(maskText("password: hunter22"), "password: [masked]");
  assert.equal(maskText("api_key=abcd1234"), "api_key=[masked]");
  assert.equal(maskText("Authorization: Bearer abcdefghijklmnop"), "Authorization: Bearer [masked]");
  assert.equal(maskText("Your password is incorrect"), "Your password is incorrect");
  assert.equal(maskText("-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----"), "[masked]");
});

test("one-time codes lose their digits, in English, French, Spanish and German; postal, order and promo numbers stay", () => {
  // The cases tafs cloud's page check answers too (runtime/test/page-codes.test.ts): one rule on both sides.
  const cases = JSON.parse(readFileSync(new URL("../../runtime/test/fixtures/one-time-codes.json", import.meta.url), "utf8"));
  for (const [text, masked] of cases.masked) assert.equal(maskText(text), masked, text);
  for (const text of cases.kept) assert.equal(maskText(text), text, text);
  const { secretSpans } = globalThis.TafsMask;
  const t = "123456 is your login code. Your verification code is 482 913.";
  assert.deepEqual(secretSpans(t).map(([a, b]) => t.slice(a, b)), ["123456", "482 913"], "a picture covers the digits");
});

test("a link keeps its address but not its credentials", () => {
  assert.equal(maskURL("https://shop.example/reset?token=abc&page=2"), "https://shop.example/reset?token=%5Bmasked%5D&page=2");
  assert.equal(maskURL("https://u:p@shop.example/"), "https://shop.example/");
  assert.equal(maskURL("https://shop.example/cb#access_token=abc"), "https://shop.example/cb");
  assert.equal(maskURL("http://shop.example/"), "");
  assert.equal(maskURL("javascript:alert(1)"), "");
});

const page = {
  fields: [
    { ref: "f1", label: "Email", kind: "email" },
    { ref: "f2", label: "Password", kind: "password" },
    { ref: "f3", label: "Card number", kind: "card" },
    { ref: "f4", label: "Code", kind: "code" },
  ],
  controls: [
    { ref: "c1", label: "Save" },
    { ref: "c2", label: "Place order" },
  ],
  links: [{ ref: "l1", label: "Help", url: "https://shop.example/help" }],
};

test("the extension's guard refuses what planque's does", () => {
  assert.equal(refuse(page, { kind: "fill", ref: "f1", value: "a@b.example" }), null);
  assert.equal(refuse(page, { kind: "fill", ref: "f2", value: "x" }).code, "secret_field");
  assert.equal(refuse(page, { kind: "fill", ref: "f4", value: "123456" }).code, "secret_field");
  assert.equal(refuse(page, { kind: "fill", ref: "f3", value: "4242" }).code, "payment_step");
  assert.equal(refuse(page, { kind: "click", ref: "c2" }).code, "payment_step");
  assert.equal(refuse(page, { kind: "click", ref: "c1" }).code, "payment_step", "any control on a payment page");
  const plain = { ...page, fields: [page.fields[0]], controls: [page.controls[0]] };
  assert.equal(refuse(plain, { kind: "click", ref: "c1" }), null);
  assert.equal(refuse(plain, { kind: "click", ref: "l1" }), null);
  assert.equal(refuse(plain, { kind: "click", ref: "c9" }).code, "ref_unknown");
  assert.equal(refuse(plain, { kind: "fill", ref: "f9", value: "x" }).code, "ref_unknown");
  assert.equal(refuse(plain, { kind: "submit", ref: "c1" }).code, "bad_step");
  assert.equal(refuse(plain, { kind: "fill", ref: "f1" }).code, "bad_step", "a fill has a value");
  // No saved sign-in: a step naming one is refused, into a password field or a plain one alike.
  assert.equal(refuse(page, { kind: "fill", ref: "f2", sealed: "sec_1:password" }).code, "secret_field");
  assert.equal(refuse(plain, { kind: "fill", ref: "f1", sealed: "sec_1:username" }).code, "secret_field");
  assert.equal(refuse(plain, { kind: "click", ref: "c1", sealed: "sec_1:password" }).code, "secret_field", "whatever the act's kind");
  // A link whose words pay is refused like a control; on a checkout or payment page no link is followed at all.
  const shop = { url: "https://shop.example/cart", fields: [], controls: [], links: [{ ref: "l1", label: "Buy now", url: "https://shop.example/buy" }, { ref: "l2", label: "Help", url: "https://shop.example/help" }] };
  assert.equal(refuse(shop, { kind: "click", ref: "l1" }).code, "payment_step");
  assert.equal(refuse(shop, { kind: "click", ref: "l2" }), null);
  const pay = { url: "https://shop.example/checkout", payment_frame: true, fields: [], controls: [], links: [{ ref: "l1", label: "Continue", url: "https://shop.example/checkout#pay" }, { ref: "l2", label: "Help", url: "https://shop.example/help" }] };
  assert.equal(refuse(pay, { kind: "click", ref: "l1" }).code, "payment_step", "a link back to the payment page itself");
  assert.equal(refuse(pay, { kind: "click", ref: "l2" }).code, "payment_step", "nor a neutral link to another page");
  // A neutral-label link on a checkout to another path may still place the order (a GET to /place-order): refused.
  const checkout = { url: "https://shop.example/checkout", checkout: { lines: [] }, fields: [], controls: [], links: [{ ref: "l1", label: "Continue", url: "https://shop.example/place-order" }] };
  assert.equal(refuse(checkout, { kind: "click", ref: "l1" }).code, "payment_step", "a checkout follows no link, whatever its path");
  assert.equal(refuse({ ...checkout, checkout: undefined }, { kind: "click", ref: "l1" }), null, "off a checkout, the same link is followed");
  for (const words of ["Pay now", "Commander", "Jetzt kaufen", "Finalizar compra", "Acquista ora", "Afrekenen", "Fazer pedido"]) {
    assert.ok(PAY_WORDS.test(words), words);
  }
});

test("a control or link that sends money out (a refund, a payout, a transfer, a withdrawal) is refused, one that only mentions it isn't", () => {
  for (const words of ["Refund", "Refund €50.00", "Issue refund", "Refund payment", "Pay out", "Payout", "Transfer funds", "Withdraw funds",
    "Make a transfer", "Bank transfer", "Retirer des fonds", "Retirar dinero", "Sacar dinheiro", "Send money", "Rembourser", "Effectuer un virement", "Rückerstattung veranlassen", "Überweisen", "Auszahlen", "Reembolsar",
    "Enviar dinero", "Effettua bonifico", "Overmaken", "Uitbetalen", "Fazer transferência", "  refund  ", "REFUND 12,50 $", "Refund 50 EUR", "Refund $1,000.00 USD", "Refund EUR 50", "Pay out 1.234,50 eur", "Überweisen 20 CHF"]) {
    assert.ok(MONEY_OUT.test(words), words);
  }
  for (const words of ["Refund policy", "Refunds and returns", "Transfer history", "Payout schedule", "How to withdraw", "Help",
    "Download refund report", "Withdrawal terms", "Remboursements", "", "Refunds", "Payouts", "Retirer", "Retirar", "Sacar", "Transfer",
    "Transferir", "Withdraw", "Virer", "Rückerstattung", "Preleva", "Abheben", "Refund FAQ", "Refund all items", "Payout settings"]) {
    assert.ok(!MONEY_OUT.test(words), words);
  }
  const console = {
    url: "https://console.example/payments/py_1", fields: [],
    controls: [{ ref: "c1", label: "Refund €50.00" }, { ref: "c2", label: "Copy payment id" }],
    links: [{ ref: "l1", label: "Refund policy", url: "https://console.example/policy" }, { ref: "l2", label: "Payout", url: "https://console.example/payouts/new" }],
  };
  assert.equal(refuse(console, { kind: "click", ref: "c1" }).code, "payment_step", "a refund button");
  assert.equal(refuse(console, { kind: "click", ref: "c2" }).code, "payment_step", "a page that refunds is a payment page: no other control either");
  assert.equal(refuse(console, { kind: "click", ref: "l2" }).code, "payment_step", "a link whose words pay out");
  const quiet = { ...console, controls: [{ ref: "c2", label: "Copy payment id" }] };
  assert.equal(refuse(quiet, { kind: "click", ref: "l1" }), null, "a link that only names refunds is followed");
  assert.equal(refuse(quiet, { kind: "click", ref: "c2" }), null);
});

test("press, scroll and back (gap 7) are held to the same rules: Enter submits nothing on a pay page, a key never goes into a secret", () => {
  const plain = { ...page, fields: [page.fields[0]], controls: [page.controls[0]] };
  assert.equal(refuse(plain, { kind: "press", ref: "f1", value: "Enter" }), null);
  assert.equal(refuse(plain, { kind: "press", ref: "f1", value: "ArrowDown" }), null);
  assert.equal(refuse(plain, { kind: "press", ref: "c1", value: "Escape" }), null);
  assert.equal(refuse(plain, { kind: "press", ref: "f1", value: "Tab" }).code, "bad_step", "a closed set of keys");
  assert.equal(refuse(plain, { kind: "press", ref: "f1", value: "Control+a" }).code, "bad_step");
  assert.equal(refuse(plain, { kind: "press", ref: "f9", value: "Enter" }).code, "ref_unknown");
  assert.equal(refuse(plain, { kind: "press", value: "Enter" }).code, "bad_step", "a press names its ref");
  // Enter submits: on a payment or checkout page it's refused, as any click there is; a key never goes into a secret field.
  assert.equal(refuse(page, { kind: "press", ref: "f1", value: "Enter" }).code, "payment_step");
  assert.equal(refuse({ ...plain, checkout: { lines: [] } }, { kind: "press", ref: "f1", value: "Enter" }).code, "payment_step");
  const signIn = { url: "https://shop.example/login", fields: [page.fields[0], page.fields[1]], controls: [], links: [] };
  assert.equal(refuse(signIn, { kind: "press", ref: "f2", value: "Enter" }).code, "secret_field");
  assert.equal(refuse(signIn, { kind: "press", ref: "f1", value: "Enter" }), null, "Enter in the email field of a sign-in page");
  // A fill may type key by key, and says so only as true.
  assert.equal(refuse(plain, { kind: "fill", ref: "f1", value: "Lyon", keys: true }), null);
  assert.equal(refuse(plain, { kind: "fill", ref: "f1", value: "Lyon", keys: "yes" }).code, "bad_step");
  // Scroll: a ref the page has, or the page itself up or down. Back names no ref.
  assert.equal(refuse(plain, { kind: "scroll", ref: "l1" }), null);
  assert.equal(refuse(plain, { kind: "scroll", ref: "x9" }).code, "ref_unknown");
  assert.equal(refuse(plain, { kind: "scroll" }), null);
  assert.equal(refuse(plain, { kind: "scroll", value: "up" }), null);
  assert.equal(refuse(plain, { kind: "scroll", value: "sideways" }).code, "bad_step");
  assert.equal(refuse(page, { kind: "back" }), null, "back from a payment page clicks nothing");
  // Enter on a control or link is a click: a link or button whose words pay is refused, as clicking it is.
  const shop = { url: "https://shop.example/cart", fields: [], controls: [{ ref: "c1", label: "Buy now" }, { ref: "c2", label: "Save" }], links: [{ ref: "l1", label: "Buy now", url: "https://shop.example/buy" }] };
  // A page whose only paying words are on a link: no paying control makes the page a payment page, so only the link's own
  // words refuse it, as a click on it is refused.
  const linkOnly = { url: "https://shop.example/item", fields: [{ ref: "f1", label: "Search", kind: "search" }], controls: [], links: [shop.links[0], { ref: "l2", label: "Help", url: "https://shop.example/help" }] };
  assert.equal(refuse(linkOnly, { kind: "click", ref: "l1" }).code, "payment_step");
  assert.equal(refuse(linkOnly, { kind: "press", ref: "l1", value: "Enter" }).code, "payment_step", "Enter on the paying link");
  assert.equal(refuse(linkOnly, { kind: "press", ref: "l2", value: "Enter" }), null, "Enter on a neutral link");
  assert.equal(refuse(linkOnly, { kind: "press", ref: "f1", value: "Enter" }), null, "Enter in a field of a page that isn't a payment page");
  assert.equal(refuse(shop, { kind: "press", ref: "l1", value: "Enter" }).code, "payment_step");
  assert.equal(refuse(shop, { kind: "press", ref: "c1", value: "Enter" }).code, "payment_step");
  assert.equal(refuse({ ...shop, controls: [shop.controls[1]], links: [] }, { kind: "press", ref: "c2", value: "Enter" }), null);
  assert.equal(refuse(plain, { kind: "back", ref: "c1" }).code, "bad_step");
});

test("every kind of act has its words in the side panel's step list", () => {
  const en = JSON.parse(readFileSync(new URL("../_locales/en/messages.json", import.meta.url), "utf8"));
  for (const kind of KINDS) assert.ok(en["step_act_" + kind] && en["step_act_" + kind].message, "step_act_" + kind);
});

test("every shipped language has an extension locale with English's keys and placeholders, and no English left in it", async () => {
  const { readdirSync, readFileSync } = await import("node:fs");
  const dir = new URL("../_locales/", import.meta.url);
  const read = (loc) => JSON.parse(readFileSync(new URL(loc + "/messages.json", dir), "utf8"));
  const en = read("en");
  // The locales are the languages the apps ship (app/strings/languages.json), in Chrome's codes: Portugal's Portuguese is
  // pt_PT, Traditional Chinese zh_TW, Serbian in Latin script sr, a regional variant with an underscore.
  const shipped = JSON.parse(readFileSync(new URL("../../app/strings/languages.json", import.meta.url), "utf8")).shipped;
  const chromeCode = (l) => ({ pt: "pt_PT", "zh-Hant": "zh_TW", "sr-Latn": "sr" })[l] || l.replace("-", "_");
  const want = shipped.filter((l) => l !== "en").map(chromeCode).sort();
  const locales = readdirSync(dir).filter((l) => l !== "en").sort();
  assert.deepEqual(locales, want, "one locale for each language the apps ship, and no other");
  // Lines that are the same in every language: names and the like. Anything else equal to English is English left in.
  const sameAsEnglish = new Set(["name", "coach_title"]); // Coach is a name, the same in every language
  // A word the language shares with English, on purpose: "Site" in French, Dutch, Portuguese, Romanian and Turkish,
  // "Password" in Italian, "Conversation" in French. Anything else equal to English is English left in.
  const sharedWords = { coach_untitled: ["fr"] };
  for (const loc of ["en", ...locales]) {
    const m = read(loc);
    assert.deepEqual(Object.keys(m).sort(), Object.keys(en).sort(), loc);
    for (const [key, { message, placeholders }] of Object.entries(m)) {
      assert.ok(message.trim(), `${loc} ${key}`);
      assert.deepEqual(Object.keys(placeholders || {}).sort(), Object.keys(en[key].placeholders || {}).sort(), `${loc} ${key}`);
      const used = [...message.matchAll(/\$([A-Z_]+)\$/g)].map((x) => x[1].toLowerCase()).sort();
      assert.deepEqual([...new Set(used)], Object.keys(placeholders || {}).sort(), `${loc} ${key} uses its placeholders`);
      if (loc !== "en" && !sameAsEnglish.has(key) && !(sharedWords[key] || []).includes(loc)) assert.notEqual(message, en[key].message, `${loc} ${key} is still English`);
    }
    assert.ok(m.description.message.length <= 132, `${loc} description: Chrome refuses over 132 characters`);
  }
});

test("the side panel's code field holds a whole pasted code, dashes and spaces included, and the extension reads it", () => {
  const shown = "K7QF-29XM-DPQR-STVW-XYZ2-3";
  const html = readFileSync(new URL("../sidepanel/index.html", import.meta.url), "utf8");
  const max = Number(/<input id="code"[^>]*maxlength="(\d+)"/.exec(html)[1]);
  assert.ok(max >= shown.length, "a code with its dashes fits");
  assert.ok(max >= shown.replace(/-/g, " ").length + 4, "room for a stray space or two");
  const want = parseCode(shown);
  assert.ok(want, "the code the phone shows parses");
  for (const typed of [shown.replace(/-/g, ""), shown.replace(/-/g, " "), " " + shown.toLowerCase() + " ", shown.replace(/-/g, " - ")]) {
    assert.deepEqual(parseCode(typed), want, typed);
  }
});

test("the pinned planque step key is a P-256 public key in SPKI form, 91 bytes, and parses", async () => {
  const { ENVIRONMENTS } = await import("../src/config.js");
  const spki = fromB64u(ENVIRONMENTS.production.planqueKey);
  assert.equal(spki.length, 91);
  // SEQUENCE { SEQUENCE { id-ecPublicKey, prime256v1 }, BIT STRING 04 || X || Y }
  assert.deepEqual([...spki.slice(0, 27)], [0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04]);
  const key = await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, true, ["verify"]);
  assert.equal(key.type, "public");
});

test("the pinned relay is the WebSocket API's own domain, over wss, with no path", async () => {
  const { ENVIRONMENTS } = await import("../src/config.js");
  const url = new URL(ENVIRONMENTS.production.relay);
  assert.equal(url.protocol, "wss:");
  assert.equal(url.hostname, "relay.planque.kiff.dev");
  assert.equal(url.pathname, "/");
  assert.equal(url.search, "", "the extension adds ?chrome=… itself");
});

test("a build's manifest hash is 64 lower-case hex characters, the form planque's allowlist keeps", async () => {
  const { manifestHashOf } = await import("../src/crypto.js");
  const bytes = readFileSync(new URL("../manifest.json", import.meta.url));
  const hash = await manifestHashOf(new Uint8Array(bytes));
  assert.match(hash, /^[0-9a-f]{64}$/);
  assert.equal(hash, createHash("sha256").update(bytes).digest("hex"));
  assert.equal(await manifestHashOf(new Uint8Array(0)), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "SHA-256 of nothing");
});

test("secretSpans names what a picture must cover: cards that pass Luhn, keys by shape, labelled values, long hex and base64 runs", () => {
  const { secretSpans } = globalThis.TafsMask;
  const pick = (text) => secretSpans(text).map(([a, b]) => text.slice(a, b));
  const stripe = "sk_live_" + "a".repeat(24);
  const hex = "0123456789abcdef".repeat(2);
  const text = `card 4242-4242-4242-4242 and 4111 1111 1111 1112, ${stripe}, api_key=abcd1234efgh, AKIA${"A".repeat(16)}, ghp_${"x".repeat(36)}, jwt eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2Q, hex ${hex}, b64 ${"Zm9v".repeat(10)}, Bearer abcdefghij1234`;
  assert.deepEqual(pick(text), [
    "4242-4242-4242-4242",
    stripe,
    "abcd1234efgh",
    "AKIA" + "A".repeat(16),
    "ghp_" + "x".repeat(36),
    "eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2Q",
    hex,
    "Zm9v".repeat(10),
    "abcdefghij1234",
  ]);
  assert.deepEqual(pick("order 1234567890123 and a long word " + "abcdefghij".repeat(4)), [], "not a card; no digit, so not a key");
  assert.deepEqual(pick("123e4567-e89b-12d3-a456-426614174000"), [], "a uuid has dashes and runs under 32");
  assert.deepEqual(pick(""), []);
});

test("Coach reads only the tab in front of the panel's window, on https, never a sensitive, switched-off, store or incognito page", () => {
  const tab = (url, extra = {}) => ({ id: 7, active: true, windowId: 2, incognito: false, url, ...extra });
  const at = { windowId: 2, off: ["news.example"] };
  assert.equal(asideRefusal(tab("https://shop.example/item?x=1"), at), null);
  assert.equal(asideRefusal(tab("https://shop.example/", { active: false }), at), "not_front", "a tab behind is never read");
  assert.equal(asideRefusal(tab("https://shop.example/", { windowId: 3 }), at), "not_front", "nor another window's tab");
  assert.equal(asideRefusal(null, at), "not_front");
  assert.equal(asideRefusal(tab("https://shop.example/"), {}), "not_front", "no window named, nothing read");
  assert.equal(asideRefusal(tab("http://shop.example/"), at), "not_https");
  assert.equal(asideRefusal(tab("chrome://settings"), at), "not_https");
  assert.equal(asideRefusal(tab("https://u:p@shop.example/"), at), "not_https");
  assert.equal(asideRefusal(tab("https://chromewebstore.google.com/detail/x"), at), "store");
  assert.equal(asideRefusal(tab("https://chrome.google.com/webstore/detail/x"), at), "store");
  assert.equal(asideRefusal(tab("https://shop.example/", { incognito: true }), at), "incognito");
  assert.equal(asideRefusal(tab("https://my.1password.com/vault"), at), "sensitive", "the run's sensitive list applies");
  assert.equal(asideRefusal(tab("https://news.example/today"), at), "off", "a site the person switched off");
  assert.equal(asideRefusal(tab("https://www.news.example/today"), at), null, "off is the exact host the person switched");
});

test("the page is checked again on the address it was read on: a tab that moved onto a refused page sends nothing", async () => {
  const fake = ({ before, after, readOn, active = true }) => {
    const calls = [];
    let n = 0;
    return {
      calls,
      tabs: { get: async (id) => ({ id, active: n++ === 0 ? true : active, windowId: 2, incognito: false, url: n === 1 ? before : after }) },
      scripting: {
        executeScript: async (o) => {
          calls.push(o.files ? "inject" : "read");
          return o.files ? [{}] : [{ result: { href: readOn, url: "https://x.example/masked", title: "T", text: "words", truncated: false } }];
        },
      },
    };
  };
  // The same page throughout: it's read and sent, without its whole address.
  let c = fake({ before: "https://shop.example/a", after: "https://shop.example/a", readOn: "https://shop.example/a?token=1" });
  let got = await readForCoach(c, 7, 2, []);
  assert.deepEqual(got, { host: "shop.example", page: { url: "https://x.example/masked", title: "T", text: "words", truncated: false } });
  assert.ok(!("href" in got.page), "the whole address never leaves");
  // Gated on a shop, read on a bank it moved to: nothing sent.
  c = fake({ before: "https://shop.example/a", after: "https://my.1password.com/", readOn: "https://my.1password.com/vault" });
  got = await readForCoach(c, 7, 2, []);
  assert.deepEqual([got.why, got.page], ["sensitive", undefined]);
  // Onto a host the person switched off.
  c = fake({ before: "https://shop.example/a", after: "https://news.example/", readOn: "https://news.example/x" });
  assert.equal((await readForCoach(c, 7, 2, ["news.example"])).why, "off");
  // The tab went behind another before the read finished.
  c = fake({ before: "https://shop.example/a", after: "https://shop.example/a", readOn: "https://shop.example/a", active: false });
  assert.equal((await readForCoach(c, 7, 2, [])).why, "not_front");
  // Refused on the first check: nothing is even injected.
  c = fake({ before: "https://my.1password.com/", after: "https://my.1password.com/", readOn: "https://my.1password.com/" });
  assert.equal((await readForCoach(c, 7, 2, [])).why, "sensitive");
  assert.deepEqual(c.calls, []);

  // For a taf: only on its own signed sites. A page off them is never read: nothing injected, nothing sent, and why.
  const wiki = "https://fr.wikipedia.org/wiki/Grenoble";
  c = fake({ before: wiki, after: wiki, readOn: wiki });
  assert.deepEqual(await readForCoach(c, 7, 2, [], ["shop.example"]), { host: "fr.wikipedia.org", why: "not_taf_site" });
  assert.deepEqual(c.calls, [], "a page on a site the taf isn't connected to is never read");
  c = fake({ before: wiki, after: wiki, readOn: wiki });
  assert.equal((await readForCoach(c, 7, 2, [], [])).why, "not_taf_site", "no signed sites: no page");
  // On its sites (a subdomain counts, as for a run), the same masked read as Coach's.
  c = fake({ before: wiki, after: wiki, readOn: wiki });
  got = await readForCoach(c, 7, 2, [], ["wikipedia.org"]);
  assert.equal(got.host, "fr.wikipedia.org");
  assert.ok(got.page && got.page.text === "words");
  // Read on its site, the tab moved off it before the read finished: nothing sent.
  c = fake({ before: "https://shop.example/a", after: "https://news.example/", readOn: "https://news.example/x" });
  got = await readForCoach(c, 7, 2, [], ["shop.example"]);
  assert.deepEqual([got.why, got.page], ["not_taf_site", undefined]);
});

test("a taf's sites for its page are the phone's signed ones: carried at pairing or relayed with their signature, never planque's list", async () => {
  const phone = await newKeyPair();
  const pairing = { chromeId: "chr_1", phoneKey: b64u(await publicSPKI(phone.publicKey)), carried: [{ grant: "g_c", sites: ["carried.example"] }] };
  const signed = async (grant, sites, key = phone.privateKey) => ({ sites, sig: await sign(key, LABEL.sites, { chrome_id: "chr_1", grant, sites }) });
  const other = await newKeyPair();
  const connections = readConnections([
    { grant: "g_1", agent: "a", name: "Wiki lookup", sites: ["wikipedia.org", "evil.example"], sites_signed: await signed("g_1", ["wikipedia.org"]) },
    { grant: "g_2", agent: "a", name: "Wiki lookup", sites: ["forged.example"], sites_signed: await signed("g_2", ["forged.example"], other.privateKey) },
    { grant: "g_3", agent: "a", name: "Wiki lookup", sites: ["unsigned.example"] },
    { grant: "g_4", agent: "b", name: "Other", sites: ["other.example"], sites_signed: await signed("g_4", ["other.example"]) },
    { grant: "g_5", agent: "a", name: "Wiki lookup", sites: ["moved.example"], sites_signed: await signed("g_1", ["moved.example"]) },
  ]);
  assert.deepEqual(await signedSitesOf(["g_1"], connections, pairing), ["wikipedia.org"], "planque's own list adds nothing");
  assert.deepEqual(await signedSitesOf(["g_2", "g_3", "g_5"], connections, pairing), [], "another key's, none, or another grant's signature: nothing");
  assert.deepEqual(await signedSitesOf(["g_c"], connections, pairing), ["carried.example"], "a carried connection's sites, as the phone signed them at pairing");
  assert.deepEqual(await signedSitesOf(["g_1"], connections, null), [], "unpaired: nothing");
  assert.ok(!(await signedSitesOf(["g_1", "g_c"], connections, pairing)).includes("other.example"), "never another taf's grant");
});

test("Coach never reads a mail site: Gmail, Outlook, any webmail host, by name or by its label", () => {
  const tab = (url) => ({ id: 7, active: true, windowId: 2, incognito: false, url });
  const at = { windowId: 2, off: [] };
  for (const url of [
    "https://mail.google.com/mail/u/0/#inbox",
    "https://outlook.live.com/mail/0/",
    "https://outlook.office.com/mail/",
    "https://outlook.office365.com/owa/",
    "https://webmail.example.org/",
    "https://mail.example.fr/inbox",
    "https://messagerie.orange.fr/",
    "https://correo.example.es/",
    "https://www.icloud.com/mail/",
    "https://app.tuta.com/",
    "https://mail.zoho.eu/",
  ]) assert.equal(asideRefusal(tab(url), at), "sensitive", url);
  // A mailbox word elsewhere in a name isn't mail: a shop called mailchimp, or a site about mail.
  assert.equal(asideRefusal(tab("https://mailchimp.com/"), at), null);
  assert.equal(asideRefusal(tab("https://www.royalmail.com/"), at), null);
  assert.equal(mailHost("MAIL.GOOGLE.COM"), true);
  assert.equal(mailHost("shop.example"), false);
});

test("the mail rule reaches every language's word and Outlook's new address, and a trailing dot gets past nothing", () => {
  const tab = (url) => ({ id: 7, active: true, windowId: 2, incognito: false, url });
  const at = { windowId: 2, off: ["news.example"] };
  for (const url of [
    "https://outlook.cloud.microsoft/mail/",
    "https://owa.example.com/owa/",
    "https://app.hey.com/",
    "https://poczta.onet.pl/",
    "https://email.seznam.cz/",
    "https://email.t-online.de/",
    "https://correio.example.com.br/",
    "https://freemail.hu/",
    "https://levelezes.example.hu/",
  ]) assert.equal(asideRefusal(tab(url), at), "sensitive", url);
  // A trailing dot is the same host: no list is got past by it, the person's switch included.
  assert.equal(asideRefusal(tab("https://outlook.live.com./"), at), "sensitive");
  assert.equal(asideRefusal(tab("https://mail.google.com./mail/"), at), "sensitive");
  assert.equal(asideRefusal(tab("https://www.paypal.com./"), at), "sensitive");
  assert.equal(asideRefusal(tab("https://news.example./"), at), "off");
  assert.equal(asideRefusal(tab("https://shop.example./"), at), null, "an ordinary host with a trailing dot is still read");
});
