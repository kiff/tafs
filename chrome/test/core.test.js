import { test } from "node:test";
import assert from "node:assert/strict";
import { canonical } from "../src/jcs.js";
import { LABEL, sign, verify, newKeyPair, publicSPKI, b64u, fromB64u, derToRaw, signingBytes } from "../src/crypto.js";
import { refuseURL, bareURL, sensitive, siteOK } from "../src/sites.js";
import { refuse, PAY_WORDS } from "../src/guard.js";
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
  for (const words of ["Pay now", "Commander", "Jetzt kaufen", "Finalizar compra", "Acquista ora", "Afrekenen", "Fazer pedido"]) {
    assert.ok(PAY_WORDS.test(words), words);
  }
});
