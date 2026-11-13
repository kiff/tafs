/**
 * The AI-data consent (docs/accounts.md, "Your data and the AI"): before anything a person writes reaches the
 * model, they allow it, once, on a screen that names the provider, what's sent and what never is. tafs cloud keeps
 * the answer per person and refuses every route that would start a model turn for them until it's given
 * (`ai_consent_required`). The apps only draw the words and send the answer. A new `AI_CONSENT_VERSION` (the words
 * changed in substance) asks again.
 */
import type { CoachStore } from "./coach.js";
import { type Lang, wordsIn } from "./words/index.js";

/** The consent's version: raised when what's sent, or to whom, changes, so everyone is asked again. */
export const AI_CONSENT_VERSION = "1";

/** Kept for the account's life (the store's records expire; this one far off). */
const CONSENT_KEPT_MS = 10 * 365 * 24 * 3600_000;

/** Where one person's answer is kept. */
export const aiConsentKey = (tenant: string, subject: string) => `${tenant}#ai-consent#${subject}`;

/** One person's consent, as kept. */
export interface AIConsent { at: string; version: string }

/**
 * What's kept: the consent now, and each one given before it with when it was withdrawn. A withdrawal never deletes
 * the record, so tafs cloud can still show who allowed what, for which version, and from when to when.
 */
interface Kept extends Partial<AIConsent> { withdrawn_at?: string; earlier?: Array<AIConsent & { withdrawn_at?: string }> }

/** At most this many earlier windows are kept per person: the newest. */
const EARLIER_KEPT = 50;

/** What the apps draw: whether it's given for this version, and the words in the person's language. */
export interface AIConsentView {
  given: boolean;
  at?: string;
  version: string;
  words: { title: string; lines: string[]; allow: string; not_now: string; withdraw: string; given: string };
}

/** The person's consent for the current version, or undefined: none, withdrawn, or given to an older version. */
export async function aiConsentOf(store: CoachStore | undefined, tenant: string, subject: string): Promise<AIConsent | undefined> {
  if (!store) return undefined;
  const kept = await store.get(aiConsentKey(tenant, subject)).catch(() => undefined) as Kept | undefined;
  return kept && !kept.withdrawn_at && kept.version === AI_CONSENT_VERSION && typeof kept.at === "string" ? { at: kept.at, version: kept.version } : undefined;
}

export function aiConsentView(consent: AIConsent | undefined, lang: Lang): AIConsentView {
  const w = wordsIn(lang);
  return {
    given: consent !== undefined,
    ...(consent ? { at: consent.at } : {}),
    version: AI_CONSENT_VERSION,
    words: { title: w.ai_consent_title, lines: [...w.ai_consent_lines], allow: w.ai_consent_allow, not_now: w.ai_consent_not_now,
      withdraw: w.ai_consent_withdraw, given: w.ai_consent_given },
  };
}

/** Records the person's yes to `version`; a version that isn't the current one is refused, so an old screen can't say yes. */
export async function giveAIConsent(store: CoachStore, tenant: string, subject: string, version: unknown, now: Date): Promise<AIConsent | "stale"> {
  if (version !== AI_CONSENT_VERSION) return "stale";
  const consent = { at: now.toISOString(), version: AI_CONSENT_VERSION };
  const kept = await read(store, tenant, subject);
  await store.put(aiConsentKey(tenant, subject), { ...consent, earlier: history(kept) }, new Date(now.getTime() + CONSENT_KEPT_MS));
  return consent;
}

/** Withdraws it: from now on, nothing they write reaches the model until they allow it again. */
export async function withdrawAIConsent(store: CoachStore, tenant: string, subject: string, now: Date): Promise<void> {
  const kept = await read(store, tenant, subject);
  if (!kept?.at || kept.withdrawn_at) return;
  await store.put(aiConsentKey(tenant, subject), { ...kept, withdrawn_at: now.toISOString() }, new Date(now.getTime() + CONSENT_KEPT_MS));
}

/** The record as kept, or undefined. A read that fails reads as none: the gate then refuses, it never lets through. */
async function read(store: CoachStore, tenant: string, subject: string): Promise<Kept | undefined> {
  return await store.get(aiConsentKey(tenant, subject)).catch(() => undefined) as Kept | undefined;
}

/** The earlier windows with the one now kept last, newest first, so a new yes keeps the proof of the old. */
function history(kept: Kept | undefined): Kept["earlier"] {
  const earlier = kept?.earlier ?? [];
  if (!kept?.at || !kept.version) return earlier;
  return [{ at: kept.at, version: kept.version, ...(kept.withdrawn_at ? { withdrawn_at: kept.withdrawn_at } : {}) }, ...earlier].slice(0, EARLIER_KEPT);
}
