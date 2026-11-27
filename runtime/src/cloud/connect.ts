/**
 * Connect apps (docs/api.md, "Connect apps"): the apps list planque's catalog through tafs cloud and
 * start a connection for one taf. tafs cloud asks planque with the person's own KIFF session and
 * keeps nothing: the grant, its approval (KIFF's words, signed on the phone) and the sign-in after it
 * are planque's and the phone's, as for a connection Coach starts.
 */
import type { Fetch } from "./kiff.js";

/** One catalog entry as the apps show it. */
export interface CatalogApp {
  entry: string;
  /** planque's key for the app: a reviewed entry's vendor ("Qonto"), a managed app's slug ("gmail"). Not for people. */
  vendor: string;
  /** planque's title, as it gives it ("Qonto invoicing: reads, and invoice drafts from quotes"); kept for apps that draw it. */
  title: string;
  /** The app's name for people, what a row reads as: "Qonto", "Gmail". */
  name: string;
  /** What this entry of the app is, under its name ("Invoicing: reads, and invoice drafts from quotes"); absent when the title is the name. */
  subtitle?: string;
  icon?: string;
  how: "oauth" | "key";
  /** `needs_setup`: the app needs setting up by an operator before anyone can connect it; the apps say so and offer no Connect. */
  /**
   * `signed_in`: planque holds a sign-in that no taf's actions are in KIFF for yet (one Coach made, its actions still to
   * choose); the apps list it under the account's own, so it can always be disconnected.
   */
  state: "available" | "connected" | "needs_reconnect" | "signed_in" | "needs_setup";
  /** For `needs_setup`: the line the apps show in place of Connect. */
  words?: string;
  grant?: string;
  /**
   * The key goes with the owner's own login at the vendor, an email (planque's `login`, Zendesk's "{login}/token"):
   * the apps ask for it before Connect and send it as `login`. Only on a key entry.
   */
  login?: true;
  /**
   * When a check connected this app this way, how (`key` or `oauth`), and where it's recorded (planque's catalog, from
   * its reviewed list): only on the entry that is the way the app was proved; absent until a check has.
   */
  proved?: Proof;
}

/** A check that connected an app: when, how (`key` or `oauth`, in the entries' own words), and where it's recorded. */
export interface Proof {
  at: string;
  how: "key" | "oauth";
  link?: string;
}

/** What an app that needs setting up says in place of Connect. */
export const NEEDS_SETUP_WORDS = "Needs setting up first. Ask tafs to enable it.";

/** planque didn't answer as it should: the apps are told planque can't be reached. */
export class ConnectError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

/**
 * A catalog entry's id: a reviewed one ("notion@1") or a managed provider's ("connector:composio:_1password",
 * whose app slugs may start with "_"). No "/", "?" or "%": it goes into planque's path encoded, as a whole.
 */
const ENTRY = /^[A-Za-z0-9._@:-]{1,300}$/;
/** A provider's id and its page cursor, as planque gives them back: passed on as they are. */
const PROVIDER = /^[A-Za-z0-9_-]{1,64}$/;
const CURSOR = /^[\x21-\x7e]{1,4096}$/;
/**
 * The owner's login at the vendor, as planque takes it (kiff/planque `loginAddress`): one email address of letters,
 * digits and `. _ % + -` before the `@`, a domain of two labels or more, at most 254 characters. Checked here too, so
 * a bad one is said before planque is asked.
 */
export const LOGIN = /^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(\.[A-Za-z0-9-]{1,63})+$/;
export const validLogin = (v: unknown): v is string => typeof v === "string" && v.length <= 254 && LOGIN.test(v);

async function call(fetchFn: Fetch, url: string, bearer: string, init: { method: string; body?: unknown }): Promise<unknown> {
  let res: Response;
  try {
    res = await fetchFn(url, {
      method: init.method,
      headers: { authorization: `Bearer ${bearer}`, ...(init.body ? { "content-type": "application/json" } : {}) },
      ...(init.body ? { body: JSON.stringify(init.body) } : {}),
    });
  } catch {
    throw new ConnectError(502, "planque didn't answer; try again");
  }
  const text = await res.text();
  if (res.status === 404) throw new ConnectError(404, "no such app in the catalog");
  if (res.status === 401 || res.status === 403) throw new ConnectError(403, "you may not connect apps on this account");
  if (!res.ok) throw new ConnectError(502, "planque didn't answer; try again");
  try {
    return JSON.parse(text);
  } catch {
    throw new ConnectError(502, "planque didn't answer; try again");
  }
}

