// The extension's signatures and proofs (docs/chrome.md, "Crypto"). Every key is ECDSA P-256 with SHA-256. A signature is
// over a label, a zero byte, then the body's canonical JSON; the labels differ per direction and purpose, so a signature
// made for one message can never be replayed as another. Signatures travel as base64url of the raw 64-byte r‖s
// (WebCrypto's form); a phone's DER signature (iOS's and Android's keystores write DER) is read as well.

import { canonical } from "./jcs.js";

export const LABEL = Object.freeze({
  pair: "tafs-chrome/pair/v1", // the phone confirms a pairing
  hello: "tafs-chrome/hello/v1", // the extension proves itself on connect
  welcome: "tafs-chrome/welcome/v1", // planque proves itself on connect
  step: "tafs-chrome/step/v1", // planque sends a step
  result: "tafs-chrome/result/v1", // the extension answers a step
  release: "tafs-chrome/release/v1", // the phone releases one sealed value for one fill
  unpair: "tafs-chrome/unpair/v1", // the extension unpairs itself
  sensitive: "tafs-chrome/sensitive/v1", // the phone allows one sensitive site for one grant
  sites: "tafs-chrome/sites/v1", // the phone signs a grant's sites when the owner approves it
  hand: "tafs-chrome/hand/v1", // the extension signs the person's answer to a hand-over, for planque to give the taf
  runState: "tafs-chrome/run_state/v1", // the extension signs a run's Pause, Resume or Stop, for planque to give the waiting taf
  push: "tafs-chrome/push/v1", // the extension signs the push subscription it gives planque, to wake it
  screenshot: "tafs-chrome/screenshot/v1", // the extension signs the picture it keeps of an act, for planque to store against that step
  extProof: "tafs-chrome/ext/v1", // HMAC: the extension's key, under the pairing code's secret
  extOneTouch: "tafs-chrome/ext-one-touch/v1", // HMAC: the same, under another label: this extension checks one fingerprint for the pairing and KIFF's request
  phoneProof: "tafs-chrome/phone/v1", // HMAC: the phone's key and the extension's, under the same secret
});

const ECDSA = { name: "ECDSA", namedCurve: "P-256" };
const SIGN = { name: "ECDSA", hash: "SHA-256" };
const enc = new TextEncoder();

export function bytes(s) {
  return enc.encode(s);
}

export function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

/** What is signed for `body` under `label`: label ‖ 0x00 ‖ JCS(body). */
export function signingBytes(label, body) {
  return concat(bytes(label), new Uint8Array([0]), bytes(canonical(body)));
}

export function b64u(data) {
  let s = "";
  for (const b of data) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromB64u(text) {
  if (typeof text !== "string" || !/^[A-Za-z0-9_-]*$/.test(text)) throw new TypeError("not base64url");
  const s = atob(text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

/** A new key pair for this extension. The private key can't be exported: it stays in this profile's IndexedDB. */
export async function newKeyPair() {
  return crypto.subtle.generateKey(ECDSA, false, ["sign", "verify"]);
}

export async function publicSPKI(publicKey) {
  return new Uint8Array(await crypto.subtle.exportKey("spki", publicKey));
}

export async function importPublic(spkiB64u) {
  return crypto.subtle.importKey("spki", fromB64u(spkiB64u), ECDSA, false, ["verify"]);
}

export async function sha256(data) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data));
}

/**
 * A released build's name for planque's allowlist: the SHA-256 of `manifest.json`'s bytes as 64 lower-case hex characters,
 * which is how planque's `chrome-builds.txt` writes it and how it looks a build up (it compares the text as sent).
 */
export async function manifestHashOf(bytes) {
  return [...(await sha256(bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sign(privateKey, label, body) {
  return b64u(new Uint8Array(await crypto.subtle.sign(SIGN, privateKey, signingBytes(label, body))));
}

/** Whether `sig` is `publicKey`'s signature of `body` under `label`. Anything malformed is false, never an error. */
export async function verify(publicKey, label, body, sig) {
  return verifyBytes(publicKey, signingBytes(label, body), sig);
}

/**
 * What the phone's approval key signs, once, to confirm two requests under one fingerprint (`kiff.requests.v1`, the same
 * lines KIFF and planque build): the tenant, `signed_at`, and the SHA-256 of each request's message as lower-case hex, in
 * sorted order, so either service builds the same bytes.
 */
export function requestsMessage(tenant, signedAt, oneSHA256, otherSHA256) {
  const [a, b] = oneSHA256 <= otherSHA256 ? [oneSHA256, otherSHA256] : [otherSHA256, oneSHA256];
  return bytes(["kiff.requests.v1", tenant, signedAt, a, b].join("\n"));
}

/** Whether `sig` is `publicKey`'s signature of exactly `data`. Anything malformed is false, never an error. */
export async function verifyBytes(publicKey, data, sig) {
  try {
    const key = typeof publicKey === "string" ? await importPublic(publicKey) : publicKey;
    const given = fromB64u(sig);
    // r‖s first, as the contract sends it; a DER signature (a phone's keystore) after, which can also be 64 bytes long.
    if (given.length === 64 && (await crypto.subtle.verify(SIGN, key, given, data))) return true;
    const raw = derToRaw(given);
    return !!raw && (await crypto.subtle.verify(SIGN, key, raw, data));
  } catch {
    return false;
  }
}

/** An X9.62 DER ECDSA signature as WebCrypto's raw r‖s, or null when it isn't one. */
export function derToRaw(der) {
  if (der.length < 8 || der[0] !== 0x30) return null;
  let at = 2;
  if (der[1] & 0x80) at = 2 + (der[1] & 0x7f);
  const ints = [];
  for (let i = 0; i < 2; i++) {
    if (der[at] !== 0x02) return null;
    const len = der[at + 1];
    let v = der.slice(at + 2, at + 2 + len);
    at += 2 + len;
    while (v.length > 32 && v[0] === 0) v = v.slice(1);
    if (v.length > 32) return null;
    const padded = new Uint8Array(32);
    padded.set(v, 32 - v.length);
    ints.push(padded);
  }
  return at === der.length ? concat(ints[0], ints[1]) : null;
}

/** HMAC-SHA256 of label ‖ 0x00 ‖ data under `secret` (the pairing code's secret, as typed). */
export async function hmac(secret, label, data) {
  const key = await crypto.subtle.importKey("raw", bytes(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, concat(bytes(label), new Uint8Array([0]), data)));
}

/** Compares two byte strings in time that doesn't depend on where they differ. */
export function sameBytes(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

export function randomId(prefix = "") {
  return prefix + b64u(crypto.getRandomValues(new Uint8Array(16)));
}
