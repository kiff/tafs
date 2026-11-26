import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { LABEL, sign, verify, newKeyPair, publicSPKI, b64u, hmac, sha256, concat, signingBytes, requestsMessage } from "../src/crypto.js";
import { profileId } from "../src/store.js";
import { Nonces, checkStep, clockOffset, result, hello, checkWelcome, checkRelease, checkAllowances, MAX_AHEAD_MS, MAX_OFFSET_MS, SKEW_MS, SKEW_KNOWN_MS } from "../src/protocol.js";
import { parseCode, offer, checkConfirmation, extensionProof, oneTouchProof, postOffer, readCarried, ALPHABET, hex } from "../src/pairing.js";
import { checkSites } from "../src/protocol.js";
import { Relay, readConnections, MAX_RESULT_BYTES, cutPages } from "../src/relay.js";

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

// The run the person started from the side panel (docs/chrome.md, "A taf works where you are"): planque names it inside what it
// signs, so only planque's verified step can let a run work in the person's tab.
test("a step's agent_run is planque's signed word: taken through the check, and refused when added or changed after signing", async () => {
  const p = await planque();
  const ctx = { planqueKey: p.key, chromeId: "chr_1", nonces: new Nonces(() => NOW), now: NOW };
  const signed = async (s) => ({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
  const ok = await checkStep(await signed(step({ nonce: "a1", op: "open", agent_run: "kr_1" })), ctx);
  assert.equal(ok.why, null, "a step with the field is a step");
  assert.equal(ok.step.agent_run, "kr_1");
  const changed = await signed(step({ nonce: "a2", agent_run: "kr_1" }));
  changed.step.agent_run = "kr_2";
  assert.equal((await checkStep(changed, ctx)).why, "not signed by planque", "changed");
  const added = await signed(step({ nonce: "a3" }));
  added.step.agent_run = "kr_1";
  assert.equal((await checkStep(added, ctx)).why, "not signed by planque", "added by someone else");
});

// The hand-over's step, as planque signs it (chrome_hand.go): op "hand", the read it was planned from, and the taf's words
// with the field to outline.
function handStep(extra = {}) {
  return step({ op: "hand", read_id: "rd_1", hand: { words: "Type the word yourself, then tap Done.", ref: "f2" }, ...extra });
}

test("a hand-over step signed by planque is taken like any other step", async () => {
  const p = await planque();
  const ctx = { planqueKey: p.key, chromeId: "chr_1", nonces: new Nonces(() => NOW), now: NOW };
  const signed = async (s) => ({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
  const ok = await checkStep(await signed(handStep()), ctx);
  assert.equal(ok.why, null, "0.2.1 answered every hand-over as malformed (bad_step) here, before the runner saw it");
  assert.equal(ok.step.op, "hand");
  // And held to the same rules as any step.
  assert.equal((await checkStep(await signed(handStep()), ctx)).why, "seen before");
  assert.equal((await checkStep(await signed(handStep({ nonce: "h2", exp: NOW - SKEW_MS - 1 })), ctx)).why, "expired");
  assert.equal((await checkStep(await signed(handStep({ nonce: "h3", sites: [] })), ctx)).why, "no sites");
  const changed = await signed(handStep({ nonce: "h4" }));
  changed.step.hand.words = "Type your password here.";
  assert.equal((await checkStep(changed, ctx)).why, "not signed by planque", "the words changed after signing");
});

// The runner's ops and checkStep's must be the same set: an op the runner runs but checkStep refuses never reaches it
// (0.2.1's "hand"), and one checkStep takes that the runner doesn't know is a bad_step only later.
test("every op the runner runs is an op a signed step may carry", async () => {
  const src = readFileSync(new URL("../src/runner.js", import.meta.url), "utf8");
  const body = src.slice(src.indexOf("async dispatch(run, step)"));
  const ops = [...body.slice(0, body.indexOf("default:")).matchAll(/case "([a-z_]+)":/g)].map((m) => m[1]);
  assert.deepEqual([...ops].sort(), ["act", "close", "hand", "open", "read"]);
  const p = await planque();
  const ctx = { planqueKey: p.key, chromeId: "chr_1", nonces: new Nonces(() => NOW), now: NOW };
  for (const op of ops) {
    const s = step({ nonce: "op-" + op, op });
    assert.equal((await checkStep({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) }, ctx)).why, null, op);
  }
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
async function phoneConfirms(secret, offered, { phone, pairing = "pr_1", name = "Work Mac", chromeId = "chr_1", carried }) {
  const spki = Uint8Array.from(Buffer.from(offered.key, "base64url"));
  assert.equal(await extensionProof(secret, spki), offered.proof, "the phone checks the extension's proof");
  const phoneKey = await pub(phone);
  const chromeHash = await sha256(spki);
  const phoneProof = b64u(await hmac(secret, LABEL.phoneProof, concat(Uint8Array.from(Buffer.from(phoneKey, "base64url")), chromeHash)));
  const body = { pairing, chrome_key_sha256: hex(chromeHash), name, phone_key: phoneKey, phone_proof: phoneProof, ...(carried !== undefined ? { carried } : {}) };
  return { pairing, chrome_id: chromeId, name, phone_key: phoneKey, phone_proof: phoneProof, phone_sig: await sign(phone.privateKey, LABEL.pair, body), ...(carried !== undefined ? { carried } : {}) };
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
async function phoneConfirmsTogether(secret, offered, { phone, kiffHash = KIFF_HASH, tenant = "tenant-1", signedAt = SIGNED_AT, name = "Work Mac", carried }) {
  const answer = await phoneConfirms(secret, offered, { phone, name, carried });
  const spki = Uint8Array.from(Buffer.from(offered.key, "base64url"));
  const body = { pairing: answer.pairing, chrome_key_sha256: hex(await sha256(spki)), name, phone_key: answer.phone_key, phone_proof: answer.phone_proof, ...(carried !== undefined ? { carried } : {}) };
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

const CARRIED = [{ grant: "grt_wiki01", sites: ["wikipedia.org", "wikimedia.org"] }, { grant: "grt_shop02", sites: ["shop.example"] }];

test("a re-pairing's carried connections are kept only as the phone signed them", async () => {
  const secret = "29XMDPQRSTVWXYZ23";
  const ext = await newKeyPair();
  const phone = await newKeyPair();
  const offered = await offer(secret, ext, { version: "0.1.0", manifestHash: "h", browser: "Chrome 131", os: "macOS" });
  const keep = { chromeId: "chr_1", name: "Work Mac", phoneKey: await pub(phone), carried: CARRIED };
  // The pairing-only form and the one-fingerprint form both carry them.
  assert.deepEqual(await checkConfirmation(secret, ext, await phoneConfirms(secret, offered, { phone, carried: CARRIED })), keep);
  assert.deepEqual(await checkConfirmation(secret, ext, await phoneConfirmsTogether(secret, offered, { phone, carried: CARRIED })), keep);
  // No list, none kept: today's pairing.
  assert.deepEqual(await checkConfirmation(secret, ext, await phoneConfirms(secret, offered, { phone })), { chromeId: "chr_1", name: "Work Mac", phoneKey: await pub(phone) });

  // planque relays another list than the one signed: the signature no longer holds.
  const signed = await phoneConfirms(secret, offered, { phone, carried: CARRIED });
  const changes = {
    "a connection added": [...CARRIED, { grant: "grt_bank03", sites: ["bank.example"] }],
    "a site added": [{ grant: "grt_wiki01", sites: ["wikipedia.org", "wikimedia.org", "evil.example"] }, CARRIED[1]],
    "a site changed": [{ grant: "grt_wiki01", sites: ["wikipedia.org", "wikimedia.net"] }, CARRIED[1]],
    "another order": [CARRIED[1], CARRIED[0]],
    "a connection dropped": [CARRIED[0]],
    "the list removed": undefined,
  };
  for (const [why, carried] of Object.entries(changes)) {
    assert.equal(await checkConfirmation(secret, ext, { ...signed, carried }), null, why);
  }
  const without = await phoneConfirms(secret, offered, { phone });
  assert.equal(await checkConfirmation(secret, ext, { ...without, carried: CARRIED }), null, "a list added to an answer signed without one");
  const together = await phoneConfirmsTogether(secret, offered, { phone, carried: CARRIED });
  assert.equal(await checkConfirmation(secret, ext, { ...together, carried: [CARRIED[0]] }), null, "a changed list, one fingerprint");

  // A malformed list is no pairing even when the phone really signed it.
  const many = Array.from({ length: 51 }, (_, i) => ({ grant: "g" + i, sites: ["shop.example"] }));
  const malformed = {
    "not a list": "grt_wiki01",
    "an object, not a list": { grant: "grt_a", sites: ["shop.example"] },
    "a number": 7,
    "null": null,
    "a site over 253 characters": [{ grant: "grt_a", sites: ["a".repeat(250) + ".com"] }],
    "more than 50": many,
    "an extra key": [{ grant: "grt_a", sites: ["shop.example"], note: "x" }],
    "a grant twice": [CARRIED[1], CARRIED[1]],
    "a grant that isn't one": [{ grant: "a b", sites: ["shop.example"] }],
    "an empty grant": [{ grant: "", sites: ["shop.example"] }],
    "no sites": [{ grant: "grt_a", sites: [] }],
    "a public suffix": [{ grant: "grt_a", sites: ["co.uk"] }],
    "a site that isn't one": [{ grant: "grt_a", sites: ["localhost"] }],
    "a site twice": [{ grant: "grt_a", sites: ["shop.example", "shop.example"] }],
    "more than 100 sites": [{ grant: "grt_a", sites: Array.from({ length: 101 }, (_, i) => "s" + i + ".example") }],
    "a site that isn't text": [{ grant: "grt_a", sites: [7] }],
    "an entry that isn't an object": ["grt_a"],
  };
  for (const [why, carried] of Object.entries(malformed)) {
    assert.equal(await checkConfirmation(secret, ext, await phoneConfirms(secret, offered, { phone, carried })), null, why);
  }
  assert.equal(readCarried(many.slice(0, 50)).length, 50, "50 is the most");
  assert.deepEqual(readCarried([{ grant: "grt_a", sites: ["b.example", "a.example"] }]), [{ grant: "grt_a", sites: ["b.example", "a.example"] }], "the order is kept");
});

test("a step for a carried connection runs on the sites the pairing carried, and never beyond them", async () => {
  const phone = await newKeyPair();
  const pairing = { chromeId: "chr_2", phoneKey: await pub(phone), carried: CARRIED };
  const step = (extra) => ({ grant: "grt_wiki01", sites: ["wikipedia.org"], sites_signed: { sites: ["wikipedia.org", "wikimedia.org"], sig: "", carried: true }, ...extra });
  assert.equal(await checkSites(step(), pairing), true, "within the carried sites, with no signature");
  assert.equal(await checkSites(step({ sites: ["wikipedia.org", "wikimedia.org"], sites_signed: undefined }), pairing), true, "the whole list");
  assert.equal(await checkSites(step({ sites: ["wikipedia.org", "evil.example"] }), pairing), false, "a site beyond them");
  assert.equal(await checkSites(step({ grant: "grt_other" }), pairing), false, "a grant that wasn't carried");
  assert.equal(await checkSites(step({ grant: "grt_shop02" }), pairing), false, "another carried grant's sites aren't this one's");
  assert.equal(await checkSites(step(), { ...pairing, carried: undefined }), false, "a pairing that carried nothing");
  assert.equal(await checkSites(step(), { ...pairing, carried: [] }), false, "an empty list");
  assert.equal(await checkSites(step(), { ...pairing, phoneKey: "" }), false, "no phone key");
  // A grant of the pairing's own is signed for this Chrome as ever, and a carried one can be signed anew.
  const fresh = async (grant, sites) => ({ sites, sig: await sign(phone.privateKey, LABEL.sites, { chrome_id: "chr_2", grant, sites }) });
  assert.equal(await checkSites({ grant: "grt_new", sites: ["new.example"], sites_signed: await fresh("grt_new", ["new.example"]) }, pairing), true, "signed for this Chrome");
  assert.equal(await checkSites({ grant: "grt_wiki01", sites: ["extra.example"], sites_signed: await fresh("grt_wiki01", ["extra.example"]) }, pairing), true, "a carried grant signed anew");
  assert.equal(await checkSites({ grant: "grt_new", sites: ["new.example"], sites_signed: { sites: ["new.example"], sig: "", carried: true } }, pairing), false, "carried: true without a carried grant");
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

async function relayWith(p, { runner, onReady, onRunStateMessage } = {}) {
  const ext = await newKeyPair();
  const handled = [];
  let wiped = false;
  const relay = new Relay({
    config: { relayURL: "wss://planque.test/v1/extension", planqueKey: p.key, version: "0.1.0", manifestHash: "h" },
    kept: { keys: ext, pairing: { chromeId: "chr_1" } },
    runner: runner || { active: false, handle: async (s) => (handled.push(s), { page: { read_id: "rd_1", url: "https://shop.example/" } }), stop: async () => {} },
    onUnpaired: () => (wiped = true),
    ...(onReady ? { onReady } : {}),
    ...(onRunStateMessage ? { onRunStateMessage } : {}),
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
  const sent = ws.sent.length;
  await ws.deliver({ type: "step", step: bad, sig: "AAAA" });
  assert.equal(handled.length, 1, "an unsigned step never reaches the runner");
  assert.equal(ws.sent.length, sent, "and gets no answer: nothing signed over its id and nonce");
  relay.close();
});

// The security read: a forged step was answered with a signed bad_step over the id and nonce it chose.
test("a step that doesn't verify gets no signed answer; a verified one the runner refuses keeps its signed refusal", async () => {
  const p = await planque();
  const other = await planque();
  const refusing = { active: false, handle: async () => ({ error: { code: "bad_step", detail: "offset" } }), stop: async () => {} };
  const { relay, ext, ws } = await relayWith(p, { runner: refusing });
  await handshake(p, ws);
  const sent = ws.sent.length;
  const forged = [
    ["another key's signature", step({ nonce: "f1" }), other.keys.privateKey],
    ["another Chrome's", step({ nonce: "f2", chrome_id: "chr_other" }), p.keys.privateKey],
    ["expired", step({ nonce: "f3", exp: NOW - 600_000 }), p.keys.privateKey],
    ["an op the extension doesn't take", step({ nonce: "f4", op: "download" }), p.keys.privateKey],
  ];
  for (const [what, s, key] of forged) {
    await ws.deliver({ type: "step", step: s, sig: await sign(key, LABEL.step, s) });
    assert.equal(ws.sent.length, sent, what + ": no answer");
  }
  await ws.deliver({ type: "step", step: { id: "op_x", nonce: "f5" }, sig: "AAAA" });
  assert.equal(ws.sent.length, sent, "an unsigned message: no answer");
  // A replay of a step already answered: no second answer.
  const once = step({ nonce: "r1", id: "op_r", read_id: "x", offset: -1, op: "read" });
  const sig = await sign(p.keys.privateKey, LABEL.step, once);
  await ws.deliver({ type: "step", step: once, sig });
  assert.equal(ws.sent.length, sent + 1);
  const answer = ws.sent.at(-1);
  assert.equal(answer.ok, false);
  assert.equal(answer.error.code, "bad_step", "the verified step's refusal is said");
  const { sig: asig, type, ...body } = answer; // eslint-disable-line no-unused-vars
  assert.equal(await verify(await pub(ext), LABEL.result, body, asig), true, "and signed");
  await ws.deliver({ type: "step", step: once, sig });
  assert.equal(ws.sent.length, sent + 1, "replayed: no answer");
  relay.close();
});

test("the relay says it is ready only once the welcome proved planque, so a push subscription goes only to planque", async () => {
  const p = await planque();
  let ready = 0;
  const { relay, ws } = await relayWith(p, { onReady: () => ready++ });
  await ws.deliver({ type: "challenge", nonce_p: "np" });
  assert.equal(ready, 0, "not before the welcome");
  await ws.deliver({ type: "welcome", sig: await sign(p.keys.privateKey, LABEL.welcome, { chrome_id: "chr_1", nonce_p: "np", nonce_e: ws.sent.at(-1).nonce_e }) });
  assert.equal(ready, 1);
  const forged = await relayWith(p, { onReady: () => ready++ });
  await forged.ws.deliver({ type: "challenge", nonce_p: "np" });
  const other = await planque();
  await forged.ws.deliver({ type: "welcome", sig: await sign(other.keys.privateKey, LABEL.welcome, { chrome_id: "chr_1", nonce_p: "np", nonce_e: forged.ws.sent.at(-1).nonce_e }) });
  assert.equal(ready, 1, "a welcome that isn't planque's gets nothing");
  relay.close(); // its keepalive timer would hold the test process open
  forged.relay.close();
});

test("an idle relay pings every 20 s to keep the worker up, until planque can wake it by push; a run pings either way", async () => {
  mock.timers.enable({ apis: ["setInterval"] });
  try {
    const p = await planque();
    const runner = { active: false, handle: async () => ({}), stop: async () => {} };
    const { relay, ws } = await relayWith(p, { runner });
    await handshake(p, ws);
    const pings = () => ws.sent.filter((m) => m.type === "ping").length;
    mock.timers.tick(20_000);
    assert.equal(pings(), 1, "idle, no push: kept awake");
    mock.timers.tick(20_000);
    assert.equal(pings(), 2);
    relay.pushed = true; // the subscription went: the push wakes it, so the worker may sleep
    mock.timers.tick(40_000);
    assert.equal(pings(), 2, "idle with a push subscription: no ping");
    runner.active = true;
    mock.timers.tick(20_000);
    assert.equal(pings(), 3, "a run pings whatever the push");
    relay.close();
  } finally {
    mock.timers.reset();
  }
});

test("an idle worker is let sleep only when planque says it keeps the push subscription, and a refusal keeps the ping going", async () => {
  mock.timers.enable({ apis: ["setInterval"] });
  try {
    const p = await planque();
    const runner = { active: false, handle: async () => ({}), stop: async () => {} };
    const { relay, ws } = await relayWith(p, { runner });
    await handshake(p, ws);
    const pings = () => ws.sent.filter((m) => m.type === "ping").length;
    mock.timers.tick(20_000);
    assert.equal(pings(), 1, "no push yet: kept awake");
    await ws.deliver({ type: "push_refused", code: "push_endpoint" });
    mock.timers.tick(20_000);
    assert.equal(pings(), 2, "refused: still kept awake");
    await ws.deliver({ type: "push_kept" });
    assert.equal(relay.pushed, true);
    mock.timers.tick(40_000);
    assert.equal(pings(), 2, "kept: the push wakes it, so no ping");
    await ws.deliver({ type: "push_refused", code: "push_unsigned" });
    mock.timers.tick(20_000);
    assert.equal(pings(), 3, "a later refusal (a new subscription refused) starts it again");
    relay.close();
  } finally {
    mock.timers.reset();
  }
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

test("an answer over the relay's limit is cut to fit and says what was cut; an act that ran is never answered as failed", async () => {
  const p = await planque();
  const links = Array.from({ length: 200 }, (_, i) => ({ ref: "l" + (i + 1), label: "Link " + i, url: "https://shop.example/p/" + i }));
  const big = { active: false, handle: async () => ({ page: { read_id: "rd_1", url: "https://shop.example/", text: "x".repeat(MAX_RESULT_BYTES), links } }), stop: async () => {} };
  const { relay, ext, ws } = await relayWith(p, { runner: big });
  await handshake(p, ws);
  const s = step();
  await ws.deliver({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
  const answer = ws.sent.at(-1);
  assert.equal(answer.type, "result");
  assert.equal(answer.error, null);
  assert.deepEqual(answer.page.truncated, ["text"], "the text first: planque's fingerprint of the read doesn't count it");
  assert.equal(answer.page.links.length, 200, "links are kept while the text alone makes it fit");
  assert.equal(answer.page.more, true);
  assert.equal(answer.page.next_offset, answer.page.text.length);
  assert.equal(answer.page.read_id, "rd_1");
  assert.ok(new TextEncoder().encode(JSON.stringify(answer)).length <= MAX_RESULT_BYTES);
  const { sig, type, ...body } = answer;
  assert.equal(await verify(await pub(ext), LABEL.result, body, sig), true);
  relay.close();
});

test("a read whose page fits no way is page_too_large, signed: nothing happened, so trying again repeats nothing", async () => {
  const p = await planque();
  // Fields are never cut (an act names them): a page whose fields alone are over the limit can't be sent.
  const fields = Array.from({ length: 200 }, (_, i) => ({ ref: "f" + (i + 1), label: "y".repeat(800), kind: "text" }));
  const big = { active: false, handle: async () => ({ page: { read_id: "rd_1", url: "https://shop.example/", text: "x", fields } }), stop: async () => {} };
  const { relay, ext, ws } = await relayWith(p, { runner: big });
  await handshake(p, ws);
  const s = step();
  await ws.deliver({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
  const answer = ws.sent.at(-1);
  assert.equal(answer.error.code, "page_too_large");
  assert.ok(JSON.stringify(answer).length < MAX_RESULT_BYTES);
  const { sig, type, ...body } = answer;
  assert.equal(await verify(await pub(ext), LABEL.result, body, sig), true);
  relay.close();
});

test("an act or a hand-over that ran, whose page fits no way, is answered done with page_dropped, never as a failure", async () => {
  // A taf told its click failed may click Submit again: the step's effect happened, so the answer says so, and the taf reads
  // again (that read may well be page_too_large, honestly: nothing happened there).
  const fields = Array.from({ length: 200 }, (_, i) => ({ ref: "f" + (i + 1), label: "y".repeat(800), kind: "text" }));
  for (const op of ["act", "hand"]) {
    const p = await planque();
    const big = { active: false, handle: async () => ({ page: { read_id: "rd_2", url: "https://shop.example/grown", text: "x", fields } }), stop: async () => {} };
    const { relay, ext, ws } = await relayWith(p, { runner: big });
    await handshake(p, ws);
    const s = step({ op });
    await ws.deliver({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
    const answer = ws.sent.at(-1);
    assert.equal(answer.ok, true, op);
    assert.equal(answer.error, null, op);
    assert.equal(answer.page, null, op);
    assert.equal(answer.page_dropped, "page_too_large", op);
    assert.ok(JSON.stringify(answer).length < MAX_RESULT_BYTES, op);
    const { sig, type, ...body } = answer;
    assert.equal(await verify(await pub(ext), LABEL.result, body, sig), true, op + ": page_dropped is in what's signed");
    relay.close();
  }
});

test("an act the runner refused is answered with its refusal, whatever its size; only a step that ran gets page_dropped", async () => {
  const p = await planque();
  const refused = { active: false, handle: async () => ({ error: { code: "site_not_listed", detail: "not one of this grant's sites" } }), stop: async () => {} };
  const { relay, ws } = await relayWith(p, { runner: refused });
  await handshake(p, ws);
  const s = step({ op: "act" });
  await ws.deliver({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
  const answer = ws.sent.at(-1);
  assert.equal(answer.ok, false);
  assert.equal(answer.error.code, "site_not_listed");
  assert.equal("page_dropped" in answer, false);
  relay.close();
});

test("a page is cut in order: text to 4,000, options past 10, links past 50, then the rest of the text and the links", () => {
  const page = {
    read_id: "rd_1",
    offset: 20000,
    text: "a".repeat(3999) + "\u{1F600}" + "b".repeat(100),
    fields: [{ ref: "f1", kind: "select", options: Array.from({ length: 30 }, (_, i) => "o" + i) }, { ref: "f2", kind: "text" }],
    controls: [{ ref: "c1", label: "Go" }],
    links: Array.from({ length: 80 }, (_, i) => ({ ref: "l" + i, label: "L", url: "https://x.example/" + i })),
  };
  const cuts = cutPages(page);
  assert.deepEqual(cuts.map((c) => c.truncated), [["text"], ["text", "options"], ["text", "options", "links"], ["text", "options", "links"], ["text", "options", "links"]]);
  assert.equal(cuts[0].text.length, 3999, "never inside an emoji");
  assert.equal(cuts[0].next_offset, 20000 + 3999);
  assert.equal(cuts[1].fields[0].options.length, 10);
  assert.equal(cuts[2].links.length, 50);
  assert.equal(cuts[3].text, "");
  assert.deepEqual(cuts[4].links, []);
  for (const c of cuts) assert.deepEqual(c.controls, page.controls, "controls are never cut");
  assert.equal(page.links.length, 80, "the page itself is left as it was");
  assert.deepEqual(cutPages({ read_id: "rd_2", text: "short" }).map((c) => c.truncated), [["text"]]);
});

test("a signed hand-over step reaches the runner over the relay and is answered handed, signed", async () => {
  const p = await planque();
  const ops = [];
  const runner = { active: false, handle: async (s) => (ops.push(s.op), s.op === "hand" ? { handed: true } : { error: { code: "bad_step" } }), stop: async () => {} };
  const { relay, ext, ws } = await relayWith(p, { runner });
  try {
    await handshake(p, ws);
    const s = handStep();
    await ws.deliver({ type: "step", step: s, sig: await sign(p.keys.privateKey, LABEL.step, s) });
    assert.deepEqual(ops, ["hand"], "the step reached the runner");
    const answer = ws.sent.at(-1);
    assert.equal(answer.type, "result");
    assert.equal(answer.ok, true);
    assert.equal(answer.handed, true);
    assert.equal(answer.error, null);
    const { sig, type, ...body } = answer;
    assert.equal(await verify(await pub(ext), LABEL.result, body, sig), true);
  } finally {
    relay.close();
  }
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

test("the relay keeps this Chrome's live connections from the welcome and each change, display only, malformed left out", async () => {
  const p = await planque();
  let changes = 0;
  const { relay, ws } = await relayWith(p);
  relay.onChange = () => changes++;
  await ws.deliver({ type: "challenge", nonce_p: "np" });
  const h = ws.sent.at(-1);
  const one = { grant: "g1", agent: "agt_wiki", name: "Wiki lookup", sites: ["wikipedia.org"] };
  await ws.deliver({ type: "welcome", sig: await sign(p.keys.privateKey, LABEL.welcome, { chrome_id: "chr_1", nonce_p: "np", nonce_e: h.nonce_e }), connections: [one, { grant: "g2" }, null, { ...one, agent: "" }] });
  assert.deepEqual(relay.connections, [one], "only well-formed ones");
  await ws.deliver({ type: "connections", connections: [] });
  assert.deepEqual(relay.connections, []);
  assert.ok(changes >= 2, "the panel hears each change");
  assert.deepEqual(readConnections("x"), []);
  assert.deepEqual(readConnections([{ ...one, sites: ["a.example", 7] }])[0].sites, ["a.example"]);
  relay.close();
});

test("planque's answer to a run's state goes to the run states, and is never taken for a step", async () => {
  const p = await planque();
  const heard = [];
  const { relay, ws, handled } = await relayWith(p, { onRunStateMessage: (m) => heard.push(m) });
  await handshake(p, ws);
  await ws.deliver({ type: "run_state_kept", run: "run_1" });
  await ws.deliver({ type: "run_state_refused", run: "run_1", code: "run_stopped" });
  await new Promise((done) => setTimeout(done, 0));
  assert.deepEqual(heard.map((m) => [m.type, m.code]), [["run_state_kept", undefined], ["run_state_refused", "run_stopped"]]);
  assert.equal(handled.length, 0);
  relay.close(); // its keepalive timer would hold the test process open
});
