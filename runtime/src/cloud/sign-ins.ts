import type { Fired } from "./agents.js";
import { healthOf, type SiteVerdict } from "./sign-in-health.js";
import { type Lang, wordsIn } from "./words/index.js";

/**
 * Home's sign-in items (docs/api.md, "Needs you, and the run that waits"): a hosted-browser connection's saved sign-in
 * that needs the owner soon, and one that stopped and held a taf's run before it started (kiff/agents' `held_sign_in`).
 * tafs cloud words them; the apps draw them and work out nothing.
 */
export interface SignInItem {
  kind: "sign_in_soon" | "sign_in_stopped";
  grant: string;
  site: string;
  taf: { id: string; name: string };
  needs_you_by?: string;
  run_at?: string;
  words: { title: string; line: string; sign_in: string; later?: string; skip?: string; why?: string };
}

interface TafLike { id: string; name: string; signInLater?: Record<string, string>; signInSkipped?: string }

/** The run a stopped sign-in holds now: the taf's latest firing, when it's `held_sign_in` and not skipped. */
export function heldSignIn(fired: readonly Fired[], taf: Pick<TafLike, "signInSkipped">): (Fired & { grant: string; site: string }) | undefined {
  const last = [...fired].sort((a, b) => b.due.localeCompare(a.due))[0];
  if (!last || last.outcome !== "held_sign_in" || !last.grant || !last.site) return undefined;
  if (taf.signInSkipped && last.due <= taf.signInSkipped) return undefined;
  return last as Fired & { grant: string; site: string };
}

/** The key Later is kept under, on each taf that uses the connection. */
export const laterKey = (grant: string, site: string) => `${grant}|${site}`;

/**
 * The items for the given tafs: each one's held run, then each site of its connection that needs the owner soon (one item
 * per connection and site, under the first taf that uses it), unless put off with Later at the check it shows. Days and
 * times in the account's zone.
 */
export function signInItems(input: {
  tafs: ReadonlyArray<TafLike>; fired: ReadonlyArray<readonly Fired[] | undefined>; grantOf: ReadonlyMap<string, string>;
  health: ReadonlyMap<string, readonly SiteVerdict[]>; now: Date; timeZone: string; lang?: Lang;
}): SignInItem[] {
  const lang = input.lang ?? "en";
  const w = wordsIn(lang);
  const locale = lang === "en" ? "en-GB" : lang;
  const time = (iso: string) => new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone: input.timeZone }).format(new Date(iso));
  const out: SignInItem[] = [];
  const stopped = new Set<string>();
  input.tafs.forEach((t, i) => {
    const held = heldSignIn(input.fired[i] ?? [], t);
    if (!held) return;
    stopped.add(laterKey(held.grant, held.site));
    out.push({ kind: "sign_in_stopped", grant: held.grant, site: held.site, taf: { id: t.id, name: t.name }, run_at: held.due,
      words: { title: w.browse_sign_in_title(held.site), line: w.browse_sign_in_held(t.name, time(held.due)), sign_in: w.browse_sign_in_again, skip: w.browse_sign_in_skip } });
  });
  const seen = new Set<string>();
  for (const t of input.tafs) {
    const grant = input.grantOf.get(t.id);
    if (!grant) continue;
    for (const v of input.health.get(grant) ?? []) {
      const key = laterKey(grant, v.site);
      if (seen.has(key) || stopped.has(key)) continue;
      const h = healthOf(v, input.now, input.timeZone, lang);
      if (h.state !== "needs_you_soon") continue;
      seen.add(key);
      if (h.checked_at && t.signInLater?.[key] === h.checked_at) continue;
      out.push({ kind: "sign_in_soon", grant, site: v.site, taf: { id: t.id, name: t.name }, needs_you_by: h.needs_you_by,
        words: { title: w.browse_sign_in_title(v.site), line: h.words.line, sign_in: w.browse_sign_in_again, later: w.brief_later, ...(h.words.why ? { why: h.words.why } : {}) } });
    }
  }
  return out;
}
