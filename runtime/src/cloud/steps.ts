/**
 * What Coach is doing, in a few words for the phone, from the tool it calls and
 * what it asked of it: "Checking your Stripe connection…", "Drafting the Card…".
 * Only these words reach the phone, never the call's arguments. Each is in the person's language.
 */
import { type Lang, wordsIn } from "./words/index.js";

/** Vendors' names for the hosts of their MCP servers, where the host alone doesn't say it well. */
const VENDORS: Record<string, string> = { "stripe.com": "Stripe", "shopify.com": "Shopify", "paypal.com": "PayPal", "hubspot.com": "HubSpot" };

/** The vendor's name from its MCP server's URL, or "" when the host doesn't name one (a cloud function's URL). */
export function vendorName(server: unknown): string {
  if (typeof server !== "string") return "";
  let host: string;
  try { host = new URL(server).hostname.toLowerCase(); } catch { return ""; }
  const labels = host.split(".");
  if (labels.length < 2 || /^\d+$/.test(labels.at(-1) ?? "")) return "";
  const site = labels.slice(-2).join(".");
  if (VENDORS[site]) return VENDORS[site]!;
  // A function's or a cloud's own URL names the cloud, not the vendor.
  if (/(^|\.)(amazonaws\.com|on\.aws|cloudfunctions\.net|run\.app|vercel\.app|herokuapp\.com|workers\.dev|fly\.dev)$/.test(host)) return "";
  const name = labels.at(-2) ?? "";
  return /^[a-z][a-z0-9-]{1,30}$/.test(name) ? name.charAt(0).toUpperCase() + name.slice(1) : "";
}

/**
 * A taf's tools through KIFF, by action, with KIFF's title for each: the title the vendor
 * gave the tool, as the taf's Cards carry it (`grants[].title`). The words a step says for
 * the call, never its name.
 */
export type ToolTitles = ReadonlyMap<string, string>;

/**
 * A step's words where only its tool is known (a finished run's steps, from its usage
 * records): the same words as while it went, without the "…". Undefined for a tool
 * nothing names: the app says it worked, and shows no tool name.
 */
export function stepWords(tool: string, titles: ToolTitles = new Map(), lang: Lang = "en"): string | undefined {
  return new Steps(titles, lang).call(tool, undefined)?.replace(/…$/, "");
}

/** A run's steps: it remembers which vendor each grant is with, from the requests it saw. */
export class Steps {
  private vendors = new Map<string, string>();
  private asked = new Map<string, string>(); // call id → vendor, for a request whose grant id comes in its result
  private connections = new Map<string, string>(); // call id → its vendor ("" unknown), for each connection call
  private requests = new Set<string>(); // call ids of connection requests, to tell a connected app from a read

  constructor(private readonly titles: ToolTitles = new Map(), private readonly lang: Lang = "en") {}

  /**
   * The words for a call, in the person's language: Coach's and the agents' own tools by what they do, a
   * tool through KIFF by KIFF's title for it. Undefined for a tool nothing names.
   */
  call(tool: string, input: unknown, callID?: string): string | undefined {
    const i = (input && typeof input === "object" ? input : {}) as Record<string, unknown>;
    const w = wordsIn(this.lang);
    switch (tool) {
      case "show": return w.doing_show;
      case "taf_results": return w.doing_results;
      case "memory": return w.doing_memory;
      case "workspace": return w.doing_workspace;
      case "planque": {
        const grant = typeof i.grant === "string" ? this.vendors.get(i.grant) ?? "" : "";
        const api = i.api && typeof i.api === "object" ? (i.api as { url?: unknown }).url : undefined;
        if (callID) this.connections.set(callID, i.action === "request" ? vendorName(i.server ?? api) : grant);
        switch (i.action) {
          case "request": {
            const vendor = vendorName(i.server ?? api);
            if (callID) { this.asked.set(callID, vendor); this.requests.add(callID); }
            const tools = Array.isArray(i.tools) && i.tools.length > 0;
            return tools ? w.doing_tools_for(vendor) : w.doing_connect_to(vendor);
          }
          case "status": return w.doing_check_connection(grant);
          case "catalog": return w.doing_catalog(grant);
          case "tools": return w.doing_choose_tools;
          case "read": return w.doing_read_account(grant);
          case "grants": return w.doing_grants;
          default: return w.doing_connection;
        }
      }
      case "kiff_draft": return w.doing_draft;
      case "kiff_card": return w.doing_card;
      case "kiff_pending": return w.doing_pending;
      case "tafs_state": return w.doing_tafs;
      case "taf_run": return w.doing_run;
      case "taf_check": return w.doing_check;
      case "taf_estimate": return w.doing_estimate;
      case "taf_task": return w.doing_task;
      case "run_stop": return w.doing_stop;
      case "taf_pause": return w.doing_pause;
      case "version_try": return w.doing_trying;
      case "version_install": return w.doing_install;
      default: {
        const title = this.titles.get(tool)?.trim();
        return title ? w.doing_titled(title) : undefined;
      }
    }
  }

