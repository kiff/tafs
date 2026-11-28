import type { GrantView } from "./taf-setup.js";
import { type Lang, wordsIn } from "./words/index.js";

/**
 * Browser batch 2 (docs/api.md, "How a taf uses websites"): the two ways a taf browses, named by what the owner gets,
 * never by technology. `cloud` is planque's hosted browser; `chrome` the owner's own paired Chrome (the grant's
 * `browser.driver: "extension"`). The way is the grant's, as the owner approved it: nothing else sets it.
 */
export type Way = "cloud" | "chrome";
export const WAYS: readonly Way[] = ["cloud", "chrome"];

/** A grant's way: undefined for a connection that isn't a browser's. */
export function wayOf(g: Pick<GrantView, "provider" | "request">): Way | undefined {
  if (g.provider !== "browser") return undefined;
  return g.request.browser?.driver === "extension" ? "chrome" : "cloud";
}

/** The way's title and line, in the person's language. */
export function wayWords(way: Way, lang: Lang = "en"): { title: string; line: string } {
  const w = wordsIn(lang);
  return way === "cloud" ? { title: w.browse_cloud_title, line: w.browse_cloud_line } : { title: w.browse_chrome_title, line: w.browse_chrome_line };
}

/** A browser grant's own fields for a connection row: its way and words, its paired Chrome's name, and its sites. */
export function wayFields(g: Pick<GrantView, "provider" | "request">, lang: Lang = "en"): {
  way?: Way; way_words?: { title: string; line: string }; chrome_name?: string; sites?: Array<{ site: string }>;
} {
  const way = wayOf(g);
  if (!way) return {};
  const name = g.request.browser?.chrome_name?.trim();
  const sites = (g.request.browser?.sites ?? []).filter((x) => typeof x === "string" && x);
  return { way, way_words: wayWords(way, lang), ...(way === "chrome" && name ? { chrome_name: name } : {}), ...(sites.length ? { sites: sites.map((site) => ({ site })) } : {}) };
}

export interface WayView { way: Way; words: { title: string; line: string; facts: string[]; status?: string } }

/**
 * Connect apps' two ways, at the account level, with or without a browser connection: each with its title, its line,
 * two facts and, when there's one to say, its state on this account. `chrome` says the Chrome it's paired with, from the
 * account's chrome connections; `cloud`'s saved sign-ins come with their health.
 */
export function waysView(grants: ReadonlyArray<Pick<GrantView, "provider" | "request" | "status">> | undefined, lang: Lang = "en"): WayView[] {
  const w = wordsIn(lang);
  const paired = (grants ?? []).find((g) => wayOf(g) === "chrome" && g.status === "connected" && g.request.browser?.chrome_name?.trim());
  return [
    { way: "cloud", words: { ...wayWords("cloud", lang), facts: [w.browse_cloud_fact_fresh, w.browse_cloud_fact_sign_in] } },
    { way: "chrome", words: { ...wayWords("chrome", lang), facts: [w.browse_chrome_fact_sign_ins, w.browse_chrome_fact_blocked],
      ...(paired ? { status: w.browse_chrome_paired(paired.request.browser!.chrome_name!.trim()) } : {}) } },
  ];
}

/**
 * A taf's browsing, for its setup: its first connected browser connection's sites and way, and the other way's
 * button, an `ask_coach` with tafs cloud's words for Coach. Undefined for a taf that doesn't browse.
 */
export function browsingOf(taf: { name: string }, grants: ReadonlyArray<Pick<GrantView, "provider" | "request" | "status">>, lang: Lang = "en"): {
  sites: string[]; way: Way; way_words: { title: string; line: string }; switch: { words: { button: string }; ask: string };
} | undefined {
  const g = grants.find((x) => wayOf(x) && x.status === "connected") ?? grants.find((x) => wayOf(x));
  if (!g) return undefined;
  const way = wayOf(g)!;
  const w = wordsIn(lang);
  const sites = (g.request.browser?.sites ?? []).filter((x) => typeof x === "string" && x);
  const site = sites.join(", ");
  return { sites, way, way_words: wayWords(way, lang),
    switch: way === "cloud"
      ? { words: { button: w.browse_switch_to_chrome }, ask: w.browse_switch_ask_chrome(taf.name, site) }
      : { words: { button: w.browse_switch_to_cloud }, ask: w.browse_switch_ask_cloud(taf.name, site) } };
}
