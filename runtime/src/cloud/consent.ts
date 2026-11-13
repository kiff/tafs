/**
 * The AI-data consent (docs/accounts.md, "Your data and the AI"): before anything a person writes reaches the
 * model, they allow it, once, on a screen that names the provider, what's sent and what never is. tafs cloud keeps
 * the answer per person and refuses every route that would start a model turn for them until it's given
 * (`ai_consent_required`). The apps only draw the words and send the answer. A new `AI_CONSENT_VERSION` (the words
 * changed in substance) asks again.
 */
import { AgentsError } from "./agents.js";
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

/*
 * Runs with no person in the moment (docs/accounts.md, "Runs with no person in the moment"): a door's message, an app's
 * event, a held one released, a hand-off, a retry, a task Coach hands a taf, a run going on after KIFF's alert, and a
 * schedule's firing. Each taf's runs take one person's consent: its maker's, recorded when it's made; for a taf from
 * before, the account's first yes from someone who manages its tafs; or that of a manager who took it over. While that
 * person's consent isn't given (withdrawn, or not to the current words), its runs start no model turn: tafs cloud
 * holds what came in and pauses the taf, with the reason, until it's given again or a manager takes it over.
 */

/** The refusal's code, beside a person route's: tafs cloud's own, never kiff/agents'. */
export const AI_CONSENT_CODE = "ai_consent_required";

/** Whether err is a run refused for its taf's consent: what waited for it (a door's message, an app's event) is held. */
export function waitsForConsent(err: unknown): boolean {
  return err instanceof AgentsError && err.code === AI_CONSENT_CODE;
}

/** Whose consent one taf's runs take. */
export const tafConsentKey = (tenant: string, taf: string) => `${tenant}#ai-consent-taf#${taf}`;
/** The account's first yes from someone who manages its tafs: what a taf from before takes. */
export const firstYesKey = (tenant: string) => `${tenant}#ai-consent-first`;
/** A taf tafs cloud paused for consent, with why: only these does a yes resume, never an owner's own halt. */
export const consentPauseKey = (tenant: string, taf: string) => `${tenant}#ai-consent-paused#${taf}`;

/** Whose consent a taf's runs take, and how they came to: they made it, they took it over, or the account's first yes. */
export interface TafConsent { subject: string; at: string; by: "maker" | "took_over" | "first_yes" }

/** Why a taf's runs can't start a model turn: no one's consent is on record for it, or that person's isn't given. */
export type ConsentWhy = "no_one" | "not_given";

/** A pause for consent; owner_halted: its owner halted it too, so a yes ends the pause and leaves their halt. */
export interface ConsentPause { why: ConsentWhy; at: string; owner_halted?: boolean }

/** Records who made the taf: its runs take their consent. */
export async function recordMaker(store: CoachStore | undefined, tenant: string, taf: string, subject: string, now: Date): Promise<void> {
  if (!store) return;
  await store.put(tafConsentKey(tenant, taf), { subject, at: now.toISOString(), by: "maker" } satisfies TafConsent, new Date(now.getTime() + CONSENT_KEPT_MS));
}

/** A manager takes the taf's runs over with their own consent, which must be given. */
export async function takeOver(store: CoachStore, tenant: string, taf: string, subject: string, now: Date): Promise<TafConsent> {
  const t: TafConsent = { subject, at: now.toISOString(), by: "took_over" };
  await store.put(tafConsentKey(tenant, taf), t, new Date(now.getTime() + CONSENT_KEPT_MS));
  return t;
}

/** Keeps the account's first yes from someone who manages its tafs; a later one changes nothing. */
export async function recordFirstYes(store: CoachStore, tenant: string, subject: string, now: Date): Promise<void> {
  if (await store.get(firstYesKey(tenant)).catch(() => undefined)) return;
  await store.put(firstYesKey(tenant), { subject, at: now.toISOString(), by: "first_yes" } satisfies TafConsent, new Date(now.getTime() + CONSENT_KEPT_MS));
}

/** Whose consent the taf's runs take, or undefined when none is on record. A read that fails reads as none. */
export async function tafConsentOf(store: CoachStore | undefined, tenant: string, taf: string): Promise<TafConsent | undefined> {
  if (!store) return undefined;
  const own = await store.get(tafConsentKey(tenant, taf)).catch(() => undefined) as TafConsent | undefined;
  if (typeof own?.subject === "string") return own;
  const first = await store.get(firstYesKey(tenant)).catch(() => undefined) as TafConsent | undefined;
  return typeof first?.subject === "string" ? first : undefined;
}

/** Whether a run of the taf with no person in the moment may start a model turn: whose consent it took, or why not. */
export async function unattendedConsent(store: CoachStore | undefined, tenant: string, taf: string): Promise<{ subject: string } | { why: ConsentWhy }> {
  const whose = await tafConsentOf(store, tenant, taf);
  if (!whose) return { why: "no_one" };
  return (await aiConsentOf(store, tenant, whose.subject)) ? { subject: whose.subject } : { why: "not_given" };
}

/** The taf's pause for consent, if tafs cloud made one. */
export async function consentPauseOf(store: CoachStore | undefined, tenant: string, taf: string): Promise<ConsentPause | undefined> {
  if (!store) return undefined;
  const p = await store.get(consentPauseKey(tenant, taf)).catch(() => undefined) as ConsentPause | undefined;
  return p && (p.why === "no_one" || p.why === "not_given") ? p : undefined;
}

/** The words for a taf paused for consent, in the person's language. */
export function consentPauseWords(why: ConsentWhy, lang: Lang): string {
  return why === "no_one" ? wordsIn(lang).ai_consent_paused_no_one : wordsIn(lang).ai_consent_paused_not_given;
}
