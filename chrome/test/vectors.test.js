// vectors/chrome-pairing.json: the pairing's proofs and signed bytes, which the phones' tests read too
// (ios/TafsTests/ChromePairingTests.swift). A change to how either side computes them fails here or there.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { LABEL, b64u, fromB64u, hmac, sha256, concat, signingBytes } from "../src/crypto.js";
import { extensionProof, oneTouchProof, hex } from "../src/pairing.js";

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
