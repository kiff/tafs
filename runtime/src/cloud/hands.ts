/**
 * "Your turn" in planque's hosted browser: a step a taf handed to its owner mid-run (a cookie wall, a check, an odd
 * question), which the owner does themselves in the hosted browser's live view, on the phone. planque keeps it and
 * lists the ones still waiting (`GET /v1/accounts/{tenant}/hands`, read with the person's own session, as
 * `/connections` is): the grant, its taf's agent, the hand-over's id, the site (from the page), the taf's words
 * (checked by planque), and when it was handed and lapses. No URL and no token: the app asks planque for the view
 * with a signed POST each time it opens one (`…/grants/{grant}/hands/{hand}/view`), or declines it
 * (`…/hands/{hand}/decline`); either answers 410 once the step no longer waits, and the next read drops it.
 *
 * tafs cloud stores nothing: Needs you lists each one under its taf, every word tafs cloud's in the person's language
 * but the taf's own, quoted.
 */
import type { Fetch } from "./kiff.js";
import { timeIn } from "./work.js";
import { type Lang, wordsIn } from "./words/index.js";

/** One hand-over waiting, as planque lists it. */
export interface Hand {
  grant: string;
  agent: string;
  hand: string;
  site: string;
  words: string;
  since: string;
  expires: string;
}

/** One hand-over as Needs you draws it. */
export interface HandView {
  /** What the app signs its view and decline asks with. */
  grant: string;
  hand: string;
  site: string;
  /** The taf's words, as planque checked them. */
  ask: string;
  since: string;
  expires: string;
  taf: { id: string; name: string };
  words: { title: string; ask: string; until: string; open: string; not_now: string };
}

/** At most this many are read: a hand-over lapses in ten minutes, so more is a planque gone wrong. */
export const MAX_HANDS = 20;

const ID = /^[A-Za-z0-9_.:-]{1,128}$/;
const SITE = /^[a-z0-9-]+(\.[a-z0-9-]+)+$/;

/** One line of planque's words, at most `max` characters. */
function line(s: unknown, max: number): string {
  const t = typeof s === "string" ? s.replace(/[\u0000-\u001f\u007f-\u009f\s]+/g, " ").trim() : "";
  const chars = [...t];
  return chars.length > max ? chars.slice(0, max - 1).join("").trimEnd() + "…" : t;
}

function instant(s: unknown): string | undefined {
  if (typeof s !== "string") return undefined;
  const t = Date.parse(s);
  return Number.isNaN(t) ? undefined : new Date(t).toISOString();
}

/** The hand-overs planque lists as waiting. Anything malformed is left out; an answer that isn't a list is none. */
export function parseHands(got: unknown): Hand[] {
  const list = got && typeof got === "object" ? (got as { hands?: unknown }).hands : undefined;
  if (!Array.isArray(list)) return [];
  const out: Hand[] = [];
  for (const h of list.slice(0, MAX_HANDS) as Record<string, unknown>[]) {
    if (!h || typeof h !== "object") continue;
    const { grant, agent, hand } = h;
    const site = typeof h.site === "string" ? h.site.toLowerCase().replace(/\.$/, "") : "";
    const since = instant(h.since), expires = instant(h.expires);
    const words = line(h.words, 200);
    if (typeof grant !== "string" || !ID.test(grant) || typeof agent !== "string" || !ID.test(agent) || typeof hand !== "string" || !ID.test(hand)) continue;
    if (site.length > 253 || !SITE.test(site) || !words || !since || !expires) continue;
    out.push({ grant, agent, hand, site, words, since, expires });
  }
  return out;
}

/** The account's waiting hand-overs; planque's error is thrown, for the caller to read as none. */
export async function accountHands(fetchFn: Fetch, base: string, tenant: string, bearer: string): Promise<Hand[]> {
  const res = await fetchFn(`${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/hands`, {
    headers: { authorization: `Bearer ${bearer}`, accept: "application/json" },
  });
  if (!res.ok) throw new Error(`planque answered ${res.status}`);
  return parseHands(await res.json());
}

/**
 * Needs you's hand-overs, oldest first: only one of the person's own tafs (by its KIFF agent) and only one not yet
 * lapsed; another's, or one lapsed, isn't drawn.
 */
export function handsOf(hands: Hand[], tafs: Array<{ id: string; name: string; kiffAgent?: string }>, lang: Lang, zone: string, now: Date): HandView[] {
  const w = wordsIn(lang);
  const byAgent = new Map(tafs.filter((t) => t.kiffAgent).map((t) => [t.kiffAgent!, t]));
  return hands
    .filter((h) => byAgent.has(h.agent) && Date.parse(h.expires) > now.getTime())
    .map((h) => {
      const t = byAgent.get(h.agent)!;
      return {
        grant: h.grant, hand: h.hand, site: h.site, ask: h.words, since: h.since, expires: h.expires,
        taf: { id: t.id, name: t.name },
        words: { title: w.your_turn_title(h.site), ask: w.your_turn_ask(h.words), until: w.your_turn_until(timeIn(h.expires, zone, now, lang)), open: w.your_turn_open, not_now: w.your_turn_not_now },
      };
    })
    .sort((a, b) => a.since.localeCompare(b.since));
}
