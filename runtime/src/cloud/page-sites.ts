/**
 * The sites Coach never reads a page from (docs/chat.md, "Coach reads the page you're on"): tafs cloud's own gate, beside
 * the Chrome extension's (`chrome/src/sites.js`, `asideRefusal`). The extension already never sends such a page; this
 * keeps the promise at the server too, whatever build sends one. The lists are the extension's own, word for word
 * (`page-sites.test.ts` holds them equal): a bank, a wallet, a password manager, a payment, health or government
 * account, a mail site, and Chrome's Web Store.
 */

const IDEOGRAPHIC_DOTS = /[。．｡]/g;

/** Sensitive sites by name (each with its subdomains), and the labels that name a bank, a government or health. */
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
  labels: /(^|[.-])(bank|banking|banque|bancaire|banca|banco|bancario|sparkasse|volksbank|bausparkasse|credit-?union|tresor|impots|irs|hmrc|gov|gouv|gob|bund|gc|nic|health|sante|gesundheit|salud|saude|patient|medicare|medicaid)([.-]|$)/,
});

/** Mail sites by name (each with its subdomains), and a host's own label that says it's mail, in the extension's languages. */
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
  labels: /(^|\.)(mail|webmail|owa|email|correo|correio|posta|poczta|messagerie|levelezes)\./,
});

/** A host as a browser reads it, as the extension's `hostOf`: lower case, ideographic dots as dots, no trailing dot. */
export function normalHost(hostname: string): string {
  return hostname.toLowerCase().replace(IDEOGRAPHIC_DOTS, ".").replace(/\.$/, "");
}

function within(host: string, site: string): boolean {
  return host === site || host.endsWith("." + site);
}

/** Whether `host` is one of `sites` or under one, as planque holds a browser connection to them. */
export function onSites(host: string, sites: readonly string[]): boolean {
  return sites.some((s) => within(host, s));
}

/**
 * Why Coach never reads a page at `host` (normalised) and `path`: "store" for Chrome's Web Store, "sensitive" for a bank,
 * wallet, password manager, payment, health, government or mail site; else null.
 */
export function pageSiteRefused(host: string, path: string): "store" | "sensitive" | null {
  if (host === "chromewebstore.google.com" || (host === "chrome.google.com" && path.startsWith("/webstore"))) return "store";
  if (SENSITIVE.sites.some((s) => within(host, s)) || SENSITIVE.labels.test(host)) return "sensitive";
  if (MAIL.sites.some((s) => within(host, s)) || MAIL.labels.test(host)) return "sensitive";
  return null;
}

/** What a path segment shaped like a key or a one-time token is replaced by, as the extension masks a secret. */
export const PATH_MASK = "[masked]";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** One run of a segment between dots and tildes: a key or a token by its shape (see `tokenSegment`). */
function tokenPart(part: string): boolean {
  if (UUID.test(part)) return false;
  // A JWT's parts are base64url JSON: its header always starts `eyJ` (`{"`).
  if (/^eyJ[A-Za-z0-9_-]{8,}={0,2}$/.test(part)) return true;
  if (/^[A-Fa-f0-9]{32,}$/.test(part)) return true;
  if (/^[A-Za-z0-9_-]{24,}={0,2}$/.test(part) && /[A-Z]/.test(part) && /[a-z]/.test(part) && /[0-9]/.test(part)) return true;
  if (/^[A-Za-z0-9]{32,}$/.test(part) && /[0-9]/.test(part) && /[A-Za-z]/.test(part)) return true;
  // A token after a prefix (`tok_…`, `reset-…`): each piece between `_` and `-` by the two shapes above that a word never has.
  return part.split(/[_-]/).some((q) => /^[A-Fa-f0-9]{32,}$/.test(q) || (/^[A-Za-z0-9]{32,}$/.test(q) && /[0-9]/.test(q) && /[A-Za-z]/.test(q)));
}

/**
 * Whether one path segment (percent-decoded) holds a key or a one-time token rather than a name: any of its runs between `.` and
 * `~` that is a JWT's part, 32 or more hex digits, 24 or more base64url characters with upper case, lower case and digits
 * together, or 32 or more letters and digits mixed, alone or after a prefix (`tok_…`). The whole segment is masked then. A word,
 * a slug (`late-parcels-2024`), a number, a file name (`report.2024.pdf`), a version (`v1.2.3`) or a UUID (an id) is kept.
 */
export function tokenSegment(segment: string): boolean {
  if (UUID.test(segment)) return false;
  return segment.split(/[.~]/).some(tokenPart);
}

/**
 * The page's path as the run reads it in its `Address:` line: every segment shaped like a key or a one-time token masked (a
 * password reset's or a sign-in link's token, a share link's secret), the rest as sent. The query and fragment never come.
 */
export function maskedPath(pathname: string): string {
  return pathname
    .split("/")
    .map((s) => {
      let d = s;
      try {
        d = decodeURIComponent(s);
      } catch {
        // a segment that isn't valid percent-encoding is read as written
      }
      return tokenSegment(d) ? PATH_MASK : s;
    })
    .join("/");
}
