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
