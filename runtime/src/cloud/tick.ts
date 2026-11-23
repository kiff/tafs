/**
 * The scheduled tick's sweeps, one after another, each timed. One log line per sweep,
 * `{"event":"sweep","name","accounts","ms"}`, so a tick that grows toward the function's
 * timeout shows which sweep grew, and one that stops partway shows where. A sweep that
 * throws logs `"failed":true` (never its error, which may name an account) and ends the
 * tick as before.
 *
 * The accounts are read once a tick and handed to every sweep, instead of once per sweep. An account
 * whose deletion is accepted (halted, its erasure under way or to be retried) is left out of that
 * list, so no sweep writes for it while it's being erased, even one running beside the deletions
 * sweep in another group. An account the deletions sweep erased in this tick is taken out before
 * the next sweep too (`erasedBy`).
 *
 * The 5-minute rule sends one event per group (`GROUPS`), each its own invocation with its own
 * timeout, run side by side; `{"sweep":"deletions"}` still runs every sweep in one.
 */
import { type Deps, sweepBriefs, sweepConsent, sweepDeletions, sweepDeliveries, sweepFailingSchedules, sweepFirstRuns, sweepHandOvers, sweepHealth, sweepHeld, sweepLate, sweepMissionLog, sweepReplies, sweepStaleCoach, sweepYourSteps } from "./handler.js";

export type Sweep = [name: string, run: (d: Deps) => Promise<unknown>];

/** The 5-minute sweep's sweeps, in order (tick.ts). */
export const SWEEPS: Sweep[] = [
  ["deletions", sweepDeletions], ["handovers", sweepHandOvers], ["replies", sweepReplies], ["deliveries", sweepDeliveries],
  ["failing", sweepFailingSchedules], ["health", sweepHealth], ["held", sweepHeld], ["late", sweepLate], ["your_steps", sweepYourSteps],
  ["consent", sweepConsent], ["first_runs", sweepFirstRuns], ["stale_coach", sweepStaleCoach], ["mission_log", sweepMissionLog], ["briefs", sweepBriefs],
];

/** The tick's groups, each one event of the 5-minute rule: about even by the first tick's times, deletions first. */
export const GROUPS: Record<string, readonly string[]> = {
  "tick-1": ["deletions", "handovers", "replies", "deliveries"],
  "tick-2": ["failing", "health"],
  "tick-3": ["held", "late", "your_steps"],
  "tick-4": ["consent", "first_runs"],
  "tick-5": ["stale_coach", "mission_log", "briefs"],
};

/** The sweeps an event runs, in order: one group's, every sweep for "deletions", none for another name. */
export function sweepsFor(event: string, all: Sweep[]): Sweep[] {
  if (event === "deletions") return all;
  const names = GROUPS[event];
  return names ? all.filter(([n]) => names.includes(n)) : [];
}

/** The accounts whose own data the deletions sweep erased: its `tafs` step done. */
export function erasedBy(name: string, result: unknown): string[] {
  if (name !== "deletions" || !Array.isArray(result)) return [];
  return result.flatMap((r: { tenant?: unknown; done?: unknown }) =>
    typeof r?.tenant === "string" && Array.isArray(r.done) && r.done.includes("tafs") ? [r.tenant] : []);
}

export async function tick(d: Deps, sweeps: Sweep[], log: (line: string) => void = console.log): Promise<Record<string, unknown>> {
  // An accepted deletion (not one still waiting on KIFF's accept, which KIFF may drop) is halted: nothing sweeps it.
  const deleting = new Set((await d.deletions?.pending() ?? []).filter((x) => !x.accepting).map((x) => x.tenant));
  let listed = (await d.tafs.tenants()).filter((t) => !deleting.has(t));
  const once: Deps = { ...d, tafs: Object.assign(Object.create(d.tafs) as Deps["tafs"], { tenants: async () => listed }) };
  const out: Record<string, unknown> = {};
  for (const [name, run] of sweeps) {
    const start = Date.now();
    const accounts = listed.length;
    let failed = false;
    try {
      out[name] = await run(once);
      const erased = new Set(erasedBy(name, out[name]));
      if (erased.size > 0) listed = listed.filter((t) => !erased.has(t));
    } catch (err) {
      failed = true;
      throw err;
    } finally {
      log(JSON.stringify({ event: "sweep", name, accounts, ms: Date.now() - start, ...(failed ? { failed } : {}) }));
    }
  }
  return out;
}
