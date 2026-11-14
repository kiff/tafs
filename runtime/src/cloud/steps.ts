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
  private targets = new Map<string, string>(); // call id → what it connects (the request's server or description, or the grant)
  private planqueCalls = new Set<string>(); // call ids of connection calls whose answer may name a grant's app
  private apps = new Map<string, string>(); // grant id → the app's name as planque answered it ("Google Sheets")
  private keyApps = new Set<string>(); // apps planque's answers this run listed with a key path (how: key), folded
  private keyRequests = new Set<string>(); // call ids of connection requests by a key (api)

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
        if (callID) this.planqueCalls.add(callID);
        if (callID && i.action === "request" && i.api) this.keyRequests.add(callID);
        // A reuse names the grant whose sign-in it uses: that's what it connects, and its app is that grant's.
        const reuse = i.action === "request" && typeof i.reuse === "string" && i.reuse ? i.reuse : undefined;
        const ofGrant = (g: unknown): string => typeof g === "string" ? this.vendors.get(g) ?? this.apps.get(g) ?? "" : "";
        const grant = ofGrant(i.grant);
        const api = i.api && typeof i.api === "object" ? (i.api as { url?: unknown }).url : undefined;
        if (callID) this.connections.set(callID, i.action === "request" ? (reuse ? ofGrant(reuse) : vendorName(i.server ?? api)) : grant);
        const target = i.action === "request" ? i.server ?? api ?? reuse : i.grant;
        // Setting a grant's tools that went through is Coach's corrected ask for that grant, whatever planque answers
        // (pending: the owner's approval comes next): an earlier refused tools call on it is past (14 Nov, 13:13 UTC:
        // "Coach couldn't connect the app yet" in red over a grant the owner had just approved).
        if (callID && i.action === "tools" && typeof i.grant === "string") this.requests.add(callID);
        if (callID && typeof target === "string" && target) this.targets.set(callID, target);
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
      case "web_read": return w.doing_web_read;
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
   * for the person (`shownWhy`), and what to do next. With what it connects (`key`: the request's server or
   * description, or the grant), so two apps whose names aren't known stay apart. Undefined for a call that isn't
   * a connection call.
   */
  connectionRefusal(callID: unknown, text: unknown): { vendor: string; key?: string; reason?: string; next: string } | undefined {
    if (typeof callID !== "string" || !this.connections.has(callID)) return undefined;
    // A guard on the order of Coach's own steps ("read the app's tool catalog before selecting this action") is about how
    // Coach asked, never the person's app: Coach reads it and goes on, and the person is shown neither its words nor a
    // way forward that isn't theirs to take (go-to-market job 3, 13 Nov).
    if (coachGuard(text)) return undefined;
    const vendor = this.connections.get(callID) ?? "";
    const key = this.targets.get(callID);
    const w = wordsIn(this.lang);
    // The person's two lines are tafs cloud's, by the refusal's kind, never the server's words (go-to-market bar, 13 Nov:
    // no raw error from planque, KIFF or a vendor reaches the person). Those stay for Coach and the log (keepWhy).
    const kind = refusalKind(text);
    const reason = kind === "cant" ? w.step_connection_why_cant(vendor)
      : kind === "later" ? w.step_connection_why_later(vendor)
      : kind === "sign_in" ? w.step_connection_why_sign_in(vendor)
      : undefined;
    // The way forward is one the app has (go-to-market job 3: a sign-in refused for LinkedIn offered "its key instead", and
    // LinkedIn has no key): what it can do instead when it lacks the access; again later when it didn't answer; a key's when
    // planque listed the app with one this run; the same again for a key request that was refused; else signing in again.
    const next = kind === "cant" ? w.step_connection_next_cant(vendor)
      : kind === "later" ? w.step_connection_next_later(vendor)
      : this.keyRequests.has(callID) ? w.step_connection_next_again(vendor)
      : vendor && this.keyApps.has(foldApp(vendor)) ? w.step_connection_next(vendor)
      : w.step_connection_next_sign_in(vendor);
    return { vendor, ...(key ? { key } : {}), ...(reason ? { reason } : {}), next };
  }

  /**
   * What any connection call went through for, once planque's answer shows its connection signed in or connected
   * (`signed_in`, `listed`, `connected`): earlier refusals for the same are past, so "Coach couldn't connect" never stands
   * under "signed in". A call whose answer shows any other state (failed, pending) clears nothing.
   */
  connectedBy(callID: unknown, text: unknown): { vendor: string; key?: string } | undefined {
    if (typeof callID !== "string" || !this.connections.has(callID)) return undefined;
    const said = typeof text === "string" ? text : JSON.stringify(text ?? "");
    if (!/"status"\s*:\s*"(signed_in|listed|connected)"/.test(said)) return undefined;
    const vendor = this.connections.get(callID) ?? "";
    const key = this.targets.get(callID);
    if (!vendor && !key) return undefined;
    return { vendor, ...(key ? { key } : {}) };
  }

  /** What a connection request was for, once it went through: earlier refusals for the same are past. */
  requestTarget(callID: unknown): { vendor: string; key?: string } | undefined {
    if (typeof callID !== "string" || !this.requests.has(callID)) return undefined;
    const key = this.targets.get(callID);
    return { vendor: this.connections.get(callID) ?? "", ...(key ? { key } : {}) };
  }

  /**
   * The app a grant connects, as the person knows it ("Google Sheets"): planque's name for it in its answer to
   * a connection call of this run. Undefined when no answer named it.
   */
  appOf(grant: unknown): string | undefined {
    return typeof grant === "string" ? this.apps.get(grant) : undefined;
  }

  /** A call's result: a request's grant id, so later steps on that grant name its vendor. */
  result(callID: unknown, text: unknown): void {
    if (typeof callID !== "string" || typeof text !== "string") return;
    if (this.planqueCalls.delete(callID)) {
      const app = appNamed(text);
      if (app) this.apps.set(app.grant, app.name);
      for (const v of keyAppsIn(text)) this.keyApps.add(v);
    }
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

/**
 * The connection server's reason as the person reads it ("Why: …"): `whyOf`, then any host name (with its path)
 * and any phone number left out too. The server can quote the description it read, which a third party writes,
 * and shown in tafs's voice a bare "evil.example" or a number to call would read as tafs's own advice. Coach
 * still reads the reason with `whyOf` alone, as data.
 */
export function shownWhy(text: unknown): string | undefined {
  const line = whyOf(text);
  if (!line) return undefined;
  const shown = line
    .replace(/\b(?:[a-z0-9-]+\.)+[a-z]{2,}\b(?:\/\S*)?/gi, "it")
    .replace(/\+?\d[\d\s().-]{7,}\d/g, "it")
    .replace(/\bit(?:\s+it)+\b/g, "it").replace(/\s{2,}/g, " ").trim();
  return /[A-Za-z]{3,}/.test(shown.replace(/\bit\b/g, "")) ? shown : undefined;
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
  refused?: { vendor: string; key?: string; reason?: string; next: string }; resolved?: true;
  /** Refused for how Coach wrote the call (coachGuard): not made, never a failure, on every read as in the stream. */
  not_made?: true }>;

/**
 * Whether a request that went through connects what a kept refusal was for: the same server, description or
 * grant (`key`), or, where either has none, the same vendor by a name that's known (two unknown ones never match).
 */
export function connectsRefused(refused: { vendor: string; key?: string }, through: { vendor: string; key?: string }): boolean {
  if (refused.key && through.key) return refused.key === through.key || (refused.vendor !== "" && refused.vendor === through.vendor);
  return refused.vendor !== "" && refused.vendor === through.vendor;
}

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

export function attachWhys<T extends { tool: string; error?: true }>(steps: T[], whys: StepWhys): Array<T & { failed?: true; why?: string; refused?: Refused; not_made?: true }> {
  return steps.map((s) => {
    if (!s.error) return s;
    const i = whys.findIndex((w) => w.tool === s.tool);
    const kept = i >= 0 ? whys.splice(i, 1)[0]! : undefined;
    // Refused for how Coach wrote it: not made, as the stream said, never red.
    if (kept?.not_made) { const { error: _e, ...rest } = s; return { ...(rest as T), not_made: true as const }; }
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

/**
 * The grant and the app's name planque's answer gives (its grant view: `{id: "grt_…", vendor: "Google Sheets"}`,
 * or `{grant: {…}}`), from the answer's text. Undefined when it names no single grant with a vendor (a list, or
 * none), or when its vendor is a host rather than a name.
 */
export function appNamed(text: string): { grant: string; name: string } | undefined {
  let v: unknown;
  try { v = JSON.parse(text); } catch { return undefined; }
  const g = (v && typeof v === "object" && "grant" in v && typeof (v as { grant: unknown }).grant === "object" ? (v as { grant: unknown }).grant : v) as { id?: unknown; vendor?: unknown } | null;
  if (!g || typeof g.id !== "string" || !/^grt_[A-Za-z0-9_-]{6,64}$/.test(g.id) || typeof g.vendor !== "string") return undefined;
  const name = g.vendor.replace(/\s+/g, " ").trim().slice(0, 60);
  return plainName(name) ? { grant: g.id, name } : undefined;
}

/** A name a person reads ("Google Sheets", "Stripe"), never a host or a link: no "." and no "/". */
function plainName(s: string): boolean {
  return s !== "" && !s.includes(".") && !s.includes("/");
}

/**
 * What a sign-in card names, for "Sign in to {app}": the app as planque named it in this run, else Coach's
 * `service` when it's a plain name. Never a host, whichever service a sign-in goes through: a link's host can be
 * the service in between rather than the app. Undefined then, and the apps say "the vendor".
 */
export function signInApp(service: unknown, app?: string): string | undefined {
  if (app && plainName(app)) return app;
  const s = typeof service === "string" ? service.trim() : "";
  return plainName(s) ? s : undefined;
}

function foldApp(name: string): string {
  return name.toLowerCase().replace(/^_+/, "").replace(/[\s._-]+/g, "");
}

/** The apps a planque answer lists with a key path (`how: "key"`), by vendor, title or entry slug, folded. */
export function keyAppsIn(text: string): string[] {
  let v: unknown;
  try { v = JSON.parse(text); } catch { return []; }
  const out = new Set<string>();
  const walk = (x: unknown, depth: number): void => {
    if (depth > 6 || !x || typeof x !== "object") return;
    if (Array.isArray(x)) { for (const e of x.slice(0, 500)) walk(e, depth + 1); return; }
    const o = x as Record<string, unknown>;
    if (o.how === "key") {
      for (const name of [o.vendor, o.title, typeof o.entry === "string" ? o.entry.replace(/^connector:[^:]+:/, "").replace(/@\d+$/, "") : undefined]) {
        if (typeof name === "string" && name.trim()) out.add(foldApp(name));
      }
    }
    for (const val of Object.values(o)) walk(val, depth + 1);
  };
  walk(v, 0);
  return [...out];
}

/**
 * Whether a connection server's refusal is a guard on the order of Coach's own steps (read the catalog, or the
 * tools, before asking for an action): Coach's to correct, never the person's to read.
 */
export function coachGuard(text: unknown): boolean {
  const line = whyOf(text) ?? "";
  return /\b(read|check|list)\b[^.]*\b(catalog|tools?)\b[^.]*\bbefore\b/i.test(line) || /\bbefore (selecting|choosing|adding|asking for) (this|an|the) (action|tool)\b/i.test(line)
    // The request's own shape, as planque checks it ("fixed argument … must be a dotted path with a JSON value"): Coach wrote it.
    || /\bargument\b[^.]*\b(?:is not a valid|must be|needs)\b|\bdotted path\b/i.test(line)
    // What a request takes, as planque says it ("reusing a managed app's sign-in takes agent, agent_name and tools: [] alone",
    // "grant is planque's grant id"): Coach asked wrong and asks again, never the person's app (Android 85, 14 Nov).
    || /\btakes\b[^.]*\balone\b/i.test(line) || /\bgrant is\b[^.]*\bgrant id\b/i.test(line)
    // A tool Coach named that the catalog doesn't hold by that name ("the catalog has no tool named GMAIL_FETCH_EMAILS",
    // "mcp.example.com lists no tool named …"): Coach looked it up wrong and looks again, never the person's app (14 Nov,
    // Gmail: a red "Coach couldn't connect the app yet" over a sign-in that had worked).
    || /\b(?:has|lists) no tool named\b/i.test(line);
}

/**
 * Whether a refusal says the app can't do it with this connection: a 403, or a scope or permission it lacks. Signing in
 * again gives the same access (go-to-market job 3: LinkedIn's page posting, "Ensure you have the scope"), so the way
 * forward is what the app can do instead.
 */
export type RefusalKind = "cant" | "later" | "sign_in" | "other";

/**
 * What kind of refusal a connection call's answer is, for the person's words: the app lacks the access (`cant`), it
 * didn't answer or is down (`later`), the sign-in didn't finish (`sign_in`), or anything else (`other`, no reason line).
 */
export function refusalKind(text: unknown): RefusalKind {
  if (lacksAccess(text)) return "cant";
  const raw = typeof text === "string" ? text.split("\n").find((l) => l.trim()) ?? "" : "";
  if (/\b(?:5\d\d|timed? ?out|unavailable|try again later|rate.?limit|too many requests)\b/i.test(raw)) return "later";
  if (/\b(?:sign(?:ed)?[- ]?in|log(?:ged)?[- ]?in|sign-in|expired|revoked|declined|cancell?ed)\b/i.test(raw)) return "sign_in";
  return "other";
}

export function lacksAccess(text: unknown): boolean {
  const line = whyOf(text) ?? "";
  return /\bscopes?\b|\bpermission\b|\bforbidden\b|\bnot (?:allowed|authori[sz]ed)\b/i.test(line);
}