/** One page of the catalog, the account's connections marked, searched by q. */
export interface AppsPage {
  apps: CatalogApp[];
  by_key: boolean;
  /** The managed provider whose apps this page lists, and the cursor of its next page: absent on the last. */
  provider?: string;
  next_cursor?: string;
  /** Entries planque left out of this page as malformed or unsupported. */
  skipped?: number;
  /** The catalog read stopped with pages left: an app that isn't here isn't absent. */
  partial?: true;
  /**
   * Every app a check connected, by its folded name ("googlecalendar"), planque's whole reviewed list: an app proved by
   * its key with no key entry in the catalog (Stripe, GitHub…) is only here.
   */
  proved?: Record<string, Proof>;
}

/** A language tag planque reads (`es`, `pt-BR`, `zh-Hant`): anything else isn't sent, and planque answers in English. */
const LANG_TAG = /^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$/;

/**
 * One page of the catalog, the account's connections marked, searched by q; page.cursor asks for the next. page.lang is the
 * person's language: planque titles each reviewed app in it, else in English (planque's `lang`), and a search matches it.
 * Only what the person reads passes it; a read that only matches apps by their entry or vendor doesn't need to.
 */
export async function listApps(fetchFn: Fetch, base: string, tenant: string, bearer: string, q: string, page: { cursor?: string; provider?: string; lang?: string } = {}): Promise<AppsPage> {
  const params = new URLSearchParams();
  if (page.lang && LANG_TAG.test(page.lang)) params.set("lang", page.lang);
  if (q.trim()) params.set("q", q.trim().slice(0, 100));
  if (page.provider && PROVIDER.test(page.provider)) params.set("provider", page.provider);
  if (page.cursor && CURSOR.test(page.cursor)) params.set("cursor", page.cursor);
  const query = params.size ? `?${params.toString().replace(/\+/g, "%20")}` : "";
  const got = await call(fetchFn, `${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/catalog${query}`, bearer, { method: "GET" }) as
    { entries?: unknown; by_key?: unknown; provider?: unknown; next_cursor?: unknown; skipped?: unknown; partial?: unknown; proved?: unknown };
  const entries = Array.isArray(got.entries) ? got.entries : [];
  const apps: CatalogApp[] = [];
  for (const e of entries as Record<string, unknown>[]) {
    const how = e.how === "key" ? "key" : e.how === "oauth" ? "oauth" : undefined;
    const state = e.state === "connected" || e.state === "needs_reconnect" || e.state === "signed_in" || e.state === "needs_setup" ? e.state : "available";
    if (typeof e.entry !== "string" || !ENTRY.test(e.entry) || typeof e.title !== "string" || !how) continue;
    const vendor = typeof e.vendor === "string" ? e.vendor : e.title;
    apps.push({
      entry: e.entry, vendor, title: e.title, ...displayName(e.entry, vendor, e.title),
      ...(typeof e.icon === "string" && e.icon.startsWith("https://") ? { icon: e.icon } : {}),
      how, state, ...(state === "needs_setup" ? { words: NEEDS_SETUP_WORDS } : {}), ...(typeof e.grant === "string" ? { grant: e.grant } : {}),
      ...(how === "key" && e.login === true ? { login: true as const } : {}),
      ...provedOf(e.proved),
    });
  }
  return {
    apps: ordered(apps, q), by_key: got.by_key === true,
    ...(typeof got.provider === "string" && PROVIDER.test(got.provider) ? { provider: got.provider } : {}),
    ...(typeof got.next_cursor === "string" && CURSOR.test(got.next_cursor) ? { next_cursor: got.next_cursor } : {}),
    ...(Number.isInteger(got.skipped) && (got.skipped as number) > 0 ? { skipped: got.skipped as number } : {}),
    ...(got.partial === true ? { partial: true as const } : {}),
    ...provedMap(got.proved),
  };
}

/** planque's top-level `proved`, kept by app name (lower-case letters and digits) with each proof as an entry's. */
function provedMap(v: unknown): { proved?: Record<string, Proof> } {
  if (!v || typeof v !== "object" || Array.isArray(v)) return {};
  const out: Record<string, Proof> = {};
  for (const [app, p] of Object.entries(v as Record<string, unknown>)) {
    const proof = provedOf(p).proved;
    if (/^[a-z0-9]{1,60}$/.test(app) && proof) out[app] = proof;
  }
  return Object.keys(out).length ? { proved: out } : {};
}

/** planque's `proved`, kept only with a date and a `how` that is `key` or `oauth`, and a link only when it's https. */
function provedOf(v: unknown): { proved?: Proof } {
  if (!v || typeof v !== "object") return {};
  const p = v as { at?: unknown; how?: unknown; link?: unknown };
  if (typeof p.at !== "string" || Number.isNaN(Date.parse(p.at)) || (p.how !== "key" && p.how !== "oauth")) return {};
  return { proved: { at: p.at, how: p.how, ...(typeof p.link === "string" && p.link.startsWith("https://") ? { link: p.link } : {}) } };
}

