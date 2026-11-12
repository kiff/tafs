/**
 * The secure connection sheet for an app's key (docs/secure-connection.md): every word the phone draws, worked
 * out from the grant the connection server shows this phone. The phone decides nothing here: whether the taf may
 * only read is tafs cloud's to say, from the grant's own tools, and a grant with any tool that isn't marked a read
 * (or with no tools, or no flags) never reads as read only.
 */
import type { ApprovalGrant } from "./approval.js";
import { type Lang, wordsIn } from "./words/index.js";

/** An API-key grant's request, in what the sheet reads (the connection server's `api`). */
export interface KeyRequest {
  kind?: string;
  catalog_entry?: string;
  api?: { url?: string; digest?: string; base?: string; auth?: { in?: string; name?: string } };
}

export interface Sheet {
  vendor: { name: string; logo?: string; host?: string };
  title: string;
  lines: Array<{ kind: "reads_only" | "may_change" | "sealed" | "disconnect"; text: string }>;
  paste: { button: string; what: string; pasted: string; cleared: string };
  details: { title: string; lines: Array<{ label: string; value: string }> };
  card_title: string;
  legal: { text: string; terms: { words: string; url: string }; privacy: { words: string; url: string } };
  button: string;
  receipt: { text: string; disconnect: string };
}

/** Whether a grant takes a key: an API-key grant (`kind: "api_key"`). */
export function takesKey(grant: ApprovalGrant): boolean {
  return (grant.request as KeyRequest).kind === "api_key";
}

/** Read only, by the grant's own tools: at least one, and every one marked `read_only: true`. */
export function readsOnly(grant: ApprovalGrant): boolean {
  const tools = grant.request.tools ?? [];
  return tools.length > 0 && tools.every((t) => t.read_only === true);
}

/** The host a URL names, or undefined for one that isn't https. */
function hostOf(url: string | undefined): string | undefined {
  try {
    const u = new URL(url ?? "");
    return u.protocol === "https:" ? u.host : undefined;
  } catch {
    return undefined;
  }
}

/** A line of the connection server's, as one short line: no control characters, at most `n` characters. */
function flat(s: string | undefined, n = 300): string {
  return (s ?? "").replace(/[\u0000-\u001f\u007f-\u009f]+/g, " ").trim().slice(0, n);
}

/** The app's Terms and Privacy Policy in the person's language, as the apps' own legal links are. */
export function legalLinks(lang: Lang): { terms: string; privacy: string } {
  const at = lang === "en" ? "https://kiff.dev" : `https://kiff.dev/${lang}`;
  return { terms: `${at}/terms`, privacy: `${at}/privacy` };
}

/**
 * The sheet for a key grant: the vendor as the catalog names it (`name`, with its logo as tafs cloud serves it),
 * the three lines, the paste words, the technical lines under Details, the legal line and the receipt. `{last4}`
 * is the phone's to fill, from the pasted key; `{terms}` and `{privacy}` are each drawn as their link.
 */
export function sheetOf(grant: ApprovalGrant, lang: Lang, app: { name?: string; logo?: string } = {}): Sheet {
  const w = wordsIn(lang);
  const req = grant.request as ApprovalGrant["request"] & KeyRequest;
  const name = flat(app.name || grant.vendor || hostOf(req.api?.base) || req.server, 60) || "the app";
  const host = hostOf(req.api?.base) ?? hostOf(req.server);
  const only = readsOnly(grant);
  const details: Array<{ label: string; value: string }> = [];
  if (hostOf(req.api?.url)) details.push({ label: w.sheet_spec, value: flat(req.api!.url) });
  if (req.api?.digest && /^[0-9a-f]{64}$/.test(req.api.digest)) details.push({ label: w.sheet_sha, value: req.api.digest });
  if (host) details.push({ label: w.sheet_sent_to, value: host });
  if (req.api?.auth?.in === "header" && req.api.auth.name) details.push({ label: w.sheet_header, value: flat(req.api.auth.name, 80) });
  const links = legalLinks(lang);
  return {
    vendor: { name, ...(app.logo ? { logo: app.logo } : {}), ...(host ? { host } : {}) },
    title: w.sheet_title(name),
    lines: [
      only ? { kind: "reads_only", text: w.sheet_reads_only(name) } : { kind: "may_change", text: w.sheet_may_change(name) },
      { kind: "sealed", text: w.sheet_sealed },
      { kind: "disconnect", text: w.sheet_leave },
    ],
    paste: { button: w.sheet_paste, what: w.sheet_paste_what(name), pasted: w.sheet_pasted, cleared: w.sheet_cleared },
    details: { title: w.sheet_details, lines: details },
    card_title: w.sheet_card_title,
    legal: { text: w.sheet_legal, terms: { words: w.sheet_terms, url: links.terms }, privacy: { words: w.sheet_privacy, url: links.privacy } },
    button: w.sheet_button,
    receipt: { text: w.sheet_receipt(name), disconnect: w.sheet_disconnect },
  };
}
