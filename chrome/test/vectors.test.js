// vectors/chrome-pairing.json: the pairing's proofs and signed bytes, which the phones' tests read too
// (ios/TafsTests/ChromePairingTests.swift). A change to how either side computes them fails here or there.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { LABEL, b64u, fromB64u, hmac, sha256, concat, signingBytes, importPublic, verify, newKeyPair } from "../src/crypto.js";
import { checkStep, Nonces, result } from "../src/protocol.js";
import { handMessage } from "../src/hand.js";
import { extensionProof, oneTouchProof, readCarried, hex } from "../src/pairing.js";

const v = JSON.parse(readFileSync(new URL("../../vectors/chrome-pairing.json", import.meta.url), "utf8"));

test("the extension's proof, the phone's proof and the signed bytes are the vectors'", async () => {
  const chromeKey = fromB64u(v.chrome_key);
  const phoneKey = fromB64u(v.phone_key);
  assert.equal(await extensionProof(v.secret, chromeKey), v.extension_proof);
  const hash = await sha256(chromeKey);
  assert.equal(hex(hash), v.chrome_key_sha256);
  assert.equal(b64u(await hmac(v.secret, LABEL.phoneProof, concat(phoneKey, hash))), v.phone_proof);
  const body = { pairing: v.pairing, chrome_key_sha256: v.chrome_key_sha256, name: v.name, phone_key: v.phone_key, phone_proof: v.phone_proof };
  assert.equal(b64u(signingBytes(LABEL.pair, body)), v.signed_bytes);
  // A re-pairing's carried connections are inside what the phone signs, in the order it wrote them (written apart in Python).
  assert.deepEqual(readCarried(v.carried), v.carried);
  assert.equal(b64u(signingBytes(LABEL.pair, { ...body, carried: v.carried })), v.signed_bytes_with_carried);
  // With the KIFF device's offer, the proof covers it too, as the phones check it.
  assert.equal(await extensionProof(v.secret, chromeKey, v.kiff_offer), v.extension_proof_with_kiff_offer);
  assert.equal(await oneTouchProof(v.secret, chromeKey, v.kiff_offer), v.one_touch_proof_with_kiff_offer, "the proof of the one-fingerprint flag, written apart in Python");
  assert.notEqual(v.one_touch_proof_with_kiff_offer, v.extension_proof_with_kiff_offer);
});

// vectors/chrome-grant.json and chrome-sensitive.json: what the phones sign when they approve a browser grant, and the
// sensitive list they mark on the approval card. ios/TafsTests/ChromePairingTests.swift reads the same files.
const g = JSON.parse(readFileSync(new URL("../../vectors/chrome-grant.json", import.meta.url), "utf8"));
const s = JSON.parse(readFileSync(new URL("../../vectors/chrome-sensitive.json", import.meta.url), "utf8"));

test("a grant's sites and a sensitive allowance sign the vectors' bytes", () => {
  assert.equal(b64u(signingBytes(LABEL.sites, { chrome_id: g.chrome_id, grant: g.grant, sites: g.sites })), g.sites_signed_bytes);
  assert.equal(b64u(signingBytes(LABEL.sensitive, { chrome_id: g.chrome_id, grant: g.grant, site: g.site })), g.sensitive_signed_bytes);
});

test("the sensitive list is the vectors' list, and reads each sample host the same", async () => {
  const { SENSITIVE, sensitive } = await import("../src/sites.js");
  assert.deepEqual(SENSITIVE.sites, s.sites);
  assert.equal(SENSITIVE.labels.source, s.labels);
  for (const [host, want] of Object.entries(s.hosts)) assert.equal(sensitive(host), want, host);
});

// vectors/chrome-release.json: the phone's release of one sealed value for one fill (docs/chrome.md, "The phone's release card").
// The phones' tests build the same bytes and read the same handles; planque's half must match it.
const r = JSON.parse(readFileSync(new URL("../../vectors/chrome-release.json", import.meta.url), "utf8"));

