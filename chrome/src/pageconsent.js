/**
 * Coach reads the page in front only with the person's page consent (docs/chrome.md, "Coach reads the page you're on"):
 * off until they allow it on the panel's screen, which says what goes to the AI model and when. Two records must hold before
 * the tab is touched: the allow kept here for this pairing and this person (wiped with the pairing, which an unpair and a
 * sign-out both end), and tafs cloud's consent for the current words. Either missing, the words go alone.
 */

/**
 * The page consent's words' version: tafs cloud's PAGE_CONSENT_VERSION. Raised when what's read from the page, or who
 * receives it, changes (so everyone is asked again), not for a rewording that stays true: "the Claude AI model" in place of
 * "Anthropic's Claude" kept "2". Moving inference to another provider (Bedrock) changes who receives it: raise it then,
 * here and in tafs cloud together.
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
