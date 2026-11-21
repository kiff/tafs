import { test } from "node:test";
import assert from "node:assert/strict";
import { kiffOffer, claimProof, claim, rawToDERBase64, OFFER_LIFETIME_MS } from "../src/kiff.js";
import { newKeyPair, publicSPKI, b64u, sha256 } from "../src/crypto.js";
import { hex } from "../src/pairing.js";

const NOW = Date.parse("2026-11-21T12:00:00Z");

test("the KIFF offer is a view-only browser extension, as KIFF's QR text", async () => {
  const k = await newKeyPair();
  const o = await kiffOffer(k, { name: "tafs for Chrome", now: NOW });
  const f = JSON.parse(o.text);
  assert.deepEqual(Object.keys(f), ["exp", "key_store", "kind", "name", "nonce", "platform", "pub", "v"]);
  assert.equal(f.v, "kiff.pair.v1");
  assert.equal(f.platform, "chrome");
  assert.equal(f.kind, "extension");
  assert.equal(f.key_store, "software");
  assert.equal(f.approval_pub, undefined, "no approval key: it never approves");
  assert.equal(f.pub, b64u(await publicSPKI(k.publicKey)));
  assert.equal(Buffer.from(f.nonce, "base64url").length, 32);
  assert.equal(Date.parse(f.exp) - NOW, OFFER_LIFETIME_MS);
  assert.equal(o.claimMessage, ["kiff.pair.claim.v1", f.nonce, hex(await sha256(await publicSPKI(k.publicKey)))].join("\n"));
});

test("the claim's proof is a DER signature over the claim message, as KIFF reads it", async () => {
  const k = await newKeyPair();
  const o = await kiffOffer(k, { name: "x", now: NOW });
  const der = Buffer.from(await claimProof(k.privateKey, o.claimMessage), "base64");
  assert.equal(der[0], 0x30);
  const { createVerify, createPublicKey } = await import("node:crypto");
  const key = createPublicKey({ key: Buffer.from(await publicSPKI(k.publicKey)), format: "der", type: "spki" });
  assert.equal(createVerify("SHA256").update(o.claimMessage).verify(key, der), true);
  assert.match(rawToDERBase64(new Uint8Array(64).fill(0x80)), /^[A-Za-z0-9+/]+=*$/);
});

test("a claim waits for the phone, and a session that could approve is ended, never kept", async () => {
  const calls = [];
  const answer = (status, body) => async (url, init) => (calls.push([url, init.headers.authorization]), { ok: status < 300, status, json: async () => body });
  assert.deepEqual(await claim({ fetch: answer(400, { error: "authorization_pending" }), base: "https://kiff.test", nonce: "n", pub: "p", proof: "x" }), { pending: true });
  assert.deepEqual(await claim({ fetch: answer(400, { error: "expired_token" }), base: "https://kiff.test", nonce: "n", pub: "p", proof: "x" }), { error: "expired" });
  const view = await claim({ fetch: answer(200, { access_token: "kiff_v", tenant: "t_01", subject: "u_1", approval_mode: "view", expires_in: 3600 }), base: "https://kiff.test", nonce: "n", pub: "p", proof: "x" });
  assert.equal(view.token, "kiff_v");
  calls.length = 0;
  const keyed = await claim({ fetch: answer(200, { access_token: "kiff_k", approval_mode: "key" }), base: "https://kiff.test", nonce: "n", pub: "p", proof: "x" });
  assert.deepEqual(keyed, { error: "not_view_only" });
  assert.deepEqual(calls.at(-1), ["https://kiff.test/v1/auth/device/logout", "Bearer kiff_k"]);
});
