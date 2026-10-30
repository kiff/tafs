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
