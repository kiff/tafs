/**
 * One-time codes in a page Coach reads (docs/chat.md, "Coach reads the page you're on"): tafs cloud's own mask, beside the
 * Chrome extension's (`chrome/src/mask.js`, `maskCodes`). The extension already masks them before it sends a page; this keeps
 * the promise at the server too, whatever build sends one. The patterns are the extension's, and the cases in
 * `test/fixtures/one-time-codes.json` hold the two to the same answers.
 *
 * A code is 4 to 8 digits, or two groups of 3 or 4 joined by a space (a non-breaking or thin one too) or a dash, named as one ("Your verification code is
 * 482913", "123456 is your login code", "votre code est 4821", "tu código es 4821", "Ihr Code lautet 4821"). The digits go,
 * the words stay. A postal, order or promo code stays.
 */

/** What a code's digits are replaced by, as the extension masks a secret. */
export const CODE_MASK = "[masked]";

const DIGITS = "(?<!\\d)(\\d{3,4}[ \\u00a0\\u2009\\u202f-]\\d{3,4}|\\d{4,8})(?!\\d)";
const NAMED = "(?:verification|security|confirmation|authentication|login|log-in|sign-in|signin|one-time|one time|access|2fa|mfa|otp)\\s+code" +
  "|one-time\\s+password|otp|passcode|code\\s+(?:de\\s+(?:vérification|verification|confirmation|sécurité|securite|connexion|validation)|à\\s+usage\\s+unique)" +
  "|código\\s+de\\s+(?:verificación|verificacion|confirmación|confirmacion|seguridad|acceso|validación|validacion|un\\s+solo\\s+uso)" +
  "|(?:bestätigungs|verifizierungs|sicherheits|anmelde|einmal|authentifizierungs)code";
const OTHER = "(?<!\\b(?:postal|zip|post|area|country|promo|discount|coupon|gift|tracking|product|error|status|order|source|tax|reference|ref)\\s)";
const WORD = "(?:code|código|codigo)";

const CODES = [
  // A code named for what it is, then the digits within a few words, on its line or the next: "verification code for Acme: 482913".
  new RegExp(`(\\b(?:${NAMED})\\b[^\\d]{0,40}?)${DIGITS}`, "giu"),
  // A bare "code" only with "is" or ":" right after it: "your code is 4821", "code: 4821".
  new RegExp(`(\\b(?:(?:your|votre|ton|tu|su|ihr|dein)\\s+)?${OTHER}${WORD}\\s*(?:(?:is|est|es|ist|lautet)\\s*:?|[:=])\\s*)${DIGITS}`, "giu"),
];
// The digits first: "482913 is your Acme verification code", "G-482913 est votre code".
const FIRST = new RegExp(`${DIGITS}(?=\\s+(?:is|est|es|ist)\\s+(?:your|votre|ton|tu|su|ihr|dein)\\b[^\\n]{0,40}?(?:code|código|codigo|otp|passcode|password)\\b)`, "giu");

/** Text with each one-time code's digits replaced, its words kept. */
export function maskedCodes(text: string): string {
  let out = text;
  for (const re of CODES) out = out.replace(re, (_all, lead: string) => lead + CODE_MASK);
  return out.replace(FIRST, CODE_MASK);
}
