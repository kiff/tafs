/**
 * Coach reads the page in front only with the person's page consent (docs/chrome.md, "Coach reads the page you're on"):
 * off until they allow it on the panel's screen, which says what goes to the AI model and when. Two records must hold before
 * the tab is touched: the allow kept here for this pairing and this person (wiped with the pairing, which an unpair and a
 * sign-out both end), and tafs cloud's consent for the current words. Either missing, the words go alone.
 */

/**
 * The page consent's words' version: tafs cloud's PAGE_CONSENT_VERSION. Raised when its words change, so everyone is asked
 * again; not for a rewording that stays true ("the Claude AI model" in place of "Anthropic's Claude" kept "2"), and not for
 * a change of provider: these words name only the model, and the AI consent, which names who runs it, is the one asked
 * again then. A page only ever goes with a turn, and every turn needs that AI consent at its exact version, so no one's
 * page reaches a new provider before they've said yes to the consent that names it.
 */
export const PAGE_CONSENT_VERSION = "2";

/** Whether the allow kept here is this person's, on this pairing's session, for the current words. */
export function allowedHere(session, allow) {
  return Boolean(session && allow && allow.version === PAGE_CONSENT_VERSION && allow.tenant === session.tenant && allow.subject === session.subject);
}

/**
 * The page that goes with a send: `read()` only when the words don't go alone and `consent()` holds, so without the
 * consent nothing of the tab is ever read. Otherwise no page, and why: "alone" or "consent".
 */
export async function pageForSend({ alone, consent, read }) {
  if (alone) return { page: null, why: "alone" };
  if (!(await consent())) return { page: null, why: "consent" };
  return read();
}
