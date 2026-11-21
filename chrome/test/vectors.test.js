// vectors/chrome-pairing.json: the pairing's proofs and signed bytes, which the phones' tests read too
// (ios/TafsTests/ChromePairingTests.swift). A change to how either side computes them fails here or there.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { LABEL, b64u, fromB64u, hmac, sha256, concat, signingBytes } from "../src/crypto.js";
import { extensionProof, hex } from "../src/pairing.js";

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
});
