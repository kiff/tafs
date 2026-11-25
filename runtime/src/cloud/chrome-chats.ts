/**
 * A Chrome's own chats (docs/chrome.md, "The side panel's chats"): the tafs extension's side panel sees the chats begun
 * in that Chrome since it was paired, and the turn of each run that worked in it, wherever its message was sent from;
 * never the rest of the person's chats, which stay on their phone and their other devices. Which Chrome is asking comes from KIFF alone: its session record's device and that device's platform
 * as it was paired (`device_id`, `device_platform` on `/v1/me`), never from anything the request says.
 *
 * A Chrome may call only the routes its side panel uses (CHROME_ROUTES); any other answers 404, so a route added later
 * is closed to it until it's listed here. On those routes, a chat it didn't begin is 404 too, by its id, its list, or
 * its live thread. Each chat a Chrome begins is kept as one item, `<tenant>#chrome-chat#<device>#<session>`, read by
 * its key: no list grows, and nothing scans the account's chats.
 */

import type { Run } from "./agents.js";
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
 * thread and chats, sending to it, a file it attaches (declared here, its bytes uploaded straight to storage, as the apps
 * do), archiving its own chats, and the page consent. Never a file read back: the panel doesn't reopen one. Paths as they
 * arrive, unescaped ids.
 */
export const CHROME_ROUTES: ReadonlyArray<{ method: string; path: RegExp }> = [
  { method: "GET", path: /^\/v1\/setup$/ },
  { method: "GET", path: /^\/v1\/tafs$/ },
  { method: "GET", path: /^\/v1\/tafs\/[^/]+\/conversation(\/[^/]+)?$/ },
  { method: "POST", path: /^\/v1\/tafs\/[^/]+\/messages$/ },
  { method: "POST", path: /^\/v1\/tafs\/[^/]+\/messages\/[^/]+\/report$/ },
  { method: "POST", path: /^\/v1\/tafs\/[^/]+\/attachments$/ },
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

/**
 * A run that worked in a Chrome, as planque recorded it: the run its call was made in, as KIFF signed it in the call's
 * permit, and the taf's KIFF agent.
 */
export interface ChromeRun { run: string; taf: string; at: string }

/**
 * The runs that worked in the asking Chrome (planque's `GET …/chromes/self/runs`), read with the panel's own KIFF
 * session: planque finds the Chrome from KIFF's word on that session's device, never from the request. None when
 * planque isn't set, can't be read, or answers anything else: the panel then shows only the chats begun in it.
 */
export async function chromeRunsOf(fetchFn: typeof fetch, base: string | undefined, tenant: string, token: string): Promise<ChromeRun[]> {
  if (!base || !token) return [];
  try {
    const res = await fetchFn(`${base}/v1/accounts/${encodeURIComponent(tenant)}/chromes/self/runs`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
    if (!res.ok) {
      console.error(`chrome runs: planque answered ${res.status}`);
      return [];
    }
    const got = await res.json() as { runs?: unknown };
    if (!Array.isArray(got.runs)) return [];
    return (got.runs as Record<string, unknown>[]).flatMap((r) => r && typeof r.run === "string" && r.run && typeof r.taf === "string"
      ? [{ run: r.run, taf: r.taf, at: typeof r.at === "string" ? r.at : "" }] : []).slice(0, CHROME_RUNS_READ);
  } catch (err) {
    console.error(`chrome runs: planque not read: ${(err as Error).message}`);
    return [];
  }
}

/** The most runs a Chrome's list reads at once: planque lists at most 50, newest first. */
export const CHROME_RUNS_READ = 50;

export const chromeRunChatKey = (tenant: string, run: string) => `${tenant}#chrome-run-chat#${run}`;

/**
 * The chats of the runs that worked in a Chrome with this taf, each with those runs: chat → runs. Each run is read
 * back from kiff/agents under the asking person's account and this taf, so a run planque lists that isn't theirs is
 * dropped, whatever planque says. A run's chat never changes, so it's kept once read, by account and run.
 */
export async function chromeRunChats(store: CoachStore | undefined, agents: { getRun(tenant: string, agent: string, run: string): Promise<Run> }, me: KIFFIdentity,
  taf: { id: string; kiffAgent?: string }, runs: ChromeRun[], now: Date): Promise<Map<string, Set<string>>> {
  const out = new Map<string, Set<string>>();
  if (!taf.kiffAgent) return out;
  const mine = runs.filter((r) => r.taf === taf.kiffAgent);
  const sessions = await Promise.all(mine.map(async ({ run }) => {
    const key = chromeRunChatKey(me.tenantID, run);
    const kept = store ? await store.get(key).catch(() => undefined) as { taf?: unknown; session?: unknown } | undefined : undefined;
    if (kept && kept.taf === taf.id && typeof kept.session === "string") return kept.session;
    const r = await agents.getRun(me.tenantID, taf.id, run).catch(() => undefined);
    if (!r || r.id !== run || r.agent !== taf.id || !r.session) return undefined;
    await store?.put(key, { taf: taf.id, session: r.session }, new Date(now.getTime() + CHROME_CHAT_KEPT_MS)).catch(() => undefined);
    return r.session;
  }));
  mine.forEach(({ run }, i) => {
    const session = sessions[i];
    if (!session) return;
    const set = out.get(session) ?? new Set<string>();
    set.add(run);
    out.set(session, set);
  });
  return out;
}