/**
 * A row's name and subtitle. A reviewed entry's vendor is the app's name, as people write it ("Qonto"), and its
 * title says what the entry does after that name ("Qonto invoicing: reads, …"): the row reads as the vendor, the
 * rest of the title under it. A managed app's vendor is its slug ("gmail") and its title is its name ("Gmail"); a
 * title that doesn't start with the vendor as written ("Use websites") is the name itself. Neither has a subtitle.
 */
export function displayName(entry: string, vendor: string, title: string): { name: string; subtitle?: string } {
  const name = vendor.trim();
  if (entry.startsWith("connector:") || !name || !title.startsWith(name)) return { name: title };
  const after = title.slice(name.length);
  // "Qonto invoicing", "Qonto: invoicing", "Qonto, read only", "Qonto – invoicing": never "Qontoapp".
  if (!/^[\s:,;.\u2013\u2014-]/.test(after)) return { name: title };
  const rest = after.replace(/^[\s:,;.\u2013\u2014-]+/, "").trim();
  return rest ? { name, subtitle: rest.charAt(0).toLocaleUpperCase("en") + rest.slice(1) } : { name };
}

/**
 * The apps people connect first, in order: what Discover opens on. A copy of planque's `guidesFirst`
 * (kiff/planque cmd/planque/guides.go), the order deploy/guides/apps.md lists them in. A stopgap until planque's
 * catalog says this order itself: keep the two the same.
 */
const FIRST_APPS = ["gmail", "googlesheets", "stripe", "github", "linkedin", "qonto",
  "googlecalendar", "googledrive", "googledocs", "outlook", "slack", "notion", "hubspot", "shopify", "calendly", "trello", "asana", "airtable"];

/** Where an app comes among the first apps, by its slug (a managed provider's entry's last part) or its vendor; else past them all. */
function firstRank(a: CatalogApp): number {
  const slug = (a.entry.split(":").pop() ?? "").replace(/@\d+$/, "").toLowerCase();
  const at = [slug, a.vendor.toLowerCase()].map((k) => FIRST_APPS.indexOf(k)).filter((i) => i >= 0);
  return at.length ? Math.min(...at) : FIRST_APPS.length;
}

/**
 * A page in the order a person looks for an app: the account's own connections first (one to sign in again
 * before one connected, then a sign-in no taf uses yet), then, for a search, apps whose name starts with it, then whose name has it, then the
 * rest, each in planque's order; with no search, the first apps people connect (FIRST_APPS), then the rest
 * by name. Only within the page: planque pages its catalog.
 */
function ordered(apps: CatalogApp[], q: string): CatalogApp[] {
  const want = q.trim().toLowerCase();
  const rank = (a: CatalogApp): number => {
    if (a.state === "needs_reconnect") return 0;
    if (a.state === "connected") return 1;
    if (a.state === "signed_in") return 2;
    if (!want) return 3;
    const name = `${a.title} ${a.vendor}`.toLowerCase();
    return a.title.toLowerCase().startsWith(want) || a.vendor.toLowerCase().startsWith(want) ? 3 : name.includes(want) ? 4 : 5;
  };
  const discover = (x: CatalogApp, y: CatalogApp): number =>
    want ? 0 : firstRank(x) - firstRank(y) || x.title.localeCompare(y.title, "en", { sensitivity: "base" });
  return apps.map((a, i) => ({ a, i, r: rank(a) })).sort((x, y) => x.r - y.r || (x.r === 3 ? discover(x.a, y.a) : 0) || x.i - y.i).map((x) => x.a);
}

/**
 * Starts a connection of entry for agent, the taf's KIFF agent: the grant to approve, nothing signed. `login` is the
 * owner's login at the vendor, for an entry the catalog lists with `login: true`; planque refuses one it doesn't take.
 */
export async function startApp(fetchFn: Fetch, base: string, tenant: string, bearer: string, entry: string, agent: string, agentName?: string, login?: string): Promise<{ grant: unknown; needs_key?: true }> {
  if (!ENTRY.test(entry)) throw new ConnectError(404, "no such app in the catalog");
  if (login !== undefined && !validLogin(login)) throw new ConnectError(400, "login is one email address: no space, colon, slash or other character");
  const got = await call(fetchFn, `${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/catalog/${encodeURIComponent(entry)}/grants`, bearer,
    // The taf's name, one line, for the words the owner reads and signs ("Connect Gmail for Refunds").
    { method: "POST", body: { agent, ...(agentName ? { agent_name: agentName.replace(/[\u0000-\u001f\u007f-\u009f]+/g, " ").trim().slice(0, 60).trim() } : {}), ...(login !== undefined ? { login } : {}) } }) as { grant?: unknown; needs_key?: unknown };
  if (!got.grant || typeof got.grant !== "object") throw new ConnectError(502, "planque didn't answer; try again");
  return { grant: got.grant, ...(got.needs_key === true ? { needs_key: true as const } : {}) };
}