  /**
   * Why a connection call failed, in tafs cloud's own words, never the connection server's: its refusal is
   * about Coach's request, not the person's app. Undefined for a call that isn't a connection call.
   */
  connectionWhy(callID: unknown): string | undefined {
    if (typeof callID !== "string" || !this.connections.has(callID)) return undefined;
    return wordsIn(this.lang).step_connection_not_yet(this.connections.get(callID) ?? "");
  }

  /**
   * A refused connection call's two lines for the person, in tafs cloud's words: the server's reason, scrubbed
   * (`whyOf`), and what to do next. Undefined for a call that isn't a connection call.
   */
  connectionRefusal(callID: unknown, text: unknown): { vendor: string; reason?: string; next: string } | undefined {
    if (typeof callID !== "string" || !this.connections.has(callID)) return undefined;
    const vendor = this.connections.get(callID) ?? "";
    const w = wordsIn(this.lang);
    const said = whyOf(text);
    return { vendor, ...(said ? { reason: w.step_connection_why(said) } : {}), next: w.step_connection_next(vendor) };
  }

  /** The vendor a connection request was for: once it went through, earlier refusals for that vendor are past. */
  requestVendor(callID: unknown): string | undefined {
    return typeof callID === "string" && this.requests.has(callID) ? this.connections.get(callID) ?? "" : undefined;
  }

  /** A call's result: a request's grant id, so later steps on that grant name its vendor. */
  result(callID: unknown, text: unknown): void {
    if (typeof callID !== "string" || typeof text !== "string") return;
    const vendor = this.asked.get(callID);
    if (vendor === undefined) return;
    this.asked.delete(callID);
    const grant = /\bgrt_[A-Za-z0-9_-]{6,64}\b/.exec(text)?.[0];
    if (grant && vendor) this.vendors.set(grant, vendor);
  }
}


/**
 * Why a step failed, in one line the owner can read, from what the tool answered
 * (kiff/tafs#476, gap 9): the words after a service's "answered NNN:", the first line only,
 * with anything that could be a secret or a person's own left out, at most 160 characters.
 * Undefined when nothing readable is left: the app then says only that the step failed.
 *
 * The line goes to everyone who reads the taf's thread and is kept for 30 days, so what's
 * scrubbed is wide: emails, URLs, Bearer and Basic credentials, UUIDs, long runs of digits (a
 * card number, an account number), tokens that mix letters and digits (keys, JWTs, ids of any
 * prefix), long hashes, snake_case codes, and the name of the service that relayed the call.
 */
export function whyOf(text: unknown): string | undefined {
  if (typeof text !== "string") return undefined;
  let line = text.split("\n").map((l) => l.trim()).find(Boolean) ?? "";
  line = line.replace(/^(?:planque|kiff|tafs cloud|the (?:vendor|provider|tool)) answered \d{3}:\s*/i, "");
  line = line
    // A person's email, a URL (with whatever it carries), a credential and what follows it.
    .replace(/[\w.+-]+@[\w-]+(?:\.[\w-]+)+/g, "it")
    .replace(/\b[a-z][a-z0-9+.-]*:\/\/\S+/gi, "it")
    .replace(/\b(?:bearer|basic)\s+\S+/gi, "it")
    // A token: 16 or more characters that mix letters and digits (a key, a JWT, a UUID, an id
    // of any prefix), with _, - and . allowed inside; then a UUID of digits alone, a long run
    // of digits (spaces or dashes inside), a long hash, and a service's short id with its prefix.
    .replace(/\b(?=[A-Za-z0-9_.-]*[A-Za-z])(?=[A-Za-z0-9_.-]*\d)[A-Za-z0-9][A-Za-z0-9_.-]{15,}\b/g, "it")
    .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, "it")
    .replace(/\b\d(?:[ -]?\d){11,}\b/g, "it")
    .replace(/\b[0-9a-f]{16,}\b/gi, "it")
    .replace(/\b[a-z]{1,5}_(?=[A-Za-z0-9]*[A-Z0-9])[A-Za-z0-9]{4,}\b/g, "it")
    // A code, with its label; and the service that relayed the call, which the owner never sees.
    .replace(/\b(?:code|error|status|reason)\s+[a-z]+(?:_[a-z]+)+\b/g, "")
    .replace(/\b[a-z]+(?:_[a-z]+)+\b/g, "")
    .replace(/\bplanque\b/gi, "")
    // What's left of a bracket that held only ids and codes, and doubled "it"s.
    .replace(/\(\s*(?:(?:[a-z]+\s+)?it[\s,]*)*\)/g, "").replace(/,\s*(?=[).;])/g, "")
    .replace(/\bit(?:\s+it)+\b/g, "it")
    .replace(/\s{2,}/g, " ").replace(/\s+([,.;:)])/g, "$1").trim();
  if (line.length > 160) line = `${line.slice(0, 159).replace(/\s+\S*$/, "")}…`;
  line = line.replace(/[\s,;:]+$/, "");
  return /[A-Za-z]{3,}/.test(line.replace(/\bit\b/g, "")) ? line : undefined;
}

