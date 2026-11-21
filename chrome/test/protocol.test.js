import { test } from "node:test";
import assert from "node:assert/strict";
import { LABEL, sign, verify, newKeyPair, publicSPKI, b64u, hmac, sha256, concat } from "../src/crypto.js";
import { Nonces, checkStep, result, hello, checkWelcome, checkRelease, checkAllowances, MAX_AHEAD_MS } from "../src/protocol.js";
import { parseCode, offer, checkConfirmation, extensionProof, ALPHABET, hex } from "../src/pairing.js";
import { Relay } from "../src/relay.js";

const pub = async (k) => b64u(await publicSPKI(k.publicKey));

async function planque() {
  const keys = await newKeyPair();
  return { keys, key: await pub(keys) };
}

const NOW = 1_800_000_000_000;

function step(extra = {}) {
  return { id: "op_1", nonce: "n1", exp: NOW + 10_000, chrome_id: "chr_1", run: "run_1", grant: "g_1", sites: ["shop.example"], op: "read", ...extra };
}

test("a step runs only when planque signed it for this Chrome, unexpired and new", async () => {
  const p = await planque();
  const nonces = new Nonces(() => NOW);
  const ctx = { planqueKey: p.key, chromeId: "chr_1", nonces, now: NOW };
  const signed = async (s) => ({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });

  assert.equal((await checkStep(await signed(step()), ctx)).why, null);
  assert.equal((await checkStep(await signed(step()), ctx)).why, "seen before", "a replay");
  assert.equal((await checkStep(await signed(step({ nonce: "n2", chrome_id: "chr_2" })), ctx)).why, "not for this Chrome");
  assert.equal((await checkStep(await signed(step({ nonce: "n3", exp: NOW - 1 })), ctx)).why, "expired");
  assert.equal((await checkStep(await signed(step({ nonce: "n4", exp: NOW + MAX_AHEAD_MS + 1 })), ctx)).why, "expiry too far ahead");
  assert.equal((await checkStep(await signed(step({ nonce: "n5", sites: [] })), ctx)).why, "no sites");
  assert.equal((await checkStep(await signed(step({ nonce: "n6", op: "eval" })), ctx)).why, "malformed");

  const other = await planque();
  const forged = { type: "step", step: step({ nonce: "n7" }), sig: await sign(other.keys.privateKey, LABEL.step, step({ nonce: "n7" })) };
  assert.equal((await checkStep(forged, ctx)).why, "not signed by planque");
  const relabelled = { type: "step", step: step({ nonce: "n8" }), sig: await sign(p.keys.privateKey, LABEL.result, step({ nonce: "n8" })) };
  assert.equal((await checkStep(relabelled, ctx)).why, "not signed by planque", "a signature made for another label");
  const changed = await signed(step({ nonce: "n9" }));
  changed.step.sites = ["evil.example"];
  assert.equal((await checkStep(changed, ctx)).why, "not signed by planque", "a field changed after signing");
});

test("each answer, hello and welcome is signed for its purpose", async () => {
  const ext = await newKeyPair();
  const r = await result(ext.privateKey, "op_1", { error: { code: "page_changed" } });
  assert.equal(r.ok, false);
  assert.equal(await verify(await pub(ext), LABEL.result, { id: "op_1", ok: false, page: null, error: { code: "page_changed" } }, r.sig), true);

  const h = await hello(ext.privateKey, { chromeId: "chr_1", nonceP: "p", nonceE: "e", version: "0.1.0", manifestHash: "h" });
  assert.equal(await verify(await pub(ext), LABEL.hello, { chrome_id: "chr_1", nonce_p: "p", nonce_e: "e", version: "0.1.0", manifest_hash: "h" }, h.sig), true);

  const p = await planque();
  const w = { type: "welcome", sig: await sign(p.keys.privateKey, LABEL.welcome, { chrome_id: "chr_1", nonce_p: "p", nonce_e: "e" }) };
  assert.equal(await checkWelcome(w, { planqueKey: p.key, chromeId: "chr_1", nonceP: "p", nonceE: "e" }), true);
  assert.equal(await checkWelcome(w, { planqueKey: p.key, chromeId: "chr_1", nonceP: "p", nonceE: "other" }), false, "another connect's nonce");
  const reflected = { type: "welcome", sig: h.sig };
  assert.equal(await checkWelcome(reflected, { planqueKey: await pub(ext), chromeId: "chr_1", nonceP: "p", nonceE: "e" }), false, "our own hello reflected");
});

