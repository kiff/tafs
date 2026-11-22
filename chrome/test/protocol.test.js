import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { LABEL, sign, verify, newKeyPair, publicSPKI, b64u, hmac, sha256, concat } from "../src/crypto.js";
import { Nonces, checkStep, clockOffset, result, hello, checkWelcome, checkRelease, checkAllowances, MAX_AHEAD_MS, MAX_OFFSET_MS, SKEW_MS, SKEW_KNOWN_MS } from "../src/protocol.js";
import { parseCode, offer, checkConfirmation, extensionProof, ALPHABET, hex } from "../src/pairing.js";
import { Relay, MAX_RESULT_BYTES } from "../src/relay.js";

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
  assert.equal((await checkStep(await signed(step({ nonce: "n3", exp: NOW - SKEW_MS - 1 })), ctx)).why, "expired");
  assert.equal((await checkStep(await signed(step({ nonce: "n4", exp: NOW + MAX_AHEAD_MS + SKEW_MS + 1 })), ctx)).why, "expiry too far ahead");
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
  const r = await result(ext.privateKey, { id: "op_1", nonce: "n1" }, "chr_1", { error: { code: "page_changed" } });
  assert.equal(r.ok, false);
  const body = { id: "op_1", nonce: "n1", chrome_id: "chr_1", ok: false, page: null, error: { code: "page_changed" } };
  assert.equal(await verify(await pub(ext), LABEL.result, body, r.sig), true);
  // The step's nonce and the Chrome are signed: the same answer can't be filed against another step or Chrome.
  assert.equal(await verify(await pub(ext), LABEL.result, { ...body, nonce: "n2" }, r.sig), false);
  assert.equal(await verify(await pub(ext), LABEL.result, { ...body, chrome_id: "chr_2" }, r.sig), false);

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
  const s = step({ op: "act", read_id: "rd_1", act: { kind: "fill", ref: "f2", sealed: "sec_1" } });
  const body = { chrome_id: "chr_1", op: "op_1", sealed: "sec_1", origin: "https://shop.example", field_ref: "f2", read_id: "rd_1", nonce: "r1", exp: NOW + 30_000 };
  const release = { ...body, sig: await sign(phone.privateKey, LABEL.release, body) };
  const ctx = { phoneKey: await pub(phone), chromeId: "chr_1", step: s, origin: "https://shop.example", now: NOW };
  assert.equal(await checkRelease(release, ctx), true);
  assert.equal(await checkRelease({ ...body, sig: await sign(p.keys.privateKey, LABEL.release, body) }, ctx), false, "planque's signature");
  assert.equal(await checkRelease(release, { ...ctx, origin: "https://evil.example" }), false, "another origin");
  assert.equal(await checkRelease(release, { ...ctx, now: NOW + 60_000 }), false, "expired");
  assert.equal(await checkRelease(release, { ...ctx, step: { ...s, act: { ...s.act, ref: "f3" } } }), false, "another field");
  assert.equal(await checkRelease(release, { ...ctx, step: { ...s, read_id: "rd_2" } }), false, "the same ref on another read");
  assert.equal(await checkRelease(release, { ...ctx, step: { ...s, read_id: undefined } }), false, "no read named");
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
  assert.equal(answer.nonce, s.nonce, "the answer names the step's own nonce");
  assert.equal(answer.chrome_id, "chr_1");
  const { sig, type, ...signedBody } = answer;
  assert.equal(await verify(await pub(ext), LABEL.result, signedBody, sig), true);

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

test("the extension speaks first: open, then planque's challenge", async () => {
  const p = await planque();
  const { relay, ws } = await relayWith(p);
  assert.deepEqual(ws.sent, [], "nothing before the connection is up");
  ws.onopen();
  assert.deepEqual(ws.sent, [{ type: "open" }]);
  await ws.deliver({ type: "challenge", nonce_p: "np" });
  assert.equal(ws.sent.at(-1).type, "hello");
  relay.close();
});

test("planque's close message counts as its close code: 4410 wipes once, 4401 and 4403 only close", async () => {
  const p = await planque();
  const before = await relayWith(p);
  await before.ws.deliver({ type: "close", code: 4410, reason: "unpaired" });
  assert.equal(before.wiped(), true, "even before the proofs");
  assert.notEqual(before.ws.closed, null);

  const after = await relayWith(p);
  let wipes = 0;
  after.relay.onUnpaired = () => (wipes += 1);
  await handshake(p, after.ws);
  await after.ws.deliver({ type: "close", code: 4410 });
  after.ws.onclose({ code: 4410 });
  assert.equal(wipes, 1, "a close message and a close code wipe once");
  assert.equal(after.relay.connected, false);

  for (const code of [4401, 4403]) {
    const r = await relayWith(p);
    await handshake(p, r.ws);
    await r.ws.deliver({ type: "close", code, reason: "no" });
    assert.equal(r.wiped(), false, String(code));
    assert.equal(r.relay.connected, false, String(code));
    assert.notEqual(r.ws.closed, null, String(code));
  }
});

