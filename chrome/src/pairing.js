// Pairing this Chrome with the owner's phone (docs/chrome.md, "Pairing"). It starts on the phone, which shows a code: four
// characters that find the pairing, and six that are a secret planque never sees. The owner types the code here. The
// extension proves its key under the secret; the phone checks that proof, then signs, and proves its own key under the same
// secret. So neither planque nor anyone between can put another key in either place.

import { LABEL, b64u, fromB64u, concat, hmac, sha256, sameBytes, verify, publicSPKI } from "./crypto.js";

/** Lowercase hex, as `chrome_key_sha256` is written. */
export function hex(data) {
  return Array.from(data, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** The code's characters: no 0, 1, I, L, O or U, so a code read aloud or by eye is typed right. */
export const ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

/** The code as typed ("k7qf-29xmdp", "K7QF 29XMDP") as `{lookup, secret}`, or null when it isn't one. */
export function parseCode(text) {
  const s = String(text || "").toUpperCase().replace(/[\s-]/g, "");
  if (s.length !== 10 || [...s].some((c) => !ALPHABET.includes(c))) return null;
  return { lookup: s.slice(0, 4), secret: s.slice(4) };
}

/** The extension's proof of its key under the secret: HMAC(secret, ext label ‖ key). */
export async function extensionProof(secret, spki) {
  return b64u(await hmac(secret, LABEL.extProof, spki));
}

/** What the extension sends planque for the pairing `lookup` names. */
export async function offer(secret, keyPair, { version, manifestHash, browser, os }) {
  const spki = await publicSPKI(keyPair.publicKey);
  return {
    key: b64u(spki),
    version,
    manifest_hash: manifestHash,
    browser,
    os,
    proof: await extensionProof(secret, spki),
  };
}

/**
 * Whether the phone's answer to our offer is the paired phone's, for our key, under the code's secret. The answer is
 * `{pairing, chrome_id, name, phone_key, phone_proof, phone_sig}`; on success, `{chromeId, name, phoneKey}`.
 */
export async function checkConfirmation(secret, keyPair, answer) {
  if (!answer || typeof answer !== "object") return null;
  const { pairing, chrome_id: chromeId, name, phone_key: phoneKey, phone_proof: phoneProof, phone_sig: phoneSig } = answer;
  if (![pairing, chromeId, name, phoneKey, phoneProof, phoneSig].every((v) => typeof v === "string" && v)) return null;
  const spki = await publicSPKI(keyPair.publicKey);
  const ours = await sha256(spki);
  let expected;
  try {
    expected = await hmac(secret, LABEL.phoneProof, concat(fromB64u(phoneKey), ours));
    if (!sameBytes(expected, fromB64u(phoneProof))) return null;
  } catch {
    return null;
  }
  const body = { pairing, chrome_key_sha256: hex(ours), name, phone_key: phoneKey, phone_proof: phoneProof };
  if (!(await verify(phoneKey, LABEL.pair, body, phoneSig))) return null;
  return { chromeId, name, phoneKey };
}