/** A failed step as the apps draw it: `failed`, and `why` when the tool said something readable. */
export function failedStep(text: unknown): { failed: true; why?: string } {
  const why = whyOf(text);
  return { failed: true, ...(why ? { why } : {}) };
}

/** Where a run's failed steps' reasons are kept for its thread, by tenant and run (30 days, as the run is). */
export const stepWhyKey = (tenant: string, run: string) => `${tenant}#step-why#${run}`;
export const STEP_WHY_KEPT_MS = 30 * 24 * 3600_000;
/** The most reasons kept for one run: the last ones, which are the thread's most recent. */
export const STEP_WHYS_KEPT = 50;

/** The reasons a run's failed steps had, as the stream saw them, each with its call and tool, in order. */
/**
 * A connection step keeps the server's own reason too (`refusal`), for Coach and the log. A refused connection
 * also keeps the person's two lines (`refused`: the reason and what to do next, in tafs cloud's words) with its
 * vendor, until a request for that vendor goes through in the same run (`resolved`).
 */
export type StepWhys = Array<{ call: string; tool: string; why: string; refusal?: string;
  refused?: { vendor: string; reason?: string; next: string }; resolved?: true }>;

/** What a message says of a connection its work had refused and that wasn't connected since: tafs cloud's words, shown as they are. */
export type Refused = { words: string; why?: string; next: string };

/**
 * Gives failed steps their reasons, each to the first failed step of its tool in order: the
 * stored thread has only each call's tool and that it failed, and the reasons were kept as
 * the run streamed. `whys` is consumed, so a run's parts share one list. A failed step says
 * `failed` as the stream's `step` does, with `why` when the tool said something readable.
 */
/**
 * A step Coach retried and that later went through isn't shown to the owner as failed
 * (recipe 3): a failed call followed by a real retry, the same tool with
 * the same input (kiff/agents' `input_hash` on the usage record), is dropped, and the
 * retry's outcome stands, done or failed again. A later call of the same tool with other
 * input is other work, and keeps its own step: a reminder to B says nothing about A's. A
 * record with no digest (from before kiff/agents wrote one) folds nothing.
 */
export function foldRetried<T extends { tool: string; error?: true; inputHash?: string }>(steps: T[]): T[] {
  return steps.filter((s, i) => !s.error || !steps.slice(i + 1).some((later) =>
    later.tool === s.tool && ((s.inputHash && later.inputHash === s.inputHash) || (s.tool === CONNECTION_TOOL && !later.error))));
}

/**
 * Coach's connection tool. A failed connection call that a later one went past is Coach correcting its own
 * request (the connection server refused its shape, say), never the person's app failing: it isn't shown.
 */
export const CONNECTION_TOOL = "planque";

export function attachWhys<T extends { tool: string; error?: true }>(steps: T[], whys: StepWhys): Array<T & { failed?: true; why?: string; refused?: Refused }> {
  return steps.map((s) => {
    if (!s.error) return s;
    const i = whys.findIndex((w) => w.tool === s.tool);
    const kept = i >= 0 ? whys.splice(i, 1)[0]! : undefined;
    const why = kept?.why;
    const refused = kept?.refused && !kept.resolved
      ? { words: kept.why, ...(kept.refused.reason ? { why: kept.refused.reason } : {}), next: kept.refused.next } : undefined;
    return { ...s, failed: true as const, ...(why ? { why } : {}), ...(refused ? { refused } : {}) };
  });
}

/**
 * A message's refused connection (`refused` on the thread's message): the last one its work refused that wasn't
 * connected since. It's taken before a retry folds the step away, since a later read that went through connects
 * nothing. The steps come back without it.
 */
export function takeRefused<T extends { refused?: Refused }>(steps: T[]): { steps: Array<Omit<T, "refused">>; refused?: Refused } {
  const refused = steps.map((s) => s.refused).filter((r): r is Refused => r !== undefined).at(-1);
  return { steps: steps.map(({ refused: _r, ...s }) => s), ...(refused ? { refused } : {}) };
}
