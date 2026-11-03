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
  vendor: string;
  title: string;
  icon?: string;
  how: "oauth" | "key";
  state: "available" | "connected" | "needs_reconnect";
  grant?: string;
}

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
}

/** One page of the catalog, the account's connections marked, searched by q; page.cursor asks for the next. */
export async function listApps(fetchFn: Fetch, base: string, tenant: string, bearer: string, q: string, page: { cursor?: string; provider?: string } = {}): Promise<AppsPage> {
  const params = new URLSearchParams();
  if (q.trim()) params.set("q", q.trim().slice(0, 100));
  if (page.provider && PROVIDER.test(page.provider)) params.set("provider", page.provider);
  if (page.cursor && CURSOR.test(page.cursor)) params.set("cursor", page.cursor);
  const query = params.size ? `?${params.toString().replace(/\+/g, "%20")}` : "";
  const got = await call(fetchFn, `${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/catalog${query}`, bearer, { method: "GET" }) as
    { entries?: unknown; by_key?: unknown; provider?: unknown; next_cursor?: unknown; skipped?: unknown };
  const entries = Array.isArray(got.entries) ? got.entries : [];
  const apps: CatalogApp[] = [];
  for (const e of entries as Record<string, unknown>[]) {
    const how = e.how === "key" ? "key" : e.how === "oauth" ? "oauth" : undefined;
    const state = e.state === "connected" || e.state === "needs_reconnect" ? e.state : "available";
    if (typeof e.entry !== "string" || !ENTRY.test(e.entry) || typeof e.title !== "string" || !how) continue;
    apps.push({
      entry: e.entry, vendor: typeof e.vendor === "string" ? e.vendor : e.title, title: e.title,
      ...(typeof e.icon === "string" && e.icon.startsWith("https://") ? { icon: e.icon } : {}),
      how, state, ...(typeof e.grant === "string" ? { grant: e.grant } : {}),
    });
  }
  return {
    apps: ordered(apps, q), by_key: got.by_key === true,
    ...(typeof got.provider === "string" && PROVIDER.test(got.provider) ? { provider: got.provider } : {}),
    ...(typeof got.next_cursor === "string" && CURSOR.test(got.next_cursor) ? { next_cursor: got.next_cursor } : {}),
    ...(Number.isInteger(got.skipped) && (got.skipped as number) > 0 ? { skipped: got.skipped as number } : {}),
  };
}

/**
 * A page in the order a person looks for an app: the account's own connections first (one to sign in again
 * before one connected), then, for a search, apps whose name starts with it, then whose name has it, then the
 * rest; planque's order otherwise. Only within the page: planque pages its catalog.
 */
function ordered(apps: CatalogApp[], q: string): CatalogApp[] {
  const want = q.trim().toLowerCase();
  const rank = (a: CatalogApp): number => {
    if (a.state === "needs_reconnect") return 0;
    if (a.state === "connected") return 1;
    if (!want) return 2;
    const name = `${a.title} ${a.vendor}`.toLowerCase();
    return a.title.toLowerCase().startsWith(want) || a.vendor.toLowerCase().startsWith(want) ? 2 : name.includes(want) ? 3 : 4;
  };
  return apps.map((a, i) => ({ a, i, r: rank(a) })).sort((x, y) => x.r - y.r || x.i - y.i).map((x) => x.a);
}

/** Starts a connection of entry for agent, the taf's KIFF agent: the grant to approve, nothing signed. */
export async function startApp(fetchFn: Fetch, base: string, tenant: string, bearer: string, entry: string, agent: string): Promise<{ grant: unknown; needs_key?: true }> {
  if (!ENTRY.test(entry)) throw new ConnectError(404, "no such app in the catalog");
  const got = await call(fetchFn, `${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/catalog/${encodeURIComponent(entry)}/grants`, bearer,
    { method: "POST", body: { agent } }) as { grant?: unknown; needs_key?: unknown };
  if (!got.grant || typeof got.grant !== "object") throw new ConnectError(502, "planque didn't answer; try again");
  return { grant: got.grant, ...(got.needs_key === true ? { needs_key: true as const } : {}) };
}