test("an answer over the relay's limit is page_too_large, signed", async () => {
  const p = await planque();
  const big = { active: false, handle: async () => ({ page: { read_id: "rd_1", url: "https://shop.example/", text: "x".repeat(MAX_RESULT_BYTES) } }), stop: async () => {} };
  const { relay, ext, ws } = await relayWith(p, { runner: big });
  await handshake(p, ws);
  const s = step();
  await ws.deliver({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
  const answer = ws.sent.at(-1);
  assert.equal(answer.type, "result");
  assert.equal(answer.error.code, "page_too_large");
  assert.ok(JSON.stringify(answer).length < MAX_RESULT_BYTES);
  const { sig, type, ...body } = answer;
  assert.equal(await verify(await pub(ext), LABEL.result, body, sig), true);
  relay.close();
});

test("a connection dropped during a run is opened again at once; idle, it waits for the alarm", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const p = await planque();
    const running = { active: true, handle: async () => ({}), stop: async () => {} };
    const { relay, ws } = await relayWith(p, { runner: running });
    await handshake(p, ws);
    ws.onclose({ code: 1001 });
    assert.equal(FakeSocket.last, ws, "not before the first wait");
    mock.timers.tick(1_000);
    assert.notEqual(FakeSocket.last, ws, "opened again");
    relay.close();

    const idle = await relayWith(p);
    await handshake(p, idle.ws);
    idle.ws.onclose({ code: 1001 });
    mock.timers.tick(60_000);
    assert.equal(FakeSocket.last, idle.ws, "idle: the alarm reconnects");
    idle.relay.close();
  } finally {
    mock.timers.reset();
  }
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

test("a clock a little off still runs a step, by planque's time when the welcome carries it and by a fixed slack when not", async () => {
  const p = await planque();
  const signed = async (extra) => {
    const s = step(extra);
    return { type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) };
  };
  const base = { planqueKey: p.key, chromeId: "chr_1", now: NOW };
  const run = async (extra, over) => (await checkStep(await signed({ nonce: "n" + Math.random(), ...extra }), { ...base, nonces: new Nonces(() => NOW), ...over })).why;
  // Without planque's time: a Chrome whose clock is 2 s behind or 40 s ahead still runs a step; a step 61 s stale doesn't.
  assert.equal(await run({ exp: NOW + MAX_AHEAD_MS + 2_000 }), null, "2 s behind");
  assert.equal(await run({ exp: NOW - 40_000 }), null, "40 s ahead of planque");
  assert.equal(await run({ exp: NOW - SKEW_MS - 1 }), "expired");
  // With it, the clock is moved by the offset and the slack shrinks.
  const behind = 20_000;
  assert.equal(await run({ exp: NOW + behind + MAX_AHEAD_MS }, { offset: behind }), null, "a Chrome 20 s behind");
  assert.equal(await run({ exp: NOW + behind - SKEW_KNOWN_MS - 1 }, { offset: behind }), "expired");
  assert.equal(await run({ exp: NOW + behind + MAX_AHEAD_MS + SKEW_KNOWN_MS + 1 }, { offset: behind }), "expiry too far ahead");
});

test("planque's time in the welcome moves the clock only a little, and only when it is a number", () => {
  assert.equal(clockOffset({ now: NOW + 2_000 }, NOW), 2_000);
  assert.equal(clockOffset({ now: NOW - 5_000 }, NOW), -5_000);
  assert.equal(clockOffset({ now: NOW + MAX_OFFSET_MS }, NOW), MAX_OFFSET_MS);
  assert.equal(clockOffset({ now: NOW + MAX_OFFSET_MS + 1 }, NOW), null, "too far out: ignored");
  assert.equal(clockOffset({ now: "later" }, NOW), null);
  assert.equal(clockOffset({ now: NaN }, NOW), null);
  assert.equal(clockOffset({}, NOW), null);
  assert.equal(clockOffset(null, NOW), null);
});

test("the clock slack's values are pinned: a wider one is a decision, not a drift", () => {
  assert.equal(MAX_AHEAD_MS, 30_000);
  assert.equal(SKEW_MS, 60_000, "slack without planque's time");
  assert.equal(SKEW_KNOWN_MS, 5_000, "slack with it");
  assert.equal(MAX_OFFSET_MS, 120_000, "the most the welcome's time may move the clock");
});
