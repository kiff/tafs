/**
 * One request, from the dial's rim (docs/api.md, "A request"): what started it, what it did step by
 * step, where its result went and what comes next, every line and time in tafs cloud's words and the
 * account's zone. The apps draw the steps as they are.
 */
import type { Run, SessionEvent } from "./agents.js";
import type { DeliveryView } from "./delivery.js";
import type { Sent } from "./doors/model.js";
import { foldRetried, stepWords, type ToolTitles } from "./steps.js";
import { requestOf, type RequestView, timeIn } from "./work.js";

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
export function fromOf(r: Run): string {
  if (r.requester?.kind === "door" || (r.source && DOOR[r.source])) return `By ${DOOR[r.source ?? ""] ?? "its door"}`;
  if (r.trigger === "schedule" || r.trigger === "cron") return "Its schedule";
  if (r.requester?.kind === "member") return "From its chat";
  return "Run now";
}

/** The waited calls each turn recorded, as kiff/agents writes them on a usage event. */
function callsOf(body: unknown): Array<{ tool: string; error: boolean; notMade: boolean; inputHash?: string }> {
  const t = (body as { timing?: { waited?: unknown } } | undefined)?.timing;
  const waited = Array.isArray(t?.waited) ? (t.waited as Array<{ tool?: unknown; is_error?: unknown; not_made?: unknown; input_hash?: unknown }>) : [];
  return waited.filter((w) => typeof w?.tool === "string").map((w) => ({ tool: w.tool as string, error: w.is_error === true, notMade: w.not_made === true, ...(typeof w.input_hash === "string" && w.input_hash ? { inputHash: w.input_hash } : {}) }));
}

export function requestDetail(r: Run, events: SessionEvent[], sent: Sent[], zone: string, now: Date, titles: ToolTitles = new Map(), again = new Set<string>(), own: DeliveryView[] = []): RequestDetail {
  const view = requestOf(r, zone, now, sent, again, own);
  const from = fromOf(r);
  const at = (t: string) => timeIn(t, zone, now);
  const steps: RequestStep[] = [{ label: "Received", line: `${from}, ${at(r.created)}`, state: "done" }];
  // Each call's step, with its tool and whether it failed, so a failed call Coach retried
  // with the same tool later in the run is folded into the retry's outcome (steps.ts foldRetried).
  const calls: Array<{ tool: string; error?: true; inputHash?: string; step: RequestStep }> = [];
  for (const e of events.filter((x) => x.run === r.id).sort((a, b) => a.seq - b.seq)) {
    if (e.kind === "usage") {
      for (const c of callsOf(e.body)) {
        // A tool nothing names says it worked, with no tool name.
        const words = stepWords(c.tool, titles) ?? "Worked on it";
        const step: RequestStep = { label: words, line: c.notMade ? `Not made · ${at(e.time)}` : c.error ? `Didn't go through · ${at(e.time)}` : at(e.time),
          state: c.notMade ? "not_made" : c.error ? "failed" : "done" };
        calls.push({ tool: c.tool, ...(c.error && !c.notMade ? { error: true as const } : {}), ...(c.inputHash ? { inputHash: c.inputHash } : {}), step });
        steps.push(step);
      }
    } else if (e.kind === "tool_result") {
      const b = (e.body ?? {}) as { state?: unknown };
      if (b.state === "held") steps.push({ label: "Held for your approval", line: `Over its Card · ${at(e.time)}`, state: "waiting" });
    }
  }
  // A failed call Coach retried with the same tool later in the run is folded into the retry's outcome.
  const kept = new Set(foldRetried(calls).map((c) => c.step));
  const folded = steps.filter((s) => !calls.some((c) => c.step === s) || kept.has(s));
  steps.length = 0;
  steps.push(...folded);
  for (const d of view.deliveries) {
    if (d.place === "door_reply") { steps.push({ label: d.state === "sent" ? "Replied" : "Reply not delivered", line: d.line, state: d.state === "sent" ? "done" : "failed" }); continue; }
    // The taf's own places (delivery.ts): delivered, handed on, trying again, or not delivered.
    const label = d.state === "sent" ? "Delivered" : d.state === "handed" ? "Handed on" : d.state === "retrying" ? "Delivering" : "Not delivered";
    steps.push({ label, line: d.line, state: d.state === "sent" || d.state === "handed" ? "done" : d.state === "retrying" ? "waiting" : "failed" });
  }
  switch (view.state) {
    case "retrying":
      steps.push({ label: r.next_at ? `Next try ${at(r.next_at)}` : "Next try soon", line: r.try && r.of && r.try >= r.of ? "If it fails again, this needs you" : `Try ${(r.try ?? 0) + 1}${r.of ? ` of ${r.of}` : ""}`, state: "next" });
      break;
    case "needs_you": steps.push({ label: "Needs you", line: view.line, state: "waiting" }); break;
    case "waiting": steps.push({ label: view.line, state: "waiting", ...(view.waiting_for ? { waits_on: "app" as const } : {}) }); break;
    case "working": steps.push({ label: "Working on it", state: "next" }); break;
    case "done": steps.push({ label: "Done", line: at(r.updated), state: "done" }); break;
    case "stopped": steps.push({ label: "Stopped", line: at(r.updated), state: "failed" }); break;
    case "failed": steps.push({ label: "It failed", line: r.error ?? at(r.updated), state: "failed" }); break;
  }
  return { ...view, from, steps };
}
