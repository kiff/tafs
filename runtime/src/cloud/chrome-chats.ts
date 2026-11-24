/**
 * A Chrome's own chats (docs/chrome.md, "The side panel's chats"): the tafs extension's side panel sees only the chats
 * begun in that Chrome since it was paired, never the rest of the person's chats, which stay on their phone and their
 * other devices. Which Chrome is asking comes from KIFF alone: its session record's device and that device's platform
 * as it was paired (`device_id`, `device_platform` on `/v1/me`), never from anything the request says.
 *
 * A Chrome may call only the routes its side panel uses (CHROME_ROUTES); any other answers 404, so a route added later
 * is closed to it until it's listed here. On those routes, a chat it didn't begin is 404 too, by its id, its list, or
 * its live thread. Each chat a Chrome begins is kept as one item, `<tenant>#chrome-chat#<device>#<session>`, read by
 * its key: no list grows, and nothing scans the account's chats.
 */

import type { CoachStore } from "./coach.js";
import type { KIFFIdentity } from "./kiff.js";

/** The platform KIFF stores for the browser extension's pairing. */
export const CHROME_PLATFORM = "chrome";

/** How long a Chrome's mark on a chat it began is kept: past any chat's own time, so the mark never outlives nothing. */
export const CHROME_CHAT_KEPT_MS = 400 * 24 * 3600_000;

/**
 * The Chrome asking, or undefined for anyone else. `device` is "" when the request is taken as a Chrome's but KIFF named
 * no device: it then has no chats at all (fail closed). That's a device's view-only session with neither a device nor a
 * platform, as KIFF answered before it named them. Once KIFF names the device, only its platform says Chrome: a view-only
 * computer paired with no platform stored keeps its whole list, as every device but a Chrome does.
 */
export function chromeOf(me: KIFFIdentity): { device: string } | undefined {
  if (me.devicePlatform === CHROME_PLATFORM) return { device: me.deviceID ?? "" };
  if (!me.devicePlatform && !me.deviceID && me.deviceSession === true && me.approvalMode === "view") return { device: "" };
  return undefined;
}

/**
 * The routes a Chrome's side panel calls (chrome/src/coach.js), and no other: the account's Coach and tafs, a taf's
 * thread and chats, sending to it, archiving its own chats, and the page consent. Paths as they arrive, unescaped ids.
 */
export const CHROME_ROUTES: ReadonlyArray<{ method: string; path: RegExp }> = [
  { method: "GET", path: /^\/v1\/setup$/ },
  { method: "GET", path: /^\/v1\/tafs$/ },
  { method: "GET", path: /^\/v1\/tafs\/[^/]+\/conversation(\/[^/]+)?$/ },
  { method: "POST", path: /^\/v1\/tafs\/[^/]+\/messages$/ },
  { method: "GET", path: /^\/v1\/tafs\/[^/]+\/chats(\/archived)?$/ },
  { method: "POST", path: /^\/v1\/tafs\/[^/]+\/chats\/[^/]+\/(archive|unarchive)$/ },
  { method: "GET", path: /^\/v1\/me\/page-consent$/ },
  { method: "POST", path: /^\/v1\/me\/page-consent$/ },
  { method: "DELETE", path: /^\/v1\/me\/page-consent$/ },
];

/** Whether a Chrome may call `method` on `path`. */
export function chromeMayCall(method: string, path: string): boolean {
  return CHROME_ROUTES.some((r) => r.method === method && r.path.test(path));
}

export const chromeChatKey = (tenant: string, device: string, session: string) => `${tenant}#chrome-chat#${device}#${session}`;

/** Whether this Chrome began the chat `session`: never with no device, no store, or no session. */
export async function chromeBegan(store: CoachStore | undefined, me: KIFFIdentity, chrome: { device: string }, session: string): Promise<boolean> {
  if (!store || !chrome.device || !session) return false;
  const kept = await store.get(chromeChatKey(me.tenantID, chrome.device, session)).catch(() => undefined);
  return kept !== undefined && kept !== null;
}

/** Marks `session` as begun in this Chrome. With no device or no store, nothing is kept, so it stays unseen there. */
export async function markChromeChat(store: CoachStore | undefined, me: KIFFIdentity, chrome: { device: string }, session: string, now: Date): Promise<void> {
  if (!store || !chrome.device || !session) return;
  await store.put(chromeChatKey(me.tenantID, chrome.device, session), { at: now.toISOString() }, new Date(now.getTime() + CHROME_CHAT_KEPT_MS));
}
