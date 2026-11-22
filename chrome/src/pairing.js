// Pairing this Chrome with the owner's phone (docs/chrome.md, "Pairing"). It starts on the phone, which shows a code: four
// characters that find the pairing, and 17 that are a secret planque never sees. The owner types the code here. The
// extension proves its key under the secret; the phone checks that proof, then signs, and proves its own key under the same
// secret. So neither planque nor anyone between can put another key in either place.

import { LABEL, b64u, fromB64u, concat, hmac, sha256, sameBytes, verify, verifyBytes, requestsMessage, signingBytes, publicSPKI } from "./crypto.js";

/** Lowercase hex, as `chrome_key_sha256` is written. */
export function hex(data) {
  return Array.from(data, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** The code's characters: no 0, 1, I, L, O or U, so a code read aloud or by eye is typed right. */
export const ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

/** The secret's length: 17 characters of 30, about 83 bits, so a proof planque stores can't be tried offline. */
export const SECRET_LENGTH = 17;
export const LOOKUP_LENGTH = 4;

/**
 * The code as typed ("K7QF-29XM-DPQR-STVW-XYZ2-3", spaces or dashes anywhere, any case) as `{lookup, secret}`: the first
 * four characters find the pairing, the other 17 are the secret. Null when it isn't one.
 */
export function parseCode(text) {
  const s = String(text || "").toUpperCase().replace(/[\s-]/g, "");
  if (s.length !== LOOKUP_LENGTH + SECRET_LENGTH || [...s].some((c) => !ALPHABET.includes(c))) return null;
  return { lookup: s.slice(0, LOOKUP_LENGTH), secret: s.slice(LOOKUP_LENGTH) };
}

/**
 * The extension's proof of its key, and of its KIFF offer when it sends one, under the secret:
 * HMAC(secret, ext label ‖ key ‖ the KIFF offer's text). So planque can swap neither key.
 */
export async function extensionProof(secret, spki, kiffOffer = "") {
  return b64u(await hmac(secret, LABEL.extProof, concat(spki, new TextEncoder().encode(kiffOffer))));
}

/**
 * The proof that this extension checks one fingerprint for the pairing and KIFF's request (`one_touch`): the same HMAC as
 * `extensionProof`, under its own label, so the flag can't be added to an offer by anyone without the code's secret, and a
 * phone that doesn't know it never reads it. A phone sends the combined signature only when this verifies, else today's.
 */
export async function oneTouchProof(secret, spki, kiffOffer = "") {
  return b64u(await hmac(secret, LABEL.extOneTouch, concat(spki, new TextEncoder().encode(kiffOffer))));
}

/**
 * What the extension sends planque for the pairing `lookup` names, with its KIFF device's offer (`kiff_offer`), and the id
 * it keeps for this Chrome profile (`profile_id`, docs/chrome.md "Your Chrome"). The id is a hint the phone uses to
 * pre-select which Chrome a re-pairing replaces; it isn't covered by the proof and authenticates nothing, and the owner's
 * approval is what replaces.
 */
export async function offer(secret, keyPair, { version, manifestHash, browser, os, kiffOffer = "", profileId = "" }) {
  const spki = await publicSPKI(keyPair.publicKey);
  return {
    key: b64u(spki),
    version,
    manifest_hash: manifestHash,
    browser,
    os,
    ...(profileId ? { profile_id: profileId } : {}),
    one_touch: true,
    one_touch_proof: await oneTouchProof(secret, spki, kiffOffer),
    ...(kiffOffer ? { kiff_offer: kiffOffer } : {}),
    proof: await extensionProof(secret, spki, kiffOffer),
  };
}

/** The offer's fields that planque added to its pairing POST later: a planque that doesn't know them refuses the body. */
const NEWER_FIELDS = ["profile_id", "one_touch", "one_touch_proof"];

/**
 * POST the offer to `url`. A planque that predates `profile_id` and the one-fingerprint flag reads its body strictly and
 * answers 400 "unknown field"; the offer is then sent once more without them, which only costs the phone its hints (it
 * pairs the old way), so an extension is never kept from pairing by a field its planque doesn't take. Any other answer,
 * a 400 for something else included, is returned as it is. `fetchFn` is the browser's `fetch`.
 */
export async function postOffer(fetchFn, url, body) {
  const send = (b) => fetchFn(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(b) });
  const sent = await send(body);
  if (sent.status !== 400 || !NEWER_FIELDS.some((k) => k in body)) return sent;
  const said = await sent.clone().text().catch(() => "");
  if (!/unknown field/i.test(said)) return sent;
  const older = Object.fromEntries(Object.entries(body).filter(([k]) => !NEWER_FIELDS.includes(k)));
  return send(older);
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
  const together = TOGETHER.filter((k) => answer[k] !== undefined);
  if (together.length === 0) {
    if (!(await verify(phoneKey, LABEL.pair, body, phoneSig))) return null;
  } else if (!(await verifyTogether(phoneKey, body, phoneSig, answer))) {
    return null;
  }
  return { chromeId, name, phoneKey };
}

/** The fields an answer carries when the phone signed the pairing and KIFF's view-only device with one fingerprint. */
const TOGETHER = ["tenant", "signed_at", "kiff_request_sha256"];

/**
 * One fingerprint, two requests (`kiff.requests.v1`, docs/chrome.md "Your Chrome"): `phone_sig` is the phone's approval-key
 * signature over the tenant, `signed_at` and the sorted SHA-256 of this pairing's message (the label, a zero byte and the
 * body as we rebuilt it, the bytes the one-signature form has always been over) and of KIFF's request. We rebuild ours
 * and take KIFF's hash as given, as KIFF and planque each do for theirs. All three fields or none: a part of them is
 * refused, as is a request paired with itself, and a field that could shift a line of the message.
 */
async function verifyTogether(phoneKey, body, phoneSig, answer) {
  const { tenant, signed_at: signedAt, kiff_request_sha256: other } = answer;
  const line = (v) => typeof v === "string" && v !== "" && !/[\r\n]/.test(v);
  if (!line(tenant) || !line(signedAt) || typeof other !== "string" || !/^[0-9a-f]{64}$/.test(other)) return false;
  const own = hex(await sha256(signingBytes(LABEL.pair, body)));
  if (own === other) return false;
  return verifyBytes(phoneKey, requestsMessage(tenant, signedAt, own, other), phoneSig);
}
