/**
 * The scheduled tick's sweeps, one after another, each timed. One log line per sweep,
 * `{"event":"sweep","name","accounts","ms"}`, so a tick that grows toward the function's
 * timeout shows which sweep grew, and one that stops partway shows where. A sweep that
 * throws logs `"failed":true` (never its error, which may name an account) and ends the
 * tick as before.
 *
 * The accounts are read once a tick and handed to every sweep, instead of once per sweep. An account
 * the deletions sweep erased is taken out of that list before the next sweep, so nothing later in
 * the tick works for it (`erasedBy`).
 */
import type { Deps } from "./handler.js";

export type Sweep = [name: string, run: (d: Deps) => Promise<unknown>];

/** The accounts whose own data the deletions sweep erased: its `tafs` step done. */
export function erasedBy(name: string, result: unknown): string[] {
  if (name !== "deletions" || !Array.isArray(result)) return [];
  return result.flatMap((r: { tenant?: unknown; done?: unknown }) =>
    typeof r?.tenant === "string" && Array.isArray(r.done) && r.done.includes("tafs") ? [r.tenant] : []);
}

export async function tick(d: Deps, sweeps: Sweep[], log: (line: string) => void = console.log): Promise<Record<string, unknown>> {
  let listed = await d.tafs.tenants();
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