test("a sealed value opens only on the paired phone's own release, never planque's", async () => {
  const phone = await newKeyPair();
  const p = await planque();
  const s = step({ op: "act", act: { kind: "fill", ref: "f2", sealed: "sec_1" } });
  const body = { chrome_id: "chr_1", op: "op_1", sealed: "sec_1", origin: "https://shop.example", field_ref: "f2", nonce: "r1", exp: NOW + 30_000 };
  const release = { ...body, sig: await sign(phone.privateKey, LABEL.release, body) };
  const ctx = { phoneKey: await pub(phone), chromeId: "chr_1", step: s, origin: "https://shop.example", now: NOW };
  assert.equal(await checkRelease(release, ctx), true);
  assert.equal(await checkRelease({ ...body, sig: await sign(p.keys.privateKey, LABEL.release, body) }, ctx), false, "planque's signature");
  assert.equal(await checkRelease(release, { ...ctx, origin: "https://evil.example" }), false, "another origin");
  assert.equal(await checkRelease(release, { ...ctx, now: NOW + 60_000 }), false, "expired");
  assert.equal(await checkRelease(release, { ...ctx, step: { ...s, act: { ...s.act, ref: "f3" } } }), false, "another field");
});

test("a pairing code is four characters of lookup and a 17-character secret, about 83 bits", () => {
  assert.deepEqual(parseCode("k7qf-29xm-dpqr-stvw-xyz2-3"), { lookup: "K7QF", secret: "29XMDPQRSTVWXYZ23" });
  assert.deepEqual(parseCode("K7QF 29XMDPQRSTVWXYZ23"), { lookup: "K7QF", secret: "29XMDPQRSTVWXYZ23" });
  assert.equal(parseCode("K7QF-29XMDP"), null, "the old six-character secret is too short");
  assert.equal(parseCode("K7QF-29XM-DPQR-STVW-XYZ2-0"), null, "a zero");
  assert.ok(!/[01ILOU]/.test(ALPHABET));
  assert.ok(Math.log2(ALPHABET.length) * 17 > 80);
});

/** The phone's side of a pairing, as the apps do it: checks our proof, then signs and proves its own key. */
async function phoneConfirms(secret, offered, { phone, pairing = "pr_1", name = "Work Mac", chromeId = "chr_1" }) {
  const spki = Uint8Array.from(Buffer.from(offered.key, "base64url"));
  assert.equal(await extensionProof(secret, spki), offered.proof, "the phone checks the extension's proof");
  const phoneKey = await pub(phone);
  const chromeHash = await sha256(spki);
  const phoneProof = b64u(await hmac(secret, LABEL.phoneProof, concat(Uint8Array.from(Buffer.from(phoneKey, "base64url")), chromeHash)));
  const body = { pairing, chrome_key_sha256: hex(chromeHash), name, phone_key: phoneKey, phone_proof: phoneProof };
  return { pairing, chrome_id: chromeId, name, phone_key: phoneKey, phone_proof: phoneProof, phone_sig: await sign(phone.privateKey, LABEL.pair, body) };
}

