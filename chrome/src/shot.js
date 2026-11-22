// A picture of an act, kept for the record (docs/chrome.md, "Screenshots of a run's acts"). One image for each act, taken just
// before it, with every secret field covered; never of a read, a blocked or sensitive site, a checkout or a payment frame.
// It is encoded here (WebP, at most 1280 px wide, about 50 KB) and sent to planque over HTTPS, signed with the extension's key
// over the step it belongs to. It can only ever add: whatever goes wrong here, the act goes ahead.

import { sign, LABEL, b64u } from "./crypto.js";
import { hostOf, sensitive } from "./sites.js";

export const MAX_WIDTH = 1280;
export const MAX_HEIGHT = 8000; // planque keeps none taller
/** What planque takes: more than this, or anything but WebP, is refused. */
export const MAX_BYTES = 120 * 1024;
const QUALITY = [0.6, 0.4];

/** Whether the page in front may be pictured at all: an https site that isn't sensitive, not a checkout, not a payment frame. */
export function canCapture(url, page) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return false;
  }
  if (u.protocol !== "https:") return false;
  if (sensitive(hostOf(url))) return false;
  if (page && (page.payment_frame || page.checkout)) return false;
  return true;
}

/**
 * The picture as WebP: scaled to at most MAX_WIDTH, at quality 0.6, and once more at 0.4 when it came out over MAX_BYTES; null
 * when it is still too big. `deps` are the browser's (`createImageBitmap`, `OffscreenCanvas`), so a test can stand in.
 */
export async function encode(blob, { createImageBitmap, OffscreenCanvas }) {
  const bitmap = await createImageBitmap(blob);
  try {
    const scale = Math.min(1, MAX_WIDTH / bitmap.width, MAX_HEIGHT / bitmap.height);
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = new OffscreenCanvas(width, height);
    canvas.getContext("2d").drawImage(bitmap, 0, 0, width, height);
    for (const quality of QUALITY) {
      const out = await canvas.convertToBlob({ type: "image/webp", quality });
      if (out.type !== "image/webp") return null;
      if (out.size <= MAX_BYTES) return out;
    }
    return null;
  } finally {
    if (typeof bitmap.close === "function") bitmap.close();
  }
}

export async function sha256Hex(bytes) {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The signature planque checks: the label, then JCS `{chrome_id, step, sha256}`. */
export function signScreenshot(privateKey, { chromeId, step, sha256 }) {
  return sign(privateKey, LABEL.screenshot, { chrome_id: chromeId, step, sha256 });
}

/** How long the browser gets to hand the picture over, so a window it can't draw never holds an act up. */
const CAPTURE_MS = 3000;

/**
 * Takes the pictures. `chrome` is the extension API, `inTab(tabId, func, args)` runs a function in the run's page, and
 * `keys()` and `pairing()` say who signs and for which Chrome. `base` is planque's address. Nothing here throws, and the act
 * goes ahead whatever happens: `capture` is what must finish before it (the page's secret fields covered, the picture taken,
 * the covers off again), `send` is what doesn't (the encoding and the upload).
 */
export class Shots {
  constructor({ chrome, inTab, keys, pairing, base, fetch = globalThis.fetch.bind(globalThis), deps = { createImageBitmap: globalThis.createImageBitmap, OffscreenCanvas: globalThis.OffscreenCanvas }, timeout = CAPTURE_MS }) {
    Object.assign(this, { chrome, inTab, keys, pairing, base, fetch, deps, timeout });
  }

  /** The picture of the run's front tab, as a data URL, or a word for why there isn't one ("site", "hidden", "unpaired", "mask"). */
  async capture({ tabId, windowId, page }) {
    try {
      const tab = await this.chrome.tabs.get(tabId);
      if (!tab || !canCapture(tab.url, page)) return { why: "site" };
      // The picture is of the window's front tab: a run's other tabs are not in it.
      if (!tab.active) return { why: "hidden" };
      const keys = this.keys();
      const pairing = this.pairing();
      if (!keys || !keys.privateKey || !pairing || !pairing.chromeId) return { why: "unpaired" };
      const masked = await this.inTab(tabId, () => globalThis.TafsPage.maskForShot(), []);
      try {
        if (!masked || !masked.ok) return { why: "mask" };
        let timer;
        const late = new Promise((resolve) => (timer = setTimeout(() => resolve(null), this.timeout)));
        const shot = await Promise.race([this.chrome.tabs.captureVisibleTab(windowId, { format: "png" }), late]).finally(() => clearTimeout(timer));
        return shot ? { png: shot } : { why: "hidden" };
      } finally {
        // The boxes come off whatever happened, before the act.
        await this.inTab(tabId, () => globalThis.TafsPage.unmaskShot(), []).catch(() => {});
      }
    } catch {
      return { why: "error" };
    }
  }

  /** Encodes the picture and posts it for the step `stepId`: "sent", or a word for why not ("size", "upload", "error"). */
  async send(png, stepId) {
    try {
      const keys = this.keys();
      const pairing = this.pairing();
      const webp = await encode(await (await this.fetch(png)).blob(), this.deps);
      if (!webp) return "size";
      const bytes = new Uint8Array(await webp.arrayBuffer());
      const sha256 = await sha256Hex(bytes);
      const sig = await signScreenshot(keys.privateKey, { chromeId: pairing.chromeId, step: stepId, sha256 });
      const url = this.base + "/v1/extension/steps/" + encodeURIComponent(stepId) + "/screenshot";
      const body = JSON.stringify({ chrome_id: pairing.chromeId, image: b64u(bytes), sig });
      const res = await this.fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body });
      return res.ok ? "sent" : "upload";
    } catch {
      return "error";
    }
  }

  /** Both, in order: for a caller that has nothing to do between them. */
  async take({ tabId, windowId, page, stepId }) {
    const got = await this.capture({ tabId, windowId, page });
    return got.png ? this.send(got.png, stepId) : got.why;
  }
}
