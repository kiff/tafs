// The extension as a view-only KIFF device of the account (docs/chrome.md, "Coach in the side panel"), paired from the
// phone in the same step as the Chrome: KIFF's own `kiff.pair.v1` offer for a browser extension (`platform: "chrome"`,
// `kind: "extension"`, `key_store: "software"`), which KIFF pairs view-only. Its session reads and chats, and KIFF refuses it
// every approval (`403 view_only`). Its own key signs only the claim of that session.

import { b64u, bytes, sha256, publicSPKI } from "./crypto.js";
import { hex } from "./pairing.js";

export const KIFF = "https://api.kiff.dev";

/** Under KIFF's 5 minutes, so the phone's clock never reads it as too far ahead. */
export const OFFER_LIFETIME_MS = (4 * 60 + 30) * 1000;

/**
 * The offer the phone signs `POST /v1/me/pairings` over, as KIFF's QR text (sorted keys), and the claim message the
 * extension's KIFF key signs to collect the session (`kiff.pair.claim.v1`, the nonce and sha256 of the key in hex).
 */
export async function kiffOffer(keyPair, { name, now = Date.now() }) {
  const spki = await publicSPKI(keyPair.publicKey);
  const pub = b64u(spki);
  const nonce = b64u(crypto.getRandomValues(new Uint8Array(32)));
  const exp = new Date(now + OFFER_LIFETIME_MS).toISOString().replace(/\.\d{3}Z$/, "Z");
  const fields = { exp, key_store: "software", kind: "extension", name, nonce, platform: "chrome", pub, v: "kiff.pair.v1" };
  const text = JSON.stringify(Object.fromEntries(Object.keys(fields).sort().map((k) => [k, fields[k]])));
  const claimMessage = ["kiff.pair.claim.v1", nonce, hex(await sha256(spki))].join("\n");
  return { text, nonce, pub, claimMessage };
}

/** An ECDSA signature as KIFF reads a claim's proof: DER, standard base64, from WebCrypto's raw r‖s. */
export function rawToDERBase64(raw) {
  const int = (b) => {
    let i = 0;
    while (i < b.length - 1 && b[i] === 0) i++;
    let v = b.slice(i);
    if (v[0] & 0x80) v = Uint8Array.of(0, ...v);
    return Uint8Array.of(2, v.length, ...v);
  };
  const r = int(raw.slice(0, 32));
  const s = int(raw.slice(32, 64));
  const der = Uint8Array.of(0x30, r.length + s.length, ...r, ...s);
  let out = "";
  for (const x of der) out += String.fromCharCode(x);
  return btoa(out);
}

/** The claim's proof: the KIFF key's signature over the claim message, made once, sent with every poll. */
export async function claimProof(privateKey, claimMessage) {
  const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privateKey, bytes(claimMessage)));
  return rawToDERBase64(raw);
}

/**
 * One claim: the session once the phone approved (`{token, tenant, subject, viewOnly, expiresIn}`), `{pending: true}`
 * until then, or `{error}`. A session that isn't view-only is ended at once and never kept: this device must never
 * approve.
 */
export async function claim({ fetch, base = KIFF, nonce, pub, proof }) {
  const res = await fetch(base + "/v1/pair/claim", { method: "POST", headers: { "content-type": "application/json", accept: "application/json" }, body: JSON.stringify({ nonce, pub, proof }) });
  const data = await res.json().catch(() => ({}));
  if (res.ok && typeof data.access_token === "string") {
    if (data.approval_mode !== "view") {
      await logout({ fetch, base, token: data.access_token }).catch(() => {});
      return { error: "not_view_only" };
    }
    return { token: data.access_token, tenant: data.tenant, subject: data.subject, viewOnly: true, expiresIn: data.expires_in };
  }
  const why = data.error || data.message || "";
  if (why === "authorization_pending" || why === "slow_down") return { pending: true };
  return { error: why === "expired_token" ? "expired" : why || "kiff" };
}

/** Ends this device's KIFF session with its own bearer (204, also for an unknown token). */
export async function logout({ fetch, base = KIFF, token }) {
  await fetch(base + "/v1/auth/device/logout", { method: "POST", headers: { authorization: "Bearer " + token, "content-type": "application/json" }, body: "{}" });
}