test("pairing binds the phone's key and ours under the code's secret, which planque never sees", async () => {
  const secret = "29XMDPQRSTVWXYZ23";
  const ext = await newKeyPair();
  const phone = await newKeyPair();
  const offered = await offer(secret, ext, { version: "0.1.0", manifestHash: "h", browser: "Chrome 131", os: "macOS" });
  assert.ok(!JSON.stringify(offered).includes(secret), "the secret isn't sent");
  const answer = await phoneConfirms(secret, offered, { phone });
  assert.deepEqual(await checkConfirmation(secret, ext, answer), { chromeId: "chr_1", name: "Work Mac", phoneKey: await pub(phone) });

  // planque swaps in a key of its own: it can sign, but it can't prove the key under the secret.
  const mallory = await newKeyPair();
  const swapped = await phoneConfirms("ZZZZZZZZZZZZZZZZZ", offered, { phone: mallory }).catch(() => null);
  assert.equal(swapped, null, "the phone itself refuses an offer proved under another secret");
  const forged = { ...answer, phone_key: await pub(mallory) };
  forged.phone_sig = await sign(mallory.privateKey, LABEL.pair, { pairing: "pr_1", chrome_key_sha256: "x", name: "Work Mac", phone_key: forged.phone_key, phone_proof: answer.phone_proof });
  assert.equal(await checkConfirmation(secret, ext, forged), null, "a substituted phone key");
  assert.equal(await checkConfirmation(secret, ext, { ...answer, name: "Other" }), null, "a renamed pairing");
  assert.equal(await checkConfirmation("AAAAAAAAAAAAAAAAA", ext, answer), null, "another secret");
  const otherExt = await newKeyPair();
  assert.equal(await checkConfirmation(secret, otherExt, answer), null, "an answer for another extension's key");
});

class FakeSocket {
  static last = null;
  constructor(url) {
    this.url = url;
    this.sent = [];
    this.closed = null;
    FakeSocket.last = this;
  }
  send(data) {
    this.sent.push(JSON.parse(data));
  }
  close(code = 1000) {
    this.closed = code;
    this.onclose && this.onclose({ code });
  }
  async deliver(m) {
    await this.onmessage({ data: JSON.stringify(m) });
  }
}

async function relayWith(p, { runner } = {}) {
  const ext = await newKeyPair();
  const handled = [];
  let wiped = false;
  const relay = new Relay({
    config: { relayURL: "wss://planque.test/v1/extension", planqueKey: p.key, version: "0.1.0", manifestHash: "h" },
    kept: { keys: ext, pairing: { chromeId: "chr_1" } },
    runner: runner || { active: false, handle: async (s) => (handled.push(s), { page: { read_id: "rd_1", url: "https://shop.example/" } }), stop: async () => {} },
    onUnpaired: () => (wiped = true),
    WebSocket: FakeSocket,
    now: () => NOW,
  });
  relay.connect();
  return { relay, ext, handled, ws: FakeSocket.last, wiped: () => wiped };
}

async function handshake(p, ws) {
  await ws.deliver({ type: "challenge", nonce_p: "np" });
  const h = ws.sent.at(-1);
  await ws.deliver({ type: "welcome", sig: await sign(p.keys.privateKey, LABEL.welcome, { chrome_id: "chr_1", nonce_p: "np", nonce_e: h.nonce_e }) });
}

