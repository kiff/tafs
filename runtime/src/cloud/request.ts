import { type Lang, wordsIn } from "./words/index.js";
/**
 * One request, from the dial's rim (docs/api.md, "A request"): what started it, what it did step by
 * step, where its result went and what comes next, every line and time in tafs cloud's words and the
 * account's zone. The apps draw the steps as they are.
 */
import type { Run, SessionEvent } from "./agents.js";
import type { DeliveryView } from "./delivery.js";
import type { Sent } from "./doors/model.js";
import { foldRetried, stepWords, type ToolTitles } from "./steps.js";
import { failedLine, requestOf, type RequestView, timeIn, whenIn } from "./work.js";

/** One step of a request: what happened, and a line under it with its time, written in the account's zone. */
export interface RequestStep {
  label: string;
  line?: string;
  /** done, failed, not made (refused back unsent), waiting on someone, or still to come. */
  state: "done" | "failed" | "not_made" | "waiting" | "next";
  /** "app" when the wait is for a connected app's event (the slow-tools design), not for the owner: the apps draw it as the app's, never as needing them. */
  waits_on?: "app";
}

export interface RequestDetail extends RequestView {
  /** Where it came from, in one line: "By email", "Its schedule", "Run now". */
  from: string;
  steps: RequestStep[];
}

const DOOR: Record<string, string> = { email: "email", whatsapp: "WhatsApp", slack: "Slack", telegram: "Telegram" };

/** Where a run came from, in words. */
export function fromOf(r: Run, lang: Lang = "en"): string {
  return fromWords(r.trigger, r.source, r.requester?.kind, lang);
}

/** Where a run came from, in words, from what started it: its trigger, its source and who asked. */
export function fromWords(trigger: string | undefined, source: string | undefined, requesterKind: string | undefined, lang: Lang = "en"): string {
  const w = wordsIn(lang);
  if (requesterKind === "door" || (source && DOOR[source])) return w.from_door(source === "email" ? w.door_name("email", "email") : DOOR[source ?? ""]);
  if (trigger === "schedule" || trigger === "cron") return w.from_schedule;
  if (requesterKind === "member") return w.from_chat;
  return w.from_run_now;
}

/** The waited calls each turn recorded, as kiff/agents writes them on a usage event. */
function callsOf(body: unknown): Array<{ tool: string; error: boolean; notMade: boolean; inputHash?: string }> {
  const t = (body as { timing?: { waited?: unknown } } | undefined)?.timing;
  const waited = Array.isArray(t?.waited) ? (t.waited as Array<{ tool?: unknown; is_error?: unknown; not_made?: unknown; input_hash?: unknown }>) : [];
  return waited.filter((w) => typeof w?.tool === "string").map((w) => ({ tool: w.tool as string, error: w.is_error === true, notMade: w.not_made === true, ...(typeof w.input_hash === "string" && w.input_hash ? { inputHash: w.input_hash } : {}) }));
}

export function requestDetail(r: Run, events: SessionEvent[], sent: Sent[], zone: string, now: Date, titles: ToolTitles = new Map(), again = new Set<string>(), own: DeliveryView[] = [], lang: Lang = "en"): RequestDetail {
  const view = requestOf(r, zone, now, sent, again, own, lang);
  const from = fromOf(r, lang);
  const w = wordsIn(lang);
  const at = (t: string) => timeIn(t, zone, now, lang);
  const steps: RequestStep[] = [{ label: w.step_received, line: `${from}, ${at(r.created)}`, state: "done" }];
  // Each call's step, with its tool and whether it failed, so a failed call Coach retried
  // with the same tool later in the run is folded into the retry's outcome (steps.ts foldRetried).
  const calls: Array<{ tool: string; error?: true; inputHash?: string; step: RequestStep }> = [];
  for (const e of events.filter((x) => x.run === r.id).sort((a, b) => a.seq - b.seq)) {
    if (e.kind === "usage") {
      for (const c of callsOf(e.body)) {
        // A tool nothing names says it worked, with no tool name.
        const words = stepWords(c.tool, titles, lang) ?? w.step_worked;
        const step: RequestStep = { label: words, line: c.notMade ? w.step_not_made(at(e.time)) : c.error ? w.step_call_failed(at(e.time)) : at(e.time),
          state: c.notMade ? "not_made" : c.error ? "failed" : "done" };
        calls.push({ tool: c.tool, ...(c.error && !c.notMade ? { error: true as const } : {}), ...(c.inputHash ? { inputHash: c.inputHash } : {}), step });
        steps.push(step);
      }
    } else if (e.kind === "tool_result") {
      const b = (e.body ?? {}) as { state?: unknown };
      if (b.state === "held") steps.push({ label: w.step_held, line: w.step_held_line(at(e.time)), state: "waiting" });
    }
  }
  // A failed call Coach retried with the same tool later in the run is folded into the retry's outcome.
  const kept = new Set(foldRetried(calls).map((c) => c.step));
  const folded = steps.filter((s) => !calls.some((c) => c.step === s) || kept.has(s));
  steps.length = 0;
  steps.push(...folded);
  for (const d of view.deliveries) {
    if (d.place === "door_reply") { steps.push({ label: d.state === "sent" ? w.step_replied : w.step_reply_not_delivered, line: d.line, state: d.state === "sent" ? "done" : "failed" }); continue; }
    // The taf's own places (delivery.ts): delivered, handed on, trying again, or not delivered.
    const label = d.state === "sent" ? w.step_delivered : d.state === "handed" ? w.step_handed : d.state === "retrying" ? w.step_delivering : w.step_not_delivered;
    steps.push({ label, line: d.line, state: d.state === "sent" || d.state === "handed" ? "done" : d.state === "retrying" ? "waiting" : "failed" });
  }
  switch (view.state) {
    case "retrying":
      steps.push({ label: r.next_at ? w.step_next_try_at(whenIn(r.next_at, zone, now, lang)) : w.step_next_try_soon, line: r.try && r.of && r.try >= r.of ? w.step_last_try : w.step_try((r.try ?? 0) + 1, r.of), state: "next" });
      break;
    case "needs_you": steps.push({ label: w.step_needs_you, line: view.line, state: "waiting" }); break;
    case "waiting": steps.push({ label: view.line, state: "waiting", ...(view.waiting_for ? { waits_on: "app" as const } : {}) }); break;
    case "working": steps.push({ label: w.work_working, state: "next" }); break;
    case "done": steps.push({ label: w.step_done, line: at(r.updated), state: "done" }); break;
    case "stopped": steps.push({ label: w.work_stopped, line: r.status === "COMPLETED" ? view.line : at(r.updated), state: "failed" }); break;
    case "failed": steps.push({ label: w.work_failed, line: view.code ? view.line : r.error ? failedLine(r.error, lang) : at(r.updated), state: "failed" }); break;
  }
  return { ...view, from, steps };
}
