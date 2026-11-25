// Coach reads the page you're on (docs/chrome.md): the read itself, gated twice. The tab in front of the panel's window is checked
// (sites.js `asideRefusal`), read once (aside.js), and then checked again on the address the read was actually taken on, after a
// fresh look that it's still the tab in front: a tab that moved in between (a redirect, a link, the person's own click) onto a
// page the gate refuses (a sensitive site, one switched off, another tab) sends nothing.

import { asideRefusal, hostOf, refuseURL } from "./sites.js";

/**
 * Why a page at [url] may not go with a message to a taf whose phone-signed sites are [within] (null: Coach, no such limit):
 * "not_taf_site" off them, else null. A taf reads only where it may work.
 */
export function offTafSites(url, within) {
  if (within == null) return null;
  return refuseURL(String(url || ""), Array.isArray(within) ? within : [], []) ? "not_taf_site" : null;
}

/** The tab [tabId] if it's what the panel of [windowId] has in front, its host, and why it may not be read (or null). */
export async function asideTab(chrome, tabId, windowId, off) {
  if (typeof tabId !== "number" || typeof windowId !== "number") return { why: "not_front", host: "" };
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  return { tab, host: hostOf(tab && tab.url), why: asideRefusal(tab, { windowId, off }) };
}

/**
 * The page for Coach, or for a taf with its signed sites [within], or why not: `{host, page}` with `page` as it's sent
 * (`{url, title, text, truncated}`, masked), or `{host, why}`. A page that can't be read is no reason to hold the message: the
 * words go alone. For a taf, a page off its sites is never read ("not_taf_site"), checked before and after the read.
 */
export async function readForCoach(chrome, tabId, windowId, off = [], within = null) {
  const first = await asideTab(chrome, tabId, windowId, off);
  if (first.why) return { host: first.host, why: first.why };
  const offSites = offTafSites(first.tab && first.tab.url, within);
  if (offSites) return { host: first.host, why: offSites };
  let read;
  try {
    await chrome.scripting.executeScript({ target: { tabId }, files: ["src/mask.js", "src/aside.js"] });
    const [res] = await chrome.scripting.executeScript({ target: { tabId }, func: () => globalThis.TafsAside.read() });
    read = res && res.result;
  } catch {
    return { host: first.host, why: "unread" };
  }
  if (!read || typeof read.text !== "string" || typeof read.href !== "string") return { host: first.host, why: "unread" };
  // The second check: the address the read was taken on, and the tab as it is now, still the one in front of that window.
  const now = await chrome.tabs.get(tabId).catch(() => null);
  const again = asideRefusal(now && { ...now, url: read.href }, { windowId, off }) || offTafSites(read.href, within);
  if (again) return { host: hostOf(read.href), why: again };
  const { href, ...page } = read; // eslint-disable-line no-unused-vars
  return { host: hostOf(href), page: { url: String(page.url || ""), title: String(page.title || ""), text: page.text, truncated: page.truncated === true } };
}
