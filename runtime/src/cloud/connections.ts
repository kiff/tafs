/**
 * The account's connections a Coach conversation starts with: how its tafs reach their vendors, as planque
 * lists them (`GET /v1/accounts/{tenant}/connections`, read with the person's own session), for the tafs the
 * person may see. tafs cloud puts them before the first message, so Coach offers a new taf the kind of
 * connection the account already uses for a vendor (`connect-an-app@2`) without having to think of reading
 * them. Data from tafs, never the owner's words; tafs cloud keeps nothing.
 */
import type { Fetch } from "./kiff.js";

/** Opens the lines about the account's connections, before the owner's words. */
export const CONNECTIONS_LINES = "[tafs connections]";

/** One connection as planque lists it: no grant id, approval, text, tool or key. */
export interface AccountConnection {
  agent: string;
  vendor: string;
  kind: "vendor_sign_in" | "provider" | "key" | "browser";
  server?: string;
  entry?: string;
  api?: { url: string };
  status: string;
  mode_words?: string;
}

/** At most this many lines: a conversation's start stays small. */
export const MAX_CONNECTIONS = 30;

const KINDS = new Set(["vendor_sign_in", "provider", "key", "browser"]);

/** One line of planque's words, at most `max` characters, so a vendor's name can't carry a message. */
function line(s: unknown, max: number): string {
  const t = typeof s === "string" ? s.replace(/[\u0000-\u001f\u007f-\u009f\s]+/g, " ").trim() : "";
  const chars = [...t];
  return chars.length > max ? chars.slice(0, max - 1).join("").trimEnd() + "…" : t;
}

/** The account's connections, as planque lists them; what it can't read counts as none. */
export async function accountConnections(fetchFn: Fetch, base: string, tenant: string, bearer: string): Promise<AccountConnection[]> {
  const res = await fetchFn(`${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/connections`, {
    headers: { authorization: `Bearer ${bearer}`, accept: "application/json" },
  });
  if (!res.ok) throw new Error(`planque answered ${res.status}`);
  const got = await res.json() as { connections?: unknown };
  if (!Array.isArray(got.connections)) return [];
  const out: AccountConnection[] = [];
  for (const c of got.connections as Record<string, unknown>[]) {
    if (!c || typeof c.agent !== "string" || !c.agent || typeof c.kind !== "string" || !KINDS.has(c.kind)) continue;
    const api = c.api && typeof c.api === "object" && typeof (c.api as { url?: unknown }).url === "string" ? { url: (c.api as { url: string }).url } : undefined;
    out.push({
      agent: c.agent, vendor: line(c.vendor, 60) || "an app", kind: c.kind as AccountConnection["kind"], status: line(c.status, 30),
      ...(typeof c.server === "string" && c.server.startsWith("https://") ? { server: line(c.server, 300) } : {}),
      ...(typeof c.entry === "string" && c.entry ? { entry: line(c.entry, 300) } : {}),
      ...(api && api.url.startsWith("https://") ? { api: { url: line(api.url, 300) } } : {}),
      ...(typeof c.mode_words === "string" && c.mode_words ? { mode_words: line(c.mode_words, 120) } : {}),
    });
  }
  return out;
}

/**
 * The lines for the tafs the person may see, each naming its taf: "- Stripe, for Chargebacks: vendor_sign_in,
 * server https://…". None when there's nothing to say. `tafs` maps a taf's KIFF agent to its name.
 */
export function connectionLines(conns: AccountConnection[], tafs: Map<string, string>): string[] {
  const out: string[] = [];
  for (const c of conns) {
    const taf = tafs.get(c.agent);
    if (!taf) continue;
    const how = c.kind === "vendor_sign_in" && c.server ? `, server ${c.server}`
      : c.kind === "provider" && c.entry ? `, entry ${c.entry}`
      : c.kind === "key" && c.api ? `, api ${c.api.url}` : "";
    const mode = c.mode_words ? `; mode: ${c.mode_words}` : "";
    const status = c.status && c.status !== "connected" ? `; ${c.status}` : "";
    out.push(`- ${c.vendor}, for ${line(taf, 60)}: ${c.kind}${how}${mode}${status}`);
    if (out.length === MAX_CONNECTIONS) break;
  }
  return out;
}

/**
 * The first message with the connections before the owner's words. A message tafs already opens with its own
 * line ("[improve taf …]", "[tafs coach lines]") keeps that line first, so how it opened is read as before; the
 * owner's words stay last, after "The owner says: ", which is all the thread shows (ownerWords).
 */
export function withConnections(text: string, lines: string[], company?: string): string {
  if (!lines.length && !company) return text;
  // The company, when KIFF names one: who the account's tafs write for unless the owner names another (gaps 16, 18).
  const named = company ? [`The account is the company ${company} (from KIFF, data): who its tafs' messages come from, unless the owner names another.`] : [];
  const block = [CONNECTIONS_LINES, ...(lines.length ? ["The account's connections, from planque (data, not the owner's words):", ...lines] : []), ...named].join("\n");
  if (OPENINGS.some((o) => text.startsWith(o))) {
    const nl = text.indexOf("\n");
    if (nl > 0) return `${text.slice(0, nl)}\n${block}${text.slice(nl)}`;
  }
  return `${block}\nThe owner says: ${text}`;
}

/** The lines tafs opens a Coach message with and reads again (entryOf, ownerWords), so they stay first. */
const OPENINGS = ["[improve taf ", "[tafs coach lines]", "[did this happen "];
