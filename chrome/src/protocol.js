// The messages between the extension and planque (docs/chrome.md, "Connect" and "Steps"). A step runs only when planque
// signed it with its pinned key, for this Chrome, unexpired and never seen before; every answer is signed by this
// extension's key. Nothing here touches a tab.

import { LABEL, sign, verify } from "./crypto.js";

export const CLOSE = Object.freeze({ unknownChrome: 4401, buildNotAllowed: 4403, unpaired: 4410 });

/** How far ahead a step's expiry may be, and how long a nonce is remembered. */
export const MAX_AHEAD_MS = 30_000;
export const NONCE_MEMORY_MS = 10 * 60_000;

const OPS = new Set(["open", "read", "act", "close"]);

/** Nonces of steps already taken, each kept until well past its step's expiry. */
export class Nonces {
  constructor(now = () => Date.now()) {
    this.now = now;
    this.seen = new Map();
  }
  /** Takes `nonce` once: false when it was taken before. */
  take(nonce) {
    const t = this.now();
    for (const [n, until] of this.seen) if (until < t) this.seen.delete(n);
    if (this.seen.has(nonce)) return false;
    this.seen.set(nonce, t + NONCE_MEMORY_MS);
    return true;
  }
}

/**
 * Why the signed step `message` may not run: a reason, or null with `step` set when it may. `planqueKey` is the pinned
 * key (a CryptoKey or SPKI base64url); `chromeId` this Chrome's id.
 */
export async function checkStep(message, { planqueKey, chromeId, nonces, now = Date.now() }) {
  const step = message && message.step;
  if (!step || typeof step !== "object" || typeof message.sig !== "string") return { why: "not a step" };
  if (!(await verify(planqueKey, LABEL.step, step, message.sig))) return { why: "not signed by planque" };
  if (step.chrome_id !== chromeId) return { why: "not for this Chrome" };
  if (typeof step.id !== "string" || typeof step.nonce !== "string" || !OPS.has(step.op)) return { why: "malformed" };
  if (typeof step.exp !== "number" || step.exp < now) return { why: "expired" };
  if (step.exp > now + MAX_AHEAD_MS) return { why: "expiry too far ahead" };
  if (!Array.isArray(step.sites) || step.sites.length === 0) return { why: "no sites" };
  if (!nonces.take(step.nonce)) return { why: "seen before" };
  return { why: null, step };
}

/** The signed answer to step `id`. `page` is the page as read back, `error` `{code, detail?}`; never both. */
export async function result(privateKey, id, { page = null, error = null } = {}) {
  const body = { id, ok: !error, page, error };
  return { type: "result", ...body, sig: await sign(privateKey, LABEL.result, body) };
}

/** The hello that proves this extension on connect. */
export async function hello(privateKey, { chromeId, nonceP, nonceE, version, manifestHash }) {
  const body = { chrome_id: chromeId, nonce_p: nonceP, nonce_e: nonceE, version, manifest_hash: manifestHash };
  return { type: "hello", ...body, sig: await sign(privateKey, LABEL.hello, body) };
}

/** Whether planque's welcome proves it holds the pinned key, for this connect's nonces. */
export async function checkWelcome(message, { planqueKey, chromeId, nonceP, nonceE }) {
  if (!message || message.type !== "welcome") return false;
  return verify(planqueKey, LABEL.welcome, { chrome_id: chromeId, nonce_p: nonceP, nonce_e: nonceE }, message.sig);
}

/**
 * Whether `release` is the paired phone's signature releasing sealed value `sealed` for one fill of step `step` on
 * `origin`, unexpired (slice 5). planque relays it and can't make one.
 */
export async function checkRelease(release, { phoneKey, chromeId, step, origin, now = Date.now() }) {
  if (!release || typeof release !== "object") return false;
  const body = {
    chrome_id: chromeId,
    op: step.id,
    sealed: step.act && step.act.sealed,
    origin,
    field_ref: step.act && step.act.ref,
    nonce: release.nonce,
    exp: release.exp,
  };
  if (typeof body.exp !== "number" || body.exp < now || typeof body.nonce !== "string") return false;
  if (release.chrome_id !== chromeId || release.op !== step.id || release.origin !== origin) return false;
  return verify(phoneKey, LABEL.release, body, release.sig);
}
