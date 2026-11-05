/**
 * What Coach is doing, in a few words for the phone, from the tool it calls and
 * what it asked of it: "Checking your Stripe connection…", "Drafting the Card…".
 * Only these words reach the phone, never the call's arguments.
 */

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
export function stepWords(tool: string, titles: ToolTitles = new Map()): string | undefined {
  return new Steps(titles).call(tool, undefined)?.replace(/…$/, "");
}

/** A run's steps: it remembers which vendor each grant is with, from the requests it saw. */
export class Steps {
  private vendors = new Map<string, string>();
  private asked = new Map<string, string>(); // call id → vendor, for a request whose grant id comes in its result

  constructor(private readonly titles: ToolTitles = new Map()) {}

  /**
   * The words for a call: Coach's and the agents' own tools by what they do, a tool
   * through KIFF by KIFF's title for it. Undefined for a tool nothing names.
   */
  call(tool: string, input: unknown, callID?: string): string | undefined {
    const i = (input && typeof input === "object" ? input : {}) as Record<string, unknown>;
    const your = (vendor: string) => (vendor ? `your ${vendor}` : "your");
    switch (tool) {
      case "show":
        return "Showing you a card…";
      case "taf_results":
        return "Setting where its results go…";
      case "memory":
        return "Going through its notes…";
      case "workspace":
        return "Working in its workspace…";
      case "planque": {
        const grant = typeof i.grant === "string" ? this.vendors.get(i.grant) ?? "" : "";
        switch (i.action) {
          case "request": {
            const vendor = vendorName(i.server);
            if (callID) this.asked.set(callID, vendor);
            const tools = Array.isArray(i.tools) && i.tools.length > 0;
            return tools ? `Preparing ${vendor ? `${vendor}'s` : "the"} tools for your approval…` : `Preparing the connection to ${vendor || "your provider"}…`;
          }
          case "status": return `Checking ${your(grant)} connection…`;
          case "catalog": return `Reading what ${grant || "your provider"} offers…`;
          case "tools": return "Choosing the tools…";
          case "read": return `Reading from ${your(grant)} account…`;
          case "grants": return "Looking up your connections…";
          default: return "Working on the connection…";
        }
      }
      case "kiff_draft": return "Drafting the Card…";
      case "kiff_card": return "Reading the taf's Card…";
      case "kiff_pending": return "Checking what waits for you…";
      case "tafs_state": return "Looking at your tafs…";
      case "taf_run": return "Checking the taf's run…";
      case "taf_task": return "Handing the task over…";
      case "run_stop": return "Stopping the run…";
      case "taf_pause": return "Pausing the taf…";
      case "version_try": return "Trying the new version…";
      case "version_install": return "Preparing the update…";
      default: {
        const title = this.titles.get(tool)?.trim();
        return title ? `${title}…` : undefined;
      }
    }
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
export type StepWhys = Array<{ call: string; tool: string; why: string }>;

/**
 * Gives failed steps their reasons, each to the first failed step of its tool in order: the
 * stored thread has only each call's tool and that it failed, and the reasons were kept as
 * the run streamed. `whys` is consumed, so a run's parts share one list. A failed step says
 * `failed` as the stream's `step` does, with `why` when the tool said something readable.
 */
export function attachWhys<T extends { tool: string; error?: true }>(steps: T[], whys: StepWhys): Array<T & { failed?: true; why?: string }> {
  return steps.map((s) => {
    if (!s.error) return s;
    const i = whys.findIndex((w) => w.tool === s.tool);
    const why = i >= 0 ? whys.splice(i, 1)[0]!.why : undefined;
    return { ...s, failed: true as const, ...(why ? { why } : {}) };
  });
}
