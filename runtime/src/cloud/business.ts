import type { CoachStore } from "./coach.js";
import { type Lang, wordsIn } from "./words/index.js";

/**
 * The business context (docs/labels.md): "What tafs know about your business", one for the account, in the person's
 * words, as Coach drafted it and the person confirmed it. Words, never permissions: it changes no Card, tool or limit.
 */
export interface Business { text: string; version: number; by?: string; at?: string }

/** About 300 words: at most this many words, and characters. */
export const BUSINESS_LIMITS = { words: 300, chars: 2400 } as const;

/** A change refused, in words Coach can act on; `stale` when the version it was drafted on isn't the current one. */
export class BusinessRefusal extends Error {
  constructor(message: string, readonly stale = false) { super(message); }
}

const KEPT_MS = 100 * 365 * 24 * 3600_000;
const CLAIM_MS = 60_000;

export const businessKey = (tenant: string) => `${tenant}#business`;

export async function readBusiness(store: CoachStore, tenant: string): Promise<Business> {
  const kept = await store.get(businessKey(tenant)) as Partial<Business> | undefined;
  return { text: typeof kept?.text === "string" ? kept.text : "", version: typeof kept?.version === "number" ? kept.version : 0,
    ...(typeof kept?.by === "string" ? { by: kept.by } : {}), ...(typeof kept?.at === "string" ? { at: kept.at } : {}) };
}

/** The text as kept: trimmed, its lines kept, within the limits. "" clears it. */
export function businessText(text: unknown): string {
  if (typeof text !== "string") throw new BusinessRefusal("text is the whole description, in the person's words (\"\" clears it)");
  const t = text.replace(/\r\n?/g, "\n").replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  const words = t ? t.split(/\s+/).length : 0;
  if (words > BUSINESS_LIMITS.words || t.length > BUSINESS_LIMITS.chars) {
    throw new BusinessRefusal(`the description is at most ${BUSINESS_LIMITS.words} words (${BUSINESS_LIMITS.chars} characters): this one has ${words} words, ${t.length} characters`);
  }
  return t;
}

/**
 * Saves `text` over the version it was drafted on: refused when that isn't the current one (someone changed it since,
 * so it's read again), or when another save took the next version first.
 */
export async function saveBusiness(store: CoachStore, tenant: string, text: unknown, version: unknown, by: string, now: Date): Promise<Business> {
  const t = businessText(text);
  const current = await readBusiness(store, tenant);
  if (version !== current.version) throw new BusinessRefusal(`the description changed since: read it again (version ${current.version}) and draft on it`, true);
  const next: Business = { text: t, version: current.version + 1, by, at: now.toISOString() };
  if (!(await store.once(`${businessKey(tenant)}#v${next.version}`, new Date(now.getTime() + CLAIM_MS)))) {
    throw new BusinessRefusal("the description changed since: read it again and draft on it", true);
  }
  await store.put(businessKey(tenant), next, new Date(now.getTime() + KEPT_MS));
  return next;
}

/** What the apps' Account section draws: the text (read-only), its version, and its words. */
export function businessView(b: Business, lang: Lang) {
  const w = wordsIn(lang);
  return { text: b.text, version: b.version, words: { title: w.business_title, empty: w.business_empty, change: w.business_change } };
}
