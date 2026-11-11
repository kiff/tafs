/**
 * "Your step" (docs/api.md, "Your step"): a step a taf may never take itself (approving a bank transfer, signing,
 * submitting a form only its owner may), which it prepared and handed to its owner to finish with kiff/agents'
 * your_step tool. The run pauses on it as on a held call, by KIFF's hand-off id; KIFF keeps the hand-off and
 * takes the owner's answer, "done" or "skipped", signed on the phone (kiff.handoff.v1).
 *
 * tafs cloud reads it from the paused run, each time, and stores nothing: what, why and the vendor's link, as
 * the taf wrote them. The link is passed on only as an https URL, with its host apart, so an app never parses
 * a URL to decide what to show; anything else is no link, only the words. Every word is tafs cloud's, in the
 * person's language.
 */
import type { Run } from "./agents.js";
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

/** The vendor as the button names it: the host without a leading www. or app. */
function vendorOf(host: string): string {
  return host.replace(/^(www|app)\./, "");
}

/** The open steps a run waits on, oldest first. */
export function yourStepsOf(r: Run, lang: Lang = "en"): YourStepView[] {
  if (r.status !== "PAUSED") return [];
  const w = wordsIn(lang);
  const out: YourStepView[] = [];
  for (const q of r.requirements ?? []) {
    if (q.tool !== YOUR_STEP_TOOL || !q.exception || q.answered) continue;
    const input = (q.input ?? {}) as { what?: unknown; why?: unknown; link?: unknown };
    const what = typeof input.what === "string" ? input.what.trim() : "";
    const why = typeof input.why === "string" ? input.why.trim() : "";
    const host = linkHost(input.link);
    out.push({
      handoff: q.exception, run: r.id, what, why,
      ...(host ? { link: (input.link as string).trim(), link_host: host } : {}),
      since: r.created, expires: q.expires,
      words: { title: w.your_step_title, done: w.your_step_done, skip: w.your_step_skip, ...(host ? { open: w.your_step_open(vendorOf(host)) } : {}), line: w.your_step_line(what) },
    });
  }
  return out;
}

/** Whether a requirement is a step handed to the owner, not a call KIFF held. */
export function isYourStep(q: { tool?: string }): boolean {
  return q.tool === YOUR_STEP_TOOL;
}
