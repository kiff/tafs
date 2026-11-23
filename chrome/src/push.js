// KIFF's wake-up by Web Push (docs/chrome.md, "Waking the extension"). A service worker MV3 stops can't be reached by planque, so
// the extension gives planque a push subscription, and planque sends one silent push (no data) when a taf needs this Chrome and it
// isn't connected; the worker wakes on the `push` event and connects. Silent: `userVisibleOnly: false` (Chrome 121), which is why the
// manifest asks `notifications`; the extension never shows one. The subscription goes to planque signed by this Chrome's key.

import { LABEL, sign } from "./crypto.js";

/** A base64url string as the bytes `applicationServerKey` takes (planque's VAPID public key, a 65-byte P-256 point). */
export function keyBytes(b64u) {
  const s = String(b64u).replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(s + "=".repeat((4 - (s.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

/** The subscription as planque takes it, or null when it isn't one: an https endpoint and the two keys, each a string. */
export function subscriptionOf(sub) {
  const j = sub && typeof sub.toJSON === "function" ? sub.toJSON() : sub;
  if (!j || typeof j.endpoint !== "string" || !j.endpoint.startsWith("https://") || j.endpoint.length > 1024) return null;
  const k = j.keys || {};
  if (typeof k.p256dh !== "string" || typeof k.auth !== "string" || !k.p256dh || !k.auth) return null;
  return { endpoint: j.endpoint, keys: { p256dh: k.p256dh, auth: k.auth } };
}

/**
 * The extension's push subscription for planque's key: the one it has, else a new one, silent. Null when this build has no key,
 * or Chrome refuses (a push service it can't reach, an old Chrome): the extension then just goes on connecting by its alarm.
 */
export async function ensureSubscription(pushManager, pushKey) {
  if (!pushKey || !pushManager) return null;
  try {
    const have = await pushManager.getSubscription();
    if (have) return have;
    return await pushManager.subscribe({ userVisibleOnly: false, applicationServerKey: keyBytes(pushKey) });
  } catch {
    return null;
  }
}

/** What the extension tells planque after its welcome: `{type: "push", subscription, sig}`, the sig over `{chrome_id, subscription}`. */
export async function pushMessage(privateKey, chromeId, subscription) {
  return { type: "push", subscription, sig: await sign(privateKey, LABEL.push, { chrome_id: chromeId, subscription }) };
}
