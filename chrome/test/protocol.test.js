import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { LABEL, sign, verify, newKeyPair, publicSPKI, b64u, hmac, sha256, concat, signingBytes, requestsMessage } from "../src/crypto.js";
import { profileId } from "../src/store.js";
import { Nonces, checkStep, clockOffset, result, hello, checkWelcome, checkRelease, checkAllowances, MAX_AHEAD_MS, MAX_OFFSET_MS, SKEW_MS, SKEW_KNOWN_MS } from "../src/protocol.js";
import { parseCode, offer, checkConfirmation, extensionProof, oneTouchProof, postOffer, ALPHABET, hex } from "../src/pairing.js";
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

const KIFF_HASH = "ab".repeat(32);
const SIGNED_AT = "2026-11-22T21:30:00Z";

/** The phone's side of a pairing with one fingerprint: one approval-key signature over this pairing and KIFF's request. */
async function phoneConfirmsTogether(secret, offered, { phone, kiffHash = KIFF_HASH, tenant = "tenant-1", signedAt = SIGNED_AT, name = "Work Mac" }) {
  const answer = await phoneConfirms(secret, offered, { phone, name });
  const spki = Uint8Array.from(Buffer.from(offered.key, "base64url"));
  const body = { pairing: answer.pairing, chrome_key_sha256: hex(await sha256(spki)), name, phone_key: answer.phone_key, phone_proof: answer.phone_proof };
  const own = hex(await sha256(signingBytes(LABEL.pair, body)));
  const data = requestsMessage(tenant, signedAt, own, kiffHash);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, phone.privateKey, data));
  return { ...answer, phone_sig: b64u(sig), tenant, signed_at: signedAt, kiff_request_sha256: kiffHash };
}

test("one fingerprint confirms the pairing and KIFF's request: the extension checks the one signature over both", async () => {
  const secret = "29XMDPQRSTVWXYZ23";
  const ext = await newKeyPair();
  const phone = await newKeyPair();
  const offered = await offer(secret, ext, { version: "0.1.0", manifestHash: "h", browser: "Chrome 131", os: "macOS" });
  const answer = await phoneConfirmsTogether(secret, offered, { phone });
  assert.deepEqual(await checkConfirmation(secret, ext, answer), { chromeId: "chr_1", name: "Work Mac", phoneKey: await pub(phone) });

  const refused = async (change, why) => assert.equal(await checkConfirmation(secret, ext, { ...answer, ...change }), null, why);
  await refused({ kiff_request_sha256: "cd".repeat(32) }, "another KIFF request than the one signed");
  await refused({ tenant: "tenant-2" }, "another tenant");
  await refused({ signed_at: "2026-11-22T21:31:00Z" }, "another time");
  await refused({ name: "Other" }, "a renamed pairing: our message changes, so the hash does");
  await refused({ chrome_id: "chr_1", pairing: "pr_2" }, "another pairing");
  const mallory = await newKeyPair();
  const swapped = { ...(await phoneConfirmsTogether(secret, offered, { phone: mallory })), phone_proof: answer.phone_proof, phone_key: answer.phone_key };
  assert.equal(await checkConfirmation(secret, ext, swapped), null, "a signature by another key");
  assert.equal(await checkConfirmation(secret, await newKeyPair(), answer), null, "an answer for another extension's key");

  // All three fields or none; the form's fields can't shift a line; a request is never paired with itself.
  for (const k of ["tenant", "signed_at", "kiff_request_sha256"]) {
    const { [k]: _gone, ...partial } = answer;
    assert.equal(await checkConfirmation(secret, ext, partial), null, "without " + k);
  }
  // Fields the phone really signed, so only the extension's own checks can refuse them: a newline could shift a line of
  // the message, a hash isn't 64 lower-case hex, a tenant or a time is empty.
  for (const [why, fields] of Object.entries({
    "a newline in the tenant": { tenant: "tenant-1\n" + SIGNED_AT },
    "a newline in the time": { signedAt: SIGNED_AT + "\n" + KIFF_HASH },
    "an empty tenant": { tenant: "" },
    "an empty time": { signedAt: "" },
    "upper-case hex": { kiffHash: KIFF_HASH.toUpperCase() },
    "a short hash": { kiffHash: "ab" },
  })) {
    assert.equal(await checkConfirmation(secret, ext, await phoneConfirmsTogether(secret, offered, { phone, ...fields })), null, why);
  }
  const spki = Uint8Array.from(Buffer.from(offered.key, "base64url"));
  const own = hex(await sha256(signingBytes(LABEL.pair, { pairing: answer.pairing, chrome_key_sha256: hex(await sha256(spki)), name: "Work Mac", phone_key: answer.phone_key, phone_proof: answer.phone_proof })));
  const itself = await phoneConfirmsTogether(secret, offered, { phone, kiffHash: own });
  assert.equal(await checkConfirmation(secret, ext, itself), null, "this pairing's own hash as KIFF's");
  // The signature a phone makes over the pairing alone is not a combined one, and the other way round.
  const alone = await phoneConfirms(secret, offered, { phone });
  assert.equal(await checkConfirmation(secret, ext, { ...alone, tenant: "tenant-1", signed_at: SIGNED_AT, kiff_request_sha256: KIFF_HASH }), null, "a pairing-only signature given as a combined one");
  assert.deepEqual(await checkConfirmation(secret, ext, alone), { chromeId: "chr_1", name: "Work Mac", phoneKey: await pub(phone) }, "a pairing-only answer still checks as before");
  for (const k of ["tenant", "signed_at", "kiff_request_sha256"]) {
    assert.equal(await checkConfirmation(secret, ext, { ...alone, [k]: "x" }), null, "a pairing-only signature with only " + k + " added");
  }
  assert.equal(await checkConfirmation(secret, ext, { ...answer, tenant: undefined, signed_at: undefined, kiff_request_sha256: undefined }), null, "a combined signature given as a pairing-only one");
});

