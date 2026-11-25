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

export const LOCAL_SUFFIXES = Object.freeze([".localhost", ".local", ".internal", ".home", ".lan", ".corp", ".intranet", ".arpa"]);
const IDEOGRAPHIC_DOTS = /[。．｡]/g;

/**
 * Never, for any grant: Chrome's own pages and the Web Store, Google's account and pay pages, and KIFF's own (kiff.dev and every
 * subdomain: its Cards, connections, keys and approvals, and tafs's). A taf never drives the controls it's held by. The
 * extension's own requests to KIFF, planque and tafs cloud aren't a tab's, so the fence, which holds only the run's tabs, never
 * touches them.
 */
const ALWAYS_BLOCKED = ["chrome.google.com", "chromewebstore.google.com", "accounts.google.com", "myaccount.google.com", "passwords.google.com", "pay.google.com", "kiff.dev"];

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
    // merchant consoles: where a business refunds, pays out and moves its money, outside any Card
    "stripe.com", "paddle.com", "adyen.com", "squareup.com", "braintreegateway.com", "gocardless.com",
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

/**
 * Mail: never read for Coach (`asideRefusal`), as the panel's words and the privacy answers promise ("a bank, payment, mail or
 * health site"). The web mail hosts by name, and any host whose own label says it's mail. Runs aren't changed by this.
 */
export const MAIL = Object.freeze({
  sites: [
    "mail.google.com", "gmail.com",
    "outlook.live.com", "outlook.office.com", "outlook.office365.com",
    "mail.yahoo.com", "mail.aol.com", "icloud.com",
    "tuta.com", "tutanota.com", "fastmail.com",
    "mail.zoho.com", "mail.zoho.eu",
    "gmx.net", "gmx.de", "gmx.com", "web.de",
    "mail.ru", "mail.yandex.ru", "laposte.net",
    "outlook.cloud.microsoft", "hey.com", "freemail.hu",
  ],
  // The word for mail as a host's own label, in the extension's languages: mail, webmail, Outlook Web Access (owa), email
  // (Seznam, T-Online), correo, correio, posta, poczta (Onet, WP, Interia), messagerie, levelezes.
  labels: /(^|\.)(mail|webmail|owa|email|correo|correio|posta|poczta|messagerie|levelezes)\./,
});

/** Whether [host] is a mail site, for Coach's read: by name (and its subdomains), or by a label that says so. */
export function mailHost(host) {
  const h = String(host || "").toLowerCase();
  return MAIL.sites.some((s) => within(h, s)) || MAIL.labels.test(h);
}

export function sensitive(host) {
  return SENSITIVE.sites.some((s) => within(host, s)) || SENSITIVE.labels.test(host);
}

/**
 * `SENSITIVE.labels` as patterns Chrome's fence can hold on a request's address (declarativeNetRequest `regexFilter`s, RE2): the
 * host, from just after `://`, has a label that names a bank, a government or a health account, exactly as `sensitive()` tests the
 * host (a label ends at a dot, a hyphen, the port, the path or the end). Built from the label words of that one regex, so the two
 * never drift; a test checks they agree. Chrome holds a pattern only within a small memory budget (one with every word is refused,
 * `memoryLimitExceeded`), so the words are split over a few patterns, each well inside it; any one matching blocks.
 * (`SENSITIVE.sites` go to the fence as domains: they match a site and its subdomains, as `within` does.)
 */
export const SENSITIVE_LABELS_URLS = (() => {
  const words = /^\(\^\|\[\.-\]\)\((.*)\)\(\[\.-\]\|\$\)$/.exec(SENSITIVE.labels.source);
  if (!words) throw new Error("SENSITIVE.labels changed shape: rebuild SENSITIVE_LABELS_URLS");
  const pattern = (ws) => "^https?://(?:[^/?#:]*[.-])?(?:" + ws.join("|") + ")(?:[.:/?#-]|$)";
  const chunks = [[]];
  for (const w of words[1].split("|")) {
    const last = chunks[chunks.length - 1];
    if (last.length && pattern([...last, w]).length > 110) chunks.push([w]);
    else last.push(w);
  }
  return Object.freeze(chunks.map(pattern));
})();

/** Chrome's own pages and the Web Store, which no grant opens: for the fence too. */
export const NEVER_OPENED = Object.freeze([...ALWAYS_BLOCKED]);

/**
 * What Chrome's fence may let a run's tab reach, exactly as `refuseURL` would: `granted`, the grant's sites that name one site
 * (`siteOK`), never a local name or an address; `allowed`, the sensitive sites the phone allowed by name, only those within a
 * granted site (an allowance outside the grant reaches nothing, as in `refuseURL`).
 */
export function fenceDomains(sites, allowed = []) {
  const clean = (s) => String(s || "").toLowerCase().replace(IDEOGRAPHIC_DOTS, ".").replace(/\.$/, "");
  const granted = [...new Set((Array.isArray(sites) ? sites : []).filter((s) => siteOK(s)).map(clean))].filter((h) => h && !isAddress(h) && !local(h) && ![...h].some((c) => c.charCodeAt(0) > 0x7f));
  const kept = [...new Set((Array.isArray(allowed) ? allowed : []).map(clean))].filter((h) => h && siteOK(h) && !isAddress(h) && !local(h) && granted.some((g) => within(h, g)));
  return { granted, allowed: kept };
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
  // As hostOf normalises it (lowercase, ideographic dots, no trailing dot): "paypal.com." is paypal.com, so no form of a host
  // gets past the lists, and the person's switch is checked in the form it's kept in.
  const host = hostOf(tab.url);
  if (!host) return "not_https";
  if (WEB_STORE({ hostname: host, pathname: u.pathname })) return "store";
  if (sensitive(host) || mailHost(host)) return "sensitive";
  if ((off || []).includes(host)) return "off";
  return null;
}

// A step's code for why the person's own tab may not be a run's (asideRefusal's reasons): a page that isn't https is a bad URL;
// Chrome's store, a private tab, a sensitive or mail site, or a site the person switched off is blocked.
const JOINED_CODE = { not_https: CODE.badURL, store: CODE.siteBlocked, incognito: CODE.siteBlocked, sensitive: CODE.siteBlocked, off: CODE.siteBlocked };

/**
 * Why the person's tab, joined to a run (docs/chrome.md, "A taf works where you are"), may not be worked on now: a step's code, or
 * null. Both gates at once: the run's own (`refuseURL`: the phone's signed sites, the sensitive sites it allowed) and Coach's
 * (`asideRefusal`: never a private tab, the store, a bank, payment, mail or health site, even one the phone allowed, nor a site the
 * person switched off).
 */
export function joinedRefusal(tab, { sites, allowed = [], off = [] } = {}) {
  if (!tab) return "no_page";
  const run = refuseURL(String(tab.url || ""), sites, allowed);
  if (run) return run.code;
  const why = asideRefusal({ ...tab, active: true, windowId: -1 }, { windowId: -1, off });
  return why ? JOINED_CODE[why] || CODE.siteBlocked : null;
}

/**
 * Why the tab in front of the panel's window may not join a run, or null when it may: it must be that window's active tab
 * ([windowId]), not pinned, and pass `joinedRefusal`. A tab in any other window, a private one, Chrome's own pages or a page
 * that isn't https never joins.
 */
export function joinRefusal(tab, { windowId, sites, allowed = [], off = [] } = {}) {
  if (!tab || !tab.active || typeof windowId !== "number" || tab.windowId !== windowId) return "not_front";
  if (tab.pinned) return "pinned";
  return joinedRefusal(tab, { sites, allowed, off });
}
