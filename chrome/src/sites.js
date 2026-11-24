// Where a run may go, enforced in the extension itself, whatever a step says (docs/chrome.md, "Sites"). planque checks the
// same rules first (internal/browser Allowed); either alone stops a step that leaves the grant's sites. On top of planque's
// rules, the extension never opens Chrome's own pages, the Web Store, or a sensitive site (a bank, a wallet, a password
// manager, a payment, health or government account) unless the owner allowed that one site by name on the phone.

export const CODE = Object.freeze({
  badURL: "bad_url",
  addressPrivate: "address_private",
  siteNotListed: "site_not_listed",
  siteBlocked: "site_blocked",
});

const LOCAL_SUFFIXES = [".localhost", ".local", ".internal", ".home", ".lan", ".corp", ".intranet", ".arpa"];
const IDEOGRAPHIC_DOTS = /[。．｡]/g;

/** Chrome's own pages and the Web Store: never, for any grant. */
const ALWAYS_BLOCKED = ["chrome.google.com", "chromewebstore.google.com", "accounts.google.com", "myaccount.google.com", "passwords.google.com", "pay.google.com"];

/**
 * Sensitive sites, blocked unless allowed one by one. Each entry is a site and its subdomains. The patterns catch what a
 * list can't name (every bank, every government): they err to blocking, and the owner can allow a site by name.
 */
export const SENSITIVE = Object.freeze({
  sites: [
    // password managers
    "1password.com", "lastpass.com", "bitwarden.com", "dashlane.com", "keepersecurity.com", "nordpass.com", "proton.me",
    // wallets and exchanges
    "coinbase.com", "binance.com", "kraken.com", "metamask.io", "blockchain.com", "crypto.com", "ledger.com", "bitstamp.net",
    // payments and money accounts
    "paypal.com", "wise.com", "revolut.com", "n26.com", "venmo.com", "cash.app", "klarna.com", "payoneer.com",
    // health
    "ameli.fr", "nhs.uk", "mychart.com", "healthcare.gov",
  ],
  // A label of the host, or its public suffix, that names a bank, a government or a health account.
  labels: /(^|[.-])(bank|banking|banque|bancaire|banca|banco|bancario|sparkasse|volksbank|bausparkasse|credit-?union|tresor|impots|irs|hmrc|gov|gouv|gob|bund|gc|nic|health|sante|gesundheit|salud|saude|patient|medicare|medicaid)([.-]|$)/,
});

/** The host a URL's browser reads, lower case, or "" when it isn't a URL. */
export function hostOf(raw) {
  try {
    return new URL(String(raw).trim()).hostname.toLowerCase().replace(IDEOGRAPHIC_DOTS, ".").replace(/\.$/, "");
  } catch {
    return "";
  }
}

/** A browser reads a host as an IPv4 address when its last label is a number: decimal, octal (0…) or hexadecimal (0x…). */
export function endsInANumber(host) {
  const labels = host.split(".");
  const last = labels[labels.length - 1];
  if (last === "") return false;
  if (last.startsWith("0x")) return /^[0-9a-f]*$/.test(last.slice(2));
  return /^[0-9]+$/.test(last);
}

function isAddress(host) {
  return /^\[.*\]$/.test(host) || /^[0-9a-f:]+$/i.test(host) && host.includes(":") || endsInANumber(host);
}

export function local(host) {
  if (host === "localhost" || !host.includes(".")) return true;
  return LOCAL_SUFFIXES.some((s) => host.endsWith(s));
}

/**
 * Public suffixes a grant must never list as a site, since every site under them would be reached: any single label
 * ("com"), and the common multi-part ones. planque refuses such a grant too; this holds without it.
 */
const PUBLIC_SUFFIXES = new Set([
  "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp",
  "or.jp", "co.kr", "com.br", "com.mx", "com.ar", "com.co", "com.tr", "com.cn", "com.hk", "com.sg", "com.tw", "co.in",
  "co.za", "co.il", "com.pl", "com.es", "com.pt", "co.it", "github.io", "herokuapp.com", "web.app", "pages.dev",
  "vercel.app", "netlify.app", "blogspot.com", "appspot.com", "cloudfront.net", "azurewebsites.net",
]);

