/**
 * "Your step" (docs/api.md, "Your step"): a step a taf may never take itself (approving a bank transfer, signing,
 * submitting a form only its owner may), which it prepared and handed to its owner to finish with kiff/agents'
 * your_step tool. The run pauses on it as on a held call, by KIFF's hand-off id; KIFF keeps the hand-off and
 * takes the owner's answer, "done" or "skipped", signed on the phone (kiff.handoff.v1).
 *
 * tafs cloud reads it from the paused run, each time, and stores nothing: what, why and the vendor's link, as
 * the taf wrote them. The link is passed on only as an https URL to one of the taf's own vendors (a site its
 * connections reach), with its host apart, so an app never parses a URL to decide what to show: a taf that read
 * a crafted message can't put a look-alike sign-in page behind our button. Anything else is no link, only the
 * words. Every word is tafs cloud's, in the person's language.
 */
import type { Requirement, Run } from "./agents.js";
import type { AccountConnection } from "./connections.js";
import { vendorName } from "./steps.js";
import { type Lang, wordsIn } from "./words/index.js";

/** kiff/agents' tool through which a taf hands its owner a step. */
export const YOUR_STEP_TOOL = "your_step";

/** One step waiting for its owner, as both apps draw it. */
export interface YourStepView {
  /** KIFF's hand-off id: what the app answers (POST /v1/me/handoffs/{id}/answer) and signs. */
  handoff: string;
  run: string;
  what: string;
  why: string;
  /** The vendor's https link, as the taf gave it; absent when it gave none, or anything but https. */
  link?: string;
  /** The link's host, for the button and the line under it. */
  link_host?: string;
  /** When the run paused on it, and when KIFF stops waiting (no answer is never done). */
  since: string;
  expires: string;
  words: { title: string; done: string; skip: string; open?: string; line: string };
}

/** The link's host when it is an https URL to a named host, else undefined. */
export function linkHost(link: unknown): string | undefined {
  if (typeof link !== "string" || !link.trim()) return undefined;
  let u: URL;
  try { u = new URL(link.trim()); } catch { return undefined; }
  if (u.protocol !== "https:" || !u.hostname.includes(".") || u.username || u.password) return undefined;
  if (/^[\d.]+$/.test(u.hostname) || u.hostname.startsWith("[")) return undefined;
  return u.hostname.toLowerCase();
}

/** How long KIFF waits for the owner's answer: its hand-offs expire a week after they're raised. */
export const HANDOFF_WAIT_MS = 7 * 24 * 3600_000;

/** A host's site: its last two labels ("app.stripe.com" is "stripe.com"). */
function siteOf(host: string): string {
  return host.split(".").slice(-2).join(".");
}

/**
 * The sites a taf's connections reach, by planque's list: each connection's server, entry and API host that
 * names a vendor (a cloud function's host names none, so it vouches for nothing).
 */
export function vendorSites(conns: AccountConnection[], agent: string): Set<string> {
  const out = new Set<string>();
  for (const c of conns) {
    if (c.agent !== agent) continue;
    for (const url of [c.server, c.entry, c.api?.url]) {
      const host = linkHost(url);
      if (host && vendorName(`https://${host}/`)) out.add(siteOf(host));
    }
  }
  return out;
}

/** The vendor as the button names it: its name from the link's host ("Stripe" for app.stripe.com), else the host without www. or app. */
function vendorOf(host: string): string {
  return vendorName(`https://${host}/`) || host.replace(/^(www|app)\./, "");
}

/**
 * When the step was handed over: KIFF's hand-off time, a week before it expires, so a run that worked a while
 * before handing it over isn't reminded early. A requirement without a readable expiry falls back to the run's start.
 */
function handedAt(q: Requirement, r: Run): string {
  const expires = Date.parse(q.expires);
  return Number.isNaN(expires) ? r.created : new Date(expires - HANDOFF_WAIT_MS).toISOString();
}

/**
 * The open steps a run waits on, oldest first. `vendors` are the sites the taf's connections reach
 * (vendorSites): a link shows only when its host is one of them. Without them, no link shows.
 */
export function yourStepsOf(r: Run, lang: Lang = "en", vendors: ReadonlySet<string> = new Set()): YourStepView[] {
  if (r.status !== "PAUSED") return [];
  const w = wordsIn(lang);
  const out: YourStepView[] = [];
  for (const q of r.requirements ?? []) {
    if (q.tool !== YOUR_STEP_TOOL || !q.exception || q.answered) continue;
    const input = (q.input ?? {}) as { what?: unknown; why?: unknown; link?: unknown };
    const what = typeof input.what === "string" ? input.what.trim() : "";
    const why = typeof input.why === "string" ? input.why.trim() : "";
    const https = linkHost(input.link);
    const host = https && vendors.has(siteOf(https)) ? https : undefined;
    out.push({
      handoff: q.exception, run: r.id, what, why,
      ...(host ? { link: (input.link as string).trim(), link_host: host } : {}),
      since: handedAt(q, r), expires: q.expires,
      words: { title: w.your_step_title, done: w.your_step_done, skip: w.your_step_skip, ...(host ? { open: w.your_step_open(vendorOf(host)) } : {}), line: w.your_step_line(what) },
    });
  }
  return out;
}

/** Whether a requirement is a step handed to the owner, not a call KIFF held. */
export function isYourStep(q: { tool?: string }): boolean {
  return q.tool === YOUR_STEP_TOOL;
}
