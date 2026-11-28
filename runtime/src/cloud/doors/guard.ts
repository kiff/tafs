/**
 * A door reply's content check: what an outside writer must never be told, read before a reply goes on its own.
 * A paired sender is trusted to give the taf work, not to read the owner's setup: a reply that links a document,
 * carries a file or folder id, names an app behind the taf, or names another person who writes to it is held for
 * the owner instead. It reads only the reply's text; it never changes it.
 */

/** The apps a taf works through, as a reply might name them: never said to an outside writer. */
const APP_NAMES = [
  "google drive", "google sheets", "google docs", "gmail", "outlook", "onedrive", "sharepoint", "dropbox", "notion", "airtable",
  "slack", "hubspot", "salesforce", "pipedrive", "quickbooks", "xero", "qonto", "stripe", "shopify", "trello", "asana", "monday.com",
];

/** Links to a document or a folder, wherever they're hosted. */
const DOC_LINK = /\bhttps?:\/\/(?:docs|drive|sheets)\.google\.com\/|\b(?:[a-z0-9-]+\.)?sharepoint\.com\/|\bonedrive\.live\.com\/|\bdropbox\.com\/(?:s|scl|home)\/|\bnotion\.so\/|\bairtable\.com\/(?:app|shr)/i;

/** A long run of id characters, as a file, folder or sheet id is ("1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"). */
const FILE_ID = /(?<![A-Za-z0-9_-])(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{25,}(?![A-Za-z0-9_-])/;

export interface GuardContext {
  /** The writer the reply goes to: the one address it may name. */
  to: string;
  /** Everyone else paired with the taf's doors. */
  others: string[];
}

/** Why a reply can't go on its own, or none: each a short reason for the owner, never sent to the writer. */
export function replyConcerns(text: string, ctx: GuardContext): string[] {
  const out: string[] = [];
  const lower = text.toLowerCase();
  if (DOC_LINK.test(text)) out.push("it links a document or a folder");
  if (FILE_ID.test(text)) out.push("it carries what looks like a file or folder id");
  const named = APP_NAMES.filter((a) => new RegExp(`(?<![a-z])${a.replace(/[.]/g, "\\.")}(?![a-z])`, "i").test(lower));
  if (named.length) out.push(`it names ${named.join(", ")}`);
  const to = ctx.to.toLowerCase();
  const others = ctx.others.map((o) => o.toLowerCase()).filter((o) => o && o !== to && lower.includes(o));
  if (others.length) out.push("it names someone else who writes to this taf");
  return out;
}
