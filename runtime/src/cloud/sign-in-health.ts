import { type Lang, wordsIn } from "./words/index.js";

/**
 * Sign-in health (docs/api.md, "Sign-in health"): planque's check of a hosted-browser connection's saved sign-in on a
 * site (kiff/planque#514), as tafs cloud words it. The apps draw it; they work out nothing themselves.
 */
export type Verdict = "signed_in" | "signed_out" | "unknown";
export type HealthState = "signed_in" | "needs_you_soon" | "stopped" | "unknown";

/** One site's answer from planque's `GET`/`POST …/grants/{id}/health`. */
export interface SiteVerdict { site: string; verdict: Verdict; checked_at?: string; expires_at?: string; why?: string }

export interface Health { state: HealthState; checked_at?: string; needs_you_by?: string; words: { line: string; why?: string } }

/** How close a site's own end of the saved sign-in must be for the owner to hear of it: 3 days. */
export const SOON_MS = 3 * 86400_000;

/** A site's health from planque's verdict: the state, its dates, and the line and Why? in the account's zone. */
export function healthOf(v: SiteVerdict, now: Date, timeZone: string, lang: Lang = "en"): Health {
  const w = wordsIn(lang);
  const checked = parsed(v.checked_at);
  const expires = parsed(v.expires_at);
  // English dates as the apps' other English lines write them ("2 Dec"), never the US order.
  const locale = lang === "en" ? "en-GB" : lang;
  const time = (d: Date) => new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone }).format(d);
  const day = (d: Date) => new Intl.DateTimeFormat(locale, { day: "numeric", month: "short", timeZone }).format(d);
  const weekday = (d: Date) => new Intl.DateTimeFormat(locale, { weekday: "short", day: "numeric", month: "short", timeZone }).format(d);
  const sameDay = (a: Date, b: Date) => day(a) === day(b) && a.getUTCFullYear() === b.getUTCFullYear();
  if (v.verdict === "signed_out" && checked) {
    return { state: "stopped", checked_at: checked.toISOString(), needs_you_by: checked.toISOString(),
      words: { line: w.browse_health_stopped(time(checked)), why: w.browse_health_why_stopped(time(checked)) } };
  }
  if (v.verdict === "signed_in" && checked) {
    if (expires && expires.getTime() - checked.getTime() <= SOON_MS) {
      return { state: "needs_you_soon", checked_at: checked.toISOString(), needs_you_by: expires.toISOString(),
        words: { line: w.browse_health_soon(weekday(expires)), why: w.browse_health_why_soon(time(checked), day(expires)) } };
    }
    return { state: "signed_in", checked_at: checked.toISOString(),
      words: { line: sameDay(checked, now) ? w.browse_health_signed_in_today(time(checked)) : w.browse_health_signed_in_on(day(checked)) } };
  }
  return { state: "unknown", ...(checked ? { checked_at: checked.toISOString() } : {}), words: { line: w.browse_health_unknown } };
}

function parsed(s: string | undefined): Date | undefined {
  if (typeof s !== "string") return undefined;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? undefined : d;
}