/** Whether `site` names one site, never a public suffix. */
export function siteOK(site) {
  return typeof site === "string" && site.includes(".") && !PUBLIC_SUFFIXES.has(site.toLowerCase().replace(/\.$/, ""));
}

function within(host, site) {
  return host === site || host.endsWith("." + site);
}

export function sensitive(host) {
  return SENSITIVE.sites.some((s) => within(host, s)) || SENSITIVE.labels.test(host);
}

/**
 * Why `raw` isn't a URL this run may open: `{code, why}`, or null when it may. `sites` are the grant's sites (and their
 * subdomains); `allowed` the sensitive sites the owner allowed by name. An empty `sites` reaches nothing: the extension
 * never takes an all-sites grant.
 */
export function refuseURL(raw, sites, allowed = []) {
  let u;
  try {
    u = new URL(String(raw).trim());
  } catch {
    return { code: CODE.badURL, why: "not a URL" };
  }
  if (u.protocol !== "https:" || !u.hostname || u.username || u.password) {
    return { code: CODE.badURL, why: "the URL must be https, with a host and no user info" };
  }
  const host = hostOf(u.href);
  if (isAddress(host)) return { code: CODE.addressPrivate, why: "the browser opens sites by name, never an address" };
  if ([...host].some((c) => c.charCodeAt(0) > 0x7f)) return { code: CODE.badURL, why: "the site's name must be in ASCII (its xn-- form)" };
  if (local(host)) return { code: CODE.addressPrivate, why: host + " is a local name, not a site" };
  if (ALWAYS_BLOCKED.some((s) => within(host, s))) return { code: CODE.siteBlocked, why: host + " is never opened by a taf" };
  if (!Array.isArray(sites) || !sites.some((s) => siteOK(s) && within(host, s))) {
    return { code: CODE.siteNotListed, why: host + " isn't one of this run's sites" };
  }
  if (sensitive(host) && !allowed.some((s) => within(host, s))) {
    return { code: CODE.siteBlocked, why: host + " looks like a bank, wallet, password, payment, health or government site" };
  }
  return null;
}

/** A URL as the record and the taf see it: no query and no fragment, which can carry a token. */
export function bareURL(raw) {
  try {
    const u = new URL(raw);
    // A page that isn't on the web (about:blank, a browser page) has no origin to keep: "" rather than "nullblank".
    if (u.protocol !== "https:" && u.protocol !== "http:") return "";
    return u.origin + u.pathname;
  } catch {
    return "";
  }
}

/** Chrome's own store: never read for Coach, whatever its address says. */
const WEB_STORE = (u) => u.hostname === "chromewebstore.google.com" || (u.hostname === "chrome.google.com" && u.pathname.startsWith("/webstore"));

/**
 * Whether Coach may read [tab] with the person's message (docs/chrome.md, "Coach reads the page you're on"): `null` when it
 * may, else why not ("not_front", "not_https", "store", "incognito", "sensitive", "off"). Only the tab in front of the panel's
 * own window ([windowId]); only https; never Chrome's store or an incognito tab; never a sensitive site (the same list a run
 * needs the phone's signed allowance for); never a host the person switched off ([off]).
 */
export function asideRefusal(tab, { windowId, off = [] } = {}) {
  if (!tab || !tab.active || typeof windowId !== "number" || tab.windowId !== windowId) return "not_front";
  if (tab.incognito) return "incognito";
  let u;
  try {
    u = new URL(String(tab.url || ""));
  } catch {
    return "not_https";
  }
  if (u.protocol !== "https:" || u.username || u.password) return "not_https";
  if (WEB_STORE(u)) return "store";
  const host = u.hostname.toLowerCase();
  if (sensitive(host)) return "sensitive";
  if ((off || []).includes(host)) return "off";
  return null;
}