test("the relay takes steps only after both ends proved themselves", async () => {
  const p = await planque();
  const { relay, ext, handled, ws } = await relayWith(p);
  assert.equal(ws.url, "wss://planque.test/v1/extension?chrome=chr_1");
  await ws.deliver({ type: "challenge", nonce_p: "np" });
  const h = ws.sent[0];
  assert.equal(h.type, "hello");
  assert.equal(await verify(await pub(ext), LABEL.hello, { chrome_id: "chr_1", nonce_p: "np", nonce_e: h.nonce_e, version: "0.1.0", manifest_hash: "h" }, h.sig), true);
  await ws.deliver({ type: "welcome", sig: await sign(p.keys.privateKey, LABEL.welcome, { chrome_id: "chr_1", nonce_p: "np", nonce_e: h.nonce_e }) });
  assert.equal(relay.connected, true);

  const s = step();
  await ws.deliver({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
  assert.equal(handled.length, 1);
  const answer = ws.sent.at(-1);
  assert.equal(answer.type, "result");
  assert.equal(answer.ok, true);

  const bad = step({ nonce: "n2" });
  await ws.deliver({ type: "step", step: bad, sig: "AAAA" });
  assert.equal(handled.length, 1, "an unsigned step never reaches the runner");
  assert.equal(ws.sent.at(-1).error.code, "bad_step");
  relay.close();
});

test("a step before the welcome, or a forged welcome, ends the connection", async () => {
  const p = await planque();
  const early = await relayWith(p);
  const s = step();
  await early.ws.deliver({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
  assert.equal(early.handled.length, 0);
  assert.notEqual(early.ws.closed, null);

  const forged = await relayWith(p);
  await forged.ws.deliver({ type: "challenge", nonce_p: "np" });
  const other = await planque();
  await forged.ws.deliver({ type: "welcome", sig: await sign(other.keys.privateKey, LABEL.welcome, { chrome_id: "chr_1", nonce_p: "np", nonce_e: forged.ws.sent[0].nonce_e }) });
  assert.equal(forged.relay.connected, false);
  assert.notEqual(forged.ws.closed, null);
});

test("planque closing with 4410 wipes the extension", async () => {
  const p = await planque();
  const r = await relayWith(p);
  await handshake(p, r.ws);
  r.ws.close(4410);
  assert.equal(r.wiped(), true);
  const other = await relayWith(p);
  other.ws.close(1006);
  assert.equal(other.wiped(), false, "a dropped connection isn't an unpair");
  r.relay.close();
  other.relay.close();
});

test("a step's nonce is still refused after the service worker restarts", async () => {
  const p = await planque();
  const box = { entries: [] };
  const kept = { get: async () => box.entries, set: async (e) => void (box.entries = e) };
  const s = step({ nonce: "nr" });
  const msg = { type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) };
  const first = new Nonces(() => NOW, kept);
  assert.equal((await checkStep(msg, { planqueKey: p.key, chromeId: "chr_1", nonces: first, now: NOW })).why, null);
  const afterRestart = new Nonces(() => NOW, kept);
  assert.equal((await checkStep(msg, { planqueKey: p.key, chromeId: "chr_1", nonces: afterRestart, now: NOW })).why, "seen before");
});

test("a release reaches at most two minutes ahead", async () => {
  const phone = await newKeyPair();
  const s = step({ op: "act", act: { kind: "fill", ref: "f2", sealed: "sec_1" } });
  const body = { chrome_id: "chr_1", op: "op_1", sealed: "sec_1", origin: "https://shop.example", field_ref: "f2", nonce: "r1", exp: NOW + 10 * 60_000 };
  const release = { ...body, sig: await sign(phone.privateKey, LABEL.release, body) };
  assert.equal(await checkRelease(release, { phoneKey: await pub(phone), chromeId: "chr_1", step: s, origin: "https://shop.example", now: NOW }), false);
});

test("a sensitive site opens only on the paired phone's own allowance for that grant", async () => {
  const phone = await newKeyPair();
  const p = await planque();
  const allow = async (k, grant, site) => ({ site, sig: await sign(k.privateKey, LABEL.sensitive, { chrome_id: "chr_1", grant, site }) });
  const ctx = { phoneKey: await pub(phone), chromeId: "chr_1", grant: "g_1" };
  assert.deepEqual(await checkAllowances([await allow(phone, "g_1", "paypal.com")], ctx), ["paypal.com"]);
  assert.deepEqual(await checkAllowances([await allow(p.keys, "g_1", "paypal.com")], ctx), [], "planque can't allow one");
  assert.deepEqual(await checkAllowances([await allow(phone, "g_2", "paypal.com")], ctx), [], "another grant's");
  assert.deepEqual(await checkAllowances(["paypal.com"], ctx), [], "a bare name");
  assert.deepEqual(await checkAllowances([await allow(phone, "g_1", "paypal.com")], { ...ctx, phoneKey: null }), [], "not paired");
});