test("a release signs the vector's bytes, takes only the vector's handles, and holds only as the extension checks it", async () => {
  const { checkRelease } = await import("../src/protocol.js");
  const { parseHandle } = await import("../src/protocol.js");
  const { sign, newKeyPair, publicSPKI } = await import("../src/crypto.js");
  const body = { chrome_id: r.chrome_id, op: r.op, sealed: r.sealed, origin: r.origin, field_ref: r.field_ref, read_id: r.read_id, nonce: r.nonce, exp: r.exp };
  assert.equal(b64u(signingBytes(LABEL.release, body)), r.signed_bytes);
  for (const [h, part] of Object.entries(r.handles)) assert.equal(parseHandle(h)?.part ?? null, part, JSON.stringify(h));
  // A release made over these fields by the paired phone holds; any field changed, another Chrome's, or a late one, doesn't.
  const phone = await newKeyPair();
  const phoneKey = b64u(await publicSPKI(phone.publicKey));
  const release = { chrome_id: r.chrome_id, op: r.op, origin: r.origin, nonce: r.nonce, exp: r.exp, sig: await sign(phone.privateKey, LABEL.release, body) };
  const step = { id: r.op, read_id: r.read_id, act: { kind: "fill", ref: r.field_ref, sealed: r.sealed } };
  const at = { phoneKey, chromeId: r.chrome_id, step, origin: r.origin, now: r.exp - 30_000 };
  assert.equal(await checkRelease(release, at), true);
  assert.equal(await checkRelease(release, { ...at, origin: "https://other.example" }), false, "another origin");
  assert.equal(await checkRelease(release, { ...at, chromeId: "chr_other" }), false, "another Chrome");
  assert.equal(await checkRelease(release, { ...at, step: { ...step, act: { ...step.act, sealed: "sec_Ab3dE-f_9q:username" } } }), false, "the other part");
  assert.equal(await checkRelease(release, { ...at, step: { ...step, act: { ...step.act, ref: "f3" } } }), false, "another field");
  assert.equal(await checkRelease(release, { ...at, step: { ...step, read_id: "rd_8" } }), false, "another read");
  assert.equal(await checkRelease({ ...release, nonce: "AAECAwQFBgcICQoLDA0OEA" }, at), false, "another nonce");
  assert.equal(await checkRelease(release, { ...at, now: r.exp + 1 }), false, "late");
});

// vectors/chrome-relay.json: planque's signed steps and the extension's signed answers, each with the exact bytes signed.
// planque reads the same file (its testdata/chrome-relay.json), so a field one side signs and the other doesn't, or a
// difference in how a body is put into bytes, fails on one side or the other before any key is involved.
const relay = JSON.parse(readFileSync(new URL("../../vectors/chrome-relay.json", import.meta.url), "utf8"));

test("each relay message's signed bytes are the extension's own, and its signature verifies under the key named", async () => {
  const planqueKey = await importPublic(relay.planque_key);
  const extensionKey = await importPublic(relay.extension_key);
  for (const [list, key] of [[relay.steps, planqueKey], [relay.results, extensionKey], [relay.hand_results, extensionKey]]) {
    for (const m of list) {
      assert.equal(b64u(signingBytes(m.label, m.body)), m.signing_bytes, m.name);
      assert.equal(await verify(key, m.label, m.body, m.sig), true, m.name);
      // A step travels as {type: "step", step, sig}; an answer as {type, ...body, sig}: the wire carries exactly the signed body.
      const { type, sig, ...rest } = m.wire;
      assert.deepEqual(type === "step" ? rest.step : rest, m.body, m.name);
      assert.equal(sig, m.sig, m.name);
    }
  }
});

test("every step planque signs is taken by checkStep, and its ops are exactly the runner's", async () => {
  for (const m of relay.steps) {
    const checked = await checkStep(m.wire, { planqueKey: relay.planque_key, chromeId: relay.chrome_id, nonces: new Nonces(() => relay.now), now: relay.now });
    assert.equal(checked.why, null, m.name);
  }
  const src = readFileSync(new URL("../src/runner.js", import.meta.url), "utf8");
  const body = src.slice(src.indexOf("async dispatch(run, step)"));
  const ops = [...body.slice(0, body.indexOf("default:")).matchAll(/case "([a-z_]+)":/g)].map((x) => x[1]);
  assert.deepEqual(relay.steps.map((m) => m.body.op).sort(), [...ops].sort());
});

test("the extension's answers sign exactly the vector's bodies: a page, a refusal, handed, and the person's Done and Not now", async () => {
  const key = await newKeyPair(); // any key: the bytes signed are what's compared
  const byId = Object.fromEntries(relay.steps.map((m) => [m.body.id, m.body]));
  for (const m of relay.results) {
    const step = byId[m.body.id];
    const out = m.body.handed ? { handed: true } : m.body.error ? { error: m.body.error } : { page: m.body.page };
    const { type, sig, ...body } = await result(key.privateKey, { id: step.id, nonce: step.nonce }, relay.chrome_id, out);
    assert.equal(type, "result", m.name);
    assert.equal(b64u(signingBytes(LABEL.result, body)), m.signing_bytes, m.name);
    assert.equal(await verify(key.publicKey, LABEL.result, body, sig), true, m.name);
  }
  for (const m of relay.hand_results) {
    const { type, sig, ...body } = await handMessage(key.privateKey, m.body);
    assert.equal(type, "hand_result", m.name);
    assert.equal(b64u(signingBytes(LABEL.hand, body)), m.signing_bytes, m.name);
    assert.equal(await verify(key.publicKey, LABEL.hand, body, sig), true, m.name);
  }
});
