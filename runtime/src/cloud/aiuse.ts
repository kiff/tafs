/**
 * AI fair use (kiff/tafs#193), from before credits: chats with the tafs and
 * Setup were bounded per period by a share of the plan's price. Since credits
 * are the balance, tafs cloud sends kiff/agents no bound with a chat, and
 * GET /v1/usage/ai answers that there is none (docs/accounts.md, "In the apps").
 * What's left here is that answer, which older apps still read.
 */

/** What the app shows. No bound: nulls, and never paused. */
export interface AIView {
  used_pct: number | null;
  asks_at_pct: number | null;
  paused: boolean;
  /**
   * Whether this person may go on (billing.manage): the apps show **Go on** only then, signed
   * through KIFF's `PUT /v1/me/usage/ai` (kiff/tafs#185).
   */
  can_go_on: boolean;
  /** When the AI use starts again by itself: the period's end (RFC 3339), null when KIFF gives none. */
  starts_again: string | null;
  /** The same, in words the apps show as they are. */
  words: string;
}

/**
 * What GET /v1/usage/ai answers since credits are the balance: no bound, nothing used, never
 * waiting. canGoOn is passed through as the apps from before credits read it.
 */
export function view(canGoOn = false): AIView {
  return { used_pct: null, asks_at_pct: null, paused: false, can_go_on: canGoOn, starts_again: null, words: "Your plan sets no AI limit for your tafs." };
}