test("the offer says the extension checks one fingerprint, under a proof only the code's holder can make", async () => {
  const secret = "29XMDPQRSTVWXYZ23";
  const ext = await newKeyPair();
  const spki = await publicSPKI(ext.publicKey);
  const offered = await offer(secret, ext, { version: "0.1.0", manifestHash: "h", browser: "Chrome 131", os: "macOS", kiffOffer: '{"v":"kiff.pair.v1"}' });
  assert.equal(offered.one_touch, true);
  assert.equal(offered.one_touch_proof, await oneTouchProof(secret, spki, '{"v":"kiff.pair.v1"}'), "the phone recomputes it from the secret, the key and the KIFF offer");
  assert.notEqual(offered.one_touch_proof, offered.proof, "its own label: the flag's proof is never the key's proof");
  assert.notEqual(offered.one_touch_proof, await oneTouchProof("ZZZZZZZZZZZZZZZZZ", spki, '{"v":"kiff.pair.v1"}'), "under the secret, which planque never sees");
  assert.notEqual(offered.one_touch_proof, await oneTouchProof(secret, spki, '{"v":"other"}'), "covers the KIFF offer, as the key's proof does");
  assert.equal(offered.proof, await extensionProof(secret, spki, '{"v":"kiff.pair.v1"}'), "the key's proof is what today's phones check, unchanged");
});

test("an offer planque refuses for a field it doesn't know goes again without the newer fields, and only then", async () => {
  const body = { key: "k", version: "0.1.0", manifest_hash: "h", browser: "Chrome 131", os: "macOS", proof: "p", profile_id: "a".repeat(32), one_touch: true, one_touch_proof: "q" };
  const calls = [];
  const planque = (strict) => async (url, init) => {
    const sent = JSON.parse(init.body);
    calls.push(sent);
    const unknown = Object.keys(sent).find((k) => strict.includes(k));
    return unknown ? new Response(JSON.stringify({ error: "the body must be a JSON object with the documented fields: unknown field \"" + unknown + "\"" }), { status: 400 }) : new Response("{}", { status: 202 });
  };
  // A planque that takes everything: one request, everything sent.
  assert.equal((await postOffer(planque([]), "u", body)).status, 202);
  assert.deepEqual(calls, [body]);
  // One that predates the fields: a second request, without them and with all the rest.
  calls.length = 0;
  assert.equal((await postOffer(planque(["profile_id", "one_touch", "one_touch_proof"]), "u", body)).status, 202);
  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1], { key: "k", version: "0.1.0", manifest_hash: "h", browser: "Chrome 131", os: "macOS", proof: "p" });
  // A 400 for another reason is the answer, not a reason to send less; nor is any other status.
  calls.length = 0;
  const other = async (url, init) => (calls.push(init.body), new Response(JSON.stringify({ error: "the proof does not match" }), { status: 400 }));
  assert.equal((await postOffer(other, "u", body)).status, 400);
  assert.equal(calls.length, 1);
  calls.length = 0;
  const refused = async (url, init) => (calls.push(init.body), new Response("{}", { status: 403 }));
  assert.equal((await postOffer(refused, "u", body)).status, 403);
  assert.equal(calls.length, 1);
  // An offer without the newer fields isn't sent twice either.
  calls.length = 0;
  const { profile_id: _a, one_touch: _b, one_touch_proof: _c, ...plain } = body;
  assert.equal((await postOffer(planque(["key"]), "u", plain)).status, 400);
  assert.equal(calls.length, 1);
});

test("what the phone signs once for two requests is the same lines KIFF and planque build, the hashes sorted", () => {
  const text = (m) => new TextDecoder().decode(m);
  assert.equal(text(requestsMessage("tenant-1", "2026-11-22T21:30:00Z", "bb".repeat(32), "aa".repeat(32))), ["kiff.requests.v1", "tenant-1", "2026-11-22T21:30:00Z", "aa".repeat(32), "bb".repeat(32)].join("\n"));
  assert.deepEqual(requestsMessage("t", "s", "bb", "aa"), requestsMessage("t", "s", "aa", "bb"), "either order");
});

test("the extension keeps an id for its Chrome profile, and offers it to pair under", async () => {
  const area = { data: {}, get: async (k) => ({ [k]: area.data[k] }), set: async (o) => void Object.assign(area.data, o) };
  let n = 0;
  const random = (len) => Uint8Array.from({ length: len }, (_, i) => (n + i) % 256);
  const id = await profileId(area, random);
  assert.match(id, /^[0-9a-f]{32}$/);
  n = 99;
  assert.equal(await profileId(area, random), id, "kept, not made again");
  area.data.profile_id = "not an id";
  assert.notEqual(await profileId(area, random), "not an id", "a value that isn't one is replaced");
  assert.equal((await profileId({ get: async () => ({}), set: async () => {} }, random)).length, 32);

  const ext = await newKeyPair();
  const offered = await offer("29XMDPQRSTVWXYZ23", ext, { version: "0.1.0", manifestHash: "h", browser: "Chrome 131", os: "macOS", profileId: id });
  assert.equal(offered.profile_id, id);
  const without = await offer("29XMDPQRSTVWXYZ23", ext, { version: "0.1.0", manifestHash: "h", browser: "Chrome 131", os: "macOS" });
  assert.ok(!("profile_id" in without), "none made, none sent");
  assert.equal(without.proof, offered.proof, "the proof doesn't cover the id: it is a hint, and authenticates nothing");
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
