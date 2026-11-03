/**
 * Result places at a vendor (RFC 052 B2): a Notion page, an email from the owner's Gmail, a Teams channel.
 * Unlike the built-in places, which tafs cloud delivers itself, the taf writes these with its own connected
 * tool, a write through KIFF inside its Card, so a version's instructions carry them and they go live with it.
 */

export type VendorPlace =
  | { place: "notion"; page: string }
  | { place: "gmail"; to: string }
  | { place: "teams"; team: string; channel: string };

const ID = /^[A-Za-z0-9:._@-]{1,200}$/;
const EMAIL = /^[^\s@<>"]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,}$/;

/** A vendor place from a request, checked; an Error says what's wrong, in words. */
export function vendorPlace(v: unknown): VendorPlace {
  const o = (v && typeof v === "object" ? v : {}) as Record<string, unknown>;
  const s = (k: string) => (typeof o[k] === "string" ? (o[k] as string).trim() : "");
  switch (o.place) {
    case "notion":
      if (!ID.test(s("page"))) throw new Error("a Notion place names its page's id");
      return { place: "notion", page: s("page") };
    case "gmail":
      if (!EMAIL.test(s("to"))) throw new Error("a Gmail place names the address it emails");
      return { place: "gmail", to: s("to") };
    case "teams":
      if (!ID.test(s("team")) || !ID.test(s("channel"))) throw new Error("a Teams place names its team's id and its channel's id");
      return { place: "teams", team: s("team"), channel: s("channel") };
    case "slack":
      throw new Error("Slack as a result place comes with the taf's Slack door");
  }
  throw new Error("a place is door_reply, app, owner_email, or {place: notion | gmail | teams, …}");
}

/** The place on the dial's Results ring, and its line. */
export function vendorWords(p: VendorPlace): { label: string; words: string } {
  switch (p.place) {
    case "notion": return { label: "Notion", words: `Adds a page under Notion page ${p.page}` };
    case "gmail": return { label: "Gmail", words: `Emails ${p.to} each result, from your Gmail` };
    case "teams": return { label: "Teams", words: `Posts each result in Teams channel ${p.channel}` };
  }
}

/** The same place as one line of the words a dial change answers. */
export function vendorLine(p: VendorPlace): string {
  switch (p.place) {
    case "notion": return `notion page ${p.page}`;
    case "gmail": return `gmail to ${p.to}`;
    case "teams": return `teams channel ${p.channel}`;
  }
}

/** What heads them in a version's instructions. */
export const VENDOR_PLACES_INTRO = "Where your results also go:\n";

/** A version's instructions for its vendor places: the taf writes each one itself, inside its Card. */
export function vendorInstructions(ps: VendorPlace[]): string | undefined {
  if (!ps.length) return undefined;
  const line = (p: VendorPlace) => {
    switch (p.place) {
      case "notion": return `- Add a page with the result under the Notion page ${p.page}, with your Notion tool.`;
      case "gmail": return `- Email the result to ${p.to}, with your Gmail tool.`;
      case "teams": return `- Post the result in the Teams channel ${p.channel} of team ${p.team}, with your Teams tool.`;
    }
  };
  return `${VENDOR_PLACES_INTRO}${ps.map(line).join("\n")}\n` +
    "Do each one at the end of every request, as a call through KIFF like any other, inside your Card. " +
    "If you have no such tool, or KIFF refuses or holds the call, say so in your result; never skip one silently.";
}
