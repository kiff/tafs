import { type Lang, wordsIn } from "./words/index.js";
import type { Fetch } from "./kiff.js";

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

/** At most this many sites are read from planque's health answer: planque's own bound on a browser grant's sites. */
const MAX_HEALTH_SITES = 20;

/**
 * What planque keeps of a hosted-browser connection's sign-in checks (`GET …/grants/{id}/health`), read with the person's
 * own KIFF session, which runs no check (kiff/planque#515). Each site's verdict and dates only; anything else planque sends
 * is dropped. Throws when planque can't answer, so a caller shows no health rather than a guess.
 */
export async function readHealth(fetchFn: Fetch, base: string, tenant: string, bearer: string, grant: string): Promise<SiteVerdict[]> {
  const res = await fetchFn(`${base.replace(/\/$/, "")}/v1/accounts/${encodeURIComponent(tenant)}/grants/${encodeURIComponent(grant)}/health`, {
    headers: { accept: "application/json", authorization: `Bearer ${bearer}` },
  });
  if (!res.ok) throw new Error(`planque answered ${res.status} for a sign-in's health`);
  const out = (await res.json().catch(() => ({}))) as { sites?: unknown };
  if (!Array.isArray(out.sites)) throw new Error("planque's health answer has no sites");
  const verdicts = new Set<Verdict>(["signed_in", "signed_out", "unknown"]);
  const str = (x: unknown) => (typeof x === "string" && x.length <= 64 ? x : undefined);
  return (out.sites as Array<Record<string, unknown>>).slice(0, MAX_HEALTH_SITES).flatMap((s) => {
    const site = typeof s?.site === "string" ? s.site.trim().toLowerCase() : "";
    if (!site || site.length > 253 || !verdicts.has(s.verdict as Verdict)) return [];
    const checked = str(s.checked_at), expires = str(s.expires_at);
    return [{ site, verdict: s.verdict as Verdict, ...(checked ? { checked_at: checked } : {}), ...(expires ? { expires_at: expires } : {}) }];
  });
}
